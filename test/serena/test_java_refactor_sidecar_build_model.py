"""G005: a successful-but-empty build-tool model fails closed by default, opting into fallback only when configured.

A Maven/Gradle project whose extraction resolves but reports NO Java source sets (an aggregator pom, a project without
the java plugin, or extraction that missed the source sets) must be treated as a build-model error — not silently
degraded to a classpath-poor conventional scan that can still produce previews. Fallback is allowed only when
``allow_conventional_fallback`` is set, and is then flagged so the apply path refuses on the degraded model.
"""

import json
import shutil
import subprocess
from pathlib import Path

import pytest

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

from test.serena._java_refactor_sidecar_helpers import (  # noqa: F401
    text_edits,
    file_ops,
    sidecar_jar,
    run_status,
    maven_offline_repo,
    write_maven_offline_project,
    _build_processor_jar,
)


def _write_gradle_two_project_reactor(tmp_path: Path) -> None:
    """An UNBUILT two-project Gradle build where :b depends on project(':a') and references a.A by source."""
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "root"\ninclude("a", "b")\n', encoding="utf-8")
    (tmp_path / "a").mkdir()
    (tmp_path / "b").mkdir()
    (tmp_path / "a/build.gradle.kts").write_text("plugins { java }\n", encoding="utf-8")
    (tmp_path / "b/build.gradle.kts").write_text('plugins { java }\ndependencies { implementation(project(":a")) }\n', encoding="utf-8")
    (tmp_path / "a/src/main/java/a").mkdir(parents=True)
    (tmp_path / "b/src/main/java/b").mkdir(parents=True)
    (tmp_path / "a/src/main/java/a/A.java").write_text(
        "package a;\npublic class A {\n    public int value() { return 1; }\n}\n", encoding="utf-8"
    )
    (tmp_path / "b/src/main/java/b/B.java").write_text(
        "package b;\nimport a.A;\npublic class B {\n    int use() { return new A().value(); }\n}\n", encoding="utf-8"
    )


def test_gradle_multi_project_resolves_dependency_from_source_unbuilt(sidecar_jar: Path, tmp_path: Path) -> None:
    # G005: in an UNBUILT multi-project Gradle build, module :b references :a; modeling the project dependency edge
    # feeds :a's source root into :b's -sourcepath, so renaming a.A.value rewrites the reference in b.B WITHOUT any
    # precompiled output of :a.
    _write_gradle_two_project_reactor(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=json.dumps({"allowIncompleteAnalysis": True}))
        )
        line = "    public int value() { return 1; }"
        result = client.preview(
            "semanticRename",
            {"relativePath": "a/src/main/java/a/A.java", "line": 3, "column": line.index("value") + 1, "newName": "renamed"},
        )
    finally:
        client.shutdown()

    assert result.get("accepted") is True, result
    edited = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert "b/src/main/java/b/B.java" in edited, result


def _gradle_no_java_plugin_project(tmp_path: Path) -> None:
    """A real Gradle project WITHOUT the java plugin (so extraction resolves but reports no Java source sets).

    A stray ``src/main/java`` source file is included: if the discoverer silently fell back to conventional discovery it
    would pick this up, so its ABSENCE from a fail-closed model proves we did not degrade.
    """
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "no-java"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text("plugins { base }\n", encoding="utf-8")
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package demo; public class App {}\n", encoding="utf-8")


def test_gradle_empty_source_sets_fail_closed_by_default(sidecar_jar: Path, tmp_path: Path) -> None:
    _gradle_no_java_plugin_project(tmp_path)

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"offline": True, "buildToolMode": "gradle"}))

    assert status["ready"] is False, status
    combined = "\n".join(status["errors"]) + "\n" + "\n".join(status["project_model"]["errors"])
    assert "no Java source sets" in combined, combined
    # Fail-closed means we did NOT silently degrade to conventional discovery, so the stray source file is not indexed.
    assert status["project_model"]["javaFileCount"] == 0, status["project_model"]


