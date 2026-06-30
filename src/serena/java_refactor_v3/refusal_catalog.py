"""Live registry of the V3 refusal codes that the per-operation planning modules formerly registered.

Under the compiler-backed (sidecar-forwarding) architecture, planning happens in the Java sidecar and the
per-operation Python planner modules (``conversions``, ``delegation``, ``extract``, ``inline``, ``recipes``,
``deadcode``, ``package_ops``) are no longer on any live execution path. Their refusal-code documentation,
however, is still part of the V3 contract surfaced by :mod:`serena.java_refactor_v3.reports.acceptance`.

This module is the single live home for those code descriptions, so the acceptance/report layer no longer has
to import the dead planner modules (importing the heavy planners would otherwise pull the entire dead planning
stack -- ``deadcode.reachability`` -> ``spi.frameworks`` / ``package_ops.rewrite`` -- into the live process).

Most entries originate as the registration the corresponding planner module performed. Where the dead-planner
vocabulary has since drifted from the code the live Java sidecar actually emits for the same condition, the entry
is reconciled to the sidecar's code (e.g. ``member_not_found`` rather than the former ``extract_member_not_found``),
so the registry documents the refusal vocabulary the system genuinely produces.
:func:`serena.java_refactor_v3.models.register_refusal_code` is idempotent for an identical (code, description)
pair, so this catalogue and any still-imported planner module register the same code without conflict, and any
accidental divergence raises immediately rather than silently drifting.
"""

from __future__ import annotations

from serena.java_refactor_v3.models import register_refusal_code

# -- safe-delete cascade / dead-code analysis (formerly deadcode.analyzer) --------------------------
register_refusal_code("no_roots", "Propagating safe delete requires at least one deletion root.")
register_refusal_code("deadcode_unknown_seed", "A requested seed type does not exist in the project graph.")
register_refusal_code(
    "deadcode_blocked_root", "A seed type is on the public-API / framework boundary and may not be auto-deleted."
)
register_refusal_code(
    "deadcode_nothing_to_delete",
    "No file could be safely removed (every target shares a file with a retained type).",
)
# -- propagating safe delete (PropagatingSafeDeletePlanner) -----------------------------------------
register_refusal_code(
    "delete_source_unreadable",
    "A symbol admitted to the delete set has an unreadable source file, so its declaration edit cannot be "
    "emitted; the whole delete is refused rather than claim a deletion it cannot perform.",
)

# -- package / source-root moves (formerly package_ops.planner) ------------------------------------
register_refusal_code(
    "package_not_found", "The source package does not exist (no declared types) in the project graph."
)
register_refusal_code(
    "package_same_target", "The requested target package is identical to the source; nothing to do."
)
register_refusal_code(
    "package_collision", "A type with the destination fully-qualified name already exists outside the moved set."
)
register_refusal_code(
    "package_split",
    "The source package is split across multiple main source roots; an unambiguous move cannot be planned.",
)
register_refusal_code("source_root_not_found", "The source root to move does not exist in the project.")
register_refusal_code(
    "source_root_collision", "Moving the source root would overwrite an existing file at the destination."
)

# -- anonymous-class -> lambda / lambda -> method-reference (formerly conversions.models) -----------
register_refusal_code(
    "anon_type_not_found", "The declaring type for the anonymous-class conversion was not found."
)
register_refusal_code("anon_not_found", "No anonymous-class instance exists at the requested occurrence.")
register_refusal_code(
    "anon_not_functional_interface",
    "The implemented type is not a verifiable single-abstract-method interface.",
)
register_refusal_code("anon_multiple_methods", "The anonymous instance does not declare exactly one method.")
register_refusal_code(
    "anon_declares_field",
    "The anonymous instance declares a field; a lambda cannot hold state.",
)
register_refusal_code(
    "anon_this_reference", "The method references this; a lambda would rebind it to the enclosing instance."
)
register_refusal_code("anon_super_reference", "The method references super; a lambda cannot express it.")
register_refusal_code(
    "anon_extends_class",
    "The instance extends a class (passes constructor arguments) rather than implementing an interface.",
)
register_refusal_code("lambda_type_not_found", "The declaring type for the lambda conversion was not found.")
register_refusal_code("lambda_not_found", "No lambda exists at the requested occurrence.")
register_refusal_code("lambda_not_single_call", "The lambda body is not a single call expression.")
register_refusal_code(
    "lambda_arg_transformed", "A call argument is transformed rather than passed through unchanged."
)
register_refusal_code("lambda_arg_reordered", "Call arguments are reordered relative to the lambda parameters.")
register_refusal_code(
    "lambda_receiver_uses_param",
    "The receiver depends on a lambda parameter (unbound-instance form); its type cannot be synthesised.",
)
register_refusal_code("lambda_partial_args", "The call arity does not match the lambda parameters.")
register_refusal_code("lambda_unsupported_shape", "The lambda body shape is unsupported for a method reference.")

