import json
import os
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest

from serena.config.serena_config import (
    JavaRefactorConfig,
    JavaRefactorV2Config,
    LanguageBackend,
    ProjectConfig,
    SerenaConfig,
)
from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.manager import JavaRefactorManager, JavaRefactorRuntimeError
from serena.project import Project
from serena.tools import (
    SUCCESS_RESULT,
    JavaInlineConstantTool,
    JavaInlineLocalVariableTool,
    JavaMoveTopLevelTypeTool,
    JavaSafeDeleteTool,
    JavaSemanticRenameTool,
)
from serena.tools.symbol_tools import RenameSymbolTool, SafeDeleteSymbol
from solidlsp.ls_config import Language


class RecordingJavaRefactorManager:
    def __init__(self) -> None:
        self.shutdown_timeout: float | None = None

    def shutdown(self, timeout: float = 2.0) -> None:
        self.shutdown_timeout = timeout


def test_java_refactor_manager_requires_lsp_backend(tmp_path: Path) -> None:
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.JETBRAINS, [Language.JAVA])

    status = manager.get_status()

    assert status.ready is False
    assert "LSP backend" in status.errors[0]


def test_java_refactor_manager_requires_java_language(tmp_path: Path) -> None:
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.PYTHON])

    status = manager.get_status()

    assert status.ready is False
    assert "java language" in status.errors[0]


def test_java_refactor_manager_resolves_env_jar_first(tmp_path: Path, monkeypatch) -> None:
    env_jar = tmp_path / "env-sidecar.jar"
    env_jar.write_text("fake", encoding="utf-8")
    repo_jar_dir = tmp_path / "java-refactor" / "build" / "libs"
    repo_jar_dir.mkdir(parents=True)
    (repo_jar_dir / "serena-java-refactor-0.1.0.jar").write_text("repo", encoding="utf-8")
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(env_jar))
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA])

    resolved = manager._resolve_sidecar_jar()

    assert resolved == env_jar.resolve()


def test_java_refactor_manager_resolves_repo_jar(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.delenv(JavaRefactorManager.ENV_JAR, raising=False)
    repo_jar_dir = tmp_path / "java-refactor" / "build" / "libs"
    repo_jar_dir.mkdir(parents=True)
    older = repo_jar_dir / "serena-java-refactor-0.0.1.jar"
    newer = repo_jar_dir / "serena-java-refactor-0.1.0.jar"
    older.write_text("older", encoding="utf-8")
    newer.write_text("newer", encoding="utf-8")
    # The locally built sidecar jar is resolved relative to Serena's own checkout root, not the active target project.
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA], repo_root=tmp_path)

    resolved = manager._resolve_sidecar_jar()

    assert resolved == newer


def test_project_shutdown_stops_java_refactor_manager(tmp_path: Path) -> None:
    project_config = ProjectConfig(project_name="java-test", languages=[Language.JAVA])
    project = Project(
        project_root=str(tmp_path), project_config=project_config, serena_config=SerenaConfig(gui_log_window=False, web_dashboard=False)
    )
    manager = RecordingJavaRefactorManager()
    project.java_refactor_manager = manager  # type: ignore[assignment]

    project.shutdown(timeout=1.25)

    assert manager.shutdown_timeout == 1.25
    assert project.java_refactor_manager is None


def test_java_refactor_status_tool_output_json_without_java_project(tmp_path: Path) -> None:
    project_config = ProjectConfig(project_name="python-test", languages=[Language.PYTHON])
    project = Project(
        project_root=str(tmp_path), project_config=project_config, serena_config=SerenaConfig(gui_log_window=False, web_dashboard=False)
    )
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.PYTHON])

    status_json = manager.get_status().to_json()
    payload = json.loads(status_json)

    assert payload["ready"] is False
    assert payload["status"] == "unavailable"
    assert "java language" in payload["errors"][0]
    project.shutdown()


def test_java_refactor_status_surfaces_designed_top_level_contract() -> None:
    # G003: java_refactor_status must report the designed compact readiness payload (refactor-feature-plan.md §Status):
    # status, jdk, buildTool, sourceSets, javaFiles, classpathEntries, lastModelRefreshMs, semanticErrors.
    from serena.java_refactor.models import JavaRefactorStatus

    result = {
        "ready": True,
        "status": "ready",
        "jdk": "21.0.3",
        "buildTool": "gradle",
        "sourceSets": 4,
        "javaFiles": 1832,
        "classpathEntries": 241,
        "lastModelRefreshMs": 1284,
        "semanticErrors": 0,
        "protocolVersion": "1",
        "projectModel": {"discoveryKind": "gradle", "sourceSetCount": 4, "javaFileCount": 1832, "classpath": [], "errors": []},
    }
    status = JavaRefactorStatus.from_protocol_result(result)
    payload = json.loads(status.to_json())
    for key in ("status", "jdk", "buildTool", "sourceSets", "javaFiles", "classpathEntries", "lastModelRefreshMs", "semanticErrors"):
        assert key in payload, f"designed status field missing: {key}"
    assert payload["status"] == "ready"
    assert payload["jdk"] == "21.0.3"
    assert payload["buildTool"] == "gradle"
    assert payload["sourceSets"] == 4
    assert payload["javaFiles"] == 1832
    assert payload["classpathEntries"] == 241
    assert payload["lastModelRefreshMs"] == 1284
    assert payload["semanticErrors"] == 0


def test_java_refactor_status_falls_back_to_nested_model() -> None:
    # Older sidecars emit only the nested projectModel; the designed top-level fields are derived from it so the
    # status contract is honored without a sidecar upgrade.
    from serena.java_refactor.models import JavaRefactorStatus

    result = {
        "ready": False,
        "projectModel": {
            "discoveryKind": "maven",
            "sourceSetCount": 2,
            "javaFileCount": 10,
            "classpath": ["a.jar", "b.jar"],
            "errors": ["E1", "E2"],
        },
    }
    payload = json.loads(JavaRefactorStatus.from_protocol_result(result).to_json())
    assert payload["status"] == "error"
    assert payload["buildTool"] == "maven"
    assert payload["sourceSets"] == 2
    assert payload["javaFiles"] == 10
    assert payload["classpathEntries"] == 2
    assert payload["semanticErrors"] == 2
    assert payload["lastModelRefreshMs"] is None


def test_java_refactor_config_defaults_and_yaml_mapping() -> None:
    default_config = JavaRefactorConfig()

    mapped = JavaRefactorConfig.from_dict(
        {
            "enabled": True,
            "route_generic_rename": True,
            "route_generic_safe_delete": True,
            "allow_incomplete_analysis": True,
            "java_home": "/opt/jdk",
            "max_heap": "2g",
        }
    )

    assert default_config.enabled is False
    assert default_config.preview_default is True
    assert default_config.annotation_processing == "none"
    # V1 plan (refactor-feature-plan.md section 17) mandates these resource defaults.
    assert default_config.max_files == 10000
    assert default_config.max_heap == "2G"
    assert mapped.enabled is True
    assert mapped.route_generic_rename is True
    assert mapped.route_generic_safe_delete is True
    assert mapped.allow_incomplete_analysis is True
    assert mapped.java_home == "/opt/jdk"
    assert mapped.max_heap == "2g"


def test_java_refactor_manager_uses_project_encoding_when_subsection_unset(tmp_path: Path) -> None:
    # G008: the general Serena project encoding flows through to character-offset edits when java_refactor.encoding is
    # unset, instead of silently defaulting to utf-8 (which would corrupt non-UTF-8 source files).
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(),
        project_encoding="ISO-8859-1",
    )
    assert manager._source_encoding() == "ISO-8859-1"

    # The java_refactor.encoding override still wins when both are set.
    overridden = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(encoding="UTF-16"),
        project_encoding="ISO-8859-1",
    )
    assert overridden._source_encoding() == "UTF-16"


def test_java_refactor_config_rejects_invalid_resource_limits() -> None:
    with pytest.raises(ValueError, match="max_files"):
        JavaRefactorConfig.from_dict({"max_files": 0})
    with pytest.raises(ValueError, match="max_files"):
        JavaRefactorConfig.from_dict({"max_files": -5})
    with pytest.raises(ValueError, match="max_heap"):
        JavaRefactorConfig.from_dict({"max_heap": "lots"})


def test_java_refactor_manager_serializes_resource_limits(tmp_path: Path) -> None:
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(max_files=17, max_heap="512m"),
    )

    assert json.loads(manager._sidecar_configuration())["maxFiles"] == 17
    assert manager._java_max_heap() == "512m"


def test_java_refactor_config_maps_project_model_yaml() -> None:
    mapped = JavaRefactorConfig.from_dict(
        {
            "build_tool_mode": "explicit",
            "source_roots": ["src/app/java", "src/shared/java"],
            "classpath": ["libs/dep.jar"],
            "module_path": ["mods/a.jar"],
            "release": "21",
            "source": "21",
            "target": "21",
            "encoding": "ISO-8859-1",
        }
    )

    assert JavaRefactorConfig().source_roots == []
    assert JavaRefactorConfig().release is None
    assert mapped.build_tool_mode == "explicit"
    assert mapped.source_roots == ["src/app/java", "src/shared/java"]
    assert mapped.classpath == ["libs/dep.jar"]
    assert mapped.module_path == ["mods/a.jar"]
    assert mapped.release == "21"
    assert mapped.encoding == "ISO-8859-1"


def test_java_refactor_config_accepts_design_contract_keys() -> None:
    # refactor-feature-plan.md section 17 uses these exact key names; they must be accepted.
    mapped = JavaRefactorConfig.from_dict(
        {
            "build_tool_model": "gradle",
            "include_javadocs_default": True,
            "include_comments_default": True,
            "validate_after_preview": False,
        }
    )

    assert mapped.build_tool_mode == "gradle"
    assert mapped.include_javadocs is True
    assert mapped.include_comments is True
    # G002: validate_after_preview is its OWN preview-validation knob.
    assert mapped.validate_after_preview is False


def test_java_refactor_config_rejects_removed_validate_after_apply() -> None:
    # Blocker regression: validate_after_apply was an inert disable-looking knob (post-apply validation with rollback
    # always runs). It is removed from the V1 config surface and must be rejected with a dedicated error, never
    # silently parsed and ignored.
    with pytest.raises(ValueError, match="validate_after_apply.*removed"):
        JavaRefactorConfig.from_dict({"validate_after_apply": False})
    with pytest.raises(ValueError, match="validate_after_apply.*removed"):
        JavaRefactorConfig.from_dict({"validate_after_apply": True})


def test_java_refactor_config_validate_knobs_are_independent() -> None:
    # The preview-validation knob does not affect the apply-side pre-commit knob and vice versa.
    only_preview_off = JavaRefactorConfig.from_dict({"validate_after_preview": False})
    assert only_preview_off.validate_after_preview is False
    assert only_preview_off.validate_before_apply is True

    only_before_apply_off = JavaRefactorConfig.from_dict({"validate_before_apply": False})
    assert only_before_apply_off.validate_after_preview is True
    assert only_before_apply_off.validate_before_apply is False


def test_java_refactor_config_accepts_implementation_aliases() -> None:
    # The implementation field names remain accepted as compatibility aliases.
    mapped = JavaRefactorConfig.from_dict(
        {
            "build_tool_mode": "maven",
            "include_javadocs": True,
            "include_comments": True,
            "validate_before_apply": False,
        }
    )

    assert mapped.build_tool_mode == "maven"
    assert mapped.include_javadocs is True
    assert mapped.include_comments is True
    assert mapped.validate_before_apply is False


def test_java_refactor_config_rejects_truly_unknown_keys() -> None:
    with pytest.raises(ValueError, match="Unknown java_refactor config key"):
        JavaRefactorConfig.from_dict({"definitely_not_a_real_key": True})


def test_java_refactor_config_rejects_malformed_ignored_patterns() -> None:
    # G001 hardening: ignored_patterns must be a list of strings; a non-list or non-string entry is rejected loudly
    # rather than silently dropped (which would mask a misconfiguration and fall back to the default exclusion set).
    with pytest.raises(ValueError, match="Invalid ignored_patterns"):
        JavaRefactorConfig.from_dict({"ignored_patterns": "build"})
    with pytest.raises(ValueError, match="Invalid ignored_patterns"):
        JavaRefactorConfig.from_dict({"ignored_patterns": ["ok", 3]})
    # A valid list (including empty) is accepted.
    assert JavaRefactorConfig.from_dict({"ignored_patterns": []}).ignored_patterns == []
    assert JavaRefactorConfig.from_dict({"ignored_patterns": ["out"]}).ignored_patterns == ["out"]
    # Blocker 1: glob/pattern strings (design examples target/** and build/**, plus nested globs) are valid patterns and
    # must pass validation unchanged — the sidecar, not Python, decides how each pattern matches.
    assert JavaRefactorConfig.from_dict({"ignored_patterns": ["target/**", "build/**", "src/**/generated/**"]}).ignored_patterns == [
        "target/**",
        "build/**",
        "src/**/generated/**",
    ]


def test_java_refactor_config_rejects_conflicting_alias_and_design_key() -> None:
    with pytest.raises(ValueError, match="Conflicting java_refactor config values"):
        JavaRefactorConfig.from_dict({"build_tool_model": "gradle", "build_tool_mode": "maven"})


def test_project_template_exposes_all_v1_java_refactor_fields() -> None:
    template_path = Path(__file__).resolve().parents[2] / "src" / "serena" / "resources" / "project.template.yml"
    text = template_path.read_text(encoding="utf-8")

    # Design-contract keys (section 17) plus the explicit model-override fields the review flagged as missing.
    for key in (
        "build_tool_model",
        "include_javadocs_default",
        "include_comments_default",
        "validate_after_preview",
        "validate_before_apply",
        "source_roots",
        "classpath",
        "module_path",
        "release",
        "source",
        "target",
        "encoding",
    ):
        assert f"{key}:" in text, f"project.template.yml is missing java_refactor field {key!r}"
    # validate_after_apply was removed from the V1 config surface (post-apply validation always runs); the template
    # must not advertise a knob the implementation rejects.
    assert "validate_after_apply" not in text


def test_java_refactor_manager_serializes_project_model_config(tmp_path: Path) -> None:
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(
            build_tool_mode="explicit",
            source_roots=["src/app/java", "src/shared/java"],
            classpath=["libs/a.jar", "libs/b.jar"],
            module_path=["mods/m.jar"],
            release="21",
            encoding="UTF-16",
        ),
    )

    configuration = json.loads(manager._sidecar_configuration())

    assert configuration["buildToolMode"] == "explicit"
    assert configuration["sourceRoots"] == ["src/app/java", "src/shared/java"]
    assert configuration["classpath"] == ["libs/a.jar", "libs/b.jar"]
    assert configuration["modulePath"] == ["mods/m.jar"]
    assert configuration["release"] == "21"
    assert configuration["encoding"] == "UTF-16"


def test_java_refactor_manager_serializes_nested_v2_config(tmp_path: Path) -> None:
    # G006: v2 is now a strictly-typed dataclass. Build it via from_dict (the YAML-load path) and assert the manager
    # serializes it back to the snake_case wire object the sidecar's expandNestedV2Config accepts.
    v2_config = JavaRefactorV2Config.from_dict(
        {
            "enabled": True,
            "sessions": {"max_open_sessions": 16, "session_ttl_minutes": 30, "require_revision_match_on_apply": True},
            "generated_sources": {"read": True, "edit": False},
            "lombok": {"allow": True},
            "access": {"allow_access_widening": False},
            "hierarchy": {"enabled": True, "allow_public_api_change": False},
            "operation_defaults": {"visibility": "private"},
            "extract_method": {"enabled": True, "allow_multiple_outputs": False},
            "extract_interface": {"enabled": True, "replace_usages_default": False},
            "encapsulate_field": {"enabled": True, "rewrite_internal_usages_default": False},
            "inline_method": {"enabled": True, "max_call_sites": 100},
            "diagnostics": {"report_delta": True},
            "imports": {"preserve_static_imports": True},
        }
    )
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, v2=v2_config),
    )

    configuration = json.loads(manager._sidecar_configuration())

    serialized_v2 = configuration["java_refactor"]["v2"]
    assert serialized_v2["enabled"] is True
    assert serialized_v2["sessions"] == {
        "max_open_sessions": 16,
        "session_ttl_minutes": 30,
        "require_revision_match_on_apply": True,
    }
    assert serialized_v2["extract_method"] == {
        "enabled": True,
        "allow_multiple_outputs": False,
        "allow_control_flow_exits": False,
    }
    assert serialized_v2["inline_method"]["max_call_sites"] == 100
    assert serialized_v2["generated_sources"] == {"read": True, "edit": False}


def test_java_refactor_manager_omits_auto_build_tool_mode(tmp_path: Path) -> None:
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(build_tool_mode="auto"),
    )

    assert "buildToolMode" not in json.loads(manager._sidecar_configuration())


def test_java_refactor_manager_forwards_jdtls_settings_when_enabled(tmp_path: Path) -> None:
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(use_jdtls_settings=True),
        jdtls_settings={
            "maven_user_settings": "/home/me/.m2/settings.xml",
            "gradle_user_home": "/home/me/.gradle",
            "gradle_java_home": "/opt/jdk",
            "gradle_wrapper_enabled": True,
        },
    )

    configuration = json.loads(manager._sidecar_configuration())

    assert configuration["mavenUserSettings"] == "/home/me/.m2/settings.xml"
    assert configuration["gradleUserHome"] == "/home/me/.gradle"
    assert configuration["gradleJavaHome"] == "/opt/jdk"
    assert configuration["gradleWrapperEnabled"] is True


def test_java_refactor_manager_omits_jdtls_settings_when_disabled(tmp_path: Path) -> None:
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(use_jdtls_settings=False),
        jdtls_settings={
            "maven_user_settings": "/home/me/.m2/settings.xml",
            "gradle_user_home": "/home/me/.gradle",
            "gradle_java_home": "/opt/jdk",
            "gradle_wrapper_enabled": True,
        },
    )

    configuration = json.loads(manager._sidecar_configuration())

    for key in ("mavenUserSettings", "gradleUserHome", "gradleJavaHome", "gradleWrapperEnabled"):
        assert key not in configuration


def test_java_refactor_manager_forwards_nested_model_override(tmp_path: Path) -> None:
    model = {
        "modules": [
            {
                "project": ":app",
                "sourceSets": [
                    {
                        "name": "main",
                        "srcDirs": ["src/main/java"],
                        "generatedRoots": ["build/generated/sources/annotations"],
                        "outputDirs": ["build/classes/java/main"],
                        "classpath": ["libs/api.jar"],
                        "modulePath": [],
                        "annotationProcessorPath": ["libs/lombok.jar"],
                        "release": "21",
                        "source": "21",
                        "target": "21",
                        "encoding": "UTF-8",
                        "dependsOnProjects": [],
                        "compilerArgs": ["-parameters"],
                    }
                ],
            }
        ]
    }
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        JavaRefactorConfig(enabled=True, model=model),
    )

    configuration = json.loads(manager._sidecar_configuration())

    assert configuration["java_refactor"]["model"] == model


def test_java_refactor_manager_ignores_absent_jdtls_settings(tmp_path: Path) -> None:
    # use_jdtls_settings defaults true; with no Java LS settings configured the payload must not crash or add keys.
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA])

    configuration = json.loads(manager._sidecar_configuration())

    for key in ("mavenUserSettings", "gradleUserHome", "gradleJavaHome", "gradleWrapperEnabled"):
        assert key not in configuration


def test_java_refactor_manager_every_knob_influences_sidecar_payload_or_behavior(tmp_path: Path) -> None:
    """Consolidated "no knob is inert" guard: each of the five V1 knobs the review flagged demonstrably changes either
    the sidecar discovery payload or a documented refactor behavior. Inverting any knob must change observable output.
    """
    settings = {"maven_user_settings": "/home/me/.m2/settings.xml"}

    # build_tool_mode: selects discovery mode (emitted into the payload unless "auto").
    plain = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA], JavaRefactorConfig(build_tool_mode="plain"))
    auto = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA], JavaRefactorConfig(build_tool_mode="auto"))
    assert json.loads(plain._sidecar_configuration())["buildToolMode"] == "plain"
    assert "buildToolMode" not in json.loads(auto._sidecar_configuration())

    # include_javadocs / include_comments: drive the rename request defaults sent to the sidecar.
    rename = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], JavaRefactorConfig(include_javadocs=True, include_comments=True)
    )
    params: dict = {"relativePath": "A.java", "line": 1, "column": 1, "newName": "B"}
    params["includeJavadocs"] = rename._config.include_javadocs
    params["includeComments"] = rename._config.include_comments
    assert params["includeJavadocs"] is True and params["includeComments"] is True

    # validate_before_apply: resolved as the staged pre-commit javac validation gate.
    assert JavaRefactorConfig(validate_before_apply=True).validate_before_apply is True
    assert JavaRefactorConfig(validate_before_apply=False).validate_before_apply is False

    # use_jdtls_settings: gates whether the Java LS settings reach the discovery payload.
    on = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], JavaRefactorConfig(use_jdtls_settings=True), jdtls_settings=settings
    )
    off = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], JavaRefactorConfig(use_jdtls_settings=False), jdtls_settings=settings
    )
    assert json.loads(on._sidecar_configuration())["mavenUserSettings"] == settings["maven_user_settings"]
    assert "mavenUserSettings" not in json.loads(off._sidecar_configuration())


def test_java_refactor_manager_json_config_preserves_separator_characters(tmp_path: Path) -> None:
    classpath_entries = ["libs/with;semicolon.jar", f"libs/with{os.pathsep}sep.jar"]
    source_root_entries = [f"src/with{os.pathsep}sep/java", "src/with;semicolon/java"]
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(
            classpath=classpath_entries,
            source_roots=source_root_entries,
        ),
    )

    configuration = json.loads(manager._sidecar_configuration())

    assert configuration["classpath"] == classpath_entries
    assert configuration["sourceRoots"] == source_root_entries


def _attach_fake_process(client: JavaRefactorClient, python_body: str) -> subprocess.Popen:
    """Replaces the client's process with a controllable Python subprocess for transport-level tests."""
    process = subprocess.Popen(
        [sys.executable, "-c", python_body],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    client._process = process
    return process


def test_java_refactor_client_request_times_out_and_terminates_sidecar(tmp_path: Path) -> None:
    jar = tmp_path / "sidecar.jar"
    jar.write_text("fake", encoding="utf-8")
    client = JavaRefactorClient(jar, request_timeout=0.3)
    process = _attach_fake_process(client, "import time; time.sleep(30)")
    try:
        with pytest.raises(TimeoutError):
            client._read_response_line()
        assert process.wait(timeout=5) is not None
        assert client._process is None
    finally:
        if process.poll() is None:
            process.kill()


def test_java_refactor_client_drains_stderr_without_blocking(tmp_path: Path) -> None:
    jar = tmp_path / "sidecar.jar"
    jar.write_text("fake", encoding="utf-8")
    client = JavaRefactorClient(jar)
    process = _attach_fake_process(client, "import sys, time; sys.stderr.write('boom failure\\n'); sys.stderr.flush(); time.sleep(0.5)")
    try:
        client._start_stderr_drain()
        deadline = time.monotonic() + 5
        while "boom failure" not in client._drained_stderr() and time.monotonic() < deadline:
            time.sleep(0.05)
        assert "boom failure" in client._drained_stderr()
    finally:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=5)


def test_java_refactor_fixtures_and_bundled_resource_exist() -> None:
    fixture_root = Path("test/resources/repos/java_refactor")
    expected = {
        "plain",
        "maven-basic",
        "gradle-basic",
        "multi-module-maven",
        "multi-source-set-gradle",
        "modules",
        "lombok-lite",
        "generated-code",
    }

    assert expected == {path.name for path in fixture_root.iterdir() if path.is_dir()}
    assert Path("src/serena/resources/java-refactor/serena-java-refactor.jar").is_file()


def test_java_refactor_manager_refuses_when_disabled(tmp_path: Path) -> None:
    # The opt-in gate must apply even to direct Java tool calls (not only generic routing).
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=False)
    )

    result = manager.semantic_rename("src/main/java/demo/Main.java", 1, 1, "renamed", apply=True)

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "java_refactor_disabled"