def test_gradle_empty_source_sets_degrade_only_when_fallback_allowed(sidecar_jar: Path, tmp_path: Path) -> None:
    _gradle_no_java_plugin_project(tmp_path)

    status = run_status(
        sidecar_jar,
        tmp_path,
        configuration=json.dumps({"offline": True, "buildToolMode": "gradle", "allowConventionalFallback": True}),
    )

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    # The degraded model is flagged so the apply path refuses (H3 degraded-model gate).
    assert model["conventionalFallbackUsed"] is True, model
    # Conventional fallback discovered the stray source file the empty build model omitted.
    assert "src/main/java/demo/App.java" in model["allJavaFiles"], model["allJavaFiles"]


def test_maven_aggregator_pom_fails_closed_by_default(sidecar_jar: Path, tmp_path: Path, maven_offline_repo: Path) -> None:
    if shutil.which("mvn") is None:
        pytest.skip("mvn is required for Maven build-model extraction tests")
    write_maven_offline_project(tmp_path, maven_offline_repo)
    # An aggregator (packaging=pom) project has no Java sources of its own: extraction resolves but yields no source sets.
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>aggregator</artifactId><version>1</version>"
        "<packaging>pom</packaging>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties></project>",
        encoding="utf-8",
    )

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "maven", "offline": True}))

    assert status["ready"] is False, status
    combined = "\n".join(status["errors"]) + "\n" + "\n".join(status["project_model"]["errors"])
    assert "no Java source sets" in combined, combined


def _write_maven_two_module_reactor(tmp_path: Path) -> None:
    """An UNBUILT Maven reactor where module b depends on reactor sibling a and references demo.A by source."""
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties>"
        "<modules><module>a</module><module>b</module></modules></project>",
        encoding="utf-8",
    )
    (tmp_path / "a").mkdir()
    (tmp_path / "b").mkdir()
    (tmp_path / "a/pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<parent><groupId>demo</groupId><artifactId>root</artifactId><version>1</version></parent>"
        "<artifactId>a</artifactId></project>",
        encoding="utf-8",
    )
    (tmp_path / "b/pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<parent><groupId>demo</groupId><artifactId>root</artifactId><version>1</version></parent>"
        "<artifactId>b</artifactId>"
        "<dependencies><dependency><groupId>demo</groupId><artifactId>a</artifactId><version>1</version></dependency></dependencies>"
        "</project>",
        encoding="utf-8",
    )
    (tmp_path / "a/src/main/java/demo").mkdir(parents=True)
    (tmp_path / "b/src/main/java/bpkg").mkdir(parents=True)
    (tmp_path / "a/src/main/java/demo/A.java").write_text(
        "package demo;\npublic class A {\n    public int value() { return 1; }\n}\n", encoding="utf-8"
    )
    (tmp_path / "b/src/main/java/bpkg/B.java").write_text(
        "package bpkg;\nimport demo.A;\npublic class B {\n    int use() { return new A().value(); }\n}\n", encoding="utf-8"
    )


def test_maven_reactor_resolves_dependency_from_source_unbuilt(sidecar_jar: Path, tmp_path: Path, maven_offline_repo: Path) -> None:
    # G005: in an UNBUILT Maven reactor, module b depends on sibling a; modeling the reactor dependency edge feeds a's
    # source root into b's -sourcepath, so renaming demo.A.value rewrites the reference in bpkg.B without a's compiled
    # output existing.
    _write_maven_two_module_reactor(tmp_path)
    write_maven_offline_project(tmp_path, maven_offline_repo)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                configuration=json.dumps({"buildToolMode": "maven", "offline": True, "allowIncompleteAnalysis": True}),
            )
        )
        line = "    public int value() { return 1; }"
        result = client.preview(
            "semanticRename",
            {"relativePath": "a/src/main/java/demo/A.java", "line": 3, "column": line.index("value") + 1, "newName": "renamed"},
        )
    finally:
        client.shutdown()

    assert result.get("accepted") is True, result
    edited = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert "b/src/main/java/bpkg/B.java" in edited, result


