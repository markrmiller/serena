"""G001: the sidecar initialize contract (nested params, structured config, encoding, javaHome, ignoredPatterns).

These tests pin the designed initialize shape end-to-end: the Python model serializes a single nested ``params`` object
with the planned fields; the sidecar accepts and consumes each one; and ``ignoredPatterns`` is real, configurable
source-discovery behavior rather than a hard-coded exclusion list.
"""

import json
from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _run_status(*args: object, **kwargs: object):
    from test.serena._java_refactor_sidecar_helpers import run_status

    return run_status(*args, **kwargs)


def test_initialize_params_serialize_nested_params_object() -> None:
    # The model must emit a single nested `params` object (not flattened top-level fields). Only set fields appear.
    minimal = JavaRefactorInitializeParams(project_root="/p", configuration="default").to_protocol_dict()
    assert minimal == {"params": {"projectRoot": "/p", "configuration": "default"}}

    full = JavaRefactorInitializeParams(
        project_root="/p",
        configuration="default",
        config={"release": "17"},
        encoding="US-ASCII",
        java_home="/opt/jdk",
        ignored_patterns=[".git", "out"],
        project_data_dir="/data",
    ).to_protocol_dict()
    assert full == {
        "params": {
            "projectRoot": "/p",
            "configuration": "default",
            "config": {"release": "17"},
            "encoding": "US-ASCII",
            "javaHome": "/opt/jdk",
            "ignoredPatterns": [".git", "out"],
            "projectDataDir": "/data",
        }
    }


def test_sidecar_consumes_structured_config_object(sidecar_jar: Path, tmp_path: Path) -> None:
    # The structured `config` object (no legacy `configuration` string) must be consumed by the discoverer: a release
    # and encoding supplied via `config` reach javac options exactly as the legacy flat string did.
    (tmp_path / "App.java").write_text("public class App {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                configuration="default",
                config={"release": "17", "encoding": "US-ASCII"},
            )
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    assert payload["ready"] is True, payload["errors"]
    source_set = payload["project_model"]["sourceSets"][0]
    assert source_set["release"] == "17"
    assert source_set["encoding"] == "US-ASCII"


def test_sidecar_consumes_nested_java_refactor_model(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "custom-src" / "demo").mkdir(parents=True)
    (tmp_path / "custom-src" / "demo" / "App.java").write_text("package demo; public class App {}\n", encoding="utf-8")
    (tmp_path / "Other.java").write_text("public class Other {}\n", encoding="utf-8")
    (tmp_path / "gen").mkdir()
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                configuration="default",
                config={
                    "java_refactor": {
                        "model": {
                            "modules": [
                                {
                                    "project": ":app",
                                    "sourceSets": [
                                        {
                                            "name": "main",
                                            "srcDirs": ["custom-src"],
                                            "generatedRoots": ["gen"],
                                            "compilerArgs": ["-parameters"],
                                        }
                                    ],
                                }
                            ]
                        }
                    }
                },
            )
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    assert payload["ready"] is True, payload["errors"]
    assert payload["project_model"]["discoveryKind"] == "explicit"
    assert payload["project_model"]["allJavaFiles"] == ["custom-src/demo/App.java"]
    source_set = payload["project_model"]["sourceSets"][0]
    assert source_set["generatedRoots"] == ["gen"]
    assert "-parameters" in source_set["javacOptions"]


def test_sidecar_top_level_encoding_overrides_config(sidecar_jar: Path, tmp_path: Path) -> None:
    # The top-level `encoding` field overlays the structured config's encoding: the discoverer must use the top-level one.
    (tmp_path / "App.java").write_text("public class App {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                configuration="default",
                config={"encoding": "ISO-8859-1"},
                encoding="US-ASCII",
            )
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    assert payload["ready"] is True, payload["errors"]
    assert payload["project_model"]["sourceSets"][0]["encoding"] == "US-ASCII"


def test_sidecar_echoes_java_home(sidecar_jar: Path, tmp_path: Path) -> None:
    # javaHome must be accepted and consumed: the sidecar echoes it back in status so callers can confirm the JDK home.
    (tmp_path / "App.java").write_text("public class App {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path), configuration="default", java_home="/opt/example-jdk"
            )
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    assert payload["java_home"] == "/opt/example-jdk"


def test_ignored_patterns_prune_additional_directory(sidecar_jar: Path, tmp_path: Path) -> None:
    # ignoredPatterns is real behavior: a directory that is NOT in the default exclusion set is pruned when configured.
    (tmp_path / "Keep.java").write_text("public class Keep {}\n", encoding="utf-8")
    (tmp_path / "drop").mkdir()
    (tmp_path / "drop" / "Drop.java").write_text("public class Drop {}\n", encoding="utf-8")

    # Default discovery sees both files (the `drop` directory is not in the built-in exclusion set).
    default_status = _run_status(sidecar_jar, tmp_path)
    assert default_status["project_model"]["javaFileCount"] == 2, default_status["project_model"]["allJavaFiles"]

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path), configuration="default", ignored_patterns=[".git", "drop"]
            )
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    assert payload["project_model"]["allJavaFiles"] == ["Keep.java"], payload["project_model"]["allJavaFiles"]


