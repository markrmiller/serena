from __future__ import annotations

import re
from pathlib import Path

import pytest

from serena.config.serena_config import JavaRefactorV3Config, V3GraphConfig
from serena.java_refactor.manager import JavaRefactorManager


ROOT = Path(__file__).resolve().parents[2]


def test_v3_graph_config_is_strict_nested_dataclass() -> None:
    cfg = JavaRefactorV3Config.from_dict(
        {"graph": {"max_graph_cache_entries": 2, "max_resource_file_bytes": 4096}}
    )

    assert isinstance(cfg.graph, V3GraphConfig)
    assert cfg.graph.max_graph_cache_entries == 2
    assert cfg.graph.max_resource_file_bytes == 4096

    with pytest.raises(ValueError, match="Unknown graph config key"):
        JavaRefactorV3Config.from_dict({"graph": {"unknown": 1}})
    with pytest.raises(ValueError, match="graph.max_resource_file_bytes"):
        JavaRefactorV3Config.from_dict({"graph": {"max_resource_file_bytes": -1}})
    with pytest.raises(ValueError, match="graph.max_graph_cache_entries"):
        JavaRefactorV3Config.from_dict({"graph": {"max_graph_cache_entries": "2"}})


def test_recipe_apply_manager_rejects_missing_authoritative_groups() -> None:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)

    result = manager._surface_recipe_apply_presentation(
        {"accepted": True, "operation": "applyRecipe", "workspaceEdit": {}, "warnings": []}
    )

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "recipe_apply_contract_violation"


def test_recipe_apply_manager_preserves_authoritative_groups() -> None:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)
    result = {"accepted": True, "operation": "applyRecipe", "matches": [], "groups": {}}

    assert manager._surface_recipe_apply_presentation(result)["accepted"] is True


def test_documented_builtin_recipe_ids_exist() -> None:
    builtin = (ROOT / "java-refactor/src/main/java/io/serena/javarefactor/v3/recipes/BuiltinRecipes.java").read_text()
    ids = set(re.findall(r'recipe\("([^"]+)"', builtin))
    docs = (ROOT / "docs/java-refactor-v3.md").read_text()
    tool_docs = (ROOT / "src/serena/tools/java_refactor_v3_tools.py").read_text()
    mentioned = set(re.findall(r'load_builtin_recipe\("([^"]+)"', docs + "\n" + tool_docs))

    assert mentioned
    assert mentioned <= ids


def test_v3_docs_examples_use_public_dict_api() -> None:
    docs = (ROOT / "docs/java-refactor-v3.md").read_text()

    assert 'client._request("impact.facts"' not in docs
    assert "plan.workspace_edit" not in docs
    assert "ImpactFactsClient(client).facts" in docs


def test_transformation_workspace_routes_recipe_and_resource_steps() -> None:
    text = (ROOT / "java-refactor/src/main/java/io/serena/javarefactor/protocol/Main.java").read_text()

    assert 'case "applyRefactorRecipe", "recipes.applyRecipe" -> workspaceEditStep(operation, applyRecipeJson(arguments));' in text
    assert 'case "planResourceEdits", "resources.planEdits" -> workspaceEditStep(operation, planResourceEditsJson(arguments));' in text


def test_rename_package_capability_advertises_subpackage_default() -> None:
    text = (ROOT / "java-refactor/src/main/java/io/serena/javarefactor/protocol/Main.java").read_text()

    assert "by default includes subpackages" in text
    assert "subpackages are not renamed" not in text


def test_read_only_tool_docs_do_not_claim_no_javac() -> None:
    text = (ROOT / "src/serena/tools/java_refactor_v3_tools.py").read_text()

    assert "runs no javac" not in text
    assert "no javac — there is no edit to validate" not in text


def test_transformation_add_session_uses_json_rpc_response_envelope() -> None:
    text = (ROOT / "java-refactor/src/main/java/io/serena/javarefactor/protocol/Main.java").read_text()
    assert 'case "transformation.addSession":' in text
    assert "return response(id, CanonicalEnvelope.augment(transformationAddSessionJson(fields)));" in text


def test_workspace_step_parses_canonical_changes_array_and_resource_plan_emits_workspace_edit() -> None:
    main = (ROOT / "java-refactor/src/main/java/io/serena/javarefactor/protocol/Main.java").read_text()
    resource = (ROOT / "java-refactor/src/main/java/io/serena/javarefactor/v3/resources/ResourceEditPlanner.java").read_text()
    assert "changesObj instanceof java.util.List<?> changeGroups" in main
    assert "addWorkspaceTextEdits(edits, file, editList)" in main
    assert '\\"workspaceEdit\\":' in resource
    assert "PlannerSupport.changesJson(projectRoot, textEdits)" in resource
    assert "CanonicalEnvelope.augment(planResourceEditsJson(fields))" in main