# -- replace inheritance with delegation (formerly delegation.models) -------------------------------
register_refusal_code(
    "delegation_type_not_found",
    "The declaring type for the delegation refactoring was not found in the project graph.",
)
register_refusal_code(
    "replace_inheritance_no_superclass", "The type has no superclass to replace with delegation."
)
register_refusal_code(
    "delegation_external_superclass",
    "The superclass is outside the project; its surface cannot be analysed to delegate soundly.",
)
register_refusal_code(
    "delegation_deep_hierarchy", "The superclass chain is deeper than one level; delegation is refused."
)
register_refusal_code(
    "delegation_abstract_parent", "The superclass is abstract; a delegate instance cannot be constructed."
)
register_refusal_code(
    "delegation_sealed_hierarchy",
    "The hierarchy is sealed; severing inheritance would violate the permits clause.",
)
register_refusal_code(
    "delegation_generic_substitution",
    "The superclass is parameterised; the generic substitution cannot be delegated soundly.",
)
register_refusal_code(
    "delegation_protected_field_dependency",
    "The subclass depends on a protected field of the superclass that delegation cannot expose.",
)
register_refusal_code(
    "delegation_super_call_hazard", "A super.* call cannot be rewritten as a delegate call soundly."
)
register_refusal_code(
    "delegation_constructor_hazard",
    "The constructor wiring cannot be rewritten to construct the delegate soundly.",
)
register_refusal_code(
    "replace_inheritance_public_api_change",
    "Severing the superclass drops it from the type's public API; confirm the public-API change to proceed.",
)
register_refusal_code(
    "replace_inheritance_protected_member_dependency",
    "The subclass depends on a protected superclass member that a delegate instance cannot expose.",
)

# -- extract class / extract superclass (formerly extract.models) -----------------------------------
register_refusal_code(
    "extract_type_not_found",
    "The declaring type for the extract operation was not found in the project graph.",
)
register_refusal_code(
    "member_not_found", "A requested member does not match a member on the declaring type."
)
register_refusal_code(
    "no_members", "No members were selected (extract class/superclass requires at least one member selector)."
)
register_refusal_code(
    "extract_generic_type", "The declaring type declares type parameters; extraction could change type inference."
)
register_refusal_code(
    "extract_generic_member", "A selected member declares its own type parameters; extraction is unsupported."
)
register_refusal_code(
    "extract_synchronized_member", "A selected member is synchronized; moving it would change its monitor."
)
register_refusal_code("extract_native_member", "A selected member is native and cannot be relocated.")
register_refusal_code(
    "extract_super_reference", "A selected member references super; extraction would rebind it."
)
register_refusal_code(
    "extract_static_member", "A selected member is static in a way that cannot be relocated soundly."
)
register_refusal_code(
    "extract_retained_state_dependency",
    "An extracted member depends on state retained by the original type.",
)
register_refusal_code(
    "extract_moved_back_reference",
    "An extracted member is referenced back from the original type in a way that cannot be delegated.",
)
register_refusal_code("extract_constructor_member", "A constructor cannot be extracted.")
register_refusal_code(
    "extract_unsupported_member", "A selected member is of an unsupported kind for extraction."
)
register_refusal_code(
    "extract_name_collision", "The extracted type name collides with an existing type."
)
register_refusal_code(
    "extract_existing_superclass",
    "The type already extends a non-Object superclass; a new superclass cannot be inserted.",
)
register_refusal_code(
    "extract_abstract_pull",
    "Pulling the member up would require an abstract declaration that cannot be synthesised soundly.",
)