# --- G021: build-model feature coverage (each feature backed by an assertion-bearing acceptance test) ------------------
#
# The V2 brief presumed coverage of the full build-feature set but left it unproven. These tests build a real fixture
# project for each feature and assert the extracted model captures it, rather than presuming it. Gradle/Maven tests skip
# when the build tool is unavailable.


def _write_gradle_composite_build(tmp_path: Path) -> None:
    """A root Gradle build that `includeBuild("lib")` (a separate, composite/included build) and uses its class."""
    (tmp_path / "settings.gradle.kts").write_text(
        'rootProject.name = "root"\nincludeBuild("lib")\n', encoding="utf-8"
    )
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { java }\ndependencies { implementation("demo:lib") }\n', encoding="utf-8"
    )
    (tmp_path / "src/main/java/app").mkdir(parents=True)
    # App does not reference Lib by source: cross-BUILD source edges (rename propagation across a composite boundary on
    # an unbuilt build) are a deeper feature than extraction. This fixture proves the included build's source sets are
    # captured/indexed in the model, which is the build-model integration contract under test here.
    (tmp_path / "src/main/java/app/App.java").write_text(
        "package app;\npublic class App {\n    int v = 1;\n}\n", encoding="utf-8"
    )
    lib = tmp_path / "lib"
    (lib / "src/main/java/lib").mkdir(parents=True)
    (lib / "settings.gradle.kts").write_text('rootProject.name = "lib"\n', encoding="utf-8")
    (lib / "build.gradle.kts").write_text('plugins { java }\ngroup = "demo"\n', encoding="utf-8")
    (lib / "src/main/java/lib/Lib.java").write_text(
        "package lib;\npublic class Lib {\n    public int value() { return 1; }\n}\n", encoding="utf-8"
    )


def test_gradle_included_build_source_sets_are_extracted(sidecar_jar: Path, tmp_path: Path) -> None:
    # Running `dumpSerenaModel` by task name only executes it in the ROOT build, so an included (composite) build's source
    # sets were silently missing from the model. The init script now makes the root task depend on each included build's
    # `dumpSerenaModel`, qualifying its project id by build name so its ':' root does not collide with the root build's.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    _write_gradle_composite_build(tmp_path)

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    names = {source_set["name"] for source_set in model["sourceSets"]}
    # The included build's main source set is captured alongside the root build's, qualified by the included build name.
    assert ":lib:main" in names, names
    # The included build's source file is indexed (its root feeds discovery), not just the root build's.
    assert any("lib/src/main/java/lib/Lib.java" in java_file for java_file in model["allJavaFiles"]), model["allJavaFiles"]


def test_gradle_toolchain_sets_source_target_without_synthesizing_release(sidecar_jar: Path, tmp_path: Path) -> None:
    # A Java toolchain selects the javac binary and pins sourceCompatibility/targetCompatibility, but must NOT be turned
    # into `--release N` (which would diverge from the real build and reject legal `--add-exports` builds). The toolchain
    # language version is 21 to match the running/daemon JVM so auto-detection resolves it offline (no JDK download).
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "tc"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        'java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }\n',
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    main = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    assert main["source"] == "21", main
    assert main["target"] == "21", main
    # No explicit options.release was set, so the toolchain must not synthesize a --release (reported as absent/null) and
    # must not pass --release to javac.
    assert not main["release"], main
    assert "--release" not in main["javacOptions"], main["javacOptions"]


def test_gradle_kotlin_output_dir_captured_in_outputdirs(sidecar_jar: Path, tmp_path: Path) -> None:
    # Kotlin/JVM mixed projects place javac-visible symbols in the Kotlin compile output. The extractor captures the
    # destination of a task NAMED compileKotlin (exactly what the Kotlin Gradle plugin registers). This exercises that
    # branch hermetically/offline by registering a same-shaped JavaCompile task named compileKotlin (never executed).
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "kt"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        'tasks.register<JavaCompile>("compileKotlin") {\n'
        '    destinationDirectory.set(layout.buildDirectory.dir("classes/kotlin/main"))\n'
        "}\n",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    main = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    # The Kotlin compile output directory is exposed so javac's classpath can see Kotlin-compiled symbols.
    assert any("classes/kotlin/main" in output_dir for output_dir in main["outputDirs"]), main["outputDirs"]


