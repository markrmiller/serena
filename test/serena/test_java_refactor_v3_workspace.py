"""Unit tests for the V3 transformation workspace engine (G001).

These exercise workspace composition (revision guard, file-conflict refusal, stats, the computed
impact report, transactional apply, eviction) against a lightweight :class:`StubDriver` so the
orchestration logic is verified without a live Java sidecar, while the real transactional applier writes
to a temp project for the apply path.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from serena.java_refactor.workspace_edit import RefactorWorkspaceEdit, TransactionalWorkspaceEditApplier, sha256_bytes
from serena.java_refactor_v3 import (
    TransformationWorkspaceManager,
    V3OperationPlan,
    WorkspaceStatus,
)
from serena.java_refactor_v3.models import V3_REFUSAL_REGISTRY, RiskLevel


class StubDriver:
    """Programmable :class:`~serena.java_refactor_v3.workspace.SessionDriver` double.

    Tests ``program`` the envelope each ``create_v2_refactor_session`` call should return, while a real
    :class:`TransactionalWorkspaceEditApplier` bound to a temp project backs the apply path.
    """

    def __init__(self, project_root: Path) -> None:
        self.project_root = Path(project_root)
        self.cancelled: list[str] = []
        self.created_ops: list[str] = []
        self.planned_ops: list[str] = []
        self._queue: list[dict[str, Any]] = []
        self._v3_queue: list[V3OperationPlan | dict[str, Any]] = []

    def program(self, envelope: dict[str, Any]) -> None:
        self._queue.append(envelope)

    def program_v3(self, plan: V3OperationPlan | dict[str, Any]) -> None:
        self._v3_queue.append(plan)

    def create_v2_refactor_session(self, operation: str, params: dict[str, Any], validate: bool | None = None) -> dict[str, Any]:
        self.created_ops.append(operation)
        assert self._queue, "StubDriver received an unprogrammed create_v2_refactor_session call"
        return self._queue.pop(0)

    def cancel_v2_refactor_session(self, session_id: str) -> dict[str, Any]:
        self.cancelled.append(session_id)
        return {"accepted": True}

    def plan_v3_operation(self, operation: str, params: dict[str, Any]) -> V3OperationPlan | dict[str, Any]:
        self.planned_ops.append(operation)
        assert self._v3_queue, "StubDriver received an unprogrammed plan_v3_operation call"
        return self._v3_queue.pop(0)

    def new_workspace_edit_applier(self) -> TransactionalWorkspaceEditApplier:
        return TransactionalWorkspaceEditApplier(self.project_root, encoding="utf-8", line_ending=None)


def _v3_plan(
    project_root: Path,
    operation: str,
    relative_path: str,
    start: int,
    end: int,
    new_text: str,
    *,
    revision: str = "r1",
    old_sha256: str | None = None,
    warnings: list[str] | None = None,
    risk: RiskLevel = RiskLevel.SAFE,
) -> V3OperationPlan:
    """Builds a compute-only V3 op plan carrying a single-file text edit with a real or overridden hash precondition."""
    sha = old_sha256 if old_sha256 is not None else sha256_bytes((project_root / relative_path).read_bytes())
    edit = RefactorWorkspaceEdit.from_protocol_dict(
        {
            "changes": [
                {
                    "path": relative_path,
                    "oldSha256": sha,
                    "edits": [{"startOffset": start, "endOffset": end, "newText": new_text, "kind": "replace"}],
                }
            ],
            "fileOperations": [],
            "warnings": warnings or [],
        }
    )
    return V3OperationPlan(
        operation=operation,
        project_revision=revision,
        workspace_edit=edit,
        risk=risk,
        warnings=list(warnings or []),
    )


def _write(project_root: Path, relative_path: str, content: str) -> None:
    path = project_root / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _text_envelope(
    project_root: Path,
    session_id: str,
    relative_path: str,
    start: int,
    end: int,
    new_text: str,
    *,
    revision: str = "r1",
    old_sha256: str | None = None,
    accepted: bool = True,
    warnings: list[str] | None = None,
) -> dict[str, Any]:
    """Builds a single-file text-edit session envelope with a real or overridden hash precondition."""
    sha = old_sha256 if old_sha256 is not None else sha256_bytes((project_root / relative_path).read_bytes())
    return {
        "accepted": accepted,
        "session": {"sessionId": session_id, "projectRevision": revision},
        "preview": {
            "workspaceEdit": {
                "changes": [
                    {
                        "path": relative_path,
                        "oldSha256": sha,
                        "edits": [{"startOffset": start, "endOffset": end, "newText": new_text, "kind": "replace"}],
                    }
                ],
                "fileOperations": [],
                "warnings": warnings or [],
            }
        },
    }


@pytest.fixture()
def project(tmp_path: Path) -> Path:
    _write(tmp_path, "A.txt", "hello world\n")
    _write(tmp_path, "B.txt", "foo bar\n")
    return tmp_path


def test_add_session_pins_revision_and_previews_stats(project: Path) -> None:
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there"))
    driver.program(_text_envelope(project, "s2", "B.txt", 0, 3, "baz"))
    first = workspace.add_session("renameMember", {})
    second = workspace.add_session("renameMember", {})
    assert first["accepted"] and second["accepted"]
    assert workspace.project_revision == "r1"

    preview = workspace.preview()
    assert preview["accepted"] and preview["applied"] is False
    assert preview["status"] == WorkspaceStatus.PREVIEWED.value
    assert preview["risk"] == "SAFE"
    assert preview["stats"]["fileCount"] == 2
    assert preview["stats"]["editCount"] == 2
    assert preview["stats"]["modifiedFiles"] == 2
    # impact report: every section is a computed file-level projection of the composed edit, never "not_analyzed".
    # This edit touches two .txt files, so resources carry honest counts and the Java/test/api sections are zeroed.
    impact = preview["impactReport"]
    assert impact["java"]["sourceFiles"] == 2
    assert impact["resources"]["fileCount"] == 2
    assert [item["path"] for item in impact["resources"]["files"]] == ["A.txt", "B.txt"]
    assert "api" in impact
    assert "semanticImpact" in impact
    assert "tests" in impact
    # honesty gate: no section is an "uncomputed" marker.
    for section in ("resources", "api", "tests"):
        assert impact[section].get("status") != "not_analyzed", impact[section]


def test_impact_report_classifies_touched_files_with_honest_counts(tmp_path: Path) -> None:
    """The preview impact report is a real file-level projection: a composed edit touching a main Java file, a
    test Java file, and a resource file yields non-trivial honest counts in every section (no ``not_analyzed``).
    """
    _write(tmp_path, "src/main/java/com/acme/Svc.java", "package com.acme;\npublic class Svc { int x = 1; }\n")
    _write(tmp_path, "src/test/java/com/acme/SvcTest.java", "package com.acme;\nclass SvcTest { int y = 2; }\n")
    _write(tmp_path, "src/main/resources/META-INF/services/com.acme.Spi", "com.acme.Svc\n")

    driver = StubDriver(tmp_path)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()
    driver.program(_text_envelope(tmp_path, "s1", "src/main/java/com/acme/Svc.java", 40, 41, "9"))
    driver.program(_text_envelope(tmp_path, "s2", "src/test/java/com/acme/SvcTest.java", 38, 39, "8"))
    driver.program(_text_envelope(tmp_path, "s3", "src/main/resources/META-INF/services/com.acme.Spi", 0, 9, "com.acme.Svc"))
    for _ in range(3):
        assert workspace.add_session("renameMember", {})["accepted"]

    impact = workspace.preview()["impactReport"]
    # resources: the META-INF/services provider file is a touched non-Java resource.
    assert impact["resources"]["fileCount"] == 1, impact["resources"]
    assert [item["path"] for item in impact["resources"]["files"]] == ["src/main/resources/META-INF/services/com.acme.Spi"], impact["resources"]
    # api: the main-source Java file is touched; the test file is NOT counted here.
    assert impact["api"]["mainSourceFilesTouched"] == 1, impact["api"]
    assert impact["api"]["files"] == ["src/main/java/com/acme/Svc.java"], impact["api"]
    # tests: the test file is classified by its test source root + *Test name.
    assert impact["tests"]["touchedTestCount"] == 1, impact["tests"]
    assert impact["tests"]["touchedTestFiles"] == ["src/test/java/com/acme/SvcTest.java"], impact["tests"]
    # honesty gate: no section is an "uncomputed" marker.
    for section in ("resources", "api", "tests"):
        assert impact[section].get("status") != "not_analyzed", impact[section]


def test_revision_mismatch_is_refused_and_session_released(project: Path) -> None:
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there", revision="r1"))
    driver.program(_text_envelope(project, "s2", "B.txt", 0, 3, "baz", revision="r2"))
    assert workspace.add_session("renameMember", {})["accepted"]
    mismatch = workspace.add_session("renameMember", {})

    assert mismatch["accepted"] is False
    assert mismatch["refusal"]["code"] == "workspace_revision_mismatch"
    # the rejected session must be released, not leaked
    assert driver.cancelled == ["s2"]
    assert len(workspace.sessions) == 1


def test_non_overlapping_same_file_sessions_compose(project: Path) -> None:
    """Two V2 sessions editing DISJOINT ranges of one file compose (offsets are relative to one pinned original).

    The composed apply is the offset-disjoint union; sharing a file raises the risk to REVIEW_REQUIRED but still
    applies (REVIEW_REQUIRED does not gate apply), and the per-file hash precondition is verified before any write.
    """
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program(_text_envelope(project, "s1", "A.txt", 0, 5, "HELLO"))
    driver.program(_text_envelope(project, "s2", "A.txt", 6, 11, "WORLD"))
    workspace.add_session("op", {})
    workspace.add_session("op", {})

    preview = workspace.preview()
    assert preview["accepted"]
    assert preview["risk"] == "REVIEW_REQUIRED"
    assert any("A.txt" in warning for warning in preview["warnings"]), preview["warnings"]

    result = manager.apply(workspace.workspace_id, allow_review_required=True)
    assert result["accepted"] and result["applied"]
    assert (project / "A.txt").read_text(encoding="utf-8") == "HELLO WORLD\n"


def test_overlapping_same_file_sessions_are_refused(project: Path) -> None:
    """Two sessions whose ranges OVERLAP cannot be offset-composed and are refused with a structured conflict."""
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program(_text_envelope(project, "s1", "A.txt", 0, 6, "HELLO_"))
    driver.program(_text_envelope(project, "s2", "A.txt", 5, 11, "_WORLD"))
    workspace.add_session("op", {})
    workspace.add_session("op", {})

    preview = workspace.preview()
    assert preview["accepted"] is False
    assert preview["refusal"]["code"] == "workspace_session_file_conflict"
    assert (project / "A.txt").read_text(encoding="utf-8") == "hello world\n"


def test_order_dependent_whole_file_op_conflict_is_refused(project: Path) -> None:
    """A whole-file operation on a path another member also edits is order-dependent and refused, not composed."""
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    sha = sha256_bytes((project / "A.txt").read_bytes())
    driver.program(_text_envelope(project, "s1", "A.txt", 0, 5, "HELLO"))
    delete_envelope = {
        "accepted": True,
        "session": {"sessionId": "s2", "projectRevision": "r1"},
        "preview": {
            "workspaceEdit": {
                "changes": [],
                "fileOperations": [{"kind": "delete", "path": "A.txt", "oldSha256": sha}],
                "warnings": [],
            }
        },
    }
    driver.program(delete_envelope)
    workspace.add_session("op", {})
    workspace.add_session("op", {})

    preview = workspace.preview()
    assert preview["accepted"] is False
    assert preview["refusal"]["code"] == "workspace_session_file_conflict"
    assert (project / "A.txt").read_text(encoding="utf-8") == "hello world\n"


def test_apply_commits_transactionally(project: Path) -> None:
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there"))
    driver.program(_text_envelope(project, "s2", "B.txt", 0, 3, "baz"))
    workspace.add_session("op", {})
    workspace.add_session("op", {})

    result = manager.apply(workspace.workspace_id, allow_review_required=True)
    assert result["accepted"] and result["applied"]
    assert result["status"] == WorkspaceStatus.APPLIED.value
    assert (project / "A.txt").read_text(encoding="utf-8") == "hello there\n"
    assert (project / "B.txt").read_text(encoding="utf-8") == "baz bar\n"
    # member sessions are released after a successful apply
    assert set(driver.cancelled) == {"s1", "s2"}
    assert all(ref.applied for ref in workspace.sessions)


def test_apply_unsafe_edit_is_refused_without_writing(project: Path) -> None:
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    # a wrong hash precondition makes the composed edit unstageable
    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there", old_sha256="0" * 64))
    workspace.add_session("op", {})

    result = workspace.apply(allow_review_required=True)
    assert result["accepted"] is False
    assert result["refusal"]["code"] == "workspace_unsafe_edit"
    assert result["status"] == WorkspaceStatus.OPEN.value
    # nothing was written
    assert (project / "A.txt").read_text(encoding="utf-8") == "hello world\n"


def test_empty_workspace_preview_is_refused(project: Path) -> None:
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    result = workspace.preview()
    assert result["accepted"] is False
    assert result["refusal"]["code"] == "workspace_empty"


def test_cancel_releases_sessions_and_drops_workspace(project: Path) -> None:
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()
    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there"))
    workspace.add_session("op", {})

    result = manager.cancel(workspace.workspace_id)
    assert result["accepted"]
    assert result["status"] == WorkspaceStatus.CANCELLED.value
    assert driver.cancelled == ["s1"]
    assert manager.get_workspace(workspace.workspace_id) is None


def test_warnings_raise_risk_to_review_required(project: Path) -> None:
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()
    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there", warnings=["reflective string reference"]))
    workspace.add_session("op", {})

    preview = workspace.preview()
    assert preview["accepted"]
    assert preview["risk"] == "REVIEW_REQUIRED"
    assert preview["warnings"] == ["reflective string reference"]


def test_overflow_eviction_releases_lru_workspace(project: Path) -> None:
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver, max_workspaces=2)
    first = manager.create_workspace()
    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there"))
    first.add_session("op", {})
    manager.create_workspace()
    manager.create_workspace()  # exceeds capacity -> evicts the LRU (first)

    assert manager.get_workspace(first.workspace_id) is None
    assert first.status == WorkspaceStatus.EVICTED
    assert driver.cancelled == ["s1"]


def test_ttl_eviction_reclaims_idle_workspace(project: Path) -> None:
    clock = {"now": 1000.0}
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver, ttl_seconds=100.0, clock=lambda: clock["now"])
    workspace = manager.create_workspace()
    workspace_id = workspace.workspace_id

    clock["now"] = 1200.0  # advance beyond the TTL
    assert manager.get_workspace(workspace_id) is None


def test_unknown_workspace_is_refused() -> None:
    driver = StubDriver(Path("/tmp"))
    manager = TransformationWorkspaceManager(driver)
    result = manager.apply("does-not-exist")
    assert result["accepted"] is False
    assert result["refusal"]["code"] == "workspace_not_found"


def test_impact_report_routes_composed_edit_to_builder(project: Path) -> None:
    # G011: the impact-report router composes the (homogeneous) workspace plan WITHOUT staging/writing and hands
    # the merged edit, its risk, and a representative operation to the caller's graph-backed builder.
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()
    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there"))
    driver.program(_text_envelope(project, "s2", "B.txt", 0, 3, "baz"))
    assert workspace.add_session("renamePackage", {})["accepted"]
    assert workspace.add_session("renamePackage", {})["accepted"]

    seen: dict[str, Any] = {}

    def build(edit: Any, risk: RiskLevel, operation: str) -> dict[str, Any]:
        seen["touched"] = sorted(edit.touched_files())
        seen["risk"] = risk
        seen["operation"] = operation
        return {"sections": 5}

    result = manager.impact_report(workspace.workspace_id, build)

    assert result["accepted"] is True
    assert result["mode"] == "impact_report"
    assert result["operation"] == "renamePackage"  # homogeneous workspace -> the single member operation
    assert result["risk"] == "SAFE"
    assert result["report"] == {"sections": 5}
    # the builder received the composed, disjoint edit and a real RiskLevel.
    assert seen["touched"] == ["A.txt", "B.txt"]
    assert isinstance(seen["risk"], RiskLevel)
    assert seen["operation"] == "renamePackage"
    # an impact report is read-only: it neither writes nor advances the workspace past OPEN.
    assert workspace.status == WorkspaceStatus.OPEN
    assert (project / "A.txt").read_text(encoding="utf-8") == "hello world\n"


def test_impact_report_labels_heterogeneous_workspace_as_composed(project: Path) -> None:
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()
    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there"))
    driver.program(_text_envelope(project, "s2", "B.txt", 0, 3, "baz"))
    assert workspace.add_session("renamePackage", {})["accepted"]
    assert workspace.add_session("deepInlineMethod", {})["accepted"]

    result = manager.impact_report(workspace.workspace_id, lambda edit, risk, operation: {"operation": operation})
    assert result["accepted"] is True
    assert result["operation"] == "composedWorkspace"  # mixed member operations
    assert result["report"] == {"operation": "composedWorkspace"}


def test_impact_report_unknown_workspace_refuses_without_building() -> None:
    driver = StubDriver(Path("/tmp"))
    manager = TransformationWorkspaceManager(driver)
    calls: list[Any] = []
    result = manager.impact_report("does-not-exist", lambda *a: calls.append(a) or {})
    assert result["accepted"] is False
    assert result["refusal"]["code"] == "workspace_not_found"
    assert calls == []  # the builder is never invoked for an absent workspace


def test_impact_report_empty_workspace_refuses_without_building(project: Path) -> None:
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()
    calls: list[Any] = []
    result = manager.impact_report(workspace.workspace_id, lambda *a: calls.append(a) or {})
    assert result["accepted"] is False
    assert result["refusal"]["code"] == "workspace_empty"
    assert calls == []


def test_mixed_workspace_composes_v2_session_and_v3_op(project: Path) -> None:
    """G2: a workspace enrolls a V2 session AND a V3 op, then composes ONE combined preview/apply over both.

    The single preview lists both members' edits with one validation pass and aggregated stats; the transactional apply
    writes both files all-or-nothing; the revision pin holds across the two member kinds; and the V2 session is released
    on apply while the V3 member (which has no sidecar session) is dropped, leaving every member marked applied.
    """
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    # one V2 session edits A.txt, one V3 op edits B.txt (disjoint files) at the same pinned revision r1.
    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there", revision="r1"))
    driver.program_v3(_v3_plan(project, "extractClass", "B.txt", 0, 3, "baz", revision="r1"))

    v2 = manager.add_session(workspace.workspace_id, "renameMember", {})
    v3 = manager.add_operation(workspace.workspace_id, "extractClass", {})
    assert v2["accepted"] and v2["mode"] == "add_session"
    assert v3["accepted"] and v3["mode"] == "add_operation"
    assert workspace.project_revision == "r1"
    assert len(workspace.sessions) == 2
    # the two members are of distinct kinds and both carry an enrolled edit.
    kinds = {ref.member_kind for ref in workspace.sessions}
    assert kinds == {"v2", "v3"}

    # ONE combined preview: stats/impact aggregate BOTH members' edits in a single validation pass.
    preview = manager.preview(workspace.workspace_id)
    assert preview["accepted"] and preview["applied"] is False
    assert preview["status"] == WorkspaceStatus.PREVIEWED.value
    assert preview["sessionCount"] == 2
    assert preview["stats"]["fileCount"] == 2
    assert preview["stats"]["editCount"] == 2
    assert preview["stats"]["modifiedFiles"] == 2
    assert sorted(stat["path"] for stat in preview["stats"]["files"]) == ["A.txt", "B.txt"]

    # transactional apply commits BOTH members' edits all-or-nothing.
    result = manager.apply(workspace.workspace_id, allow_review_required=True)
    assert result["accepted"] and result["applied"]
    assert result["status"] == WorkspaceStatus.APPLIED.value
    assert sorted(result["touchedFiles"]) == ["A.txt", "B.txt"]
    assert (project / "A.txt").read_text(encoding="utf-8") == "hello there\n"
    assert (project / "B.txt").read_text(encoding="utf-8") == "baz bar\n"
    # the V2 session is released; the V3 op has no sidecar session to cancel; both members are marked applied.
    assert driver.cancelled == ["s1"]
    assert all(ref.applied for ref in workspace.sessions)


def test_mixed_workspace_revision_mismatch_is_refused(project: Path) -> None:
    """A V3 op planned against a different revision than the pinned V2 session is refused (nothing enrolled)."""
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there", revision="r1"))
    driver.program_v3(_v3_plan(project, "deepInlineMethod", "B.txt", 0, 3, "baz", revision="r2"))
    assert workspace.add_session("renameMember", {})["accepted"]

    mismatch = workspace.add_operation("deepInlineMethod", {})
    assert mismatch["accepted"] is False
    assert mismatch["refusal"]["code"] == "workspace_revision_mismatch"
    # the V3 op was not enrolled.
    assert len(workspace.sessions) == 1


def test_cross_member_non_overlapping_v2_v3_compose(project: Path) -> None:
    """A V2 session and a V3 op editing DISJOINT ranges of the same file compose into one transactional apply."""
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program(_text_envelope(project, "s1", "A.txt", 0, 5, "HELLO"))
    driver.program_v3(_v3_plan(project, "extractClass", "A.txt", 6, 11, "WORLD"))
    assert workspace.add_session("renameMember", {})["accepted"]
    assert workspace.add_operation("extractClass", {})["accepted"]

    preview = workspace.preview()
    assert preview["accepted"]
    assert preview["risk"] == "REVIEW_REQUIRED"

    apply = manager.apply(workspace.workspace_id, allow_review_required=True)
    assert apply["accepted"] and apply["applied"]
    assert (project / "A.txt").read_text(encoding="utf-8") == "HELLO WORLD\n"


def test_cross_member_overlapping_v2_v3_is_refused(project: Path) -> None:
    """A V2 session and a V3 op with OVERLAPPING ranges on one file are refused, nothing applied."""
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program(_text_envelope(project, "s1", "A.txt", 0, 6, "HELLO_"))
    driver.program_v3(_v3_plan(project, "extractClass", "A.txt", 5, 11, "_WORLD"))
    assert workspace.add_session("renameMember", {})["accepted"]
    assert workspace.add_operation("extractClass", {})["accepted"]

    preview = workspace.preview()
    assert preview["accepted"] is False
    assert preview["refusal"]["code"] == "workspace_session_file_conflict"

    apply = manager.apply(workspace.workspace_id, allow_review_required=True)
    assert apply["accepted"] is False
    assert apply["refusal"]["code"] == "workspace_session_file_conflict"
    # nothing was written.
    assert (project / "A.txt").read_text(encoding="utf-8") == "hello world\n"


def test_cross_member_non_overlapping_v3_v3_compose(project: Path) -> None:
    """Two V3 ops editing DISJOINT ranges of one file compose (V3<->V3 parity with V2<->V2)."""
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program_v3(_v3_plan(project, "extractClass", "A.txt", 0, 5, "HELLO"))
    driver.program_v3(_v3_plan(project, "deepInlineMethod", "A.txt", 6, 11, "WORLD"))
    assert workspace.add_operation("extractClass", {})["accepted"]
    assert workspace.add_operation("deepInlineMethod", {})["accepted"]

    preview = workspace.preview()
    assert preview["accepted"]
    assert preview["risk"] == "REVIEW_REQUIRED"

    apply = manager.apply(workspace.workspace_id, allow_review_required=True)
    assert apply["accepted"] and apply["applied"]
    assert (project / "A.txt").read_text(encoding="utf-8") == "HELLO WORLD\n"


def test_mixed_workspace_cancel_drops_v3_and_cancels_v2(project: Path) -> None:
    """G2 cancellation: cancelling a mixed workspace cancels the V2 session and drops the V3 op (none left)."""
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there"))
    driver.program_v3(_v3_plan(project, "extractClass", "B.txt", 0, 3, "baz"))
    assert workspace.add_session("renameMember", {})["accepted"]
    assert workspace.add_operation("extractClass", {})["accepted"]
    assert len(workspace.sessions) == 2

    result = manager.cancel(workspace.workspace_id)
    assert result["accepted"]
    assert result["status"] == WorkspaceStatus.CANCELLED.value
    # only the V2 session is cancelled in the sidecar; the V3 op had no session to release.
    assert driver.cancelled == ["s1"]
    # the workspace is dropped from the manager registry and is terminal with nothing to compose.
    assert manager.get_workspace(workspace.workspace_id) is None
    assert workspace.status == WorkspaceStatus.CANCELLED
    assert (project / "A.txt").read_text(encoding="utf-8") == "hello world\n"
    assert (project / "B.txt").read_text(encoding="utf-8") == "foo bar\n"


def test_add_operation_propagates_v3_refusal_without_enrolling(project: Path) -> None:
    """A driver V3-plan refusal (e.g. a sidecar decline) is surfaced verbatim and never enrolled."""
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program_v3(
        {
            "accepted": False,
            "applied": False,
            "operation": "extractClass",
            "mode": "preview",
            "refusal": {"code": "no_members", "message": "no members selected"},
        }
    )
    result = workspace.add_operation("extractClass", {})
    assert result["accepted"] is False
    assert result["refusal"]["code"] == "no_members"
    assert result["planRefusal"]["refusal"]["code"] == "no_members"
    assert len(workspace.sessions) == 0


def test_mixed_workspace_warning_raises_risk_to_review_required(project: Path) -> None:
    """A warning on EITHER member kind raises the composed workspace risk to REVIEW_REQUIRED."""
    driver = StubDriver(project)
    manager = TransformationWorkspaceManager(driver)
    workspace = manager.create_workspace()

    driver.program(_text_envelope(project, "s1", "A.txt", 6, 11, "there"))
    driver.program_v3(_v3_plan(project, "extractClass", "B.txt", 0, 3, "baz", warnings=["reflective reference"]))
    assert workspace.add_session("renameMember", {})["accepted"]
    assert workspace.add_operation("extractClass", {})["accepted"]

    preview = workspace.preview()
    assert preview["accepted"]
    assert preview["risk"] == "REVIEW_REQUIRED"
    assert preview["warnings"] == ["reflective reference"]


def test_every_emitted_refusal_code_is_registered(project: Path) -> None:
    # the registry single-sources documentation for every workspace refusal code (G011 prerequisite)
    for code in (
        "workspace_not_found",
        "workspace_terminal",
        "workspace_empty",
        "workspace_session_not_accepted",
        "workspace_revision_mismatch",
        "workspace_session_file_conflict",
        "workspace_unsafe_edit",
        "workspace_apply_failed",
    ):
        assert V3_REFUSAL_REGISTRY.get(code)
