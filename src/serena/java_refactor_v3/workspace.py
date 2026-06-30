"""Transformation workspace engine (G001).

A :class:`TransformationWorkspace` groups several V2 refactor sessions under one revision-guarded unit
and composes their planned edits into a single transactional apply. The
:class:`TransformationWorkspaceManager` owns the live workspaces, enforces capacity/TTL eviction, and
exposes the workspace-level preview/apply/cancel surface the V3 tools call.

The engine never re-plans semantics: each member session is planned by the sidecar against a pinned
project revision, and the workspace only *composes* the already-planned, hash-guarded edits. Because every
member's offsets are relative to the same pinned original, two members may share a file iff their text edits
occupy disjoint offset ranges in one encoding and neither performs a whole-file create/delete/rename on it;
genuinely overlapping or order-dependent edits are refused. A successful compose is thus an offset-disjoint
union whose hash preconditions are still verified by Serena's transactional applier before any byte is written.
"""

from __future__ import annotations

import time
import uuid
from collections import OrderedDict
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
from typing import Any, Protocol, cast, runtime_checkable

from serena.java_refactor.workspace_edit import (
    RefactorTextEdit,
    RefactorWorkspaceEdit,
    TransactionalWorkspaceEditApplier,
    WorkspaceEditError,
)
from serena.java_refactor_v3.models import (
    WORKSPACE_APPLY_FAILED,
    WORKSPACE_EMPTY,
    WORKSPACE_NOT_FOUND,
    WORKSPACE_REVISION_MISMATCH,
    WORKSPACE_SESSION_FILE_CONFLICT,
    WORKSPACE_SESSION_NOT_ACCEPTED,
    WORKSPACE_TERMINAL,
    WORKSPACE_UNSAFE_EDIT,
    WORKSPACE_REVIEW_REQUIRED,
    FileChangeKind,
    FileEditStat,
    ImpactReport,
    RiskLevel,
    TransformationRefusal,
    WorkspaceStats,
    WorkspaceStatus,
)


@dataclass
class V3OperationPlan:
    """A compute-only V3 op plan returned by :meth:`SessionDriver.plan_v3_operation` for workspace enrollment.

    Unlike a V2 session a V3 op has no live sidecar session to cancel: it is a pure, already-computed edit. The
    workspace enrolls this plan as a member carrying its parsed :class:`RefactorWorkspaceEdit` directly, so on cancel a
    V3 member is simply dropped (there is nothing to release in the sidecar).

    :ivar operation: the V3 operation name (e.g. ``extractClass``), retained for reporting/labelling.
    :ivar project_revision: the project revision the op was planned against (workspace pin source); may be ``None``.
    :ivar workspace_edit: the parsed, hash-guarded edit the sidecar computed compute-only.
    :ivar risk: the canonical risk the sidecar classified for this edit.
    :ivar warnings: the sidecar's warnings for this edit (raise the composed risk to REVIEW_REQUIRED).
    """

    operation: str
    project_revision: Any
    workspace_edit: RefactorWorkspaceEdit
    risk: RiskLevel
    warnings: list[str]


@runtime_checkable
class SessionDriver(Protocol):
    """Narrow abstraction the workspace engine needs from a V2/V3 refactor backend.

    The production :class:`~serena.java_refactor.manager.JavaRefactorManager` satisfies this protocol;
    tests provide a lightweight double. Keeping the surface minimal (create/cancel a V2 session, plan a V3 op, and
    mint a transactional applier) keeps the composition logic unit-testable without a live Java sidecar.
    """

    def create_v2_refactor_session(self, operation: str, params: dict[str, Any], validate: bool | None = None) -> dict[str, Any]:
        """Creates a revision-guarded V2 preview session and returns its envelope."""
        ...

    def cancel_v2_refactor_session(self, session_id: str) -> dict[str, Any]:
        """Cancels a live V2 session, releasing it in the sidecar."""
        ...

    def plan_v3_operation(self, operation: str, params: dict[str, Any]) -> "V3OperationPlan | dict[str, Any]":
        """Plans a V3 op compute-only, returning a :class:`V3OperationPlan` or a structured refusal dict.

        The plan carries the parsed :class:`RefactorWorkspaceEdit` (and its revision/risk/warnings) WITHOUT applying or
        javac-validating it; the workspace owns the single validation seam when it stages the composed plan. A sidecar
        refusal (or a malformed/unclassified edit) is returned verbatim as a refusal dict so it is never enrolled.
        """
        ...

    def new_workspace_edit_applier(self) -> TransactionalWorkspaceEditApplier:
        """Returns a fresh transactional applier bound to the project root/encoding/line-ending."""
        ...


def _session_id_of(envelope: dict[str, Any]) -> str | None:
    """Extracts the V2 ``sessionId`` from a create envelope, or ``None`` when absent/malformed."""
    session = envelope.get("session")
    if isinstance(session, dict):
        session_id = session.get("sessionId")
        if isinstance(session_id, str) and session_id:
            return session_id
    return None


def _project_revision_of(envelope: dict[str, Any]) -> Any:
    """Extracts the create-time project revision from a session envelope.

    The revision may surface at the envelope root or inside the ``session``/``preview`` sub-objects under
    a few historical key spellings; the first present value wins. ``None`` means the backend did not report
    a revision, in which case the workspace's revision guard stays permissive (it cannot enforce an
    unreported value) and relies on the applier's per-file hash preconditions instead.
    """
    candidate_keys = ("projectRevision", "createRevision", "revision")
    for container in (envelope, envelope.get("session"), envelope.get("preview")):
        if not isinstance(container, dict):
            continue
        for key in candidate_keys:
            if key in container and container[key] is not None:
                return container[key]
    return None


