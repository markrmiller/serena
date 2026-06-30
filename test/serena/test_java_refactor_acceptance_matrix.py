"""Executable Java refactor acceptance matrix (V1 plan plus V2 session goals).

Every acceptance row of the design's test matrix maps here to at least one named test, and the map is verified
against the test files' ASTs: deleting or renaming a mapped test fails this suite, so the matrix cannot silently rot.
A second check ties the map to CI: every file referenced below must actually be executed by the dedicated
java-refactor workflow (either via its ``test/serena/test_java_refactor*.py`` glob or by an explicit path), so a
blocking acceptance test cannot live outside the dedicated CI path unnoticed.

Files are parsed with ``ast`` rather than imported so that mapping verification has no import side effects and can
reference test modules that are not importable as packages (``test/solidlsp/java`` has no ``__init__.py``).
"""

import ast
import fnmatch
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

FIXTURES = "test/serena/test_java_refactor_fixture_matrix.py"
RENAME = "test/serena/test_java_refactor_sidecar_rename.py"
SAFE_DELETE = "test/serena/test_java_refactor_sidecar_safe_delete.py"
MOVE = "test/serena/test_java_refactor_sidecar_move.py"
INLINE = "test/serena/test_java_refactor_sidecar_inline.py"
MODEL = "test/serena/test_java_refactor_sidecar_model.py"
BUILD_MODEL = "test/serena/test_java_refactor_sidecar_build_model.py"
INTEGRATION = "test/serena/test_java_refactor.py"
TARGET_HINTS = "test/serena/test_java_refactor_sidecar_target_hints.py"
WORKSPACE_EDIT = "test/serena/test_workspace_edit.py"
SESSIONS = "test/serena/test_java_refactor_sidecar_sessions.py"
IMPORTS = "test/serena/test_java_refactor_imports.py"
ACCEPTANCE = "test/serena/test_java_refactor_acceptance_matrix.py"
JDTLS = "test/solidlsp/java/test_java_refactor_jdtls_integration.py"
HARDBLOCKERS = "test/serena/test_java_refactor_hardblockers.py"
G010 = "test/serena/test_java_refactor_g010_merge_proofs.py"

WORKFLOW = ".github/workflows/java-refactor.yml"
WORKFLOW_GLOB = "test/serena/test_java_refactor*.py"

