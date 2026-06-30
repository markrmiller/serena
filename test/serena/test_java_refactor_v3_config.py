"""§20: strict typed schema for the ``java_refactor.v3`` configuration sub-tree.

These tests are pure-Python (no sidecar jar): they exercise the ``JavaRefactorV3Config`` dataclass family — its
authoritative per-domain defaults (design §20), unknown-key rejection at every depth, malformed-value rejection,
enum-valued field validation, the non-bypassable safety guards, and the round-trip through the parent
``JavaRefactorConfig.from_dict`` under the ``v3`` key.
"""

from __future__ import annotations

import pytest

from serena.java_refactor.manager import JavaRefactorManager
from serena.config.serena_config import (
    JavaRefactorConfig,
    JavaRefactorV3Config,
    V3ClassRefactorsConfig,
    V3ConversionsConfig,
    V3DeletionConfig,
    V3FrameworksConfig,
    V3InlineConfig,
    V3PackagesConfig,
    V3RecipesConfig,
    V3ResourcesConfig,
    V3TransformationsConfig,
    V3ValidationConfig,
)


def test_defaults_are_authoritative_per_domain() -> None:
    """Every documented §20 default is regression-guarded here, one assertion per domain."""
    config = JavaRefactorV3Config()

    assert config.enabled is True

    assert config.transformations == V3TransformationsConfig(
        max_open_workspaces=8,
        workspace_ttl_minutes=60,
        require_clean_revision_on_apply=True,
        allow_multi_module_edits=True,
    )
    assert config.packages == V3PackagesConfig(
        rename_enabled=True,
        move_enabled=True,
        rewrite_module_info=True,
        rewrite_resources=True,
        rewrite_reflective_strings=False,
    )
    assert config.deletion == V3DeletionConfig(
        propagate_enabled=True,
        public_api_policy="keep",
        max_cascade_depth=5,
        include_tests_default=False,
        delete_empty_packages=True,
    )
    assert config.class_refactors == V3ClassRefactorsConfig(
        extract_class_enabled=True,
        extract_superclass_enabled=True,
        replace_inheritance_with_delegation_enabled=True,
        leave_delegates_default=True,
        allow_public_api_change=False,
    )
    assert config.inline == V3InlineConfig(
        deep_inline_enabled=True,
        max_call_sites=25,
        introduce_temps_for_side_effects=True,
        delete_inlined_method_default=False,
    )
    assert config.conversions == V3ConversionsConfig(
        anonymous_to_lambda_enabled=True,
        lambda_to_method_reference_enabled=True,
    )
    assert config.resources == V3ResourcesConfig(
        enabled=True,
        scan_xml=True,
        scan_properties=True,
        scan_yaml=True,
        scan_json=True,
        scan_service_loader=True,
        auto_apply_confidence="high",
        report_reflection_candidates=True,
    )
    assert config.frameworks == V3FrameworksConfig(
        enabled=True,
        spring="auto",
        jakarta_persistence="auto",
        jackson="auto",
        junit="auto",
    )
    assert config.recipes == V3RecipesConfig(enabled=True, allow_user_recipes=True, builtins_enabled=True)
    assert config.validation == V3ValidationConfig(
        javac_required=True,
        run_build_tool_compile=False,
        run_tests=False,
        max_validation_seconds=120,
    )


def test_none_yields_defaults() -> None:
    assert JavaRefactorV3Config.from_dict(None) == JavaRefactorV3Config()


def test_full_section_20_yaml_round_trips() -> None:
    """The exact §20 YAML block (as a mapping) is accepted and validated end to end."""
    data = {
        "enabled": True,
        "transformations": {
            "max_open_workspaces": 8,
            "workspace_ttl_minutes": 60,
            "require_clean_revision_on_apply": True,
            "allow_multi_module_edits": True,
        },
        "packages": {
            "rename_enabled": True,
            "move_enabled": True,
            "rewrite_module_info": True,
            "rewrite_resources": True,
            "rewrite_reflective_strings": False,
        },
        "deletion": {
            "propagate_enabled": True,
            "public_api_policy": "keep",
            "max_cascade_depth": 5,
            "include_tests_default": False,
            "delete_empty_packages": True,
        },
        "class_refactors": {
            "extract_class_enabled": True,
            "extract_superclass_enabled": True,
            "replace_inheritance_with_delegation_enabled": True,
            "leave_delegates_default": True,
            "allow_public_api_change": False,
        },
        "inline": {
            "deep_inline_enabled": True,
            "max_call_sites": 25,
            "introduce_temps_for_side_effects": True,
            "delete_inlined_method_default": False,
        },
        "conversions": {"anonymous_to_lambda_enabled": True, "lambda_to_method_reference_enabled": True},
        "resources": {
            "enabled": True,
            "scan_xml": True,
            "scan_properties": True,
            "scan_yaml": True,
            "scan_json": True,
            "scan_service_loader": True,
            "auto_apply_confidence": "high",
            "report_reflection_candidates": True,
        },
        "frameworks": {
            "enabled": True,
            "spring": "auto",
            "jakarta_persistence": "auto",
            "jackson": "auto",
            "junit": "auto",
        },
        "recipes": {"enabled": True, "allow_user_recipes": True, "builtins_enabled": True},
        "validation": {
            "javac_required": True,
            "run_build_tool_compile": False,
            "run_tests": False,
            "max_validation_seconds": 120,
        },
    }
    assert JavaRefactorV3Config.from_dict(data) == JavaRefactorV3Config()