def test_gradle_annotation_processor_option_reaches_javac_options(sidecar_jar: Path, tmp_path: Path) -> None:
    # Annotation-processor options (`-Akey=value`) configured on the compile task must reach javac verbatim so processors
    # behave as they do in the real build. They are carried through the extracted compilerArgs into javacOptions.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "apopt"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        'tasks.named<JavaCompile>("compileJava") { options.compilerArgs.add("-Amyproc.key=myvalue") }\n',
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    main = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    assert "-Amyproc.key=myvalue" in main["javacOptions"], main["javacOptions"]


def test_maven_active_profile_contributes_compiler_release(sidecar_jar: Path, tmp_path: Path, maven_offline_repo: Path) -> None:
    # A configured Maven profile must flow through extraction (the sidecar passes `-P<profile>` to both Maven goals). The
    # base release is 11; activating profile `j17` overrides it to 17 in the effective POM, proving profiles take effect.
    if shutil.which("mvn") is None:
        pytest.skip("mvn is required for Maven build-model extraction tests")
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>prof</artifactId><version>1</version>"
        "<properties><maven.compiler.release>11</maven.compiler.release></properties>"
        "<profiles><profile><id>j17</id>"
        "<properties><maven.compiler.release>17</maven.compiler.release></properties>"
        "</profile></profiles></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package demo; public class App {}\n", encoding="utf-8")

    # Without the profile the effective release is the base 11.
    base = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "maven", "offline": True}))
    base_main = next(source_set for source_set in base["project_model"]["sourceSets"] if source_set["name"] == "main")
    assert base_main["release"] == "11", base_main

    # Activating profile j17 must override it to 17, proving the configured profile reached the extraction invocation.
    activated = run_status(
        sidecar_jar, tmp_path,
        configuration=json.dumps({"buildToolMode": "maven", "offline": True, "mavenProfiles": ["j17"]}),
    )
    activated_main = next(
        source_set for source_set in activated["project_model"]["sourceSets"] if source_set["name"] == "main"
    )
    assert activated_main["release"] == "17", activated_main


