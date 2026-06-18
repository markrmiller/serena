"""G006: strict typed schema for the ``java_refactor.v2`` configuration sub-tree.

These tests are pure-Python (no sidecar jar): they exercise the ``JavaRefactorV2Config`` dataclass family — its
authoritative per-domain defaults, unknown-key rejection at every depth, malformed-value rejection, and per-domain
round-trips through ``from_dict``.
"""

from __future__ import annotations

import dataclasses

import pytest

from serena.config.serena_config import (
    JavaRefactorConfig,
    JavaRefactorV2Config,
    LanguageBackend,
    V2AccessConfig,
    V2ChangeSignatureConfig,
    V2DiagnosticsConfig,
    V2EncapsulateFieldConfig,
    V2ExtractInterfaceConfig,
    V2ExtractMethodConfig,
    V2FormattingConfig,
    V2GeneratedSourcesConfig,
    V2HierarchyConfig,
    V2ImportsConfig,
    V2InlineMethodConfig,
    V2IntroduceFieldConfig,
    V2LombokConfig,
    V2MoveMemberConfig,
    V2OperationDefaultsConfig,
    V2SessionsConfig,
    V2StyleConfig,
)
from serena.java_refactor.manager import JavaRefactorManager
from solidlsp.ls_config import Language


def test_defaults_are_authoritative_per_domain() -> None:
    """Every documented default (design §20/§18) is regression-guarded here, one assertion per domain."""
    config = JavaRefactorV2Config()

    assert config.enabled is True

    assert config.sessions.max_open_sessions == 16
    assert config.sessions.session_ttl_minutes == 30
    assert config.sessions.require_revision_match_on_apply is True

    assert config.change_signature.enabled is True
    assert config.change_signature.allow_public_api_change is False
    assert config.change_signature.allow_removed_side_effecting_arguments is False

    assert config.move_member.enabled is True
    assert config.move_member.allow_access_widening is False
    assert config.move_member.leave_delegate_default is True
    assert config.move_member.rewrite_call_sites_default is True

    assert config.hierarchy.enabled is True
    assert config.hierarchy.allow_public_api_change is False

    assert config.extract_method.enabled is True
    assert config.extract_method.allow_multiple_outputs is False
    assert config.extract_method.allow_control_flow_exits is False

    assert config.extract_interface.enabled is True
    assert config.extract_interface.replace_usages_default is False

    assert config.encapsulate_field.enabled is True
    assert config.encapsulate_field.rewrite_internal_usages_default is False
    assert config.encapsulate_field.refuse_compound_assignments is True

    assert config.inline_method.enabled is True
    assert config.inline_method.max_call_sites == 100
    assert config.inline_method.delete_inlined_method_default is False

    assert config.introduce_field.enabled is True

    assert config.formatting.use_external_formatter is False
    assert config.formatting.command is None

    assert config.diagnostics.enabled is True
    assert config.diagnostics.report_delta is True

    assert config.imports.preserve_static_imports is True

    assert config.access.allow_access_widening is False
    assert config.access.allow_security_sensitive_private_widening is False

    assert config.generated_sources.read is True
    assert config.generated_sources.edit is False

    assert config.lombok.enabled is False
    assert config.lombok.allow is False

    assert config.operation_defaults.visibility == "private"

    assert config.style.preserve_line_endings is True


def test_from_dict_none_yields_defaults() -> None:
    assert JavaRefactorV2Config.from_dict(None) == JavaRefactorV2Config()


def test_unknown_top_level_v2_key_rejected() -> None:
    with pytest.raises(ValueError, match=r"Unknown java_refactor config key"):
        JavaRefactorV2Config.from_dict({"bogus": 1})


def test_unknown_nested_v2_key_rejected() -> None:
    with pytest.raises(ValueError, match=r"Unknown sessions config key"):
        JavaRefactorV2Config.from_dict({"sessions": {"bogus_key": 1}})


def test_unknown_key_rejected_via_java_refactor_config_from_dict() -> None:
    # The strict schema is enforced through the full JavaRefactorConfig.from_dict YAML-load path, not only the
    # standalone dataclass.
    with pytest.raises(ValueError, match=r"Unknown change_signature config key"):
        JavaRefactorConfig.from_dict({"v2": {"change_signature": {"typo": True}}})


@pytest.mark.parametrize(
    "data",
    [
        {"sessions": {"max_open_sessions": "bad"}},
        {"sessions": {"max_open_sessions": -1}},
        {"sessions": {"session_ttl_minutes": 0}},
        {"sessions": {"session_ttl_minutes": -5}},
        {"inline_method": {"max_call_sites": "lots"}},
        {"inline_method": {"max_call_sites": 0}},
    ],
)
def test_malformed_numeric_value_rejected(data: dict) -> None:
    with pytest.raises(ValueError):
        JavaRefactorV2Config.from_dict(data)


