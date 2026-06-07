"""Executable V1 acceptance matrix (refactor-feature-plan.md section 15, safety rules section 16).

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
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

FIXTURES = "test/serena/test_java_refactor_fixture_matrix.py"
RENAME = "test/serena/test_java_refactor_sidecar_rename.py"
SAFE_DELETE = "test/serena/test_java_refactor_sidecar_safe_delete.py"
MOVE = "test/serena/test_java_refactor_sidecar_move.py"
INLINE = "test/serena/test_java_refactor_sidecar_inline.py"
MODEL = "test/serena/test_java_refactor_sidecar_model.py"
INTEGRATION = "test/serena/test_java_refactor.py"
TARGET_HINTS = "test/serena/test_java_refactor_sidecar_target_hints.py"
WORKSPACE_EDIT = "test/serena/test_workspace_edit.py"
JDTLS = "test/solidlsp/java/test_java_refactor_jdtls_integration.py"

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
        "fixture set is complete": [(FIXTURES, "test_fixture_matrix_is_complete")],
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
}


def _test_function_names(relative_path: str) -> set[str]:
    source = (REPO_ROOT / relative_path).read_text(encoding="utf-8")
    tree = ast.parse(source, filename=relative_path)
    return {node.name for node in tree.body if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef) and node.name.startswith("test_")}


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