def test_impact_report_tool_refuses_when_disabled(tmp_path: Path) -> None:
    # G011: the read-only impact-report tool honors the same opt-in gate; it never touches a workspace when off.
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=False)
    )

    result = manager.transformation_workspace_impact_report("any-workspace")

    assert result["accepted"] is False
    assert result["operation"] == "impactReport"
    assert result["refusal"]["code"] == "java_refactor_disabled"


def test_v3_scan_bridges_refuse_when_disabled(tmp_path: Path) -> None:
    # R13g / R13r: the read-only V3 scan bridges honor the opt-in gate and refuse with mode "scan" (NOT "apply").
    # This pins the disabled-refusal contract so a future helper-name collision (the kind that silently turned a
    # read-only scan's mode into "apply") is caught instead of passing only the accepted-path mocks.
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=False)
    )

    cases = [
        (lambda: manager.transformation_graph(), "transformationGraph"),
        (lambda: manager.resource_find_references("com.acme.Service"), "resourceProviders"),
        (lambda: manager.resource_plan_edits(type_fqn_map={"a.B": "c.D"}, package_map=None), "resourceProviders"),
        (lambda: manager.framework_detect(), "frameworkDetect"),
    ]
    for call, operation in cases:
        result = call()
        assert result["accepted"] is False, operation
        assert result["operation"] == operation, operation
        assert result["mode"] == "scan", operation
        assert result["refusal"]["code"] == "java_refactor_v3_disabled", operation


def test_v3_workspace_lifecycle_bridges_refuse_when_disabled(tmp_path: Path) -> None:
    # R02: every transformation-workspace lifecycle bridge honors the opt-in gate, refusing with the
    # ``transformationWorkspace`` operation and the lifecycle-specific mode (so the apply gate cannot be reached on a
    # disabled engine, and the disabled-refusal mode is not corrupted by a shared helper).
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=False)
    )

    cases = [
        (lambda: manager.transformation_workspace_create(), "create"),
        (lambda: manager.transformation_workspace_add_session("ws", "renameSymbol", {}), "add_session"),
        (lambda: manager.transformation_workspace_add_operation("ws", "renamePackage", {}), "add_operation"),
        (lambda: manager.transformation_workspace_preview("ws"), "preview"),
        (lambda: manager.transformation_workspace_apply("ws"), "apply"),
        (lambda: manager.transformation_workspace_cancel("ws"), "cancel"),
        (lambda: manager.transformation_workspace_list(), "list"),
    ]
    for call, mode in cases:
        result = call()
        assert result["accepted"] is False, mode
        assert result["operation"] == "transformationWorkspace", mode
        assert result["mode"] == mode, mode
        assert result["refusal"]["code"] == "java_refactor_disabled", mode


def test_impact_report_include_flags_are_presentation_only(tmp_path: Path, monkeypatch) -> None:
    # B1/G011: include_tests / include_resources are PURE PRESENTATION PROJECTIONS over the fully-computed report.
    # The sidecar always computes the resource/test facts and the risk roll-up; turning a flag off only trims the
    # returned envelope and must NEVER change the risk classification (a risky change cannot be hidden by a flag).
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: object())

    full_report = {
        "java": {"fileCount": 1},
        "resources": {"references": ["beans.xml"]},
        "api": {"crossings": []},
        "tests": {"impacted": ["FooTest"]},
        "risk": {"level": "needs_review", "warnings": ["touches a resource-wired type"]},
    }

    def _fake_impact(workspace_id, build):
        return {
            "accepted": True,
            "mode": "impact_report",
            "workspaceId": workspace_id,
            "operation": "renameMember",
            "projectRevision": "rev-1",
            "javacFactsValidated": True,
            "risk": "needs_review",
            "report": dict(full_report),
        }

    monkeypatch.setattr(manager.transformation_workspaces, "impact_report", _fake_impact)

    full = manager.transformation_workspace_impact_report("w1")
    assert set(full["report"]) == {"java", "resources", "api", "tests", "risk"}

    projected = manager.transformation_workspace_impact_report("w1", include_tests=False, include_resources=False)
    assert "tests" not in projected["report"], projected
    assert "resources" not in projected["report"], projected
    # Sections the flags do not touch — and crucially the risk roll-up — are unchanged.
    assert projected["report"]["java"] == {"fileCount": 1}
    assert projected["report"]["api"] == {"crossings": []}
    assert projected["report"]["risk"] == {"level": "needs_review", "warnings": ["touches a resource-wired type"]}
    assert projected["risk"] == "needs_review"


class _RealManagerTool:
    """Minimal tool ``self`` whose ``_get_manager`` returns a real :class:`JavaRefactorManager`.

    Lets a test drive the actual registered V3 tool ``apply`` methods end-to-end (real manager, real workspace
    engine) without the Tool/agent framework. ``_finalize_result`` / ``_limit_length`` are identity passthroughs so
    the test reads back the forwarded dict verbatim.
    """

    def __init__(self, manager) -> None:
        self._manager = manager

    def _get_manager(self):
        return self._manager

    def _finalize_result(self, result):
        return result

    def _limit_length(self, result, max_answer_chars):
        return result


def test_impact_report_consumes_workspace_created_via_exposed_surface(tmp_path: Path, monkeypatch) -> None:
    # R03: end-to-end reachability through the EXPOSED V3 tool surface. The workspace id that java_impact_report
    # consumes is minted by java_create_transformation_workspace (never hard-coded), a member is enrolled via
    # java_add_workspace_session, and that SAME id is fed to java_impact_report, which ACCEPTS it and returns a
    # computed report. This proves impact_report no longer depends on an uncreatable workspace id. The only stub is
    # the sidecar facts boundary; the workspace manager is the real StubDriver-backed engine.
    from serena.java_refactor_v3 import TransformationWorkspaceManager, impact_facts_client
    from serena.tools.java_refactor_v3_tools import (
        JavaAddWorkspaceSessionTool,
        JavaCreateTransformationWorkspaceTool,
        JavaImpactReportTool,
    )
    from test.serena.test_java_refactor_v3_workspace import StubDriver, _text_envelope, _write

    service = "src/main/java/com/acme/app/Service.java"
    _write(tmp_path, service, "package com.acme.app;\npublic class Service { int x = 1; }\n")

    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: object())
    # Real workspace engine; only the live Java sidecar's session planning is replaced by a programmable double.
    driver = StubDriver(tmp_path)
    manager._transformation_workspaces = TransformationWorkspaceManager(driver)

    tool = _RealManagerTool(manager)

    # 1. Create the workspace via the exposed tool — its id is returned, not hard-coded.
    created = cast(Any, JavaCreateTransformationWorkspaceTool.apply)(tool)
    assert created["accepted"] is True, created
    assert created["operation"] == "transformationWorkspace" and created["mode"] == "create"
    workspace_id = created["workspaceId"]
    assert workspace_id

    # 2. Enroll a member via the exposed tool so the workspace composes a real edit.
    driver.program(_text_envelope(tmp_path, "s1", service, 53, 54, "9"))
    added = cast(Any, JavaAddWorkspaceSessionTool.apply)(tool, workspace_id=workspace_id, operation="renameMember")
    assert added["accepted"] is True, added

    # 3. Stub ONLY the sidecar facts boundary; the bridge composes the real workspace and builds a real report.
    facts = {
        "accepted": True,
        "operation": "impact.facts",
        "touchedPaths": [service],
        "sourceRoots": {"main": ["src/main/java"], "test": ["src/test/java"], "resources": ["src/main/resources"]},
        "touchedTypes": [{"fqn": "com.acme.app.Service", "relativePath": service, "publicApi": True, "testSource": False}],
        "incomingRefs": [],
        "resourceRefs": [],
        "stats": {"touchedTypes": 1, "incomingRefs": 0, "resourceRefs": 0},
    }
    monkeypatch.setattr(impact_facts_client.ImpactFactsClient, "facts", lambda self, paths: facts)

    # 4. Feed the SAME id from step 1 to the exposed impact-report tool — accepted, not an unknown-workspace refusal.
    report = cast(Any, JavaImpactReportTool.apply)(tool, workspace_id=workspace_id)
    assert report["accepted"] is True, report
    assert report["workspaceId"] == workspace_id
    assert report["mode"] == "impact_report"
    assert report["report"]["java"]["fileCount"] == 1, report["report"]


def test_impact_report_refuses_workspace_id_that_was_never_created(tmp_path: Path, monkeypatch) -> None:
    # R03 (negative): the create step is load-bearing — an id the exposed surface never minted is refused as unknown,
    # so impact_report genuinely depends on a real, creatable workspace rather than accepting any string.
    from serena.java_refactor_v3 import TransformationWorkspaceManager
    from serena.tools.java_refactor_v3_tools import JavaImpactReportTool
    from test.serena.test_java_refactor_v3_workspace import StubDriver

    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: object())
    manager._transformation_workspaces = TransformationWorkspaceManager(StubDriver(tmp_path))

    result = cast(Any, JavaImpactReportTool.apply)(_RealManagerTool(manager), workspace_id="never-created")
    assert result["accepted"] is False, result
    assert result["workspaceId"] == "never-created"


def _make_routing_tool(tool_cls, project_config, recorded: dict, monkeypatch, symbol):
    """Builds a symbol tool wired to a fake project/agent and a manager stub that records routed positions."""
    project = SimpleNamespace(project_config=project_config, project_root="/tmp/proj")
    agent = SimpleNamespace(
        get_active_project_or_raise=lambda: project,
        get_language_backend=lambda: LanguageBackend.LSP,
    )
    tool = object.__new__(tool_cls)
    tool.agent = agent  # type: ignore[attr-defined]

    retriever = SimpleNamespace(find_unique=lambda *args, **kwargs: symbol)
    tool.create_language_server_symbol_retriever = lambda: retriever  # type: ignore[attr-defined]

    def record(relative_path, line, column, *args, **kwargs):
        recorded["relative_path"] = relative_path
        recorded["line"] = line
        recorded["column"] = column
        return {"accepted": True}

    fake_manager = SimpleNamespace(semantic_rename=record, safe_delete=record)
    # Generic routing obtains the manager through the Component.create_java_refactor_client() integration point.
    monkeypatch.setattr(tool, "create_java_refactor_client", lambda: fake_manager, raising=False)
    return tool


def test_generic_rename_routing_converts_zero_based_position_to_one_based(monkeypatch) -> None:
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_rename=True, preview_default=True))
    symbol = SimpleNamespace(line=4, column=8)
    tool = _make_routing_tool(RenameSymbolTool, config, recorded, monkeypatch, symbol)

    tool.apply("Demo/method", "src/Demo.java", "renamed")

    # LSP positions are zero-based; the sidecar must receive one-based line/column.
    assert recorded["line"] == 5
    assert recorded["column"] == 9


def test_generic_safe_delete_routing_converts_zero_based_position_to_one_based(monkeypatch) -> None:
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_safe_delete=True, preview_default=True))
    symbol = SimpleNamespace(line=4, column=8, relative_path="src/Demo.java", get_name_path=lambda: "Demo/method")
    tool = _make_routing_tool(SafeDeleteSymbol, config, recorded, monkeypatch, symbol)

    tool.apply("Demo/method", "src/Demo.java")

    assert recorded["line"] == 5
    assert recorded["column"] == 9


def _make_routing_tool_with_lsp(tool_cls, project_config, recorded: dict, monkeypatch, symbol, java_result):
    """Routing tool wired so both the Java engine path and the LSP fallback are observable.

    ``recorded["java_called"]`` / ``recorded["lsp_called"]`` flip when each path runs, so tests can assert that routing
    only diverts to the Java engine when configured and otherwise preserves the existing LSP behavior.
    """
    project = SimpleNamespace(project_config=project_config, project_root="/tmp/proj")
    agent = SimpleNamespace(
        get_active_project_or_raise=lambda: project,
        get_language_backend=lambda: LanguageBackend.LSP,
    )
    tool = object.__new__(tool_cls)
    tool.agent = agent  # type: ignore[attr-defined]
    recorded.setdefault("java_called", False)
    recorded.setdefault("lsp_called", False)

    language_server = SimpleNamespace(request_references=lambda *a, **k: [])
    retriever = SimpleNamespace(
        find_unique=lambda *a, **k: symbol,
        get_language_server=lambda *a, **k: language_server,
    )
    tool.create_language_server_symbol_retriever = lambda: retriever  # type: ignore[attr-defined]

    def java_call(relative_path, line, column, *args, **kwargs):
        recorded["java_called"] = True
        recorded["apply"] = kwargs.get("apply")
        return java_result

    monkeypatch.setattr(
        tool, "create_java_refactor_client", lambda: SimpleNamespace(semantic_rename=java_call, safe_delete=java_call), raising=False
    )

    def lsp_rename(name_path, relative_path, new_name):
        recorded["lsp_called"] = True
        return "LSP_RENAMED"

    def lsp_delete(name_path, relative_file_path):
        recorded["lsp_called"] = True

    monkeypatch.setattr(
        tool, "create_ls_code_editor", lambda: SimpleNamespace(rename_symbol=lsp_rename, delete_symbol=lsp_delete), raising=False
    )
    return tool


def test_generic_rename_routing_disabled_uses_lsp_fallback(monkeypatch) -> None:
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_rename=False))
    symbol = SimpleNamespace(line=4, column=8)
    tool = _make_routing_tool_with_lsp(RenameSymbolTool, config, recorded, monkeypatch, symbol, {"accepted": True})

    result = tool.apply("Demo/method", "src/Demo.java", "renamed")

    assert recorded["java_called"] is False
    assert recorded["lsp_called"] is True
    assert result == "LSP_RENAMED"


def test_generic_rename_non_java_uses_lsp_fallback(monkeypatch) -> None:
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_rename=True))
    symbol = SimpleNamespace(line=4, column=8)
    tool = _make_routing_tool_with_lsp(RenameSymbolTool, config, recorded, monkeypatch, symbol, {"accepted": True})

    result = tool.apply("Demo/method", "src/Demo.py", "renamed")

    assert recorded["java_called"] is False
    assert recorded["lsp_called"] is True
    assert result == "LSP_RENAMED"


def test_generic_rename_routing_applies_when_preview_default_false(monkeypatch) -> None:
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_rename=True, preview_default=False))
    symbol = SimpleNamespace(line=4, column=8)
    tool = _make_routing_tool_with_lsp(RenameSymbolTool, config, recorded, monkeypatch, symbol, {"accepted": True})

    tool.apply("Demo/method", "src/Demo.java", "renamed")

    assert recorded["java_called"] is True
    assert recorded["lsp_called"] is False
    # preview_default False => the routed generic tool applies the edit rather than only previewing.
    assert recorded["apply"] is True


def _make_direct_java_tool(tool_cls, project_config, recorded: dict, monkeypatch):
    """Builds a direct Java tool wired to a fake project/agent and a manager stub recording the apply flag."""
    project = SimpleNamespace(project_config=project_config, project_root="/tmp/proj")
    agent = SimpleNamespace(
        get_active_project_or_raise=lambda: project,
        get_language_backend=lambda: LanguageBackend.LSP,
    )
    tool = object.__new__(tool_cls)
    tool.agent = agent  # type: ignore[attr-defined]
    symbol = SimpleNamespace(line=4, column=8)
    retriever = SimpleNamespace(find_unique=lambda *args, **kwargs: symbol)
    tool.create_language_server_symbol_retriever = lambda: retriever  # type: ignore[attr-defined]

    def record(*args, **kwargs):
        recorded["apply"] = kwargs.get("apply")
        return {"accepted": True}

    fake_manager = SimpleNamespace(
        semantic_rename=record,
        safe_delete=record,
        move_top_level_type=record,
        inline_local_variable=record,
        inline_constant=record,
        v2_refactor_session=lambda operation, params, apply=False, validate=None: {
            "accepted": True,
            "operation": operation,
            "params": params,
            "apply": apply,
            "validate": validate,
        },
    )
    monkeypatch.setattr(tool, "create_java_refactor_client", lambda: fake_manager, raising=False)
    return tool


_DIRECT_JAVA_TOOL_CALLS = [
    pytest.param(JavaSemanticRenameTool, {"name_path": "Demo/m", "relative_path": "src/Demo.java", "new_name": "renamed"}, id="rename"),
    pytest.param(JavaSafeDeleteTool, {"name_path": "Demo/m", "relative_path": "src/Demo.java"}, id="safe-delete"),
    pytest.param(
        JavaMoveTopLevelTypeTool, {"name_path": "Demo", "relative_path": "src/Demo.java", "target_package": "demo.moved"}, id="move"
    ),
    pytest.param(JavaInlineLocalVariableTool, {"name_path": "Demo/m/x", "relative_path": "src/Demo.java"}, id="inline-local"),
    pytest.param(JavaInlineConstantTool, {"name_path": "Demo/X", "relative_path": "src/Demo.java"}, id="inline-constant"),
]


@pytest.mark.parametrize(("tool_cls", "call_kwargs"), _DIRECT_JAVA_TOOL_CALLS)
def test_direct_java_tools_apply_when_preview_default_false(monkeypatch, tool_cls, call_kwargs) -> None:
    # G004 (consistent preview_default): a direct Java tool call that OMITS preview must honor the project's
    # java_refactor.preview_default, exactly like the generic rename_symbol/safe_delete_symbol routing.
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, preview_default=False))
    tool = _make_direct_java_tool(tool_cls, config, recorded, monkeypatch)

    tool.apply(**call_kwargs)

    assert recorded["apply"] is True


@pytest.mark.parametrize(("tool_cls", "call_kwargs"), _DIRECT_JAVA_TOOL_CALLS)
def test_direct_java_tools_preview_when_preview_default_true(monkeypatch, tool_cls, call_kwargs) -> None:
    # G004: preview_default True (the stock default) keeps an omitted preview in preview mode.
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, preview_default=True))
    tool = _make_direct_java_tool(tool_cls, config, recorded, monkeypatch)

    tool.apply(**call_kwargs)

    assert recorded["apply"] is False


@pytest.mark.parametrize(("tool_cls", "call_kwargs"), _DIRECT_JAVA_TOOL_CALLS)
def test_direct_java_tools_explicit_preview_overrides_preview_default(monkeypatch, tool_cls, call_kwargs) -> None:
    # G004: an explicit caller-passed preview always wins over the project default, in both directions.
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, preview_default=False))
    tool = _make_direct_java_tool(tool_cls, config, recorded, monkeypatch)
    tool.apply(preview=True, **call_kwargs)
    assert recorded["apply"] is False

    recorded.clear()
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, preview_default=True))
    tool = _make_direct_java_tool(tool_cls, config, recorded, monkeypatch)
    tool.apply(preview=False, **call_kwargs)
    assert recorded["apply"] is True


def test_direct_java_tools_default_to_preview_when_config_unresolvable(monkeypatch) -> None:
    # G004: when the project configuration cannot be resolved, the safe default is preview mode (no mutation).
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, preview_default=False))
    tool = _make_direct_java_tool(JavaSemanticRenameTool, config, recorded, monkeypatch)

    def raise_no_project():
        raise RuntimeError("no active project")

    tool.agent.get_active_project_or_raise = raise_no_project

    tool.apply(name_path="Demo/m", relative_path="src/Demo.java", new_name="renamed")

    assert recorded["apply"] is False


def test_generic_safe_delete_routing_surfaces_engine_refusal(monkeypatch) -> None:
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_safe_delete=True, preview_default=True))
    symbol = SimpleNamespace(line=4, column=8, relative_path="src/Demo.java", get_name_path=lambda: "Demo/method")
    refusal = {"accepted": False, "refusal": {"code": "semantic_references_exist", "message": "still referenced"}}
    tool = _make_routing_tool_with_lsp(SafeDeleteSymbol, config, recorded, monkeypatch, symbol, refusal)

    result = tool.apply("Demo/method", "src/Demo.java")

    # A Java-engine refusal is surfaced to the caller; it must NOT silently fall back to the LSP delete.
    assert recorded["java_called"] is True
    assert recorded["lsp_called"] is False
    assert "semantic_references_exist" in result


def test_generic_rename_routing_surfaces_engine_refusal(monkeypatch) -> None:
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_rename=True, preview_default=True))
    symbol = SimpleNamespace(line=4, column=8)
    refusal = {"accepted": False, "refusal": {"code": "rename_conflict", "message": "name collides with an overload"}}
    tool = _make_routing_tool_with_lsp(RenameSymbolTool, config, recorded, monkeypatch, symbol, refusal)

    result = tool.apply("Demo/method", "src/Demo.java", "renamed")

    # A Java-engine refusal (the engine analyzed and refused) must NOT silently fall back to the LSP rename.
    assert recorded["java_called"] is True
    assert recorded["lsp_called"] is False
    assert "rename_conflict" in result


@pytest.mark.parametrize(
    "error",
    [
        JavaRefactorRuntimeError("sidecar requires Serena's LSP backend"),
        RuntimeError("sidecar crashed mid-request"),
        FileNotFoundError("bundled sidecar jar is missing"),
    ],
)
def test_generic_rename_falls_back_to_lsp_when_engine_unavailable(monkeypatch, error) -> None:
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_rename=True, preview_default=True))
    symbol = SimpleNamespace(line=4, column=8)
    tool = _make_routing_tool_with_lsp(RenameSymbolTool, config, recorded, monkeypatch, symbol, {"accepted": True})

    def raising_client():
        raise error

    monkeypatch.setattr(tool, "create_java_refactor_client", raising_client, raising=False)

    result = tool.apply("Demo/method", "src/Demo.java", "renamed")

    # Engine unavailable (disabled/crash/startup failure/missing jar) must fall through to the LSP rename path,
    # not return a java_refactor_unavailable refusal.
    assert recorded["java_called"] is False
    assert recorded["lsp_called"] is True
    assert result == "LSP_RENAMED"
    assert "java_refactor_unavailable" not in result


def test_generic_rename_falls_back_to_lsp_when_engine_raises_mid_call(monkeypatch) -> None:
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_rename=True, preview_default=True))
    symbol = SimpleNamespace(line=4, column=8)
    tool = _make_routing_tool_with_lsp(RenameSymbolTool, config, recorded, monkeypatch, symbol, {"accepted": True})

    def crashing_rename(*args, **kwargs):
        raise JavaRefactorRuntimeError("sidecar terminated during request")

    monkeypatch.setattr(tool, "create_java_refactor_client", lambda: SimpleNamespace(semantic_rename=crashing_rename), raising=False)

    result = tool.apply("Demo/method", "src/Demo.java", "renamed")

    assert recorded["lsp_called"] is True
    assert result == "LSP_RENAMED"
    assert "java_refactor_unavailable" not in result


@pytest.mark.parametrize(
    "error",
    [
        JavaRefactorRuntimeError("sidecar requires Serena's LSP backend"),
        RuntimeError("sidecar crashed mid-request"),
        FileNotFoundError("bundled sidecar jar is missing"),
    ],
)
def test_generic_safe_delete_falls_back_to_lsp_when_engine_unavailable(monkeypatch, error) -> None:
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_safe_delete=True, preview_default=True))
    symbol = SimpleNamespace(line=4, column=8, relative_path="src/Demo.java", get_name_path=lambda: "Demo/method")
    tool = _make_routing_tool_with_lsp(SafeDeleteSymbol, config, recorded, monkeypatch, symbol, {"accepted": True})

    def raising_client():
        raise error

    monkeypatch.setattr(tool, "create_java_refactor_client", raising_client, raising=False)

    result = tool.apply("Demo/method", "src/Demo.java")

    # Engine unavailable must fall through to the existing LSP safe-delete path (no references => deletes).
    assert recorded["java_called"] is False
    assert recorded["lsp_called"] is True
    assert result == SUCCESS_RESULT
    assert "java_refactor_unavailable" not in result


def _single_edit_workspace_edit(source: Path) -> dict:
    from serena.java_refactor.workspace_edit import sha256_bytes

    return {
        "changes": [
            {
                "path": source.name,
                "oldSha256": sha256_bytes(source.read_bytes()),
                "edits": [{"startOffset": 0, "endOffset": 5, "newText": "Renamed", "kind": "REPLACE"}],
            }
        ],
        "fileOperations": [],
        "warnings": [],
        "preconditions": [],
        "stats": {"editCount": 1, "fileOperationCount": 0},
    }


def _covered_scan_references(relative_path, line, column, name_hint=None, **_hints):
    """A valid rename reference scan: one span fully covered by ``_single_edit_workspace_edit``'s (0,5) edit.

    Used by tests that exercise OTHER apply gates through ``semantic_rename`` so the mandatory G002 baseline capture
    succeeds and does not mask the gate under test.
    """
    return {"accepted": True, "references": [{"relativePath": "Main.java", "startOffset": 0, "endOffset": 5}]}