# Plan section 15 rows (plus the section 16 safety rules) -> the explicit test(s) that exercise each row.
ACCEPTANCE_MATRIX: dict[str, dict[str, list[tuple[str, str]]]] = {
    "Java fixtures": {
        "plain": [
            (FIXTURES, "test_plain_fixture_discovers_and_renames_with_file_operation"),
            (FIXTURES, "test_plain_fixture_safe_deletes_unused_private_method"),
        ],
        "maven-basic": [(FIXTURES, "test_maven_basic_fixture_extracts_model")],
        "gradle-basic": [(FIXTURES, "test_gradle_basic_fixture_extracts_model_and_renames")],
        "multi-module-maven": [(FIXTURES, "test_multi_module_maven_fixture_renames_in_module")],
        "multi-source-set-gradle": [(FIXTURES, "test_multi_source_set_gradle_fixture_renames_across_source_sets")],
        "modules": [(FIXTURES, "test_modules_fixture_discovers_modular_model_and_renames")],
        "lombok-lite": [(FIXTURES, "test_lombok_lite_fixture_renames_field_and_constructor_usage")],
        "generated-code": [(FIXTURES, "test_generated_code_fixture_refuses_v2_session_edits_without_opt_in")],
        "fixture set is complete": [(FIXTURES, "test_fixture_matrix_is_complete")],
        "V2 fixture set is complete": [(FIXTURES, "test_v2_fixture_matrix_is_complete")],
    },
    "Rename cases": {
        "local variable shadowing": [(RENAME, "test_sidecar_rename_local_variable_shadowing_renames_only_inner_scope")],
        "parameter vs field same name": [(RENAME, "test_sidecar_rename_parameter_sharing_field_name_renames_only_parameter")],
        "overloaded methods": [
            (RENAME, "test_sidecar_rename_refuses_same_arity_overload_ambiguity"),
            (RENAME, "test_sidecar_rename_allows_different_arity_same_name"),
        ],
        "overridden interface method": [(RENAME, "test_sidecar_rename_method_renames_override_group")],
        "superclass method": [(RENAME, "test_sidecar_rename_superclass_method_renames_override_group")],
        "static method": [(RENAME, "test_sidecar_rename_static_method_rewrites_unqualified_and_class_qualified_calls")],
        "private method": [(RENAME, "test_sidecar_rename_private_method_rewrites_declaration_and_call")],
        "field access via this": [(RENAME, "test_java_semantic_rename_manager_applies_transactional_edit")],
        "field access via class qualifier": [(RENAME, "test_sidecar_rename_field_via_class_qualifier_rewrites_static_accesses")],
        "static import": [(RENAME, "test_sidecar_rename_static_import_field_rewrites_import_and_use")],
        "wildcard import": [(RENAME, "test_sidecar_rename_type_through_wildcard_import_rewrites_usage")],
        "nested class": [(RENAME, "test_sidecar_rename_nested_class_updates_references_without_file_rename")],
        "record": [(RENAME, "test_sidecar_rename_record_type_updates_constructions")],
        "enum": [(RENAME, "test_sidecar_rename_enum_type_updates_references_and_renames_file")],
        "sealed type": [(RENAME, "test_sidecar_rename_sealed_permitted_subtype_updates_permits_clause")],
        "annotation type": [(RENAME, "test_sidecar_rename_annotation_type_updates_declaration_and_usage")],
        "constructor rename through class rename": [(RENAME, "test_sidecar_rename_constructor_through_type_rename_applies")],
        "public top-level type file rename": [(RENAME, "test_sidecar_semantic_rename_plans_top_level_type_file_rename")],
        "CRLF file": [(RENAME, "test_sidecar_rename_crlf_file_preserves_line_endings_on_apply")],
    },
    "Safe delete cases": {
        "unused private method": [
            (SAFE_DELETE, "test_sidecar_safe_delete_allows_standalone_unreferenced_method"),
            (SAFE_DELETE, "test_java_safe_delete_manager_applies_private_method_delete"),
        ],
        "used private method": [(SAFE_DELETE, "test_sidecar_safe_delete_refuses_live_references")],
        "method referenced by method reference": [(SAFE_DELETE, "test_sidecar_safe_delete_refuses_method_referenced_by_method_reference")],
        "field used in annotation value": [(SAFE_DELETE, "test_sidecar_safe_delete_refuses_field_used_in_annotation_value")],
        "local variable unused": [(SAFE_DELETE, "test_sidecar_safe_delete_allows_own_line_unused_local")],
        "field with multi-declarator refusal": [(SAFE_DELETE, "test_sidecar_safe_delete_refuses_public_api_and_multi_declarator")],
        "public API refusal": [(SAFE_DELETE, "test_sidecar_safe_delete_refuses_public_api_and_multi_declarator")],
        "top-level file delete": [
            (SAFE_DELETE, "test_sidecar_safe_delete_plans_top_level_type_file_delete"),
            (SAFE_DELETE, "test_sidecar_safe_delete_sole_top_level_type_deletes_file"),
        ],
    },
    "Move cases": {
        "import rewrite": [(MOVE, "test_sidecar_move_rewrites_import_and_removes_obsolete_import")],
        "same package to different package": [(MOVE, "test_java_move_top_level_type_manager_applies_package_file_and_import_rewrites")],
        "different source root": [(MOVE, "test_java_move_top_level_type_target_directory_across_source_roots")],
        "static imports": [
            (MOVE, "test_sidecar_move_import_golden_static_imports_untouched"),
            (MOVE, "test_sidecar_move_into_default_package_refuses_inbound_static_import"),
        ],
        "static import does not suppress required normal import": [
            (MOVE, "test_sidecar_move_static_import_does_not_suppress_required_normal_import"),
            (MOVE, "test_sidecar_move_wildcard_plus_static_import_gets_normal_import"),
            (MOVE, "test_sidecar_move_file_in_target_package_gets_no_redundant_import"),
        ],
        "FQN references": [(MOVE, "test_sidecar_move_rewrites_fqn_respecting_identifier_boundaries")],
        "target class exists refusal": [
            (MOVE, "test_sidecar_move_top_level_type_refuses_target_collision_and_module_info"),
            (MOVE, "test_sidecar_move_refuses_duplicate_type_in_target_package_other_source_root"),
        ],
        "module-info refusal": [
            (MOVE, "test_sidecar_move_refuses_when_module_info_exports_old_package"),
            (MOVE, "test_sidecar_move_refuses_when_module_info_opens_old_package"),
        ],
    },
    "Inline cases": {
        "literal initializer": [
            (INLINE, "test_sidecar_inline_private_constant_g010_removes_declaration"),
            (INLINE, "test_sidecar_inline_local_allowed_in_modelled_context"),
        ],
        "arithmetic initializer": [(INLINE, "test_sidecar_inline_local_additive_right_operand_of_subtraction_parenthesizes")],
        "precedence requiring parentheses": [
            (INLINE, "test_sidecar_inline_private_compile_time_constant_applies_with_parentheses"),
            (INLINE, "test_sidecar_inline_local_per_usage_context_aware_parenthesization"),
        ],
        "assignment-after-init refusal": [(INLINE, "test_sidecar_inline_local_refuses_reassigned_variable")],
        "unstable initializer dependency refusal": [
            (INLINE, "test_sidecar_inline_local_refuses_dependency_reassigned_after_declaration"),
            (INLINE, "test_sidecar_inline_local_refuses_field_dependency_initializer"),
            (INLINE, "test_sidecar_inline_local_refuses_array_element_dependency_initializer"),
            (INLINE, "test_sidecar_inline_local_accepts_stable_final_local_dependency"),
        ],
        "method-call initializer refusal": [(INLINE, "test_sidecar_inline_local_refuses_method_call_initializer")],
        "multi-declarator refusal": [(INLINE, "test_sidecar_inline_local_refuses_multi_declarator")],
        "captured variable case": [
            (INLINE, "test_sidecar_inline_local_refuses_capture_in_lambda"),
            (INLINE, "test_sidecar_inline_local_refuses_capture_in_anonymous_class"),
        ],
    },
    "V2 session operations": {
        "session lifecycle and revision guard": [
            (SESSIONS, "test_change_signature_session_rewrites_declaration_and_call_sites"),
            (SESSIONS, "test_apply_session_refuses_stale_source_revision"),
        ],
        "capability and unsupported-planner diagnostics": [
            (SESSIONS, "test_status_reports_v2_capabilities"),
            (SESSIONS, "test_v2_session_surface_refuses_unimplemented_planner"),
        ],
        "change signature and introduce parameter": [
            (SESSIONS, "test_change_signature_session_rewrites_declaration_and_call_sites"),
            (SESSIONS, "test_introduce_parameter_session_replaces_expression_and_updates_callers"),
            (SESSIONS, "test_change_signature_refuses_overload_ambiguity"),
            (SESSIONS, "test_introduce_parameter_adds_import_for_explicit_fqn_type"),
            (SESSIONS, "test_change_signature_import_planner_falls_back_on_simple_name_conflict"),
            (SESSIONS, "test_change_signature_import_planner_preserves_static_import_group"),
        ],
        "move static and instance members": [
            (SESSIONS, "test_move_static_member_session_moves_declaration_and_rewrites_calls"),
            (SESSIONS, "test_move_instance_method_session_moves_to_parameter_receiver"),
            (SESSIONS, "test_move_static_member_refuses_instance_targets"),
            (SESSIONS, "test_move_static_member_rewrites_static_import_references"),
            (SESSIONS, "test_v2_nested_access_config_allows_security_sensitive_private_widening"),
        ],
        "pull up and push down members": [
            (SESSIONS, "test_pull_up_member_session_moves_declaration_to_supertype"),
            (SESSIONS, "test_push_down_member_session_copies_to_subtypes_and_removes_source"),
            (SESSIONS, "test_pull_up_member_refuses_target_collision"),
        ],
        "extract method and extract interface": [
            (SESSIONS, "test_extract_method_session_extracts_complete_statement"),
            (SESSIONS, "test_extract_method_refuses_control_flow_selection"),
            (SESSIONS, "test_extract_method_preserves_tabs_and_crlf_style"),
            (SESSIONS, "test_v2_nested_extract_method_config_supplies_default_visibility"),
            (SESSIONS, "test_extract_interface_session_creates_interface_and_connects_source"),
            (SESSIONS, "test_extract_interface_target_package_imports_and_applies"),
            (SESSIONS, "test_extract_interface_import_planner_falls_back_on_simple_name_conflict"),
            (SESSIONS, "test_extract_interface_refuses_private_members"),
        ],
        "introduce field, encapsulate field, and inline method": [
            (SESSIONS, "test_introduce_field_session_adds_field_and_replaces_expression"),
            (SESSIONS, "test_introduce_field_preserves_tab_style"),
            (SESSIONS, "test_encapsulate_field_session_generates_accessors_and_rewrites_simple_uses"),
            (SESSIONS, "test_introduce_field_refuses_unsafe_initializer"),
            (SESSIONS, "test_inline_method_session_replaces_simple_calls_and_removes_method"),
            (SESSIONS, "test_inline_method_refuses_statement_bodies"),
        ],
        "generated and Lombok-managed policy gate": [
            (SESSIONS, "test_v2_session_refuses_generated_sources_without_opt_in"),
            (SESSIONS, "test_v2_session_allows_generated_sources_with_explicit_opt_in"),
            (SESSIONS, "test_v2_direct_preview_and_apply_refuse_generated_sources_without_opt_in"),
            (SESSIONS, "test_v2_generated_sources_edit_config_allows_default_generated_session"),
            (SESSIONS, "test_v2_nested_generated_sources_config_allows_default_generated_session"),
            (SESSIONS, "test_v2_session_refuses_lombok_sources_without_opt_in"),
            (SESSIONS, "test_v2_session_refuses_when_policy_source_cannot_be_read"),
        ],
        "path traversal and absolute path refusals": [
            (SESSIONS, "test_v2_session_refuses_source_path_traversal"),
            (SESSIONS, "test_v2_session_refuses_target_relative_path_traversal"),
            (SESSIONS, "test_v2_session_refuses_target_type_traversal"),
            (SESSIONS, "test_v2_session_refuses_extract_interface_target_package_traversal"),
        ],
    },
    # The five V2 hard blockers from the static review against the apply contract. These rows freeze the regression
    # coverage for the incremental session-apply guarantees that justify advertising the session-applied ops "supported"
    # (see Main#READY_OPERATIONS): applied units are recorded only after the caller's post-commit ack, the full
    # project-revision token is enforced on every incremental apply, the operation target is re-resolved and its semantic
    # identity re-checked before any apply envelope is returned, and a change-signature default must be a proven
    # compile-time constant (no factory/side-effect bypass).
    "V2 incremental session-apply hard-blocker contract": {
        # G001 PostCommitAck: a successful sidecar apply-session must NOT mark units applied; only the explicit
        # post-commit ack moves session state, so a Python-side commit failure leaves the units re-appliable.
        "post-commit ack records applied units, not edit emission (G001)": [
            (SESSIONS, "test_incremental_apply_without_ack_keeps_units_unapplied"),
            (SESSIONS, "test_incremental_session_apply_subset_then_remaining"),
        ],
        # G002 FullRevisionGuard: incremental apply enforces the FULL project-revision token (build files, compiler
        # args, classpath, source/generated roots, per-file source hashes), exempting only acknowledged-committed paths.
        "full project-revision guard on incremental apply (G002)": [
            (SESSIONS, "test_apply_session_refuses_stale_source_revision"),
            (SESSIONS, "test_apply_session_refuses_mismatched_expected_revision"),
        ],
        # G003 ReResolveTarget: incremental apply re-resolves the current target and compares semantic identity against
        # the stored target before returning any apply envelope; a moved/renamed target is a structured refusal.
        "re-resolve target identity before incremental apply (G003)": [
            (SESSIONS, "test_incremental_apply_refuses_when_target_identity_moved"),
        ],
        # G004 ChangeSigDefaultPurity: a change-signature default is detached text, so only a proven compile-time
        # constant is admitted; a type-qualified enum constant is accepted (and imported), while an unverifiable or
        # inaccessible default is refused.
        "change-signature default admits only proven constants (G004)": [
            (SESSIONS, "test_change_signature_imports_default_expression_at_call_site"),
            (SESSIONS, "test_change_signature_default_adds_import_at_cross_package_call_site"),
            (SESSIONS, "test_change_signature_refuses_default_with_inaccessible_type_at_call_site"),
        ],
    },
    "Serena integration tests": {
        "tool appears only when configured": [
            (INTEGRATION, "test_java_refactor_tools_visibility_gated_by_config"),
            (INTEGRATION, "test_java_refactor_tools_single_project_visibility_gated_by_config"),
            (INTEGRATION, "test_java_refactor_tools_not_available_without_enabled_active_project"),
        ],
        "preview does not modify files": [
            (INTEGRATION, "test_generic_rename_routing_previews_by_default_and_does_not_mutate"),
            (INTEGRATION, "test_generic_safe_delete_routing_previews_by_default_and_does_not_mutate"),
            (WORKSPACE_EDIT, "test_workspace_edit_preview_does_not_modify_files"),
        ],
        "apply modifies expected files": [
            (RENAME, "test_java_semantic_rename_manager_applies_transactional_edit"),
            (INTEGRATION, "test_generic_rename_routing_applies_when_preview_default_false"),
        ],
        "hash mismatch refuses apply": [
            (INTEGRATION, "test_apply_refuses_hash_mismatch_as_structured_refusal"),
            (SAFE_DELETE, "test_safe_delete_apply_refuses_when_file_changed_after_planning"),
            (WORKSPACE_EDIT, "test_workspace_edit_rejects_hash_mismatch"),
        ],
        "sidecar crash returns clean tool error": [
            (INTEGRATION, "test_generic_rename_falls_back_to_lsp_when_engine_raises_mid_call"),
            (INTEGRATION, "test_java_refactor_client_request_times_out_and_terminates_sidecar"),
        ],
        "existing LSP rename remains available as fallback": [
            (INTEGRATION, "test_generic_rename_routing_disabled_uses_lsp_fallback"),
            (INTEGRATION, "test_generic_rename_falls_back_to_lsp_when_engine_unavailable"),
        ],
        "JDTLS caches/diagnostics still work after edits": [(JDTLS, "test_jdtls_stays_coherent_after_sidecar_rename")],
    },
    "Safety rules (section 16)": {
        "project model valid": [(MODEL, "test_sidecar_hard_discovery_errors_still_refuse_preview")],
        "target resolved to exactly one semantic element": [(RENAME, "test_sidecar_rename_refuses_same_arity_overload_ambiguity")],
        "all edit spans are exact": [
            (WORKSPACE_EDIT, "test_workspace_edit_apply_character_offsets_descending_preserves_line_endings"),
            (RENAME, "test_sidecar_apply_rename_after_non_bmp_char_uses_utf16_offsets"),
        ],
        "no overlapping edits": [(WORKSPACE_EDIT, "test_workspace_edit_rejects_overlapping_edits")],
        "no file hash changed since preview": [
            (WORKSPACE_EDIT, "test_workspace_edit_rejects_hash_mismatch"),
            (WORKSPACE_EDIT, "test_workspace_edit_rejects_text_edit_without_hash_precondition"),
            (WORKSPACE_EDIT, "test_workspace_edit_rejects_destructive_file_operation_without_hash"),
        ],
        "no unsupported generated/dependency source": [
            (RENAME, "test_sidecar_rename_refuses_edit_in_generated_source_root"),
            (SAFE_DELETE, "test_sidecar_safe_delete_refuses_target_in_generated_source_root"),
            (MOVE, "test_sidecar_move_refuses_target_in_generated_source_root"),
            (INLINE, "test_sidecar_inline_refuses_target_in_generated_source_root"),
            (RENAME, "test_sidecar_rename_refuses_classpath_only_binary_target"),
        ],
        "no new javac ERROR diagnostics": [
            (INTEGRATION, "test_incomplete_analysis_apply_rejects_new_errors"),
            (INTEGRATION, "test_java_refactor_manager_rolls_back_on_post_validation_failure"),
        ],
    },
    # Safety-contract hardening rows: each pins one of the end-to-end guarantees layered onto the plan's section 16
    # rules (validation-response gating, rename completion proof, target identity, offset-boundary refusals).
    "Safety hardening": {
        "refused validateEdit blocks apply, preview validation, and post-apply revalidation": [
            (INTEGRATION, "test_apply_refuses_when_validate_edit_is_refused"),
            (INTEGRATION, "test_preview_validation_reports_refused_validate_edit_as_not_ready"),
            (INTEGRATION, "test_apply_rolls_back_when_post_apply_revalidation_is_refused"),
        ],
        "rename completion proven by exact rewrite of every baseline reference": [
            (INTEGRATION, "test_rename_rolls_back_when_edit_overlaps_but_does_not_replace_identifier"),
            (INTEGRATION, "test_rename_rolls_back_when_covering_edit_text_is_not_new_name"),
            (INTEGRATION, "test_rename_rolls_back_when_redeclared_symbol_keeps_old_name"),
            (INTEGRATION, "test_rename_rolls_back_when_old_key_still_referenced"),
        ],
        "target identity verified against name/kind/arity hints before planning": [
            (TARGET_HINTS, "test_sidecar_refuses_position_on_enclosing_declaration_with_mismatched_hints"),
            (TARGET_HINTS, "test_sidecar_refuses_same_line_sibling_declaration"),
            (TARGET_HINTS, "test_sidecar_refuses_wrong_overload_arity"),
            (TARGET_HINTS, "test_sidecar_accepts_matching_overload_arity"),
            (TARGET_HINTS, "test_sidecar_refuses_parameter_when_field_was_requested"),
            (TARGET_HINTS, "test_sidecar_accepts_parameter_when_parameter_was_requested"),
            (TARGET_HINTS, "test_sidecar_resolve_target_verifies_name_hint"),
            (INTEGRATION, "test_target_hints_from_lsp_symbol_derives_name_kind_and_arity"),
            (INTEGRATION, "test_target_hints_are_forwarded_to_sidecar_params_and_baseline_scan"),
        ],
        "UTF-16 boundary and encoding failures are structured refusals": [
            (WORKSPACE_EDIT, "test_workspace_edit_refuses_edit_splitting_surrogate_pair"),
            (WORKSPACE_EDIT, "test_workspace_edit_refuses_out_of_bounds_utf16_offset"),
            (WORKSPACE_EDIT, "test_workspace_edit_refuses_unknown_project_encoding"),
            (WORKSPACE_EDIT, "test_workspace_edit_refuses_undecodable_file_content"),
            (WORKSPACE_EDIT, "test_workspace_edit_refuses_lone_surrogate_replacement"),
            (WORKSPACE_EDIT, "test_workspace_edit_refuses_replacement_unrepresentable_in_project_encoding"),
            (INTEGRATION, "test_surrogate_splitting_edit_is_structured_refusal_on_preview_and_apply"),
        ],
    },
    # V1 merge-blocker hardening rows. Build-model fidelity (real effective compiler options per source set),
    # complete generated-source-root discovery beyond the conventional annotations dir, and bundled-jar provenance
    # (fresh build + source fingerprint) — each pinned so the blocking regression fixtures cannot silently rot.
    "Build-model compiler options (Blocker 1)": {
        "non-managed compiler args survive verbatim": [
            (MODEL, "test_gradle_extracts_add_exports_into_javac_options"),
            (MODEL, "test_gradle_extracts_enable_preview_into_javac_options"),
            (MODEL, "test_maven_extracts_compiler_plugin_args_into_javac_options"),
        ],
        "compiler-arg-only build succeeds": [(MODEL, "test_gradle_compiler_arg_only_build_succeeds_and_exposes_arg")],
        "extracted args extend never override managed flags": [(MODEL, "test_gradle_extracted_managed_flag_does_not_duplicate")],
    },
    # G004 build-model Gradle OPERATION coverage: each row runs a real refactor against a distinct build shape and
    # asserts the correct edit OR the correct refusal — proving resolution, not just model extraction (which G021 pins).
    "Build-model Gradle operation coverage (G004)": {
        "included build: operation edits included source set": [
            (BUILD_MODEL, "test_gradle_included_build_operation_renames_in_included_source_set"),
        ],
        "release below sidecar JDK resolves and applies": [
            (BUILD_MODEL, "test_gradle_release_below_sidecar_jdk_resolves_and_applies"),
        ],
        "release above sidecar JDK refuses apply": [
            (BUILD_MODEL, "test_gradle_release_above_sidecar_jdk_refuses_apply"),
        ],
        "Java resolves Kotlin output dir and applies": [
            (BUILD_MODEL, "test_gradle_java_resolves_kotlin_output_dir_and_applies"),
        ],
        "extra source set: operation propagates into integrationTest": [
            (BUILD_MODEL, "test_gradle_extra_source_set_operation_propagates_into_integration_test"),
        ],
        "unmodelable source set marked unproven and apply refused": [
            (BUILD_MODEL, "test_gradle_unmodelable_source_set_marks_unproven_and_refuses_apply"),
        ],
    },
    "Generated source roots (Blocker 2)": {
        "non-conventional generated root is visible and edit-refused": [(MODEL, "test_maven_discovers_non_conventional_generated_root")],
        "conventional generated roots discovered": [(MODEL, "test_sidecar_extracts_generated_roots")],
        "annotation-processor output is non-editable": [(MODEL, "test_sidecar_marks_annotation_processor_generated_output_non_editable")],
    },
    "Bundled jar provenance (Blocker 3)": {
        "bundled jar matches a fresh build": [(INTEGRATION, "test_bundled_resource_jar_matches_fresh_build")],
        "bundled jar fingerprint matches source": [(INTEGRATION, "test_bundled_sidecar_jar_fingerprint_matches_source")],
        "packaging refuses a stale or unverifiable jar": [(INTEGRATION, "test_packaging_hook_blocks_stale_or_unverifiable_sidecar_jar")],
        "built wheel bundles the jar": [(INTEGRATION, "test_bundled_sidecar_jar_is_included_in_built_wheel")],
    },
    # Contract-narrowing hard blockers: each row pins a V1-contract behavior that an earlier conservative refusal had
    # narrowed away. Removing or renaming any mapped test fails the matrix, so the broadened contract cannot silently rot.
    "Move extraction from multi-top-level files (HB-1)": {
        "extract type, leaving package-private companion": [
            (MOVE, "test_sidecar_move_extracts_public_type_leaving_package_private_companion"),
        ],
        "extract one of multiple public types": [(MOVE, "test_sidecar_move_extracts_one_of_multiple_public_types")],
        "import splitting and Javadoc carry-over": [(MOVE, "test_sidecar_move_extraction_splits_imports_and_carries_javadoc")],
        "rollback when extracted result does not compile": [
            (MOVE, "test_sidecar_move_extraction_rolls_back_when_result_does_not_compile"),
        ],
    },
    "Safe delete local-variable surgery (HB-2)": {
        "same-line local delete (exact span)": [(SAFE_DELETE, "test_sidecar_safe_delete_accepts_shared_line_local")],
        "multi-declarator local comma surgery": [
            (SAFE_DELETE, "test_sidecar_safe_delete_multi_declarator_local_removes_first"),
            (SAFE_DELETE, "test_sidecar_safe_delete_multi_declarator_local_removes_last"),
        ],
        "construct-specific undeletable refusals": [
            (SAFE_DELETE, "test_sidecar_safe_delete_refuses_for_init_variable"),
            (SAFE_DELETE, "test_sidecar_safe_delete_refuses_enhanced_for_variable"),
            (SAFE_DELETE, "test_sidecar_safe_delete_refuses_catch_parameter"),
            (SAFE_DELETE, "test_sidecar_safe_delete_refuses_resource_variable"),
        ],
    },
    "Inline contract broadening (HB-3, HB-4)": {
        "same-line local inline (exact-span removal)": [(INLINE, "test_sidecar_inline_local_accepts_multi_statement_line")],
        "zero-reference private constant delete-only": [
            (INLINE, "test_sidecar_inline_constant_zero_reference_private_emits_delete_only"),
        ],
    },
    # V2 hard blockers (the NEW HB-1..HB-11 set, distinct from the older HB-1..HB-4 contract-narrowing rows above).
    # Each blocker landed compiler-/AST-backed safety in the Java engine; these rows pin the end-to-end acceptance
    # tests (real sidecar jar) that observe each blocker's key edge cases so the design-audit guards cover them.
    "V2 hard blockers (HB v2-hardblockers)": {
        # HB-1/G001 is a TOOL-layer contract (preview=False is honored as a transactional one-shot apply; preview/None
        # returns a session-only preview); its Python unit tests live in the integration suite under the workflow glob.
        "HB-1/G001 V2 operation tools honor preview=False as a transactional one-shot apply": [
            (INTEGRATION, "test_g001_v2_operation_tool_honors_preview_false_one_shot_apply"),
            (INTEGRATION, "test_hb1_v2_operation_tool_preview_is_default_and_unannotated"),
        ],
        "HB-2 registry truthfulness: every V2 capability op reports supported": [
            (HARDBLOCKERS, "test_hb2_every_v2_capability_operation_reports_supported"),
        ],
        "HB-3 moveStaticMember semantic same-erasure collision refusals": [
            (HARDBLOCKERS, "test_hb3_move_static_refuses_collision_simple_vs_fqn_param"),
            (HARDBLOCKERS, "test_hb3_move_static_refuses_generic_instantiation_vs_erasure"),
            (HARDBLOCKERS, "test_hb3_move_static_refuses_annotation_and_formatting_differences"),
        ],
        "HB-3 genuinely different erasure is allowed": [
            (HARDBLOCKERS, "test_hb3_move_static_allows_genuinely_different_erasure"),
        ],
        "HB-4 moveInstanceMethod super/type-variable AST safety": [
            (HARDBLOCKERS, "test_hb4_move_instance_refuses_spaced_super_reference"),
            (HARDBLOCKERS, "test_hb4_move_instance_allows_super_token_in_comment_or_string"),
            (HARDBLOCKERS, "test_hb4_move_instance_refuses_source_type_variable_in_signature"),
            (HARDBLOCKERS, "test_hb4_move_instance_allows_type_parameter_named_only_in_javadoc"),
        ],
        "HB-5 hierarchy member rendering (verbatim javac slices)": [
            (HARDBLOCKERS, "test_hb5_pull_up_renders_multiline_generic_return_type"),
            (HARDBLOCKERS, "test_hb5_pull_up_renders_type_use_annotation_on_return_type"),
            (HARDBLOCKERS, "test_hb5_pull_up_renders_annotation_between_modifiers_and_type"),
            (HARDBLOCKERS, "test_hb5_pull_up_renders_body_with_braces_in_comment_and_string"),
        ],
        "HB-6 hierarchy import transfer and cleanup": [
            (HARDBLOCKERS, "test_hb6_pull_up_preserves_import_used_only_in_comment_or_string"),
            (HARDBLOCKERS, "test_hb6_pull_up_preserves_static_wildcard_import"),
            (HARDBLOCKERS, "test_hb6_pull_up_same_package_reference_needs_no_import"),
        ],
        "HB-7 introduceField scope binding (this.field vs unqualified)": [
            (HARDBLOCKERS, "test_hb7_introduce_field_qualifies_when_lambda_param_shadows"),
            (HARDBLOCKERS, "test_hb7_introduce_field_qualifies_when_catch_parameter_shadows"),
            (HARDBLOCKERS, "test_hb7_introduce_field_unqualified_for_sibling_block_local"),
        ],
        "HB-8 extract-method scope-checked synthesized method name (multi-output/control-flow synthesis is V3 scope, refused in V2)": [
            (SESSIONS, "test_extract_method_refuses_name_collision"),
            (HARDBLOCKERS, "test_g003_extract_method_accepts_single_output_statement_selection"),
            (HARDBLOCKERS, "test_g003_extract_method_refuses_multi_output_even_when_requested"),
            (HARDBLOCKERS, "test_g003_extract_method_refuses_control_flow_even_when_requested"),
        ],
        "HB-9 inline single-throw body, no token fallback": [
            (HARDBLOCKERS, "test_hb9_inline_single_throw_body_at_statement_site"),
            (HARDBLOCKERS, "test_hb9_inline_single_throw_body_with_checked_exception"),
            (HARDBLOCKERS, "test_hb9_inline_refuses_unmodellable_body_instead_of_token_substitution"),
        ],
        "HB-10 accepted V2 preview carries validated diagnostic delta": [
            (HARDBLOCKERS, "test_hb10_v2_preview_carries_validated_diagnostic_delta"),
            (HARDBLOCKERS, "test_hb10_manager_v2_preview_carries_validated_diagnostic_delta"),
        ],
    },
    # G005: import management is ONE shared contract, not a per-operation patchwork. The engine-level contract is
    # unit-proven in the Java sidecar (shared/ImportManagerTest, shared/ImportRewritePlannerTest); each behavioral row
    # below pins the per-operation acceptance tests that prove representative operations actually inherit that contract
    # end-to-end (the previewed import block obeys the shared rule). Removing or renaming any mapped test fails the
    # matrix, so the shared guarantee cannot silently narrow to a single operation.
    "Shared import management (G005)": {
        "add import for a newly referenced type": [
            (SESSIONS, "test_introduce_parameter_adds_import_for_explicit_fqn_type"),
            (SESSIONS, "test_change_signature_imports_default_expression_at_call_site"),
            (IMPORTS, "test_introduce_field_adds_import_for_fully_qualified_field_type"),
            (IMPORTS, "test_move_instance_method_transplants_body_import_into_target"),
        ],
        "remove an import that becomes unused after the edit": [
            (MOVE, "test_sidecar_move_rewrites_import_and_removes_obsolete_import"),
            (IMPORTS, "test_pull_up_member_removes_source_import_made_unused"),
        ],
        "static import preservation and collision handling": [
            (SESSIONS, "test_change_signature_import_planner_preserves_static_import_group"),
            (SESSIONS, "test_move_static_member_rewrites_static_import_references"),
        ],
        "wildcard import preserved unless it conflicts": [
            (IMPORTS, "test_pull_up_member_preserves_unrelated_wildcard_import"),
            (MOVE, "test_sidecar_move_wildcard_plus_static_import_gets_normal_import"),
        ],
        "java.lang and same-package types are not imported": [
            (IMPORTS, "test_introduce_field_skips_java_lang_and_same_package_field_types"),
            (IMPORTS, "test_change_signature_does_not_import_same_package_or_java_lang_types"),
        ],
        "ambiguous simple name falls back to a fully qualified name": [
            (SESSIONS, "test_change_signature_import_planner_falls_back_on_simple_name_conflict"),
            (SESSIONS, "test_extract_interface_import_planner_falls_back_on_simple_name_conflict"),
        ],
        "unused-import cleanup only when semantic references prove it unused": [
            (IMPORTS, "test_pull_up_member_preserves_import_still_used_by_remaining_code"),
            (IMPORTS, "test_pull_up_member_removes_source_import_made_unused"),
        ],
        "style preserved: groups, blank lines, static placement, ordering": [
            (SESSIONS, "test_change_signature_import_planner_preserves_static_import_group"),
            (IMPORTS, "test_pull_up_member_preserves_unrelated_wildcard_import"),
        ],
    },
    # G010: per-operation "still required before merge" proof obligations. Each operation's cross-cutting obligations
    # (constructor/qualified/cross-file call sites, side-effect/duplication policy, shadowing, compound-assignment
    # policy, checked-exception propagation, method-reference refusal, ...) map to a POSITIVE and/or NEGATIVE behavioral
    # test through the shared import + session-safety contract. Most were already proven by earlier goals; the few
    # implemented-but-untested obligations were closed in test_java_refactor_g010_merge_proofs. Removing or renaming any
    # mapped test fails this matrix, so a per-operation merge obligation cannot silently lose its proof.
    "G010 per-operation merge proofs": {
        # change signature -------------------------------------------------------------------------------------------
        "change signature: add parameter (declaration + call sites)": [
            (SESSIONS, "test_change_signature_session_rewrites_declaration_and_call_sites"),
        ],
        "change signature: remove parameter (positive + still-used refusal)": [
            (SESSIONS, "test_change_signature_removes_unused_parameter"),
            (SESSIONS, "test_change_signature_refuses_removed_parameter_still_used"),
        ],
        "change signature: reorder parameters and arguments": [
            (SESSIONS, "test_change_signature_reorders_parameters_and_call_arguments"),
            (SESSIONS, "test_change_signature_uses_old_index_for_reorder_and_rename"),
        ],
        "change signature: return conversion (positive + incompatible refusal)": [
            (SESSIONS, "test_change_signature_applies_return_conversion_to_used_call_sites"),
            (SESSIONS, "test_change_signature_refuses_incompatible_return_body"),
        ],
        "change signature: constructor call sites": [
            (SESSIONS, "test_change_signature_updates_constructor_call_sites"),
        ],
        "change signature: qualified + cross-file call sites": [
            (SESSIONS, "test_change_signature_reports_exact_multi_file_touched_stats"),
            (SESSIONS, "test_change_signature_default_adds_import_at_cross_package_call_site"),
        ],
        "change signature: overload safety refusal": [
            (SESSIONS, "test_change_signature_refuses_overload_ambiguity"),
        ],
        "change signature: method-reference arity (safe rename + arity refusal)": [
            (SESSIONS, "test_change_signature_rewrites_safe_method_reference_renames"),
            (SESSIONS, "test_change_signature_refuses_method_reference_arity_change"),
        ],
        # introduce parameter ----------------------------------------------------------------------------------------
        "introduce parameter: selected-expression extraction + caller update": [
            (SESSIONS, "test_introduce_parameter_session_replaces_expression_and_updates_callers"),
        ],
        "introduce parameter: side-effect / evaluation-order refusal": [
            (SESSIONS, "test_introduce_parameter_refuses_impure_selected_expression"),
        ],
        "introduce parameter: call-site default duplication portability refusal": [
            (G010, "test_introduce_parameter_refuses_captured_state_default"),
        ],
        "introduce parameter: cross-file call sites": [
            (SESSIONS, "test_introduce_parameter_reports_exact_multi_file_touched_stats"),
        ],
        "introduce parameter: method-reference arity refusal": [
            (SESSIONS, "test_introduce_parameter_inherits_method_reference_arity_refusal"),
        ],
        # move static member -----------------------------------------------------------------------------------------
        "move static member: erased-signature collision refusal": [
            (SESSIONS, "test_move_static_member_refuses_semantic_signature_collision"),
            (HARDBLOCKERS, "test_hb3_move_static_refuses_collision_simple_vs_fqn_param"),
        ],
        "move static member: private-dependency access-widening gate (positive + refusal)": [
            (SESSIONS, "test_move_static_member_private_source_dependency_requires_access_widening"),
            (SESSIONS, "test_move_static_member_refuses_security_sensitive_private_widening"),
        ],
        "move static member: import transfer + qualified references": [
            (SESSIONS, "test_move_static_member_rewrites_semantic_references_and_imports"),
            (SESSIONS, "test_move_static_member_rewrites_static_import_references"),
        ],
        # move instance method (G003/G008 own the bulk; these pin the per-op obligations) -----------------------------
        "move instance method: source-state dependency refusal": [
            (SESSIONS, "test_move_instance_method_refuses_super_usage_in_body"),
            (SESSIONS, "test_move_instance_method_refuses_non_simple_receiver"),
        ],
        "move instance method: access-widening gate": [
            (SESSIONS, "test_move_instance_method_gates_cross_package_access_widening"),
        ],
        # extract method ---------------------------------------------------------------------------------------------
        "extract method: complete-statement extraction": [
            (SESSIONS, "test_extract_method_session_extracts_complete_statement"),
        ],
        "extract method: synthesized method-name collision (HB-8)": [
            (SESSIONS, "test_extract_method_refuses_name_collision"),
            (HARDBLOCKERS, "test_g003_extract_method_accepts_single_output_statement_selection"),
        ],
        "extract method: control-flow / multiple-output refusal": [
            (SESSIONS, "test_extract_method_refuses_control_flow_selection"),
            (SESSIONS, "test_extract_method_refuses_multiple_output_variables"),
        ],
        "extract method: lambda-boundary / name-collision (shadowing) refusal": [
            (SESSIONS, "test_extract_method_refuses_lambda_boundary_selection"),
            (SESSIONS, "test_extract_method_refuses_name_collision"),
        ],
        # extract interface ------------------------------------------------------------------------------------------
        "extract interface: public-method extraction + implements rewrite": [
            (SESSIONS, "test_extract_interface_session_creates_interface_and_connects_source"),
        ],
        "extract interface: covariant/generic signatures + import transfer": [
            (SESSIONS, "test_extract_interface_renders_inherited_generic_signature_imports"),
            (SESSIONS, "test_extract_interface_target_package_imports_and_applies"),
        ],
        "extract interface: private signature / duplicate-signature refusal": [
            (SESSIONS, "test_extract_interface_refuses_private_signature_types"),
            (SESSIONS, "test_extract_interface_refuses_duplicate_method_signatures"),
        ],
        # introduce field --------------------------------------------------------------------------------------------
        "introduce field: scope-binding qualification HB-7 (catch / lambda)": [
            (HARDBLOCKERS, "test_hb7_introduce_field_qualifies_when_catch_parameter_shadows"),
            (HARDBLOCKERS, "test_hb7_introduce_field_qualifies_when_lambda_param_shadows"),
        ],
        "introduce field: scope-binding qualification HB-7 (resource / pattern)": [
            (G010, "test_introduce_field_qualifies_when_resource_variable_shadows"),
            (G010, "test_introduce_field_qualifies_when_pattern_variable_shadows"),
        ],
        "introduce field: checked-exception initializer refusal": [
            (G010, "test_introduce_field_refuses_checked_exception_initializer"),
        ],
        "introduce field: non-constant initializer policy (positive + refusal)": [
            (SESSIONS, "test_introduce_field_creates_compile_time_constant"),
            (SESSIONS, "test_introduce_field_refuses_non_constant_initializer"),
        ],
        # encapsulate field ------------------------------------------------------------------------------------------
        "encapsulate field: accessor generation + direct-use rewrite": [
            (SESSIONS, "test_encapsulate_field_session_generates_accessors_and_rewrites_simple_uses"),
            (SESSIONS, "test_encapsulate_field_rewrites_external_member_references"),
        ],
        "encapsulate field: accessor collision refusal": [
            (SESSIONS, "test_encapsulate_field_refuses_existing_accessor_collision"),
        ],
        "encapsulate field: compound-assignment policy refusal": [
            (SESSIONS, "test_encapsulate_field_refuses_compound_assignment"),
        ],
        # inline method ----------------------------------------------------------------------------------------------
        "inline method: single-return body": [
            (SESSIONS, "test_inline_method_session_replaces_simple_calls_and_removes_method"),
        ],
        "inline method: single-throw body (HB-9, plain + checked)": [
            (HARDBLOCKERS, "test_hb9_inline_single_throw_body_at_statement_site"),
            (HARDBLOCKERS, "test_hb9_inline_single_throw_body_with_checked_exception"),
        ],
        "inline method: checked-exception propagation": [
            (SESSIONS, "test_inline_method_allows_declared_checked_exceptions_when_caller_declares"),
        ],
        "inline method: evaluation-order / duplication refusal": [
            (SESSIONS, "test_inline_method_refuses_duplicate_side_effecting_argument"),
            (SESSIONS, "test_inline_method_refuses_order_sensitive_arguments"),
        ],
        "inline method: method-reference refusal": [
            (G010, "test_inline_method_refuses_method_reference_call_site"),
        ],
        "inline method: unmodellable-body refusal (no token fallback)": [
            (HARDBLOCKERS, "test_hb9_inline_refuses_unmodellable_body_instead_of_token_substitution"),
        ],
    },
}


