"""Planned V3 transformation model module.

Compatibility façade for refactor-feature-plan-V3.md callers.  The concrete
models live in :mod:`serena.java_refactor_v3.models` and workspace helpers;
this module preserves the planned import surface without duplicating logic.
"""

from serena.java_refactor_v3.models import (
    FileChangeKind,
    FileEditStat,
    ImpactReport,
    RiskLevel,
    TransformationRefusal,
    WorkspaceStats,
    WorkspaceStatus,
)
from serena.java_refactor_v3.workspace import (
    TransformationSessionRef,
    TransformationWorkspace,
    V3OperationPlan,
)

__all__ = [
    "FileChangeKind",
    "FileEditStat",
    "ImpactReport",
    "RiskLevel",
    "TransformationRefusal",
    "TransformationSessionRef",
    "TransformationWorkspace",
    "V3OperationPlan",
    "WorkspaceStats",
    "WorkspaceStatus",
]