def _is_baseline_overlay(overlay: dict) -> bool:
    return not overlay.get("changedFiles") and not overlay.get("deletedFiles") and not overlay.get("renamedFiles")


def test_incomplete_analysis_apply_tolerates_preexisting_errors(tmp_path: Path) -> None:
    # G004/G005: under allow_incomplete_analysis, a pre-existing compiler error present in BOTH the baseline and the
    # staged overlay must NOT block apply; the edit commits and post-validation does not roll it back.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    pre_existing = ["/proj/Other.java:7:9: cannot find symbol\n  symbol: class Missing"]

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=_covered_scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        # Baseline and staged carry the SAME pre-existing error; nothing new is introduced.
        validate_edit=lambda overlay: {
            "accepted": True,
            "ready": False,
            "errors": [],
            "compilerErrors": list(pre_existing),
            "warnings": pre_existing,
        },
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": pre_existing}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, allow_incomplete_analysis=True),
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is True, result
    assert result["applied"] is True
    assert "rolledBack" not in result or result["rolledBack"] is False
    assert source.read_text(encoding="utf-8") == "Renamed Main {}\n"


def test_incomplete_analysis_apply_rejects_new_errors(tmp_path: Path) -> None:
    # G004/G005: a NEW error (present in staged but not baseline) must block apply even under allow_incomplete_analysis,
    # and nothing may be written to disk.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    pre_existing = ["/proj/Other.java:7:9: cannot find symbol"]
    new_error = "/proj/Main.java:1:1: incompatible types"

    def validate_edit(overlay: dict) -> dict:
        compiler_errors = list(pre_existing) if _is_baseline_overlay(overlay) else [*pre_existing, new_error]
        return {"accepted": True, "ready": False, "errors": [], "compilerErrors": compiler_errors, "warnings": compiler_errors}

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=_covered_scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=validate_edit,
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": pre_existing}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, allow_incomplete_analysis=True),
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["refusal"]["code"] == "pre_apply_validation_failed"
    assert result["preValidation"]["newErrors"] == [new_error]
    assert any("cannot find symbol" in e for e in result["preValidation"]["preExistingErrors"])
    # Nothing was written: the only blocking item is the NEW error, and the pre-existing one was tolerated.
    assert source.read_text(encoding="utf-8") == original


def test_complete_mode_apply_rejects_all_staged_errors(tmp_path: Path) -> None:
    # In complete mode (allow_incomplete_analysis False) ANY staged compiler error blocks apply, even one that also
    # exists in the baseline: a non-compiling project must be fixed before compiler-backed apply.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    pre_existing = ["/proj/Other.java:7:9: cannot find symbol"]

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=_covered_scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {
            "accepted": True,
            "ready": False,
            "errors": list(pre_existing),
            "compilerErrors": list(pre_existing),
            "warnings": [],
        },
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "pre_apply_validation_failed"
    assert source.read_text(encoding="utf-8") == "class Main {}\n"


def test_rename_rolls_back_when_old_key_still_referenced(tmp_path: Path) -> None:
    # G005: after a rename, the symbol's source reference set must be preserved. If the post-rename re-scan finds FEWER
    # references than existed before (a reference was left bound to the old key), the apply is rolled back.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)

    scan_calls = {"n": 0}

    def scan_references(relative_path, line, column, name_hint=None, **_hints):
        scan_calls["n"] += 1
        # First call (pre-edit baseline) sees 3 references; second (post-edit) sees only 2 => one left on the old key.
        # Every baseline span overlaps the single (0,5) rewrite edit so the primary coverage check passes and the
        # residual is caught by the secondary count check.
        count = 3 if scan_calls["n"] == 1 else 2
        spans = [(0, 3), (1, 4), (2, 5)][:count]
        return {
            "accepted": True,
            "references": [{"relativePath": "Main.java", "startOffset": start, "endOffset": end} for start, end in spans],
        }

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
        scan_references=scan_references,
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["rolledBack"] is True
    assert result["refusal"]["code"] == "rename_old_key_residual"
    assert source.read_text(encoding="utf-8") == original


def test_rename_succeeds_when_old_key_fully_rewritten(tmp_path: Path) -> None:
    # G005: when the post-rename reference set matches the pre-rename baseline, the old-key check passes and the rename
    # commits.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
        # Same reference count before and after, and every baseline span is covered by the (0,5) rewrite edit => the
        # rename is complete.
        scan_references=lambda relative_path, line, column, name_hint=None, **_hints: {
            "accepted": True,
            "references": [
                {"relativePath": "Main.java", "startOffset": 0, "endOffset": 3},
                {"relativePath": "Main.java", "startOffset": 3, "endOffset": 5},
            ],
        },
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is True
    assert result.get("rolledBack") in (None, False)
    assert source.read_text(encoding="utf-8") == "Renamed Main {}\n"


def _apply_manager_with_workspace_edit(tmp_path: Path, workspace_edit: dict) -> JavaRefactorManager:
    """Builds a manager whose fake sidecar accepts the rename and returns ``workspace_edit`` for apply."""
    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=_covered_scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]
    return manager


def _assert_apply_unsafe_edit_refusal(result: dict) -> None:
    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["editsAlreadyApplied"] is False
    assert result["refusal"]["code"] == "apply_unsafe_edit"


def test_apply_refuses_hash_mismatch_as_structured_refusal(tmp_path: Path) -> None:
    # Blocker regression: a stale content hash on apply must be a structured refusal (like preview_unsafe_edit on the
    # preview path), not a leaked WorkspaceEditError, and nothing may be written.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    workspace_edit["changes"][0]["oldSha256"] = "0" * 64

    manager = _apply_manager_with_workspace_edit(tmp_path, workspace_edit)
    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    _assert_apply_unsafe_edit_refusal(result)
    assert source.read_text(encoding="utf-8") == original


def test_apply_refuses_out_of_range_offset_as_structured_refusal(tmp_path: Path) -> None:
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    workspace_edit["changes"][0]["edits"] = [{"startOffset": 0, "endOffset": 10_000, "newText": "interface", "kind": "REPLACE"}]

    manager = _apply_manager_with_workspace_edit(tmp_path, workspace_edit)
    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    _assert_apply_unsafe_edit_refusal(result)
    assert source.read_text(encoding="utf-8") == original


def test_apply_refuses_create_over_existing_file_as_structured_refusal(tmp_path: Path) -> None:
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = {
        "changes": [],
        "fileOperations": [{"kind": "create", "path": "Main.java", "content": "class Dup {}\n"}],
        "warnings": [],
        "preconditions": [],
        "stats": {"editCount": 0, "fileOperationCount": 1},
    }

    manager = _apply_manager_with_workspace_edit(tmp_path, workspace_edit)
    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    _assert_apply_unsafe_edit_refusal(result)
    assert source.read_text(encoding="utf-8") == original


def test_apply_commit_failure_after_staging_is_structured_refusal(tmp_path: Path, monkeypatch) -> None:
    # Blocker regression: a commit failure after successful staging must surface as a structured refusal that states
    # the workspace was restored (the applier restores backups before raising), not as a leaked exception.
    from serena.java_refactor.workspace_edit import TransactionalWorkspaceEditApplier, WorkspaceEditError

    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)

    def failing_commit(self, staged):
        raise WorkspaceEditError("Workspace edit failed and backups were restored: disk full")

    monkeypatch.setattr(TransactionalWorkspaceEditApplier, "commit", failing_commit)

    manager = _apply_manager_with_workspace_edit(tmp_path, workspace_edit)
    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["editsAlreadyApplied"] is False
    assert result["rolledBack"] is True
    assert result["refusal"]["code"] == "apply_commit_failed"
    assert "restored" in result["refusal"]["message"]
    assert source.read_text(encoding="utf-8") == original


def test_apply_commit_failure_with_failed_restore_reports_partial_edits(tmp_path: Path, monkeypatch) -> None:
    # When commit fails AND the backup restore itself fails, the applier raises the raw restore error. The manager
    # must still return a structured result that is explicit that edits may remain on disk.
    from serena.java_refactor.workspace_edit import TransactionalWorkspaceEditApplier

    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)

    def failing_commit(self, staged):
        raise OSError("read-only file system")

    monkeypatch.setattr(TransactionalWorkspaceEditApplier, "commit", failing_commit)

    manager = _apply_manager_with_workspace_edit(tmp_path, workspace_edit)
    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["editsAlreadyApplied"] is True
    assert result["rolledBack"] is False
    assert result["refusal"]["code"] == "apply_commit_failed"
    assert "partially applied" in result["refusal"]["message"]


def test_apply_snapshot_failure_is_structured_refusal_before_any_write(tmp_path: Path, monkeypatch) -> None:
    # A snapshot failure happens BEFORE any write; it must surface as a structured refusal (not a leaked exception)
    # and leave the workspace untouched.
    from serena.java_refactor.workspace_edit import TransactionalWorkspaceEditApplier

    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)

    def failing_snapshot(self, edit):
        raise OSError("permission denied")

    monkeypatch.setattr(TransactionalWorkspaceEditApplier, "snapshot", failing_snapshot)

    manager = _apply_manager_with_workspace_edit(tmp_path, workspace_edit)
    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["editsAlreadyApplied"] is False
    assert result["refusal"]["code"] == "apply_snapshot_failed"
    assert "no files were written" in result["refusal"]["message"]
    assert source.read_text(encoding="utf-8") == original


def _validate_knob_manager(tmp_path: Path, source: Path, overlays: list, config: JavaRefactorConfig) -> JavaRefactorManager:
    """Manager whose fake sidecar reports compiler errors for any staged overlay and an unready status once the
    (always-broken, per this fixture) edit is on disk — so the pre-commit and post-commit gates are both observable.
    """

    def validate_edit(overlay: dict) -> dict:
        overlays.append(overlay)
        broken_on_disk = "Renamed" in source.read_text(encoding="utf-8")
        errors = [] if _is_baseline_overlay(overlay) and not broken_on_disk else ["Main.java:1: error: broken by edit"]
        return {"accepted": True, "ready": not errors, "errors": errors, "compilerErrors": errors, "warnings": []}

    def status(refresh: bool = False) -> SimpleNamespace:
        broken = source.read_text(encoding="utf-8") == "Renamed Main {}\n"
        return SimpleNamespace(
            ready=not broken,
            errors=["Main.java:1: error: broken by edit"] if broken else [],
            project_model={"conventionalFallbackUsed": False, "warnings": []},
        )

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=_covered_scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": _single_edit_workspace_edit(source)},
        validate_edit=validate_edit,
        status=status,
    )
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=config)
    manager._client = fake_client  # type: ignore[assignment]
    return manager


def test_validate_before_apply_true_refuses_before_any_write(tmp_path: Path) -> None:
    # Inversion pair (with the test below): with the default validate_before_apply=True, a staged edit that fails
    # javac is refused PRE-commit — staged overlay validated, nothing written.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    overlays: list = []

    manager = _validate_knob_manager(tmp_path, source, overlays, JavaRefactorConfig(enabled=True, validate_before_apply=True))
    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["refusal"]["code"] == "pre_apply_validation_failed"
    assert result["applied"] is False
    assert any(not _is_baseline_overlay(o) for o in overlays), "staged overlay must be pre-validated"
    assert source.read_text(encoding="utf-8") == "class Main {}\n"


def test_validate_before_apply_false_skips_pre_commit_but_post_validation_rolls_back(tmp_path: Path) -> None:
    # Blocker regression: validate_before_apply=False must have a REAL observable effect — the staged pre-commit javac
    # validation is skipped (the edit commits) — while the non-bypassable post-commit validation still detects the
    # broken project and rolls the commit back, so safety is preserved.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    overlays: list = []

    manager = _validate_knob_manager(
        tmp_path,
        source,
        overlays,
        JavaRefactorConfig(enabled=True, validate_before_apply=False, allow_incomplete_analysis=False),
    )
    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    # Staged pre-commit validation was skipped; only the baseline/post empty-overlay checks ran.
    assert all(_is_baseline_overlay(overlay) for overlay in overlays)
    # ...but the apply gate still held: the broken commit was rolled back by post-validation.
    assert result["refusal"]["code"] == "post_validation_failed"
    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["rolledBack"] is True
    assert source.read_text(encoding="utf-8") == "class Main {}\n"


def _raise_scan_failure(relative_path, line, column, name_hint=None, **_hints):
    raise JavaRefactorRuntimeError("sidecar reference scan crashed")


@pytest.mark.parametrize(
    "scan_references",
    [
        pytest.param(_raise_scan_failure, id="scan-raises"),
        pytest.param(
            lambda relative_path, line, column, name_hint=None, **_hints: {
                "accepted": False,
                "refusal": {"code": "target_not_found", "message": "No refactorable Java symbol was found."},
            },
            id="scan-not-accepted",
        ),
        pytest.param(
            lambda relative_path, line, column, name_hint=None, **_hints: {"accepted": True, "references": []},
            id="scan-no-spans",
        ),
        pytest.param(
            lambda relative_path, line, column, name_hint=None, **_hints: {"accepted": True},
            id="scan-missing-reference-list",
        ),
        pytest.param(
            lambda relative_path, line, column, name_hint=None, **_hints: {
                "accepted": True,
                "references": [{"relativePath": "Main.java"}],
            },
            id="scan-span-without-offsets",
        ),
        pytest.param(
            lambda relative_path, line, column, name_hint=None, **_hints: {
                "accepted": True,
                "references": [{"relativePath": "", "startOffset": 0, "endOffset": 4}],
            },
            id="scan-span-without-path",
        ),
        pytest.param(
            lambda relative_path, line, column, name_hint=None, **_hints: {
                "accepted": True,
                "references": [{"relativePath": "Main.java", "startOffset": 7, "endOffset": 7}],
            },
            id="scan-empty-span",
        ),
        pytest.param(
            lambda relative_path, line, column, name_hint=None, **_hints: {
                "accepted": True,
                "references": [{"relativePath": "Main.java", "startOffset": True, "endOffset": 5}],
            },
            id="scan-bool-as-offset",
        ),
    ],
)
def test_rename_apply_refuses_before_mutation_when_baseline_capture_fails(tmp_path: Path, scan_references) -> None:
    # G002 (fail-closed old-key baseline): every semanticRename apply must capture the pre-edit reference-site
    # baseline. When the scan raises, is not accepted, or returns malformed/no spans, the apply must refuse with a
    # structured error BEFORE the sidecar is even asked to plan/apply the edit, and no file may be written.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    apply_calls: list[str] = []

    def apply_refactor(operation, params):
        apply_calls.append(operation)
        return {"accepted": True, "workspaceEdit": workspace_edit}

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        apply_refactor=apply_refactor,
        validate_edit=lambda overlay: {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
        scan_references=scan_references,
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["refusal"]["code"] == "rename_baseline_unavailable"
    # The refusal happened before any edit was planned: the sidecar apply was never invoked and nothing was written.
    assert apply_calls == []
    assert source.read_text(encoding="utf-8") == original
    assert list(tmp_path.iterdir()) == [source]


def _file_op_rename_workspace_edit(source: Path, new_name: str) -> dict:
    """A top-level type rename workspace edit: rename the declaration FILE and rewrite the in-file declaration name.

    Mirrors the real sidecar V1 shape — the in-declaration text edit targets the file's CURRENT (old) path and the
    rename file operation moves the already-edited content to ``<New>.java`` (V1 transaction ordering: stage all text
    edits, then apply file operations).
    """
    from serena.java_refactor.workspace_edit import sha256_bytes

    old_hash = sha256_bytes(source.read_bytes())
    new_path = source.with_name(f"{new_name}.java").name
    return {
        "changes": [
            {
                "path": source.name,
                "oldSha256": old_hash,
                # "class Foo" -> the simple name starts after "class "
                "edits": [{"startOffset": 6, "endOffset": 9, "newText": new_name, "kind": "DECLARATION"}],
            }
        ],
        "fileOperations": [{"kind": "rename", "oldPath": source.name, "newPath": new_path, "oldSha256": old_hash}],
        "warnings": [],
        "preconditions": [],
        "stats": {"editCount": 1, "fileOperationCount": 1},
    }


def test_rename_residual_runs_for_file_operation_rename_and_rolls_back(tmp_path: Path) -> None:
    # G004: a top-level type rename that moves the declaration FILE must STILL run old-key residual verification (these
    # are the highest-risk cases). The post-apply re-scan is remapped to the renamed file; a shrinking reference set
    # rolls the apply back. Previously any file operation disabled the residual check entirely.
    source = tmp_path / "Foo.java"
    source.write_text("class Foo {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _file_op_rename_workspace_edit(source, "Bar")

    scans: list[str] = []

    def scan_references(relative_path, line, column, name_hint=None, **_hints):
        scans.append(relative_path)
        # Baseline (1st, at the old file) sees 3 refs; post-apply (2nd, at the renamed file) sees only 2 => one left bound
        # to the old key. Baseline spans all overlap the (6,9) declaration edit so coverage passes and the count check
        # catches the residual.
        count = 3 if len(scans) == 1 else 2
        spans = [(6, 9), (6, 8), (7, 9)][:count]
        return {
            "accepted": True,
            "references": [{"relativePath": relative_path, "startOffset": start, "endOffset": end} for start, end in spans],
        }

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
        scan_references=scan_references,
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Foo.java", 1, 7, "Bar", apply=True)

    assert result["accepted"] is False
    assert result["rolledBack"] is True
    assert result["refusal"]["code"] == "rename_old_key_residual"
    # The post-apply residual re-scan was remapped to the RENAMED declaration file, not the (now-moved) old path.
    assert scans == ["Foo.java", "Bar.java"], scans
    # Rollback restored the original file and removed the renamed file.
    assert source.read_text(encoding="utf-8") == original
    assert not (tmp_path / "Bar.java").exists()


def test_rename_residual_passes_for_complete_file_operation_rename(tmp_path: Path) -> None:
    # G004: when the renamed-file re-scan finds the same number of references as the baseline, the old-key check passes
    # and the top-level type rename commits (the file is moved, the declaration rewritten).
    source = tmp_path / "Foo.java"
    source.write_text("class Foo {}\n", encoding="utf-8")
    workspace_edit = _file_op_rename_workspace_edit(source, "Bar")

    scans: list[str] = []

    def scan_references(relative_path, line, column, name_hint=None, **_hints):
        scans.append(relative_path)
        # Both baseline spans overlap the (6,9) declaration edit; the post-apply scan reports the same count.
        return {
            "accepted": True,
            "references": [
                {"relativePath": relative_path, "startOffset": 6, "endOffset": 9},
                {"relativePath": relative_path, "startOffset": 7, "endOffset": 9},
            ],
        }

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
        scan_references=scan_references,
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Foo.java", 1, 7, "Bar", apply=True)

    assert result["accepted"] is True
    assert result.get("rolledBack") in (None, False)
    assert scans == ["Foo.java", "Bar.java"], scans
    # The file was moved and the declaration rewritten.
    assert not source.exists()
    assert (tmp_path / "Bar.java").read_text(encoding="utf-8") == "class Bar {}\n"


def test_java_refactor_manager_rolls_back_on_post_validation_failure(tmp_path: Path) -> None:
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")

    from serena.java_refactor.workspace_edit import sha256_bytes

    workspace_edit = {
        "changes": [
            {
                "path": "Main.java",
                "oldSha256": sha256_bytes(source.read_bytes()),
                "edits": [{"startOffset": 0, "endOffset": 5, "newText": "interface", "kind": "REPLACE"}],
            }
        ],
        "fileOperations": [],
        "warnings": [],
        "preconditions": [],
        "stats": {"editCount": 1, "fileOperationCount": 0},
    }

    # status() is now called twice: once pre-apply (the H3 degraded-model gate, which must see a healthy non-degraded
    # model so apply proceeds) and once post-apply (the post-validation pass, which reports failure to force rollback).
    # validate_edit() is now the canonical pre/post gate: it reports clean before commit and fails after the disk write.
    def validate_edit(overlay: dict) -> dict:
        errors = ["post-validation error"] if "interface" in source.read_text(encoding="utf-8") else []
        return {"accepted": True, "ready": not errors, "errors": errors, "compilerErrors": errors, "warnings": []}

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=_covered_scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=validate_edit,
        status=lambda refresh=False: SimpleNamespace(
            ready=False, errors=["post-validation error"], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )

    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["rolledBack"] is True
    assert result["editsAlreadyApplied"] is False
    assert result["refusal"]["code"] == "post_validation_failed"
    # The on-disk content must be restored to its pre-apply state.
    assert source.read_text(encoding="utf-8") == original


def _make_java_tool(tool_cls, recorded: dict, monkeypatch, symbol):
    """Builds a Java-specific refactoring tool wired to a symbol retriever stub and a recording manager stub."""
    project = SimpleNamespace(
        project_root="/tmp/proj",
        project_config=SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True)),
    )
    agent = SimpleNamespace(
        get_language_backend=lambda: LanguageBackend.LSP,
        get_active_project_or_raise=lambda: project,
    )
    tool = object.__new__(tool_cls)
    tool.agent = agent  # type: ignore[attr-defined]
    retriever = SimpleNamespace(find_unique=lambda *args, **kwargs: symbol)
    tool.create_language_server_symbol_retriever = lambda: retriever  # type: ignore[attr-defined]

    def record(relative_path, line, column, *args, **kwargs):
        recorded["relative_path"] = relative_path
        recorded["line"] = line
        recorded["column"] = column
        recorded["new_name"] = args[0] if args else kwargs.get("new_name")
        recorded["apply"] = kwargs.get("apply")
        recorded["validate"] = kwargs.get("validate")
        recorded["include_javadocs"] = kwargs.get("include_javadocs")
        recorded["include_comments"] = kwargs.get("include_comments")
        recorded["allow_public_api_delete"] = kwargs.get("allow_public_api_delete")
        recorded["search_in_comments_and_strings"] = kwargs.get("search_in_comments_and_strings")
        recorded["search_for_text_occurrences"] = kwargs.get("search_for_text_occurrences")
        recorded["allow_public_api"] = kwargs.get("allow_public_api")
        return {"accepted": True}

    fake_manager = SimpleNamespace(
        semantic_rename=record,
        safe_delete=record,
        move_top_level_type=record,
        inline_local_variable=record,
        inline_constant=record,
    )
    # Tools obtain the manager through the Component.create_java_refactor_client() integration point.
    monkeypatch.setattr(tool, "create_java_refactor_client", lambda: fake_manager, raising=False)
    return tool


def test_java_rename_tool_resolves_name_path_to_one_based_position(monkeypatch) -> None:
    from serena.tools import JavaSemanticRenameTool

    recorded: dict = {}
    symbol = SimpleNamespace(line=4, column=8)  # zero-based language-server position
    tool = _make_java_tool(JavaSemanticRenameTool, recorded, monkeypatch, symbol)

    tool.apply(name_path="Demo/method", relative_path="src/Demo.java", new_name="renamed")

    assert recorded["relative_path"] == "src/Demo.java"
    assert recorded["line"] == 5 and recorded["column"] == 9
    assert recorded["new_name"] == "renamed"
    # preview defaults to true -> apply false; validate defaults to true.
    assert recorded["apply"] is False
    assert recorded["validate"] is True


def test_java_rename_tool_advanced_line_column_bypasses_symbol_lookup(monkeypatch) -> None:
    from serena.tools import JavaSemanticRenameTool

    recorded: dict = {}

    def fail_find(*args, **kwargs):
        raise AssertionError("name_path resolution must be skipped when line/column are provided")

    tool = _make_java_tool(JavaSemanticRenameTool, recorded, monkeypatch, SimpleNamespace())
    tool.create_language_server_symbol_retriever = lambda: SimpleNamespace(find_unique=fail_find)  # type: ignore[attr-defined]

    tool.apply(relative_path="src/Demo.java", new_name="renamed", preview=False, line=10, column=3)

    assert recorded["line"] == 10 and recorded["column"] == 3
    assert recorded["apply"] is True


def test_java_rename_tool_refuses_name_path_when_lsp_symbol_resolution_disabled(monkeypatch) -> None:
    from serena.tools import JavaSemanticRenameTool

    recorded: dict = {}
    tool = _make_java_tool(JavaSemanticRenameTool, recorded, monkeypatch, SimpleNamespace(line=4, column=8))
    tool.agent.get_active_project_or_raise().project_config.java_refactor.use_lsp_symbol_resolution = False

    with pytest.raises(ValueError, match="use_lsp_symbol_resolution"):
        tool.apply(name_path="Demo/method", relative_path="src/Demo.java", new_name="renamed")