def test_unknown_top_level_v3_key_rejected() -> None:
    with pytest.raises(ValueError, match="Unknown java_refactor config key"):
        JavaRefactorV3Config.from_dict({"bogus": 1})


def test_unknown_nested_v3_key_rejected() -> None:
    with pytest.raises(ValueError, match="Unknown deletion config key"):
        JavaRefactorV3Config.from_dict({"deletion": {"bogus_key": 1}})


def test_malformed_bool_rejected() -> None:
    with pytest.raises(ValueError, match="packages.rename_enabled.*expected a boolean"):
        JavaRefactorV3Config.from_dict({"packages": {"rename_enabled": "yes"}})


def test_malformed_positive_int_rejected() -> None:
    with pytest.raises(ValueError, match="inline.max_call_sites.*expected a positive integer"):
        JavaRefactorV3Config.from_dict({"inline": {"max_call_sites": 0}})


def test_public_api_policy_enum_validated() -> None:
    for policy in ("keep", "warn", "allow"):
        config = JavaRefactorV3Config.from_dict({"deletion": {"public_api_policy": policy}})
        assert config.deletion.public_api_policy == policy
    # Legacy "report" is accepted as an alias and normalized to the canonical "warn", matching the analyzer mapping.
    legacy = JavaRefactorV3Config.from_dict({"deletion": {"public_api_policy": "report"}})
    assert legacy.deletion.public_api_policy == "warn"
    with pytest.raises(ValueError, match="deletion.public_api_policy.*keep.*warn.*allow|expected one of"):
        JavaRefactorV3Config.from_dict({"deletion": {"public_api_policy": "delete"}})


def test_auto_apply_confidence_enum_validated() -> None:
    config = JavaRefactorV3Config.from_dict({"resources": {"auto_apply_confidence": "medium"}})
    assert config.resources.auto_apply_confidence == "medium"
    with pytest.raises(ValueError, match="resources.auto_apply_confidence.*expected one of"):
        JavaRefactorV3Config.from_dict({"resources": {"auto_apply_confidence": "always"}})


def test_framework_mode_enum_validated() -> None:
    config = JavaRefactorV3Config.from_dict({"frameworks": {"spring": "off", "junit": "on"}})
    assert config.frameworks.spring == "off"
    assert config.frameworks.junit == "on"
    with pytest.raises(ValueError, match="frameworks.jackson.*expected one of"):
        JavaRefactorV3Config.from_dict({"frameworks": {"jackson": "maybe"}})


def test_clean_revision_guard_is_non_bypassable() -> None:
    with pytest.raises(ValueError, match="require_clean_revision_on_apply.*non-bypassable"):
        JavaRefactorV3Config.from_dict({"transformations": {"require_clean_revision_on_apply": False}})


def test_javac_required_is_non_bypassable() -> None:
    with pytest.raises(ValueError, match="javac_required.*non-bypassable"):
        JavaRefactorV3Config.from_dict({"validation": {"javac_required": False}})


def test_parent_config_round_trips_v3_subtree() -> None:
    """``JavaRefactorConfig.from_dict`` converts and validates the nested ``v3`` mapping like ``v2``."""
    config = JavaRefactorConfig.from_dict(
        {"enabled": True, "v3": {"inline": {"max_call_sites": 10}, "deletion": {"public_api_policy": "allow"}}}
    )
    assert isinstance(config.v3, JavaRefactorV3Config)
    assert config.v3.inline.max_call_sites == 10
    assert config.v3.deletion.public_api_policy == "allow"


def test_parent_config_default_v3_is_authoritative() -> None:
    assert JavaRefactorConfig().v3 == JavaRefactorV3Config()


def test_parent_config_rejects_unknown_nested_v3_key() -> None:
    with pytest.raises(ValueError, match="Unknown resources config key"):
        JavaRefactorConfig.from_dict({"v3": {"resources": {"bogus": True}}})


def test_parent_config_rejects_non_mapping_v3() -> None:
    with pytest.raises(ValueError, match="Invalid v3 value|expected a mapping"):
        JavaRefactorConfig.from_dict({"v3": 5})


def _manager_with_config(config: JavaRefactorConfig) -> JavaRefactorManager:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)
    manager._config = config
    return manager


def test_python_package_operations_honor_v3_config_gates() -> None:
    globally_disabled = _manager_with_config(JavaRefactorConfig.from_dict({"enabled": True, "v3": {"enabled": False}}))
    assert globally_disabled.rename_package("com.acme.old", "com.acme.new")["refusal"]["configPath"] == "java_refactor.v3"
    assert globally_disabled.move_package("com.acme.old", "com.acme.new")["refusal"]["configPath"] == "java_refactor.v3"
    assert globally_disabled.move_source_root("src/main/java", "src/other/java")["refusal"]["configPath"] == "java_refactor.v3"

    rename_disabled = _manager_with_config(
        JavaRefactorConfig.from_dict({"enabled": True, "v3": {"packages": {"rename_enabled": False}}})
    )
    assert rename_disabled.rename_package("com.acme.old", "com.acme.new")["refusal"]["configPath"] == (
        "java_refactor.v3.packages.rename_enabled"
    )

    move_disabled = _manager_with_config(
        JavaRefactorConfig.from_dict({"enabled": True, "v3": {"packages": {"move_enabled": False}}})
    )
    assert move_disabled.move_package("com.acme.old", "com.acme.new")["refusal"]["configPath"] == (
        "java_refactor.v3.packages.move_enabled"
    )
    assert move_disabled.move_source_root("src/main/java", "src/other/java")["refusal"]["configPath"] == (
        "java_refactor.v3.packages.move_enabled"
    )