V2_FULL_DESIGN_REQUIREMENTS: dict[str, list[tuple[str, str]]] = {
    "override groups": [
        (SESSIONS, "test_change_signature_updates_override_group_and_resolved_call_sites"),
        (RENAME, "test_sidecar_rename_method_renames_override_group"),
        (RENAME, "test_sidecar_rename_parameter_divergent_names_across_override_group_updates_all"),
    ],
    "interface implementations": [
        (RENAME, "test_sidecar_rename_parameter_in_interface_method_updates_implementation"),
        (RENAME, "test_sidecar_rename_overriding_interface_method_parameter_updates_interface"),
        (SESSIONS, "test_extract_interface_session_creates_interface_and_connects_source"),
    ],
    "constructors": [
        (SESSIONS, "test_change_signature_updates_constructor_call_sites"),
        (RENAME, "test_sidecar_rename_constructor_reference_rewrites_type_token"),
        (RENAME, "test_sidecar_rename_refuses_direct_constructor_rename"),
    ],
    "method references": [
        (SESSIONS, "test_change_signature_refuses_method_reference_arity_change"),
        (RENAME, "test_sidecar_rename_method_reference_and_call_target_method_token"),
        (RENAME, "test_sidecar_rename_includes_method_reference_expression"),
    ],
    # G008: move-instance method-reference safety — refuse with located evidence when the declaration is removed,
    # accept when a delegate with the original name/signature is retained so the capture still resolves.
    "move-instance method-reference safety": [
        (SESSIONS, "test_move_instance_method_refuses_cross_file_method_reference"),
        (SESSIONS, "test_move_instance_method_accepts_method_reference_with_retained_delegate"),
    ],
    "generic types": [
        (SESSIONS, "test_extract_interface_renders_inherited_generic_signature_imports"),
        (RENAME, "test_sidecar_rename_parameterized_type_rewrites_raw_type_only"),
        (RENAME, "test_sidecar_rename_library_override_parameter_refused_when_parameterized"),
    ],
    "inherited members": [
        (RENAME, "test_sidecar_rename_refuses_inherited_same_arity_overload"),
        (RENAME, "test_sidecar_rename_field_refuses_inherited_field_hiding"),
        (SESSIONS, "test_push_down_member_refuses_source_typed_call_site_removal"),
    ],
    "cross-file semantic references": [
        (SESSIONS, "test_move_static_member_rewrites_semantic_references_and_imports"),
        (RENAME, "test_sidecar_semantic_rename_rewrites_cross_source_set_reference"),
        (MODEL, "test_sidecar_scans_references_across_source_set_boundary"),
    ],
    "overloaded methods": [
        (SESSIONS, "test_change_signature_refuses_overload_ambiguity"),
        (SESSIONS, "test_move_instance_method_refuses_target_overload_ambiguity"),
        (RENAME, "test_sidecar_cross_task_rename_refuses_same_arity_overload_in_secondary_owner"),
    ],
    "nested boundaries": [
        (SESSIONS, "test_v2_nested_extract_method_config_supplies_default_visibility"),
        (RENAME, "test_sidecar_rename_nested_class_updates_references_without_file_rename"),
    ],
    "anonymous boundaries": [
        (INLINE, "test_sidecar_inline_local_refuses_capture_in_anonymous_class"),
        (RENAME, "test_sidecar_rename_type_updates_anonymous_class_reference"),
    ],
    "lambda boundaries": [
        (SESSIONS, "test_extract_method_refuses_lambda_boundary_selection"),
        (INLINE, "test_sidecar_inline_local_refuses_capture_in_lambda"),
        (SAFE_DELETE, "test_sidecar_safe_delete_param_refuses_lambda_argument"),
    ],
    "multiple source sets and modules": [
        (FIXTURES, "test_multi_source_set_gradle_fixture_renames_across_source_sets"),
        (FIXTURES, "test_multi_module_maven_fixture_renames_in_module"),
        (FIXTURES, "test_modules_fixture_discovers_modular_model_and_renames"),
        (RENAME, "test_sidecar_per_source_set_cross_set_override_rename_rewrites_subclass"),
    ],
    "import ambiguity": [
        (SESSIONS, "test_change_signature_import_planner_falls_back_on_simple_name_conflict"),
        (SESSIONS, "test_extract_interface_import_planner_falls_back_on_simple_name_conflict"),
    ],
    "static imports": [
        (SESSIONS, "test_change_signature_import_planner_preserves_static_import_group"),
        (SESSIONS, "test_move_static_member_rewrites_static_import_references"),
    ],
    "generated and Lombok policy": [
        (SESSIONS, "test_v2_session_refuses_generated_sources_without_opt_in"),
        (SESSIONS, "test_v2_session_allows_generated_sources_with_explicit_opt_in"),
        (SESSIONS, "test_v2_session_refuses_lombok_sources_without_opt_in"),
        (SESSIONS, "test_v2_session_refuses_when_source_edit_reaches_generated_reference"),
    ],
    "stale semantic target identity": [
        (SESSIONS, "test_change_signature_rewrites_only_javac_resolved_target"),
        (RENAME, "test_sidecar_rename_method_reference_and_call_target_method_token"),
    ],
    "refusal-code inventory": [
        (ACCEPTANCE, "test_v2_refusal_code_inventory_matches_sidecar_sources"),
    ],
    # G007: §21 bullet coverage — previously uncovered bullets now have named tests
    "change-signature parameter removal": [
        (SESSIONS, "test_change_signature_removes_unused_parameter"),
    ],
    "move-member super-body refusal": [
        (SESSIONS, "test_move_instance_method_refuses_super_usage_in_body"),
    ],
    "hierarchy static constant pull-up": [
        (SESSIONS, "test_pull_up_member_moves_static_constant_to_supertype"),
    ],
    "hierarchy push-down selected subclass": [
        (SESSIONS, "test_push_down_member_targets_selected_subclass_only"),
    ],
    "extract-interface safe usage replacement": [
        (SESSIONS, "test_extract_interface_replaces_safe_usage_at_call_sites"),
    ],
    "extract-interface private return type refusal": [
        (SESSIONS, "test_extract_interface_refuses_private_return_type_crossing_package"),
    ],
    "extract-interface duplicate signatures refusal": [
        (SESSIONS, "test_extract_interface_refuses_duplicate_method_signatures"),
    ],
    "encapsulate-field boolean getter prefix": [
        (SESSIONS, "test_encapsulate_field_boolean_getter_uses_is_prefix"),
    ],
    "encapsulate-field annotation preservation": [
        (SESSIONS, "test_encapsulate_field_preserves_field_annotations"),
    ],
    "encapsulate-field accessor collision": [
        (SESSIONS, "test_encapsulate_field_refuses_existing_accessor_collision"),
    ],
    "inline-method static helper": [
        (SESSIONS, "test_inline_method_inlines_static_helper"),
    ],
    "inline-method receiver substitution": [
        (SESSIONS, "test_inline_method_substitutes_receiver_in_body"),
    ],
    "inline-method duplicate side-effect argument": [
        (SESSIONS, "test_inline_method_refuses_duplicate_side_effecting_argument"),
    ],
    "extract-method multiple outputs refusal": [
        (SESSIONS, "test_extract_method_v2_refuses_multiple_output_variables"),
    ],
    "extract-method comment preservation": [
        (SESSIONS, "test_extract_method_v2_preserves_comments_inside_selection"),
    ],
    # G009: pull-up/push-down scope audit — every behavior the V2 hierarchy sections (plan §8/§9) promise has an
    # explicit behavioral row asserting the planned edit or the precise refusal. The 11 audited behaviors:
    "G009 pull-up concrete method": [
        (SESSIONS, "test_pull_up_member_session_moves_declaration_to_supertype"),
    ],
    "G009 pull-up abstract declaration": [
        (SESSIONS, "test_pull_up_member_make_abstract_keeps_source_with_override"),
    ],
    "G009 pull-up to interface (method + constant)": [
        (SESSIONS, "test_pull_up_member_to_interface_adds_declaration_and_override"),
        (SESSIONS, "test_pull_up_member_constant_to_interface_renders_public_static_final"),
    ],
    "G009 push-down copy (kept in source)": [
        (SESSIONS, "test_push_down_member_defaults_to_copy_all_direct_subtypes"),
    ],
    "G009 push-down remove (deleted from source)": [
        (SESSIONS, "test_push_down_member_session_copies_to_subtypes_and_removes_source"),
    ],
    "G009 member collisions in target(s)": [
        (SESSIONS, "test_pull_up_member_refuses_target_collision"),
        (SESSIONS, "test_push_down_member_refuses_target_collision"),
    ],
    "G009 sibling override compatibility": [
        (SESSIONS, "test_pull_up_member_allows_compatible_sibling_override"),
    ],
    "G009 source-call safety": [
        (SESSIONS, "test_pull_up_member_refuses_source_only_body_dependency"),
        (SESSIONS, "test_push_down_member_refuses_source_typed_call_site_removal"),
    ],
    "G009 covariant/generic override compatibility": [
        (SESSIONS, "test_pull_up_member_allows_covariant_return_sibling_override"),
        (SESSIONS, "test_pull_up_member_refuses_incompatible_generic_override_sibling"),
    ],
    "G009 import transfer and cleanup": [
        (SESSIONS, "test_pull_up_member_adds_target_import_and_cleans_unused_source_import"),
    ],
    "G009 public-API confirmation order": [
        (SESSIONS, "test_pull_up_member_refuses_public_member_without_confirmation"),
        (SESSIONS, "test_pull_up_member_refuses_source_only_body_dependency"),
    ],
}