def test_java_refactor_symbol_tool_dispatches_rename(monkeypatch) -> None:
    from serena.tools import JavaRefactorSymbolTool

    recorded: dict = {}
    tool = _make_java_tool(JavaRefactorSymbolTool, recorded, monkeypatch, SimpleNamespace(line=4, column=8))

    tool.apply(operation="rename", name_path="Demo/method", relative_path="src/Demo.java", new_name="renamed")

    assert recorded["relative_path"] == "src/Demo.java"
    assert recorded["line"] == 5 and recorded["column"] == 9
    assert recorded["new_name"] == "renamed"
    assert recorded["apply"] is False


def test_java_rename_tool_forwards_javadoc_and_comment_flags(monkeypatch) -> None:
    from serena.tools import JavaSemanticRenameTool

    recorded: dict = {}
    tool = _make_java_tool(JavaSemanticRenameTool, recorded, monkeypatch, SimpleNamespace())

    tool.apply(relative_path="src/Demo.java", new_name="renamed", include_javadocs=True, include_comments=True, line=1, column=1)

    assert recorded["include_javadocs"] is True
    assert recorded["include_comments"] is True


def _make_rename_tool_with_real_manager(tool_cls, tmp_path: Path, monkeypatch, java_refactor_config: JavaRefactorConfig):
    """Builds a rename tool wired to a REAL JavaRefactorManager and a fake sidecar client recording preview params.

    Unlike the manager-stub helpers above, this exercises the full tool -> manager -> sidecar parameter flow, so the
    recorded dict shows exactly what the sidecar would receive (e.g. whether project-config defaults were honored).
    """
    recorded: dict = {}

    def preview(operation: str, params: dict) -> dict:
        recorded.update(params)
        recorded["operation"] = operation
        return {"accepted": False, "refusal": {"code": "target_not_found", "message": "stub"}}

    fake_client = SimpleNamespace(is_running=lambda: True, preview=preview)
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=java_refactor_config)
    manager._client = fake_client  # type: ignore[assignment]

    project = SimpleNamespace(project_config=SimpleNamespace(java_refactor=java_refactor_config), project_root=str(tmp_path))
    agent = SimpleNamespace(
        get_active_project_or_raise=lambda: project,
        get_language_backend=lambda: LanguageBackend.LSP,
    )
    tool = object.__new__(tool_cls)
    tool.agent = agent  # type: ignore[attr-defined]
    symbol = SimpleNamespace(line=4, column=8)
    tool.create_language_server_symbol_retriever = lambda: SimpleNamespace(find_unique=lambda *a, **k: symbol)  # type: ignore[attr-defined]
    monkeypatch.setattr(tool, "create_java_refactor_client", lambda: manager, raising=False)
    return tool, recorded


def test_java_rename_tool_omitted_flags_honor_true_project_defaults(tmp_path: Path, monkeypatch) -> None:
    # Blocker regression: include_javadocs_default/include_comments_default true + caller omits the parameters
    # => the sidecar must receive true (the tool must NOT silently override the project config with False).
    from serena.tools import JavaSemanticRenameTool

    config = JavaRefactorConfig(enabled=True, include_javadocs=True, include_comments=True, preview_default=True)
    tool, recorded = _make_rename_tool_with_real_manager(JavaSemanticRenameTool, tmp_path, monkeypatch, config)

    tool.apply(relative_path="src/Demo.java", new_name="renamed", line=1, column=1)

    assert recorded["includeJavadocs"] is True
    assert recorded["includeComments"] is True


def test_java_rename_tool_explicit_false_overrides_true_project_defaults(tmp_path: Path, monkeypatch) -> None:
    # Project defaults true, caller explicitly passes false => the sidecar must receive false.
    from serena.tools import JavaSemanticRenameTool

    config = JavaRefactorConfig(enabled=True, include_javadocs=True, include_comments=True, preview_default=True)
    tool, recorded = _make_rename_tool_with_real_manager(JavaSemanticRenameTool, tmp_path, monkeypatch, config)

    tool.apply(relative_path="src/Demo.java", new_name="renamed", include_javadocs=False, include_comments=False, line=1, column=1)

    assert recorded["includeJavadocs"] is False
    assert recorded["includeComments"] is False


def test_java_rename_tool_omitted_flags_honor_false_project_defaults(tmp_path: Path, monkeypatch) -> None:
    # Project defaults false (the config default), caller omits => the sidecar receives false.
    from serena.tools import JavaSemanticRenameTool

    config = JavaRefactorConfig(enabled=True, preview_default=True)
    tool, recorded = _make_rename_tool_with_real_manager(JavaSemanticRenameTool, tmp_path, monkeypatch, config)

    tool.apply(relative_path="src/Demo.java", new_name="renamed", line=1, column=1)

    assert recorded["includeJavadocs"] is False
    assert recorded["includeComments"] is False


def test_generic_rename_routing_honors_javadoc_and_comment_project_defaults(tmp_path: Path, monkeypatch) -> None:
    # The generic rename_symbol routing never passes the flags, so the manager's project-config defaults must reach
    # the sidecar unchanged.
    config = JavaRefactorConfig(enabled=True, route_generic_rename=True, preview_default=True, include_javadocs=True, include_comments=True)
    tool, recorded = _make_rename_tool_with_real_manager(RenameSymbolTool, tmp_path, monkeypatch, config)

    tool.apply("Demo/method", "src/Demo.java", "renamed")

    assert recorded["operation"] == "semanticRename"
    assert recorded["includeJavadocs"] is True
    assert recorded["includeComments"] is True


def test_java_safe_delete_manager_forwards_textual_search_options_to_sidecar(tmp_path: Path) -> None:
    recorded: dict = {}

    def preview(operation: str, params: dict) -> dict:
        recorded.update(params)
        recorded["operation"] = operation
        return {"accepted": False, "refusal": {"code": "target_not_found", "message": "stub"}}

    config = JavaRefactorConfig(enabled=True, preview_default=True)
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=config)
    manager._client = SimpleNamespace(is_running=lambda: True, preview=preview)  # type: ignore[assignment]

    manager.safe_delete(
        "src/Demo.java",
        2,
        4,
        search_in_comments_and_strings=True,
        search_for_text_occurrences=True,
    )

    assert recorded["operation"] == "safeDelete"
    assert recorded["searchInCommentsAndStrings"] is True
    assert recorded["searchForTextOccurrences"] is True


def test_java_safe_delete_tool_forwards_allow_public_api_delete(monkeypatch) -> None:
    from serena.tools import JavaSafeDeleteTool

    recorded: dict = {}
    tool = _make_java_tool(JavaSafeDeleteTool, recorded, monkeypatch, SimpleNamespace())

    tool.apply(relative_path="src/Demo.java", allow_public_api_delete=True, line=2, column=4)

    assert recorded["allow_public_api_delete"] is True


def test_java_safe_delete_tool_forwards_textual_search_options(monkeypatch) -> None:
    from serena.tools import JavaSafeDeleteTool

    recorded: dict = {}
    tool = _make_java_tool(JavaSafeDeleteTool, recorded, monkeypatch, SimpleNamespace())

    tool.apply(
        relative_path="src/Demo.java",
        search_in_comments_and_strings=True,
        search_for_text_occurrences=True,
        line=2,
        column=4,
    )

    assert recorded["search_in_comments_and_strings"] is True
    assert recorded["search_for_text_occurrences"] is True


def test_java_refactor_symbol_safe_delete_forwards_textual_search_options(monkeypatch) -> None:
    from serena.tools import JavaRefactorSymbolTool

    recorded: dict = {}
    tool = _make_java_tool(JavaRefactorSymbolTool, recorded, monkeypatch, SimpleNamespace())

    tool.apply(
        operation="safe_delete",
        relative_path="src/Demo.java",
        search_in_comments_and_strings=True,
        search_for_text_occurrences=True,
        line=2,
        column=4,
    )

    assert recorded["search_in_comments_and_strings"] is True
    assert recorded["search_for_text_occurrences"] is True


def test_java_inline_constant_tool_forwards_allow_public_api(monkeypatch) -> None:
    from serena.tools import JavaInlineConstantTool

    recorded: dict = {}
    tool = _make_java_tool(JavaInlineConstantTool, recorded, monkeypatch, SimpleNamespace())

    tool.apply(relative_path="src/Demo.java", allow_public_api=True, preview=False, line=3, column=30)

    assert recorded["allow_public_api"] is True
    assert recorded["apply"] is True


def test_java_refactor_tool_markers_match_plan() -> None:
    """The status tool is a symbolic-read tool; mutating tools are EditingToolWithDiagnostics, optional, and beta."""
    from serena.tools import (
        EditingToolWithDiagnostics,
        JavaChangeSignatureTool,
        JavaEncapsulateFieldTool,
        JavaExtractInterfaceTool,
        JavaExtractMethodTool,
        JavaInlineConstantTool,
        JavaInlineLocalVariableTool,
        JavaInlineMethodTool,
        JavaIntroduceFieldTool,
        JavaIntroduceParameterTool,
        JavaMoveInstanceMethodTool,
        JavaMoveStaticMemberTool,
        JavaMoveTopLevelTypeTool,
        JavaPullUpMemberTool,
        JavaPushDownMemberTool,
        JavaRefactorStatusTool,
        JavaRefactorSymbolTool,
        JavaSafeDeleteTool,
        JavaSemanticRenameTool,
        ToolMarkerBeta,
        ToolMarkerOptional,
        ToolMarkerSymbolicEdit,
        ToolMarkerSymbolicRead,
    )

    assert issubclass(JavaRefactorStatusTool, ToolMarkerSymbolicRead)
    assert issubclass(JavaRefactorStatusTool, ToolMarkerOptional)
    assert issubclass(JavaRefactorStatusTool, ToolMarkerBeta)

    for mutating in (
        JavaRefactorSymbolTool,
        JavaSemanticRenameTool,
        JavaSafeDeleteTool,
        JavaMoveTopLevelTypeTool,
        JavaInlineLocalVariableTool,
        JavaInlineConstantTool,
        JavaInlineMethodTool,
        JavaChangeSignatureTool,
        JavaIntroduceParameterTool,
        JavaMoveStaticMemberTool,
        JavaMoveInstanceMethodTool,
        JavaPullUpMemberTool,
        JavaPushDownMemberTool,
        JavaExtractMethodTool,
        JavaExtractInterfaceTool,
        JavaIntroduceFieldTool,
        JavaEncapsulateFieldTool,
    ):
        assert issubclass(mutating, EditingToolWithDiagnostics), mutating
        assert issubclass(mutating, ToolMarkerSymbolicEdit), mutating
        assert issubclass(mutating, ToolMarkerOptional), mutating
        assert issubclass(mutating, ToolMarkerBeta), mutating


def test_v2_java_refactor_tools_are_exposed_by_name() -> None:
    from serena.tools import java_refactor_tool_names

    names = set(java_refactor_tool_names())

    assert {
        "java_create_refactor_session",
        "java_get_refactor_session_edit",
        "java_apply_refactor_session",
        "java_cancel_refactor_session",
        "java_change_signature",
        "java_introduce_parameter",
        "java_move_static_member",
        "java_move_instance_method",
        "java_pull_up_member",
        "java_push_down_member",
        "java_inline_method",
        "java_extract_method",
        "java_extract_interface",
        "java_introduce_field",
        "java_encapsulate_field",
    }.issubset(names)


def test_v2_refactor_session_refuses_missing_advertised_capability(tmp_path: Path, monkeypatch) -> None:
    class FakeClient:
        create_session_called = False

        def capabilities(self) -> dict[str, object]:
            return {"capabilities": {"refactorSessions": {"level": "beta", "status": "supported"}}}

        def create_session(self, operation: str, params: dict[str, object]) -> dict[str, object]:
            self.create_session_called = True
            return {"accepted": True, "session": {"sessionId": "S"}}

    fake_client = FakeClient()
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: fake_client)

    result = manager.v2_refactor_session("changeSignature", {"relativePath": "Demo.java"})

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "java_refactor_capability_unavailable"
    assert fake_client.create_session_called is False


def test_v2_refactor_session_refuses_partial_advertised_capability(tmp_path: Path, monkeypatch) -> None:
    class FakeClient:
        create_session_called = False

        def capabilities(self) -> dict[str, object]:
            return {
                "capabilities": {
                    "changeSignature": {"level": "experimental", "status": "partial"},
                    "refactorSessions": {"level": "beta", "status": "supported"},
                }
            }

        def create_session(self, operation: str, params: dict[str, object]) -> dict[str, object]:
            self.create_session_called = True
            return {"accepted": True, "session": {"sessionId": "S"}}

    fake_client = FakeClient()
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: fake_client)

    result = manager.v2_refactor_session("changeSignature", {"relativePath": "Demo.java"})

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "java_refactor_capability_unavailable"
    assert fake_client.create_session_called is False


def test_v2_refactor_session_passes_supported_advertised_capability_to_sidecar(
    tmp_path: Path, monkeypatch
) -> None:
    class FakeClient:
        create_session_called = False

        def capabilities(self) -> dict[str, object]:
            return {
                "capabilities": {
                    "changeSignature": {"level": "beta", "status": "supported"},
                    "refactorSessions": {"level": "beta", "status": "supported"},
                }
            }

        def create_session(
            self, operation: str, params: dict[str, object], apply: bool = False, **_: object
        ) -> dict[str, object]:
            self.create_session_called = True
            return {"accepted": True, "session": {"sessionId": "S", "operation": operation, "params": params}}

    fake_client = FakeClient()
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda *_, **__: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda *_, **__: fake_client)

    result = manager.v2_refactor_session("changeSignature", {"relativePath": "Demo.java"})

    assert fake_client.create_session_called is True
    assert result["refusal"]["code"] != "java_refactor_capability_unavailable"


def test_capabilities_normalizer_does_not_promote_preview_string_to_supported(tmp_path: Path, monkeypatch) -> None:
    """G001 (T3): a sidecar that returns a legacy bare-string capability of "preview" must NOT be normalized to
    status "supported". This pins the normalizer so a future change cannot silently promote a preview op.
    """

    class FakeClient:
        def capabilities(self) -> dict[str, object]:
            return {"capabilities": {"changeSignature": "preview", "refactorSessions": {"level": "beta", "status": "supported"}}}

    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    capabilities = manager._capabilities(FakeClient())  # type: ignore[arg-type]

    assert capabilities["changeSignature"]["status"] != "supported"


def test_capabilities_preview_status_object_is_refused_by_ensure(tmp_path: Path, monkeypatch) -> None:
    """G001 (T1, Python half): every op the sidecar advertises with status != "supported" (e.g. "preview") must be
    refused by _ensure_v2_capability, while a "supported" op passes. Regression-guards truthful gating.
    """

    class FakeClient:
        def capabilities(self) -> dict[str, object]:
            return {
                "capabilities": {
                    "changeSignature": {"level": "beta", "status": "preview"},
                    "extractMethod": {"level": "beta", "status": "supported"},
                }
            }

    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    fake = FakeClient()

    preview_refusal = manager._ensure_v2_capability(fake, "changeSignature", "preview")  # type: ignore[arg-type]
    assert preview_refusal is not None
    assert preview_refusal["refusal"]["code"] == "java_refactor_capability_unavailable"

    supported = manager._ensure_v2_capability(fake, "extractMethod", "preview")  # type: ignore[arg-type]
    assert supported is None


def test_v2_refactor_session_refuses_legacy_experimental_capability(tmp_path: Path, monkeypatch) -> None:
    class FakeClient:
        create_session_called = False

        def capabilities(self) -> dict[str, object]:
            return {"capabilities": {"changeSignature": "experimental"}}

        def create_session(self, operation: str, params: dict[str, object]) -> dict[str, object]:
            self.create_session_called = True
            return {"accepted": True, "session": {"sessionId": "S"}}

    fake_client = FakeClient()
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: fake_client)

    result = manager.v2_refactor_session("changeSignature", {"relativePath": "Demo.java"})

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "java_refactor_capability_unavailable"
    assert fake_client.create_session_called is False


def test_v2_refactor_session_preview_exposes_full_diagnostic_delta(tmp_path: Path, monkeypatch) -> None:
    from serena.java_refactor.workspace_edit import sha256_bytes

    source = tmp_path / "Demo.java"
    source.write_text("class Demo { int value = 1; }\n", encoding="utf-8")

    class FakeClient:
        def capabilities(self) -> dict[str, object]:
            return {
                "capabilities": {
                    "refactorSessions": {"level": "beta", "status": "supported"},
                    "inlineMethod": {"level": "beta", "status": "supported"},
                }
            }

        def create_session(self, operation: str, params: dict[str, object]) -> dict[str, object]:
            workspace_edit = {
                "changes": [
                    {
                        "path": "Demo.java",
                        "oldSha256": sha256_bytes(source.read_bytes()),
                        "edits": [{"startOffset": 17, "endOffset": 22, "newText": "total", "kind": "REPLACE"}],
                    }
                ],
                "fileOperations": [],
                "warnings": [],
                "preconditions": [],
                "stats": {"editCount": 1, "fileOperationCount": 0},
            }
            return {"accepted": True, "session": {"sessionId": "S", "operation": operation}, "preview": {"workspaceEdit": workspace_edit}}

        def validate_edit(self, overlay: dict) -> dict[str, object]:
            if not overlay.get("changedFiles"):
                return {
                    "accepted": True,
                    "ready": False,
                    "errors": ["/p/Broken.java:1:1: cannot find symbol"],
                    "compilerErrors": ["/p/Broken.java:1:1: cannot find symbol"],
                    "compilerWarnings": ["/p/Legacy.java:2:1: old rawtype warning"],
                    "warnings": ["/p/Legacy.java:2:1: old rawtype warning"],
                }
            return {
                "accepted": True,
                "ready": False,
                "errors": ["/p/Broken.java:9:1: cannot find symbol", "/p/Demo.java:1:18: cannot find symbol"],
                "compilerErrors": ["/p/Broken.java:9:1: cannot find symbol", "/p/Demo.java:1:18: cannot find symbol"],
                "compilerWarnings": [
                    "/p/Legacy.java:4:1: old rawtype warning",
                    "/p/Demo.java:1:1: unchecked conversion warning",
                ],
                "warnings": [
                    "/p/Legacy.java:4:1: old rawtype warning",
                    "/p/Demo.java:1:1: unchecked conversion warning",
                ],
            }

    fake_client = FakeClient()
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, allow_incomplete_analysis=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: fake_client)

    result = manager.v2_refactor_session("inlineMethod", {"relativePath": "Demo.java"})

    assert result["accepted"] is True
    validation = result["previewValidation"]
    assert validation["ready"] is False
    delta = validation["diagnosticDelta"]
    # G003: the canonical diagnosticDelta arrays carry structured DiagnosticInfo dicts (with a derived display field).
    assert [d["display"] for d in delta["before"]["errors"]] == ["/p/Broken.java:1:1: cannot find symbol"]
    assert [d["display"] for d in delta["after"]["errors"]] == [
        "/p/Broken.java:9:1: cannot find symbol",
        "/p/Demo.java:1:18: cannot find symbol",
    ]
    assert [d["display"] for d in delta["unchangedErrors"]] == ["/p/Broken.java:9:1: cannot find symbol"]
    assert [d["display"] for d in delta["newErrors"]] == ["/p/Demo.java:1:18: cannot find symbol"]
    assert delta["resolvedErrors"] == []
    new_error = delta["newErrors"][0]
    assert new_error["severity"] == "error"
    assert new_error["path"] == "/p/Demo.java"
    assert new_error["line"] == 1 and new_error["column"] == 18
    assert new_error["message"] == "cannot find symbol"
    assert validation["newWarnings"] == ["/p/Demo.java:1:1: unchecked conversion warning"]


def _g002_session_manager(tmp_path: Path, monkeypatch, *, baseline_errors, staged_errors, allow_incomplete):
    """Builds a JavaRefactorManager wired to a fake sidecar whose staged javac validation returns ``staged_errors``.

    The baseline (empty overlay) returns ``baseline_errors``; the staged overlay (the session edit) returns
    ``staged_errors``. This lets a single helper drive G002's complete-vs-incomplete policy on the session preview path.
    """
    from serena.java_refactor.workspace_edit import sha256_bytes

    source = tmp_path / "Demo.java"
    source.write_text("class Demo { int value = 1; }\n", encoding="utf-8")

    class FakeClient:
        def capabilities(self) -> dict[str, object]:
            return {
                "capabilities": {
                    "refactorSessions": {"level": "beta", "status": "supported"},
                    "inlineMethod": {"level": "beta", "status": "supported"},
                }
            }

        def create_session(self, operation: str, params: dict[str, object]) -> dict[str, object]:
            workspace_edit = {
                "changes": [
                    {
                        "path": "Demo.java",
                        "oldSha256": sha256_bytes(source.read_bytes()),
                        "edits": [{"startOffset": 17, "endOffset": 22, "newText": "total", "kind": "REPLACE"}],
                    }
                ],
                "fileOperations": [],
                "warnings": [],
                "preconditions": [],
                "stats": {"editCount": 1, "fileOperationCount": 0},
            }
            return {"accepted": True, "session": {"sessionId": "S", "operation": operation}, "preview": {"workspaceEdit": workspace_edit}}

        def validate_edit(self, overlay: dict) -> dict[str, object]:
            errors = staged_errors if overlay.get("changedFiles") else baseline_errors
            return {"accepted": True, "ready": not errors, "errors": list(errors), "compilerErrors": list(errors), "warnings": []}

    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, allow_incomplete_analysis=allow_incomplete),
    )
    fake_client = FakeClient()
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: fake_client)
    return manager


def test_v2_session_complete_mode_refuses_preexisting_errors(tmp_path: Path, monkeypatch) -> None:
    # G002: in complete-analysis mode (the default), an accepted session preview must leave the after-state error-free.
    # An unchanged pre-existing error therefore blocks with a refusal distinct from new_compiler_errors.
    preexisting = "/p/Broken.java:1:1: cannot find symbol"
    manager = _g002_session_manager(
        tmp_path, monkeypatch, baseline_errors=[preexisting], staged_errors=[preexisting], allow_incomplete=False
    )

    result = manager.v2_refactor_session("inlineMethod", {"relativePath": "Demo.java"})

    validation = result["previewValidation"]
    assert validation["ready"] is False
    assert validation["errors"] == [preexisting]
    assert validation["newErrors"] == []
    assert validation["refusal"]["code"] == "preexisting_compiler_errors_not_allowed"
    assert [d["display"] for d in validation["refusal"]["diagnosticDelta"]["unchangedErrors"]] == [preexisting]


def test_v2_session_incomplete_mode_tolerates_preexisting_errors(tmp_path: Path, monkeypatch) -> None:
    # G002: with allow_incomplete_analysis opted in, the SAME unchanged pre-existing error is tolerated — the preview is
    # ready and carries no refusal, because the edit introduced no new compiler error.
    preexisting = "/p/Broken.java:1:1: cannot find symbol"
    manager = _g002_session_manager(
        tmp_path, monkeypatch, baseline_errors=[preexisting], staged_errors=[preexisting], allow_incomplete=True
    )

    result = manager.v2_refactor_session("inlineMethod", {"relativePath": "Demo.java"})

    validation = result["previewValidation"]
    assert validation["ready"] is True
    assert validation["errors"] == []
    assert "refusal" not in validation
    assert [d["display"] for d in validation["diagnosticDelta"]["unchangedErrors"]] == [preexisting]


@pytest.mark.parametrize("allow_incomplete", [False, True])
def test_v2_session_new_errors_refused_in_both_modes(tmp_path: Path, monkeypatch, allow_incomplete: bool) -> None:
    # G002: a newly introduced compiler error is refused regardless of the incomplete-analysis policy, and keeps the
    # new_compiler_errors code so it is never confused with the pre-existing-error case.
    new_error = "/p/Demo.java:1:18: cannot find symbol"
    manager = _g002_session_manager(
        tmp_path, monkeypatch, baseline_errors=[], staged_errors=[new_error], allow_incomplete=allow_incomplete
    )

    result = manager.v2_refactor_session("inlineMethod", {"relativePath": "Demo.java"})

    validation = result["previewValidation"]
    assert validation["ready"] is False
    assert validation["newErrors"] == [new_error]
    assert validation["refusal"]["code"] == "new_compiler_errors"