def test_gradle_annotation_processor_classpath_jars_are_extracted(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008 gap 1: the init.gradle resolves the annotationProcessor configuration at lines 62-64, but no test asserts
    # that real jar paths appear in the model's annotationProcessorPath field. This uses a pre-built stub jar placed
    # via files(...) in the annotationProcessor configuration so no network resolution is needed (offline).
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    proc_jar = _build_processor_jar(tmp_path)
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "apcp"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        f'plugins {{ id("java") }}\n'
        f'dependencies {{ annotationProcessor(files("{proc_jar.as_posix()}")) }}\n',
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/example"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    main = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    # The processor jar must appear in annotationProcessorPath, not just as a -A compiler arg.
    assert any(proc_jar.name in entry for entry in main["annotationProcessorPath"]), main["annotationProcessorPath"]


def test_maven_source_target_without_release_sets_source_and_target_flags(
    sidecar_jar: Path, tmp_path: Path, maven_offline_repo: Path
) -> None:
    # G008 gap 2: maven.compiler.source + maven.compiler.target (the legacy non-release pair) must produce -source N
    # -target N in javacOptions, NOT --release N. This is distinct from maven.compiler.release (already tested).
    if shutil.which("mvn") is None:
        pytest.skip("mvn is required for Maven build-model extraction tests")
    write_maven_offline_project(tmp_path, maven_offline_repo)
    (tmp_path / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>src-tgt</artifactId><version>1</version>"
        "<properties>"
        "<maven.compiler.source>11</maven.compiler.source>"
        "<maven.compiler.target>11</maven.compiler.target>"
        "</properties></project>",
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package demo; public class App {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "maven", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    main = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    # The legacy pair must produce source/target fields and the corresponding javac flags.
    assert main["source"] == "11", main
    assert main["target"] == "11", main
    assert not main.get("release"), main  # no --release when only source+target are set
    assert "-source" in main["javacOptions"], main["javacOptions"]
    assert "-target" in main["javacOptions"], main["javacOptions"]
    assert "--release" not in main["javacOptions"], main["javacOptions"]


def test_explicit_model_named_modules_produces_distinct_source_sets(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008 gap 3: §17.3 flat-module explicit override — modules: [{name, sourceRoots, release}, ...] must produce one
    # distinct source set per module entry, each with its own name, source roots, and per-module release. No build tool
    # required; this is a pure explicit-model test.
    (tmp_path / "lib/src/lib").mkdir(parents=True)
    (tmp_path / "app/src/app").mkdir(parents=True)
    (tmp_path / "lib/src/lib/Lib.java").write_text("package lib;\npublic class Lib {}\n", encoding="utf-8")
    (tmp_path / "app/src/app/App.java").write_text("package app;\npublic class App {}\n", encoding="utf-8")

    config = json.dumps({
        "buildToolMode": "explicit",
        "model": {
            "modules": [
                {"name": "lib", "sourceRoots": ["lib/src"], "release": "17"},
                {"name": "app", "sourceRoots": ["app/src"], "release": "21"},
            ]
        },
    })
    status = run_status(sidecar_jar, tmp_path, configuration=config)

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    # In a multi-module explicit model each source set is qualified as "<module>:main" (same convention as Gradle/Maven).
    source_set_names = {source_set["name"] for source_set in model["sourceSets"]}
    assert "lib:main" in source_set_names, source_set_names
    assert "app:main" in source_set_names, source_set_names
    lib_set = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "lib:main")
    app_set = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "app:main")
    assert lib_set["release"] == "17", lib_set
    assert app_set["release"] == "21", app_set
    # Source roots must be distinct between the two modules.
    lib_roots = set(lib_set.get("sourceRoots", []))
    app_roots = set(app_set.get("sourceRoots", []))
    assert lib_roots.isdisjoint(app_roots), (lib_roots, app_roots)


def test_gradle_test_scoped_dependency_excluded_from_main_classpath(sidecar_jar: Path, tmp_path: Path) -> None:
    # G021 (B5): a Gradle testImplementation dependency must appear on the test source set's classpath but NOT on main's,
    # mirroring the Maven test-scope separation (test_java_refactor_sidecar_model.py::
    # test_sidecar_maven_separates_compile_and_test_classpaths). This proves Gradle test classpath is resolved distinctly
    # so main is never compiled against test-only libraries when refactoring tests. Offline: the dependency is a pre-built
    # stub jar wired via files(...) so no network resolution is needed.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    dep_jar = _build_processor_jar(tmp_path)
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "testcp"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        f'plugins {{ id("java") }}\n'
        f'dependencies {{ testImplementation(files("{dep_jar.as_posix()}")) }}\n',
        encoding="utf-8",
    )
    main_src = tmp_path / "src/main/java/example"
    test_src = tmp_path / "src/test/java/example"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    (main_src / "App.java").write_text("package example; public class App {}\n", encoding="utf-8")
    (test_src / "AppTest.java").write_text("package example; public class AppTest {}\n", encoding="utf-8")

    status = run_status(sidecar_jar, tmp_path, configuration=json.dumps({"buildToolMode": "gradle", "offline": True}))

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    main_set = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "main")
    test_set = next(source_set for source_set in model["sourceSets"] if source_set["name"] == "test")

    def classpath_value(options: list[str]) -> str:
        return options[options.index("-classpath") + 1] if "-classpath" in options else ""

    # The test-only dependency must be on the test classpath and absent from the main classpath.
    assert dep_jar.name not in classpath_value(main_set["javacOptions"]), main_set["javacOptions"]
    assert dep_jar.name in classpath_value(test_set["javacOptions"]), test_set["javacOptions"]