def _parse_edit(envelope: dict[str, Any]) -> RefactorWorkspaceEdit:
    """Parses the Serena workspace-edit out of a session envelope's ``preview.workspaceEdit``."""
    preview = envelope.get("preview")
    if not isinstance(preview, dict) or "workspaceEdit" not in preview:
        raise WorkspaceEditError("Session envelope is missing preview.workspaceEdit.")
    return RefactorWorkspaceEdit.from_protocol_dict(preview["workspaceEdit"])


@dataclass
class TransformationSessionRef:
    """A member enrolled in a workspace, with its planned edit cached for composition.

    A member is either a live V2 sidecar SESSION (``member_kind == "v2"``, identified by ``session_id``, released on
    cancel/apply) or a pure compute-only V3 OPERATION plan (``member_kind == "v3"``, identified by ``member_id``, simply
    DROPPED on cancel — there is no sidecar session to release). Both carry an already-planned, hash-guarded edit, and
    composition treats them identically (the same-file conflict and disjoint-union rules apply uniformly).

    :ivar operation: the operation name (e.g. ``renameMember`` / ``extractClass``), retained for reporting.
    :ivar project_revision: the project revision the member was planned against (workspace pin source).
    :ivar workspace_edit: the parsed, hash-guarded edit the member planned.
    :ivar member_kind: ``"v2"`` for a live sidecar session, ``"v3"`` for a compute-only op plan.
    :ivar session_id: the sidecar session id for a V2 member; ``None`` for a V3 member.
    :ivar member_id: a stable identifier for the member (the ``session_id`` for V2, a generated id for V3).
    :ivar applied: whether this member's plan has been committed as part of a workspace apply.
    """

    operation: str
    project_revision: Any
    workspace_edit: RefactorWorkspaceEdit
    member_kind: str = "v2"
    session_id: str | None = None
    member_id: str = ""
    applied: bool = False

    def __post_init__(self) -> None:
        if not self.member_id:
            self.member_id = self.session_id or uuid.uuid4().hex

    @property
    def is_v3(self) -> bool:
        """Whether this member is a compute-only V3 op plan (dropped, not cancelled)."""
        return self.member_kind == "v3"

    def summary(self) -> dict[str, Any]:
        """A compact, serializable summary of this member."""
        return {
            "memberKind": self.member_kind,
            "memberId": self.member_id,
            "sessionId": self.session_id,
            "operation": self.operation,
            "projectRevision": self.project_revision,
            "applied": self.applied,
            "touchedFiles": self.workspace_edit.touched_files(),
        }


@dataclass
class _Composition:
    """Outcome of composing a workspace's member edits — either a merged plan or a refusal."""

    refusal: TransformationRefusal | None = None
    merged_edit: RefactorWorkspaceEdit | None = None
    stats: WorkspaceStats | None = None
    risk: RiskLevel = RiskLevel.SAFE

    @property
    def ok(self) -> bool:
        return self.refusal is None


def _compute_stats(edit: RefactorWorkspaceEdit) -> WorkspaceStats:
    """Builds per-file/edit statistics from a composed workspace edit.

    File operations classify their files (create/delete/rename); remaining text-edited files are modifies.
    A rename carries its destination path and counts any in-place text edits planned under the old path.
    """
    # tally text edits per file
    edits_by_path: dict[str, int] = {}
    for text_edit in edit.text_edits:
        edits_by_path[text_edit.relative_path] = edits_by_path.get(text_edit.relative_path, 0) + 1

    # classify files touched by whole-file operations first
    files: list[FileEditStat] = []
    covered: set[str] = set()
    for operation in edit.file_operations:
        if operation.kind == "create":
            files.append(FileEditStat(operation.relative_path, FileChangeKind.CREATE, edits_by_path.get(operation.relative_path, 0)))
            covered.add(operation.relative_path)
        elif operation.kind == "delete":
            files.append(FileEditStat(operation.relative_path, FileChangeKind.DELETE, 0))
            covered.add(operation.relative_path)
        elif operation.kind == "rename":
            destination = operation.new_relative_path or operation.relative_path
            files.append(
                FileEditStat(destination, FileChangeKind.RENAME, edits_by_path.get(operation.relative_path, 0), rename_source=operation.relative_path)
            )
            covered.add(operation.relative_path)
            covered.add(destination)

    # remaining text-edited files are in-place modifications
    for path, count in edits_by_path.items():
        if path in covered:
            continue
        files.append(FileEditStat(path, FileChangeKind.MODIFY, count))
        covered.add(path)

    files.sort(key=lambda stat: stat.path)
    return WorkspaceStats(files=files, edit_count=len(edit.text_edits))


def _merge_edits(edits: Sequence[RefactorWorkspaceEdit]) -> RefactorWorkspaceEdit:
    """Disjoint-union merge of independently planned, non-overlapping workspace edits."""
    text_edits = []
    file_operations = []
    old_hashes: dict[str, str] = {}
    warnings: list[str] = []
    preconditions: list[str] = []
    stats: dict[str, int] = {}
    for edit in edits:
        text_edits.extend(edit.text_edits)
        file_operations.extend(edit.file_operations)
        old_hashes.update(edit.old_hashes)
        warnings.extend(edit.warnings)
        preconditions.extend(edit.preconditions)
        for key, value in edit.stats.items():
            stats[key] = stats.get(key, 0) + value
    return RefactorWorkspaceEdit(
        text_edits=text_edits,
        file_operations=file_operations,
        old_hashes=old_hashes,
        warnings=warnings,
        preconditions=preconditions,
        stats=stats,
    )