EXPECTED_V2_REFUSAL_CODES = {
    'AMBIGUOUS_OVERLOAD_AFTER_MOVE',
    'BUILD_FILE_UPDATE_REQUIRED',
    'PUBLIC_API_CONFIRMATION_REQUIRED',
    'accessor_collision',
    'ambiguous_member_selection',
    'argument_mismatch',
    'assigned_outside_declaration',
    'body_analysis_unbindable',
    'build_file_rewrite_unsupported',
    'call_site_ambiguous',
    'call_site_not_found',
    'checked_exception_initializer',
    'checked_exception_unsupported',
    'concurrency_sensitive_field',
    'conflicting_field_modes',
    'constructor_required',
    'constructor_strategy_required',
    'control_flow_unsupported',
    'control_flow_with_outputs_unsupported',
    'cross_site_reference_unsupported',
    'cross_site_resolvability_unproven',
    'delete_public_api_unsupported',
    'duplicate_signatures',
    'enum_constants_unsupported',
    'expression_type_unknown',
    'extract_name_collision',
    'field_already_exists',
    'field_not_found',
    'generated_source_refused',
    'import_conflict',
    'incompatible_member_body',
    'incomplete_inline',
    'initialization_order_unsupported',
    'initializer_extraction_unsupported',
    'initializer_references_subclass',
    'inline_body_unmodellable',
    'interface_already_exists',
    'interface_field_not_constant',
    'invalid_',
    'invalid_interface_name',
    'invalid_selection',
    'invalid_visibility',
    'lambda_boundary_unsupported',
    'local_variable_capture',
    'lombok_managed_source_refused',
    'make_static_unsupported',
    'malformed_move_package',
    'malformed_move_source_root',
    'malformed_rename_package',
    'package_split_across_modules',
    'source_root_not_found',
    'max_call_sites_exceeded',
    'member_not_found',
    'method_body_unsupported',
    'method_not_supported',
    'method_reference_unsupported',
    'missing_',
    'missing_initializer',
    'missing_interface_name',
    'missing_new_method_name',
    'missing_relative_path',
    'missing_selected_expression',
    'missing_selection',
    'missing_target_type',
    'missing_target_types',
    'move_package_failed',
    'multi_declarator_field_unsupported',
    'no_supported_members',
    'non_constant_initializer',
    'non_editable_target',
    'overloaded_method_body_unbound',
    'override_method_unsupported',
    'package_collision',
    'package_not_found',
    'path_outside_project',
    'private_type_unsupported',
    'public_api_confirmation_required',
    'receiver_substitution_unsupported',
    'record_component_unsupported',
    'recursive_body_unsupported',
    'rename_package_failed',
    'selection_not_extractable',
    'selection_out_of_range',
    'serialization_impact',
    'sibling_member_collision',
    'source_type_not_found',
    'statement_body_unsupported',
    'static_field_unsupported',
    'super_body_unsupported',
    'super_receiver_unsupported',
    'target_field_not_found',
    'target_member_exists',
    'target_method_exists',
    'target_not_member',
    'target_not_source_type',
    'target_not_subtype',
    'target_not_supertype',
    'target_not_type',
    'target_parameter_not_found',
    'type_parameter_unsupported',
    'unparseable_source',
    'unresolved_moved_signature',
    'unresolved_type',
    'unsafe_argument',
    'unsafe_argument_reuse',
    'unsafe_field_pull_up',
    'unsafe_field_push_down',
    'unsafe_initializer',
    'unsafe_receiver',
    'unsafe_selected_expression',
    'unsafe_source_call_site',
    'unsafe_usage_replacement',
    'unsupported_constructor_strategy',
    'unsupported_members',
    'void_call_context_unsupported',
}