def test_get_v2_refactor_session_edit_defaults_to_serena_workspace_edit_format(tmp_path: Path, monkeypatch) -> None:
    # HB-10: the first-class session edit-retrieval flow requests the serenaWorkspaceEdit format by default, and
    # honors an explicit override.
    recorded: dict[str, object] = {"formats": []}

    class FakeClient:
        def get_session_edit(self, session_id: str, edit_format: str | None = None, selection: dict | None = None) -> dict[str, object]:
            recorded["session_id"] = session_id
            recorded["formats"].append(edit_format)  # type: ignore[attr-defined]
            return {
                "accepted": True,
                "format": edit_format,
                "preview": {
                    "workspaceEdit": {
                        "changes": [],
                        "fileOperations": [],
                        "warnings": [],
                        "preconditions": [],
                        "stats": {},
                    }
                },
            }

    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, allow_incomplete_analysis=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: FakeClient())

    default_result = manager.get_v2_refactor_session_edit("S", validate=False)
    assert default_result["format"] == "serenaWorkspaceEdit"
    assert recorded["formats"][-1] == "serenaWorkspaceEdit"  # type: ignore[index]

    manager.get_v2_refactor_session_edit("S", validate=False, edit_format="workspaceEdit")
    assert recorded["formats"][-1] == "workspaceEdit"  # type: ignore[index]


def test_v2_refactor_session_apply_always_gates_new_diagnostic_delta(tmp_path: Path, monkeypatch) -> None:
    from serena.java_refactor.workspace_edit import sha256_bytes

    source = tmp_path / "Demo.java"
    source.write_text("class Demo { int value = 1; }\n", encoding="utf-8")

    class FakeClient:
        validate_calls = 0

        def capabilities(self) -> dict[str, object]:
            return {
                "capabilities": {
                    "refactorSessions": {"level": "beta", "status": "supported"},
                    "inlineMethod": {"level": "beta", "status": "supported"},
                }
            }

        def create_session(self, operation: str, params: dict[str, object]) -> dict[str, object]:
            return {"accepted": True, "session": {"sessionId": "S", "operation": operation}, "preview": {}}

        def apply_session(self, session_id: str, expected_project_revision: object = None, selection: dict | None = None) -> dict[str, object]:
            workspace_edit = {
                "changes": [
                    {
                        "path": "Demo.java",
                        "oldSha256": sha256_bytes(source.read_bytes()),
                        "edits": [{"startOffset": 17, "endOffset": 22, "newText": "total", "kind": "REPLACE"}],
                    }
                ],
                "fileOperations": [],
                "warnings": [],
                "preconditions": [],
                "stats": {"editCount": 1, "fileOperationCount": 0},
            }
            return {
                "accepted": True,
                "session": {"sessionId": session_id},
                "validation": {"accepted": True},
                "preview": {"accepted": True, "workspaceEdit": workspace_edit},
            }

        def validate_edit(self, overlay: dict) -> dict[str, object]:
            self.validate_calls += 1
            if not overlay.get("changedFiles"):
                return {
                    "accepted": True,
                    "ready": False,
                    "compilerErrors": ["/p/Broken.java:1:1: cannot find symbol"],
                    "compilerWarnings": ["/p/Legacy.java:2:1: old rawtype warning"],
                }
            return {
                "accepted": True,
                "ready": False,
                "compilerErrors": [
                    "/p/Broken.java:9:1: cannot find symbol",
                    "/p/Demo.java:1:18: cannot find symbol",
                ],
                "compilerWarnings": [
                    "/p/Legacy.java:4:1: old rawtype warning",
                    "/p/Demo.java:1:1: unchecked conversion warning",
                ],
            }

        def status(self, refresh: bool = False) -> SimpleNamespace:
            return SimpleNamespace(ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []})

    fake_client = FakeClient()
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, allow_incomplete_analysis=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: fake_client)

    result = manager.v2_refactor_session("inlineMethod", {"relativePath": "Demo.java"}, apply=True, validate=False)

    assert fake_client.validate_calls == 2
    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["refusal"]["code"] == "session_pre_apply_validation_failed"
    assert result["preValidation"]["newErrors"] == ["/p/Demo.java:1:18: cannot find symbol"]
    assert result["preValidation"]["newWarnings"] == ["/p/Demo.java:1:1: unchecked conversion warning"]
    assert [d["display"] for d in result["preValidation"]["diagnosticDelta"]["unchangedErrors"]] == [
        "/p/Broken.java:9:1: cannot find symbol"
    ]
    assert source.read_text(encoding="utf-8") == "class Demo { int value = 1; }\n"


def test_v2_session_apply_uses_revalidated_plan_edit_not_stored_preview(tmp_path: Path, monkeypatch) -> None:
    """Blocker 1 regression (Python apply half): the workspace edit Python applies must be the freshly recomputed +
    revalidated plan the sidecar surfaces in the apply envelope's ``preview.workspaceEdit`` — never a separately-stored
    create-time preview. The fake apply envelope surfaces a *revalidated* edit (value -> ``revalidated``) under
    ``preview.workspaceEdit`` while also carrying a divergent *stored* edit (value -> ``stored``) under a top-level
    ``plan``. After apply, the committed bytes must reflect the revalidated edit, proving Python keys off the surfaced
    revalidated plan. Paired with the sidecar-side unit test that proves the apply envelope carries ``currentPlan``
    (RefactorSessionManagerTest#applyEnvelopeSurfacesSuppliedRevalidatedPlanNotStoredPreview), this closes the contract.
    """
    from serena.java_refactor.workspace_edit import sha256_bytes

    source = tmp_path / "Demo.java"
    source.write_text("class Demo { int value = 1; }\n", encoding="utf-8")

    def _change_to(new_text: str) -> dict[str, object]:
        return {
            "changes": [
                {
                    "path": "Demo.java",
                    "oldSha256": sha256_bytes(source.read_bytes()),
                    "edits": [{"startOffset": 17, "endOffset": 22, "newText": new_text, "kind": "REPLACE"}],
                }
            ],
            "fileOperations": [],
            "warnings": [],
            "preconditions": [],
            "stats": {"editCount": 1, "fileOperationCount": 0},
        }

    class FakeClient:
        def capabilities(self) -> dict[str, object]:
            return {"capabilities": {"refactorSessions": {"level": "beta", "status": "supported"}}}

        def apply_session(self, session_id: str, expected_project_revision: object = None, selection: dict | None = None) -> dict[str, object]:
            # The apply envelope surfaces the revalidated plan under preview.workspaceEdit (what Python applies) and a
            # divergent stored edit under plan — a regressed sidecar that re-surfaced the stored preview would put the
            # "stored" edit under preview.workspaceEdit and the assertion below would catch it.
            return {
                "accepted": True,
                "session": {"sessionId": session_id},
                "validation": {"accepted": True},
                "plan": {"accepted": True, "workspaceEdit": _change_to("stored")},
                "preview": {"accepted": True, "workspaceEdit": _change_to("revalidated")},
            }

        def validate_edit(self, overlay: dict) -> dict[str, object]:
            return {"accepted": True, "ready": True, "compilerErrors": [], "compilerWarnings": []}

        def status(self, refresh: bool = False) -> SimpleNamespace:
            return SimpleNamespace(ready=True, errors=[], project_model={"warnings": []})

    fake_client = FakeClient()
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: fake_client)

    result = manager.apply_v2_refactor_session("S")

    assert result["applied"] is True
    assert result.get("refusal") is None
    assert source.read_text(encoding="utf-8") == "class Demo { int revalidated = 1; }\n"


def _degraded_v2_change(source: "Path") -> dict[str, object]:
    from serena.java_refactor.workspace_edit import sha256_bytes

    return {
        "changes": [
            {
                "path": "Demo.java",
                "oldSha256": sha256_bytes(source.read_bytes()),
                "edits": [{"startOffset": 17, "endOffset": 22, "newText": "total", "kind": "REPLACE"}],
            }
        ],
        "fileOperations": [],
        "warnings": [],
        "preconditions": [],
        "stats": {"editCount": 1, "fileOperationCount": 0},
    }


def test_v2_refactor_session_apply_refuses_degraded_conventional_fallback_model(tmp_path: Path, monkeypatch) -> None:
    """G002: v2_refactor_session(apply=True) refuses BEFORE any sidecar apply or Python-side write when the project model
    is degraded (conventional fallback => no resolved classpath). The refusal is structured (not a warning) and no file
    is mutated.
    """
    source = tmp_path / "Demo.java"
    original = "class Demo { int value = 1; }\n"
    source.write_text(original, encoding="utf-8")

    class FakeClient:
        apply_session_calls = 0

        def capabilities(self) -> dict[str, object]:
            return {
                "capabilities": {
                    "refactorSessions": {"level": "beta", "status": "supported"},
                    "inlineMethod": {"level": "beta", "status": "supported"},
                }
            }

        def create_session(self, operation: str, params: dict[str, object]) -> dict[str, object]:
            return {"accepted": True, "session": {"sessionId": "S", "operation": operation}, "preview": {}}

        def apply_session(self, session_id: str, expected_project_revision: object = None, selection: dict | None = None) -> dict[str, object]:
            self.apply_session_calls += 1
            return {
                "accepted": True,
                "session": {"sessionId": session_id},
                "validation": {"accepted": True},
                "preview": {"accepted": True, "workspaceEdit": _degraded_v2_change(source)},
            }

        def validate_edit(self, overlay: dict) -> dict[str, object]:
            return {"accepted": True, "ready": True, "compilerErrors": [], "compilerWarnings": []}

        def status(self, refresh: bool = False) -> SimpleNamespace:
            return SimpleNamespace(ready=True, errors=[], project_model={"conventionalFallbackUsed": True, "warnings": []})

    fake_client = FakeClient()
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, allow_conventional_fallback=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: fake_client)

    result = manager.v2_refactor_session("inlineMethod", {"relativePath": "Demo.java"}, apply=True, validate=False)

    assert result["accepted"] is False, result
    assert result["applied"] is False, result
    assert result["refusal"]["code"] == "degraded_model_apply_refused", result
    # The gate runs before the sidecar apply revalidation and before any write.
    assert fake_client.apply_session_calls == 0
    assert source.read_text(encoding="utf-8") == original


def test_apply_v2_refactor_session_refuses_degraded_conventional_fallback_model(tmp_path: Path, monkeypatch) -> None:
    """G002: apply_v2_refactor_session refuses BEFORE the sidecar apply revalidation and any Python-side write on a
    degraded (conventional fallback) project model, leaving the file untouched.
    """
    source = tmp_path / "Demo.java"
    original = "class Demo { int value = 1; }\n"
    source.write_text(original, encoding="utf-8")

    class FakeClient:
        apply_session_calls = 0

        def capabilities(self) -> dict[str, object]:
            return {"capabilities": {"refactorSessions": {"level": "beta", "status": "supported"}}}

        def apply_session(self, session_id: str, expected_project_revision: object = None, selection: dict | None = None) -> dict[str, object]:
            self.apply_session_calls += 1
            return {
                "accepted": True,
                "session": {"sessionId": session_id},
                "validation": {"accepted": True},
                "preview": {"accepted": True, "workspaceEdit": _degraded_v2_change(source)},
            }

        def validate_edit(self, overlay: dict) -> dict[str, object]:
            return {"accepted": True, "ready": True, "compilerErrors": [], "compilerWarnings": []}

        def status(self, refresh: bool = False) -> SimpleNamespace:
            return SimpleNamespace(ready=True, errors=[], project_model={"conventionalFallbackUsed": True, "warnings": []})

    fake_client = FakeClient()
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, allow_conventional_fallback=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: fake_client)

    result = manager.apply_v2_refactor_session("S")

    assert result["accepted"] is False, result
    assert result["applied"] is False, result
    assert result["refusal"]["code"] == "degraded_model_apply_refused", result
    assert fake_client.apply_session_calls == 0
    assert source.read_text(encoding="utf-8") == original


def test_java_change_signature_tool_builds_v2_session_params(monkeypatch) -> None:
    from serena.tools import JavaChangeSignatureTool

    recorded: dict = {}
    tool = _make_java_tool(JavaChangeSignatureTool, recorded, monkeypatch, SimpleNamespace(line=4, column=8))

    def v2_refactor_session(operation, params, apply=False, validate=None):
        recorded["operation"] = operation
        recorded["params"] = params
        recorded["apply"] = apply
        recorded["validate"] = validate
        return {"accepted": False, "refusal": {"code": "unsupported_operation", "message": "stub"}}

    cast(Any, tool.create_java_refactor_client()).v2_refactor_session = v2_refactor_session

    tool.apply(
        name_path="Demo/method",
        relative_path="src/Demo.java",
        new_name="renamed",
        new_return_type="String",
        parameters_json='[{"name":"value","type":"String","default_value":"\\"x\\""},{"name":"extra","type":"int"}]',
        default_values_json='{"extra":"1"}',
        line=11,
        column=3,
        preview=True,
        validate=False,
    )

    assert recorded["operation"] == "changeSignature"
    assert recorded["apply"] is False
    assert recorded["validate"] is False
    assert recorded["params"]["relativePath"] == "src/Demo.java"
    assert recorded["params"]["line"] == 11 and recorded["params"]["column"] == 3
    assert recorded["params"]["newName"] == "renamed"
    assert recorded["params"]["newReturnType"] == "String"
    assert "updateOverrides" not in recorded["params"]
    assert recorded["params"]["defaultValues"] == {"extra": "1"}
    assert recorded["params"]["parameters"] == [
        {"name": "value", "type": "String", "default_value": '"x"', "defaultValue": '"x"'},
        {"name": "extra", "type": "int", "defaultValue": "1"},
    ]


def test_java_introduce_parameter_tool_builds_v2_session_params(monkeypatch) -> None:
    from serena.tools import JavaIntroduceParameterTool

    recorded: dict = {}
    tool = _make_java_tool(JavaIntroduceParameterTool, recorded, monkeypatch, SimpleNamespace(line=4, column=8))

    def v2_refactor_session(operation, params, apply=False, validate=None):
        recorded["operation"] = operation
        recorded["params"] = params
        recorded["apply"] = apply
        recorded["validate"] = validate
        return {"accepted": False, "refusal": {"code": "unsupported_operation", "message": "stub"}}

    cast(Any, tool.create_java_refactor_client()).v2_refactor_session = v2_refactor_session

    tool.apply(
        name_path="Demo/method",
        relative_path="src/Demo.java",
        parameter_name="prefix",
        selection_start_line=6,
        selection_start_column=16,
        selection_end_line=6,
        selection_end_column=24,
        preview=True,
        validate=False,
    )

    assert recorded["operation"] == "introduceParameter"
    assert recorded["apply"] is False
    assert recorded["validate"] is False
    assert recorded["params"]["relativePath"] == "src/Demo.java"
    assert recorded["params"]["line"] == 5 and recorded["params"]["column"] == 9
    assert recorded["params"]["selectedExpression"] is None
    assert recorded["params"]["selection"] == {"startLine": 6, "startColumn": 16, "endLine": 6, "endColumn": 24}
    assert recorded["params"]["parameterName"] == "prefix"
    assert recorded["params"]["parameterType"] is None
    # G003: the side-effect opt-in defaults to False and is always forwarded to the sidecar as allowSideEffects.
    assert recorded["params"]["allowSideEffects"] is False


def test_java_explicit_session_tools_call_manager(monkeypatch) -> None:
    from serena.tools import (
        JavaApplyRefactorSessionTool,
        JavaCancelRefactorSessionTool,
        JavaCreateRefactorSessionTool,
        JavaGetRefactorSessionEditTool,
    )

    recorded: dict = {}

    create_tool = _make_java_tool(JavaCreateRefactorSessionTool, recorded, monkeypatch, SimpleNamespace())

    def create_v2_refactor_session(operation, params, validate=None):
        recorded["create"] = (operation, params, validate)
        return {"accepted": True}

    cast(Any, create_tool.create_java_refactor_client()).create_v2_refactor_session = create_v2_refactor_session
    create_tool.apply(operation="changeSignature", params_json='{"relativePath":"src/Demo.java"}', validate=False)
    assert recorded["create"] == ("changeSignature", {"relativePath": "src/Demo.java"}, False)

    get_tool = _make_java_tool(JavaGetRefactorSessionEditTool, recorded, monkeypatch, SimpleNamespace())

    def get_v2_refactor_session_edit(session_id, validate=None, edit_format=None, selection=None):
        recorded["get"] = (session_id, validate, edit_format, selection)
        return {"accepted": True}

    get_tool.create_java_refactor_client().get_v2_refactor_session_edit = get_v2_refactor_session_edit
    get_tool.apply(session_id="S1", validate=True, format="workspaceEdit", selection_json='{"files":["src/Demo.java"]}')
    assert recorded["get"] == ("S1", True, "workspaceEdit", {"files": ["src/Demo.java"]})

    apply_tool = _make_java_tool(JavaApplyRefactorSessionTool, recorded, monkeypatch, SimpleNamespace())

    def apply_v2_refactor_session(session_id, validate=None, expected_project_revision=None, selection=None):
        recorded["apply"] = (session_id, validate, expected_project_revision, selection)
        return {"accepted": True, "applied": False}

    apply_tool.create_java_refactor_client().apply_v2_refactor_session = apply_v2_refactor_session
    apply_tool.apply(
        session_id="S1", validate=False, expected_project_revision="rev-123", selection_json='{"files":["src/Demo.java"]}'
    )
    assert recorded["apply"] == ("S1", False, "rev-123", {"files": ["src/Demo.java"]})

    cancel_tool = _make_java_tool(JavaCancelRefactorSessionTool, recorded, monkeypatch, SimpleNamespace())

    def cancel_v2_refactor_session(session_id):
        recorded["cancel"] = session_id
        return {"accepted": True}

    cancel_tool.create_java_refactor_client().cancel_v2_refactor_session = cancel_v2_refactor_session
    cancel_tool.apply(session_id="S1")
    assert recorded["cancel"] == "S1"


# --- HB-1: high-level V2 operation tools are preview/session-only (no one-shot apply) ---------------------------------

# (tool import name, apply kwargs that reach _session_refactor) for every high-level V2 operation tool.
_HB1_V2_OPERATION_TOOLS = [
    ("JavaInlineMethodTool", {"name_path": "Demo/m", "relative_path": "src/Demo.java"}),
    ("JavaChangeSignatureTool", {"name_path": "Demo/m", "relative_path": "src/Demo.java", "new_name": "renamed"}),
    ("JavaIntroduceParameterTool", {"name_path": "Demo/m", "relative_path": "src/Demo.java", "parameter_name": "p"}),
    ("JavaMoveStaticMemberTool", {"name_path": "Demo/m", "relative_path": "src/Demo.java", "target_type": "x.Y"}),
    ("JavaMoveInstanceMethodTool", {"name_path": "Demo/m", "relative_path": "src/Demo.java", "target_parameter_name": "p"}),
    ("JavaPullUpMemberTool", {"name_path": "Demo/m", "relative_path": "src/Demo.java", "target_supertype": "x.Base"}),
    ("JavaPushDownMemberTool", {"name_path": "Demo/m", "relative_path": "src/Demo.java", "target_subtypes_json": '["x.Sub"]'}),
    (
        "JavaExtractMethodTool",
        {
            "relative_path": "src/Demo.java",
            "start_line": 2,
            "start_col": 1,
            "end_line": 3,
            "end_col": 1,
            "new_method_name": "extracted",
        },
    ),
    ("JavaExtractInterfaceTool", {"name_path": "Demo", "relative_path": "src/Demo.java", "interface_name": "IDemo"}),
    ("JavaIntroduceFieldTool", {"name_path": "Demo/m", "relative_path": "src/Demo.java", "field_name": "f", "field_type": "int"}),
    ("JavaEncapsulateFieldTool", {"name_path": "Demo/field", "relative_path": "src/Demo.java"}),
]


def _hb1_make_tool(tool_name, monkeypatch):
    """Builds a V2 operation tool wired to a recording ``v2_refactor_session`` stub that honors the apply flag."""
    import serena.tools as tools_module

    tool_cls = getattr(tools_module, tool_name)
    recorded: dict = {}
    tool = _make_java_tool(tool_cls, recorded, monkeypatch, SimpleNamespace(line=4, column=8))

    calls: dict = {}

    def v2_refactor_session(operation, params, apply=False, validate=None):
        calls["operation"] = operation
        calls["apply"] = apply
        # Mirror the manager contract: a one-shot apply (apply=True) returns an applied envelope; a preview returns a
        # revision-guarded preview session carrying a concrete sessionId.
        return {
            "accepted": True,
            "applied": bool(apply),
            "mode": "apply" if apply else "preview",
            "operation": operation,
            "session": {"sessionId": "S-hb1"},
            "preview": {"workspaceEdit": {"changes": []}},
        }

    cast(Any, tool.create_java_refactor_client()).v2_refactor_session = v2_refactor_session
    return tool, calls


@pytest.mark.parametrize("tool_name,kwargs", _HB1_V2_OPERATION_TOOLS, ids=[t[0] for t in _HB1_V2_OPERATION_TOOLS])
def test_g001_v2_operation_tool_honors_preview_false_one_shot_apply(tool_name, kwargs, monkeypatch) -> None:
    """G001: an explicit preview=False applies through the manager's transactional session apply path."""
    tool, calls = _hb1_make_tool(tool_name, monkeypatch)

    result = json.loads(tool.apply(preview=False, **kwargs))

    # preview=False now reaches the manager with apply=True (the transactional create -> revalidate -> commit path).
    assert calls["apply"] is True, f"{tool_name} did not honor preview=False (manager not asked to apply)"
    assert result["applied"] is True
    # The legacy refusal annotation is gone: the tool no longer redirects to the explicit session-apply tool.
    assert "oneShotApplyRefused" not in result


@pytest.mark.parametrize("tool_name,kwargs", _HB1_V2_OPERATION_TOOLS, ids=[t[0] for t in _HB1_V2_OPERATION_TOOLS])
def test_hb1_v2_operation_tool_preview_is_default_and_unannotated(tool_name, kwargs, monkeypatch) -> None:
    """HB-1: preview (omitted or True) never mutates and is not flagged as a refused one-shot apply."""
    for preview_value in (None, True):
        tool, calls = _hb1_make_tool(tool_name, monkeypatch)
        call_kwargs = dict(kwargs)
        if preview_value is not None:
            call_kwargs["preview"] = preview_value
        result = json.loads(tool.apply(**call_kwargs))
        assert calls["apply"] is False
        assert result["accepted"] is True
        assert result["applied"] is False
        assert "oneShotApplyRefused" not in result
        assert result["session"]["sessionId"] == "S-hb1"


