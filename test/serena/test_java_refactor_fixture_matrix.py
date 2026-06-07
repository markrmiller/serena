"""V1 fixture matrix (refactor-feature-plan.md §15 "Test matrix").

Every committed fixture repository under ``test/resources/repos/java_refactor/`` is exercised here against the REAL
sidecar: each fixture's project model must discover with its intended build kind, and the non-build-tool fixtures are
additionally driven through a real refactoring preview. Fixtures are always copied into ``tmp_path`` first so neither
extraction nor sidecar caches can mutate the committed tree.

Build-tool-dependent fixtures honor the suite's established environment guards: Maven fixtures reuse the warmed
offline repository (skipped when ``mvn`` is unavailable) and Gradle fixtures skip when ``gradle`` is unavailable.
"""

import shutil
from pathlib import Path

import pytest

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from test.serena._java_refactor_sidecar_helpers import (  # noqa: F401
    file_ops,
    maven_offline_config,
    maven_offline_repo,
    run_status,
    sidecar_jar,
    text_edits,
    write_maven_offline_project,
)

FIXTURE_ROOT = Path("test/resources/repos/java_refactor")

# The exact fixture set the V1 plan requires; the committed tree must stay in lockstep with this matrix.
REQUIRED_FIXTURES = {
    "plain",
    "maven-basic",
    "gradle-basic",
    "multi-module-maven",
    "multi-source-set-gradle",
    "modules",
    "lombok-lite",
}


def _copy_fixture(name: str, tmp_path: Path) -> Path:
    target = tmp_path / name
    shutil.copytree(FIXTURE_ROOT / name, target)
    return target


def _preview(sidecar_jar_path: Path, project_root: Path, operation: str, params: dict, configuration: str = "default") -> dict:
    client = JavaRefactorClient(sidecar_jar_path)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        return client.preview(operation, params)
    finally:
        client.shutdown()


def test_fixture_matrix_is_complete() -> None:
    assert {path.name for path in FIXTURE_ROOT.iterdir() if path.is_dir()} == REQUIRED_FIXTURES


def test_plain_fixture_discovers_and_renames_with_file_operation(sidecar_jar: Path, tmp_path: Path) -> None:
    root = _copy_fixture("plain", tmp_path)

    status = run_status(sidecar_jar, root)
    assert status["ready"] is True, status["errors"]
    assert status["project_model"]["discoveryKind"] == "plain"
    assert status["project_model"]["javaFileCount"] == 1

    # Renaming the top-level type whose file matches its name must plan the file rename operation too.
    result = _preview(sidecar_jar, root, "semanticRename", {"relativePath": "Hello.java", "line": 1, "column": 7, "newName": "Greeting"})
    assert result["accepted"] is True, result
    operations = file_ops(result["workspaceEdit"])
    assert operations[0]["kind"] == "rename"
    assert operations[0]["newRelativePath"] == "Greeting.java"


def test_plain_fixture_safe_deletes_unused_private_method(sidecar_jar: Path, tmp_path: Path) -> None:
    root = _copy_fixture("plain", tmp_path)
    source = (root / "Hello.java").read_text(encoding="utf-8")

    column = source.index("helper") + 1
    result = _preview(sidecar_jar, root, "safeDelete", {"relativePath": "Hello.java", "line": 1, "column": column})

    assert result["accepted"] is True, result
    assert result["workspaceEdit"]["stats"]["editCount"] == 1


def test_modules_fixture_discovers_modular_model_and_renames(sidecar_jar: Path, tmp_path: Path) -> None:
    root = _copy_fixture("modules", tmp_path)

    status = run_status(sidecar_jar, root)
    assert status["ready"] is True, status["errors"]
    assert status["project_model"]["modular"] == "true" or status["project_model"]["modular"] is True

    result = _preview(
        sidecar_jar, root, "semanticRename", {"relativePath": "src/main/java/demo/Mod.java", "line": 1, "column": 28, "newName": "Renamed"}
    )
    assert result["accepted"] is True, result
    assert file_ops(result["workspaceEdit"])[0]["newRelativePath"].endswith("Renamed.java")


def test_lombok_lite_fixture_renames_field_and_constructor_usage(sidecar_jar: Path, tmp_path: Path) -> None:
    root = _copy_fixture("lombok-lite", tmp_path)
    relative = "src/main/java/demo/LombokLite.java"
    source = (root / relative).read_text(encoding="utf-8")

    status = run_status(sidecar_jar, root)
    assert status["ready"] is True, status["errors"]

    column = source.index("name;") + 1
    result = _preview(sidecar_jar, root, "semanticRename", {"relativePath": relative, "line": 1, "column": column, "newName": "label"})
    assert result["accepted"] is True, result
    # Declaration plus the `this.name` constructor write must both be rewritten.
    assert result["workspaceEdit"]["stats"]["editCount"] >= 2, result["workspaceEdit"]


def test_maven_basic_fixture_extracts_model(sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path) -> None:
    root = _copy_fixture("maven-basic", tmp_path)
    write_maven_offline_project(root, maven_offline_repo)

    status = run_status(sidecar_jar, root, configuration=maven_offline_config)

    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    assert model["discoveryKind"] == "maven"
    assert any("demo/App.java" in "".join(source_set["javaFiles"]) for source_set in model["sourceSets"])


def test_multi_module_maven_fixture_renames_in_module(
    sidecar_jar: Path, tmp_path: Path, maven_offline_config: str, maven_offline_repo: Path
) -> None:
    # Model extraction for this fixture is covered by test_sidecar_extracts_multi_module; here the matrix additionally
    # proves a real operation plans inside a reactor module.
    root = _copy_fixture("multi-module-maven", tmp_path)
    write_maven_offline_project(root, maven_offline_repo)

    result = _preview(
        sidecar_jar,
        root,
        "semanticRename",
        {"relativePath": "a/src/main/java/demo/A.java", "line": 1, "column": 28, "newName": "Renamed"},
        configuration=maven_offline_config,
    )

    assert result["accepted"] is True, result
    assert file_ops(result["workspaceEdit"])[0]["newRelativePath"].endswith("Renamed.java")


def test_gradle_basic_fixture_extracts_model_and_renames(sidecar_jar: Path, tmp_path: Path) -> None:
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    root = _copy_fixture("gradle-basic", tmp_path)

    status = run_status(sidecar_jar, root)
    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    assert model["discoveryKind"] == "gradle"

    result = _preview(
        sidecar_jar, root, "semanticRename", {"relativePath": "src/main/java/demo/App.java", "line": 1, "column": 28, "newName": "Renamed"}
    )
    assert result["accepted"] is True, result


def test_multi_source_set_gradle_fixture_renames_across_source_sets(sidecar_jar: Path, tmp_path: Path) -> None:
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    root = _copy_fixture("multi-source-set-gradle", tmp_path)

    status = run_status(sidecar_jar, root)
    model = status["project_model"]
    assert status["ready"] is True, model["errors"]
    assert model["discoveryKind"] == "gradle"
    assert len(model["sourceSets"]) >= 2, model["sourceSets"]

    # Renaming Main must rewrite its references in the TEST source set as well (cross-source-set rename).
    result = _preview(
        sidecar_jar, root, "semanticRename", {"relativePath": "src/main/java/demo/Main.java", "line": 1, "column": 28, "newName": "Core"}
    )
    assert result["accepted"] is True, result
    touched = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert "src/main/java/demo/Main.java" in touched
    assert "src/test/java/demo/MainTest.java" in touched, touched
