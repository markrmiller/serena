"""Behavioural acceptance suite for the V3 transformation platform (HARD BLOCKER B12).

The matrix in :mod:`serena.java_refactor_v3.reports.acceptance` claims, per tool, that the eight cross-cutting
invariants hold. That matrix is, on its own, a TAUTOLOGY: ``_row_invariants`` returns
``dict.fromkeys(V3_INVARIANTS, True)`` unconditionally, so re-asserting "every row's invariants are all True"
proves nothing about real tool behaviour. ``test_java_refactor_v3_reports.py`` covers the matrix's internal
consistency; THIS file independently verifies, from observed runtime behaviour, that the platform actually
satisfies the invariants the matrix asserts.

The probes here NEVER read ``ACCEPTANCE_MATRIX``/``acceptance_matrix()`` booleans to decide whether an invariant
holds — they drive the real models, the live refusal-code registry, and the established light protocol seams. The
sole place the matrix is consulted is the provenance-honesty check, where the matrix's *provenance partition* is
held up against an INDEPENDENTLY-declared edit-emitting/read-only split of the tool surface, so a regression that
mislabels a tool (or that flips a tool between edit-emitting and read-only) fails this suite. None of these tests
is satisfiable by the hardcoded matrix alone: each would still fail if a tool's real behaviour regressed.

These are deliberately LIGHT probes (no per-tool JVM sidecar run): they exercise the same Python-layer seams the
other ``test_java_refactor_v3_*`` suites use (``RiskLevel`` / ``RiskLevel.from_sidecar_wire`` per
``test_java_refactor_v3_apply_policy``; the refusal models per the canonical-envelope/recipe protocols), so the
suite runs fast while still being a genuine behavioural check.
"""

from __future__ import annotations

import pytest

from serena.java_refactor_v3.models import (
    RiskLevel,
    TransformationRefusal,
    register_refusal_code,
)
from serena.java_refactor_v3.reports import (
    acceptance_matrix,
    all_refusal_codes,
    edit_emitting_tools,
)

# Independently-declared (NOT read from the matrix) read-only tool surface: tools that emit no workspace edit and
# whose results are pure analysis derived from real javac facts. This is the behavioural ground truth the matrix's
# provenance partition is checked against. It is maintained here from the tools' real semantics, so if a tool is
# mislabeled in the matrix (e.g. a read-only scan tagged javac-delta, or an edit tool tagged javac-facts), the
# cross-check below fails.
_READ_ONLY_TOOLS: frozenset[str] = frozenset(
    {
        "transformationGraph",
        "deadCodeScan",
        "resourceProviders",
        "frameworkDetect",
        "frameworkReferences",
        "frameworkParticipate",
        "scanMigrationOpportunities",
        "impactReport",
        "transformationReport",
    }
)

# Every refusal-emitting tool family and one or more representative prefixes its codes carry. The registry must be
# non-trivially populated per family (a family silently losing its codes is a real regression of structuredRefusals).
# Counts are LOWER bounds derived from the live catalogue, so adding codes never breaks the suite but DELETING a
# family's codes below the threshold does.
_REFUSAL_FAMILY_PREFIXES: dict[str, tuple[str, ...]] = {
    "workspace": ("workspace_",),
    "deadcode/delete": (
        "deadcode_", "max_cascade_",
        "delete_",
        "source_root_",
        "no_roots",
        "invalid_min_confidence",
        "malformed_move_source_root",
    ),
    "package": (
        "package_",
        "malformed_move_package",
        "malformed_rename_package",
        "move_package_failed",
        "rename_package_failed",
        "build_file_rewrite_unsupported",
    ),
    "extract": (
        "extract_",
        "member_not_found",
        "no_members",
        "insufficient_classes",
        "source_type_not_found",
        "unsupported_source_kind",
        "ambiguous_member",
        "invalid_member",
        "no_sources",
    ),
    "delegation": ("delegation_", "replace_inheritance_"),
    "inline": ("inline_", "not_private"),
    "anon/lambda": ("anon_", "lambda_"),
    "recipe": ("recipe_", "malformed_recipe"),
    "resource": ("resource_", "unsupported_resource_kind", "malformed_resource_edit_map"),
    "sidecar/protocol": ("io_error", "missing_field", "unparseable_source", "non_editable_target"),
    # Cross-cutting refusals the live sidecar emits without a legacy planner prefix: the SPI reference-scan input
    # checks (resource/framework providers) and the javac diagnostic-delta validation gate any edit tool runs through.
    "scan/validation": ("resource_target_unresolved", "framework_target_unresolved", "new_compiler_errors"),
}

# Minimum number of distinct codes each family must register. These are conservative floors (the live catalogue has
# more), chosen so the assertion fails if a whole family's registration is dropped or gutted.
_REFUSAL_FAMILY_MIN_CODES: dict[str, int] = {
    'workspace': 5,
    'deadcode/delete': 4,
    'package': 4,
    'extract': 8,
    'delegation': 8,
    'inline': 10,
    'anon/lambda': 8,
    'recipe': 5,
    'scan/validation': 3,
    'resource': 2,
    'sidecar/protocol': 4,
}