# --- G004: build-model Gradle OPERATION coverage (not just model extraction) ------------------------------------------
#
# G021 proved the model EXTRACTS included builds, toolchains, Kotlin output dirs, and extra source sets. G004 proves a
# real refactor operation actually RESOLVES against each shape (or is soundly REFUSED when the shape cannot be modeled),
# honoring the governing rule: a source set that cannot be soundly modeled is marked classpath-UNPROVEN and apply is
# refused with a structured code — never a silent partial edit.


def _apply_op(sidecar_jar: Path, project_root: Path, operation: str, params: dict, configuration: str) -> dict:
    """Initializes a sidecar against ``project_root`` and runs a real apply (the full model+apply gate), returning the
    raw response (``accepted`` plus either ``workspaceEdit`` or ``refusal``)."""
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        return client.apply_refactor(operation, params)
    finally:
        client.shutdown()


GRADLE_OFFLINE = json.dumps({"buildToolMode": "gradle", "offline": True})


def _write_gradle_included_build_for_operation(tmp_path: Path) -> None:
    """Root build that `includeBuild("lib")`; the included build owns `lib.Lib`. The root app does NOT depend on lib, so
    extraction never has to build the included build's jar — the operation runs purely on the included build's modeled
    `:lib:main` source set."""
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "root"\nincludeBuild("lib")\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text('plugins { java }\n', encoding="utf-8")
    (tmp_path / "src/main/java/app").mkdir(parents=True)
    (tmp_path / "src/main/java/app/App.java").write_text(
        "package app;\npublic class App {\n    int v = 1;\n}\n", encoding="utf-8"
    )
    lib = tmp_path / "lib"
    (lib / "src/main/java/lib").mkdir(parents=True)
    (lib / "settings.gradle.kts").write_text('rootProject.name = "lib"\n', encoding="utf-8")
    (lib / "build.gradle.kts").write_text('plugins { java }\ngroup = "demo"\n', encoding="utf-8")
    (lib / "src/main/java/lib/Lib.java").write_text(
        "package lib;\npublic class Lib {\n    public int value() { return 1; }\n}\n", encoding="utf-8"
    )


def test_gradle_included_build_operation_renames_in_included_source_set(sidecar_jar: Path, tmp_path: Path) -> None:
    # Operation coverage: a rename executes against a source set that belongs to a SEPARATE included (composite) build,
    # proving the engine discovers, indexes, and edits the included build's `:lib:main` — not merely that the model lists
    # it. The included build is fully modeled (its own classpath resolves), so apply is accepted and edits lib's source.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    _write_gradle_included_build_for_operation(tmp_path)
    line = "    public int value() { return 1; }"
    result = _apply_op(
        sidecar_jar, tmp_path, "semanticRename",
        {"relativePath": "lib/src/main/java/lib/Lib.java", "line": 3, "column": line.index("value") + 1, "newName": "renamed"},
        GRADLE_OFFLINE,
    )

    assert result.get("accepted") is True, result
    edited = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert "lib/src/main/java/lib/Lib.java" in edited, result


def test_gradle_release_below_sidecar_jdk_resolves_and_applies(sidecar_jar: Path, tmp_path: Path) -> None:
    # A source set whose `--release` is LOWER than the sidecar's own JDK (release 17 under a JDK 21 sidecar) must still
    # resolve java.lang and apply: `javac --release 17` is supported on JDK 21, so the operation succeeds.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "rel17"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        'tasks.named<JavaCompile>("compileJava") { options.release.set(17) }\n',
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/app"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package app;\npublic class App {\n    int counter = 1;\n}\n", encoding="utf-8")

    line = "    int counter = 1;"
    result = _apply_op(
        sidecar_jar, tmp_path, "semanticRename",
        {"relativePath": "src/main/java/app/App.java", "line": 3, "column": line.index("counter") + 1, "newName": "total"},
        GRADLE_OFFLINE,
    )

    assert result.get("accepted") is True, result
    edited = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert "src/main/java/app/App.java" in edited, result