ACCEPTANCE_MATRIX["V2 full semantic requirements"] = V2_FULL_DESIGN_REQUIREMENTS


# refactor-feature-plan-V3.md §26 acceptance criteria. Every V3 capability the platform claims must map, by exact
# name, to at least one behavior-asserting test (positive edit and/or precise refusal). The generic guards below
# (rows-map-to-existing-tests, workflow-runs-every-file, asserts-behavior-not-mere-existence) then hold these rows to
# the same standard as the V1/V2 rows, so the §26 table cannot silently rot. Both the high-level sidecar op tests
# (`test_java_refactor_sidecar_v3_*`) and the thin-protocol forwarder tests (`test_java_refactor_v3_*_protocol`) are
# mapped: the former prove the capability end to end through the workspace engine, the latter pin the JSON contract.
V3_RENAME_PACKAGE = "test/serena/test_java_refactor_sidecar_v3_package_rename.py"
V3_MOVE_PACKAGE = "test/serena/test_java_refactor_sidecar_v3_package_move.py"
V3_SOURCE_ROOT_MOVE = "test/serena/test_java_refactor_sidecar_v3_source_root_move.py"
V3_SAFE_DELETE = "test/serena/test_java_refactor_sidecar_v3_safe_delete.py"
V3_EXTRACT_INLINE = "test/serena/test_java_refactor_sidecar_v3_extract_inline.py"
V3_CONVERSIONS = "test/serena/test_java_refactor_sidecar_v3_conversions.py"
V3_RECIPES = "test/serena/test_java_refactor_sidecar_v3_recipes.py"
V3_PKG_MOD_RES = "test/serena/test_java_refactor_sidecar_v3_package_module_resources.py"
V3_DELETION_PROTO = "test/serena/test_java_refactor_v3_deletion_protocol.py"
V3_CLASS_REFACTOR_PROTO = "test/serena/test_java_refactor_v3_class_refactor_protocol.py"
V3_DEEP_INLINE_PROTO = "test/serena/test_java_refactor_v3_deep_inline_protocol.py"
V3_CONVERSIONS_PROTO = "test/serena/test_java_refactor_v3_conversions_protocol.py"
V3_RECIPE_ENGINE_PROTO = "test/serena/test_java_refactor_v3_recipe_engine_protocol.py"
V3_RESOURCE_SPI_PROTO = "test/serena/test_java_refactor_v3_resource_spi_protocol.py"
V3_FRAMEWORK_SPI_PROTO = "test/serena/test_java_refactor_v3_framework_spi_protocol.py"
V3_IMPACT_FACTS_PROTO = "test/serena/test_java_refactor_v3_impact_facts_protocol.py"
V3_GRAPH_PROTO = "test/serena/test_java_refactor_v3_graph_protocol.py"
V3_SIDECAR_FACTS = "test/serena/test_java_refactor_v3_sidecar_facts.py"
V3_TOOLS = "test/serena/test_java_refactor_v3_tools.py"
V3_TRANSFORMATION_PROTO = "test/serena/test_java_refactor_v3_transformation_protocol.py"
V3_WORKSPACE = "test/serena/test_java_refactor_v3_workspace.py"