def _detect_same_file_conflict(
    sessions: Sequence[TransformationSessionRef],
) -> tuple[TransformationRefusal | None, list[str]]:
    """Finds cross-member same-file conflicts and the shared files that ARE offset-composable.

    Members are planned independently against the SAME pinned revision, so their text-edit offsets are all
    relative to one original file. Two members may therefore edit a single file together iff their edits
    occupy disjoint offset ranges in one encoding and neither performs a whole-file create/delete/rename on
    it (a whole-file op is order-dependent and never composes with another member's edit to that path).

    Returns ``(refusal, shared_paths)``: ``refusal`` is the first real conflict (or ``None``), and
    ``shared_paths`` are the text-only files edited by more than one member that composed cleanly (used to
    raise the composed risk to REVIEW_REQUIRED, since composing independent edits to one file warrants review).
    """
    text_edits_by_path: dict[str, list[tuple[str, RefactorTextEdit]]] = {}
    fileop_members_by_path: dict[str, set[str]] = {}
    for ref in sessions:
        edit = ref.workspace_edit
        for text_edit in edit.text_edits:
            text_edits_by_path.setdefault(text_edit.relative_path, []).append((ref.member_id, text_edit))
        for operation in edit.file_operations:
            fileop_members_by_path.setdefault(operation.relative_path, set()).add(ref.member_id)
            if operation.new_relative_path is not None:
                fileop_members_by_path.setdefault(operation.new_relative_path, set()).add(ref.member_id)

    # a whole-file op may not share its path with any other member's edit/op (order-dependent, not composable)
    for path in sorted(fileop_members_by_path):
        members = set(fileop_members_by_path[path])
        members.update(member_id for member_id, _ in text_edits_by_path.get(path, ()))
        if len(members) > 1:
            first, second = sorted(members)[:2]
            return (
                TransformationRefusal(
                    WORKSPACE_SESSION_FILE_CONFLICT,
                    f"Members {first!r} and {second!r} both target {path!r} where one performs a whole-file "
                    f"create/delete/rename; such operations are order-dependent and not composable — apply them "
                    f"sequentially instead.",
                ),
                [],
            )

    # text-only shared files compose iff one offset encoding and disjoint offset ranges across the members
    shared: list[str] = []
    for path in sorted(text_edits_by_path):
        entries = text_edits_by_path[path]
        if len({member_id for member_id, _ in entries}) < 2:
            continue  # a single member's own same-file edits are validated by the applier, not composed here
        if len({text_edit.offset_encoding for _, text_edit in entries}) > 1:
            return (
                TransformationRefusal(
                    WORKSPACE_SESSION_FILE_CONFLICT,
                    f"Members editing {path!r} mix byte and character offset encodings; their edits cannot be "
                    f"composed — apply them sequentially instead.",
                ),
                [],
            )
        # sweep the union of edits in offset order; a start before the running max end of a DIFFERENT member overlaps
        ordered = sorted(entries, key=lambda item: (item[1].start_offset, item[1].end_offset))
        cover_member, cover = ordered[0]
        for member_id, text_edit in ordered[1:]:
            if text_edit.start_offset < cover.end_offset and member_id != cover_member:
                return (
                    TransformationRefusal(
                        WORKSPACE_SESSION_FILE_CONFLICT,
                        f"Members {cover_member!r} and {member_id!r} edit overlapping ranges of {path!r} "
                        f"([{cover.start_offset},{cover.end_offset}) vs [{text_edit.start_offset},{text_edit.end_offset})); "
                        f"overlapping edits are not composable — apply them sequentially instead.",
                    ),
                    [],
                )
            if text_edit.end_offset > cover.end_offset:
                cover_member, cover = member_id, text_edit
        shared.append(path)
    return None, shared


def _is_test_path(path: str) -> bool:
    """Whether a project-relative POSIX path lives under a conventional test source root or names a test type.

    Used by the composed-edit projection to classify a touched Java file as test scope without a project graph:
    matches a ``src/test/`` (or ``/test/java/``) source root or a ``*Test``/``*Tests``/``*IT`` type name.
    """
    lowered = path.lower()
    if "src/test/" in lowered or "/test/java/" in lowered or lowered.startswith("test/"):
        return True
    stem = path.rsplit("/", 1)[-1]
    if not stem.endswith(".java"):
        return False
    name = stem[: -len(".java")]
    return name.endswith(("Test", "Tests", "IT"))


def _build_impact_report(stats: WorkspaceStats, risk: RiskLevel, warnings: Iterable[str]) -> ImpactReport:
    """Builds the impact report as a file-level projection of the composed edit (no graph required).

    Every section is genuinely computed from the touched files the composition actually carries; there are no
    ``not_analyzed`` placeholders. A change that touches no resources/tests/main-source Java yields honest ZERO
    counts (``fileCount: 0`` / ``touchedTestFiles: []``), never an "uncomputed" marker. This is the lightweight
    workspace-local projection used while composing edits; the public manager preview/apply routes replace it with
    the full graph-backed cross-reference report (wired resource providers, tests that *reference* a changed type,
    API boundary crossings) computed by :class:`~serena.java_refactor_v3.reports.impact.ImpactReportBuilder`,
    which has the project graph this projection deliberately does not.
    """
    java = {
        "sourceFiles": stats.file_count,
        "edits": stats.edit_count,
        "modified": stats.modified_files,
        "created": stats.created_files,
        "deleted": stats.deleted_files,
        "renamed": stats.renamed_files,
        "files": [stat.to_dict() for stat in stats.files],
    }

    # Classify every touched file by type from its path/kind alone (a true projection of the composed edit).
    resource_files = [stat.path for stat in stats.files if not stat.path.endswith(".java")]
    java_stats = [stat for stat in stats.files if stat.path.endswith(".java")]
    test_files = sorted(stat.path for stat in java_stats if _is_test_path(stat.path))
    main_types_touched = sorted(stat.path for stat in java_stats if not _is_test_path(stat.path))

    resources = {
        "fileCount": len(resource_files),
        "files": sorted(resource_files),
    }
    api = {
        "mainSourceFilesTouched": len(main_types_touched),
        "files": main_types_touched,
    }
    tests = {
        "touchedTestFiles": test_files,
        "touchedTestCount": len(test_files),
    }
    return ImpactReport(
        java=java,
        resources=resources,
        api=api,
        tests=tests,
        risk={"level": risk.value, "warnings": list(warnings)},
    )


