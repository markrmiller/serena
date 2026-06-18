"""Review Gap 14: uniform risk classification + enforced apply policy (no default-medium).

refactor-feature-plan-V3.md §18 ("Validation and safety") requires a single canonical risk taxonomy
(SAFE / REVIEW_REQUIRED / REFUSED) and ONE canonical apply-enforcement seam:

* every accepted V3 edit is classified from an explicit sidecar value -- the bridge NEVER defaults an
  unknown/missing payload to a guessed "medium" classification;
* SAFE applies; REVIEW_REQUIRED is blocked UNLESS the uniform ``allow_review_required`` control opts in;
  REFUSED never applies;
* the aggregate (the sidecar's composed risk, raising REVIEW_REQUIRED when a resource/framework/build/delete
  fact participates) is honoured at the bridge.

These tests pin that behaviour at the canonical Python seams -- :meth:`RiskLevel.from_sidecar_wire`,
:meth:`JavaRefactorManager._route_sidecar_v3_edit` (normalisation, no default-medium) and
:meth:`JavaRefactorManager._bridge_v3_edit` (the uniform apply-policy gate) -- without spinning up the JVM
sidecar, so the policy decision is asserted in isolation and deterministically.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor import manager as manager_module
from serena.java_refactor.manager import JavaRefactorManager
from serena.java_refactor.workspace_edit import RefactorWorkspaceEdit, WorkspaceEditPreview
from serena.java_refactor_v3.models import RiskLevel
from solidlsp.ls_config import Language

# ── canonical normalisation: sidecar wire risk -> RiskLevel, with NO default-to-medium ────────────────────────────


def test_from_sidecar_wire_maps_safe_and_needs_review() -> None:
    # The two accepted-edit wire values the sidecar's CanonicalEnvelope emits map onto the canonical taxonomy.
    assert RiskLevel.from_sidecar_wire("safe") is RiskLevel.SAFE
    assert RiskLevel.from_sidecar_wire("needs_review") is RiskLevel.REVIEW_REQUIRED


def test_from_sidecar_wire_refuses_medium_explicitly() -> None:
    # The crux of the gap: "medium" is NOT a value the sidecar emits, and the normaliser must never accept it as a
    # silent middle ground -- it raises so an unclassified payload is a loud programming error, never a guess.
    with pytest.raises(ValueError) as excinfo:
        RiskLevel.from_sidecar_wire("medium")
    message = str(excinfo.value)
    assert "medium" in message
    assert "unclassified" in message.lower()


@pytest.mark.parametrize("bad", [None, "", "informational", "unknown", "REVIEW_REQUIRED"])
def test_from_sidecar_wire_refuses_unknown_or_missing(bad: Any) -> None:
    # A missing (None) or otherwise unrecognised wire value -- including the read-only-scan marker "informational"
    # which must never reach the edit-apply bridge -- raises rather than returning a default classification.
    with pytest.raises(ValueError):
        RiskLevel.from_sidecar_wire(bad)


# ── test scaffolding: a manager with the JVM-backed collaborators of _bridge_v3_edit stubbed out ──────────────────


class _FakeStaged:
    """Stands in for a StagedEdit: only ``preview`` and ``overlay()`` are read by the bridge under test."""

    def __init__(self) -> None:
        self.preview = WorkspaceEditPreview(
            touched_files=["src/main/java/com/acme/A.java"],
            edit_count=1,
            file_operation_count=0,
            warnings=[],
            preconditions=[],
            stats={"modified": 1},
        )

    def overlay(self) -> dict[str, Any]:
        return {"changedFiles": {}, "deletedFiles": [], "renamedFiles": []}


class _FakeApplier:
    """A no-write applier so the apply pipeline can complete without touching disk or the sidecar.

    The class is instantiated by the bridge as ``TransactionalWorkspaceEditApplier(root, encoding=..., line_ending=...)``;
    its ``commit`` records that a commit happened so a test can assert the gate did (or did not) reach the write.
    """

    committed = False

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        pass

    def stage(self, workspace_edit: RefactorWorkspaceEdit) -> _FakeStaged:
        return _FakeStaged()

    def snapshot(self, workspace_edit: RefactorWorkspaceEdit) -> dict:
        return {}

    def commit(self, staged: _FakeStaged) -> Any:
        type(self).committed = True
        return staged.preview

    def restore(self, snapshot: dict) -> None:
        pass


class _ReadyStatus:
    ready = True
    errors: list = []
    project_model: dict = {}


class _FakeClient:
    def status(self, refresh: bool = False) -> _ReadyStatus:
        return _ReadyStatus()


def _manager_for_bridge(tmp_path: Path, monkeypatch) -> JavaRefactorManager:
    """Builds a manager whose _bridge_v3_edit collaborators are stubbed so only the policy decision is exercised."""
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        # Turn validation knobs off so the apply path does not call into the (absent) sidecar javac; the apply-policy
        # gate runs BEFORE validation regardless, so this only simplifies the SAFE/allowed apply completion.
        java_refactor_config=JavaRefactorConfig(
            enabled=True, validate_before_apply=False, validate_after_preview=False, allow_incomplete_analysis=False
        ),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: _FakeClient())
    monkeypatch.setattr(manager, "_degraded_model_apply_refusal", lambda client, operation: None)
    monkeypatch.setattr(manager_module, "TransactionalWorkspaceEditApplier", _FakeApplier)
    _FakeApplier.committed = False
    return manager


def _empty_edit() -> RefactorWorkspaceEdit:
    return RefactorWorkspaceEdit()


# ── the uniform apply-policy gate at the canonical _bridge_v3_edit seam ────────────────────────────────────────────


def test_safe_edit_applies(tmp_path: Path, monkeypatch) -> None:
    # A SAFE-classified edit applies: it is accepted, marked applied, and the transactional commit is reached.
    manager = _manager_for_bridge(tmp_path, monkeypatch)
    result = manager._bridge_v3_edit(
        operation="extractClass",
        workspace_edit=_empty_edit(),
        apply=True,
        validate=False,
        risk=RiskLevel.SAFE,
        warnings=[],
        summary={},
    )
    assert result["accepted"] is True, result
    assert result["applied"] is True, result
    assert result["risk"] == "SAFE", result
    assert _FakeApplier.committed is True, "a SAFE apply must reach the transactional commit"


def test_review_required_edit_is_blocked_by_default(tmp_path: Path, monkeypatch) -> None:
    # A REVIEW_REQUIRED edit is BLOCKED on apply when allow_review_required is not set: it refuses with the uniform
    # `review_required` code, applies nothing, and never reaches the workspace-mutating commit.
    manager = _manager_for_bridge(tmp_path, monkeypatch)
    result = manager._bridge_v3_edit(
        operation="extractClass",
        workspace_edit=_empty_edit(),
        apply=True,
        validate=False,
        risk=RiskLevel.REVIEW_REQUIRED,
        allow_review_required=False,
        warnings=[],
        summary={},
    )
    assert result["accepted"] is False, result
    assert result["applied"] is False, result
    assert result["refusal"]["code"] == "review_required", result
    assert result["risk"] == "REVIEW_REQUIRED", result
    assert _FakeApplier.committed is False, "a blocked REVIEW_REQUIRED edit must write nothing"


def test_review_required_edit_applies_with_uniform_allow_control(tmp_path: Path, monkeypatch) -> None:
    # The SAME REVIEW_REQUIRED edit applies once the uniform allow_review_required control opts in.
    manager = _manager_for_bridge(tmp_path, monkeypatch)
    result = manager._bridge_v3_edit(
        operation="extractClass",
        workspace_edit=_empty_edit(),
        apply=True,
        validate=False,
        risk=RiskLevel.REVIEW_REQUIRED,
        allow_review_required=True,
        warnings=[],
        summary={},
    )
    assert result["accepted"] is True, result
    assert result["applied"] is True, result
    assert _FakeApplier.committed is True, "REVIEW_REQUIRED must apply when explicitly allowed"


def test_review_required_preview_is_never_blocked(tmp_path: Path, monkeypatch) -> None:
    # The gate governs APPLY only: a preview of a REVIEW_REQUIRED edit is always permitted (it writes nothing), so a
    # reviewer can always inspect a needs-review change before deciding to apply it.
    manager = _manager_for_bridge(tmp_path, monkeypatch)
    result = manager._bridge_v3_edit(
        operation="extractClass",
        workspace_edit=_empty_edit(),
        apply=False,
        validate=False,
        risk=RiskLevel.REVIEW_REQUIRED,
        allow_review_required=False,
        warnings=[],
        summary={},
    )
    assert result["accepted"] is True, result
    assert result["applied"] is False, result
    assert "refusal" not in result, result


# ── no default-to-medium at the routing seam, and REFUSED never applies ───────────────────────────────────────────


def test_route_refuses_accepted_payload_without_explicit_risk(tmp_path: Path, monkeypatch) -> None:
    # A V3 sidecar payload that is accepted but carries NO explicit risk is a sidecar<->bridge contract violation. The
    # router must fail CLOSED -- refusing with `unclassified_risk` and applying nothing -- rather than defaulting the
    # payload to a guessed "medium" classification (the precise regression this gap eliminates).
    manager = _manager_for_bridge(tmp_path, monkeypatch)
    bridge_calls: list = []
    monkeypatch.setattr(manager, "_bridge_v3_edit", lambda **kw: bridge_calls.append(kw) or {"accepted": True})

    payload = {"accepted": True, "workspaceEdit": {"changes": [], "fileOperations": []}}  # no "risk" key
    result = manager._route_sidecar_v3_edit("extractClass", payload, apply=True, validate=False)

    assert result["accepted"] is False, result
    assert result["applied"] is False, result
    assert result["refusal"]["code"] == "unclassified_risk", result
    assert "medium" not in str(result).lower(), "the router must not fall back to a 'medium' classification"
    assert bridge_calls == [], "an unclassified payload must never reach the apply bridge"


def test_route_normalises_safe_payload_to_canonical_risk(tmp_path: Path, monkeypatch) -> None:
    # An accepted SAFE payload is normalised onto the canonical RiskLevel and forwarded to the bridge as RiskLevel.SAFE
    # (canonical "SAFE" on the result), never the lowercase wire token and never "medium".
    manager = _manager_for_bridge(tmp_path, monkeypatch)
    forwarded: dict = {}
    monkeypatch.setattr(
        manager,
        "_bridge_v3_edit",
        lambda **kw: forwarded.update(kw) or {"accepted": True, "applied": False, "risk": kw["risk"].value},
    )

    payload = {"accepted": True, "risk": "safe", "workspaceEdit": {"changes": [], "fileOperations": []}}
    result = manager._route_sidecar_v3_edit("extractClass", payload, apply=False, validate=False)

    assert forwarded["risk"] is RiskLevel.SAFE, forwarded
    assert result["risk"] == "SAFE", result


def test_route_passes_through_refused_payload_without_classifying(tmp_path: Path, monkeypatch) -> None:
    # A REFUSED result (accepted:false) is passed through verbatim and NEVER routed to the apply bridge: REFUSED can
    # never apply, and it carries no risk to classify (so it cannot trip the no-default-medium normalisation either).
    manager = _manager_for_bridge(tmp_path, monkeypatch)
    bridge_calls: list = []
    monkeypatch.setattr(manager, "_bridge_v3_edit", lambda **kw: bridge_calls.append(kw) or {"accepted": True})

    payload = {"accepted": False, "refusal": {"code": "extract_no_members", "message": "nothing to extract"}}
    result = manager._route_sidecar_v3_edit("extractClass", payload, apply=True, validate=False)

    assert result["accepted"] is False, result
    assert result["applied"] is False, result
    assert result["refusal"]["code"] == "extract_no_members", result
    assert bridge_calls == [], "a REFUSED payload must never reach the apply bridge"


# ── composition: the sidecar's aggregate (worst-of) risk is honoured at the bridge ────────────────────────────────


def test_composition_resource_or_framework_aggregate_blocks_apply(tmp_path: Path, monkeypatch) -> None:
    # Composition with the prior gaps: when the sidecar's CanonicalEnvelope raises the AGGREGATE risk to needs_review
    # because a change participates with a resource/framework/build/delete fact, that needs_review payload normalises to
    # REVIEW_REQUIRED and is blocked on apply by the uniform gate -- a resource/framework veto cannot be silently
    # applied. The SAFE counterpart with the same op applies, proving the aggregate (worst-of) drives the decision.
    manager = _manager_for_bridge(tmp_path, monkeypatch)

    needs_review_payload = {
        "accepted": True,
        "risk": "needs_review",  # aggregate raised by a resource-wired / framework-participating change
        "warnings": ["touches a resource-wired type"],
        "workspaceEdit": {"changes": [], "fileOperations": []},
    }
    blocked = manager._route_sidecar_v3_edit("propagateSafeDelete", needs_review_payload, apply=True, validate=False)
    assert blocked["accepted"] is False, blocked
    assert blocked["refusal"]["code"] == "review_required", blocked
    assert _FakeApplier.committed is False, "a composed REVIEW_REQUIRED aggregate must not apply by default"

    safe_payload = {
        "accepted": True,
        "risk": "safe",
        "warnings": [],
        "workspaceEdit": {"changes": [], "fileOperations": []},
    }
    applied = manager._route_sidecar_v3_edit("propagateSafeDelete", safe_payload, apply=True, validate=False)
    assert applied["accepted"] is True, applied
    assert applied["applied"] is True, applied
    assert _FakeApplier.committed is True, "the SAFE counterpart of the same op must apply"