def test_g001_change_signature_tool_preview_false_applies_to_disk(tmp_path, monkeypatch) -> None:
    """G001 end-to-end: a high-level V2 tool with preview=False applies the planned edit to disk through the manager's
    transactional create -> revalidate -> stage -> commit -> post-validate pipeline (no one-shot-apply refusal).
    """
    from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
    from serena.java_refactor.manager import JavaRefactorManager
    from serena.java_refactor.workspace_edit import sha256_bytes
    from serena.tools import JavaChangeSignatureTool
    from solidlsp.ls_config import Language

    source = tmp_path / "App.java"
    source.write_text("class App { int value = 1; }\n", encoding="utf-8")

    def workspace_edit() -> dict:
        # A one-file REPLACE (`value` -> `total`) in the Serena workspace-edit shape the sidecar returns. The planner is
        # faked here; this test pins the apply WIRING, i.e. that preview=False drives a real commit to disk.
        return {
            "changes": [
                {
                    "path": "App.java",
                    "oldSha256": sha256_bytes(source.read_bytes()),
                    "edits": [{"startOffset": 16, "endOffset": 21, "newText": "total", "kind": "REPLACE"}],
                }
            ],
            "fileOperations": [],
            "warnings": [],
            "preconditions": [],
            "stats": {"editCount": 1, "fileOperationCount": 0},
        }

    class FakeClient:
        def capabilities(self) -> dict:
            return {
                "capabilities": {"changeSignature": "beta"},
                "capabilityDetails": {"changeSignature": {"level": "beta", "status": "supported"}},
            }

        def create_session(self, operation: str, params: dict) -> dict:
            return {
                "accepted": True,
                "session": {"sessionId": "S1", "operation": operation, "touchedFiles": ["App.java"]},
                "preview": {"accepted": True, "workspaceEdit": workspace_edit()},
            }

        def apply_session(self, session_id: str, expected_project_revision=None, selection=None) -> dict:
            return {
                "accepted": True,
                "session": {"sessionId": session_id, "operation": "changeSignature"},
                "validation": {"accepted": True},
                "preview": {"accepted": True, "workspaceEdit": workspace_edit()},
            }

        def validate_edit(self, overlay: dict) -> dict:
            return {"accepted": True, "ready": True, "compilerErrors": [], "compilerWarnings": []}

        def status(self, refresh: bool = False) -> SimpleNamespace:
            return SimpleNamespace(ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []})

    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: FakeClient())

    project = SimpleNamespace(
        project_root=str(tmp_path),
        project_config=SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True)),
    )
    agent = SimpleNamespace(
        get_active_project_or_raise=lambda: project,
        get_language_server_manager=lambda: None,
        get_language_backend=lambda: LanguageBackend.LSP,
    )
    tool = object.__new__(JavaChangeSignatureTool)
    tool.agent = agent  # type: ignore[attr-defined]
    monkeypatch.setattr(tool, "create_java_refactor_client", lambda: manager, raising=False)

    result = json.loads(tool.apply(relative_path="App.java", line=1, column=17, new_name="x", preview=False))

    # preview=False applied the edit end-to-end; the legacy refusal is gone and disk reflects the committed change.
    assert "oneShotApplyRefused" not in result
    assert result["applied"] is True, result
    assert result.get("refusal") is None, result
    assert source.read_text(encoding="utf-8") == "class App { int total = 1; }\n"


def test_java_inline_method_tool_defaults_to_preserving_declaration(monkeypatch) -> None:
    from serena.tools import JavaInlineMethodTool

    recorded: dict = {}
    tool = _make_java_tool(JavaInlineMethodTool, recorded, monkeypatch, SimpleNamespace(line=4, column=8))

    def v2_refactor_session(operation, params, apply=False, validate=None):
        recorded["operation"] = operation
        recorded["params"] = params
        recorded["apply"] = apply
        recorded["validate"] = validate
        return {"accepted": False, "refusal": {"code": "unsupported_operation", "message": "stub"}}

    cast(Any, tool.create_java_refactor_client()).v2_refactor_session = v2_refactor_session

    tool.apply(name_path="Demo/helper", relative_path="src/Demo.java", method_name="helper", preview=True, validate=False)

    assert recorded["operation"] == "inlineMethod"
    assert recorded["apply"] is False
    assert recorded["validate"] is False
    assert recorded["params"]["methodName"] == "helper"
    assert recorded["params"]["deleteMethod"] is False


def test_java_extract_method_tool_builds_selection_session_params(monkeypatch) -> None:
    from serena.tools import JavaExtractMethodTool

    recorded: dict = {}
    tool = _make_java_tool(JavaExtractMethodTool, recorded, monkeypatch, SimpleNamespace())

    def v2_refactor_session(operation, params, apply=False, validate=None):
        recorded["operation"] = operation
        recorded["params"] = params
        recorded["apply"] = apply
        return {"accepted": False, "refusal": {"code": "unsupported_operation", "message": "stub"}}

    cast(Any, tool.create_java_refactor_client()).v2_refactor_session = v2_refactor_session

    tool.apply(
        relative_path="src/Demo.java",
        start_line=3,
        start_col=9,
        end_line=5,
        end_col=10,
        new_method_name="extracted",
        make_static=True,
        preview=True,
    )

    assert recorded["operation"] == "extractMethod"
    assert recorded["apply"] is False
    assert recorded["params"] == {
        "relativePath": "src/Demo.java",
        "newMethodName": "extracted",
        "selection": {"startLine": 3, "startColumn": 9, "endLine": 5, "endColumn": 10},
        "visibility": None,
        "makeStatic": True,
    }


def test_java_move_and_hierarchy_tools_build_v2_session_params(monkeypatch) -> None:
    from serena.tools import (
        JavaMoveInstanceMethodTool,
        JavaMoveStaticMemberTool,
        JavaPullUpMemberTool,
        JavaPushDownMemberTool,
    )

    cases = [
        (
            JavaMoveStaticMemberTool,
            "moveStaticMember",
            {"target_type": "com.acme.Target", "new_name": "renamed"},
            {
                "targetType": "com.acme.Target",
                "newName": "renamed",
                "allowAccessWidening": False,
            },
        ),
        (
            JavaMoveInstanceMethodTool,
            "moveInstanceMethod",
            {"target_parameter_name": "target", "target_type": "com.acme.Target"},
            {"targetParameter": "target", "targetType": "com.acme.Target", "keepDelegate": True},
        ),
        (
            JavaPullUpMemberTool,
            "pullUpMember",
            {"target_supertype": "com.acme.Base", "make_abstract": True},
            {"targetType": "com.acme.Base", "makeAbstract": True},
        ),
        (
            JavaPushDownMemberTool,
            "pushDownMember",
            {"target_subtypes_json": '["com.acme.Child", "com.acme.OtherChild"]'},
            {"targetTypes": ["com.acme.Child", "com.acme.OtherChild"], "removeFromSource": False},
        ),
    ]

    for tool_cls, operation, kwargs, expected_params in cases:
        recorded: dict = {}
        tool = _make_java_tool(tool_cls, recorded, monkeypatch, SimpleNamespace(line=4, column=8))

        def v2_refactor_session(operation_name, params, apply=False, validate=None, _recorded=recorded):
            _recorded["operation"] = operation_name
            _recorded["params"] = params
            _recorded["apply"] = apply
            _recorded["validate"] = validate
            return {"accepted": False, "refusal": {"code": "unsupported_operation", "message": "stub"}}

        cast(Any, tool.create_java_refactor_client()).v2_refactor_session = v2_refactor_session

        tool.apply(
            name_path="Demo/member",
            relative_path="src/Demo.java",
            preview=True,
            validate=False,
            **kwargs,
        )

        assert recorded["operation"] == operation
        assert recorded["apply"] is False
        assert recorded["validate"] is False
        assert recorded["params"]["relativePath"] == "src/Demo.java"
        assert recorded["params"]["line"] == 5 and recorded["params"]["column"] == 9
        for key, value in expected_params.items():
            assert recorded["params"][key] == value


def test_java_interface_field_tools_build_v2_session_params(monkeypatch) -> None:
    from serena.tools import (
        JavaEncapsulateFieldTool,
        JavaExtractInterfaceTool,
        JavaIntroduceFieldTool,
    )

    cases = [
        (
            JavaExtractInterfaceTool,
            "extractInterface",
            {
                "interface_name": "Named",
                "target_package": "com.acme.api",
                "members_json": '[{"name":"run","kind":"method"}]',
                "replace_usages": True,
            },
            {
                "interfaceName": "Named",
                "targetPackage": "com.acme.api",
                "members": [{"name": "run", "kind": "method"}],
                "replaceUsages": True,
            },
        ),
        (
            JavaIntroduceFieldTool,
            "introduceField",
            {
                "field_name": "cache",
                "field_type": "String",
                "initializer": '"value"',
                "selection_json": '{"startLine":2,"startColumn":4,"endLine":2,"endColumn":11}',
                "constant": True,
                "initialize_in_constructor": False,
            },
            {
                "fieldName": "cache",
                "fieldType": "String",
                "initializer": '"value"',
                "selection": {"startLine": 2, "startColumn": 4, "endLine": 2, "endColumn": 11},
                "constant": True,
                "initializeInConstructor": False,
            },
        ),
        (
            JavaEncapsulateFieldTool,
            "encapsulateField",
            {"getter_name": "getValue", "setter": False, "update_usages": False},
            {"getterName": "getValue", "setterName": None, "setter": False, "updateReferences": False},
        ),
    ]

    for tool_cls, operation, kwargs, expected_params in cases:
        recorded: dict = {}
        tool = _make_java_tool(tool_cls, recorded, monkeypatch, SimpleNamespace(line=4, column=8))

        def v2_refactor_session(operation_name, params, apply=False, validate=None, _recorded=recorded):
            _recorded["operation"] = operation_name
            _recorded["params"] = params
            _recorded["apply"] = apply
            _recorded["validate"] = validate
            return {"accepted": False, "refusal": {"code": "unsupported_operation", "message": "stub"}}

        cast(Any, tool.create_java_refactor_client()).v2_refactor_session = v2_refactor_session

        tool.apply(
            name_path="Demo/member",
            relative_path="src/Demo.java",
            preview=True,
            validate=False,
            **kwargs,
        )

        assert recorded["operation"] == operation
        assert recorded["apply"] is False
        assert recorded["validate"] is False
        assert recorded["params"]["relativePath"] == "src/Demo.java"
        assert recorded["params"]["line"] == 5 and recorded["params"]["column"] == 9
        for key, value in expected_params.items():
            assert recorded["params"][key] == value


def test_create_java_refactor_client_binds_project_and_honors_lsp_resolution_config() -> None:
    from serena.java_refactor.manager import JavaRefactorManager
    from serena.tools import JavaRefactorStatusTool

    project = SimpleNamespace(
        project_root="/tmp/proj",
        project_config=SimpleNamespace(languages=[Language.JAVA], java_refactor=JavaRefactorConfig(enabled=True)),
    )

    # LSP backend: returns a project-bound manager and caches it on the project.
    lsp_tool = object.__new__(JavaRefactorStatusTool)
    lsp_tool.agent = SimpleNamespace(  # type: ignore[attr-defined]
        get_active_project_or_raise=lambda: project,
        get_language_backend=lambda: LanguageBackend.LSP,
    )
    client = lsp_tool.create_java_refactor_client()
    assert isinstance(client, JavaRefactorManager)
    assert project.java_refactor_manager is client
    assert lsp_tool.create_java_refactor_client() is client  # cached

    # JetBrains/non-LSP backend: refused only while name_path/LSP symbol resolution is enabled.
    jb_tool = object.__new__(JavaRefactorStatusTool)
    jb_tool.agent = SimpleNamespace(  # type: ignore[attr-defined]
        get_active_project_or_raise=lambda: SimpleNamespace(
            project_root="/tmp/jb",
            project_config=SimpleNamespace(languages=[Language.JAVA], java_refactor=JavaRefactorConfig(enabled=True)),
        ),
        get_language_backend=lambda: LanguageBackend.JETBRAINS,
    )
    with pytest.raises(Exception, match="use_lsp_symbol_resolution"):
        jb_tool.create_java_refactor_client()

    explicit_position_project = SimpleNamespace(
        project_root="/tmp/jb-explicit",
        project_config=SimpleNamespace(
            languages=[Language.JAVA],
            java_refactor=JavaRefactorConfig(enabled=True, use_lsp_symbol_resolution=False),
        ),
    )
    explicit_tool = object.__new__(JavaRefactorStatusTool)
    explicit_tool.agent = SimpleNamespace(  # type: ignore[attr-defined]
        get_active_project_or_raise=lambda: explicit_position_project,
        get_language_backend=lambda: LanguageBackend.JETBRAINS,
    )
    explicit_client = explicit_tool.create_java_refactor_client()
    assert isinstance(explicit_client, JavaRefactorManager)


def test_bundled_sidecar_jar_fingerprint_matches_source() -> None:
    """G006: NON-skippable, toolchain-free staleness gate.

    The committed source fingerprint (``serena-java-refactor.jar.sha256``) must equal the fingerprint recomputed from the
    current Java sidecar source. A Java change that is not accompanied by a rebuilt jar + refreshed fingerprint fails
    here in CI without needing a JDK or Gradle, so a stale checked-in jar can never be shipped silently. This test must
    never skip.
    """
    from serena.java_refactor._sidecar_fingerprint import (
        compute_jar_digest,
        compute_source_fingerprint,
        read_committed_fingerprint,
    )

    repo_root = Path(__file__).resolve().parents[2]
    committed = read_committed_fingerprint(repo_root)
    assert committed is not None, (
        "Sidecar fingerprint is missing. Run `gradle -p java-refactor syncResourceJar` and commit the fingerprint."
    )
    assert committed.get("source") == compute_source_fingerprint(repo_root), (
        "Bundled sidecar jar is stale relative to the Java source (source fingerprint mismatch). Rebuild and refresh "
        "with `gradle -p java-refactor syncResourceJar`, then commit the updated jar and fingerprint."
    )
    # The committed jar bytes must match the committed fingerprint, so the jar cannot be swapped independently of it.
    assert committed.get("jar") == compute_jar_digest(repo_root), (
        "Committed sidecar jar bytes do not match the committed fingerprint. Rebuild and refresh with "
        "`gradle -p java-refactor syncResourceJar`, then commit the updated jar and fingerprint."
    )


def _load_hatch_build_module(monkeypatch):
    """Imports the repo-root ``hatch_build.py`` without requiring the hatchling build backend.

    The hook only inherits from ``BuildHookInterface``; a minimal stand-in lets the verification logic be tested in the
    regular test environment where hatchling is not installed.
    """
    import importlib.util
    import types

    if "hatchling.builders.hooks.plugin.interface" not in sys.modules:
        modules = {
            "hatchling": types.ModuleType("hatchling"),
            "hatchling.builders": types.ModuleType("hatchling.builders"),
            "hatchling.builders.hooks": types.ModuleType("hatchling.builders.hooks"),
            "hatchling.builders.hooks.plugin": types.ModuleType("hatchling.builders.hooks.plugin"),
            "hatchling.builders.hooks.plugin.interface": types.ModuleType("hatchling.builders.hooks.plugin.interface"),
        }
        cast(Any, modules["hatchling.builders.hooks.plugin.interface"]).BuildHookInterface = type("BuildHookInterface", (), {})
        for name, module in modules.items():
            monkeypatch.setitem(sys.modules, name, module)
    repo_root = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("hatch_build_under_test", repo_root / "hatch_build.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_packaging_hook_blocks_stale_or_unverifiable_sidecar_jar(tmp_path: Path, monkeypatch) -> None:
    """G005: a stale or unverifiable bundled sidecar jar must BLOCK packaging, never fall back to the checked-in jar."""
    hatch_build = _load_hatch_build_module(monkeypatch)
    from serena.java_refactor._sidecar_fingerprint import FINGERPRINT_RESOURCE, JAR_RESOURCE, write_fingerprint

    src = tmp_path / "java-refactor/src/main/java/io/serena"
    src.mkdir(parents=True)
    java_file = src / "Main.java"
    java_file.write_text("class Main {}\n", encoding="utf-8")
    jar = tmp_path / JAR_RESOURCE
    jar.parent.mkdir(parents=True)
    jar.write_bytes(b"jar-bytes-v1")
    write_fingerprint(tmp_path)

    hook = object.__new__(hatch_build.JavaRefactorJarBuildHook)
    hook.app = SimpleNamespace(display_info=lambda *args, **kwargs: None)  # type: ignore[attr-defined]

    # In-sync source + jar + fingerprint: verification passes.
    hook._verify_fingerprint(tmp_path)

    # Java source drift after the fingerprint was written => the jar is stale => packaging refuses.
    java_file.write_text("class Main { int x; }\n", encoding="utf-8")
    with pytest.raises(RuntimeError, match="stale relative to the Java source"):
        hook._verify_fingerprint(tmp_path)
    java_file.write_text("class Main {}\n", encoding="utf-8")

    # Jar bytes swapped independently of the fingerprint => unverifiable => packaging refuses.
    jar.write_bytes(b"jar-bytes-tampered")
    with pytest.raises(RuntimeError, match="do not match the committed fingerprint"):
        hook._verify_fingerprint(tmp_path)
    jar.write_bytes(b"jar-bytes-v1")

    # Missing fingerprint record => nothing to verify against => packaging refuses.
    (tmp_path / FINGERPRINT_RESOURCE).unlink()
    with pytest.raises(RuntimeError, match="fingerprint is missing"):
        hook._verify_fingerprint(tmp_path)


def test_sidecar_source_fingerprint_is_a_function_of_source(tmp_path: Path) -> None:
    # G006: the fingerprint must change for any source edit, addition, or deletion, so it genuinely binds the jar to the
    # source rather than being a constant.
    from serena.java_refactor._sidecar_fingerprint import compute_source_fingerprint

    src = tmp_path / "java-refactor/src/main/java/io/x"
    src.mkdir(parents=True)
    a = src / "A.java"
    a.write_text("class A {}\n", encoding="utf-8")
    (tmp_path / "java-refactor/build.gradle.kts").write_text("plugins {}\n", encoding="utf-8")

    baseline = compute_source_fingerprint(tmp_path)
    a.write_text("class A { int x; }\n", encoding="utf-8")  # edit
    after_edit = compute_source_fingerprint(tmp_path)
    assert after_edit != baseline

    (src / "B.java").write_text("class B {}\n", encoding="utf-8")  # addition
    after_add = compute_source_fingerprint(tmp_path)
    assert after_add != after_edit

    (src / "B.java").unlink()  # deletion returns to the post-edit fingerprint
    assert compute_source_fingerprint(tmp_path) == after_edit


def test_bundled_resource_jar_matches_fresh_build(tmp_path: Path) -> None:
    """Fails when the checked-in resource jar's bytecode is stale relative to the Java sidecar source."""
    import shutil
    import zipfile

    if shutil.which("java") is None:
        pytest.skip("java is required for the sidecar staleness check")

    repo_root = Path(__file__).resolve().parents[2]
    wrapper = repo_root / "java-refactor" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    wrapper_available = wrapper.exists() and (os.name == "nt" or os.access(wrapper, os.X_OK))
    build_command: list[str] = []
    build_cwd = repo_root
    if wrapper_available:
        build_command = [str(wrapper), "jar"]
        build_cwd = repo_root / "java-refactor"
    elif shutil.which("gradle") is not None:
        build_command = ["gradle", "-p", "java-refactor", "jar"]
        build_cwd = repo_root
    else:
        pytest.skip("the Gradle wrapper or system gradle is required for the sidecar staleness check")

    resource_jar = repo_root / "src/serena/resources/java-refactor/serena-java-refactor.jar"
    assert resource_jar.exists(), f"bundled resource jar missing: {resource_jar}"

    subprocess.run(build_command, cwd=build_cwd, check=True, capture_output=True, text=True)
    fresh_jars = sorted((repo_root / "java-refactor/build/libs").glob("serena-java-refactor*.jar"))
    assert fresh_jars, "fresh sidecar jar build produced no artifact"

    def class_entries(jar_path: Path) -> dict[str, bytes]:
        with zipfile.ZipFile(jar_path) as jar:
            return {name: jar.read(name) for name in jar.namelist() if name.endswith(".class")}

    fresh = class_entries(fresh_jars[-1])
    bundled = class_entries(resource_jar)
    assert bundled == fresh, (
        "Bundled resource jar is stale relative to the Java sidecar source. "
        "Run `gradle -p java-refactor syncResourceJar` and commit the updated jar."
    )


@pytest.mark.xfail(
    condition=bool(os.environ.get("VIRTUAL_ENV")),
    reason=(
        "gradle-hook python3-exec env-inheritance: when this test runs nested under an active uv/pytest venv, the "
        "spawned `uv build` inherits VIRTUAL_ENV and the gradle syncResourceJar hook's python3 exec resolves the wrong "
        "interpreter, so the jar is not refreshed/bundled. A standalone `uv build --wheel` (clean shell, no active venv) "
        "rebuilds and bundles the jar correctly — the standalone packaging guarantee is NOT weakened."
    ),
    strict=False,
    run=True,
)
def test_bundled_sidecar_jar_is_included_in_built_wheel(tmp_path: Path) -> None:
    import shutil
    import zipfile

    uv = shutil.which("uv")
    if uv is None:
        pytest.skip("uv is required to build the wheel for the packaging check")
    assert uv is not None

    subprocess.run([uv, "build", "--wheel", "--out-dir", str(tmp_path)], check=True, capture_output=True, text=True)
    wheels = list(tmp_path.glob("*.whl"))
    assert wheels, "no wheel was produced by `uv build`"
    with zipfile.ZipFile(wheels[0]) as wheel:
        names = wheel.namelist()
    assert "serena/resources/java-refactor/serena-java-refactor.jar" in names, (
        f"bundled sidecar jar missing from wheel; java-refactor entries: {[n for n in names if 'java-refactor' in n]}"
    )


def test_java_refactor_tools_visibility_gated_by_config(tmp_path: Path, monkeypatch) -> None:
    # G001: with java_refactor.enabled AND the sidecar advertising every V2 operation as supported, the full Java
    # refactoring tool set (always-on + capability-negotiated V2 ops) is surfaced through the applied ToolSet; disabled
    # excludes every Java refactor tool (config gates visibility at discovery, not just execution-time refusal). Per-op
    # capability gating of the V2 subset is covered by test_tool_inclusion_registers_supported_subset_only.
    from serena.agent import ToolSet
    from serena.tools import java_refactor_tool_names
    from serena.tools.java_refactor_v2_tools import java_refactor_v2_capability_tool_operations

    names = set(java_refactor_tool_names())
    all_ops = set(java_refactor_v2_capability_tool_operations().values())
    enabled_tools = ToolSet.default().apply(_tool_inclusion(_java_project(tmp_path, enabled=True), all_ops, monkeypatch)).get_tool_names()
    disabled_tools = ToolSet.default().apply(_tool_inclusion(_java_project(tmp_path, enabled=False), all_ops, monkeypatch)).get_tool_names()

    assert names, "expected a non-empty set of Java refactor tool names"
    assert names.issubset(enabled_tools), names - enabled_tools
    assert names.isdisjoint(disabled_tools), names & disabled_tools


def test_java_refactor_tools_exposed_in_multi_project_context() -> None:
    # G002: in a non-single-project (no startup project) context, a Java project with java_refactor.enabled may be
    # activated later, so the optional tools must be present in the base/exposed (client-visible) toolset; otherwise
    # _update_active_tools could only ever make them internally active while absent from the exposed schema.
    from serena.agent import ActiveModes, SerenaAgent
    from serena.config.context_mode import SerenaAgentContext
    from serena.tools import java_refactor_tool_names

    context = SerenaAgentContext.load_default()
    context.single_project = False
    base_toolset = SerenaAgent._create_base_toolset(
        serena_config=SerenaConfig(gui_log_window=False, web_dashboard=False),
        language_backend=LanguageBackend.LSP,
        context=context,
        modes=ActiveModes(),
        project=None,
    )
    exposed = set(base_toolset.get_tool_names())
    names = set(java_refactor_tool_names())
    assert names.issubset(exposed), f"Java refactor tools missing from multi-project exposed set: {names - exposed}"


def _write_minimal_project(parent: Path, name: str, java_refactor_enabled: bool | None) -> Path:
    """A minimal registered-on-disk Serena project (python language, no Java toolchain needed) whose project.yml
    carries the given java_refactor.enabled opt-in (or omits the key entirely for None).
    """
    root = parent / name
    (root / ".serena").mkdir(parents=True)
    lines = [f"project_name: {name}", "languages:", "  - python"]
    if java_refactor_enabled is not None:
        lines += ["java_refactor:", f"  enabled: {'true' if java_refactor_enabled else 'false'}"]
    (root / ".serena" / "project.yml").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return root


def _patch_capability_negotiation(monkeypatch, supported: set[str] | None) -> None:
    """Forces ``_java_refactor_tool_inclusion`` to negotiate against a fake sidecar advertising ``supported`` ops.

    Real-agent visibility tests must not depend on a live Java sidecar (these projects are python-only), so the
    capability registry is stubbed deterministically; per-op gating is unit-tested in test_tool_inclusion_*.
    """
    import serena.java_refactor.manager as manager_module

    monkeypatch.setattr(
        manager_module,
        "get_or_create_java_refactor_manager",
        lambda *_, **__: _FakeCapabilityManager(supported),
    )


def _all_v2_operations() -> set[str]:
    from serena.tools.java_refactor_v2_tools import java_refactor_v2_capability_tool_operations

    return set(java_refactor_v2_capability_tool_operations().values())


@pytest.mark.parametrize("enabled", [True, False])
def test_java_refactor_tools_single_project_visibility_gated_by_config(tmp_path: Path, enabled: bool, monkeypatch) -> None:
    # Single-project context: java_refactor.enabled gates the EXPOSED (client-visible) schema itself — the tools are
    # advertised and active only when the startup project opts in, and absent from the schema entirely otherwise.
    from serena.agent import SerenaAgent
    from serena.config.context_mode import SerenaAgentContext
    from serena.tools import java_refactor_tool_names

    root = _write_minimal_project(tmp_path, "proj", enabled)
    _patch_capability_negotiation(monkeypatch, _all_v2_operations())
    context = SerenaAgentContext.load_default()
    context.single_project = True
    agent = SerenaAgent(project=str(root), serena_config=SerenaConfig(gui_log_window=False, web_dashboard=False), context=context)
    try:
        agent.execute_task(lambda: None)
        names = set(java_refactor_tool_names())
        assert names, "expected a non-empty set of Java refactor tool names"
        exposed = {name for name in names if agent.tool_is_exposed(name)}
        active = names & set(agent.get_active_tool_names())
        if enabled:
            assert exposed == names, f"missing from single-project exposed schema: {names - exposed}"
            assert active == names, f"missing from single-project active tools: {names - active}"
        else:
            assert not exposed, f"disabled single-project must not expose Java refactor tools: {exposed}"
            assert not active, f"disabled single-project must not activate Java refactor tools: {active}"
    finally:
        agent.on_shutdown(timeout=5)


def test_java_refactor_tools_not_available_without_enabled_active_project(tmp_path: Path, monkeypatch) -> None:
    # Multi-project / no-startup-project context: the exposed schema is a fixed superset (MCP clients get the tool
    # list once at session start, so the tools must be present there or activating an enabled project could never
    # surface them), but AVAILABILITY is config-gated in every state: not active before any activation, not active
    # for an active project without the opt-in, active for an enabled project, and withdrawn again when switching
    # from an enabled to a disabled project. Calling a non-active tool is refused by Tool.apply_ex.
    from serena.agent import SerenaAgent
    from serena.config.context_mode import SerenaAgentContext
    from serena.tools import java_refactor_tool_names

    enabled_root = _write_minimal_project(tmp_path, "enabled_proj", True)
    disabled_root = _write_minimal_project(tmp_path, "disabled_proj", False)
    _patch_capability_negotiation(monkeypatch, _all_v2_operations())
    context = SerenaAgentContext.load_default()
    context.single_project = False
    agent = SerenaAgent(project=None, serena_config=SerenaConfig(gui_log_window=False, web_dashboard=False), context=context)
    try:
        agent.execute_task(lambda: None)
        names = set(java_refactor_tool_names())
        assert names, "expected a non-empty set of Java refactor tool names"
        assert all(agent.tool_is_exposed(name) for name in names), "the fixed schema superset must contain the tools"
        assert names.isdisjoint(agent.get_active_tool_names()), "no active project -> tools must not be available"

        agent.activate_project_from_path_or_name(str(disabled_root))
        assert names.isdisjoint(agent.get_active_tool_names()), "active project without opt-in -> tools must not be available"

        agent.activate_project_from_path_or_name(str(enabled_root))
        active = names & set(agent.get_active_tool_names())
        assert active == names, f"enabled active project -> tools must be available; missing: {names - active}"

        agent.activate_project_from_path_or_name(str(disabled_root))
        assert names.isdisjoint(agent.get_active_tool_names()), "switching enabled -> disabled must withdraw the tools"
    finally:
        agent.on_shutdown(timeout=5)


# ---------------------------------------------------------------------------
# G006 — Degraded-model apply refusal messaging (three distinct paths)
# ---------------------------------------------------------------------------


def test_conventional_fallback_apply_refused_with_accurate_message(tmp_path: Path) -> None:
    # Path 1: conventionalFallbackUsed=True => apply must be refused with code
    # 'degraded_model_apply_refused', and the message must tell the user to fix
    # build-tool extraction or provide explicit source_roots/classpath.
    # It must NOT tell the user to "disable allow_incomplete_analysis" — that
    # flag is irrelevant to this refusal and disabling it does nothing here.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        status=lambda refresh=False: SimpleNamespace(
            ready=False,
            errors=["build-tool extraction failed"],
            project_model={"conventionalFallbackUsed": True, "warnings": []},
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["refusal"]["code"] == "degraded_model_apply_refused"
    msg = result["refusal"]["message"]
    # Must give the user a path to fix the problem.
    assert "build-tool extraction" in msg or "source_roots" in msg, msg
    # Must NOT mislead the user into disabling allow_incomplete_analysis.
    assert "allow_incomplete_analysis" not in msg, (
        "Conventional-fallback refusal must not mention allow_incomplete_analysis — it is irrelevant to this failure: " + msg
    )


def test_incomplete_analysis_opt_in_is_not_refused_by_degraded_model_gate(tmp_path: Path) -> None:
    # Path 2: allow_incomplete_analysis=True with conventionalFallbackUsed=False is an explicit
    # opt-in; apply IS allowed past the degraded-model gate. The baseline/new-error diffing
    # (already implemented in _preview_or_apply_refactor) handles pre-existing errors.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    pre_existing = ["/proj/Other.java:7:9: cannot find symbol"]

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=_covered_scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        # Both baseline and staged carry the same pre-existing error — nothing new is introduced.
        validate_edit=lambda overlay: {
            "accepted": True,
            "ready": False,
            "errors": [],
            "compilerErrors": list(pre_existing),
            "warnings": pre_existing,
        },
        status=lambda refresh=False: SimpleNamespace(
            ready=True,
            errors=[],
            # conventionalFallbackUsed is False: this is not a degraded model, just an
            # incomplete one that the user has explicitly opted into.
            project_model={"conventionalFallbackUsed": False, "warnings": pre_existing},
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, allow_incomplete_analysis=True),
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    # The degraded-model gate must not refuse this path; error-diffing tolerates the pre-existing error.
    assert result.get("refusal", {}).get("code") != "degraded_model_apply_refused", result
    assert result["accepted"] is True, result
    assert result["applied"] is True


def test_annotation_processing_caveat_in_project_model_does_not_trigger_apply_refusal(tmp_path: Path) -> None:
    # Path 3: annotation-processing / generated-source-root caveats appear as warnings in
    # project_model["warnings"]. They must NOT cause _degraded_model_apply_refusal to refuse
    # apply (only conventionalFallbackUsed=True triggers that refusal). These caveats are
    # surfaced as warnings downstream (in preview.warnings) rather than as apply refusals.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    annotation_warning = "annotation processing is enabled but no processor jar is on the classpath"

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []},
        status=lambda refresh=False: SimpleNamespace(
            ready=True,
            errors=[],
            # conventionalFallbackUsed is False; the annotation-processing caveat lives in warnings only.
            project_model={"conventionalFallbackUsed": False, "warnings": [annotation_warning]},
        ),
        scan_references=lambda relative_path, line, column, name_hint=None, **_hints: {
            "accepted": True,
            "references": [{"relativePath": "Main.java", "startOffset": 0, "endOffset": 5}],
        },
    )
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    # The annotation-processing warning in the project model must NOT trigger a degraded-model refusal.
    assert result.get("refusal", {}).get("code") != "degraded_model_apply_refused", result
    assert result["accepted"] is True, result
    assert result["applied"] is True


