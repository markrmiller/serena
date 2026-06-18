import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.manager import JavaRefactorManager
from serena.java_refactor.models import JavaRefactorInitializeParams
from solidlsp.ls_config import Language
from test.serena._java_refactor_sidecar_helpers import (  # noqa: F401
    CROSS_SOURCE_SET_CONFIG,
    javac_supports_release_21,
    _build_processor_jar,
    _build_vendored_jar,
    _crafted_apply,
    _plain_project,
    _preview_op,
    _preview_rename,
    _preview_safe_delete,
    _utf16_offset,
    _write_cross_source_set_project,
    _write_demo_main,
    _write_divergent_gradle_project,
    _write_generated_root_project,
    _write_gradle_java_project,
    _write_source_level_divergent_project,
    _write_two_module_project,
    file_ops,
    maven_offline_config,
    maven_offline_repo,
    run_status,
    sidecar_jar,
    text_edits,
    write_maven_offline_project,
)


def test_sidecar_discovers_plain_project(sidecar_jar: Path, tmp_path: Path) -> None:
    java_file = tmp_path / "Hello.java"
    java_file.write_text('class Hello { String value() { return "ok"; } }\n', encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path)

    assert status["ready"] is True
    model = status["project_model"]
    assert model["discoveryKind"] == "plain"
    assert model["javaFileCount"] == 1
    assert model["sourceSets"][0]["javaFiles"] == ["Hello.java"]
    assert "-encoding" in model["sourceSets"][0]["javacOptions"]


