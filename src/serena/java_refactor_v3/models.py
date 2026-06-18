"""Shared value objects for the V3 transformation platform.

This module holds the cross-cutting models every V3 tool emits: the risk classification, the structured
refusal type plus a central refusal-code registry (so every code is documented in one place per G011),
the per-file/edit statistics, and the agent-facing impact report.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Any


class RiskLevel(enum.Enum):
    """Risk classification attached to every V3 transformation result.

    ``SAFE`` edits pass validation and touch no public/framework boundary; ``REVIEW_REQUIRED`` edits are
    applicable but cross a boundary or rely on a heuristic a human should confirm; ``REFUSED`` results
    carry a :class:`TransformationRefusal` and produce no edit.
    """

    SAFE = "SAFE"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"
    REFUSED = "REFUSED"

    @classmethod
    def from_sidecar_wire(cls, wire_risk: object) -> "RiskLevel":
        """Maps the sidecar's accepted-edit wire risk (``"safe"``/``"needs_review"``) onto the canonical taxonomy.

        This is the single normalisation seam for the apply path: an accepted V3 edit payload always carries a risk
        the sidecar's :class:`CanonicalEnvelope` computed (composing resource confidence, framework participation
        vetoes/warnings, deletions, and the javac diagnostic delta). An unrecognised or missing value is a contract
        violation between the sidecar and the bridge — never silently downgraded to a guessed "medium" — so this
        raises :class:`ValueError` rather than returning a default.

        ``"informational"`` (the read-only-scan marker the sidecar splices for findDeadCode /
        scanMigrationOpportunities / transformation.report) never gates an apply and never reaches the edit-apply
        bridge, so it is intentionally not accepted here.
        """
        mapped = _SIDECAR_WIRE_RISK_TO_LEVEL.get(str(wire_risk))
        if mapped is None:
            raise ValueError(
                f"unclassified V3 edit risk {wire_risk!r}: an accepted sidecar edit payload must carry an explicit "
                f"risk in {sorted(_SIDECAR_WIRE_RISK_TO_LEVEL)} (the apply bridge never defaults an unknown payload to "
                "a guessed classification)"
            )
        return mapped


# Canonical mapping from the sidecar's accepted-edit wire vocabulary (CanonicalEnvelope.classifyRisk emits ``"safe"``
# or ``"needs_review"``) onto the :class:`RiskLevel` taxonomy. Defined at module scope (NOT in the enum body, where it
# would be misread as a member) and deliberately WITHOUT any "medium" entry: the apply bridge classifies every
# edit-emitting payload from an explicit sidecar value and never falls back to a guessed middle ground.
_SIDECAR_WIRE_RISK_TO_LEVEL: dict[str, RiskLevel] = {
    "safe": RiskLevel.SAFE,
    "needs_review": RiskLevel.REVIEW_REQUIRED,
}


class WorkspaceStatus(enum.Enum):
    """Lifecycle state of a :class:`~serena.java_refactor_v3.workspace.TransformationWorkspace`."""

    # accepts new member sessions; nothing planned or applied yet
    OPEN = "open"
    # member sessions composed into a validated, not-yet-applied plan
    PREVIEWED = "previewed"
    # the composed plan was committed to disk transactionally
    APPLIED = "applied"
    # the caller cancelled the workspace and its member sessions were released
    CANCELLED = "cancelled"
    # the workspace was evicted by the manager (capacity/TTL) and its sessions released
    EVICTED = "evicted"
    # an apply attempt failed and rolled back; the workspace is terminal and must be recreated
    FAILED = "failed"

    def is_terminal(self) -> bool:
        """Whether no further composition/apply is possible on a workspace in this state."""
        return self in (WorkspaceStatus.APPLIED, WorkspaceStatus.CANCELLED, WorkspaceStatus.EVICTED, WorkspaceStatus.FAILED)


class FileChangeKind(enum.Enum):
    """Kind of change a planned edit makes to a single file."""

    MODIFY = "modify"
    CREATE = "create"
    DELETE = "delete"
    RENAME = "rename"


@dataclass(frozen=True)
class TransformationRefusal:
    """Structured reason a V3 transformation declined to produce or apply an edit.

    :ivar code: stable machine-readable code (registered in :data:`V3_REFUSAL_REGISTRY`).
    :ivar message: human-facing explanation, free-form and specific to the refused call.
    """

    code: str
    message: str

    def to_dict(self) -> dict[str, str]:
        """The wire shape Serena tools surface under a result's ``refusal`` key."""
        return {"code": self.code, "message": self.message}


# central registry of every V3 refusal code -> one-line description (G011: document every refusal code).
V3_REFUSAL_REGISTRY: dict[str, str] = {}