def test_empty_ignored_patterns_prunes_nothing(sidecar_jar: Path, tmp_path: Path) -> None:
    # An explicit empty ignoredPatterns means "prune nothing": a file under the default-excluded `out` dir becomes visible.
    (tmp_path / "Main.java").write_text("public class Main {}\n", encoding="utf-8")
    out = tmp_path / "out"
    out.mkdir()
    (out / "Gen.java").write_text("public class Gen {}\n", encoding="utf-8")

    # By default the `out` directory is pruned, so only Main.java is discovered.
    default_status = _run_status(sidecar_jar, tmp_path)
    assert default_status["project_model"]["allJavaFiles"] == ["Main.java"], default_status["project_model"]["allJavaFiles"]

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default", ignored_patterns=[])
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    assert payload["project_model"]["allJavaFiles"] == ["Main.java", "out/Gen.java"], payload["project_model"]["allJavaFiles"]


def test_ignored_patterns_glob_prunes_target_tree(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 1 regression: a design-style glob `target/**` must prune everything under target/ over the project-relative
    # path. The list omits the bare `target` default name, so a pass proves the GLOB matched (not the legacy bare name).
    (tmp_path / "Keep.java").write_text("public class Keep {}\n", encoding="utf-8")
    (tmp_path / "target" / "gen").mkdir(parents=True)
    (tmp_path / "target" / "gen" / "Drop.java").write_text("public class Drop {}\n", encoding="utf-8")
    keep_dir = tmp_path / "tooling"
    keep_dir.mkdir()
    (keep_dir / "Helper.java").write_text("public class Helper {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default", ignored_patterns=["target/**"])
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    files = payload["project_model"]["allJavaFiles"]
    assert "target/gen/Drop.java" not in files, files
    assert "Keep.java" in files and "tooling/Helper.java" in files, files


def test_ignored_patterns_glob_prunes_build_tree(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 1 regression: the design example `build/**` must prune everything under build/.
    (tmp_path / "Keep.java").write_text("public class Keep {}\n", encoding="utf-8")
    (tmp_path / "build" / "classes").mkdir(parents=True)
    (tmp_path / "build" / "classes" / "Gen.java").write_text("public class Gen {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default", ignored_patterns=["build/**"])
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    assert payload["project_model"]["allJavaFiles"] == ["Keep.java"], payload["project_model"]["allJavaFiles"]


def test_ignored_patterns_nested_glob_prunes_only_matching_subtree(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 1 regression: a nested glob `a/**/skip/**` must match across intermediate directories yet leave a sibling
    # subtree untouched, proving `**` spans path separators while still being anchored to the named segments.
    (tmp_path / "Root.java").write_text("public class Root {}\n", encoding="utf-8")
    (tmp_path / "a" / "b" / "skip").mkdir(parents=True)
    (tmp_path / "a" / "b" / "skip" / "Dropped.java").write_text("public class Dropped {}\n", encoding="utf-8")
    (tmp_path / "a" / "b" / "keep").mkdir(parents=True)
    (tmp_path / "a" / "b" / "keep" / "Stay.java").write_text("public class Stay {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default", ignored_patterns=["a/**/skip/**"])
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    files = payload["project_model"]["allJavaFiles"]
    assert "a/b/skip/Dropped.java" not in files, files
    assert "a/b/keep/Stay.java" in files and "Root.java" in files, files


def test_sidecar_accepts_build_tool_model_design_key(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 2 regression: the sidecar protocol must honor the design key `buildToolModel` (not only `buildToolMode`).
    # A pom.xml would normally select the Maven discoverer; forcing `plain` via buildToolModel must override that.
    (tmp_path / "pom.xml").write_text("<project></project>", encoding="utf-8")
    (tmp_path / "Standalone.java").write_text("public class Standalone {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default", config={"buildToolModel": "plain"})
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    assert payload["ready"] is True, payload["errors"]
    assert payload["project_model"]["discoveryKind"] == "plain"


def test_sidecar_build_tool_model_and_mode_agree(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 2 regression: supplying both aliases with the SAME value is not a conflict; discovery proceeds normally.
    (tmp_path / "pom.xml").write_text("<project></project>", encoding="utf-8")
    (tmp_path / "Standalone.java").write_text("public class Standalone {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path), configuration="default", config={"buildToolModel": "plain", "buildToolMode": "plain"}
            )
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    assert payload["ready"] is True, payload["errors"]
    assert payload["project_model"]["discoveryKind"] == "plain"


def test_sidecar_rejects_conflicting_build_tool_mode_aliases(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 2 regression: conflicting alias values (buildToolMode != buildToolModel) must fail loudly as a project-model
    # error, never silently pick one spelling.
    (tmp_path / "App.java").write_text("public class App {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path), configuration="default", config={"buildToolMode": "maven", "buildToolModel": "gradle"}
            )
        )
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    assert payload["ready"] is False
    assert any("Conflicting build-tool mode" in error for error in payload["errors"]), payload["errors"]


def test_legacy_configuration_string_still_honored(sidecar_jar: Path, tmp_path: Path) -> None:
    # Backward compatibility: a request carrying only the legacy flat `configuration` JSON string (no structured config,
    # no top-level encoding) must behave exactly as before.
    (tmp_path / "App.java").write_text("public class App {}\n", encoding="utf-8")
    status = _run_status(sidecar_jar, tmp_path, configuration=json.dumps({"release": "17", "encoding": "US-ASCII"}))
    assert status["ready"] is True, status["errors"]
    source_set = status["project_model"]["sourceSets"][0]
    assert source_set["release"] == "17"
    assert source_set["encoding"] == "US-ASCII"


def _write_signature_app(project_root: Path) -> None:
    (project_root / "App.java").write_text(
        """public class App {
    String greet() {
        return helper("Bob");
    }

    String helper(String name) {
        return "hi " + name;
    }
}
""",
        encoding="utf-8",
    )


def test_sidecar_accepts_full_v2_sessions_config_block(sidecar_jar: Path, tmp_path: Path) -> None:
    """G006: a full v2.sessions config block (limits + require_revision_match_on_apply) is consumed by the sidecar's
    RefactorSessionManager.configure() and a session can still be created (config is accepted, not rejected)."""
    _write_signature_app(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={
                    "java_refactor": {
                        "v2": {
                            "sessions": {
                                "max_open_sessions": 4,
                                "session_ttl_minutes": 15,
                                "require_revision_match_on_apply": True,
                            }
                        }
                    }
                },
            )
        )
        created = client.create_session(
            "changeSignature",
            {"relativePath": "App.java", "line": 6, "column": 12, "newName": "format",
             "parameters": [{"name": "name", "type": "String"}]},
        )
    finally:
        client.shutdown()

    assert json.loads(status.to_json())["ready"] is True
    assert created["accepted"] is True


def test_sidecar_apply_refuses_mismatched_expected_project_revision(sidecar_jar: Path, tmp_path: Path) -> None:
    """G006: with require_revision_match_on_apply on (default), an apply that pins a wrong expectedProjectRevision is
    refused with project_revision_mismatch — proving the revision guard rather than silently applying."""
    _write_signature_app(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {"relativePath": "App.java", "line": 6, "column": 12, "newName": "format",
             "parameters": [{"name": "name", "type": "String"}]},
        )
        assert created["accepted"] is True
        session_id = created["session"]["sessionId"]
        result = client.apply_session(session_id, expected_project_revision="sha256:deadbeefmismatch")
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "project_revision_mismatch"


def test_sidecar_v2_disabled_flag_disables_every_v2_operation(sidecar_jar: Path, tmp_path: Path) -> None:
    """G006: java_refactor.v2.enabled:false makes every V2 session op return operation_disabled."""
    _write_signature_app(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"enabled": False}}},
            )
        )
        refused = client.create_session(
            "changeSignature",
            {"relativePath": "App.java", "line": 6, "column": 12, "newName": "format",
             "parameters": [{"name": "name", "type": "String"}]},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "operation_disabled"


def test_sidecar_per_operation_enabled_flag_disables_only_that_operation(sidecar_jar: Path, tmp_path: Path) -> None:
    """G006: extract_method.enabled:false disables only extractMethod; other V2 ops (e.g. changeSignature) remain
    enabled."""
    _write_signature_app(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"extract_method": {"enabled": False}}}},
            )
        )
        extract_refused = client.create_session(
            "extractMethod",
            {"relativePath": "App.java", "startLine": 3, "endLine": 3, "methodName": "extracted"},
        )
        signature_ok = client.create_session(
            "changeSignature",
            {"relativePath": "App.java", "line": 6, "column": 12, "newName": "format",
             "parameters": [{"name": "name", "type": "String"}]},
        )
    finally:
        client.shutdown()

    assert extract_refused["accepted"] is False
    assert extract_refused["refusal"]["code"] == "operation_disabled"
    # changeSignature is unaffected: it either succeeds or refuses for a non-config reason, never operation_disabled.
    if signature_ok["accepted"] is False:
        assert signature_ok["refusal"]["code"] != "operation_disabled"