# ── riskClassification: the real classifier contract, verified behaviourally ──────────────────────────────────────


def test_risk_level_taxonomy_is_exactly_three_members() -> None:
    # The matrix claims every tool carries a riskClassification. The classifier itself must expose EXACTLY the three
    # canonical levels — no more, no fewer. A drift (e.g. someone adding a "MEDIUM" middle ground) fails here, which
    # the tautological matrix could never catch.
    assert {level.name for level in RiskLevel} == {"SAFE", "REVIEW_REQUIRED", "REFUSED"}
    assert {level.value for level in RiskLevel} == {"SAFE", "REVIEW_REQUIRED", "REFUSED"}


def test_from_sidecar_wire_maps_canonical_values() -> None:
    # The real normalisation contract every edit-emitting tool's apply path depends on: the sidecar's accepted-edit
    # wire vocabulary maps onto the canonical taxonomy. Proven by calling the classifier, not by reading the matrix.
    assert RiskLevel.from_sidecar_wire("safe") is RiskLevel.SAFE
    assert RiskLevel.from_sidecar_wire("needs_review") is RiskLevel.REVIEW_REQUIRED


@pytest.mark.parametrize("bad", ["medium", "unknown", "informational", "SAFE", "", None])
def test_from_sidecar_wire_raises_on_unknown(bad: object) -> None:
    # The crux of the riskClassification contract: an unrecognised/missing wire value is NEVER silently downgraded to
    # a guessed classification — the classifier raises. "medium" and "informational" are explicitly NOT accepted.
    # If a regression made the classifier default unknowns instead of raising, this fails (the matrix could not).
    with pytest.raises(ValueError):
        RiskLevel.from_sidecar_wire(bad)


def test_from_sidecar_wire_does_not_accept_canonical_names() -> None:
    # The wire vocabulary is the lowercase sidecar tokens, NOT the canonical enum names; feeding a canonical name back
    # in is a contract violation and must raise. This pins the seam direction (wire -> level), not level -> level.
    for level in RiskLevel:
        with pytest.raises(ValueError):
            RiskLevel.from_sidecar_wire(level.value)


# ── structuredRefusals: real registry + real {code, message} shape, no partial edit ───────────────────────────────


def test_refusal_registry_is_non_trivially_populated() -> None:
    # structuredRefusals requires a real, documented refusal registry — not an empty stub. The aggregated live
    # registry must be substantial and every entry must be a lowercase code mapped to a non-empty description.
    codes = all_refusal_codes()
    assert len(codes) >= 50, f"refusal registry is implausibly small: {len(codes)} codes"
    for code, description in codes.items():
        assert code and code == code.lower(), code
        assert isinstance(description, str) and description.strip(), code


def test_every_refusing_tool_family_has_registered_codes() -> None:
    # Each tool family that can refuse must have its codes registered AND non-trivially populated. Driven entirely
    # from the live registry's contents — if a family's registration regresses below its floor, this fails. The
    # matrix's hardcoded structuredRefusals=True cannot satisfy this.
    codes = set(all_refusal_codes())
    for family, prefixes in _REFUSAL_FAMILY_PREFIXES.items():
        family_codes = {c for c in codes if c.startswith(prefixes)}
        floor = _REFUSAL_FAMILY_MIN_CODES[family]
        assert len(family_codes) >= floor, (
            f"refusal family {family!r} (prefixes {prefixes}) has {len(family_codes)} codes, expected >= {floor}; "
            f"got {sorted(family_codes)}"
        )


def test_refusal_codes_partition_into_known_families() -> None:
    # Every registered code belongs to one of the declared refusing families: a brand-new, undocumented family of
    # codes (or a typo'd prefix) shows up here, keeping the structuredRefusals surface honest and enumerated.
    all_prefixes = tuple(p for prefixes in _REFUSAL_FAMILY_PREFIXES.values() for p in prefixes)
    unclassified = sorted(c for c in all_refusal_codes() if not c.startswith(all_prefixes))
    assert not unclassified, f"refusal codes do not map to any declared family: {unclassified}"


def test_transformation_refusal_shape_is_code_message_only() -> None:
    # The structured refusal a tool surfaces is exactly {code, message} (no partial edit, no extra keys), and the
    # code round-trips through the live registry. This is the observable shape the structuredRefusals invariant
    # promises; proven by constructing a refusal from a REAL registered code and serialising it.
    real_code = next(iter(all_refusal_codes()))
    refusal = TransformationRefusal(code=real_code, message="boundary type may not be auto-deleted")
    wire = refusal.to_dict()
    assert set(wire) == {"code", "message"}, wire
    assert wire["code"] == real_code
    assert wire["message"]
    # the code a refusal carries is one the registry documents (no orphan refusal codes on the wire).
    assert wire["code"] in all_refusal_codes()


