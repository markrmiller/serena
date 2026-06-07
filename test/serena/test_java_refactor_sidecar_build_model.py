"""G005: a successful-but-empty build-tool model fails closed by default, opting into fallback only when configured.

A Maven/Gradle project whose extraction resolves but reports NO Java source sets (an aggregator pom, a project without
the java plugin, or extraction that missed the source sets) must be treated as a build-model error — not silently
degraded to a classpath-poor conventional scan that can still produce previews. Fallback is allowed only when
``allow_conventional_fallback`` is set, and is then flagged so the apply path refuses on the degraded model.
"""

import json
import shutil
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