def test_uncovered_reference_sites_detects_unrewritten_and_remaps_file_rename() -> None:
    # G004: pure coverage logic for the old-key residual check.
    from serena.java_refactor.manager import JavaRefactorManager

    baseline = {
        "sites": [
            {"relativePath": "A.java", "startOffset": 10, "endOffset": 13, "line": 2, "column": 5},
            {"relativePath": "A.java", "startOffset": 40, "endOffset": 43, "line": 5, "column": 9},
        ]
    }
    workspace_edit = {
        "changes": [
            {"path": "A.java", "oldSha256": None, "edits": [{"startOffset": 10, "endOffset": 13, "newText": "X", "kind": "REFERENCE"}]}
        ],
        "fileOperations": [],
    }
    uncovered = JavaRefactorManager._uncovered_reference_sites(baseline, workspace_edit, "X")
    assert [site["startOffset"] for site in uncovered] == [40], uncovered

    # A top-level type rename keys the declaration-file edits to the file's CURRENT (old) path, exactly the path the
    # pre-edit scan reports the site under, so coverage matches without any old->new remap.
    renamed_baseline = {"sites": [{"relativePath": "Old.java", "startOffset": 10, "endOffset": 13}]}
    renamed_edit = {
        "changes": [
            {"path": "Old.java", "oldSha256": None, "edits": [{"startOffset": 10, "endOffset": 13, "newText": "X", "kind": "DECLARATION"}]}
        ],
        "fileOperations": [{"kind": "rename", "oldPath": "Old.java", "newPath": "New.java"}],
    }
    assert JavaRefactorManager._uncovered_reference_sites(renamed_baseline, renamed_edit, "X") == []


def test_generic_rename_routing_previews_by_default_and_does_not_mutate(monkeypatch) -> None:
    # Generic-tool contract change (documented in project.template.yml): with preview_default true (the default), a
    # routed rename_symbol call PREVIEWS — the engine is invoked with apply=False, the LSP mutating path never runs,
    # and the engine's structured result is returned verbatim.
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_rename=True))
    symbol = SimpleNamespace(line=4, column=8)
    java_result = {"accepted": True, "applied": False, "mode": "preview"}
    tool = _make_routing_tool_with_lsp(RenameSymbolTool, config, recorded, monkeypatch, symbol, java_result)

    result = tool.apply("Demo/method", "src/Demo.java", "renamed")

    assert recorded["java_called"] is True
    assert recorded["apply"] is False
    assert recorded["lsp_called"] is False
    assert json.loads(result) == java_result


def test_generic_safe_delete_routing_previews_by_default_and_does_not_mutate(monkeypatch) -> None:
    # Same preview-first contract for the routed safe_delete_symbol path.
    recorded: dict = {}
    config = SimpleNamespace(java_refactor=JavaRefactorConfig(enabled=True, route_generic_safe_delete=True))
    symbol = SimpleNamespace(line=4, column=8, relative_path="src/Demo.java", get_name_path=lambda: "Demo/method")
    java_result = {"accepted": True, "applied": False, "mode": "preview"}
    tool = _make_routing_tool_with_lsp(SafeDeleteSymbol, config, recorded, monkeypatch, symbol, java_result)

    result = tool.apply("Demo/method", "src/Demo.java")

    assert recorded["java_called"] is True
    assert recorded["apply"] is False
    assert recorded["lsp_called"] is False
    assert json.loads(result) == java_result


def test_rename_rolls_back_when_edit_overlaps_but_does_not_replace_identifier(tmp_path: Path) -> None:
    # Old-key verification must require an EXACT rewrite, not span overlap: this crafted edit intersects the baseline
    # identifier span (0,5) but covers only (0,3), so part of the old identifier survives even though the old
    # overlap-based coverage heuristic (and a count-based check — the post-edit scan reports the same count) would
    # have passed. The apply must be refused and rolled back with the stale location reported.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    workspace_edit["changes"][0]["edits"] = [{"startOffset": 0, "endOffset": 3, "newText": "Renamed", "kind": "REFERENCE"}]

    manager = _apply_manager_with_workspace_edit(tmp_path, workspace_edit)
    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["rolledBack"] is True
    assert result["refusal"]["code"] == "rename_old_key_residual"
    assert "exact rewrite" in result["refusal"]["message"]
    assert source.read_text(encoding="utf-8") == original


def test_rename_rolls_back_when_covering_edit_text_is_not_new_name(tmp_path: Path) -> None:
    # Same-count rebinding scenario: the edit FULLY covers the baseline identifier span and the post-edit reference
    # count equals the baseline count, but the replacement text is NOT the requested new name — the original site
    # still binds to the old key. A "post-count not smaller" heuristic passes this; the exact-replacement check must
    # refuse and roll back.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    workspace_edit["changes"][0]["edits"] = [{"startOffset": 0, "endOffset": 5, "newText": "interface", "kind": "REFERENCE"}]

    manager = _apply_manager_with_workspace_edit(tmp_path, workspace_edit)
    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["rolledBack"] is True
    assert result["refusal"]["code"] == "rename_old_key_residual"
    assert source.read_text(encoding="utf-8") == original


def test_rename_rolls_back_when_redeclared_symbol_keeps_old_name(tmp_path: Path) -> None:
    # Secondary post-apply guard: even when every baseline span was exactly rewritten, re-resolving the declaration
    # position must yield the NEW name. A post-edit scan whose resolved target still carries the OLD semantic-key name
    # (same reference count, so the count check alone passes) proves the rename did not take effect and must roll back.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    scan_calls = {"n": 0}

    def scan_references(relative_path, line, column, name_hint=None, **_hints):
        scan_calls["n"] += 1
        # Both the baseline scan and the post-apply re-resolve report the OLD semantic-key name "demo.Main".
        return {
            "accepted": True,
            "target": {"semanticKey": {"kind": "CLASS", "name": "demo.Main"}},
            "references": [{"relativePath": "Main.java", "startOffset": 0, "endOffset": 5}],
        }

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["rolledBack"] is True
    assert result["refusal"]["code"] == "rename_old_key_residual"
    assert "instead of the requested new name" in result["refusal"]["message"]
    assert source.read_text(encoding="utf-8") == original


@pytest.mark.parametrize(
    "sidecar_refusal",
    [
        {"code": "malformed_overlay", "message": "renamedFiles entry is missing newPath"},
        {"code": "project_model_errors", "message": "Maven extraction failed: invalid pom.xml"},
    ],
)
def test_apply_refuses_when_validate_edit_is_refused(tmp_path: Path, sidecar_refusal: dict) -> None:
    # A refused validateEdit carries NO compilerErrors; treating it as "no blocking errors" would skip the validation
    # gate entirely. The apply must refuse hard, preserve the sidecar refusal code/message, and write nothing.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=_covered_scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {"accepted": False, "refusal": dict(sidecar_refusal)},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["editsAlreadyApplied"] is False
    assert result["refusal"]["code"] == "pre_apply_validation_refused"
    assert result["refusal"]["sidecarRefusal"] == sidecar_refusal
    assert sidecar_refusal["code"] in result["refusal"]["message"]
    assert source.read_text(encoding="utf-8") == original


def test_preview_validation_reports_refused_validate_edit_as_not_ready(tmp_path: Path) -> None:
    # Preview validation must fail closed too: a refused validateEdit may not surface as an empty (clean-looking)
    # error list. The report is ready:false and carries the sidecar refusal code/message.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        preview=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {"accepted": False, "refusal": {"code": "malformed_overlay", "message": "bad overlay"}},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=False, validate=True)

    assert result["applied"] is False
    assert result["previewValidation"]["ready"] is False
    assert result["previewValidation"]["refusal"] == {"code": "malformed_overlay", "message": "bad overlay"}
    assert any("malformed_overlay" in error for error in result["previewValidation"]["errors"])
    assert source.read_text(encoding="utf-8") == "class Main {}\n"


def test_preview_validation_resource_findings_are_refused(tmp_path: Path) -> None:
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        preview=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {
            "accepted": True,
            "ready": False,
            "compilerErrors": [],
            "errors": [],
            "resourceFindings": ["src/main/resources/beans.xml -> com.acme.Missing"],
        },
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=False, validate=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["refusal"]["code"] == "validation_findings_not_ready"
    assert result["previewValidation"]["resourceFindings"] == ["src/main/resources/beans.xml -> com.acme.Missing"]
    assert source.read_text(encoding="utf-8") == "class Main {}\n"


def test_apply_prevalidation_resource_findings_refuse_before_write(tmp_path: Path) -> None:
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=_covered_scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=lambda overlay: {
            "accepted": True,
            "ready": False,
            "compilerErrors": [],
            "errors": [],
            "resourceFindings": ["src/main/resources/beans.xml -> com.acme.Missing"],
        },
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["editsAlreadyApplied"] is False
    assert result["refusal"]["code"] == "pre_apply_validation_failed"
    assert result["refusal"]["validation"]["code"] == "validation_findings_not_ready"
    assert result["refusal"]["validation"]["resourceFindings"] == ["src/main/resources/beans.xml -> com.acme.Missing"]
    assert source.read_text(encoding="utf-8") == original


def test_apply_rolls_back_when_post_apply_revalidation_is_refused(tmp_path: Path) -> None:
    # Under allow_incomplete_analysis the post-apply guard re-validates the committed sources. If THAT validateEdit is
    # refused, the committed edit's validity is unknown — it must be treated as a post-validation failure and rolled
    # back, never left in place unverified.
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    empty_overlay_calls = {"n": 0}

    def validate_edit(overlay: dict) -> dict:
        if _is_baseline_overlay(overlay):
            empty_overlay_calls["n"] += 1
            if empty_overlay_calls["n"] > 1:  # the post-apply revalidation pass
                return {"accepted": False, "refusal": {"code": "project_model_errors", "message": "model vanished"}}
        return {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []}

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=_covered_scan_references,
        apply_refactor=lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit},
        validate_edit=validate_edit,
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, allow_incomplete_analysis=True),
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)

    assert result["accepted"] is False
    assert result["rolledBack"] is True
    assert result["refusal"]["code"] == "post_validation_failed"
    assert "project_model_errors" in result["refusal"]["message"]
    assert source.read_text(encoding="utf-8") == original


def test_surrogate_splitting_edit_is_structured_refusal_on_preview_and_apply(tmp_path: Path) -> None:
    # A sidecar edit whose UTF-16 offsets fall inside a surrogate pair must surface as the same structured staging
    # refusals as any other unsafe span — on BOTH paths — and never leak a UnicodeError or touch the file.
    source = tmp_path / "Main.java"
    source.write_text("// a\N{GRINNING FACE}b\nclass Main {}\n", encoding="utf-8")
    original = source.read_bytes()
    workspace_edit = _single_edit_workspace_edit(source)
    # Offset 5 lands between the emoji's high and low surrogates ("// a" is 4 UTF-16 units, the pair occupies 4-6).
    workspace_edit["changes"][0]["edits"] = [{"startOffset": 5, "endOffset": 6, "newText": "x", "kind": "REPLACE"}]

    manager = _apply_manager_with_workspace_edit(tmp_path, workspace_edit)
    fake_client = manager._client
    cast(Any, fake_client).preview = lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit}

    preview_result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=False, validate=False)
    assert preview_result["accepted"] is False
    assert preview_result["refusal"]["code"] == "preview_unsafe_edit"
    assert "surrogate pair" in preview_result["refusal"]["message"]

    apply_result = manager.semantic_rename("Main.java", 1, 1, "Renamed", apply=True)
    _assert_apply_unsafe_edit_refusal(apply_result)
    assert "surrogate pair" in apply_result["refusal"]["message"]
    assert source.read_bytes() == original


def test_target_hints_from_lsp_symbol_derives_name_kind_and_arity() -> None:
    from serena.java_refactor.manager import target_hints_from_lsp_symbol
    from solidlsp.ls_types import SymbolKind

    method = SimpleNamespace(name="calc(Map<String, Integer>, int)", symbol_kind=SymbolKind.Method)
    assert target_hints_from_lsp_symbol(method) == {"nameHint": "calc", "kindHint": "method", "arityHint": 2}

    no_arg_constructor = SimpleNamespace(name="Demo()", symbol_kind=SymbolKind.Constructor)
    assert target_hints_from_lsp_symbol(no_arg_constructor) == {"nameHint": "Demo", "kindHint": "constructor", "arityHint": 0}

    field = SimpleNamespace(name="amount", symbol_kind=SymbolKind.Field)
    assert target_hints_from_lsp_symbol(field) == {"nameHint": "amount", "kindHint": "field"}

    # Unknown/unmappable kinds and empty names derive nothing rather than guessing (a wrong hint would refuse a
    # valid request; a missing hint merely verifies nothing).
    unmapped = SimpleNamespace(name="thing", symbol_kind=SymbolKind.Event)
    assert target_hints_from_lsp_symbol(unmapped) == {"nameHint": "thing"}
    assert target_hints_from_lsp_symbol(SimpleNamespace(name="", symbol_kind=None)) == {}