# -- deep inline method (formerly inline.models) ---------------------------------------------------
register_refusal_code(
    "inline_type_not_found", "The declaring type for the inline target was not found in the project graph."
)
register_refusal_code(
    "inline_method_not_found", "No method with the given name exists on the declaring type."
)
register_refusal_code(
    "inline_overloaded", "The method is overloaded; an unambiguous single target is required to inline."
)
register_refusal_code(
    "not_private", "V3 inline method supports private methods only; the target method is not private."
)
register_refusal_code("inline_no_body", "The method has no inlinable body.")
register_refusal_code(
    "inline_generic_method", "The method declares type parameters; inlining could change type inference."
)
register_refusal_code(
    "inline_loop_hazard", "The method body contains a loop that cannot be lifted into an expression context."
)
register_refusal_code(
    "inline_yield_hazard", "The method body uses a switch-expression yield; inlining is unsupported."
)
register_refusal_code(
    "inline_super_hazard", "The method body references super; a call site would rebind it."
)
register_refusal_code(
    "inline_early_return_hazard", "The method body returns early; lifting it would change control flow."
)
register_refusal_code(
    "inline_checked_exception_hazard",
    "The method declares checked exceptions; inlining could change exception flow.",
)
register_refusal_code(
    "inline_expression_context_hazard",
    "A multi-statement non-void body cannot be lifted into an expression call site.",
)
register_refusal_code("inline_complex_body_hazard", "The method body is too complex to inline soundly.")
register_refusal_code(
    "inline_qualified_call_hazard",
    "A call site has a non-this receiver; the body cannot be relocated soundly.",
)
register_refusal_code("inline_recursion_hazard", "The method is recursive; inlining would not terminate.")
register_refusal_code(
    "inline_arg_duplication_hazard",
    "An argument with side effects would be duplicated; inlining is refused.",
)
register_refusal_code(
    "inline_arity_mismatch", "A call site's argument count does not match the method parameters."
)
register_refusal_code("inline_no_call_sites", "The method has no call sites to inline.")

# -- semantic recipe engine (formerly recipes.models) ----------------------------------------------
register_refusal_code(
    "recipe_invalid", "The recipe document is not a well-formed object with a name and a list of rules."
)
register_refusal_code("recipe_no_rules", "The recipe declares no rules.")
register_refusal_code(
    "recipe_unknown_rule_kind", "A rule declares a kind the engine does not support."
)
register_refusal_code(
    "recipe_rule_missing_field", "A rule is missing a parameter its kind requires."
)
register_refusal_code(
    "recipe_not_found", "No built-in recipe is registered under the requested id."
)
register_refusal_code(
    "recipe_no_matches", "The recipe matched nothing in the project, so there is nothing to apply."
)
register_refusal_code(
    "recipe_overlapping_edits",
    "Two recipe rules produce overlapping edits in one file; the whole apply is refused rather than "
    "silently applying a subset.",
)

# -- live sidecar codes with no dead-planner analog (javac validation / SPI scans / scan inputs) ----
register_refusal_code(
    "new_compiler_errors",
    "The refactor would introduce new javac ERROR diagnostics; it is refused rather than committing a build break.",
)
register_refusal_code("invalid_min_confidence", "min_confidence must be one of 'high', 'medium', or 'low'.")
register_refusal_code(
    "resource_target_unresolved",
    "A non-empty resource target (fully-qualified class or package name) is required and could not be resolved.",
)
register_refusal_code(
    "framework_target_unresolved",
    "The framework reference-scan target could not be resolved (e.g. the project has no Java sources to scan).",
)
register_refusal_code(
    "recipe_review_required",
    "A recipe apply matched REVIEW_REQUIRED findings and needs explicit approval before any write.",
)
register_refusal_code(
    "recipe_refused_match",
    "A recipe apply matched at least one REFUSED finding and cannot be partially applied.",
)