def test_sidecar_status_emits_designed_top_level_contract(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: the real sidecar status must carry the designed compact readiness payload, not only the nested model.
    java_file = tmp_path / "Hello.java"
    java_file.write_text('class Hello { String value() { return "ok"; } }\n', encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path)

    assert status["status"] == "ready"
    assert status["buildTool"] == "plain"
    assert status["sourceSets"] == 1
    assert status["javaFiles"] == 1
    assert status["semanticErrors"] == 0
    assert isinstance(status["jdk"], str) and status["jdk"]
    assert isinstance(status["lastModelRefreshMs"], int) and status["lastModelRefreshMs"] >= 0
    assert "classpathEntries" in status


def test_sidecar_cache_invalidates_on_content_only_change(sidecar_jar: Path, tmp_path: Path) -> None:
    """A content-only edit (identical mtime and size) must invalidate the cached project model.

    The project model cache lives in the sidecar process, so both status calls reuse the SAME started client;
    restarting it would empty the cache and prove nothing.
    """
    java_file = tmp_path / "Hello.java"
    compiling = "class Hello { int value() { return 1234567; } }\n"
    failing = "class Hello { int value() { return ABCDEFG; } }\n"  # ABCDEFG is an undefined symbol
    assert len(compiling) == len(failing)  # isolate content-hash behavior: byte length is identical

    java_file.write_text(compiling, encoding="utf-8")
    original_stat = java_file.stat()

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        first = json.loads(client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default")).to_json())
        assert first["ready"] is True
        assert not first["errors"]

        # Overwrite with different content of the same byte length, then restore mtime so mtime AND size are unchanged.
        java_file.write_text(failing, encoding="utf-8")
        os.utime(java_file, (original_stat.st_atime, original_stat.st_mtime))
        restored_stat = java_file.stat()
        assert restored_stat.st_size == original_stat.st_size
        assert restored_stat.st_mtime == original_stat.st_mtime  # mtime genuinely restored

        # Reuse the same long-lived process so the in-sidecar cache is exercised.
        second = json.loads(client.status().to_json())
        assert second["ready"] is False
        assert second["errors"]
        assert "cannot find symbol" in "\n".join(second["errors"])
    finally:
        client.shutdown()


def test_sidecar_discovers_maven_release_and_invalidation(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    # Real Maven extraction: help:effective-pom resolves maven.compiler.release from the effective POM.
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>release-app</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=maven_offline_config)

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    assert model["discoveryKind"] == "maven"
    assert model["sourceSets"][0]["release"] == "17"
    assert "pom.xml" in model["invalidationFiles"]
    assert "--release" in model["sourceSets"][0]["javacOptions"]


def test_sidecar_discovers_gradle_multi_source_set(sidecar_jar: Path, tmp_path: Path) -> None:
    # Real Gradle extraction: the java plugin's main and test source sets are surfaced from the build model.
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "sample"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\njava { sourceCompatibility = JavaVersion.VERSION_17 }\n', encoding="utf-8"
    )
    main_src = tmp_path / "src/main/java/example"
    test_src = tmp_path / "src/test/java/example"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    (main_src / "Main.java").write_text("package example; public class Main {}\n", encoding="utf-8")
    (test_src / "MainTest.java").write_text("package example; public class MainTest {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"offline": True, "allowIncompleteAnalysis": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    assert model["discoveryKind"] == "gradle"
    assert [source_set["name"] for source_set in model["sourceSets"]] == ["main", "test"]
    assert "settings.gradle.kts" in model["invalidationFiles"]
    assert "build.gradle.kts" in model["invalidationFiles"]


def test_sidecar_rejects_main_referencing_test_source_set(sidecar_jar: Path, tmp_path: Path) -> None:
    # G002 directional source-set modeling: main must NOT see test. A main class that references a test-only type must
    # fail to compile because test's roots are never on main's -sourcepath. The previous all-roots-visible model
    # resolved this illegal reference and wrongly reported the project as ready.
    main_src, test_src = _write_gradle_java_project(tmp_path)
    (main_src / "Main.java").write_text("package example; public class Main { TestOnly ref; }\n", encoding="utf-8")
    (test_src / "TestOnly.java").write_text("package example; public class TestOnly {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"offline": True}))

    model = status["project_model"]
    assert status["ready"] is False, model
    combined_errors = "\n".join(status["errors"]) + "\n" + "\n".join(model["errors"])
    assert "cannot find symbol" in combined_errors, combined_errors


def test_sidecar_allows_test_referencing_main_source_set(sidecar_jar: Path, tmp_path: Path) -> None:
    # G002 direction sanity: test depends on main, so a test class resolves main symbols against source even though
    # main is not pre-compiled.
    main_src, test_src = _write_gradle_java_project(tmp_path)
    (main_src / "Main.java").write_text("package example; public class Main { public int value() { return 1; } }\n", encoding="utf-8")
    (test_src / "MainTest.java").write_text("package example; public class MainTest { int v = new Main().value(); }\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]


def test_sidecar_maven_separates_compile_and_test_classpaths(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    # G002: a test-scoped dependency must appear on the test source set's classpath but NOT on main's. The compile- and
    # test-scope build-classpath resolutions are kept distinct so main is never compiled against test-only libraries.
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>scoped-app</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties>"
        "<dependencies><dependency><groupId>demo</groupId><artifactId>vendor-lib</artifactId>"
        "<version>1.0</version><scope>test</scope></dependency></dependencies></project>",
        encoding="utf-8",
    )
    main_src = tmp_path / "src/main/java/example"
    test_src = tmp_path / "src/test/java/example"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    (main_src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")
    (test_src / "AppTest.java").write_text("package example; public class AppTest {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=maven_offline_config)

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    main_set = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    test_set = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "test")

    def classpath_value(options: list[str]) -> str:
        return options[options.index("-classpath") + 1] if "-classpath" in options else ""

    assert "vendor-lib-1.0.jar" not in classpath_value(main_set["javacOptions"]), main_set["javacOptions"]
    assert "vendor-lib-1.0.jar" in classpath_value(test_set["javacOptions"]), test_set["javacOptions"]


def test_sidecar_persistent_model_cache_reuse_and_invalidation_across_restart(sidecar_jar: Path, tmp_path: Path) -> None:
    # G006: the validated project model is persisted under Serena's project-data directory, so a restarted sidecar (each
    # run_status is a fresh process) reuses it instead of re-validating; a source edit invalidates the persisted entry.
    project = tmp_path / "proj"
    src = project / "src/main/java"
    src.mkdir(parents=True)
    app = src / "App.java"
    app.write_text("public class App { int v() { return 1; } }\n", encoding="utf-8")
    data_dir = tmp_path / "serena-data"

    first = run_status(sidecar_jar, project, project_data_dir=data_dir)
    assert first["ready"] is True, first["errors"]
    assert first["model_cache_source"] == "fresh", first
    cache_file = data_dir / "java-refactor" / "project-model.cache.json"
    assert cache_file.is_file(), "validated model should be persisted under the Serena project-data directory"

    # Restart: a new process has an empty in-process cache but finds the matching persisted entry.
    second = run_status(sidecar_jar, project, project_data_dir=data_dir)
    assert second["model_cache_source"] == "persistent", second

    # Editing a source file changes the content-sensitive key, invalidating the persisted entry.
    app.write_text("public class App { int v() { return 2; } }\n", encoding="utf-8")
    third = run_status(sidecar_jar, project, project_data_dir=data_dir)
    assert third["model_cache_source"] == "fresh", third

    # Without a project-data directory, persistence is disabled and every run re-validates.
    assert run_status(sidecar_jar, project)["model_cache_source"] == "fresh"


def test_sidecar_marks_annotation_processor_generated_output_non_editable(sidecar_jar: Path, tmp_path: Path) -> None:
    # G007: the compile task's annotation-processor generated-sources output directory is discovered as a generated root
    # even when it lives OUTSIDE a /generated/ path. Generated symbols resolve (so references compile), but an edit that
    # would modify a generated file is refused.
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "sample"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        "java { sourceCompatibility = JavaVersion.VERSION_17 }\n"
        'tasks.named<JavaCompile>("compileJava") { options.generatedSourceOutputDirectory.set(file("build/apout")) }\n',
        encoding="utf-8",
    )
    main_src = tmp_path / "src/main/java/example"
    main_src.mkdir(parents=True)
    # The generated output dir ("build/apout") deliberately does NOT contain "/generated/", so only the
    # generatedSourceOutputDirectory discovery (not the path heuristic) can mark it generated.
    gen = tmp_path / "build/apout/example/gen"
    gen.mkdir(parents=True)
    (gen / "Gen.java").write_text("package example.gen;\npublic class Gen {\n    public int value() { return 1; }\n}\n", encoding="utf-8")
    (main_src / "App.java").write_text(
        "package example; import example.gen.Gen; public class App { int x = new Gen().value(); }\n", encoding="utf-8"
    )

    config = json.dumps({"offline": True})
    status = run_status(sidecar_jar, tmp_path, configuration=config)

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    assert any(root.replace("\\", "/").endswith("build/apout") for root in model["generatedSourceRoots"]), model["generatedSourceRoots"]

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=config))
        # Renaming the generated method must be refused: its declaration span lies under a generated root.
        result = client.preview(
            "semanticRename", {"relativePath": "build/apout/example/gen/Gen.java", "line": 3, "column": 16, "newName": "amount"}
        )
    finally:
        client.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "non_editable_target", result


def test_sidecar_reports_module_info(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java"
    src.mkdir(parents=True)
    (src / "module-info.java").write_text("module demo { }\n", encoding="utf-8")
    (src / "demo").mkdir()
    (src / "demo" / "Main.java").write_text("package demo; public class Main {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path)

    model = status["project_model"]
    assert status["ready"] is True
    assert model["sourceSets"][0]["modular"] is True
    assert "src/main/java/module-info.java" in model["invalidationFiles"]


def test_sidecar_invalidation_includes_maven_wrapper_files(sidecar_jar: Path, tmp_path: Path) -> None:
    # G006: the plan lists mvnw/gradlew (and the .mvn/wrapper distribution pin) as project-model invalidation inputs; a
    # wrapper version bump can change the resolved build tool. The old list tracked gradlew but omitted the Maven wrapper.
    (tmp_path / "mvnw").write_text("#!/bin/sh\n", encoding="utf-8")
    (tmp_path / "mvnw.cmd").write_text("@echo off\n", encoding="utf-8")
    (tmp_path / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")
    (tmp_path / ".mvn/wrapper").mkdir(parents=True)
    (tmp_path / ".mvn/wrapper/maven-wrapper.properties").write_text(
        "distributionUrl=https://example/apache-maven-3.9.6-bin.zip\n", encoding="utf-8"
    )
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package demo; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path)

    model = status["project_model"]
    invalidation = {path.replace("\\", "/") for path in model["invalidationFiles"]}
    assert "mvnw" in invalidation, invalidation
    assert "mvnw.cmd" in invalidation, invalidation
    assert "gradlew" in invalidation, invalidation
    assert ".mvn/wrapper/maven-wrapper.properties" in invalidation, invalidation


def test_sidecar_modular_routes_classpath_dependencies_to_module_path(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003 regression: a modular project whose dependencies are carried on the classpath (Maven/explicit layout, where
    # modulePath is empty) must route them onto --module-path (as automatic modules), never lose them. A modular set
    # must not use -classpath (a named module cannot read the unnamed/classpath module).
    deps = tmp_path / "deps"
    deps.mkdir()
    src = tmp_path / "src/main/java"
    (src / "demo").mkdir(parents=True)
    (src / "module-info.java").write_text("module demo { }\n", encoding="utf-8")
    (src / "demo" / "App.java").write_text("package demo; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"classpath": [str(deps)]}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    options = model["sourceSets"][0]["javacOptions"]
    assert "--module-path" in options, options
    module_path_value = options[options.index("--module-path") + 1]
    assert str(deps) in module_path_value, module_path_value
    assert "-classpath" not in options, options


def test_sidecar_modular_requires_graph_is_validated(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: a module that uses a system module it does not `requires` must be REJECTED (real module-graph validation,
    # not mere module-info.java detection). Adding the requires makes it ready.
    src = tmp_path / "src/main/java"
    (src / "demo").mkdir(parents=True)
    (src / "demo" / "Use.java").write_text(
        "package demo; import java.sql.Connection; public class Use { Connection c; }\n", encoding="utf-8"
    )

    (src / "module-info.java").write_text("module demo { }\n", encoding="utf-8")
    missing = run_status(sidecar_jar, tmp_path)
    assert missing["ready"] is False
    assert "does not read it" in "\n".join(missing["errors"]), missing["errors"]

    (src / "module-info.java").write_text("module demo { requires java.sql; }\n", encoding="utf-8")
    present = run_status(sidecar_jar, tmp_path)
    assert present["ready"] is True, present["errors"]


def test_sidecar_modular_allows_reference_to_exported_package(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: a multi-module source set is disambiguated with one --module-source-path <module>=<root> per module, so
    # module modB may use a package modA exports.
    _write_two_module_project(tmp_path, "package app; import coreapi.Api; public class App { int x = Api.v(); }\n")

    status = run_status(sidecar_jar, tmp_path)

    assert status["ready"] is True, status["errors"]
    options = status["project_model"]["sourceSets"][0]["javacOptions"]
    assert options.count("--module-source-path") == 2, options
    assert "--add-modules" in options


def test_sidecar_modular_rejects_reference_to_non_exported_package(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: module modB references a package modA does NOT export; the module graph must reject it.
    _write_two_module_project(tmp_path, "package app; import internal.Secret; public class App { int x = Secret.s(); }\n")

    status = run_status(sidecar_jar, tmp_path)

    assert status["ready"] is False
    assert "does not export it" in "\n".join(status["errors"]), status["errors"]


def test_sidecar_fails_closed_on_incomplete_classpath(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Broken.java").write_text("import missing.Type; class Broken { Type value; }\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path)

    assert status["ready"] is False
    assert status["errors"]
    assert "package missing does not exist" in "\n".join(status["errors"])


def test_sidecar_warning_only_incomplete_when_configured(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Broken.java").write_text("import missing.Type; class Broken { Type value; }\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"allowIncompleteAnalysis": True}))

    assert status["ready"] is True
    model = status["project_model"]
    assert model["errors"] == []
    assert "package missing does not exist" in "\n".join(model["warnings"])


def test_sidecar_status_enforces_configured_max_files(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "A.java").write_text("class A {}\n", encoding="utf-8")
    (tmp_path / "B.java").write_text("class B {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"maxFiles": 1}))

    assert status["ready"] is False
    assert "max_files=1" in "\n".join(status["errors"])


def test_sidecar_explicit_source_roots_override_layout(sidecar_jar: Path, tmp_path: Path) -> None:
    # A non-conventional source root that auto-detection (src/main/java) would never find.
    src = tmp_path / "app/code/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "explicit", "sourceRoots": ["app/code"]}))

    assert status["ready"] is True
    model = status["project_model"]
    assert model["discoveryKind"] == "explicit"
    assert model["javaFileCount"] == 1
    assert model["sourceSets"][0]["javaFiles"] == ["app/code/example/App.java"]


def test_sidecar_classpath_resolves_external_dependency(sidecar_jar: Path, tmp_path: Path) -> None:
    # Compile a dependency into a classes directory the main sources reference only via classpath.
    dep_src = tmp_path / "dep_src/dep"
    dep_src.mkdir(parents=True)
    (dep_src / "Lib.java").write_text("package dep; public class Lib { public static int answer() { return 42; } }\n", encoding="utf-8")
    classes_dir = tmp_path / "deps/classes"
    classes_dir.mkdir(parents=True)
    subprocess.run(["javac", "-d", str(classes_dir), str(dep_src / "Lib.java")], check=True, capture_output=True, text=True)

    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; import dep.Lib; public class App { int v = Lib.answer(); }\n", encoding="utf-8")

    without_classpath = run_status(sidecar_jar, tmp_path)
    assert without_classpath["ready"] is False
    assert "package dep does not exist" in "\n".join(without_classpath["errors"])

    with_classpath = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"classpath": [str(classes_dir)]}))
    assert with_classpath["ready"] is True, with_classpath["errors"]
    javac_options = with_classpath["project_model"]["sourceSets"][0]["javacOptions"]
    assert "-classpath" in javac_options
    assert str(classes_dir) in javac_options[javac_options.index("-classpath") + 1]


def test_sidecar_json_config_preserves_multiple_classpath_entries_with_separator(sidecar_jar: Path, tmp_path: Path) -> None:
    # Two independent dependencies on two separate classpath entries. The second classes directory lives under a
    # parent whose name contains the OS path separator, which the old pathsep-joined flat protocol would have
    # truncated/merged (and on Windows ';' collides with the entry separator). JSON arrays must keep both intact.
    first_dep_src = tmp_path / "dep_a_src/depa"
    first_dep_src.mkdir(parents=True)
    (first_dep_src / "LibA.java").write_text(
        "package depa; public class LibA { public static int answer() { return 1; } }\n", encoding="utf-8"
    )
    first_classes = tmp_path / "deps_a/classes"
    first_classes.mkdir(parents=True)
    subprocess.run(["javac", "-d", str(first_classes), str(first_dep_src / "LibA.java")], check=True, capture_output=True, text=True)

    second_dep_src = tmp_path / "dep_b_src/depb"
    second_dep_src.mkdir(parents=True)
    (second_dep_src / "LibB.java").write_text(
        "package depb; public class LibB { public static int answer() { return 2; } }\n", encoding="utf-8"
    )
    second_classes = tmp_path / "deps_b/classes"
    second_classes.mkdir(parents=True)
    subprocess.run(["javac", "-d", str(second_classes), str(second_dep_src / "LibB.java")], check=True, capture_output=True, text=True)

    # A source root whose own directory name embeds the OS path separator. With the old pathsep-joined flat protocol
    # this entry would have been split/truncated (and on Windows ';' collides with the entry separator); JSON arrays
    # keep it intact as a single entry.
    first_src = tmp_path / f"roots{os.pathsep}one/example"
    first_src.mkdir(parents=True)
    (first_src / "AppA.java").write_text(
        "package example; import depa.LibA; public class AppA { int v = LibA.answer(); }\n", encoding="utf-8"
    )
    second_src = tmp_path / "roots_two/example"
    second_src.mkdir(parents=True)
    (second_src / "AppB.java").write_text(
        "package example; import depb.LibB; public class AppB { int v = LibB.answer(); }\n", encoding="utf-8"
    )

    status = run_status(
        sidecar_jar,
        tmp_path,
        configuration=json.dumps(
            {
                "buildToolMode": "explicit",
                "sourceRoots": [f"roots{os.pathsep}one", "roots_two"],
                "classpath": [str(first_classes), str(second_classes)],
            }
        ),
    )

    # Both source roots compiled, and both dependencies resolved => neither classpath entry was truncated/merged.
    assert status["ready"] is True, status["errors"]
    javac_options = status["project_model"]["sourceSets"][0]["javacOptions"]
    classpath_value = javac_options[javac_options.index("-classpath") + 1]
    assert str(first_classes) in classpath_value
    assert str(second_classes) in classpath_value
    assert status["project_model"]["javaFileCount"] == 2


def test_sidecar_build_tool_mode_forces_plain(sidecar_jar: Path, tmp_path: Path) -> None:
    # A pom.xml would normally select the maven discoverer; buildToolMode=plain must override that.
    (tmp_path / "pom.xml").write_text("<project></project>", encoding="utf-8")
    (tmp_path / "Standalone.java").write_text("public class Standalone {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "plain"}))

    assert status["ready"] is True
    assert status["project_model"]["discoveryKind"] == "plain"


def test_sidecar_explicit_version_and_encoding_reach_javac_options(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text("public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"release": "17", "encoding": "US-ASCII"}))

    assert status["ready"] is True
    source_set = status["project_model"]["sourceSets"][0]
    assert source_set["release"] == "17"
    assert source_set["encoding"] == "US-ASCII"
    options = source_set["javacOptions"]
    assert options[options.index("--release") + 1] == "17"
    assert options[options.index("-encoding") + 1] == "US-ASCII"


def test_sidecar_relative_classpath_resolves_against_project_root(sidecar_jar: Path, tmp_path: Path) -> None:
    dep_src = tmp_path / "dep_src/dep"
    dep_src.mkdir(parents=True)
    (dep_src / "Lib.java").write_text("package dep; public class Lib {}\n", encoding="utf-8")
    classes_dir = tmp_path / "deps/classes"
    classes_dir.mkdir(parents=True)
    subprocess.run(["javac", "-d", str(classes_dir), str(dep_src / "Lib.java")], check=True, capture_output=True, text=True)

    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; import dep.Lib; public class App { Lib lib; }\n", encoding="utf-8")

    # A project-root-relative classpath entry must resolve against the project root, not the sidecar CWD.
    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"classpath": [f"deps{os.sep}classes"]}))

    assert status["ready"] is True, status["errors"]


def test_sidecar_conventional_roots_include_src_test_java_and_src(sidecar_jar: Path, tmp_path: Path) -> None:
    # G005 gap 1: plain/conventional discovery must surface src/main/java, src/test/java, AND src as roots (those that
    # exist), not just src/main/java. All three layouts hold sources here; every file must be discovered.
    (tmp_path / "pom.xml").write_text("<project></project>", encoding="utf-8")  # forces plain via buildToolMode below
    main_src = tmp_path / "src/main/java/example"
    test_src = tmp_path / "src/test/java/example"
    bare_src = tmp_path / "src/legacy"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    bare_src.mkdir(parents=True)
    (main_src / "Main.java").write_text("package example; public class Main {}\n", encoding="utf-8")
    (test_src / "MainTest.java").write_text("package example; public class MainTest {}\n", encoding="utf-8")
    (bare_src / "Legacy.java").write_text("package legacy; public class Legacy {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "plain"}))

    assert status["ready"] is True, status["project_model"]["errors"]
    model = status["project_model"]
    assert model["discoveryKind"] == "plain"
    source_roots = model["sourceSets"][0]["sourceRoots"]
    assert "src/main/java" in source_roots
    assert "src/test/java" in source_roots
    assert "src" in source_roots
    # Every Java file across the three roots is discovered exactly once (overlapping src/* roots are de-duplicated).
    assert model["allJavaFiles"] == [
        "src/legacy/Legacy.java",
        "src/main/java/example/Main.java",
        "src/test/java/example/MainTest.java",
    ]
    assert model["javaFileCount"] == 3


def test_sidecar_plain_discovery_falls_back_to_root_when_no_conventional_roots(sidecar_jar: Path, tmp_path: Path) -> None:
    # G005 gap 1: when none of src/main/java, src/test/java, src exist, a flat single-file project (sources directly
    # under the root) must still resolve against the project root rather than yielding no sources.
    (tmp_path / "Flat.java").write_text("public class Flat {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path)

    assert status["ready"] is True, status["project_model"]["errors"]
    model = status["project_model"]
    assert model["discoveryKind"] == "plain"
    assert model["sourceSets"][0]["javaFiles"] == ["Flat.java"]


def test_sidecar_model_exposes_aggregate_shape(sidecar_jar: Path, tmp_path: Path) -> None:
    # G005 gap 3: the top-level model must expose aggregate allJavaFiles / classpath / modulePath / outputDirs (union of
    # all source sets). A compiled classpath dir is supplied so the aggregate classpath is non-empty and absolute.
    dep_src = tmp_path / "dep_src/dep"
    dep_src.mkdir(parents=True)
    (dep_src / "Lib.java").write_text("package dep; public class Lib {}\n", encoding="utf-8")
    classes_dir = tmp_path / "deps/classes"
    classes_dir.mkdir(parents=True)
    subprocess.run(["javac", "-d", str(classes_dir), str(dep_src / "Lib.java")], check=True, capture_output=True, text=True)

    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; import dep.Lib; public class App { Lib lib; }\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"classpath": [str(classes_dir)]}))

    assert status["ready"] is True, status["project_model"]["errors"]
    model = status["project_model"]
    # allJavaFiles is the de-duplicated, project-relative union of every source set's files.
    assert model["allJavaFiles"] == ["src/main/java/example/App.java"]
    # The aggregate classpath unions the source sets' classpaths and is reported as absolute paths.
    assert str(classes_dir) in model["classpath"]
    # Aggregate keys exist with the documented shape even when empty.
    assert model["modulePath"] == []
    assert isinstance(model["outputDirs"], list)
    assert isinstance(model["generatedSourceRoots"], list)


def test_sidecar_conventional_output_dirs_populated_when_built(sidecar_jar: Path, tmp_path: Path) -> None:
    # G005 gap 2: SourceSet.outputDirs must be populated from conventional discovery. A pre-existing target/classes
    # (the Maven layout's compiler output) is surfaced as an output dir; phantom unbuilt dirs are not reported.
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")
    output_dir = tmp_path / "target/classes"
    output_dir.mkdir(parents=True)

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "plain"}))

    assert status["ready"] is True, status["project_model"]["errors"]
    model = status["project_model"]
    assert "target/classes" in model["sourceSets"][0]["outputDirs"]
    # The aggregate outputDirs unions the source sets' output dirs.
    assert "target/classes" in model["outputDirs"]


def test_sidecar_gradle_extracts_module_path_for_modular_build(sidecar_jar: Path, tmp_path: Path) -> None:
    # G005 gap 4: the Gradle init script must emit a real modulePath (not hardcoded []) for a modular build. A
    # compiled module on the dependency configuration must appear on the modular source set's module path.
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "modular"\n', encoding="utf-8")
    # A pre-built dependency module placed on the compile classpath via a flat-dir-style files() dependency, so Gradle
    # resolves it offline with no network. The init script reuses the resolved compile classpath as the module path
    # for a modular source set.
    dep_classes = tmp_path / "libs/depmod"
    dep_pkg = tmp_path / "dep_src/depmod"
    dep_pkg.mkdir(parents=True)
    (tmp_path / "dep_src" / "module-info.java").write_text("module dep.mod { exports depmod; }\n", encoding="utf-8")
    (dep_pkg / "Dep.java").write_text("package depmod; public class Dep {}\n", encoding="utf-8")
    dep_jar = tmp_path / "libs" / "depmod.jar"
    (tmp_path / "libs").mkdir(parents=True, exist_ok=True)
    classes_out = tmp_path / "dep_out"
    classes_out.mkdir()
    subprocess.run(
        ["javac", "-d", str(classes_out), str(tmp_path / "dep_src" / "module-info.java"), str(dep_pkg / "Dep.java")],
        check=True,
        capture_output=True,
        text=True,
    )
    subprocess.run(["jar", "cf", str(dep_jar), "-C", str(classes_out), "."], check=True, capture_output=True, text=True)

    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        "java { sourceCompatibility = JavaVersion.VERSION_17 }\n"
        'dependencies { implementation(files("libs/depmod.jar")) }\n',
        encoding="utf-8",
    )
    main_src = tmp_path / "src/main/java"
    pkg = main_src / "app"
    pkg.mkdir(parents=True)
    (main_src / "module-info.java").write_text("module app { requires dep.mod; }\n", encoding="utf-8")
    (pkg / "App.java").write_text("package app; public class App { depmod.Dep d; }\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"offline": True, "allowIncompleteAnalysis": True}))

    model = status["project_model"]
    main_set = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    assert main_set["modular"] is True
    # The init script emitted a real module path (the resolved compile classpath) for the modular source set.
    assert any("depmod.jar" in entry for entry in main_set["modulePath"]), main_set["modulePath"]
    assert any("depmod.jar" in entry for entry in model["modulePath"]), model["modulePath"]


def test_sidecar_maven_extracts_annotation_processor_path(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    # G005 gap 5: the maven-compiler-plugin's declared <annotationProcessorPaths> must be resolved to real jar paths
    # (matched against the resolved compile classpath) and surfaced as the source set's annotationProcessorPath. The
    # vendored demo:vendor-lib jar (installed into the offline repo) stands in for a processor artifact.
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>ap-app</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties>"
        "<dependencies><dependency><groupId>demo</groupId><artifactId>vendor-lib</artifactId>"
        "<version>1.0</version></dependency></dependencies>"
        "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
        "<artifactId>maven-compiler-plugin</artifactId>"
        "<configuration><annotationProcessorPaths><path>"
        "<groupId>demo</groupId><artifactId>vendor-lib</artifactId><version>1.0</version>"
        "</path></annotationProcessorPaths></configuration></plugin></plugins></build></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=maven_offline_config)

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    assert model["discoveryKind"] == "maven"
    main_set = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    assert any("vendor-lib-1.0.jar" in entry for entry in main_set["annotationProcessorPath"]), main_set["annotationProcessorPath"]


def test_sidecar_preview_and_apply_return_structured_refusals(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Hello.java").write_text("class Hello {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        preview = client.preview("renameSymbol", {"file": "Hello.java"})
        apply = client.apply_refactor("renameSymbol", {"file": "Hello.java"})
    finally:
        client.shutdown()

    for result, mode in [(preview, "preview"), (apply, "apply")]:
        assert result["accepted"] is False
        assert result["applied"] is False
        assert result["mode"] == mode
        assert result["refusal"]["code"] == "unsupported_operation"
        assert result["diagnostics"] == []
        assert result["warnings"] == []
        assert result["stats"] == {"editCount": 0, "fileOperationCount": 0, "touchedFileCount": 0}
        assert text_edits(result["workspaceEdit"]) == []
        assert file_ops(result["workspaceEdit"]) == []


def test_sidecar_resolves_target_and_scans_overload_references(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """package demo;

class Main {
    void helper() {}
    void helper(int value) {}
    void run() {
        helper();
        helper(1);
        Runnable helper = () -> {};
        helper.run();
    }
}
"""
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.scan_references("src/main/java/demo/Main.java", 7, 9, "helper")
    finally:
        client.shutdown()

    assert result["accepted"] is True
    assert result["target"]["semanticKey"]["kind"] == "METHOD"
    assert result["target"]["semanticKey"]["name"] == "helper"
    assert result["target"]["semanticKey"]["signature"] == "()"
    references = [(reference["line"], reference["column"], reference["text"]) for reference in result["references"]]
    assert references == [(4, 10, "helper"), (7, 9, "helper")]


def test_sidecar_canonical_key_format_for_type_field_method_ctor(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """package com.acme;

class Foo {
    int count;
    Foo(String label) {}
    int bar(String name, int times) { return times; }
}
"""
    src = tmp_path / "src/main/java/com/acme"
    src.mkdir(parents=True)
    (src / "Foo.java").write_text(source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        # Type declaration name "Foo" on line 3.
        type_key = client.resolve_target("src/main/java/com/acme/Foo.java", 3, 7, "Foo")["target"]["semanticKey"]
        # Field "count" on line 4.
        field_key = client.resolve_target("src/main/java/com/acme/Foo.java", 4, 9, "count")["target"]["semanticKey"]
        # Constructor "Foo" on line 5.
        ctor_key = client.resolve_target("src/main/java/com/acme/Foo.java", 5, 5, "Foo")["target"]["semanticKey"]
        # Method "bar" on line 6.
        method_key = client.resolve_target("src/main/java/com/acme/Foo.java", 6, 9, "bar")["target"]["semanticKey"]
    finally:
        client.shutdown()

    assert type_key["canonical"] == "com.acme.Foo"
    assert field_key["canonical"] == "com.acme.Foo#count"
    assert ctor_key["canonical"] == "com.acme.Foo#<init>(java.lang.String)"
    assert method_key["canonical"] == "com.acme.Foo#bar(java.lang.String,int)"


def test_sidecar_canonical_key_disambiguates_locals_in_distinct_methods(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """package demo;

class Main {
    void first() {
        int x = 1;
    }
    void second() {
        int x = 2;
    }
}
"""
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        first_key = client.resolve_target("src/main/java/demo/Main.java", 5, 13, "x")["target"]["semanticKey"]
        second_key = client.resolve_target("src/main/java/demo/Main.java", 8, 13, "x")["target"]["semanticKey"]
    finally:
        client.shutdown()

    assert first_key["kind"] == "LOCAL_VARIABLE"
    assert second_key["kind"] == "LOCAL_VARIABLE"
    assert first_key["name"] == "x"
    assert second_key["name"] == "x"
    # Same simple name and type, but distinct declaration offsets keep the canonical keys distinct.
    assert first_key["canonical"] != second_key["canonical"]
    assert first_key["declOffset"] != second_key["declOffset"]
    assert first_key["declFile"].endswith("Main.java")
    assert first_key["canonical"].endswith("#x")


def test_sidecar_canonical_key_disambiguates_parameters_in_distinct_methods(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """package demo;

class Main {
    void first(int value) {}
    void second(int value) {}
}
"""
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        first_key = client.resolve_target("src/main/java/demo/Main.java", 4, 20, "value")["target"]["semanticKey"]
        second_key = client.resolve_target("src/main/java/demo/Main.java", 5, 21, "value")["target"]["semanticKey"]
    finally:
        client.shutdown()

    assert first_key["kind"] == "PARAMETER"
    assert second_key["kind"] == "PARAMETER"
    assert first_key["canonical"] != second_key["canonical"]
    # The enclosing executable's canonical key is the prefix.
    assert first_key["canonical"].startswith("demo.Main#first(int)@")
    assert second_key["canonical"].startswith("demo.Main#second(int)@")
    assert first_key["canonical"].endswith("#value")


def test_sidecar_direct_targeting_enforces_identifier_span(sidecar_jar: Path, tmp_path: Path) -> None:
    """Direct line/column targeting (no nameHint) must resolve the symbol under the cursor, never an enclosing
    declaration selected purely by AST containment. Requires span != null and startOffset <= offset < endOffset.
    """
    source = """package demo;

class Sample {
    int base = 10;
    int counter = base + 1;

    void run() {

        int value = compute();
    }

    int compute() {
        return 0;
    }
}
"""
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Sample.java").write_text(source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        path = "src/main/java/demo/Sample.java"

        # Method-body whitespace (blank line 8 inside run): no symbol -> target_not_found, never the enclosing method.
        method_body_ws = client.resolve_target(path, 8, 1)
        # Class-body whitespace (blank line 6 between members): -> target_not_found, never the enclosing class.
        class_body_ws = client.resolve_target(path, 6, 1)
        # Variable initializer, on '=' (col 17), not the variable name: -> target_not_found, never `counter` by containment.
        initializer_eq = client.resolve_target(path, 5, 17)
        # Initializer identifier `base` (col 19): resolves to the referenced field, never the `counter` declaration.
        initializer_ref = client.resolve_target(path, 5, 19)
        # Identifier end boundary: `counter` occupies cols 9-15; col 16 is one past the last char (exclusive end).
        end_boundary = client.resolve_target(path, 5, 16)
        # Identifier last char (col 15) and start (col 9) are inside the span -> resolve `counter`.
        last_char = client.resolve_target(path, 5, 15)
        first_char = client.resolve_target(path, 5, 9)
        # Leading indentation inside a method body (line 9, col 1) must not rename the enclosing method/class.
        leading_indent = client.resolve_target(path, 9, 1)
    finally:
        client.shutdown()

    assert method_body_ws["accepted"] is False
    assert method_body_ws["refusal"]["code"] == "target_not_found"
    assert class_body_ws["accepted"] is False
    assert class_body_ws["refusal"]["code"] == "target_not_found"
    assert initializer_eq["accepted"] is False
    assert initializer_eq["refusal"]["code"] == "target_not_found"

    assert initializer_ref["accepted"] is True
    assert initializer_ref["target"]["semanticKey"]["name"] == "base"

    assert end_boundary["accepted"] is False
    assert end_boundary["refusal"]["code"] == "target_not_found"

    assert last_char["accepted"] is True
    assert last_char["target"]["semanticKey"]["name"] == "counter"
    assert first_char["accepted"] is True
    assert first_char["target"]["semanticKey"]["name"] == "counter"

    assert leading_indent["accepted"] is False
    assert leading_indent["refusal"]["code"] == "target_not_found"


def test_sidecar_resolves_targets_outside_first_source_set(sidecar_jar: Path, tmp_path: Path) -> None:
    main_src = tmp_path / "src/main/java/demo"
    test_src = tmp_path / "src/test/java/demo"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    (main_src / "Main.java").write_text("package demo; class Main {}\n", encoding="utf-8")
    (test_src / "Helper.java").write_text(
        """package demo;
class Helper {
    int value() { return 1; }
    int call() { return value(); }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        # Explicit config (both source roots) keeps this operation test hermetic by bypassing build-tool extraction;
        # SemanticIndex unions all roots, so cross-source-set references still resolve.
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                configuration=json.dumps(
                    {"buildToolMode": "explicit", "sourceRoots": ["src/main/java", "src/test/java"], "allowIncompleteAnalysis": True}
                ),
            )
        )

        result = client.scan_references("src/test/java/demo/Helper.java", 3, 9)
    finally:
        client.shutdown()

    assert result["accepted"] is True
    assert result["stats"]["referenceCount"] == 2
    assert {ref["line"] for ref in result["references"]} == {3, 4}


def test_sidecar_scans_references_across_source_set_boundary(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_cross_source_set_project(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=CROSS_SOURCE_SET_CONFIG))

        # Target the main-source-set method declaration; its only call site lives in the test source set.
        result = client.scan_references("src/main/java/demo/Service.java", 3, 9, "value")
    finally:
        client.shutdown()

    assert result["accepted"] is True
    references = {(reference["relativePath"], reference["text"]) for reference in result["references"]}
    assert ("src/main/java/demo/Service.java", "value") in references
    assert ("src/test/java/demo/ServiceTest.java", "value") in references


def test_sidecar_resolves_static_import_reference_span(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Util.java").write_text("package demo; public class Util { public static void ping() {} }\n", encoding="utf-8")
    (src / "Use.java").write_text("package demo;\nimport static demo.Util.ping;\nclass Use { void run() { ping(); } }\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.scan_references("src/main/java/demo/Util.java", 1, 61, "ping")
    finally:
        client.shutdown()

    assert result["accepted"] is True
    references = {(reference["relativePath"], reference["text"]) for reference in result["references"]}
    assert ("src/main/java/demo/Util.java", "ping") in references
    assert ("src/main/java/demo/Use.java", "ping") in references


def test_java_manager_refuses_pre_commit_when_staged_edit_does_not_compile(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    source = _write_demo_main(tmp_path)
    original = source.read_text(encoding="utf-8")

    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        client = manager._get_or_start_client(refresh=False)
        # The staged content is not valid Java, so the in-memory pre-commit javac must reject it before any disk write.
        client.apply_refactor = _crafted_apply(client, "package demo;\nclass Main { this is not java }\n", source)  # type: ignore[method-assign]
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is False
    assert result["applied"] is False
    assert result["refusal"]["code"] == "pre_apply_validation_failed"
    assert result["preValidation"]["ready"] is False
    assert result["preValidation"]["errors"]
    # Nothing was written: the file is byte-identical to its pre-apply state.
    assert source.read_text(encoding="utf-8") == original


def test_java_manager_commits_valid_apply_to_disk_after_pre_commit_validation(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    source = _write_demo_main(tmp_path)

    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        client = manager._get_or_start_client(refresh=False)
        # The crafted edit must be a REAL rename (the identifier span rewritten to exactly the new name): the rename
        # old-key verification refuses any apply whose baseline reference sites are not exactly rewritten, so a
        # whole-file replacement can no longer stand in for a valid rename apply.
        original = source.read_text(encoding="utf-8")
        from serena.java_refactor.workspace_edit import sha256_bytes

        start = original.index("value")
        workspace_edit = {
            "changes": [
                {
                    "path": "src/main/java/demo/Main.java",
                    "oldSha256": sha256_bytes(source.read_bytes()),
                    "edits": [{"startOffset": start, "endOffset": start + len("value"), "newText": "renamed", "kind": "DECLARATION"}],
                }
            ],
            "fileOperations": [],
            "warnings": [],
            "preconditions": [],
            "stats": {"editCount": 1, "fileOperationCount": 0},
        }
        client.apply_refactor = lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit}  # type: ignore[method-assign]
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True, result
    assert result["applied"] is True
    assert source.read_text(encoding="utf-8") == "package demo;\nclass Main {\n    int renamed = 1;\n}\n"


def test_java_manager_apply_validation_gate_not_disabled_by_config(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # validate_before_apply=False is a REAL knob with a bounded effect: it skips ONLY the staged pre-commit javac
    # validation (so the broken edit commits), but the NON-BYPASSABLE post-apply validation still detects that the
    # project no longer compiles and rolls the commit back. Safety is preserved end-to-end: the file ends up unchanged.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    source = _write_demo_main(tmp_path)
    original = source.read_text(encoding="utf-8")
    broken = "package demo;\nclass Main { this is not java }\n"

    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, validate_before_apply=False),
    )
    validate_edit_calls = []
    try:
        client = manager._get_or_start_client(refresh=False)
        original_validate_edit = client.validate_edit
        client.validate_edit = lambda overlay: validate_edit_calls.append(overlay) or original_validate_edit(overlay)  # type: ignore[method-assign]
        client.apply_refactor = _crafted_apply(client, broken, source)  # type: ignore[method-assign]
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=True)
    finally:
        manager.shutdown()

    # The pre-commit staged validation was skipped (the knob's observable effect)...
    assert not validate_edit_calls, "validate_before_apply=False must skip the staged pre-commit javac validation"
    # ...but post-apply validation rolled the broken commit back, so the workspace is unchanged.
    assert result["accepted"] is False, result
    assert result["applied"] is False, result
    assert result["rolledBack"] is True, result
    assert result["refusal"]["code"] == "post_validation_failed", result
    assert source.read_text(encoding="utf-8") == original


def test_java_manager_apply_validate_false_still_runs_pre_commit_gate(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # G001: a per-call validate=False likewise cannot disable the apply gate. apply=True, validate=False with a
    # non-compiling staged edit is refused with nothing written.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    source = _write_demo_main(tmp_path)
    original = source.read_text(encoding="utf-8")
    broken = "package demo;\nclass Main { this is not java }\n"

    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        client = manager._get_or_start_client(refresh=False)
        client.apply_refactor = _crafted_apply(client, broken, source)  # type: ignore[method-assign]
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=True, validate=False)
    finally:
        manager.shutdown()

    assert result["accepted"] is False, result
    assert result["applied"] is False, result
    assert result["refusal"]["code"] == "pre_apply_validation_failed", result
    assert source.read_text(encoding="utf-8") == original


def test_java_manager_apply_validate_false_still_rolls_back_residual(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # G001: post-apply verification + rollback also run under validate=False. An incomplete rename that leaves a stale
    # reference (which still COMPILES, defeating javac post-validation) is caught by old-key residual verification and
    # rolled back, even though the caller passed validate=False.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Base.java").write_text("package demo;\nclass Base { int data; }\n", encoding="utf-8")
    sub = src / "Sub.java"
    sub_source = "package demo;\nclass Sub extends Base {\n    int data;\n    int use() { return data; }\n}\n"
    sub.write_text(sub_source, encoding="utf-8")

    original_apply = JavaRefactorClient.apply_refactor

    def dropping_apply(self: JavaRefactorClient, operation: str, params: dict | None = None) -> dict:
        result = original_apply(self, operation, params)
        if operation == "semanticRename" and result.get("accepted"):
            edits = text_edits(result["workspaceEdit"])
            if len(edits) >= 2:
                drop = max(edits, key=lambda edit: edit["startOffset"])
                for change in result["workspaceEdit"].get("changes", []):
                    if change.get("path") == drop["relativePath"]:
                        change["edits"] = [
                            e
                            for e in change["edits"]
                            if not (e["startOffset"] == drop["startOffset"] and e["endOffset"] == drop["endOffset"])
                        ]
        return result

    monkeypatch.setattr(JavaRefactorClient, "apply_refactor", dropping_apply)
    column = "    int data;".index("data") + 1
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.semantic_rename("src/main/java/demo/Sub.java", 3, column, "renamed", apply=True, validate=False)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "rename_old_key_residual", result
    assert result.get("rolledBack") is True, result
    assert sub.read_text(encoding="utf-8") == sub_source


def test_java_status_surfaces_full_project_model_contract(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: java_refactor_status must surface every designed project-level field, even though the internal Java record
    # stores compiler settings per source set. Derivability is not sufficient — the V1 contract keys must be present and
    # carry the derived values.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package demo; public class App {}\n", encoding="utf-8")
    config = json.dumps(
        {
            "buildToolMode": "explicit",
            "sourceRoots": ["src/main/java"],
            "release": "17",
            "encoding": "UTF-8",
            "allowIncompleteAnalysis": True,
        }
    )
    status = run_status(sidecar_jar, tmp_path, configuration=config)
    model = status["project_model"]

    contract = [
        "allJavaFiles",
        "classpath",
        "modulePath",
        "generatedSourceRoots",
        "release",
        "source",
        "target",
        "encoding",
        "modular",
        "javacOptions",
        "invalidationFiles",
    ]
    for field in contract:
        assert field in model, f"project model missing designed contract field {field!r}: {sorted(model)}"
    for list_field in ["allJavaFiles", "classpath", "modulePath", "generatedSourceRoots", "javacOptions", "invalidationFiles"]:
        assert isinstance(model[list_field], list), (list_field, model[list_field])
    assert isinstance(model["modular"], bool), model["modular"]
    # The explicit-config compiler settings are surfaced at the project level (derived from per-source-set storage).
    assert model["release"] == "17", model
    assert model["encoding"] == "UTF-8", model
    assert "--release" in model["javacOptions"] and "17" in model["javacOptions"], model["javacOptions"]
    # allJavaFiles is the de-duplicated union of every source set's Java files.
    assert any(path.endswith("App.java") for path in model["allJavaFiles"]), model["allJavaFiles"]


def test_sidecar_json_output_escapes_tab_in_replacement(sidecar_jar: Path, tmp_path: Path) -> None:
    # The inline-local initializer contains a literal TAB between '+' and 'b'. The emitted replacement therefore
    # carries a control character that must be JSON-escaped; if JsonUtil under-escaped it, json.loads (inside
    # client.preview) would raise on the malformed protocol line.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        "package demo;\nclass Main {\n    int run(int a, int b) {\n        int x = a +\tb;\n        return x * 2;\n    }\n}\n",
        encoding="utf-8",
    )
    assert "\t" in (src / "Main.java").read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": 13})
    finally:
        client.shutdown()

    assert result["accepted"] is True
    replacements = {edit["replacement"] for edit in text_edits(result["workspaceEdit"]) if edit["replacement"]}
    # The tab survives the round-trip intact inside the parenthesized initializer.
    assert any("\t" in replacement for replacement in replacements), replacements


def test_sidecar_model_cache_invalidates_on_source_change(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "Hello.java"
    source.write_text("class Hello {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        first = client.status(refresh=True)
        # Introduce a compile error; the cached model must be invalidated by the changed file state.
        source.write_text("class Hello { Missing field; }\n", encoding="utf-8")
        second = client.status(refresh=True)
    finally:
        client.shutdown()

    assert first.ready is True
    assert second.ready is False


# ---------------------------------------------------------------------------------------------------------------------
# Real build-model extraction tests (Gradle init script / Maven stock goals). All run strictly offline: Gradle uses
# vendored jars via files(...) with --offline; Maven uses a warmed file-based .m2-repo with -o. Any accidental network
# access fails loudly because extraction is forced offline.
# ---------------------------------------------------------------------------------------------------------------------


def test_sidecar_extracts_gradle_classpath_offline(sidecar_jar: Path, tmp_path: Path) -> None:
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    # A vendored jar referenced only via files(...) — no mavenCentral, resolved offline by compileClasspath.
    vendor_jar = _build_vendored_jar(tmp_path, "vendor", "VendorLib", "public static int answer() { return 42; }")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "cp-sample"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        f'plugins {{ id("java") }}\ndependencies {{ implementation(files("{vendor_jar.as_posix()}")) }}\n',
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text(
        "package example; import vendor.VendorLib; public class App { int v = VendorLib.answer(); }\n", encoding="utf-8"
    )

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    main = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    assert any(str(vendor_jar) in entry for entry in main["classpath"]), main["classpath"]


def test_sidecar_annotation_processing_none_disables_processing(sidecar_jar: Path, tmp_path: Path) -> None:
    _plain_project(tmp_path)

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"annotationProcessing": "none"}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    options = next(ss["javacOptions"] for ss in model["sourceSets"] if ss["name"] == "main")
    assert "-proc:none" in options, options


def test_sidecar_annotation_processing_classpath_uses_default_discovery(sidecar_jar: Path, tmp_path: Path) -> None:
    _plain_project(tmp_path)

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"annotationProcessing": "classpath"}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    options = next(ss["javacOptions"] for ss in model["sourceSets"] if ss["name"] == "main")
    assert "-proc:none" not in options, options
    assert "-processorpath" not in options, options


def test_sidecar_annotation_processing_project_without_processors_disables(sidecar_jar: Path, tmp_path: Path) -> None:
    _plain_project(tmp_path)

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"annotationProcessing": "project"}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    options = next(ss["javacOptions"] for ss in model["sourceSets"] if ss["name"] == "main")
    # A project that declares no annotation processors means "no processors" -> -proc:none.
    assert "-proc:none" in options, options


def test_sidecar_annotation_processing_project_with_processor_uses_processorpath(sidecar_jar: Path, tmp_path: Path) -> None:
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    proc_jar = _build_processor_jar(tmp_path)
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "ap-sample"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        f'plugins {{ id("java") }}\ndependencies {{ annotationProcessor(files("{proc_jar.as_posix()}")) }}\n',
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(
        sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True, "annotationProcessing": "project"})
    )

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    options = next(ss["javacOptions"] for ss in model["sourceSets"] if ss["name"] == "main")
    assert "-processorpath" in options, options
    processor_path = options[options.index("-processorpath") + 1]
    assert "proc.jar" in processor_path, processor_path
    assert "-proc:none" not in options, options


def test_sidecar_extracts_custom_source_set(sidecar_jar: Path, tmp_path: Path) -> None:
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "css-sample"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text('plugins { id("java") }\nsourceSets { create("integrationTest") }\n', encoding="utf-8")
    main_src = tmp_path / "src/main/java/example"
    it_src = tmp_path / "src/integrationTest/java/example"
    main_src.mkdir(parents=True)
    it_src.mkdir(parents=True)
    (main_src / "Main.java").write_text("package example; public class Main {}\n", encoding="utf-8")
    (it_src / "IntegrationCheck.java").write_text("package example; public class IntegrationCheck {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    names = {source_set["name"] for source_set in model["sourceSets"]}
    assert "main" in names
    assert "integrationTest" in names


def test_sidecar_extracts_generated_roots(sidecar_jar: Path, tmp_path: Path) -> None:
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    # A pre-created generated source dir under build/generated/... wired into the main source set surfaces as a
    # generated root without running any annotation processor offline.
    generated = tmp_path / "build/generated/sources/custom/java/main/example"
    generated.mkdir(parents=True)
    (generated / "Generated.java").write_text("package example; public class Generated {}\n", encoding="utf-8")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "gen-sample"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\nsourceSets { named("main") { java { srcDir("build/generated/sources/custom/java/main") } } }\n',
        encoding="utf-8",
    )
    main_src = tmp_path / "src/main/java/example"
    main_src.mkdir(parents=True)
    (main_src / "Main.java").write_text("package example; public class Main {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    main = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    assert any("generated" in root for root in main["generatedRoots"]), main["generatedRoots"]
    # A generated root must appear exactly once in sourceRoots; it must not be double-listed by being both a plain
    # srcDir and a generated root (which would make javac see the same root twice).
    generated_roots = [root for root in main["sourceRoots"] if "generated" in root]
    assert len(generated_roots) == len(set(generated_roots)), main["sourceRoots"]
    for generated_root in main["generatedRoots"]:
        assert main["sourceRoots"].count(generated_root) == 1, main["sourceRoots"]


def test_sidecar_zero_source_set_extraction_flags_conventional_fallback(sidecar_jar: Path, tmp_path: Path) -> None:
    """H3/G005: extraction succeeds but yields ZERO source sets. By default this now FAILS CLOSED (see
    test_java_refactor_sidecar_build_model.py); when ``allow_conventional_fallback`` is set, conventional discovery is
    used and the resulting classpath-less model must be flagged as a conventional fallback so the degraded-model apply
    gate engages.

    The Gradle `java` plugin's main source set is repointed to an empty `customsrc` directory, so the build model
    resolves with no Java files. The actual sources live under the conventional `src/main/java`, which only the
    conventional-discovery fallback finds.
    """
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "zero-ss"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\nsourceSets { named("main") { java { setSrcDirs(listOf("customsrc")) } } }\n',
        encoding="utf-8",
    )
    (tmp_path / "customsrc").mkdir()
    conventional_src = tmp_path / "src/main/java/example"
    conventional_src.mkdir(parents=True)
    (conventional_src / "Main.java").write_text("package example; public class Main {}\n", encoding="utf-8")

    status = run_status(
        sidecar_jar,
        tmp_path,
        configuration=json.dumps({"buildToolMode": "gradle", "offline": True, "allowConventionalFallback": True}),
    )

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    assert model["conventionalFallbackUsed"] is True, model


def test_sidecar_extracts_maven_classpath_offline(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>cp-app</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties>"
        "<dependencies><dependency><groupId>demo</groupId><artifactId>vendor-lib</artifactId>"
        "<version>1.0</version></dependency></dependencies></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text(
        "package example; import vendor.VendorLib; public class App { int v = VendorLib.answer(); }\n", encoding="utf-8"
    )

    status = run_status(sidecar_jar, tmp_path, configuration=maven_offline_config)

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    main = next(source_set for source_set in model["sourceSets"] if source_set["name"].endswith("main"))
    assert any("vendor-lib" in entry for entry in main["classpath"]), main["classpath"]


def test_sidecar_maven_user_settings_reaches_invocation(sidecar_jar: Path, tmp_path: Path) -> None:
    """A mavenUserSettings path supplied by use_jdtls_settings is passed to Maven as ``-s <settings>``: pointing it at a
    non-existent settings file makes Maven fail fast with a message naming that file, which surfaces as a model error.
    This proves the flag is actually applied to the extraction invocation (the failure is independent of the network).
    """
    if shutil.which("mvn") is None:
        pytest.skip("mvn is required for Maven build-model extraction tests")
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>settings-app</artifactId><version>1</version></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")
    missing_settings = tmp_path / "no-such-settings.xml"

    configuration = json.dumps({"buildToolMode": "maven", "mavenUserSettings": str(missing_settings)})
    status = run_status(sidecar_jar, tmp_path, configuration=configuration)

    assert status["ready"] is False
    errors = "\n".join(status["project_model"]["errors"])
    assert "settings file" in errors and "no-such-settings.xml" in errors, errors


def test_sidecar_gradle_java_home_reaches_invocation(sidecar_jar: Path, tmp_path: Path) -> None:
    """A gradleJavaHome path supplied by use_jdtls_settings is passed to Gradle as ``-Dorg.gradle.java.home``: pointing
    it at a non-existent JDK makes Gradle fail fast with a message naming that path, surfaced as a model error. This
    proves the flag is actually applied to the Gradle extraction invocation.
    """
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    (tmp_path / "settings.gradle").write_text('rootProject.name = "jh"\n', encoding="utf-8")
    (tmp_path / "build.gradle").write_text("plugins { id 'java' }\n", encoding="utf-8")
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")
    missing_jdk = tmp_path / "no-such-jdk"

    configuration = json.dumps({"buildToolMode": "gradle", "offline": True, "gradleJavaHome": str(missing_jdk)})
    status = run_status(sidecar_jar, tmp_path, configuration=configuration)

    assert status["ready"] is False
    errors = "\n".join(status["project_model"]["errors"])
    assert "org.gradle.java.home" in errors and "no-such-jdk" in errors, errors


def test_sidecar_extracts_multi_module(sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path) -> None:
    # Reuse the offline reactor fixture: copy it into tmp so the .mvn/maven.config pin does not mutate the repo fixture.
    fixture = Path("test/resources/repos/java_refactor/multi-module-maven")
    shutil.copytree(fixture, tmp_path / "reactor")
    project_root = tmp_path / "reactor"
    write_maven_offline_project(project_root, maven_offline_repo)

    status = run_status(sidecar_jar, project_root, configuration=maven_offline_config)

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    assert model["discoveryKind"] == "maven"
    # The reactor's only module with Java sources is `a`; its main source set is extracted with its own classpath.
    assert any(source_set["javaFiles"] for source_set in model["sourceSets"])
    assert any("demo/A.java" in "".join(source_set["javaFiles"]) for source_set in model["sourceSets"])


def test_sidecar_maven_extraction_does_not_mutate_project_tree(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    # G004: build-model discovery must never write inside the project tree (Phase 1 acceptance). The classpath files
    # dependency:build-classpath produces are written to an out-of-tree temp directory, so the project tree snapshot is
    # byte-for-byte identical before and after extraction.
    fixture = Path("test/resources/repos/java_refactor/multi-module-maven")
    shutil.copytree(fixture, tmp_path / "reactor")
    project_root = tmp_path / "reactor"
    write_maven_offline_project(project_root, maven_offline_repo)

    def snapshot() -> dict[str, int]:
        return {str(path.relative_to(project_root)): path.stat().st_size for path in sorted(project_root.rglob("*")) if path.is_file()}

    before = snapshot()
    status = run_status(sidecar_jar, project_root, configuration=maven_offline_config)
    after = snapshot()

    assert status["ready"] is True, status["project_model"]["errors"]
    new_files = sorted(set(after) - set(before))
    assert not new_files, f"extraction wrote files into the project tree: {new_files}"
    assert before == after, "extraction modified existing files in the project tree"
    # Specifically, none of the legacy in-tree classpath artifacts may appear anywhere under the project.
    assert not list(project_root.rglob("serena-deps-*.txt"))
    assert not list(project_root.rglob("cp-*.txt"))


def test_sidecar_maven_extracts_build_helper_and_declared_generated_roots(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    # G005: Maven extraction must account for additional source roots configured via build-helper-maven-plugin and for
    # generated roots that are declared by convention but not yet present on disk, not just the primary src directories.
    (tmp_path / ".mvn").mkdir()
    (tmp_path / ".mvn/maven.config").write_text(f"-Dmaven.repo.local={maven_offline_repo}\n", encoding="utf-8")
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>bh</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties>"
        "<build><plugins><plugin>"
        "<groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.4.0</version>"
        "<executions>"
        "<execution><id>add-src</id><goals><goal>add-source</goal></goals>"
        "<configuration><sources><source>src/extra/java</source></sources></configuration></execution>"
        "<execution><id>add-test</id><goals><goal>add-test-source</goal></goals>"
        "<configuration><sources><source>src/extra-test/java</source></sources></configuration></execution>"
        "</executions></plugin></plugins></build></project>",
        encoding="utf-8",
    )
    (tmp_path / "src/main/java/demo").mkdir(parents=True)
    (tmp_path / "src/main/java/demo/App.java").write_text("package demo; public class App {}\n", encoding="utf-8")
    (tmp_path / "src/extra/java/demo").mkdir(parents=True)
    (tmp_path / "src/extra/java/demo/Extra.java").write_text("package demo; public class Extra {}\n", encoding="utf-8")
    (tmp_path / "src/test/java/demo").mkdir(parents=True)
    (tmp_path / "src/test/java/demo/AppTest.java").write_text("package demo; class AppTest {}\n", encoding="utf-8")
    (tmp_path / "src/extra-test/java/demo").mkdir(parents=True)
    (tmp_path / "src/extra-test/java/demo/ExtraTest.java").write_text("package demo; class ExtraTest {}\n", encoding="utf-8")
    # A populated conventional annotation-processor output directory must be surfaced as a generated root.
    (tmp_path / "target/generated-sources/annotations/demo").mkdir(parents=True)
    (tmp_path / "target/generated-sources/annotations/demo/Gen.java").write_text("package demo; public class Gen {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=maven_offline_config)

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    by_name = {source_set["name"]: source_set for source_set in model["sourceSets"]}

    main_roots = [root.replace("\\", "/") for root in by_name["main"]["sourceRoots"]]
    assert any(root.endswith("src/main/java") for root in main_roots), main_roots
    # build-helper add-source root is picked up (the old extractor read only sourceDirectory and missed this).
    assert any(root.endswith("src/extra/java") for root in main_roots), main_roots

    test_roots = [root.replace("\\", "/") for root in by_name["test"]["sourceRoots"]]
    assert any(root.endswith("src/test/java") for root in test_roots), test_roots
    assert any(root.endswith("src/extra-test/java") for root in test_roots), test_roots

    # The build-helper-added file is indexed as part of the main source set.
    assert any("demo/Extra.java" in file for file in by_name["main"]["javaFiles"]), by_name["main"]["javaFiles"]

    # The conventional annotation-processor generated output is discovered as a generated root.
    main_generated = [root.replace("\\", "/") for root in by_name["main"]["generatedRoots"]]
    assert any(root.endswith("target/generated-sources/annotations") for root in main_generated), main_generated


def test_sidecar_fails_closed_when_build_tool_unavailable(sidecar_jar: Path, tmp_path: Path) -> None:
    # An offline Maven build against an empty repo cannot resolve the extraction plugins, so extraction fails. With the
    # default fail-closed policy this surfaces as a project-model error and ready:false.
    empty_repo = tmp_path / "empty-m2"
    empty_repo.mkdir()
    (tmp_path / ".mvn").mkdir()
    (tmp_path / ".mvn/maven.config").write_text(f"-Dmaven.repo.local={empty_repo}\n", encoding="utf-8")
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>broken</artifactId><version>1</version></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "maven", "offline": True}))

    assert status["ready"] is False
    assert any("extraction failed" in error for error in status["errors"]), status["errors"]


def test_sidecar_conventional_fallback_when_opted_in(sidecar_jar: Path, tmp_path: Path) -> None:
    # Same sabotaged offline Maven build, but allow_conventional_fallback degrades to conventional discovery with a
    # warning instead of failing closed.
    empty_repo = tmp_path / "empty-m2"
    empty_repo.mkdir()
    (tmp_path / ".mvn").mkdir()
    (tmp_path / ".mvn/maven.config").write_text(f"-Dmaven.repo.local={empty_repo}\n", encoding="utf-8")
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>fallback</artifactId><version>1</version></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(
        sidecar_jar,
        tmp_path,
        configuration=json.dumps({"buildToolMode": "maven", "offline": True, "allowConventionalFallback": True}),
    )

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    assert model["discoveryKind"] == "maven"
    assert model["javaFileCount"] == 1
    assert any("conventional" in warning for warning in model["warnings"]), model["warnings"]


def test_sidecar_preview_warns_on_incomplete_classpath(sidecar_jar: Path, tmp_path: Path) -> None:
    # G012: a preview produced against a degraded (classpath-less conventional-fallback) model must carry an explicit
    # incomplete-analysis warning so the caller knows references resolvable only via the classpath may be missed.
    empty_repo = tmp_path / "empty-m2"
    empty_repo.mkdir()
    (tmp_path / ".mvn").mkdir()
    (tmp_path / ".mvn/maven.config").write_text(f"-Dmaven.repo.local={empty_repo}\n", encoding="utf-8")
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>fallback</artifactId><version>1</version></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    app = "package example; public class App { void run() { int value = 1; System.out.println(value); } }"
    (src / "App.java").write_text(app + "\n", encoding="utf-8")
    configuration = json.dumps({"buildToolMode": "maven", "offline": True, "allowConventionalFallback": True})
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=configuration))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/example/App.java", "line": 1, "column": app.index("value = 1") + 1, "newName": "amount"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    warnings = result["workspaceEdit"]["warnings"]
    assert any("incomplete analysis" in warning.lower() for warning in warnings), warnings


def test_sidecar_divergent_classpath_genuine_test_error_is_reported(sidecar_jar: Path, tmp_path: Path) -> None:
    """A genuine compile error confined to the test source set is still surfaced as an error (no false-accept): the
    cross-set -sourcepath/-implicit:none does not mask real test-set diagnostics.
    """
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    _write_divergent_gradle_project(tmp_path)
    (tmp_path / "src/test/java/demo/ServiceTest.java").write_text(
        "package demo;\nclass ServiceTest {\n    int run() { return new Service().value() + nope(); }\n}\n",
        encoding="utf-8",
    )

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    assert status["ready"] is False
    errors = "\n".join(status["project_model"]["errors"])
    assert "cannot find symbol" in errors and "nope()" in errors, errors


def test_sidecar_source_level_divergence_validates_each_set_with_own_release(sidecar_jar: Path, tmp_path: Path) -> None:
    """A 17-only text block in the test source set (release 17) validates cleanly even though main is release 11 and is
    placed on the test set's -sourcepath: the cross-set source is loaded with -implicit:none, not re-validated at 17.
    """
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    _write_source_level_divergent_project(tmp_path, main_uses_textblock=False)

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    assert status["ready"] is True, status["project_model"]["errors"]


def test_sidecar_source_level_divergence_reports_main_under_its_own_release(sidecar_jar: Path, tmp_path: Path) -> None:
    """The same text block placed in main (release 11) IS rejected, and the diagnostic is attributed to main's own pass
    (no double-reporting, no wrong-options attribution from main appearing on the test set's -sourcepath).
    """
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    _write_source_level_divergent_project(tmp_path, main_uses_textblock=True)

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    assert status["ready"] is False
    errors = status["project_model"]["errors"]
    joined = "\n".join(errors)
    assert "text blocks are not supported in -source 11" in joined, joined
    # Reported once, against the main file only (not duplicated via the test set's sourcepath view of the same file).
    assert sum("Service.java" in error and "text blocks" in error for error in errors) == 1, errors


# ---------------------------------------------------------------------------------------------------------------------
# Code-review fixes H1-H3, M4-M7. All build-tool extraction here is strictly OFFLINE (vendored jars / warmed .m2-repo).
# ---------------------------------------------------------------------------------------------------------------------


def test_sidecar_gradle_multi_project_extracts_both_subprojects(sidecar_jar: Path, tmp_path: Path) -> None:
    """H1: in a multi-project Gradle build, BOTH subprojects' source sets survive extraction with classpaths intact.

    The init script writes a per-project model file (rather than one shared file that the last project overwrote), and
    the Java side merges them. A vendored dependency on subproject `b` proves its classpath is preserved through merge.
    """
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    vendor_jar = _build_vendored_jar(tmp_path, "vendor", "VendorLib", "public static int answer() { return 42; }")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "multi"\ninclude("a", "b")\n', encoding="utf-8")
    # Root has no java plugin; both subprojects do. `b` depends on the vendored jar via files(...), resolved offline.
    (tmp_path / "a").mkdir()
    (tmp_path / "a" / "build.gradle.kts").write_text('plugins { id("java") }\n', encoding="utf-8")
    (tmp_path / "b").mkdir()
    (tmp_path / "b" / "build.gradle.kts").write_text(
        f'plugins {{ id("java") }}\ndependencies {{ implementation(files("{vendor_jar.as_posix()}")) }}\n',
        encoding="utf-8",
    )
    a_src = tmp_path / "a/src/main/java/a"
    a_src.mkdir(parents=True)
    (a_src / "A.java").write_text("package a; public class A {}\n", encoding="utf-8")
    b_src = tmp_path / "b/src/main/java/b"
    b_src.mkdir(parents=True)
    (b_src / "B.java").write_text("package b; import vendor.VendorLib; public class B { int v = VendorLib.answer(); }\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    names = {source_set["name"] for source_set in model["sourceSets"]}
    # Both subprojects' main source sets are present (multi-module names are "<projectPath>:<sourceSet>").
    assert {":a:main", ":b:main"} <= names, names
    a_main = next(ss for ss in model["sourceSets"] if ss["name"] == ":a:main")
    b_main = next(ss for ss in model["sourceSets"] if ss["name"] == ":b:main")
    assert any("A.java" in f for f in a_main["javaFiles"]), a_main["javaFiles"]
    assert any("B.java" in f for f in b_main["javaFiles"]), b_main["javaFiles"]
    # b's vendored dependency survived the per-project-file merge with its classpath intact.
    assert any(str(vendor_jar) in entry for entry in b_main["classpath"]), b_main["classpath"]


def test_sidecar_gradle_multi_module_test_depends_on_own_module_main(sidecar_jar: Path, tmp_path: Path) -> None:
    # G007: in a multi-module build the test source set is named "<modulePath>:test" (e.g. ":a:test") and must depend on
    # its OWN module's main (":a:main"), NOT an unqualified "main" that does not exist. A test that references a main-only
    # class resolves ONLY when that cross-source-root edge is correct; a broken edge leaves main's symbol unresolved.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    # Two subprojects make this a multi-module build, so source-set names are qualified (":a:test", ":b:main", ...).
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "multi"\ninclude("a", "b")\n', encoding="utf-8")
    for module in ("a", "b"):
        (tmp_path / module).mkdir()
        (tmp_path / module / "build.gradle.kts").write_text('plugins { id("java") }\n', encoding="utf-8")
        main_dir = tmp_path / module / "src/main/java" / module
        main_dir.mkdir(parents=True)
        (main_dir / "Main.java").write_text(
            f"package {module}; public class Main {{ public int value() {{ return 1; }} }}\n", encoding="utf-8"
        )
    a_test = tmp_path / "a/src/test/java/a"
    a_test.mkdir(parents=True)
    # ATest references module a's main-only type Main; with no compiled output offline, this resolves only via the
    # :a:test -> :a:main source-root dependency edge (NOT an unqualified "main", which would also wrongly match :b:main).
    (a_test / "ATest.java").write_text("package a; class ATest { int x = new Main().value(); }\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    names = {source_set["name"] for source_set in model["sourceSets"]}
    assert ":a:main" in names, names
    assert ":a:test" in names, names
    # If :a:test depended on an unqualified "main", A would be unresolved and extraction would report a semantic error.
    assert not model["errors"], model["errors"]


def test_sidecar_maven_module_dir_differs_from_artifact_id(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    """M4: a Maven module whose directory name differs from its artifactId still has its classpath resolved.

    The module lives in directory `child-dir` but declares artifactId `child-artifact`; directory derivation from the
    aggregator POM's <modules> entries (not the artifactId) is what locates its build-classpath output file.
    """
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>root</artifactId>"
        "<version>1</version><packaging>pom</packaging><modules><module>child-dir</module></modules></project>",
        encoding="utf-8",
    )
    child = tmp_path / "child-dir"
    child.mkdir()
    (child / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><parent><groupId>demo</groupId><artifactId>root</artifactId>"
        "<version>1</version></parent><artifactId>child-artifact</artifactId>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties>"
        "<dependencies><dependency><groupId>demo</groupId><artifactId>vendor-lib</artifactId>"
        "<version>1.0</version></dependency></dependencies></project>",
        encoding="utf-8",
    )
    src = child / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text(
        "package example; import vendor.VendorLib; public class App { int v = VendorLib.answer(); }\n", encoding="utf-8"
    )

    status = run_status(sidecar_jar, tmp_path, configuration=maven_offline_config)

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    child_main = next(ss for ss in model["sourceSets"] if any("child-dir" in f for f in ss["javaFiles"]))
    assert any("vendor-lib" in entry for entry in child_main["classpath"]), child_main["classpath"]


def test_sidecar_subproject_build_file_change_invalidates_model(sidecar_jar: Path, tmp_path: Path) -> None:
    """M5: editing a SUBPROJECT build file invalidates the cached extraction (not just root-level build files).

    A vendored dependency is added to subproject `b`'s build.gradle.kts between two status calls on the same long-lived
    sidecar; the extraction cache key must change so the second model picks up the new classpath entry.
    """
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    vendor_jar = _build_vendored_jar(tmp_path, "vendor", "VendorLib", "public static int answer() { return 42; }")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "multi"\ninclude("a", "b")\n', encoding="utf-8")
    (tmp_path / "a").mkdir()
    (tmp_path / "a" / "build.gradle.kts").write_text('plugins { id("java") }\n', encoding="utf-8")
    b_build = tmp_path / "b" / "build.gradle.kts"
    (tmp_path / "b").mkdir()
    b_build.write_text('plugins { id("java") }\n', encoding="utf-8")
    a_src = tmp_path / "a/src/main/java/a"
    a_src.mkdir(parents=True)
    (a_src / "A.java").write_text("package a; public class A {}\n", encoding="utf-8")
    b_src = tmp_path / "b/src/main/java/b"
    b_src.mkdir(parents=True)
    (b_src / "B.java").write_text("package b; public class B {}\n", encoding="utf-8")

    config = json.dumps({"buildToolMode": "gradle", "offline": True})
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        first = json.loads(client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=config)).to_json())
        assert first["ready"] is True, first["project_model"]["errors"]
        b_main_before = next(ss for ss in first["project_model"]["sourceSets"] if ss["name"] == ":b:main")
        assert not any(str(vendor_jar) in entry for entry in b_main_before["classpath"])

        # Add a dependency to the SUBPROJECT build file only; the root build files are unchanged.
        b_build.write_text(
            f'plugins {{ id("java") }}\ndependencies {{ implementation(files("{vendor_jar.as_posix()}")) }}\n',
            encoding="utf-8",
        )

        second = json.loads(client.status(refresh=True).to_json())
        assert second["ready"] is True, second["project_model"]["errors"]
        b_main_after = next(ss for ss in second["project_model"]["sourceSets"] if ss["name"] == ":b:main")
        assert any(str(vendor_jar) in entry for entry in b_main_after["classpath"]), b_main_after["classpath"]
    finally:
        client.shutdown()


def test_java_apply_refused_on_degraded_conventional_fallback_model(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """H3: with allow_conventional_fallback and a forced extraction failure, preview succeeds but apply writes nothing.

    The sabotaged offline Maven build (empty repo) cannot resolve the extraction plugins, so discovery degrades to a
    classpath-less conventional model. Preview is permissive; apply refuses with degraded_model_apply_refused and the
    source file is left byte-for-byte unchanged.
    """
    if shutil.which("mvn") is None:
        pytest.skip("mvn is required for Maven build-model extraction tests")
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    empty_repo = tmp_path / "empty-m2"
    empty_repo.mkdir()
    (tmp_path / ".mvn").mkdir()
    (tmp_path / ".mvn/maven.config").write_text(f"-Dmaven.repo.local={empty_repo}\n", encoding="utf-8")
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>degraded</artifactId><version>1</version></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    original = "package demo;\nclass Main {\n    void helper() {}\n    void run() { helper(); }\n}\n"
    source.write_text(original, encoding="utf-8")

    config = JavaRefactorConfig(enabled=True, build_tool_mode="maven", offline=True, allow_conventional_fallback=True)
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=config)
    try:
        preview = manager.semantic_rename("src/main/java/demo/Main.java", 3, 10, "renamed", apply=False)
        applied = manager.semantic_rename("src/main/java/demo/Main.java", 3, 10, "renamed", apply=True)
    finally:
        manager.shutdown()

    assert preview["accepted"] is True, preview
    assert applied["accepted"] is False, applied
    assert applied["applied"] is False, applied
    assert applied["refusal"]["code"] == "degraded_model_apply_refused", applied
    # Nothing was written.
    assert source.read_text(encoding="utf-8") == original


def test_sidecar_plain_model_includes_xlint_none(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 3 regression: every generated javac invocation carries the required default `-Xlint:none` (plain model).
    (tmp_path / "App.java").write_text("public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path)

    assert status["ready"] is True, status["project_model"]["errors"]
    options = status["project_model"]["sourceSets"][0]["javacOptions"]
    assert "-Xlint:none" in options, options


def test_sidecar_explicit_model_includes_xlint_none(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 3 regression: the explicit (manual source-root) model also emits `-Xlint:none`.
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "explicit", "sourceRoots": ["src/main/java"]}))

    assert status["ready"] is True, status["project_model"]["errors"]
    options = status["project_model"]["sourceSets"][0]["javacOptions"]
    assert "-Xlint:none" in options, options


def test_sidecar_maven_model_includes_xlint_none(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    # Blocker 3 regression: the real Maven-extracted model emits `-Xlint:none` alongside the extracted release/encoding.
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>xlint</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package demo; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=maven_offline_config)

    assert status["ready"] is True, status["project_model"]["errors"]
    options = status["project_model"]["sourceSets"][0]["javacOptions"]
    assert "-Xlint:none" in options, options


def test_sidecar_gradle_model_includes_xlint_none(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 3 regression: the real Gradle-extracted model emits `-Xlint:none` for each source set.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    main_src, _ = _write_gradle_java_project(tmp_path)
    (main_src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    assert status["ready"] is True, status["project_model"]["errors"]
    for source_set in status["project_model"]["sourceSets"]:
        assert "-Xlint:none" in source_set["javacOptions"], source_set["javacOptions"]


def test_sidecar_gradle_extraction_does_not_mutate_project_tree(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 4 regression: Gradle discovery must not modify the project (Phase 1 acceptance). Gradle's project-local
    # cache (.gradle) is relocated to an out-of-tree temp dir via --project-cache-dir, so the project tree is byte-for-byte
    # identical before and after, with no new .gradle/build/metadata files.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    main_src, _ = _write_gradle_java_project(tmp_path)
    (main_src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    def snapshot() -> dict[str, int]:
        return {str(path.relative_to(tmp_path)): path.stat().st_size for path in sorted(tmp_path.rglob("*")) if path.is_file()}

    before = snapshot()
    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))
    after = snapshot()

    assert status["ready"] is True, status["project_model"]["errors"]
    new_files = sorted(set(after) - set(before))
    assert not new_files, f"Gradle extraction wrote files into the project tree: {new_files}"
    assert before == after, "Gradle extraction modified existing files in the project tree"
    assert not (tmp_path / ".gradle").exists(), "Gradle wrote a project-local .gradle cache into the project tree"
    assert not (tmp_path / "build").exists(), "Gradle wrote a build directory into the project tree"

# --- V1 incomplete-analysis contract: warning-only preview, apply refused unless opted in ----------------------------


def _write_incomplete_analysis_project(tmp_path: Path) -> int:
    """One file with an unresolved import (incomplete classpath) plus one compiling file; returns the rename column."""
    (tmp_path / "Broken.java").write_text("import missing.Type; class Broken { Type value; }\n", encoding="utf-8")
    app = "class App { void run() { int amount = 1; System.out.println(amount); } }"
    (tmp_path / "App.java").write_text(app + "\n", encoding="utf-8")
    return app.index("amount = 1") + 1


def test_sidecar_incomplete_analysis_preview_is_warning_only_by_default(sidecar_jar: Path, tmp_path: Path) -> None:
    # Plan contract (§Incomplete project behavior): unresolved compiler diagnostics keep PREVIEW available with an
    # explicit incomplete-analysis warning instead of refusing the operation outright.
    column = _write_incomplete_analysis_project(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview("semanticRename", {"relativePath": "App.java", "line": 1, "column": column, "newName": "total"})
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    warnings = result["workspaceEdit"]["warnings"]
    assert any("incomplete analysis" in warning.lower() for warning in warnings), warnings
    assert any("apply is refused" in warning.lower() for warning in warnings), warnings


def test_sidecar_incomplete_analysis_apply_refused_by_default(sidecar_jar: Path, tmp_path: Path) -> None:
    # Plan contract: apply against an incompletely analyzed project is refused unless allow_incomplete_analysis=true.
    column = _write_incomplete_analysis_project(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.apply_refactor("semanticRename", {"relativePath": "App.java", "line": 1, "column": column, "newName": "total"})
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "incomplete_analysis_apply_refused"
    assert "allow_incomplete_analysis" in result["refusal"]["message"]
    assert "package missing does not exist" in result["refusal"]["message"]


def test_sidecar_incomplete_analysis_apply_planning_proceeds_when_opted_in(sidecar_jar: Path, tmp_path: Path) -> None:
    # With the explicit opt-in the sidecar plans the apply edit; the Python apply gate then tolerates only the
    # PRE-EXISTING diagnostics (newly introduced compiler errors still refuse — covered by the manager-level tests).
    column = _write_incomplete_analysis_project(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path), configuration=json.dumps({"allowIncompleteAnalysis": True})
            )
        )

        result = client.apply_refactor("semanticRename", {"relativePath": "App.java", "line": 1, "column": column, "newName": "total"})
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    assert result["workspaceEdit"]["stats"]["editCount"] >= 2


def test_sidecar_incomplete_analysis_resolve_target_still_works(sidecar_jar: Path, tmp_path: Path) -> None:
    # Read-only semantic analysis (resolveTarget/scanReferences) stays available on an incompletely analyzed project;
    # only mutating applies are gated.
    column = _write_incomplete_analysis_project(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.resolve_target("App.java", 1, column)
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    assert result["target"]["semanticKey"]["name"].endswith("amount"), result["target"]


def test_sidecar_hard_discovery_errors_still_refuse_preview(sidecar_jar: Path, tmp_path: Path) -> None:
    # The warning-only preview applies ONLY to compiler diagnostics; a hard discovery error (here: the configured
    # max_files limit is exceeded) still refuses preview outright.
    (tmp_path / "A.java").write_text("class A {}\n", encoding="utf-8")
    (tmp_path / "B.java").write_text("class B {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=json.dumps({"maxFiles": 1})))

        result = client.preview("semanticRename", {"relativePath": "A.java", "line": 1, "column": 7, "newName": "Total"})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "project_model_errors"


# --- Blocker 1: real compiler-option extraction is merged into each source set's javacOptions and exposed in status ---


def _source_set(status: dict, name: str = "main") -> dict:
    """The named source set from a status payload's project model."""
    return next(s for s in status["project_model"]["sourceSets"] if s["name"] == name)


def _write_gradle_compiler_args_project(tmp_path: Path, compile_block: str, clean_class: bool = True) -> None:
    """A minimal single-source-set Gradle (Kotlin DSL) project whose compileJava carries the given configuration block."""
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "args"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n' + f'tasks.named<JavaCompile>("compileJava") {{ {compile_block} }}\n',
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    body = "public class Api { public void run() {} }" if clean_class else "public class Api { void run() { return 1; } }"
    (src / "Api.java").write_text(f"package demo;\n{body}\n", encoding="utf-8")


def test_gradle_extracts_add_exports_into_javac_options(sidecar_jar: Path, tmp_path: Path) -> None:
    # --add-exports is not a managed flag, so both it and its module/package=target value must survive verbatim, in
    # order, in the merged javacOptions, and the clean class must still compile (ready True).
    _write_gradle_compiler_args_project(
        tmp_path,
        'options.compilerArgs.addAll(listOf("--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"))',
    )
    status = run_status(sidecar_jar, tmp_path)
    assert status["ready"] is True, status["errors"]
    options = _source_set(status)["javacOptions"]
    assert "--add-exports" in options, options
    assert options[options.index("--add-exports") + 1] == "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED", options


@pytest.mark.skipif(
    not javac_supports_release_21(),
    reason="requires a javac supporting --release 21 (JDK 21+); skipped on JDK 17 runs (passes under JDK 21)",
)
def test_gradle_extracts_enable_preview_into_javac_options(sidecar_jar: Path, tmp_path: Path) -> None:
    # --enable-preview at the running JDK's release compiles clean (non-preview) code successfully and the flag must be
    # present in the merged javacOptions alongside Serena's own managed --release.
    feature = int(os.environ.get("SERENA_TEST_JAVA_FEATURE", "21"))
    _write_gradle_compiler_args_project(
        tmp_path,
        f'options.release.set({feature}); options.compilerArgs.add("--enable-preview")',
    )
    status = run_status(sidecar_jar, tmp_path)
    assert status["ready"] is True, status["errors"]
    options = _source_set(status)["javacOptions"]
    assert "--enable-preview" in options, options
    assert options.count("--release") == 1, options


def test_gradle_compiler_arg_only_build_succeeds_and_exposes_arg(sidecar_jar: Path, tmp_path: Path) -> None:
    # A build whose ONLY compile configuration is a compiler arg (no release/source/target) still builds successfully and
    # surfaces that arg in javacOptions — the extraction path is not gated on any managed setting being present.
    _write_gradle_compiler_args_project(tmp_path, 'options.compilerArgs.add("-Xlint:all")')
    status = run_status(sidecar_jar, tmp_path)
    assert status["ready"] is True, status["errors"]
    assert "-Xlint:all" in _source_set(status)["javacOptions"]


def test_gradle_extracted_managed_flag_does_not_duplicate(sidecar_jar: Path, tmp_path: Path) -> None:
    # Merge safety: an extracted arg that collides with a Serena-managed flag (-encoding here) must be dropped together
    # with its value token rather than appended, so the managed flag appears exactly once and keeps Serena's value.
    _write_gradle_compiler_args_project(
        tmp_path,
        'options.encoding = "UTF-8"; options.compilerArgs.addAll(listOf("-encoding", "US-ASCII", "-Xlint:all"))',
    )
    status = run_status(sidecar_jar, tmp_path)
    assert status["ready"] is True, status["errors"]
    options = _source_set(status)["javacOptions"]
    assert options.count("-encoding") == 1, options
    assert options[options.index("-encoding") + 1] == "UTF-8", options
    assert "US-ASCII" not in options, options
    assert "-Xlint:all" in options, options


def test_maven_extracts_compiler_plugin_args_into_javac_options(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    # Maven extraction must read the compiler plugin's effective <compilerArgs> and merge them into javacOptions; the
    # non-managed --add-exports and its value survive verbatim and the clean class compiles.
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>args-app</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties>"
        "<build><plugins><plugin>"
        "<groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>"
        "<configuration><compilerArgs>"
        "<arg>--add-exports</arg><arg>jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>"
        "</compilerArgs></configuration>"
        "</plugin></plugins></build></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package demo;\npublic class App { public void run() {} }\n", encoding="utf-8")
    status = run_status(sidecar_jar, tmp_path, configuration=maven_offline_config)
    assert status["ready"] is True, status["errors"]
    options = _source_set(status)["javacOptions"]
    assert "--add-exports" in options, options
    assert options[options.index("--add-exports") + 1] == "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED", options


# --- Blocker 2: non-conventional Maven generated roots are discovered, made visible on sourcepath, and edit-refused ---


def test_maven_discovers_non_conventional_generated_root(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    # A code-generator output dir (here protobuf-style target/generated-sources/protobuf/java) lives OUTSIDE the
    # annotation-processor convention (target/generated-sources/annotations) and is NOT registered via build-helper, yet
    # must be (a) classified as a generated root, (b) placed on -sourcepath so main code referencing the generated type
    # resolves (ready True), and (c) refused as a non-editable target. A single extraction proves all three.
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>gen-app</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties></project>",
        encoding="utf-8",
    )
    main_src = tmp_path / "src/main/java/demo"
    main_src.mkdir(parents=True)
    (main_src / "App.java").write_text("package demo;\npublic class App { Proto p; }\n", encoding="utf-8")
    gen = tmp_path / "target/generated-sources/protobuf/java/demo"
    gen.mkdir(parents=True)
    (gen / "Proto.java").write_text("package demo;\npublic class Proto {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = json.loads(
            client.initialize(
                JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=maven_offline_config)
            ).to_json()
        )
        assert status["ready"] is True, status["errors"]
        generated_roots = _source_set(status)["generatedRoots"]
        assert "target/generated-sources/protobuf/java" in generated_roots, generated_roots
        # Renaming the generated type from its editable usage site must still be refused, because the declaration edit
        # (and file rename) would land inside the generated root — proving the gate covers non-conventional roots too.
        app_text = (main_src / "App.java").read_text(encoding="utf-8")
        proto_col = app_text.split("\n")[1].index("Proto") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/App.java", "line": 2, "column": proto_col, "newName": "Proto2"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "non_editable_target", result