V3_ACCEPTANCE_CRITERIA: dict[str, list[tuple[str, str]]] = {
    "Rename package": [
        (V3_RENAME_PACKAGE, "test_sidecar_rename_package_basic_moves_file_and_rewrites_references"),
        (V3_RENAME_PACKAGE, "test_sidecar_rename_package_refuses_collision_with_existing_target_type"),
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_rewrites_module_info_directive"),
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_rewrites_resource_fqcn_and_warns_on_reflection"),
    ],
    "Move package": [
        (V3_MOVE_PACKAGE, "test_sidecar_move_package_moves_package_and_subpackage_with_validated_delta"),
        (V3_MOVE_PACKAGE, "test_sidecar_move_package_excludes_subpackages_when_requested"),
        (V3_MOVE_PACKAGE, "test_sidecar_move_package_refuses_collision_with_existing_target_type"),
        (V3_SOURCE_ROOT_MOVE, "test_sidecar_move_source_root_relocates_files_without_text_edits"),
        (V3_SOURCE_ROOT_MOVE, "test_sidecar_move_source_root_refuses_destination_collision"),
        (V3_SOURCE_ROOT_MOVE, "test_sidecar_move_source_root_preserve_true_keeps_declarations_and_moves_files"),
        (V3_SOURCE_ROOT_MOVE, "test_sidecar_move_source_root_preserve_false_recomputes_package_from_directory"),
    ],
    "Propagate delete": [
        (V3_SAFE_DELETE, "test_sidecar_safe_delete_apply_removes_orphan_files"),
        (V3_SAFE_DELETE, "test_sidecar_safe_delete_refuses_when_deletion_breaks_compilation"),
        (V3_DELETION_PROTO, "test_cascade_pulls_in_private_helper"),
        (V3_DELETION_PROTO, "test_blocked_when_live_referrer_remains"),
        (V3_DELETION_PROTO, "test_service_loader_provider_line_removed"),
        (V3_DELETION_PROTO, "test_propagate_refuses_when_spring_ref_still_points_at_deleted_bean"),
        (V3_DELETION_PROTO, "test_propagate_refuses_when_no_roots"),
    ],
    "Find dead code": [
        (V3_SAFE_DELETE, "test_sidecar_find_dead_code_scan_is_read_only"),
        (V3_DELETION_PROTO, "test_find_dead_code_high_and_low"),
        (V3_DELETION_PROTO, "test_find_dead_code_mutates_nothing"),
        (V3_DELETION_PROTO, "test_find_dead_code_reports_unused_constructor_and_overload"),
        (V3_DELETION_PROTO, "test_find_dead_code_never_reports_required_roots"),
        (V3_TOOLS, "test_find_dead_code_max_answer_chars_bounds_the_report"),
    ],
    "Extract class": [
        (V3_EXTRACT_INLINE, "test_sidecar_extract_class_apply_writes_helper"),
        (V3_EXTRACT_INLINE, "test_sidecar_extract_class_refuses_dropping_delegates_for_public_api"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_moves_field_and_method"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_refuses_super_dependency"),
    ],
    "Extract superclass": [
        (V3_EXTRACT_INLINE, "test_sidecar_extract_superclass_apply_inserts_extends"),
        (V3_EXTRACT_INLINE, "test_sidecar_extract_superclass_refuses_existing_superclass"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_hoists_common_method"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_refuses_existing_super"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_make_abstract_keeps_concrete_override"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_suggests_interface_alternative"),
    ],
    "Replace inheritance with delegation": [
        (V3_EXTRACT_INLINE, "test_sidecar_replace_inheritance_apply_rewrites_to_composition"),
        (V3_EXTRACT_INLINE, "test_sidecar_replace_inheritance_refuses_no_superclass"),
        # §10 literal-implementation coverage: a co-located `implements` clause is preserved (only `extends` is severed),
        # cross-package forwarders use a simple type name plus an added import, the public-API change is gated behind an
        # explicit confirmation, and a protected-superclass-member dependency is refused with a specific code.
        (V3_EXTRACT_INLINE, "test_sidecar_replace_inheritance_preserves_implements_clause"),
        (V3_EXTRACT_INLINE, "test_sidecar_replace_inheritance_cross_package_uses_import"),
        (V3_EXTRACT_INLINE, "test_sidecar_replace_inheritance_blocks_public_api_change_by_default"),
        (V3_EXTRACT_INLINE, "test_sidecar_replace_inheritance_refuses_protected_member_dependency"),
        (V3_EXTRACT_INLINE, "test_sidecar_replace_inheritance_rewrites_super_method_call"),
        (V3_CLASS_REFACTOR_PROTO, "test_replace_inheritance_forwards_methods"),
        (V3_CLASS_REFACTOR_PROTO, "test_replace_inheritance_refuses_generic_super"),
    ],
    "Deep inline method": [
        (V3_EXTRACT_INLINE, "test_sidecar_deep_inline_method_apply_inlines_and_deletes"),
        (V3_EXTRACT_INLINE, "test_sidecar_deep_inline_method_refuses_non_private"),
        (V3_DEEP_INLINE_PROTO, "test_deep_inline_void_multi_statement"),
        (V3_DEEP_INLINE_PROTO, "test_deep_inline_refuses_recursion"),
        (V3_DEEP_INLINE_PROTO, "test_deep_inline_max_call_sites_below_count_refuses"),
        (V3_DEEP_INLINE_PROTO, "test_deep_inline_max_call_sites_at_count_proceeds"),
    ],
    "Anonymous to lambda": [
        (V3_CONVERSIONS, "test_sidecar_convert_anonymous_to_lambda_apply_rewrites"),
        (V3_CONVERSIONS, "test_sidecar_convert_anonymous_to_lambda_refuses_state"),
        (V3_CONVERSIONS_PROTO, "test_anonymous_to_lambda_runnable"),
        (V3_CONVERSIONS_PROTO, "test_anonymous_to_lambda_refuses_field"),
    ],
    "Lambda to method reference": [
        (V3_CONVERSIONS, "test_sidecar_convert_lambda_to_method_reference_apply_rewrites"),
        (V3_CONVERSIONS, "test_sidecar_convert_lambda_to_method_reference_refuses_block_body"),
        (V3_CONVERSIONS_PROTO, "test_lambda_to_static_method_reference"),
        (V3_CONVERSIONS_PROTO, "test_lambda_to_method_reference_refuses_compound"),
    ],
    "Resource-aware rewrite": [
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_rewrites_resource_fqcn_and_warns_on_reflection"),
        (V3_RESOURCE_SPI_PROTO, "test_resource_find_across_providers"),
        (V3_RESOURCE_SPI_PROTO, "test_resource_find_package_prefix"),
        (V3_RESOURCE_SPI_PROTO, "test_resource_find_refuses_unknown_kind"),
    ],
    "Framework-aware blocking": [
        (V3_FRAMEWORK_SPI_PROTO, "test_framework_detect_junit"),
        (V3_FRAMEWORK_SPI_PROTO, "test_framework_find_declares"),
        (V3_FRAMEWORK_SPI_PROTO, "test_framework_find_names"),
    ],
    "Recipe engine": [
        (V3_RECIPES, "test_sidecar_apply_recipe_apply_rewrites"),
        (V3_RECIPES, "test_sidecar_apply_recipe_refuses_new_compiler_errors"),
        (V3_RECIPES, "test_sidecar_scan_migration_opportunities_groups_and_writes_nothing"),
        (V3_RECIPE_ENGINE_PROTO, "test_recipe_apply_custom_safe_static_call"),
        (V3_RECIPE_ENGINE_PROTO, "test_recipe_apply_refuses_unresolved_symbol"),
        (V3_RECIPE_ENGINE_PROTO, "test_recipe_apply_blocks_needs_review_by_default"),
        (V3_RECIPE_ENGINE_PROTO, "test_recipe_apply_applies_needs_review_when_allowed"),
    ],
    "Impact report": [
        (V3_IMPACT_FACTS_PROTO, "test_touched_types_with_flags"),
        (V3_IMPACT_FACTS_PROTO, "test_incoming_refs_main_vs_test"),
        (V3_IMPACT_FACTS_PROTO, "test_resource_refs_detected"),
        (V3_IMPACT_FACTS_PROTO, "test_mutates_nothing"),
        (V3_SIDECAR_FACTS, "test_report_over_sidecar_facts_has_all_five_sections"),
        # G3: the sidecar transformation.report computes the real §17 five-section report (no computed:false),
        # and the Python composed-edit projection computes every section from touched files (no not_analyzed).
        (V3_TRANSFORMATION_PROTO, "test_report_computes_all_five_sections_with_honest_counts"),
        (V3_TRANSFORMATION_PROTO, "test_report_zero_sections_are_computed_not_skeleton"),
        (V3_WORKSPACE, "test_impact_report_classifies_touched_files_with_honest_counts"),
    ],
}

# The canonical §26 capability set, transcribed verbatim from the design's "V3 is complete when..." table (left
# column). It is an independent literal -- NOT derived from V3_ACCEPTANCE_CRITERIA -- so the completeness guard below
# detects drift in either direction: dropping a capability row, or adding one the design never sanctioned.
V3_SECTION_26_CAPABILITIES = frozenset(
    {
        "Rename package",
        "Move package",
        "Propagate delete",
        "Find dead code",
        "Extract class",
        "Extract superclass",
        "Replace inheritance with delegation",
        "Deep inline method",
        "Anonymous to lambda",
        "Lambda to method reference",
        "Resource-aware rewrite",
        "Framework-aware blocking",
        "Recipe engine",
        "Impact report",
    }
)

ACCEPTANCE_MATRIX["V3 acceptance criteria"] = V3_ACCEPTANCE_CRITERIA


# F14: the V3 code-review blocker ledger. The static V3 review raised fourteen blocking findings (F1-F14); each was
# closed by REAL compiler-backed behavior, never a documented refusal or a no-op param. This section is the capstone
# (F14 itself): it maps every other finding F1-F13 -- by exact name -- to the dedicated, behavior-asserting test(s)
# that prove its fix, and maps F14 to the guards below that hold this ledger to the same standard as every other row.
# Because the section is registered into ACCEPTANCE_MATRIX, the generic guards already enforce that each mapped row
# (a) names a test that exists (test_acceptance_matrix_rows_map_to_existing_tests), (b) runs under the dedicated CI
# workflow (test_dedicated_workflow_runs_every_mapped_test_file), and (c) asserts behavior rather than mere existence
# (test_every_mapped_test_asserts_behavior_not_mere_existence). The completeness guard below then ties the section to
# the verbatim F1-F14 finding set, so a dropped finding -- or a finding silently demoted to no coverage -- fails CI.
V3_CAPABILITY_CONTRACT = "test/serena/test_java_refactor_sidecar_v3_capability_contract.py"
V3_CAPABILITY_GATE_PROTO = "test/serena/test_java_refactor_v3_capability_gate_protocol.py"
V3_PKG_REF_SURFACE = "test/serena/test_java_refactor_sidecar_v3_package_reference_surface.py"
V3_PKG_RENAME_AST = "test/serena/test_java_refactor_v3_package_rename_ast_safety.py"
V3_TYPE_RENAME_RESOURCE = "test/serena/test_java_refactor_v3_type_rename_resource_rewrite.py"
V3_VALIDATE_EDIT_RESOURCE = "test/serena/test_java_refactor_v3_validate_edit_resource_resolution.py"
V3_SAFE_DELETE_IMPORT_CLEANUP = "test/serena/test_java_refactor_v3_safe_delete_import_cleanup.py"
V3_REPORTS = "test/serena/test_java_refactor_v3_reports.py"
V3_APPLY_POLICY = "test/serena/test_java_refactor_v3_apply_policy.py"