@pytest.mark.parametrize(
    "data",
    [
        {"enabled": "yes"},
        {"sessions": {"require_revision_match_on_apply": "true"}},
        {"change_signature": {"enabled": 1}},
        {"access": {"allow_access_widening": "false"}},
    ],
)
def test_malformed_boolean_value_rejected(data: dict) -> None:
    with pytest.raises(ValueError):
        JavaRefactorV2Config.from_dict(data)


def test_operation_defaults_visibility_must_be_string() -> None:
    with pytest.raises(ValueError):
        JavaRefactorV2Config.from_dict({"operation_defaults": {"visibility": 3}})


def test_per_domain_round_trip_sessions() -> None:
    config = JavaRefactorV2Config.from_dict(
        {"sessions": {"max_open_sessions": 4, "session_ttl_minutes": 15, "require_revision_match_on_apply": True}}
    )
    assert config.sessions == V2SessionsConfig(
        max_open_sessions=4, session_ttl_minutes=15, require_revision_match_on_apply=True
    )


def test_sessions_require_revision_match_false_rejected() -> None:
    # G003: the apply-time stale-revision guard is non-bypassable; an explicit opt-out is refused at config-load
    # rather than silently accepted as a no-op.
    with pytest.raises(ValueError, match=r"require_revision_match_on_apply:false is not supported"):
        JavaRefactorV2Config.from_dict({"sessions": {"require_revision_match_on_apply": False}})


def test_sessions_require_revision_match_true_accepted() -> None:
    config = JavaRefactorV2Config.from_dict({"sessions": {"require_revision_match_on_apply": True}})
    assert config.sessions.require_revision_match_on_apply is True


def test_extract_method_allow_multiple_outputs_true_rejected() -> None:
    # G006: multi-output extraction is V3 scope and hardwired off in V2; enabling it via config is refused rather than
    # silently ignored (which would falsely imply the opt-in works).
    with pytest.raises(ValueError, match=r"allow_multiple_outputs:true is not supported"):
        JavaRefactorV2Config.from_dict({"extract_method": {"allow_multiple_outputs": True}})


def test_extract_method_allow_control_flow_exits_true_rejected() -> None:
    # G006: control-flow-exit extraction is V3 scope and hardwired off in V2; enabling it via config is refused.
    with pytest.raises(ValueError, match=r"allow_control_flow_exits:true is not supported"):
        JavaRefactorV2Config.from_dict({"extract_method": {"allow_control_flow_exits": True}})


def test_extract_method_opt_ins_false_accepted() -> None:
    config = JavaRefactorV2Config.from_dict(
        {"extract_method": {"allow_multiple_outputs": False, "allow_control_flow_exits": False}}
    )
    assert config.extract_method.allow_multiple_outputs is False
    assert config.extract_method.allow_control_flow_exits is False


def test_per_domain_round_trip_change_signature() -> None:
    config = JavaRefactorV2Config.from_dict(
        {
            "change_signature": {
                "enabled": False,
                "allow_public_api_change": True,
                "allow_removed_side_effecting_arguments": True,
            }
        }
    )
    assert config.change_signature == V2ChangeSignatureConfig(
        enabled=False,
        allow_public_api_change=True,
        allow_removed_side_effecting_arguments=True,
    )


def test_per_domain_round_trip_move_member() -> None:
    config = JavaRefactorV2Config.from_dict(
        {
            "move_member": {
                "enabled": False,
                "allow_access_widening": True,
                "leave_delegate_default": False,
                "rewrite_call_sites_default": False,
            }
        }
    )
    assert config.move_member == V2MoveMemberConfig(
        enabled=False, allow_access_widening=True, leave_delegate_default=False, rewrite_call_sites_default=False
    )


def test_per_domain_round_trip_hierarchy() -> None:
    config = JavaRefactorV2Config.from_dict({"hierarchy": {"enabled": False, "allow_public_api_change": True}})
    assert config.hierarchy == V2HierarchyConfig(enabled=False, allow_public_api_change=True)


def test_per_domain_round_trip_extract_method() -> None:
    # G006: allow_multiple_outputs / allow_control_flow_exits are V3 scope and cannot be enabled in V2 (see the
    # dedicated rejection tests above); the round-trippable V2 surface is `enabled` plus the opt-ins pinned to false.
    config = JavaRefactorV2Config.from_dict(
        {"extract_method": {"enabled": False, "allow_multiple_outputs": False, "allow_control_flow_exits": False}}
    )
    assert config.extract_method == V2ExtractMethodConfig(
        enabled=False, allow_multiple_outputs=False, allow_control_flow_exits=False
    )