def register_refusal_code(code: str, description: str) -> str:
    """Registers a refusal ``code`` with its documentation, returning the code for inline use.

    Re-registering the same code with the same description is idempotent; a conflicting description for
    an already-registered code is a programming error and raises, so the registry stays single-sourced.
    """
    existing = V3_REFUSAL_REGISTRY.get(code)
    if existing is not None and existing != description:
        raise ValueError(f"Refusal code {code!r} already registered with a different description.")
    V3_REFUSAL_REGISTRY[code] = description
    return code


# workspace-engine refusal codes (G001).
WORKSPACE_NOT_FOUND = register_refusal_code(
    "workspace_not_found", "The referenced transformation workspace id does not exist or was evicted."
)
WORKSPACE_TERMINAL = register_refusal_code(
    "workspace_terminal", "The workspace is in a terminal state (applied/cancelled/evicted/failed) and accepts no further operations."
)
WORKSPACE_EMPTY = register_refusal_code(
    "workspace_empty", "The workspace has no member sessions to preview or apply."
)
WORKSPACE_SESSION_NOT_ACCEPTED = register_refusal_code(
    "workspace_session_not_accepted", "A member V2 session was refused by the sidecar, so the workspace cannot compose it."
)
WORKSPACE_REVISION_MISMATCH = register_refusal_code(
    "workspace_revision_mismatch", "A member session was planned against a different project revision than the workspace pins."
)
WORKSPACE_SESSION_FILE_CONFLICT = register_refusal_code(
    "workspace_session_file_conflict", "Two member sessions edit the same file; independently-planned overlapping edits cannot be composed safely."
)
WORKSPACE_UNSAFE_EDIT = register_refusal_code(
    "workspace_unsafe_edit", "A member session's planned edit could not be staged exactly (hash/precondition/offset mismatch)."
)
WORKSPACE_APPLY_FAILED = register_refusal_code(
    "workspace_apply_failed", "The composed workspace edit failed to commit and was rolled back; no files were changed."
)


@dataclass
class FileEditStat:
    """Per-file impact summary for one file a workspace's composed edit touches.

    :ivar path: project-relative POSIX path of the affected file (the destination path for a rename).
    :ivar kind: the kind of change applied to the file.
    :ivar edit_count: number of distinct text replacements within the file (0 for whole-file operations).
    :ivar rename_source: original path when ``kind`` is :attr:`FileChangeKind.RENAME`, else ``None``.
    """

    path: str
    kind: FileChangeKind
    edit_count: int = 0
    rename_source: str | None = None

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {"path": self.path, "kind": self.kind.value, "editCount": self.edit_count}
        if self.rename_source is not None:
            data["renameSource"] = self.rename_source
        return data


@dataclass
class WorkspaceStats:
    """Aggregate file/edit statistics for a workspace's composed plan.

    :ivar files: per-file stats, sorted by path.
    :ivar edit_count: total number of text replacements across all files.
    """

    files: list[FileEditStat] = field(default_factory=list)
    edit_count: int = 0

    @property
    def file_count(self) -> int:
        """Number of distinct files the plan touches."""
        return len(self.files)

    def _count_kind(self, kind: FileChangeKind) -> int:
        return sum(1 for stat in self.files if stat.kind is kind)

    @property
    def modified_files(self) -> int:
        """Number of in-place-modified files."""
        return self._count_kind(FileChangeKind.MODIFY)

    @property
    def created_files(self) -> int:
        """Number of created files."""
        return self._count_kind(FileChangeKind.CREATE)

    @property
    def deleted_files(self) -> int:
        """Number of deleted files."""
        return self._count_kind(FileChangeKind.DELETE)

    @property
    def renamed_files(self) -> int:
        """Number of renamed/moved files."""
        return self._count_kind(FileChangeKind.RENAME)

    def to_dict(self) -> dict[str, Any]:
        return {
            "fileCount": self.file_count,
            "editCount": self.edit_count,
            "modifiedFiles": self.modified_files,
            "createdFiles": self.created_files,
            "deletedFiles": self.deleted_files,
            "renamedFiles": self.renamed_files,
            "files": [stat.to_dict() for stat in self.files],
        }


@dataclass
class ImpactReport:
    """Agent-facing impact summary for a transformation.

    The five sections map to the dimensions a reviewer cares about: Java sources, non-Java resources,
    the public-API boundary, tests, and an overall risk roll-up. Two producers fill it: the composed-edit
    projection in :func:`serena.java_refactor_v3.workspace._build_impact_report` (every section computed from
    the touched files alone — honest zero counts, never ``not_analyzed``) and the graph-backed
    :class:`serena.java_refactor_v3.reports.impact.ImpactReportBuilder` (cross-reference picture: wired
    resource providers, tests referencing a changed type, API boundary crossings). Both serialize identically.
    """

    java: dict[str, Any] = field(default_factory=dict)
    resources: dict[str, Any] = field(default_factory=dict)
    api: dict[str, Any] = field(default_factory=dict)
    tests: dict[str, Any] = field(default_factory=dict)
    risk: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {"java": self.java, "resources": self.resources, "api": self.api, "tests": self.tests, "risk": self.risk}
