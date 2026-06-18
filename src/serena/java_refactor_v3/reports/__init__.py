"""Whole-repo impact reports and the V3 acceptance matrix (G011)."""

from __future__ import annotations

from serena.java_refactor_v3.reports.acceptance import (
    ACCEPTANCE_MATRIX,
    V3_INVARIANTS,
    acceptance_matrix,
    all_refusal_codes,
    edit_emitting_tools,
    jetbrains_references,
    tool_invariant_evidence,
)
from serena.java_refactor_v3.reports.impact import ImpactReportBuilder

__all__ = [
    "ACCEPTANCE_MATRIX",
    "V3_INVARIANTS",
    "ImpactReportBuilder",
    "acceptance_matrix",
    "all_refusal_codes",
    "edit_emitting_tools",
    "jetbrains_references",
    "tool_invariant_evidence",
]