def test_gradle_release_above_sidecar_jdk_refuses_apply(sidecar_jar: Path, tmp_path: Path) -> None:
    # A source set whose `--release` EXCEEDS the sidecar JDK (release 25 under a JDK 21 sidecar) cannot be soundly
    # analyzed: `javac --release 25` is unsupported on JDK 21, so analysis is incomplete. Per the governing rule the
    # mutating apply must be REFUSED (not silently produce an edit against an unanalyzable source set).
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "rel25"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        'tasks.named<JavaCompile>("compileJava") { options.release.set(25) }\n',
        encoding="utf-8",
    )
    src = tmp_path / "src/main/java/app"
    src.mkdir(parents=True)
    (src / "App.java").write_text("package app;\npublic class App {\n    int counter = 1;\n}\n", encoding="utf-8")

    line = "    int counter = 1;"
    result = _apply_op(
        sidecar_jar, tmp_path, "semanticRename",
        {"relativePath": "src/main/java/app/App.java", "line": 3, "column": line.index("counter") + 1, "newName": "total"},
        GRADLE_OFFLINE,
    )

    # Sound, STRUCTURED refusal (not an opaque javac exception, not a silent partial edit): validating the source set at
    # release 25 fails on this JDK, marking analysis incomplete, so the apply gate refuses before mutating anything.
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "incomplete_analysis_apply_refused", result


def _write_gradle_java_depends_on_kotlin_output(tmp_path: Path) -> None:
    """Java in `main` references `kt.KotlinThing`, whose compiled class lives ONLY in the Kotlin compile output dir
    (`build/classes/kotlin/main`) — exactly where the Kotlin Gradle plugin emits javac-visible symbols. The class is
    produced by javac (a .class is language-agnostic) so the fixture stays hermetic/offline with no Kotlin toolchain.
    A `compileKotlin` task (JavaCompile-typed, never executed) advertises that destination so the extractor captures it.
    """
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "ktmix"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        'tasks.register<JavaCompile>("compileKotlin") {\n'
        '    destinationDirectory.set(layout.buildDirectory.dir("classes/kotlin/main"))\n'
        "}\n",
        encoding="utf-8",
    )
    kotlin_out = tmp_path / "build/classes/kotlin/main"
    kotlin_out.mkdir(parents=True)
    kt_src = tmp_path / "kt_src/kt"
    kt_src.mkdir(parents=True)
    (kt_src / "KotlinThing.java").write_text(
        "package kt; public class KotlinThing { public static int answer() { return 42; } }\n", encoding="utf-8"
    )
    subprocess.run(
        ["javac", "-d", str(kotlin_out), str(kt_src / "KotlinThing.java")], check=True, capture_output=True, text=True
    )
    src = tmp_path / "src/main/java/app"
    src.mkdir(parents=True)
    (src / "App.java").write_text(
        "package app;\nimport kt.KotlinThing;\npublic class App {\n    int v = KotlinThing.answer();\n}\n", encoding="utf-8"
    )


def test_gradle_java_resolves_kotlin_output_dir_and_applies(sidecar_jar: Path, tmp_path: Path) -> None:
    # Operation coverage for Kotlin-mixed projects: App resolves `kt.KotlinThing` ONLY from the captured Kotlin compile
    # output dir. The extractor now places that dir on the javac compile classpath (not only informational outputDirs),
    # so the project analyzes cleanly and the mutating apply is accepted. Without that wiring App fails to resolve
    # KotlinThing and apply would be refused as incomplete — this test pins the resolution path.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    _write_gradle_java_depends_on_kotlin_output(tmp_path)

    line = "    int v = KotlinThing.answer();"
    result = _apply_op(
        sidecar_jar, tmp_path, "semanticRename",
        {"relativePath": "src/main/java/app/App.java", "line": 4, "column": line.index("v ") + 1, "newName": "result"},
        GRADLE_OFFLINE,
    )

    assert result.get("accepted") is True, result
    edited = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert "src/main/java/app/App.java" in edited, result