V3_REVIEW_BLOCKERS: dict[str, list[tuple[str, str]]] = {
    "F1: enumerate every V3 op in the public capability/readiness contract": [
        (V3_CAPABILITY_CONTRACT, "test_sidecar_enumerates_every_v3_dispatch_operation"),
        (V3_CAPABILITY_CONTRACT, "test_sidecar_v3_operation_reports_disabled_when_section_flag_gates_it"),
        (V3_CAPABILITY_CONTRACT, "test_sidecar_v3_master_switch_disables_every_dispatch_operation"),
        (V3_CAPABILITY_GATE_PROTO, "test_v3_op_refused_when_disabled_by_config"),
    ],
    "F2: resource SPI planEdits wired into rename/move/delete/validation/impact": [
        (V3_RESOURCE_SPI_PROTO, "test_resource_plan_edits_across_providers"),
        (V3_RESOURCE_SPI_PROTO, "test_resource_plan_edits_interface_rename"),
        (V3_RESOURCE_SPI_PROTO, "test_resource_plan_edits_offsets_match_find"),
        (V3_RESOURCE_SPI_PROTO, "test_resource_plan_edits_refuses_empty"),
    ],
    "F3: full package rename/move rewrite surface (wildcard/static imports, javadoc, FQNs)": [
        (V3_PKG_REF_SURFACE, "test_sidecar_rename_package_rewrites_wildcard_import_and_javadoc"),
        (V3_PKG_REF_SURFACE, "test_sidecar_move_package_rewrites_wildcard_import_and_javadoc"),
        (V3_PKG_RENAME_AST, "test_rename_package_rewrites_code_refs_but_not_strings_or_comments"),
        (V3_RENAME_PACKAGE, "test_sidecar_rename_package_basic_moves_file_and_rewrites_references"),
    ],
    "F4: module-info.java module-aware behavior": [
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_rewrites_module_info_directive"),
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_respects_rewrite_module_info_false"),
        (V3_PKG_MOD_RES, "test_sidecar_move_package_removes_redundant_export_when_merging_into_exported_package"),
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_refuses_when_split_across_modules"),
    ],
    "F5: resource rewriting (service-loader renames, provider lines, confidence, package-prefix policy)": [
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_renames_service_loader_file_and_rewrites_provider_line"),
        (V3_PKG_MOD_RES, "test_sidecar_resource_class_name_rewrite_carries_high_confidence_kind"),
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_leaves_standalone_package_prefix_untouched_by_default"),
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_rewrites_package_prefix_with_medium_confidence_when_enabled"),
        (V3_TYPE_RENAME_RESOURCE, "test_type_rename_renames_service_loader_registration_file"),
    ],
    "F6: framework participation pipeline (Spring/JPA/Jackson/JUnit)": [
        (V3_FRAMEWORK_SPI_PROTO, "test_framework_detect_junit"),
        (V3_FRAMEWORK_SPI_PROTO, "test_framework_find_declares"),
        (V3_FRAMEWORK_SPI_PROTO, "test_framework_find_names"),
        (V3_FRAMEWORK_SPI_PROTO, "test_framework_find_refuses_empty"),
        # participate(): the transformation-participant half (§16) — plugins veto deletes, validate metadata,
        # contribute resource edits/warnings on rename, and contribute reachability roots.
        (V3_FRAMEWORK_SPI_PROTO, "test_participate_jpa_entity_blocks_safe_delete"),
        (V3_FRAMEWORK_SPI_PROTO, "test_participate_jpa_metadata_validation_warns_without_id"),
        (V3_FRAMEWORK_SPI_PROTO, "test_participate_jpa_metadata_no_warning_with_id"),
        (V3_FRAMEWORK_SPI_PROTO, "test_participate_spring_rename_contributes_resource_edit"),
        (V3_FRAMEWORK_SPI_PROTO, "test_participate_junit_test_is_reachability_root"),
        (V3_FRAMEWORK_SPI_PROTO, "test_participate_refuses_unrecognized_change_kind"),
        # plan-joined seams: participation actually affects the delete plan and the dead-code scan.
        (V3_DELETION_PROTO, "test_propagate_delete_blocked_by_framework_participant"),
        (V3_DELETION_PROTO, "test_find_dead_code_keeps_junit_test_via_framework_participant"),
    ],
    "F7: full V3 impact report (semantic/resource/test/API/risk)": [
        (V3_IMPACT_FACTS_PROTO, "test_touched_types_with_flags"),
        (V3_IMPACT_FACTS_PROTO, "test_incoming_refs_main_vs_test"),
        (V3_IMPACT_FACTS_PROTO, "test_resource_refs_detected"),
        (V3_IMPACT_FACTS_PROTO, "test_risk_high_for_framework_entry_point"),
        (V3_REPORTS, "test_impact_report_flags_api_resources_and_tests"),
        (V3_SIDECAR_FACTS, "test_report_over_sidecar_facts_has_all_five_sections"),
        # G3: transformation.report's authoritative §17 five-section report computed end-to-end over a live sidecar.
        (V3_TRANSFORMATION_PROTO, "test_report_computes_all_five_sections_with_honest_counts"),
        (V3_TRANSFORMATION_PROTO, "test_report_zero_sections_are_computed_not_skeleton"),
    ],
    "F8: validation resource/framework layers (exact resolution, provider/interface consistency)": [
        (V3_VALIDATE_EDIT_RESOURCE, "test_validate_edit_flags_dangling_spring_bean_after_rename_without_resource_rewrite"),
        (V3_VALIDATE_EDIT_RESOURCE, "test_validate_edit_flags_dangling_jpa_class_after_delete"),
        (V3_VALIDATE_EDIT_RESOURCE, "test_validate_edit_no_finding_when_resource_rewritten_in_same_overlay"),
        (V3_VALIDATE_EDIT_RESOURCE, "test_validate_edit_no_finding_for_library_type_reference"),
    ],
    "F9: propagating safe-delete cleanup (imports, empty pkg/dir, resource/bean entries)": [
        (V3_DELETION_PROTO, "test_cascade_pulls_in_private_helper"),
        (V3_DELETION_PROTO, "test_blocked_when_live_referrer_remains"),
        (V3_DELETION_PROTO, "test_service_loader_provider_line_removed"),
        (V3_DELETION_PROTO, "test_propagate_refuses_when_spring_ref_still_points_at_deleted_bean"),
        (V3_DELETION_PROTO, "test_find_dead_code_reports_unused_constructor_and_overload"),
        (V3_SAFE_DELETE_IMPORT_CLEANUP, "test_safe_delete_strips_dangling_import_and_compiles"),
        (V3_SAFE_DELETE_IMPORT_CLEANUP, "test_safe_delete_does_not_mask_real_usage_and_refuses"),
    ],
    "F10: extract class with constructor-aware dependency closure": [
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_moves_field_and_method"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_injects_constructor_assigned_fields"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_allows_selected_method_dependency"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_passes_retained_field_as_constructor_parameter"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_keeps_retained_method_as_delegate_call"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_rewrites_external_method_usage_with_update_usages"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_refuses_external_field_usage_even_with_update_usages"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_refuses_retained_field_without_constructor"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_class_refuses_multiple_constructors"),
    ],
    "F11: extract superclass (abstract method hoisting, field pull-up, constructor propagation, implements preservation, interface-alternative suggestion)": [
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_hoists_common_method"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_interposes_shared_superclass"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_preserves_implements"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_propagates_constructor"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_refuses_existing_super"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_make_abstract_keeps_concrete_override"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_suggests_interface_alternative"),
        (V3_CLASS_REFACTOR_PROTO, "test_extract_superclass_field_pull_up_does_not_suggest_interface"),
    ],
    "F12: replace inheritance with delegation (constructor adaptation, safe super rewrites)": [
        (V3_CLASS_REFACTOR_PROTO, "test_replace_inheritance_forwards_methods"),
        (V3_CLASS_REFACTOR_PROTO, "test_replace_inheritance_forwards_transitively_inherited_method"),
        (V3_CLASS_REFACTOR_PROTO, "test_replace_inheritance_honors_custom_delegate_field_name"),
        (V3_CLASS_REFACTOR_PROTO, "test_replace_inheritance_refuses_generic_super"),
    ],
    "F13: recipe changeMethodSignature via compiler-backed change-signature engine": [
        (V3_RECIPE_ENGINE_PROTO, "test_recipe_apply_change_method_signature"),
        (V3_RECIPE_ENGINE_PROTO, "test_recipe_change_signature_refusal_passthrough"),
        (V3_RECIPE_ENGINE_PROTO, "test_recipe_apply_refuses_no_matches"),
        (V3_RECIPE_ENGINE_PROTO, "test_recipe_apply_refuses_unresolved_symbol"),
    ],
    "F14: test matrix covering every blocker (this ledger + its guards)": [
        (ACCEPTANCE, "test_v3_review_blockers_cover_every_finding"),
        (ACCEPTANCE, "test_v3_review_blocker_rows_map_to_existing_tests"),
        (ACCEPTANCE, "test_v3_review_blocker_rows_assert_behavior"),
    ],
}

# The verbatim F1-F14 finding identifiers from the V3 static review. An independent literal -- NOT derived from
# V3_REVIEW_BLOCKERS -- so the completeness guard detects drift in BOTH directions: a dropped finding row, or a row
# claiming a finding the review never raised.
V3_REVIEW_FINDING_IDS = frozenset({f"F{n}" for n in range(1, 15)})

ACCEPTANCE_MATRIX["V3 review blockers"] = V3_REVIEW_BLOCKERS


# V3 review gaps: post-blocker review findings ("Review Gap N") raised AFTER the F1-F14 ledger. Each is closed by REAL
# compiler-backed behavior and mapped here to its dedicated, behavior-asserting test(s). Registered into
# ACCEPTANCE_MATRIX so the generic guards enforce that every mapped row names a test that exists, runs under the
# dedicated CI workflow, and asserts behavior — exactly as for the F-ledger.
V3_REVIEW_GAPS: dict[str, list[tuple[str, str]]] = {
    # Gap 6: split-package / collision detection must use the build graph's real package-to-source-root facts for ALL
    # source packages (exported/opened or not), not module-info exports alone. A package physically present in two
    # source roots/modules is split regardless of module-info, and the qualified directive forms are preserved across a
    # rename.
    "Review Gap 6: build-graph split-package detection (package-to-source-root facts, not module-info exports)": [
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_detects_split_of_non_exported_package_via_build_graph"),
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_split_of_non_exported_package_proceeds_with_module_strategy"),
        (V3_PKG_MOD_RES, "test_sidecar_move_package_detects_non_exported_split_across_two_source_roots"),
        (V3_PKG_MOD_RES, "test_sidecar_rename_package_preserves_qualified_directives_and_provides_uses"),
    ],
    # Gap 14: uniform risk classification + enforced apply policy, no default-medium. Every accepted V3 edit is
    # classified from an explicit sidecar value onto the canonical SAFE/REVIEW_REQUIRED/REFUSED taxonomy, and the
    # apply path enforces policy at ONE seam (the bridge): SAFE applies, REVIEW_REQUIRED is blocked unless the uniform
    # allow_review_required control opts in, REFUSED never applies. The bridge never defaults an unclassified payload to
    # a guessed "medium" — it fails closed. Composes with the resource-confidence and framework-participation gaps,
    # whose facts the sidecar's aggregate (worst-of) risk already rolls into needs_review.
    "Review Gap 14: uniform risk classification + enforced apply policy (no default-medium)": [
        (V3_APPLY_POLICY, "test_from_sidecar_wire_maps_safe_and_needs_review"),
        (V3_APPLY_POLICY, "test_from_sidecar_wire_refuses_medium_explicitly"),
        (V3_APPLY_POLICY, "test_from_sidecar_wire_refuses_unknown_or_missing"),
        (V3_APPLY_POLICY, "test_safe_edit_applies"),
        (V3_APPLY_POLICY, "test_review_required_edit_is_blocked_by_default"),
        (V3_APPLY_POLICY, "test_review_required_edit_applies_with_uniform_allow_control"),
        (V3_APPLY_POLICY, "test_route_refuses_accepted_payload_without_explicit_risk"),
        (V3_APPLY_POLICY, "test_route_passes_through_refused_payload_without_classifying"),
        (V3_APPLY_POLICY, "test_composition_resource_or_framework_aggregate_blocks_apply"),
    ],
    # Review Gap (transformation graph): the unified V3 transformation graph (refactor-feature-plan-V3.md §1.2/§3
    # F-GRAPH) must be a REAL whole-repo graph built from the sidecar's compiler/build/resource models — not a
    # touched-set facade or a Python-side report contract alone. The sidecar assembles build layout (Maven/Gradle/plain
    # source roots, package->source-root and type->file maps), javac symbols/hierarchy/calls, EXACT resource FQN
    # references (provider-backed, never substring), and javac-resolved test->production edges; it caches per project
    # revision and invalidates on any source edit (content-addressed). The Python graph_client reshapes that payload
    # into the ProjectGraph contract verbatim so the impact report reads a real graph. Each protocol test asserts one of
    # these behaviors against a live sidecar.
    "Review Gap (transformation graph): real whole-repo graph built/cached from compiler+build+resource models": [
        (V3_GRAPH_PROTO, "test_plain_graph_build_system_roots_and_maps"),
        (V3_GRAPH_PROTO, "test_gradle_graph_reports_gradle_build_system"),
        (V3_GRAPH_PROTO, "test_maven_graph_reports_maven_build_system"),
        (V3_GRAPH_PROTO, "test_resource_references_are_exact_fqn_not_substring"),
        (V3_GRAPH_PROTO, "test_tests_referencing_a_touched_type"),
        (V3_GRAPH_PROTO, "test_graph_cached_then_incrementally_updated_on_source_change"),
        (V3_GRAPH_PROTO, "test_graph_build_refuses_before_initialize"),
        (V3_GRAPH_PROTO, "test_parsed_graph_feeds_report_shaped_reads"),
    ],
}

ACCEPTANCE_MATRIX["V3 review gaps"] = V3_REVIEW_GAPS