def test_per_domain_round_trip_extract_interface() -> None:
    config = JavaRefactorV2Config.from_dict({"extract_interface": {"enabled": False, "replace_usages_default": True}})
    assert config.extract_interface == V2ExtractInterfaceConfig(enabled=False, replace_usages_default=True)


def test_per_domain_round_trip_encapsulate_field() -> None:
    config = JavaRefactorV2Config.from_dict(
        {
            "encapsulate_field": {
                "enabled": False,
                "rewrite_internal_usages_default": True,
                "refuse_compound_assignments": False,
            }
        }
    )
    assert config.encapsulate_field == V2EncapsulateFieldConfig(
        enabled=False, rewrite_internal_usages_default=True, refuse_compound_assignments=False
    )


def test_per_domain_round_trip_inline_method() -> None:
    config = JavaRefactorV2Config.from_dict(
        {"inline_method": {"enabled": False, "max_call_sites": 7, "delete_inlined_method_default": True}}
    )
    assert config.inline_method == V2InlineMethodConfig(
        enabled=False, max_call_sites=7, delete_inlined_method_default=True
    )


def test_per_domain_round_trip_introduce_field() -> None:
    config = JavaRefactorV2Config.from_dict({"introduce_field": {"enabled": False}})
    assert config.introduce_field == V2IntroduceFieldConfig(enabled=False)


def test_unknown_introduce_field_key_rejected() -> None:
    with pytest.raises(ValueError, match=r"Unknown introduce_field config key"):
        JavaRefactorV2Config.from_dict({"introduce_field": {"bogus": True}})


def test_introduce_field_wrong_type_rejected() -> None:
    with pytest.raises(ValueError):
        JavaRefactorV2Config.from_dict({"introduce_field": {"enabled": "yes"}})


def test_per_domain_round_trip_formatting() -> None:
    config = JavaRefactorV2Config.from_dict(
        {"formatting": {"use_external_formatter": True, "command": "google-java-format"}}
    )
    assert config.formatting == V2FormattingConfig(use_external_formatter=True, command="google-java-format")


def test_formatting_command_accepts_none() -> None:
    config = JavaRefactorV2Config.from_dict({"formatting": {"command": None}})
    assert config.formatting.command is None


def test_formatting_command_accepts_string() -> None:
    config = JavaRefactorV2Config.from_dict({"formatting": {"command": "fmt"}})
    assert config.formatting.command == "fmt"


@pytest.mark.parametrize("bad_command", [5, 1.2, True, ["fmt"], {"k": "v"}])
def test_formatting_command_rejects_non_string(bad_command: object) -> None:
    with pytest.raises(ValueError, match=r"expected a string or null"):
        JavaRefactorV2Config.from_dict({"formatting": {"command": bad_command}})


def test_formatting_use_external_formatter_must_be_bool() -> None:
    with pytest.raises(ValueError):
        JavaRefactorV2Config.from_dict({"formatting": {"use_external_formatter": "true"}})


def test_unknown_formatting_key_rejected() -> None:
    with pytest.raises(ValueError, match=r"Unknown formatting config key"):
        JavaRefactorV2Config.from_dict({"formatting": {"bogus": True}})


def test_per_domain_round_trip_diagnostics() -> None:
    config = JavaRefactorV2Config.from_dict({"diagnostics": {"enabled": True, "report_delta": True}})
    assert config.diagnostics == V2DiagnosticsConfig(enabled=True, report_delta=True)


def test_diagnostics_enabled_false_rejected() -> None:
    # G006: diagnostics are always collected; the opt-out cannot affect the sidecar so it is refused at config-load.
    with pytest.raises(ValueError, match=r"diagnostics.enabled:false is not supported"):
        JavaRefactorV2Config.from_dict({"diagnostics": {"enabled": False}})


def test_diagnostics_report_delta_false_rejected() -> None:
    with pytest.raises(ValueError, match=r"diagnostics.report_delta:false is not supported"):
        JavaRefactorV2Config.from_dict({"diagnostics": {"report_delta": False}})


def test_per_domain_round_trip_imports() -> None:
    config = JavaRefactorV2Config.from_dict({"imports": {"preserve_static_imports": True}})
    assert config.imports == V2ImportsConfig(preserve_static_imports=True)


def test_imports_preserve_static_imports_false_rejected() -> None:
    # G006: static imports are always preserved; the opt-out is a no-op, so it is refused.
    with pytest.raises(ValueError, match=r"imports.preserve_static_imports:false is not supported"):
        JavaRefactorV2Config.from_dict({"imports": {"preserve_static_imports": False}})


def test_per_domain_round_trip_access() -> None:
    config = JavaRefactorV2Config.from_dict(
        {"access": {"allow_access_widening": True, "allow_security_sensitive_private_widening": True}}
    )
    assert config.access == V2AccessConfig(
        allow_access_widening=True, allow_security_sensitive_private_widening=True
    )