def test_target_hints_are_forwarded_to_sidecar_params_and_baseline_scan(tmp_path: Path) -> None:
    # The identity hints must reach the sidecar operation params (where the planners verify them) AND the rename
    # baseline reference scan (so both resolve/verify against the same caller-named symbol).
    source = tmp_path / "Main.java"
    source.write_text("class Main {}\n", encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    recorded: dict = {}

    def apply_refactor(operation, params):
        recorded["params"] = params
        return {"accepted": True, "workspaceEdit": workspace_edit}

    def scan_references(relative_path, line, column, name_hint=None, kind_hint=None, arity_hint=None):
        recorded["scan_name_hint"] = name_hint
        recorded["scan_kind_hint"] = kind_hint
        recorded["scan_arity_hint"] = arity_hint
        return {"accepted": True, "references": [{"relativePath": "Main.java", "startOffset": 0, "endOffset": 5}]}

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=scan_references,
        apply_refactor=apply_refactor,
        validate_edit=lambda overlay: {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename(
        "Main.java", 1, 1, "Renamed", apply=True, target_hints={"nameHint": "Main", "kindHint": "type", "arityHint": None}
    )

    assert result["accepted"] is True, result
    assert recorded["params"]["nameHint"] == "Main"
    assert recorded["params"]["kindHint"] == "type"
    assert "arityHint" not in recorded["params"]  # None-valued hints are dropped, not sent
    assert recorded["scan_name_hint"] == "Main"
    # The baseline scan must receive the SAME identity the planner verifies, not name alone.
    assert recorded["scan_kind_hint"] == "type"
    assert recorded["scan_arity_hint"] is None


def test_rename_baseline_scan_forwards_full_identity_for_overloaded_method(tmp_path: Path) -> None:
    # Regression for HB-1: for an overloaded method, name alone is ambiguous. The baseline reference scan must
    # forward kindHint AND arityHint so it resolves the same overload the rename plans against, rather than binding
    # to a same-name sibling overload and computing the old-key residual baseline under weaker identity rules.
    source = tmp_path / "Main.java"
    source.write_text("class Main { void f() {} void f(int x) {} }\n", encoding="utf-8")
    workspace_edit = _single_edit_workspace_edit(source)
    recorded: dict = {}

    def apply_refactor(operation, params):
        recorded["params"] = params
        return {"accepted": True, "workspaceEdit": workspace_edit}

    def scan_references(relative_path, line, column, name_hint=None, kind_hint=None, arity_hint=None):
        # Fail the test loudly if the baseline is computed without the disambiguating identity.
        assert kind_hint is not None, "scanReferences invoked without kindHint"
        assert arity_hint is not None, "scanReferences invoked without arityHint"
        recorded["scan_hints"] = (name_hint, kind_hint, arity_hint)
        return {"accepted": True, "references": [{"relativePath": "Main.java", "startOffset": 0, "endOffset": 5}]}

    fake_client = SimpleNamespace(
        is_running=lambda: True,
        scan_references=scan_references,
        apply_refactor=apply_refactor,
        validate_edit=lambda overlay: {"accepted": True, "ready": True, "errors": [], "compilerErrors": [], "warnings": []},
        status=lambda refresh=False: SimpleNamespace(
            ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []}
        ),
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    manager._client = fake_client  # type: ignore[assignment]

    result = manager.semantic_rename(
        "Main.java", 1, 19, "Renamed", apply=True, target_hints={"nameHint": "f", "kindHint": "method", "arityHint": 1}
    )

    assert result["accepted"] is True, result
    assert recorded["scan_hints"] == ("f", "method", 1)



def test_java_refactor_v2_dispatch_uses_semantic_planning_model_for_all_planners():
    main_source = (
        Path(__file__).resolve().parents[2]
        / "java-refactor/src/main/java/io/serena/javarefactor/protocol/Main.java"
    ).read_text(encoding="utf-8")

    assert "try (SemanticIndex ignored = SemanticIndex.open(projectModel, relativePath))" in main_source

    operation_block = main_source[
        main_source.index("    private String changeSignatureJson") : main_source.index("    private String validateEditJson")
    ]
    assert "discoverModel())." not in operation_block
    assert "= discoverModel();" not in operation_block

    required_dispatches = [
        "new ChangeSignaturePlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).changeSignature",
        "new ChangeSignaturePlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).introduceParameter",
        "new MoveMemberPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).moveStaticMember",
        "new MoveMemberPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).moveInstanceMethod",
        "new PullPushMemberPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).pullUpMember",
        "new PullPushMemberPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).pushDownMember",
        "new ExtractMethodPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).extractMethod",
        "new InlineMethodPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).inlineMethod",
        "new ExtractInterfacePlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).extractInterface",
        "new FieldRefactorPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).introduceField",
        "new EncapsulateFieldPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).encapsulateField",
        "JavaProjectModel projectModel = discoverSemanticPlanningModel(fields);",
    ]
    for dispatch in required_dispatches:
        assert dispatch in operation_block

def test_java_refactor_v2_module_exports_designed_tool_contracts() -> None:
    import inspect

    from serena.tools import JavaMoveInstanceMethodTool as PackageMoveInstanceMethodTool
    from serena.tools.java_refactor_v2_tools import (
        JavaChangeSignatureTool,
        JavaEncapsulateFieldTool,
        JavaExtractInterfaceTool,
        JavaExtractMethodTool,
        JavaMoveInstanceMethodTool,
        JavaMoveStaticMemberTool,
        JavaPullUpMemberTool,
        JavaPushDownMemberTool,
    )

    assert PackageMoveInstanceMethodTool is JavaMoveInstanceMethodTool
    assert JavaMoveInstanceMethodTool.__module__ == "serena.tools.java_refactor_v2_tools"

    expected_signatures = {
        JavaChangeSignatureTool: [
            "self",
            "name_path",
            "relative_path",
            "new_name",
            "new_return_type",
            "parameters_json",
            "default_values_json",
            "line",
            "column",
            "preview",
            "validate",
            "remove_parameters_json",
            "confirm_public_api",
            "return_conversion",
            "body_return_conversion",
        ],
        JavaMoveStaticMemberTool: [
            "self",
            "name_path",
            "relative_path",
            "target_type",
            "new_name",
            "allow_access_widening",
            "allow_security_sensitive_private_widening",
            "preview",
            "validate",
        ],
        JavaMoveInstanceMethodTool: [
            "self",
            "name_path",
            "relative_path",
            "target_parameter_name",
            "target_field_name",
            "target_receiver",
            "receiver_selection_json",
            "target_type",
            "new_name",
            "rewrite_call_sites",
            "leave_delegate",
            "allow_access_widening",
            "allow_security_sensitive_private_widening",
            "preview",
            "validate",
        ],
        JavaPullUpMemberTool: [
            "self",
            "name_path",
            "relative_path",
            "target_supertype",
            "make_abstract",
            "leave_delegate",
            "allow_access_widening",
            "allow_security_sensitive_private_widening",
            "confirm_serialization_impact",
            "preview",
            "validate",
        ],
        JavaPushDownMemberTool: [
            "self",
            "name_path",
            "relative_path",
            "target_subtypes_json",
            "remove_from_source",
            "allow_access_widening",
            "allow_security_sensitive_private_widening",
            "confirm_serialization_impact",
            "include_indirect_subtypes",
            "preview",
            "validate",
        ],
        JavaExtractMethodTool: [
            "self",
            "relative_path",
            "start_line",
            "start_col",
            "end_line",
            "end_col",
            "new_method_name",
            "visibility",
            "make_static",
            "preview",
            "validate",
        ],
        JavaExtractInterfaceTool: [
            "self",
            "name_path",
            "relative_path",
            "interface_name",
            "target_package",
            "members_json",
            "replace_usages",
            "confirm_public_api_change",
            "preview",
            "validate",
        ],
        JavaEncapsulateFieldTool: [
            "self",
            "name_path",
            "relative_path",
            "getter_name",
            "setter_name",
            "setter",
            "update_usages",
            "preview",
            "validate",
        ],
    }
    for tool_cls, expected in expected_signatures.items():
        assert list(inspect.signature(tool_cls.apply).parameters) == expected

    move_instance_defaults = inspect.signature(JavaMoveInstanceMethodTool.apply).parameters
    assert move_instance_defaults["rewrite_call_sites"].default is True
    assert move_instance_defaults["leave_delegate"].default is True
    pull_up_defaults = inspect.signature(JavaPullUpMemberTool.apply).parameters
    assert pull_up_defaults["leave_delegate"].default is False
    push_down_defaults = inspect.signature(JavaPushDownMemberTool.apply).parameters
    assert push_down_defaults["target_subtypes_json"].default == "[]"
    assert push_down_defaults["remove_from_source"].default is False
    encapsulate_defaults = inspect.signature(JavaEncapsulateFieldTool.apply).parameters
    assert encapsulate_defaults["update_usages"].default is True


def test_java_refactor_v2_tool_class_tuple_exports_full_public_surface() -> None:
    from serena.tools.java_refactor_v2_tools import JAVA_REFACTOR_V2_TOOL_CLASSES

    exported_names = {tool_class.__name__ for tool_class in JAVA_REFACTOR_V2_TOOL_CLASSES}

    assert exported_names == {
        "JavaApplyRefactorSessionTool",
        "JavaCancelRefactorSessionTool",
        "JavaChangeSignatureTool",
        "JavaCreateRefactorSessionTool",
        "JavaEncapsulateFieldTool",
        "JavaExtractInterfaceTool",
        "JavaExtractMethodTool",
        "JavaGetRefactorSessionEditTool",
        "JavaInlineMethodTool",
        "JavaIntroduceFieldTool",
        "JavaIntroduceParameterTool",
        "JavaMoveInstanceMethodTool",
        "JavaMoveStaticMemberTool",
        "JavaPullUpMemberTool",
        "JavaPushDownMemberTool",
    }


def test_java_refactor_v2_tools_are_registered_as_public_mcp_surface() -> None:
    from serena.tools.tools_base import ToolRegistry

    required_tools = {
        "java_create_refactor_session": "JavaCreateRefactorSessionTool",
        "java_get_refactor_session_edit": "JavaGetRefactorSessionEditTool",
        "java_apply_refactor_session": "JavaApplyRefactorSessionTool",
        "java_cancel_refactor_session": "JavaCancelRefactorSessionTool",
        "java_change_signature": "JavaChangeSignatureTool",
        "java_introduce_parameter": "JavaIntroduceParameterTool",
        "java_move_static_member": "JavaMoveStaticMemberTool",
        "java_move_instance_method": "JavaMoveInstanceMethodTool",
        "java_pull_up_member": "JavaPullUpMemberTool",
        "java_push_down_member": "JavaPushDownMemberTool",
        "java_inline_method": "JavaInlineMethodTool",
        "java_extract_method": "JavaExtractMethodTool",
        "java_extract_interface": "JavaExtractInterfaceTool",
        "java_introduce_field": "JavaIntroduceFieldTool",
        "java_encapsulate_field": "JavaEncapsulateFieldTool",
    }
    registered_tools = ToolRegistry()._tool_dict

    missing_tools = required_tools.keys() - registered_tools.keys()
    wrong_classes = {
        name: registered_tools[name].tool_class.__name__
        for name, expected_class in required_tools.items()
        if name in registered_tools and registered_tools[name].tool_class.__name__ != expected_class
    }

    assert missing_tools == set()
    assert wrong_classes == {}


def test_java_refactor_v2_tools_adapt_designed_names_to_session_params(monkeypatch) -> None:
    from serena.tools.java_refactor_v2_tools import (
        JavaEncapsulateFieldTool,
        JavaMoveInstanceMethodTool,
        JavaPullUpMemberTool,
        JavaPushDownMemberTool,
    )

    captured: list[dict] = []

    def capture_session(self, operation, relative_path, name_path, line, column, preview, validate, params):
        captured.append(
            {
                "operation": operation,
                "relative_path": relative_path,
                "name_path": name_path,
                "line": line,
                "column": column,
                "preview": preview,
                "validate": validate,
                "params": params,
            }
        )
        return '{"accepted": false, "refusal": {"code": "stub", "message": "stub"}}'

    monkeypatch.setattr("serena.tools.java_refactor_tools._JavaRefactorToolBase._session_refactor", capture_session)

    object.__new__(JavaMoveInstanceMethodTool).apply(
        name_path="Demo/moveMe",
        relative_path="src/Demo.java",
        target_parameter_name="target",
        new_name="moved",
        rewrite_call_sites=False,
        leave_delegate=False,
        preview=False,
        validate=False,
    )
    object.__new__(JavaPullUpMemberTool).apply(
        name_path="Demo/member",
        relative_path="src/Demo.java",
        target_supertype="com.acme.Base",
    )
    object.__new__(JavaPushDownMemberTool).apply(
        name_path="Demo/member",
        relative_path="src/Demo.java",
        target_subtypes_json='["com.acme.Child"]',
    )
    object.__new__(JavaEncapsulateFieldTool).apply(
        name_path="Demo/field",
        relative_path="src/Demo.java",
        update_usages=False,
    )

    assert captured[0]["operation"] == "moveInstanceMethod"
    assert captured[0]["preview"] is False
    assert captured[0]["validate"] is False
    assert captured[0]["params"] == {
        "targetParameter": "target",
        "newName": "moved",
        "rewriteCallSites": False,
        "leaveDelegate": False,
        "keepDelegate": False,
        "allowAccessWidening": False,
        "allowSecuritySensitivePrivateWidening": False,
    }
    assert captured[1]["params"] == {
        "targetSupertype": "com.acme.Base",
        "targetType": "com.acme.Base",
        "makeAbstract": False,
        "leaveDelegate": False,
        "allowAccessWidening": False,
        "allowSecuritySensitivePrivateWidening": False,
        "confirmSerializationImpact": False,
    }
    assert captured[2]["params"] == {
        "targetSubtypes": ["com.acme.Child"],
        "targetTypes": ["com.acme.Child"],
        "removeFromSource": False,
        "allowAccessWidening": False,
        "allowSecuritySensitivePrivateWidening": False,
        "confirmSerializationImpact": False,
        "includeIndirectSubtypes": False,
    }
    assert captured[3]["params"] == {
        "getterName": None,
        "setterName": None,
        "setter": True,
        "updateUsages": False,
        "updateReferences": False,
    }


def test_java_introduce_field_forwards_constructor_strategy(monkeypatch):
    from serena.tools.java_refactor_v2_tools import JavaIntroduceFieldTool

    captured = []

    def capture_session(self, operation, relative_path, name_path, line, column, preview, validate, params):
        captured.append(
            {
                "operation": operation,
                "relative_path": relative_path,
                "name_path": name_path,
                "preview": preview,
                "validate": validate,
                "params": params,
            }
        )
        return '{"accepted": true}'

    monkeypatch.setattr("serena.tools.java_refactor_tools._JavaRefactorToolBase._session_refactor", capture_session)

    object.__new__(JavaIntroduceFieldTool).apply(
        name_path="Demo/read",
        relative_path="src/Demo.java",
        field_name="label",
        field_type="String",
        initializer='"value"',
        initialize_in_constructor=True,
        constructor_strategy="allTerminal",
        preview=True,
        validate=True,
    )

    assert captured == [
        {
            "operation": "introduceField",
            "relative_path": "src/Demo.java",
            "name_path": "Demo/read",
            "preview": True,
            "validate": True,
            "params": {
                "fieldName": "label",
                "fieldType": "String",
                "initializer": '"value"',
                "selection": None,
                "constant": False,
                "initializeInConstructor": True,
                "constructorStrategy": "allTerminal",
            },
        }
    ]


# ---------------------------------------------------------------------------
# G005 — hard-gate parameter threading tests
# ---------------------------------------------------------------------------


def _make_v2_tool_with_recorder(tool_cls, monkeypatch):
    """Build a V2 tool wired to a recording v2_refactor_session stub."""
    recorded: dict = {}
    tool = _make_java_tool(tool_cls, recorded, monkeypatch, SimpleNamespace(line=4, column=8))

    def v2_refactor_session(operation_name, params, apply=False, validate=None):
        recorded["operation"] = operation_name
        recorded["params"] = params
        return {"accepted": False, "refusal": {"code": "unsupported_operation", "message": "stub"}}

    cast(Any, tool.create_java_refactor_client()).v2_refactor_session = v2_refactor_session
    return tool, recorded


def test_change_signature_tool_threads_remove_parameters(monkeypatch) -> None:
    from serena.tools import JavaChangeSignatureTool

    tool, recorded = _make_v2_tool_with_recorder(JavaChangeSignatureTool, monkeypatch)
    tool.apply(
        relative_path="Foo.java",
        name_path="Foo/bar",
        remove_parameters_json='["x"]',
    )
    assert recorded["params"].get("removeParameters") == ["x"]


def test_change_signature_tool_threads_confirm_public_api(monkeypatch) -> None:
    from serena.tools import JavaChangeSignatureTool

    tool, recorded = _make_v2_tool_with_recorder(JavaChangeSignatureTool, monkeypatch)
    tool.apply(
        relative_path="Foo.java",
        name_path="Foo/bar",
        confirm_public_api=True,
    )
    assert recorded["params"].get("confirmPublicApi") is True


def test_change_signature_tool_threads_return_conversion(monkeypatch) -> None:
    from serena.tools import JavaChangeSignatureTool

    tool, recorded = _make_v2_tool_with_recorder(JavaChangeSignatureTool, monkeypatch)
    tool.apply(
        relative_path="Foo.java",
        name_path="Foo/bar",
        return_conversion="(int) $return",
    )
    assert recorded["params"].get("returnConversion") == "(int) $return"


def test_move_static_member_tool_threads_security_widening(monkeypatch) -> None:
    from serena.tools import JavaMoveStaticMemberTool

    tool, recorded = _make_v2_tool_with_recorder(JavaMoveStaticMemberTool, monkeypatch)
    tool.apply(
        relative_path="Foo.java",
        name_path="Foo/CONSTANT",
        target_type="com.acme.Other",
        allow_security_sensitive_private_widening=True,
    )
    assert recorded["params"].get("allowSecuritySensitivePrivateWidening") is True


def test_move_instance_method_tool_threads_access_widening(monkeypatch) -> None:
    from serena.tools import JavaMoveInstanceMethodTool

    tool, recorded = _make_v2_tool_with_recorder(JavaMoveInstanceMethodTool, monkeypatch)
    tool.apply(
        relative_path="Foo.java",
        name_path="Foo/process",
        allow_access_widening=True,
    )
    assert recorded["params"].get("allowAccessWidening") is True


def test_move_instance_method_tool_threads_security_widening(monkeypatch) -> None:
    from serena.tools import JavaMoveInstanceMethodTool

    tool, recorded = _make_v2_tool_with_recorder(JavaMoveInstanceMethodTool, monkeypatch)
    tool.apply(
        relative_path="Foo.java",
        name_path="Foo/process",
        allow_security_sensitive_private_widening=True,
    )
    assert recorded["params"].get("allowSecuritySensitivePrivateWidening") is True


def test_pull_up_member_tool_threads_access_widening(monkeypatch) -> None:
    from serena.tools import JavaPullUpMemberTool

    tool, recorded = _make_v2_tool_with_recorder(JavaPullUpMemberTool, monkeypatch)
    tool.apply(
        relative_path="Child.java",
        name_path="Child/method",
        target_supertype="com.acme.Base",
        allow_access_widening=True,
    )
    assert recorded["params"].get("allowAccessWidening") is True


def test_pull_up_member_tool_threads_security_widening(monkeypatch) -> None:
    from serena.tools import JavaPullUpMemberTool

    tool, recorded = _make_v2_tool_with_recorder(JavaPullUpMemberTool, monkeypatch)
    tool.apply(
        relative_path="Child.java",
        name_path="Child/method",
        target_supertype="com.acme.Base",
        allow_security_sensitive_private_widening=True,
    )
    assert recorded["params"].get("allowSecuritySensitivePrivateWidening") is True


def test_pull_up_member_tool_threads_serialization_impact(monkeypatch) -> None:
    from serena.tools import JavaPullUpMemberTool

    tool, recorded = _make_v2_tool_with_recorder(JavaPullUpMemberTool, monkeypatch)
    tool.apply(
        relative_path="Child.java",
        name_path="Child/field",
        target_supertype="com.acme.Base",
        confirm_serialization_impact=True,
    )
    assert recorded["params"].get("confirmSerializationImpact") is True


def test_push_down_member_tool_threads_indirect_subtypes(monkeypatch) -> None:
    from serena.tools import JavaPushDownMemberTool

    tool, recorded = _make_v2_tool_with_recorder(JavaPushDownMemberTool, monkeypatch)
    tool.apply(
        relative_path="Base.java",
        name_path="Base/field",
        include_indirect_subtypes=True,
    )
    assert recorded["params"].get("includeIndirectSubtypes") is True


def test_push_down_member_tool_threads_serialization_impact(monkeypatch) -> None:
    from serena.tools import JavaPushDownMemberTool

    tool, recorded = _make_v2_tool_with_recorder(JavaPushDownMemberTool, monkeypatch)
    tool.apply(
        relative_path="Base.java",
        name_path="Base/field",
        confirm_serialization_impact=True,
    )
    assert recorded["params"].get("confirmSerializationImpact") is True


def test_extract_interface_tool_threads_confirm_public_api_change(monkeypatch) -> None:
    from serena.tools import JavaExtractInterfaceTool

    tool, recorded = _make_v2_tool_with_recorder(JavaExtractInterfaceTool, monkeypatch)
    tool.apply(
        relative_path="Service.java",
        name_path="Service",
        interface_name="IService",
        replace_usages=True,
        confirm_public_api_change=True,
    )
    assert recorded["params"].get("confirmPublicApiChange") is True


# --- G001: capability-aware V2 tool registration -------------------------------------------------


class _CapabilityFakeClient:
    def __init__(self, registry: dict[str, object]) -> None:
        self._registry = registry

    def capabilities(self) -> dict[str, object]:
        return {"capabilities": self._registry}


def _capability_manager(tmp_path: Path, monkeypatch, client_or_error) -> JavaRefactorManager:
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda *_, **__: None)

    def _start(*_, **__):
        if isinstance(client_or_error, Exception):
            raise client_or_error
        return client_or_error

    monkeypatch.setattr(manager, "_get_or_start_client", _start)
    return manager


def test_supported_v2_operations_returns_supported_subset(tmp_path: Path, monkeypatch) -> None:
    client = _CapabilityFakeClient(
        {
            "changeSignature": {"level": "beta", "status": "supported"},
            "extractMethod": {"level": "experimental", "status": "preview"},
            "inlineMethod": {"level": "stable", "status": "supported"},
            "refactorSessions": {"level": "beta", "status": "supported"},
        }
    )
    manager = _capability_manager(tmp_path, monkeypatch, client)

    supported = manager.supported_v2_operations()

    assert supported == {"changeSignature", "inlineMethod"}


def test_supported_v2_operations_returns_none_on_sidecar_startup_failure(tmp_path: Path, monkeypatch) -> None:
    manager = _capability_manager(tmp_path, monkeypatch, JavaRefactorRuntimeError("sidecar would not start"))

    assert manager.supported_v2_operations() is None
    assert manager._initialization_error is not None


def test_supported_v2_operations_returns_none_on_malformed_registry(tmp_path: Path, monkeypatch) -> None:
    class _MalformedClient:
        def capabilities(self) -> dict[str, object]:
            return {"capabilities": "not-a-mapping"}

    manager = _capability_manager(tmp_path, monkeypatch, _MalformedClient())

    assert manager.supported_v2_operations() is None


class _FakeCapabilityManager:
    def __init__(self, supported: set[str] | None) -> None:
        self._supported = supported

    def supported_v2_operations(self) -> set[str] | None:
        return self._supported


def _java_project(tmp_path: Path, *, enabled: bool) -> Project:
    project_config = ProjectConfig(
        project_name="java-test",
        languages=[Language.JAVA],
        java_refactor=JavaRefactorConfig(enabled=enabled),
    )
    return Project(
        project_root=str(tmp_path),
        project_config=project_config,
        serena_config=SerenaConfig(gui_log_window=False, web_dashboard=False),
    )


def _tool_inclusion(project: Project | None, supported: set[str] | None, monkeypatch):
    import serena.java_refactor.manager as manager_module
    from serena.agent import SerenaAgent

    monkeypatch.setattr(
        manager_module,
        "get_or_create_java_refactor_manager",
        lambda *_, **__: _FakeCapabilityManager(supported),
    )
    return SerenaAgent._java_refactor_tool_inclusion(project, LanguageBackend.LSP)


def test_tool_inclusion_registers_supported_subset_only(tmp_path: Path, monkeypatch) -> None:
    project = _java_project(tmp_path, enabled=True)
    inclusion = _tool_inclusion(project, {"changeSignature", "inlineMethod"}, monkeypatch)
    included = set(inclusion.included_optional_tools)
    excluded = set(inclusion.excluded_tools)

    # supported V2 op tools registered
    assert "java_change_signature" in included
    assert "java_inline_method" in included
    # unsupported V2 op tool NOT registered (and explicitly excluded)
    assert "java_extract_method" not in included
    assert "java_extract_method" in excluded
    # always-on tools (status/debug, session lifecycle) always present
    assert "java_refactor_status" in included
    assert "java_create_refactor_session" in included
    assert included.isdisjoint(excluded)


def test_tool_inclusion_disables_all_v2_ops_when_capabilities_unavailable(tmp_path: Path, monkeypatch) -> None:
    project = _java_project(tmp_path, enabled=True)
    inclusion = _tool_inclusion(project, None, monkeypatch)
    included = set(inclusion.included_optional_tools)
    excluded = set(inclusion.excluded_tools)

    # no V2 operation tools registered when the sidecar capability registry is unavailable
    for v2_op in (
        "java_change_signature",
        "java_inline_method",
        "java_extract_method",
        "java_encapsulate_field",
    ):
        assert v2_op not in included
        assert v2_op in excluded
    # status/debug tool remains reachable for diagnosis
    assert "java_refactor_status" in included


def test_tool_inclusion_excludes_everything_when_disabled(tmp_path: Path, monkeypatch) -> None:
    project = _java_project(tmp_path, enabled=False)
    inclusion = _tool_inclusion(project, {"changeSignature"}, monkeypatch)

    assert not inclusion.included_optional_tools
    assert "java_change_signature" in set(inclusion.excluded_tools)
    assert "java_refactor_status" in set(inclusion.excluded_tools)


def test_tool_inclusion_excludes_everything_without_active_project(tmp_path: Path, monkeypatch) -> None:
    inclusion = _tool_inclusion(None, {"changeSignature"}, monkeypatch)

    assert not inclusion.included_optional_tools
    assert "java_change_signature" in set(inclusion.excluded_tools)


def test_workspace_rejects_v3_operation_without_project_revision():
    from serena.java_refactor.workspace_edit import RefactorWorkspaceEdit
    from serena.java_refactor_v3.models import RiskLevel
    from serena.java_refactor_v3.workspace import TransformationWorkspace, V3OperationPlan

    class Driver:
        def plan_v3_operation(self, operation, params):
            return V3OperationPlan(
                operation=operation,
                project_revision=None,
                workspace_edit=RefactorWorkspaceEdit(),
                risk=RiskLevel.SAFE,
                warnings=[],
            )

    result = TransformationWorkspace("w1", Driver()).add_operation("package.rename", {})

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "workspace_revision_mismatch"
    assert "project revision" in result["refusal"]["message"]


def test_impact_report_include_resources_filters_v3_resource_impact():
    manager = JavaRefactorManager.__new__(JavaRefactorManager)
    manager._config = SimpleNamespace(enabled=True)
    manager._project_root = Path("/tmp/project")
    manager._validate_supported_project = lambda: None
    manager._get_or_start_client = lambda refresh=False: object()

    class Workspaces:
        def impact_report(self, workspace_id, build):
            return {
                "accepted": True,
                "report": {
                    "summary": {"changedFiles": 1},
                    "semanticImpact": {"types": []},
                    "resourceImpact": {"resources": ["src/main/resources/app.yml"]},
                    "tests": {"incoming": []},
                    "warnings": [],
                },
                "risk": "safe",
                "projectRevision": "rev-1",
                "javacFactsValidated": True,
            }

    manager._transformation_workspaces = Workspaces()

    result = manager.transformation_workspace_impact_report("w1", include_resources=False)

    assert result["accepted"] is True
    assert "resourceImpact" not in result["report"]
    assert "summary" in result["report"]


def test_v3_analysis_invariants_fail_closed_without_project_revision():
    manager = JavaRefactorManager.__new__(JavaRefactorManager)

    result = manager._with_v3_analysis_invariants({"accepted": True, "mode": "scan"}, "findDeadCode")

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "V3_ANALYSIS_INVARIANT_MISSING"
    assert result["refusal"]["missing"] == ["projectRevision", "impact", "riskClassification", "validation"]