def _sidecar_refusal_codes() -> set[str]:
    refusal_pattern = re.compile(r'new\s+Refusal\s*\(\s*"([^"]+)"')
    java_sources = (REPO_ROOT / "java-refactor/src/main/java").rglob("*.java")
    return {code for source in java_sources for code in refusal_pattern.findall(source.read_text(encoding="utf-8"))}


def _test_function_names(relative_path: str) -> set[str]:
    source = (REPO_ROOT / relative_path).read_text(encoding="utf-8")
    tree = ast.parse(source, filename=relative_path)
    return {node.name for node in tree.body if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef) and node.name.startswith("test_")}


def _test_function_nodes(relative_path: str) -> dict[str, ast.FunctionDef | ast.AsyncFunctionDef]:
    source = (REPO_ROOT / relative_path).read_text(encoding="utf-8")
    tree = ast.parse(source, filename=relative_path)
    return {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef) and node.name.startswith("test_")}


def _behavioral_check_count(node: ast.FunctionDef | ast.AsyncFunctionDef) -> int:
    """Count behavior-asserting constructs in a test body.

    A real acceptance test must *observe* behavior, not merely call a function and rely on it not throwing.
    We recognize four equivalent forms: ``assert`` statements; ``with pytest.raises(...)`` refusal/error gates;
    ``_assert_*`` shared-helper calls (the sidecar tests factor positive/refusal assertions into helpers); and
    ``pytest.fail(...)`` guards. A mapped test with zero of these is an existence-only stub (Blocker 19) and fails
    the guard below, so the matrix cannot silently rot from behavior assertions into mere "the function still exists".
    """
    count = 0
    for child in ast.walk(node):
        if isinstance(child, ast.Assert):
            count += 1
        elif isinstance(child, ast.Call):
            func = child.func
            if (isinstance(func, ast.Attribute) and func.attr in {"raises", "warns", "fail"}) or (
                isinstance(func, ast.Name) and func.id.startswith("_assert")
            ):
                count += 1
    return count


def test_v2_refusal_code_inventory_matches_sidecar_sources() -> None:
    assert _sidecar_refusal_codes() == EXPECTED_V2_REFUSAL_CODES


def test_v2_full_design_requirements_are_part_of_acceptance_matrix() -> None:
    assert ACCEPTANCE_MATRIX["V2 full semantic requirements"] == V2_FULL_DESIGN_REQUIREMENTS


def test_v3_acceptance_criteria_cover_section_26_capabilities() -> None:
    # refactor-feature-plan-V3.md §26: V3 is complete only when EVERY capability in the design's table has acceptance
    # coverage. Tie the matrix section to the verbatim §26 capability set so the table cannot drift -- a dropped
    # capability row or an unsanctioned addition both fail here, and the generic guards then enforce that each mapped
    # row points at a real, behavior-asserting test.
    assert set(ACCEPTANCE_MATRIX["V3 acceptance criteria"]) == set(V3_SECTION_26_CAPABILITIES), (
        "the V3 acceptance section drifted from refactor-feature-plan-V3.md §26; every shipped V3 capability must keep "
        "exactly one acceptance row, and no row may claim a capability the design does not list."
    )


def test_v3_review_blockers_cover_every_finding() -> None:
    # F14: the blocker ledger must account for EXACTLY the fourteen V3 review findings F1-F14 -- no finding dropped and
    # no row inventing a finding the review never raised. We read the leading "F<n>" token of each row key and tie the
    # set to the verbatim V3_REVIEW_FINDING_IDS literal. The generic guards then enforce that every row's mapped tests
    # exist, run under CI, and assert behavior -- so "covered" cannot decay into an empty or existence-only mapping.
    finding_ids = {key.split(":", 1)[0].strip() for key in ACCEPTANCE_MATRIX["V3 review blockers"]}
    assert finding_ids == set(V3_REVIEW_FINDING_IDS), (
        "the V3 review-blocker ledger drifted from the F1-F14 finding set; every blocking finding must keep exactly "
        f"one row and no row may claim a finding the review never raised. got={sorted(finding_ids)}"
    )


def test_v3_review_blocker_rows_map_to_existing_tests() -> None:
    # F14: each blocker row must reference at least one test that exists by exact name. (This duplicates the generic
    # rows-map guard's coverage for this section on purpose: F14 maps to this test, so the ledger names a concrete,
    # behavior-asserting proof of its own integrity rather than only relying on the shared guard.)
    nodes_by_file: dict[str, set[str]] = {}
    missing: list[str] = []
    for finding, mapped in ACCEPTANCE_MATRIX["V3 review blockers"].items():
        assert mapped, f"{finding}: no mapped tests"
        for relative_path, test_name in mapped:
            if relative_path not in nodes_by_file:
                assert (REPO_ROOT / relative_path).is_file(), f"{finding}: missing test file {relative_path}"
                nodes_by_file[relative_path] = _test_function_names(relative_path)
            if test_name not in nodes_by_file[relative_path]:
                missing.append(f"{finding} -> {relative_path}::{test_name}")
    assert not missing, f"V3 review-blocker ledger references missing test(s): {missing}"


def test_v3_review_blocker_rows_assert_behavior() -> None:
    # F14: every blocker proof must observe behavior (an edit/value/refusal outcome), never merely call a function. A
    # row mapped to an existence-only test would let a finding be "covered" by a test that asserts nothing; this guard
    # fails in that case, freezing the ledger's honesty.
    nodes_by_file: dict[str, dict[str, ast.FunctionDef | ast.AsyncFunctionDef]] = {}
    existence_only: list[str] = []
    for finding, mapped in ACCEPTANCE_MATRIX["V3 review blockers"].items():
        for relative_path, test_name in mapped:
            if relative_path not in nodes_by_file:
                nodes_by_file[relative_path] = _test_function_nodes(relative_path)
            node = nodes_by_file[relative_path].get(test_name)
            if node is not None and _behavioral_check_count(node) == 0:
                existence_only.append(f"{finding} -> {relative_path}::{test_name}")
    assert not existence_only, f"V3 review-blocker ledger maps to existence-only test(s): {existence_only}"


def test_acceptance_matrix_rows_map_to_existing_tests() -> None:
    # Every design acceptance row must reference at least one test that actually exists, by exact name.
    names_by_file: dict[str, set[str]] = {}
    missing: list[str] = []
    for section, rows in ACCEPTANCE_MATRIX.items():
        for row, mapped in rows.items():
            assert mapped, f"{section} / {row}: no mapped tests"
            for relative_path, test_name in mapped:
                if relative_path not in names_by_file:
                    assert (REPO_ROOT / relative_path).is_file(), f"{section} / {row}: missing test file {relative_path}"
                    names_by_file[relative_path] = _test_function_names(relative_path)
                if test_name not in names_by_file[relative_path]:
                    missing.append(f"{section} / {row} -> {relative_path}::{test_name}")
    assert not missing, f"acceptance matrix references missing test(s): {missing}"


def test_dedicated_workflow_runs_every_mapped_test_file() -> None:
    # The acceptance matrix is only executable if the dedicated CI workflow actually runs every file it references:
    # either the file matches the workflow's test glob, or the workflow must name its path explicitly (this is what
    # guards the JDTLS seam test under test/solidlsp/ and the transactional applier tests in test_workspace_edit.py).
    workflow_text = (REPO_ROOT / WORKFLOW).read_text(encoding="utf-8")
    assert WORKFLOW_GLOB in workflow_text, f"dedicated workflow no longer runs the {WORKFLOW_GLOB} glob"
    not_run: list[str] = []
    for rows in ACCEPTANCE_MATRIX.values():
        for mapped in rows.values():
            for relative_path, _ in mapped:
                if fnmatch.fnmatch(relative_path, WORKFLOW_GLOB):
                    continue
                if relative_path not in workflow_text:
                    not_run.append(relative_path)
    assert not sorted(set(not_run)), f"dedicated workflow does not run mapped test file(s): {sorted(set(not_run))}"


def test_every_mapped_test_asserts_behavior_not_mere_existence() -> None:
    # Blocker 19: a mapped acceptance test must assert ACTUAL behavior (a value/edit/refusal outcome), not merely
    # invoke a named function and rely on it not throwing. Every mapped (file, test) must therefore contain at least
    # one behavioral check -- an `assert`, a `pytest.raises(...)` refusal/error gate, or an `_assert_*` helper call.
    # This freezes the audit: if any mapped test is later reduced to an existence-only stub, this guard fails.
    nodes_by_file: dict[str, dict[str, ast.FunctionDef | ast.AsyncFunctionDef]] = {}
    existence_only: list[str] = []
    for section, rows in ACCEPTANCE_MATRIX.items():
        for row, mapped in rows.items():
            for relative_path, test_name in mapped:
                if relative_path not in nodes_by_file:
                    nodes_by_file[relative_path] = _test_function_nodes(relative_path)
                node = nodes_by_file[relative_path].get(test_name)
                # Missing names are already reported by test_acceptance_matrix_rows_map_to_existing_tests.
                if node is not None and _behavioral_check_count(node) == 0:
                    existence_only.append(f"{section} / {row} -> {relative_path}::{test_name}")
    assert not existence_only, f"acceptance matrix maps to existence-only test(s) with no behavioral assertion: {existence_only}"


# G018: the name token that identifies a V2 operation's tests in the acceptance matrix. Each V2 op the registry can
# advertise "supported" must map (by this token) to at least one behavior-asserting acceptance test, so "supported"
# cannot be claimed for an op with no behavioral coverage.
_V2_OPERATION_TEST_TOKENS: dict[str, str] = {
    "changeSignature": "change_signature",
    "introduceParameter": "introduce_parameter",
    "moveStaticMember": "move_static_member",
    "moveInstanceMethod": "move_instance_method",
    "pullUpMember": "pull_up_member",
    "pushDownMember": "push_down_member",
    "extractMethod": "extract_method",
    "extractInterface": "extract_interface",
    "introduceField": "introduce_field",
    "encapsulateField": "encapsulate_field",
    "inlineMethod": "inline_method",
}


def test_v2_capability_operations_have_test_token_mapping() -> None:
    # G018: the token map must cover exactly the V2 capability surface the sidecar can advertise "supported". Keeping it
    # in lockstep with the manager's _V2_CAPABILITY_OPERATIONS means a new V2 op cannot be added without also declaring
    # how its behavioral coverage is identified below.
    from serena.java_refactor.manager import _V2_CAPABILITY_OPERATIONS

    assert set(_V2_OPERATION_TEST_TOKENS) == _V2_CAPABILITY_OPERATIONS, (
        "the V2 op token map drifted from the manager's _V2_CAPABILITY_OPERATIONS; every advertisable V2 op must declare "
        "its acceptance-test token so 'supported' stays tied to behavioral coverage."
    )


def test_every_supported_v2_operation_maps_to_behavioral_acceptance_coverage() -> None:
    # G018 supported<->behavior tie: every V2 operation that the registry advertises "supported" (all eleven, since the
    # blockers G001-G017 have landed) must map to at least one acceptance-matrix test that (a) is named for the op and
    # (b) actually asserts behavior (passes the same behavioral-check guard as test_every_mapped_test_asserts_behavior).
    # This makes "supported" un-claimable for an op with no behavioral acceptance test: drop the op's behavioral tests
    # and this guard fails, forcing either restored coverage or an honest downgrade away from "supported".
    nodes_by_file: dict[str, dict[str, ast.FunctionDef | ast.AsyncFunctionDef]] = {}

    mapped: set[tuple[str, str]] = set()
    for rows in ACCEPTANCE_MATRIX.values():
        for entries in rows.values():
            for relative_path, test_name in entries:
                mapped.add((relative_path, test_name))

    uncovered: list[str] = []
    for operation, token in _V2_OPERATION_TEST_TOKENS.items():
        behavioral_hits = 0
        for relative_path, test_name in mapped:
            if token not in test_name:
                continue
            if relative_path not in nodes_by_file:
                nodes_by_file[relative_path] = _test_function_nodes(relative_path)
            node = nodes_by_file[relative_path].get(test_name)
            if node is not None and _behavioral_check_count(node) > 0:
                behavioral_hits += 1
        if behavioral_hits == 0:
            uncovered.append(operation)

    assert not uncovered, (
        "V2 operations advertised 'supported' but with no behavior-asserting acceptance-matrix coverage: "
        f"{sorted(uncovered)} -- either add a behavioral acceptance test for the op or it must not claim 'supported'."
    )