def test_per_domain_round_trip_generated_sources() -> None:
    config = JavaRefactorV2Config.from_dict({"generated_sources": {"read": False, "edit": True}})
    assert config.generated_sources == V2GeneratedSourcesConfig(read=False, edit=True)


def test_per_domain_round_trip_lombok() -> None:
    config = JavaRefactorV2Config.from_dict({"lombok": {"allow": True}})
    assert config.lombok == V2LombokConfig(enabled=False, allow=True)


def test_lombok_enabled_true_rejected() -> None:
    # G006: lombok.enabled is read by nothing in the sidecar (Lombok is driven by lombok.jar/lombok.classpath + allow);
    # the value that reads as "turn lombok on" is refused so it cannot silently no-op.
    with pytest.raises(ValueError, match=r"lombok.enabled:true has no effect"):
        JavaRefactorV2Config.from_dict({"lombok": {"enabled": True, "allow": True}})


def test_lombok_jar_and_classpath_round_trip() -> None:
    # Blocker 7: the typed V2 config now exposes lombok.jar / lombok.classpath so the Lombok annotation-processor path
    # can be configured end-to-end (instead of only the no-op lombok.enabled / lombok.allow flags).
    config = JavaRefactorV2Config.from_dict(
        {"lombok": {"allow": True, "jar": "libs/lombok.jar", "classpath": ["libs/lombok.jar", "libs/extra.jar"]}}
    )
    assert config.lombok == V2LombokConfig(
        enabled=False, allow=True, jar="libs/lombok.jar", classpath=["libs/lombok.jar", "libs/extra.jar"]
    )


def test_lombok_classpath_rejects_non_string_list() -> None:
    # Blocker 7: lombok.classpath is validated as a list of strings, so a malformed value is rejected at config-load
    # rather than silently passed through to the sidecar.
    with pytest.raises(ValueError, match=r"lombok.classpath"):
        JavaRefactorV2Config.from_dict({"lombok": {"classpath": [1, 2]}})


def test_lombok_jar_classpath_mapped_to_sidecar_discovery_keys(tmp_path) -> None:
    # Blocker 7: the typed lombok.jar / lombok.classpath are mapped onto the sidecar's flat discovery keys
    # (java_refactor.v2.lombokJar / lombokClasspath) that expandNestedV2Config copies into the build-model config, so
    # Lombok actually lands on the classpath + annotation-processor path. This is the end-to-end wiring.
    config = JavaRefactorConfig(
        enabled=True,
        v2=JavaRefactorV2Config(lombok=V2LombokConfig(allow=True, jar="libs/lombok.jar", classpath=["libs/lombok.jar"])),
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=config
    )
    v2_payload = manager._sidecar_config_dict()["java_refactor"]["v2"]
    assert v2_payload["lombokJar"] == "libs/lombok.jar"
    assert v2_payload["lombokClasspath"] == ["libs/lombok.jar"]
    # The nested typed block is preserved too (the sidecar reads `allow` from it).
    assert v2_payload["lombok"]["allow"] is True


def test_per_domain_round_trip_operation_defaults() -> None:
    config = JavaRefactorV2Config.from_dict({"operation_defaults": {"visibility": "public"}})
    assert config.operation_defaults == V2OperationDefaultsConfig(visibility="public")


def test_per_domain_round_trip_style() -> None:
    config = JavaRefactorV2Config.from_dict({"style": {"preserve_line_endings": True}})
    assert config.style == V2StyleConfig(preserve_line_endings=True)


def test_style_preserve_line_endings_false_rejected() -> None:
    # G006: the source's inferred line ending is always preserved; the opt-out cannot affect the sidecar so it is
    # refused at config-load.
    with pytest.raises(ValueError, match=r"style.preserve_line_endings:false is not supported"):
        JavaRefactorV2Config.from_dict({"style": {"preserve_line_endings": False}})


def test_style_defaults_when_absent() -> None:
    # A V2 config carrying no style block keeps the documented default; the sidecar forwards `style` either way.
    assert JavaRefactorV2Config.from_dict({}).style == V2StyleConfig()


def test_unknown_style_key_rejected() -> None:
    with pytest.raises(ValueError, match=r"Unknown style config key"):
        JavaRefactorV2Config.from_dict({"style": {"bogus_style_key": True}})


def test_asdict_round_trips_back_through_from_dict() -> None:
    # The wire serialization (dataclasses.asdict) must be re-loadable by the strict schema unchanged.
    original = JavaRefactorV2Config.from_dict(
        {"sessions": {"max_open_sessions": 8}, "inline_method": {"max_call_sites": 42}}
    )
    serialized = dataclasses.asdict(original)
    assert JavaRefactorV2Config.from_dict(serialized) == original
