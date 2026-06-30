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
WORKSPACE_REVIEW_REQUIRED = register_refusal_code(
    "workspace_review_required",
    "The composed workspace edit is REVIEW_REQUIRED and needs explicit approval before apply.",
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


class V3ImpactReportDict(dict):
    """V3 five-section report with non-enumerated legacy aliases.

    The public serialized shape is exactly the V3 contract.  Legacy
    in-process callers may still look up java/resources/api/risk aliases,
    but those aliases do not appear in keys(), iteration, items(), or JSON
    serialization because the underlying dict contains only V3 sections.
    """

    _V3_KEYS = ("summary", "semanticImpact", "resourceImpact", "tests", "warnings")

    def __init__(self, sections: dict[str, Any], aliases: dict[str, Any] | None = None) -> None:
        super().__init__({key: sections[key] for key in self._V3_KEYS})
        self._aliases = dict(aliases or {})

    def __getitem__(self, key: str) -> Any:
        if key in self._aliases:
            return self._aliases[key]
        return super().__getitem__(key)

    def get(self, key: str, default: Any = None) -> Any:
        if key in self._aliases:
            return self._aliases[key]
        return super().get(key, default)

    def __contains__(self, key: object) -> bool:
        return key in self._aliases or super().__contains__(key)

    def legacy_aliases(self) -> dict[str, Any]:
        """Return a copy of non-serialized compatibility aliases."""
        return dict(self._aliases)


class ImpactReport:
    """Public V3 impact report adapter.

    The V3 public contract serializes exactly five sections:
    summary, semanticImpact, resourceImpact, tests, and warnings.  The
    pre-V3 Python builder still computes legacy java/resources/api/risk
    sections; accept those sections and expose them as non-enumerated aliases
    for in-process compatibility.
    """

    def __init__(
        self,
        summary: dict[str, Any] | None = None,
        semanticImpact: dict[str, Any] | None = None,
        resourceImpact: dict[str, Any] | None = None,
        tests: dict[str, Any] | None = None,
        warnings: list[Any] | None = None,
        *,
        java: dict[str, Any] | None = None,
        resources: dict[str, Any] | None = None,
        api: dict[str, Any] | None = None,
        risk: dict[str, Any] | RiskLevel | str | None = None,
    ) -> None:
        self.summary = dict(summary or {})
        self.semanticImpact = dict(semanticImpact or {})
        self.resourceImpact = dict(resourceImpact or {})
        self.tests = dict(tests or {})
        self.warnings = list(warnings or [])
        self.java = dict(java or {})
        self.resources = dict(resources or {})
        self.api = dict(api or {})
        self.risk = risk

    @staticmethod
    def _risk_value(value: dict[str, Any] | RiskLevel | str | None) -> str:
        if isinstance(value, RiskLevel):
            return value.value
        if isinstance(value, dict):
            raw = value.get("level", value.get("risk", RiskLevel.SAFE.value))
            return raw.value if isinstance(raw, RiskLevel) else str(raw)
        if value is None:
            return RiskLevel.SAFE.value
        return value.value if isinstance(value, RiskLevel) else str(value)

    @staticmethod
    def _file_entry(value: Any) -> dict[str, Any]:
        entry: dict[str, Any]
        if isinstance(value, dict):
            entry = dict(value)
        else:
            entry = {"path": str(value)}
        if "path" not in entry:
            entry["path"] = entry.get("relativePath") or entry.get("newRelativePath") or entry.get("newPath") or entry.get("file")
        if "relativePath" not in entry and entry.get("path") is not None:
            entry["relativePath"] = entry["path"]
        if "referencedTypes" not in entry:
            entry["referencedTypes"] = entry.get("touchedTypes", entry.get("types", []))
        if entry.get("sourcePath") is None:
            source = entry.get("oldPath") or entry.get("source") or entry.get("from")
            if source is not None:
                entry["sourcePath"] = source
        return entry

    @classmethod
    def _file_entries(cls, section: dict[str, Any], *keys: str) -> list[dict[str, Any]]:
        for key in keys:
            value = section.get(key)
            if isinstance(value, list):
                return [cls._file_entry(item) for item in value]
        return []

    def to_dict(self) -> V3ImpactReportDict:
        legacy_java = dict(self.java)
        legacy_resources = dict(self.resources)
        legacy_api = dict(self.api)
        risk_value = self._risk_value(self.risk)
        risk_dict = dict(self.risk) if isinstance(self.risk, dict) else {}
        risk_dict.setdefault("level", risk_value)
        risk_dict.setdefault("risk", risk_value)

        java_files = self._file_entries(
            legacy_java,
            "files",
            "changedFiles",
            "filesChanged",
            "touchedFiles",
            "javaFiles",
        )
        resource_files = self._file_entries(
            legacy_resources,
            "files",
            "changedFiles",
            "resources",
            "resourceFiles",
            "resourcesTouched",
        )
        test_files = self._file_entries(self.tests, "files", "touchedTestFiles", "tests", "testFiles")

        legacy_java["files"] = java_files
        legacy_java["fileCount"] = legacy_java.get("fileCount", legacy_java.get("filesChanged", len(java_files)))
        legacy_resources["files"] = resource_files
        legacy_resources["fileCount"] = legacy_resources.get(
            "fileCount", legacy_resources.get("resourcesTouched", legacy_resources.get("filesChanged", len(resource_files)))
        )
        legacy_api.setdefault("publicApiChanged", bool(legacy_api.get("publicApiChanges") or legacy_api.get("changed")))

        summary = dict(self.summary)
        def _count(value: object) -> int:
            if isinstance(value, int):
                return value
            if isinstance(value, (list, tuple, set, frozenset)):
                return len(value)
            return 0

        summary.setdefault(
            "filesChanged",
            [
                *list(legacy_java.get("filesChanged") or legacy_java.get("changedFiles") or ()),
                *list(legacy_resources.get("files") or legacy_resources.get("resourceFiles") or ()),
            ],
        )
        summary.setdefault("changedFileCount", _count(legacy_java.get("fileCount")) + _count(legacy_resources.get("fileCount")))
        summary.setdefault("javaFilesChanged", legacy_java.get("fileCount", 0))
        summary.setdefault("resourceFilesChanged", legacy_resources.get("fileCount", 0))
        summary.setdefault("testsTouched", self.tests.get("touchedTestCount", len(test_files)))
        summary.setdefault("risk", risk_value)
        if risk_dict.get("operation") is not None:
            summary.setdefault("operation", risk_dict["operation"])

        semantic = dict(self.semanticImpact)
        semantic.setdefault("java", legacy_java)
        semantic.setdefault("api", legacy_api)
        semantic.setdefault("risk", risk_dict)
        semantic.setdefault("files", java_files)
        semantic.setdefault("publicApiChanged", legacy_api.get("publicApiChanged", False))

        resource = dict(self.resourceImpact)
        resource.setdefault("resources", legacy_resources)
        resource.setdefault("files", resource_files)

        tests = dict(self.tests)
        tests.setdefault("files", test_files)

        warnings = list(self.warnings)
        warnings.extend(str(w) for w in legacy_java.get("warnings", []) if str(w) not in warnings)
        warnings.extend(str(w) for w in legacy_resources.get("warnings", []) if str(w) not in warnings)
        warnings.extend(str(w) for w in risk_dict.get("warnings", []) if str(w) not in warnings)

        sections = {
            "summary": summary,
            "semanticImpact": semantic,
            "resourceImpact": resource,
            "tests": tests,
            "warnings": warnings,
        }
        aliases = {
            "java": legacy_java,
            "resources": legacy_resources,
            "api": legacy_api,
            "risk": risk_dict,
        }
        return V3ImpactReportDict(sections, aliases)
