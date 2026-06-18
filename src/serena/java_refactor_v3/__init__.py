"""Java refactor engine V3 — whole-repo transformation platform.

This package layers package/module refactoring, propagating safe delete, extract class/superclass,
generalized inline, lambda conversions, API-migration recipes, resource-aware refactoring, and
agent-facing impact reports on top of the V1 compiler-backed sidecar and the V2 refactor-session
protocol. Every V3 tool is preview-first, returns structured refusal reasons, classifies risk
(SAFE / REVIEW_REQUIRED / REFUSED), summarizes file/resource impact, applies transactionally, guards
the project revision, validates with javac, and carries no JetBrains dependency.

The :class:`~serena.java_refactor_v3.workspace.TransformationWorkspaceManager` is the entry point: it
groups multiple V2 refactor sessions under a single revision-guarded workspace and drives
workspace-level preview/apply/cancel through Serena's transactional workspace-edit applier.
"""

from serena.java_refactor_v3.class_refactor_client import ClassRefactorClient
from serena.java_refactor_v3.conversions_client import ConversionsClient
from serena.java_refactor_v3.framework_spi_client import FrameworkSpiClient
from serena.java_refactor_v3.graph_client import GraphClient, GraphRefused, parse_project_graph
from serena.java_refactor_v3.inline_refactor_client import InlineRefactorClient
from serena.java_refactor_v3.models import (
    V3_REFUSAL_REGISTRY,
    FileChangeKind,
    FileEditStat,
    ImpactReport,
    RiskLevel,
    TransformationRefusal,
    WorkspaceStats,
    WorkspaceStatus,
    register_refusal_code,
)
from serena.java_refactor_v3.recipe_engine_client import RecipeEngineClient
from serena.java_refactor_v3.resource_spi_client import ResourceSpiClient
from serena.java_refactor_v3.transformation_client import TransformationClient
from serena.java_refactor_v3.workspace import (
    SessionDriver,
    TransformationSessionRef,
    TransformationWorkspace,
    TransformationWorkspaceManager,
    V3OperationPlan,
)

__all__ = [
    "V3_REFUSAL_REGISTRY",
    "ClassRefactorClient",
    "ConversionsClient",
    "FileChangeKind",
    "FileEditStat",
    "FrameworkSpiClient",
    "GraphClient",
    "GraphRefused",
    "ImpactReport",
    "InlineRefactorClient",
    "RecipeEngineClient",
    "ResourceSpiClient",
    "RiskLevel",
    "SessionDriver",
    "TransformationClient",
    "TransformationRefusal",
    "TransformationSessionRef",
    "TransformationWorkspace",
    "TransformationWorkspaceManager",
    "V3OperationPlan",
    "WorkspaceStats",
    "WorkspaceStatus",
    "parse_project_graph",
    "register_refusal_code",
]
