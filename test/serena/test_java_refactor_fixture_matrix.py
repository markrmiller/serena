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
from test.serena._java_refactor_sidecar_helpers import (
    file_ops,
    run_status,
    text_edits,
    write_maven_offline_project,
)

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)

FIXTURE_ROOT = Path("test/resources/repos/java_refactor")
V2_FIXTURE_ROOT = Path("test/resources/repos/java_refactor_v2")

# The exact fixture set the V1 plan requires; the committed tree must stay in lockstep with this matrix.
REQUIRED_FIXTURES = {
    "plain",
    "maven-basic",
    "gradle-basic",
    "multi-module-maven",
    "multi-source-set-gradle",
    "modules",
    "lombok-lite",
    "generated-code",
}

REQUIRED_V2_FIXTURES = {
    "change-signature",
    "hierarchy",
    "move-members",
    "extract-method",
    "extract-interface",
    "field-refactors",
    "inline-method",
    "generated-source",
    "maven-basic",
    "gradle-basic",
    # §21.1 design-spec names — added without removing existing names (ADD-don't-rename rule)
    "change-signature-basic",
    "change-signature-hierarchy",
    "move-static-member",
    "move-instance-method",
    "pull-up-push-down",
    "encapsulate-field",
    "multi-module-gradle",
    "multi-module-maven",
    "mixed-generated-sources",
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


def _selection_for(source: str, selected: str) -> dict:
    start = source.index(selected)
    end = start + len(selected)

    def line_column(offset: int) -> tuple[int, int]:
        prefix = source[:offset]
        return prefix.count("\n") + 1, offset - prefix.rfind("\n")

    start_line, start_column = line_column(start)
    end_line, end_column = line_column(end)
    return {"startLine": start_line, "startColumn": start_column, "endLine": end_line, "endColumn": end_column}


def test_fixture_matrix_is_complete() -> None:
    assert {path.name for path in FIXTURE_ROOT.iterdir() if path.is_dir()} == REQUIRED_FIXTURES


def test_v2_fixture_matrix_is_complete() -> None:
    assert {path.name for path in V2_FIXTURE_ROOT.iterdir() if path.is_dir()} == REQUIRED_V2_FIXTURES


def test_change_signature_fixture_introduces_parameter_from_ast_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    root = tmp_path / "change-signature"
    shutil.copytree(V2_FIXTURE_ROOT / "change-signature", root)

    result = _preview(
        sidecar_jar,
        root,
        "introduceParameter",
        {
            "relativePath": "src/main/java/demo/ChangeSignatureSample.java",
            "line": 4,
            "selection": {"startLine": 5, "startColumn": 16, "endLine": 5, "endColumn": 24},
            "parameterName": "prefix",
            "confirmPublicApi": True,
        },
    )

    assert result["accepted"] is True, result
    changes = {change["path"]: change["edits"] for change in result["workspaceEdit"]["changes"]}
    sample_edits = changes["src/main/java/demo/ChangeSignatureSample.java"]

    assert any(
        edit["kind"] == "CHANGE_SIGNATURE_DECLARATION"
        and "greet(String name, String prefix)" in edit["newText"]
        for edit in sample_edits
    )
    assert any(
        edit["kind"] == "INTRODUCE_PARAMETER_BODY" and edit["newText"] == "prefix"
        for edit in sample_edits
    )
    assert any(
        edit["kind"] == "CHANGE_SIGNATURE_CALL" and edit["newText"] == 'greet("Serena", "Hello ")'
        for edit in sample_edits
    )
    assert any(
        edit["kind"] == "CHANGE_SIGNATURE_CALL" and edit["newText"] == 'greet("Remote", "Hello ")'
        for edit in changes["src/main/java/demo/OtherCaller.java"]
    )




def test_extract_method_v2_synthesizes_input_output_and_inserts_after_current_method(sidecar_jar: Path, tmp_path: Path) -> None:
    root = tmp_path / "extract-method"
    shutil.copytree(V2_FIXTURE_ROOT / "extract-method", root)
    relative = "src/main/java/demo/ExtractMethodSample.java"
    source = (root / relative).read_text(encoding="utf-8")
    selected = "int total = base + field;"

    result = _preview(
        sidecar_jar,
        root,
        "extractMethod",
        {
            "relativePath": relative,
            "newMethodName": "calculateTotal",
            "selection": _selection_for(source, selected),
        },
    )

    assert result["accepted"] is True, result
    edits = {change["path"]: change["edits"] for change in result["workspaceEdit"]["changes"]}[relative]
    call = next(edit for edit in edits if edit["kind"] == "EXTRACT_METHOD_CALL")
    declaration = next(edit for edit in edits if edit["kind"] == "EXTRACT_METHOD_DECLARATION")

    assert call["newText"] == "int total = calculateTotal(base);"
    assert "private int calculateTotal(int base)" in declaration["newText"]
    assert "return base + field;" in declaration["newText"]
    assert "static" not in declaration["newText"].split("calculateTotal", 1)[0]


def test_extract_method_v2_extracts_static_expression_with_parameters(sidecar_jar: Path, tmp_path: Path) -> None:
    root = tmp_path / "extract-method"
    shutil.copytree(V2_FIXTURE_ROOT / "extract-method", root)
    relative = "src/main/java/demo/ExtractMethodSample.java"
    source = (root / relative).read_text(encoding="utf-8")
    selected = "left + right"

    result = _preview(
        sidecar_jar,
        root,
        "extractMethod",
        {
            "relativePath": relative,
            "newMethodName": "sumInputs",
            "selection": _selection_for(source, selected),
        },
    )

    assert result["accepted"] is True, result
    edits = {change["path"]: change["edits"] for change in result["workspaceEdit"]["changes"]}[relative]
    call = next(edit for edit in edits if edit["kind"] == "EXTRACT_METHOD_CALL")
    declaration = next(edit for edit in edits if edit["kind"] == "EXTRACT_METHOD_DECLARATION")

    assert call["newText"] == "sumInputs(left, right)"
    assert "private static int sumInputs(int left, int right)" in declaration["newText"]
    assert "return left + right;" in declaration["newText"]


def test_extract_method_v2_infers_checked_exception_throws_clause(sidecar_jar: Path, tmp_path: Path) -> None:
    root = tmp_path / "extract-method"
    shutil.copytree(V2_FIXTURE_ROOT / "extract-method", root)
    relative = "src/main/java/demo/ExtractMethodSample.java"
    source = (root / relative).read_text(encoding="utf-8")
    selected = 'Files.readString(Path.of("demo.txt"));'

    result = _preview(
        sidecar_jar,
        root,
        "extractMethod",
        {
            "relativePath": relative,
            "newMethodName": "readDemo",
            "selection": _selection_for(source, selected),
        },
    )

    assert result["accepted"] is True, result
    edits = {change["path"]: change["edits"] for change in result["workspaceEdit"]["changes"]}[relative]
    declaration = next(edit for edit in edits if edit["kind"] == "EXTRACT_METHOD_DECLARATION")

    assert "private void readDemo() throws java.io.IOException" in declaration["newText"]


def test_extract_method_v2_refuses_partial_statement_with_suggested_range(sidecar_jar: Path, tmp_path: Path) -> None:
    root = tmp_path / "extract-method"
    shutil.copytree(V2_FIXTURE_ROOT / "extract-method", root)
    relative = "src/main/java/demo/ExtractMethodSample.java"
    source = (root / relative).read_text(encoding="utf-8")
    selected = "total = base + field"

    result = _preview(
        sidecar_jar,
        root,
        "extractMethod",
        {
            "relativePath": relative,
            "newMethodName": "calculateTotal",
            "selection": _selection_for(source, selected),
        },
    )

    assert result["accepted"] is False, result
    # Canonical partial-statement refusal code (refactor-feature-plan-V2.md §10.2); a partial
    # selection that cuts through a statement is refused with an expanded suggested range.
    assert result["refusal"]["code"] == "SELECTION_NOT_STATEMENT_ALIGNED"
    assert "suggested range:" in result["refusal"]["message"]
    assert len(result["suggestedRanges"]) == 1
    suggested = result["suggestedRanges"][0]
    assert set(suggested) == {"startLine", "startColumn", "endLine", "endColumn"}


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


def test_generated_code_fixture_refuses_v2_session_edits_without_opt_in(sidecar_jar: Path, tmp_path: Path) -> None:
    root = _copy_fixture("generated-code", tmp_path)
    relative = "generated/GeneratedSample.java"

    status = run_status(sidecar_jar, root)
    assert status["ready"] is True, status["errors"]

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(root), configuration="default"))
        refused = client.create_session(
            "extractMethod",
            {
                "relativePath": relative,
                "newMethodName": "printGenerated",
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 41},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "generated_source_refused"


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
        sidecar_jar, root, "semanticRename", {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": 14, "newName": "Core"}
    )
    assert result["accepted"] is True, result
    touched = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert "src/main/java/demo/Main.java" in touched
    assert "src/test/java/demo/MainTest.java" in touched, touched