def _write_gradle_extra_source_set(tmp_path: Path) -> None:
    """A custom `integrationTest` source set whose code references a `main` symbol. The sidecar models a non-main source
    set as depending on `main`, feeding main's roots into the extra set's -sourcepath so cross-set references resolve."""
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "extraset"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\nsourceSets { create("integrationTest") }\n', encoding="utf-8"
    )
    main_src = tmp_path / "src/main/java/demo"
    it_src = tmp_path / "src/integrationTest/java/demo"
    main_src.mkdir(parents=True)
    it_src.mkdir(parents=True)
    (main_src / "Service.java").write_text(
        "package demo;\npublic class Service {\n    public int value() { return 1; }\n}\n", encoding="utf-8"
    )
    (it_src / "ServiceIT.java").write_text(
        "package demo;\nclass ServiceIT {\n    int run() { return new Service().value(); }\n}\n", encoding="utf-8"
    )


def test_gradle_extra_source_set_operation_propagates_into_integration_test(sidecar_jar: Path, tmp_path: Path) -> None:
    # Operation coverage for source sets BEYOND main/test: renaming a `main` symbol rewrites its reference in a custom
    # `integrationTest` source set, proving the extra set is both modeled and edited (cross-set sourcepath wiring).
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    _write_gradle_extra_source_set(tmp_path)

    line = "    public int value() { return 1; }"
    result = _apply_op(
        sidecar_jar, tmp_path, "semanticRename",
        {"relativePath": "src/main/java/demo/Service.java", "line": 3, "column": line.index("value") + 1, "newName": "magnitude"},
        json.dumps({"buildToolMode": "gradle", "offline": True, "allowIncompleteAnalysis": True}),
    )

    assert result.get("accepted") is True, result
    edited = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert "src/main/java/demo/Service.java" in edited, result
    # The custom source set's reference to the renamed main symbol is rewritten too.
    assert "src/integrationTest/java/demo/ServiceIT.java" in edited, result


def _write_gradle_unmodelable_source_set(tmp_path: Path) -> None:
    """A custom `feature` source set declares an UNRESOLVABLE dependency. Offline, its compile classpath cannot be
    resolved, so the source set cannot be soundly modeled. `main` and `feature` sources are self-contained (they do not
    use the missing dependency) so javac stays clean — the only model defect is the unprovable classpath."""
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "unprovable"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        'sourceSets { create("feature") }\n'
        'dependencies { "featureImplementation"("does.not:exist:9.9.9") }\n',
        encoding="utf-8",
    )
    main_src = tmp_path / "src/main/java/demo"
    feat_src = tmp_path / "src/feature/java/feat"
    main_src.mkdir(parents=True)
    feat_src.mkdir(parents=True)
    (main_src / "Service.java").write_text(
        "package demo;\npublic class Service {\n    public int value() { return 1; }\n}\n", encoding="utf-8"
    )
    (feat_src / "Feat.java").write_text("package feat;\nclass Feat {\n    int x = 1;\n}\n", encoding="utf-8")


def test_gradle_unmodelable_source_set_marks_unproven_and_refuses_apply(sidecar_jar: Path, tmp_path: Path) -> None:
    # Governing rule: a source set whose compile classpath cannot be resolved is marked classpath-UNPROVEN, and apply is
    # REFUSED with a structured code rather than silently producing a partial edit against an unverified model. The edit
    # itself targets `main` (whose classpath resolves) — the refusal is driven purely by the unprovable `feature` set.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    _write_gradle_unmodelable_source_set(tmp_path)

    line = "    public int value() { return 1; }"
    result = _apply_op(
        sidecar_jar, tmp_path, "semanticRename",
        {"relativePath": "src/main/java/demo/Service.java", "line": 3, "column": line.index("value") + 1, "newName": "magnitude"},
        GRADLE_OFFLINE,
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "classpath_unproven_apply_refused", result
    # The refusal names the unprovable source set, so the user knows which set to fix.
    assert "feature" in result["refusal"]["message"], result
