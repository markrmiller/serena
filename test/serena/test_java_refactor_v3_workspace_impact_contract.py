from __future__ import annotations

from types import SimpleNamespace
from typing import cast

from serena.config.serena_config import JavaRefactorConfig
from serena.java_refactor.manager import JavaRefactorManager


_DEFAULT_REVISION = object()

V3_IMPACT_REPORT = {
    "summary": {"filesChanged": ["src/main/java/app/A.java"], "risk": "SAFE"},
    "semanticImpact": {"javaMoves": [], "publicApiChanges": []},
    "resourceImpact": {"resourcesChanged": [], "serviceLoaderImpact": []},
    "tests": {"suggested": ["AppTest"], "likelyAffected": []},
    "warnings": [],
}


class _WorkspaceStub:
    def __init__(self) -> None:
        self.apply_calls: list[dict[str, object]] = []
        self.preview_calls: list[str] = []
        self.workspace = SimpleNamespace(project_revision={"projectRevision": "rev-1"})

    def get_workspace(self, workspace_id: str) -> SimpleNamespace:
        return self.workspace

    def preview(self, workspace_id: str) -> dict[str, object]:
        self.preview_calls.append(workspace_id)
        return {"accepted": True, "workspaceId": workspace_id, "risk": "SAFE"}

    def apply(
        self,
        workspace_id: str,
        *,
        validate: bool | None = None,
        expected_project_revision: object | None = None,
        allow_review_required: bool = False,
    ) -> dict[str, object]:
        self.apply_calls.append(
            {
                "workspace_id": workspace_id,
                "validate": validate,
                "expected_project_revision": expected_project_revision,
                "allow_review_required": allow_review_required,
            }
        )
        return {"accepted": True, "workspaceId": workspace_id, "applied": True}


class _FailingApplyWorkspace(_WorkspaceStub):
    def apply(self, *args: object, **kwargs: object) -> dict[str, object]:  # pragma: no cover - failure path assertion
        raise AssertionError("workspace apply must not run after impact-report refusal")


class _TestManager(JavaRefactorManager):
    def __init__(
        self,
        workspace: _WorkspaceStub,
        impact_result: dict[str, object],
        current_revision: object | None = _DEFAULT_REVISION,
    ) -> None:
        self._config = cast(JavaRefactorConfig, SimpleNamespace(enabled=True))
        self._workspace_stub = workspace
        self._impact_result = impact_result
        self._current_revision = {"projectRevision": "rev-1"} if current_revision is _DEFAULT_REVISION else current_revision

    @property
    def transformation_workspaces(self) -> _WorkspaceStub:  # type: ignore[override]
        return self._workspace_stub

    def _validate_supported_project(self) -> None:
        return None

    def transformation_workspace_impact_report(  # type: ignore[override]
        self,
        workspace_id: str,
        include_tests: bool = True,
        include_resources: bool = True,
    ) -> dict[str, object]:
        return self._impact_result

    def _current_v3_project_revision(self) -> object:  # type: ignore[override]
        return self._current_revision


def _manager_with_workspace(
    workspace: _WorkspaceStub,
    impact_result: dict[str, object],
    current_revision: object | None = _DEFAULT_REVISION,
) -> JavaRefactorManager:
    return _TestManager(workspace, impact_result, current_revision=current_revision)


def test_workspace_preview_attaches_graph_backed_v3_impact_report() -> None:
    workspace = _WorkspaceStub()
    manager = _manager_with_workspace(workspace, {"accepted": True, "report": V3_IMPACT_REPORT})

    result = manager.transformation_workspace_preview("workspace-1")

    assert result["accepted"] is True
    assert result["impactReport"] == V3_IMPACT_REPORT
    assert result["impactReportSource"] == "sidecar_facts"
    assert workspace.preview_calls == ["workspace-1"]


def test_workspace_apply_preflights_and_attaches_graph_backed_v3_impact_report() -> None:
    workspace = _WorkspaceStub()
    manager = _manager_with_workspace(workspace, {"accepted": True, "report": V3_IMPACT_REPORT})
    expected_revision = {"projectRevision": "rev-1"}

    result = manager.transformation_workspace_apply(
        "workspace-2",
        validate=True,
        expected_project_revision=expected_revision,
        allow_review_required=True,
    )

    assert result["accepted"] is True
    assert result["impactReport"] == V3_IMPACT_REPORT
    assert result["impactReportSource"] == "sidecar_facts"
    assert workspace.apply_calls == [
        {
            "workspace_id": "workspace-2",
            "validate": True,
            "expected_project_revision": expected_revision,
            "allow_review_required": True,
        }
    ]


def test_workspace_apply_refuses_before_write_when_primary_impact_report_refuses() -> None:
    refusal = {
        "accepted": False,
        "refusal": {
            "code": "workspace_impact_report_unavailable",
            "message": "sidecar facts unavailable",
        },
    }
    manager = _manager_with_workspace(_FailingApplyWorkspace(), refusal)

    result = manager.transformation_workspace_apply("workspace-3")

    assert result["accepted"] is False
    assert result["refusal"] == refusal["refusal"]
    assert result["workspaceId"] == "workspace-3"
    assert result["mode"] == "apply"


def test_workspace_apply_refuses_live_revision_drift_before_local_write() -> None:
    workspace = _WorkspaceStub()
    manager = _manager_with_workspace(
        workspace,
        {"accepted": True, "report": V3_IMPACT_REPORT},
    )
    manager._current_revision = {"projectRevision": "rev-2"}  # type: ignore[attr-defined]

    result = manager.transformation_workspace_apply("workspace-4")

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "workspace_revision_mismatch"
    assert workspace.apply_calls == []


def test_workspace_apply_refuses_when_live_revision_is_unavailable() -> None:
    workspace = _WorkspaceStub()
    manager = _manager_with_workspace(
        workspace,
        {"accepted": True, "report": V3_IMPACT_REPORT},
        current_revision=None,
    )

    result = manager.transformation_workspace_apply("workspace-5")

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "workspace_revision_unavailable"
    assert workspace.apply_calls == []