class TransformationWorkspace:
    """A revision-guarded grouping of V2 sessions composed into one transactional apply."""

    def __init__(self, workspace_id: str, driver: SessionDriver, *, clock: Callable[[], float] = time.time) -> None:
        """
        :param workspace_id: opaque unique id used to address this workspace through the manager.
        :param driver: backend that creates/cancels V2 sessions and mints transactional appliers.
        :param clock: wall-clock source (injectable for deterministic tests); used for created/updated stamps.
        """
        self._id = workspace_id
        self._driver = driver
        self._clock = clock
        self._status = WorkspaceStatus.OPEN
        self._sessions: list[TransformationSessionRef] = []
        self._project_revision: Any = None
        self._created_at = clock()
        self._updated_at = self._created_at

    @property
    def workspace_id(self) -> str:
        """The workspace's opaque id."""
        return self._id

    @property
    def status(self) -> WorkspaceStatus:
        """The workspace's current lifecycle state."""
        return self._status

    @property
    def project_revision(self) -> Any:
        """The pinned project revision shared by all member sessions (``None`` until first add)."""
        return self._project_revision

    @property
    def sessions(self) -> tuple[TransformationSessionRef, ...]:
        """The member sessions, in enrollment order."""
        return tuple(self._sessions)

    def _touch(self) -> None:
        self._updated_at = self._clock()

    def add_session(self, operation: str, params: dict[str, Any], validate: bool | None = None) -> dict[str, Any]:
        """Plans a V2 session and enrolls it in this workspace under the pinned revision.

        Creates a revision-guarded preview session through the driver, refuses if the sidecar declined it,
        pins (or re-checks) the workspace revision, parses and caches the planned edit, and appends the
        member session. The cached edit is what workspace preview/apply later composes.
        """
        # reject mutation of a terminal workspace
        if self._status.is_terminal():
            return self._terminal_refusal("add_session")

        # create the revision-guarded V2 preview session
        envelope = self._driver.create_v2_refactor_session(operation, params, validate=validate)
        if not envelope.get("accepted"):
            return self._refuse(
                WORKSPACE_SESSION_NOT_ACCEPTED,
                f"The sidecar refused the {operation!r} session, so it was not added to the workspace.",
                mode="add_session",
                sessionEnvelope=envelope,
            )
        session_id = _session_id_of(envelope)
        if session_id is None:
            return self._refuse(
                WORKSPACE_SESSION_NOT_ACCEPTED,
                f"The sidecar accepted the {operation!r} session but returned no sessionId; nothing was enrolled.",
                mode="add_session",
            )

        # enforce the single-revision pin across member sessions
        revision = _project_revision_of(envelope)
        if revision is None:
            self._safe_cancel(session_id)
            return self._refuse(
                WORKSPACE_REVISION_MISMATCH,
                f"The {operation!r} session returned no project revision; the V3 workspace revision guard cannot be enforced.",
                mode="add_session",
            )
        if self._project_revision is None and not self._sessions:
            self._project_revision = revision
        elif revision is not None and self._project_revision is not None and revision != self._project_revision:
            # release the freshly created session before refusing — it must not leak
            self._safe_cancel(session_id)
            return self._refuse(
                WORKSPACE_REVISION_MISMATCH,
                f"The {operation!r} session was planned against project revision {revision!r}, "
                f"but the workspace pins {self._project_revision!r}; recreate the workspace to compose them.",
                mode="add_session",
            )

        # parse and cache the planned, hash-guarded edit
        try:
            edit = _parse_edit(envelope)
        except (WorkspaceEditError, KeyError, TypeError, ValueError) as error:
            self._safe_cancel(session_id)
            return self._refuse(
                WORKSPACE_UNSAFE_EDIT,
                f"The {operation!r} session's planned edit could not be parsed and was not enrolled: {error}",
                mode="add_session",
            )

        self._sessions.append(
            TransformationSessionRef(
                operation=operation,
                project_revision=revision,
                workspace_edit=edit,
                member_kind="v2",
                session_id=session_id,
            )
        )
        self._status = WorkspaceStatus.OPEN
        self._touch()
        return {
            "accepted": True,
            "mode": "add_session",
            "workspaceId": self._id,
            "status": self._status.value,
            "sessionId": session_id,
            "operation": operation,
            "sessionCount": len(self._sessions),
            "projectRevision": self._project_revision,
        }

    def add_operation(self, operation: str, params: dict[str, Any]) -> dict[str, Any]:
        """Plans a compute-only V3 op and enrolls it in this workspace under the pinned revision.

        The V3 counterpart of :meth:`add_session`: it asks the driver to PLAN the op compute-only (nothing applied or
        javac-validated), refuses if the sidecar declined it (the refusal is surfaced verbatim, so a refused op is never
        enrolled), pins (or re-checks) the workspace revision, and appends a V3 member carrying the parsed edit directly.
        Because a V3 op has no live sidecar session, the member is dropped (not cancelled) on workspace cancel. The
        cached edit is composed identically to a V2 member's, so cross-member conflict detection is uniform (V2<->V3 and
        V3<->V3 same-file overlaps are refused exactly like V2<->V2).
        """
        # reject mutation of a terminal workspace
        if self._status.is_terminal():
            return self._terminal_refusal("add_operation")

        # plan the V3 op compute-only through the driver
        plan = self._driver.plan_v3_operation(operation, params)
        if isinstance(plan, dict):
            # a sidecar refusal (or malformed/unclassified edit) is surfaced verbatim; nothing was enrolled.
            return self._operation_refusal(plan, operation)

        # enforce the single-revision pin across members
        revision = plan.project_revision
        if revision is None:
            return self._refuse(
                WORKSPACE_REVISION_MISMATCH,
                f"The {operation!r} op did not return a project revision; V3 workspaces cannot safely compose it.",
                mode="add_operation",
            )
        if self._project_revision is None and not self._sessions:
            self._project_revision = revision
        elif revision is not None and self._project_revision is not None and revision != self._project_revision:
            return self._refuse(
                WORKSPACE_REVISION_MISMATCH,
                f"The {operation!r} op was planned against project revision {revision!r}, "
                f"but the workspace pins {self._project_revision!r}; recreate the workspace to compose them.",
                mode="add_operation",
            )

        member_id = uuid.uuid4().hex
        self._sessions.append(
            TransformationSessionRef(
                operation=operation,
                project_revision=revision,
                workspace_edit=plan.workspace_edit,
                member_kind="v3",
                session_id=None,
                member_id=member_id,
            )
        )
        self._status = WorkspaceStatus.OPEN
        self._touch()
        return {
            "accepted": True,
            "mode": "add_operation",
            "workspaceId": self._id,
            "status": self._status.value,
            "memberId": member_id,
            "operation": operation,
            "sessionCount": len(self._sessions),
            "projectRevision": self._project_revision,
        }

    def _compose(self) -> _Composition:
        """Composes member edits into a single merged plan, or returns a structured refusal.

        Enforces the non-empty and single-revision invariants, then composes the member edits with
        offset-aware same-file handling: members may share a file iff their edits occupy disjoint offset
        ranges in one encoding and neither performs a whole-file op on it (see :func:`_detect_same_file_conflict`).
        True range overlaps and order-dependent edits are refused; otherwise the disjoint union is merged and
        a shared-file composition raises the risk to REVIEW_REQUIRED.
        """
        # require at least one member session
        if not self._sessions:
            return _Composition(refusal=TransformationRefusal(WORKSPACE_EMPTY, "The workspace has no member sessions to compose."))

        # re-check the single-revision pin (defensive; add_session already enforces it)
        revisions = {ref.project_revision for ref in self._sessions if ref.project_revision is not None}
        if len(revisions) > 1:
            return _Composition(
                refusal=TransformationRefusal(
                    WORKSPACE_REVISION_MISMATCH,
                    f"Member sessions span multiple project revisions {sorted(map(repr, revisions))}; they cannot be composed.",
                )
            )

        # Offset-aware same-file composition. Members are planned independently against the SAME pinned
        # revision, so their text-edit offsets are all relative to one original file; co-located members
        # compose iff their ranges are disjoint in one encoding and neither performs a whole-file op on the
        # shared path. Only true overlaps and order-dependent (whole-file) edits are refused — strictly the
        # cases the transactional applier could not safely stage.
        conflict, shared_paths = _detect_same_file_conflict(self._sessions)
        if conflict is not None:
            return _Composition(refusal=conflict)

        # disjoint union of the member plans
        merged = _merge_edits([ref.workspace_edit for ref in self._sessions])
        if shared_paths:
            merged.warnings.append(
                "Composed independently-planned edits to shared file(s) "
                f"{', '.join(map(repr, shared_paths))}; review the combined result before applying."
            )
        stats = _compute_stats(merged)
        risk = RiskLevel.REVIEW_REQUIRED if merged.warnings else RiskLevel.SAFE
        return _Composition(merged_edit=merged, stats=stats, risk=risk)

    def preview(self) -> dict[str, Any]:
        """Composes and validates the member plan without writing anything.

        Returns the aggregated stats and a workspace-local impact projection of the composed edit. The public manager
        attaches the graph-backed V3 impact report before returning preview/apply results. Staging the merged edit
        in memory (no write) revalidates every file's hash precondition, so a drifted/unsafe composition is refused
        here rather than at apply time.
        """
        if self._status.is_terminal():
            return self._terminal_refusal("preview")
        composition = self._compose()
        if not composition.ok:
            return self._composition_refusal(composition, mode="preview")

        # dry-run staging revalidates hashes/offsets without mutating the workspace
        assert composition.merged_edit is not None and composition.stats is not None
        try:
            self._driver.new_workspace_edit_applier().stage(composition.merged_edit)
        except (WorkspaceEditError, KeyError, TypeError, ValueError) as error:
            return self._refuse(
                WORKSPACE_UNSAFE_EDIT,
                f"The composed workspace edit could not be staged exactly, so the preview is refused: {error}",
                mode="preview",
            )

        validation = self._validate_composed_edit(
            composition,
            mode="preview",
            apply=False,
            validate=True,
            allow_review_required=True,
        )
        if validation is not None and not validation.get("accepted", False):
            return self._refuse(
                validation.get("refusal", {}).get("code") or WORKSPACE_UNSAFE_EDIT,
                validation.get("refusal", {}).get("message") or "The composed workspace edit failed V3 validation.",
                mode="preview",
                validation=validation,
                impactReport=_build_impact_report(
                    composition.stats,
                    composition.risk,
                    composition.merged_edit.warnings,
                ).to_dict(),
                warnings=list(composition.merged_edit.warnings),
            )

        self._status = WorkspaceStatus.PREVIEWED
        self._touch()
        return self._success_envelope(composition, mode="preview", applied=False, validation=validation)

    def composed_impact_inputs(self) -> dict[str, Any]:
        """Composes the member plan for a READ-ONLY impact report, without staging or writing anything.

        Returns ``{"accepted": True, "edit": <RefactorWorkspaceEdit>, "risk": <RiskLevel>, "operation": <str>}``
        on success, or a structured refusal envelope (terminal/empty/conflicting/revision-mismatched workspace).
        Unlike :meth:`preview` this performs no disk staging: an impact report is a pure projection of the
        composed plan and changes nothing, so it neither validates file hashes nor advances the workspace status.
        ``operation`` is the single member operation when the workspace is homogeneous, else ``"composedWorkspace"``.
        """
        if self._status.is_terminal():
            return self._terminal_refusal("impact_report")
        composition = self._compose()
        if not composition.ok:
            return self._composition_refusal(composition, mode="impact_report")
        assert composition.merged_edit is not None
        operations = [ref.operation for ref in self._sessions]
        operation = operations[0] if len(set(operations)) == 1 else "composedWorkspace"
        return {
            "accepted": True,
            "edit": composition.merged_edit,
            "risk": composition.risk,
            "operation": operation,
        }

    def apply(
        self,
        validate: bool | None = None,
        expected_project_revision: Any = None,
        allow_review_required: bool = False,
    ) -> dict[str, Any]:
        """Composes the member plan and commits it transactionally (all-or-nothing).

        ``expected_project_revision`` optionally pins an optimistic-concurrency guard: when supplied it must
        match the workspace's pinned revision or the apply is refused before any write. On a successful
        commit the member sessions are released and the workspace becomes :attr:`WorkspaceStatus.APPLIED`;
        on any staging/commit failure the applier rolls back and the workspace becomes
        :attr:`WorkspaceStatus.FAILED` with nothing written.
        """
        if self._status.is_terminal():
            return self._terminal_refusal("apply")

        # optimistic-concurrency guard against the pinned revision
        if self._project_revision is None:
            return self._refuse(
                WORKSPACE_REVISION_MISMATCH,
                "Apply refused because the workspace has no pinned project revision.",
                mode="apply",
            )
        if expected_project_revision is not None and expected_project_revision != self._project_revision:
            return self._refuse(
                WORKSPACE_REVISION_MISMATCH,
                f"Apply expected project revision {expected_project_revision!r} but the workspace pins {self._project_revision!r}.",
                mode="apply",
            )

        composition = self._compose()
        if not composition.ok:
            return self._composition_refusal(composition, mode="apply")
        assert composition.merged_edit is not None and composition.stats is not None

        if composition.risk == RiskLevel.REVIEW_REQUIRED and not allow_review_required:
            return self._refuse(
                WORKSPACE_REVIEW_REQUIRED,
                "The composed workspace edit is REVIEW_REQUIRED; preview it or pass allow_review_required=true before applying.",
                mode="apply",
                impactReport=_build_impact_report(
                    composition.stats,
                    composition.risk,
                    composition.merged_edit.warnings,
                ).to_dict(),
                warnings=list(composition.merged_edit.warnings),
            )

        validation = self._validate_composed_edit(
            composition,
            mode="apply",
            apply=True,
            validate=validate,
            allow_review_required=allow_review_required,
        )
        if validation is not None and not validation.get("accepted", False):
            self._status = WorkspaceStatus.FAILED if validation.get("applied") else self._status
            self._touch()
            return self._refuse(
                validation.get("refusal", {}).get("code") or WORKSPACE_UNSAFE_EDIT,
                validation.get("refusal", {}).get("message")
                or "The composed workspace edit failed javac validation.",
                mode="apply",
                risk=composition.risk,
                validation=validation,
            )

        applier = self._driver.new_workspace_edit_applier()
        try:
            staged = applier.stage(composition.merged_edit)
        except (WorkspaceEditError, KeyError, TypeError, ValueError) as error:
            return self._refuse(
                WORKSPACE_UNSAFE_EDIT,
                f"The composed workspace edit could not be staged exactly, so nothing was applied: {error}",
                mode="apply",
            )
        try:
            applier.commit(staged)
        except Exception as error:
            self._status = WorkspaceStatus.FAILED
            self._touch()
            return self._refuse(
                WORKSPACE_APPLY_FAILED,
                f"The composed workspace edit failed to commit and was rolled back; no files were changed: {error}",
                mode="apply",
            )

        # release the now-spent members and mark the workspace applied (V2 sessions are cancelled; V3 ops are dropped)
        for ref in self._sessions:
            ref.applied = True
            self._release(ref)
        self._status = WorkspaceStatus.APPLIED
        self._touch()
        result = self._success_envelope(composition, mode="apply", applied=True, validation=validation)
        result["touchedFiles"] = composition.merged_edit.touched_files()
        return result

    def cancel(self) -> dict[str, Any]:
        """Cancels every member and marks the workspace cancelled (idempotent once terminal).

        V2 sessions are cancelled in the sidecar; V3 op members carry a pure compute-only edit with no sidecar session,
        so they are simply DROPPED. Both are released from the workspace.
        """
        if self._status.is_terminal():
            return self._terminal_refusal("cancel")
        for ref in self._sessions:
            self._release(ref)
        self._status = WorkspaceStatus.CANCELLED
        self._touch()
        return {"accepted": True, "mode": "cancel", "workspaceId": self._id, "status": self._status.value, "sessionCount": len(self._sessions)}

    def evict(self) -> None:
        """Releases members (cancels V2 sessions, drops V3 ops) and marks the workspace evicted (manager-driven)."""
        if self._status.is_terminal():
            self._status = WorkspaceStatus.EVICTED
            return
        for ref in self._sessions:
            self._release(ref)
        self._status = WorkspaceStatus.EVICTED
        self._touch()

    def status_dict(self) -> dict[str, Any]:
        """A serializable status summary for workspace listing."""
        return {
            "workspaceId": self._id,
            "status": self._status.value,
            "sessionCount": len(self._sessions),
            "projectRevision": self._project_revision,
            "createdAt": self._created_at,
            "updatedAt": self._updated_at,
            "sessions": [ref.summary() for ref in self._sessions],
        }

    def _validate_composed_edit(
        self,
        composition: _Composition,
        *,
        mode: str,
        apply: bool,
        validate: bool | None,
        allow_review_required: bool,
    ) -> dict[str, Any] | None:
        """Delegates composed V3 workspace validation/application to the manager bridge when available."""
        assert composition.stats is not None and composition.merged_edit is not None
        validator = getattr(self._driver, "validate_v3_workspace_edit", None)
        if not callable(validator):
            return self._refuse(
                WORKSPACE_UNSAFE_EDIT,
                "The composed workspace edit cannot be accepted because no V3 javac validation bridge is available.",
                mode=mode,
            )
        return cast(
            dict[str, Any] | None,
            validator(
                operation="transformationWorkspace",
                workspace_edit=composition.merged_edit,
                apply=apply,
                validate=validate,
                risk=composition.risk,
                allow_review_required=allow_review_required,
                warnings=list(composition.merged_edit.warnings),
                summary=f"Transformation workspace {self._id} {mode}",
            ),
        )

    def _success_envelope(
        self,
        composition: _Composition,
        *,
        mode: str,
        applied: bool,
        validation: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        assert composition.stats is not None and composition.merged_edit is not None
        impact = _build_impact_report(composition.stats, composition.risk, composition.merged_edit.warnings)
        result = {
            "accepted": True,
            "applied": applied,
            "mode": mode,
            "workspaceId": self._id,
            "status": self._status.value,
            "risk": composition.risk.value,
            "projectRevision": self._project_revision,
            "sessionCount": len(self._sessions),
            "stats": composition.stats.to_dict(),
            "impactReport": impact.to_dict(),
            "warnings": list(composition.merged_edit.warnings),
        }
        if validation is None:
            return self._refuse(
                WORKSPACE_UNSAFE_EDIT,
                "The composed workspace edit cannot be accepted without a real V3 javac validation result.",
                mode=mode,
            )
        result["validation"] = validation
        return result

    def _composition_refusal(self, composition: _Composition, *, mode: str) -> dict[str, Any]:
        assert composition.refusal is not None
        return self._refuse(composition.refusal.code, composition.refusal.message, mode=mode)

    def _operation_refusal(self, plan_refusal: dict[str, Any] | None, operation: str) -> dict[str, Any]:
        """Builds a structured refusal for an operation that failed V3-plan enrollment."""
        refusal = plan_refusal if isinstance(plan_refusal, dict) else {}
        nested_value = refusal.get("refusal")
        nested_refusal = nested_value if isinstance(nested_value, dict) else {}
        code_value = refusal.get("code") or nested_refusal.get("code") or WORKSPACE_SESSION_NOT_ACCEPTED
        message_value = (
            refusal.get("message")
            or nested_refusal.get("message")
            or "Operation did not produce an accepted V3 plan"
        )
        return self._refuse(
            str(code_value),
            str(message_value),
            mode="add_operation",
            operation=operation,
            planRefusal=refusal,
        )

    def _refuse(self, code: str, message: str, *, mode: str, **extra: Any) -> dict[str, Any]:
        result = {
            "accepted": False,
            "applied": False,
            "mode": mode,
            "workspaceId": self._id,
            "status": self._status.value,
            "risk": RiskLevel.REFUSED.value,
            "refusal": TransformationRefusal(code, message).to_dict(),
        }
        result.update(extra)
        return result

    def _terminal_refusal(self, mode: str) -> dict[str, Any]:
        return self._refuse(
            WORKSPACE_TERMINAL,
            f"The workspace is {self._status.value!r} and cannot {mode.replace('_', ' ')}; create a new workspace.",
            mode=mode,
        )

    def _release(self, ref: TransformationSessionRef) -> None:
        """Releases a member: cancels a V2 session in the sidecar, or drops a V3 op (no session to release)."""
        if ref.is_v3 or ref.session_id is None:
            return
        self._safe_cancel(ref.session_id)

    def _safe_cancel(self, session_id: str) -> None:
        """Best-effort session release that never raises (cleanup must not mask a primary outcome)."""
        try:
            self._driver.cancel_v2_refactor_session(session_id)
        except Exception:
            pass


class TransformationWorkspaceManager:
    """Owns live transformation workspaces and enforces capacity/TTL eviction.

    The manager is the V3 tools' entry point: it mints workspaces, routes the add-session/preview/apply/
    cancel surface to them by id, and reclaims stale or overflowing workspaces (releasing their sidecar
    sessions) so long-running agents do not leak sessions.
    """

    def __init__(
        self,
        driver: SessionDriver,
        *,
        max_workspaces: int = 32,
        ttl_seconds: float | None = 3600.0,
        clock: Callable[[], float] = time.monotonic,
        wall_clock: Callable[[], float] = time.time,
    ) -> None:
        """
        :param driver: backend that creates/cancels V2 sessions and mints transactional appliers.
        :param max_workspaces: hard cap on concurrently live workspaces; the least-recently-used is evicted on overflow.
        :param ttl_seconds: idle time after which a workspace is evicted on the next manager touch; ``None`` disables TTL eviction.
        :param clock: monotonic source for recency/TTL accounting (injectable for tests).
        :param wall_clock: wall-clock source stamped onto workspaces for human-facing created/updated times.
        """
        if max_workspaces <= 0:
            raise ValueError("max_workspaces must be a positive integer.")
        self._driver = driver
        self._max_workspaces = max_workspaces
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._wall_clock = wall_clock
        # LRU order: most-recently-touched workspaces move to the end
        self._workspaces: "OrderedDict[str, TransformationWorkspace]" = OrderedDict()
        self._last_touched: dict[str, float] = {}

    def create_workspace(self) -> TransformationWorkspace:
        """Creates and registers a new open workspace, first reclaiming expired/overflowing ones."""
        self._reclaim()
        workspace_id = uuid.uuid4().hex
        workspace = TransformationWorkspace(workspace_id, self._driver, clock=self._wall_clock)
        self._workspaces[workspace_id] = workspace
        self._last_touched[workspace_id] = self._clock()
        self._evict_overflow()
        return workspace

    def get_workspace(self, workspace_id: str) -> TransformationWorkspace | None:
        """Returns the workspace for ``workspace_id`` (refreshing its recency), or ``None`` if unknown."""
        self._reclaim()
        workspace = self._workspaces.get(workspace_id)
        if workspace is not None:
            self._mark_recent(workspace_id)
        return workspace

    def list_workspaces(self) -> list[dict[str, Any]]:
        """Returns status summaries of all live workspaces (after reclaiming stale ones)."""
        self._reclaim()
        return [workspace.status_dict() for workspace in self._workspaces.values()]

    def evict(self, workspace_id: str) -> bool:
        """Explicitly evicts a workspace by id, releasing its sessions; returns whether it existed."""
        workspace = self._workspaces.pop(workspace_id, None)
        self._last_touched.pop(workspace_id, None)
        if workspace is None:
            return False
        workspace.evict()
        return True

    def add_session(self, workspace_id: str, operation: str, params: dict[str, Any], validate: bool | None = None) -> dict[str, Any]:
        """Routes :meth:`TransformationWorkspace.add_session` by workspace id."""
        workspace = self.get_workspace(workspace_id)
        if workspace is None:
            return self._not_found(workspace_id, "add_session")
        result = workspace.add_session(operation, params, validate=validate)
        self._mark_recent(workspace_id)
        return result

    def add_operation(self, workspace_id: str, operation: str, params: dict[str, Any]) -> dict[str, Any]:
        """Routes :meth:`TransformationWorkspace.add_operation` (V3 op enrollment) by workspace id."""
        workspace = self.get_workspace(workspace_id)
        if workspace is None:
            return self._not_found(workspace_id, "add_operation")
        result = workspace.add_operation(operation, params)
        self._mark_recent(workspace_id)
        return result

    def preview(self, workspace_id: str) -> dict[str, Any]:
        """Routes :meth:`TransformationWorkspace.preview` by workspace id."""
        workspace = self.get_workspace(workspace_id)
        if workspace is None:
            return self._not_found(workspace_id, "preview")
        result = workspace.preview()
        self._mark_recent(workspace_id)
        return result

    def apply(
        self,
        workspace_id: str,
        validate: bool | None = None,
        expected_project_revision: Any = None,
        allow_review_required: bool = False,
    ) -> dict[str, Any]:
        """Routes :meth:`TransformationWorkspace.apply` by workspace id."""
        workspace = self.get_workspace(workspace_id)
        if workspace is None:
            return self._not_found(workspace_id, "apply")
        result = workspace.apply(
            validate=validate,
            expected_project_revision=expected_project_revision,
            allow_review_required=allow_review_required,
        )
        self._mark_recent(workspace_id)
        return result

    def cancel(self, workspace_id: str) -> dict[str, Any]:
        """Routes :meth:`TransformationWorkspace.cancel` by workspace id, then drops it from the registry."""
        workspace = self._workspaces.get(workspace_id)
        if workspace is None:
            return self._not_found(workspace_id, "cancel")
        result = workspace.cancel()
        self._workspaces.pop(workspace_id, None)
        self._last_touched.pop(workspace_id, None)
        return result

    def impact_report(
        self,
        workspace_id: str,
        build: Callable[[RefactorWorkspaceEdit, RiskLevel, str], dict[str, Any]],
    ) -> dict[str, Any]:
        """Composes the workspace plan and delegates graph-backed report construction to ``build`` (G011).

        READ-ONLY: routes to :meth:`TransformationWorkspace.composed_impact_inputs`, then hands the composed
        edit, its risk, and a representative operation label to ``build`` (the caller owns the project graph
        and :class:`ImpactReportBuilder`, so this module stays graph-free). Returns the wrapped report on
        success, the unknown-workspace refusal when the id is absent, or the composition refusal verbatim.
        """
        workspace = self.get_workspace(workspace_id)
        if workspace is None:
            return self._not_found(workspace_id, "impact_report")
        inputs = workspace.composed_impact_inputs()
        if not inputs.get("accepted"):
            return inputs
        report = build(inputs["edit"], inputs["risk"], inputs["operation"])
        self._mark_recent(workspace_id)
        return {
            "accepted": True,
            "mode": "impact_report",
            "workspaceId": workspace_id,
            "operation": inputs["operation"],
            "risk": inputs["risk"].value,
            "report": report,
        }

    def _mark_recent(self, workspace_id: str) -> None:
        if workspace_id in self._workspaces:
            self._workspaces.move_to_end(workspace_id)
            self._last_touched[workspace_id] = self._clock()

    def _reclaim(self) -> None:
        """Evicts workspaces idle beyond the TTL (no-op when TTL is disabled)."""
        if self._ttl_seconds is None:
            return
        now = self._clock()
        expired = [wid for wid, touched in self._last_touched.items() if now - touched > self._ttl_seconds]
        for workspace_id in expired:
            self.evict(workspace_id)

    def _evict_overflow(self) -> None:
        while len(self._workspaces) > self._max_workspaces:
            oldest_id, _ = next(iter(self._workspaces.items()))
            self.evict(oldest_id)

    def _not_found(self, workspace_id: str, mode: str) -> dict[str, Any]:
        return {
            "accepted": False,
            "applied": False,
            "mode": mode,
            "workspaceId": workspace_id,
            "risk": RiskLevel.REFUSED.value,
            "refusal": TransformationRefusal(
                WORKSPACE_NOT_FOUND, f"No transformation workspace {workspace_id!r}; it does not exist or was evicted."
            ).to_dict(),
        }