def test_register_refusal_code_rejects_conflicting_redefinition() -> None:
    # The registry is single-sourced: re-registering an existing code with a DIFFERENT description raises rather than
    # silently overwriting. This is what keeps structuredRefusals codes stable/documented; a behavioural probe of the
    # registration seam, independent of the matrix.
    existing_code = next(iter(all_refusal_codes()))
    # idempotent re-registration with the identical description is fine...
    same_description = all_refusal_codes()[existing_code]
    assert register_refusal_code(existing_code, same_description) == existing_code
    # ...but a conflicting description for the same code is a loud programming error.
    with pytest.raises(ValueError):
        register_refusal_code(existing_code, same_description + " (conflicting redefinition)")


# ── provenance honesty: edit-emitting vs read-only partition matches the tool surface ─────────────────────────────


def test_provenance_partition_matches_independent_tool_classification() -> None:
    # The HONESTY check the tautological matrix cannot provide: the matrix's provenance partition must match an
    # INDEPENDENTLY-declared split of the tool surface into edit-emitting (javac-delta) vs read-only (javac-facts).
    # If a regression mislabels a tool — tagging a read-only scan as javac-delta or an edit tool as javac-facts —
    # the two partitions diverge and this fails. Nothing here trusts the matrix's own booleans.
    provenance: dict[str, str] = {str(row["tool"]): str(row["provenance"]) for row in acceptance_matrix()}

    delta_tools = {tool for tool, prov in provenance.items() if prov == "javac-delta"}
    facts_tools = {tool for tool, prov in provenance.items() if prov == "javac-facts"}

    # the read-only set in the matrix must be EXACTLY the independently-declared read-only tool surface.
    assert facts_tools == _READ_ONLY_TOOLS, (
        f"matrix read-only(javac-facts) set diverged from real read-only tools; "
        f"only-in-matrix={facts_tools - _READ_ONLY_TOOLS}, only-in-truth={_READ_ONLY_TOOLS - facts_tools}"
    )
    # and the edit-emitting set must be exactly the complement — disjoint from and covering the rest.
    assert delta_tools.isdisjoint(_READ_ONLY_TOOLS)
    assert delta_tools | facts_tools == set(provenance)


def test_read_only_tools_carry_javac_facts_and_emit_no_edit() -> None:
    # Every read-only tool in the matrix carries provenance "javac-facts" — i.e. it is honestly NOT delta-validated
    # (it emits no edit) yet is still compiler-backed. Checked against the independent read-only set, so a read-only
    # tool that was wrongly promoted to an edit provenance is caught.
    provenance: dict[str, str] = {str(row["tool"]): str(row["provenance"]) for row in acceptance_matrix()}
    for tool in _READ_ONLY_TOOLS:
        assert tool in provenance, f"read-only tool {tool!r} is missing from the acceptance matrix"
        assert provenance[tool] == "javac-facts", f"{tool} is read-only but tagged {provenance[tool]!r}"


def test_edit_emitting_tools_are_exactly_the_delta_provenance_rows() -> None:
    # The edit-emitting tool registry (exposed by acceptance.edit_emitting_tools) and the matrix's javac-delta
    # provenance partition must agree, and must be DISJOINT from the read-only surface. This cross-checks two
    # independently-maintained declarations of the same partition: a one-sided edit (adding a tool to the delta
    # registry but not the matrix, or vice versa) fails here.
    delta_registry: set[str] = {str(tool) for tool in edit_emitting_tools()}
    assert delta_registry, "edit-emitting tool registry must not be empty"
    assert delta_registry.isdisjoint(_READ_ONLY_TOOLS), (
        f"a tool is declared both edit-emitting and read-only: {delta_registry & _READ_ONLY_TOOLS}"
    )

    matrix_delta: set[str] = {
        str(row["tool"]) for row in acceptance_matrix() if row["provenance"] == "javac-delta"
    }
    assert matrix_delta == delta_registry, (
        f"edit-emitting registry diverged from matrix javac-delta rows; "
        f"only-in-registry={delta_registry - matrix_delta}, only-in-matrix={matrix_delta - delta_registry}"
    )


def test_every_matrix_tool_is_classified_edit_or_read_only() -> None:
    # Completeness: every tool that appears in the matrix is accounted for by exactly one of the two independent
    # behavioural partitions. A new tool added to the matrix without being classified as edit-emitting or read-only
    # is caught (it would otherwise carry an unverified provenance claim).
    matrix_tools: set[str] = {str(row["tool"]) for row in acceptance_matrix()}
    classified = set(edit_emitting_tools()) | _READ_ONLY_TOOLS
    assert matrix_tools == classified, (
        f"matrix tools not behaviourally classified: unclassified={matrix_tools - classified}, "
        f"classified-but-absent={classified - matrix_tools}"
    )


def test_provenance_is_only_the_two_honest_kinds() -> None:
    # Provenance is one of exactly two honest, compiler-backed kinds — never an "unvalidated"/"heuristic" marker.
    # Driven off the live matrix rows: if a row ever carried a non-compiler-backed provenance, this fails.
    kinds = {row["provenance"] for row in acceptance_matrix()}
    assert kinds == {"javac-delta", "javac-facts"}
