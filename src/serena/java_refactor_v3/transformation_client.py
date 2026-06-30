"""Thin Python client for the sidecar ``transformation.*`` protocol (refactor-feature-plan-V3.md §1.1/§2).

Phase 1 moves transformation-workspace *composition* into the Java sidecar: it runs the named V3 operation
planner(s), composes their edits into one workspace, validates the composed overlay once via javac, and returns an
authoritative, preview-ready workspace edit. This module is the Python side of that protocol — a stateless wrapper
over a live :class:`~serena.java_refactor.client.JavaRefactorClient` that forwards each ``transformation.*`` request
and returns the sidecar's JSON result verbatim.

The client deliberately holds no composition logic of its own: same-file composition and true-overlap refusal are the
sidecar's responsibility now (the old Python-side same-file refusal is retired). It only shapes requests and returns
results, so the canonical accepted/refused envelopes the sidecar produces are surfaced unchanged to callers and tools.
"""

from __future__ import annotations

from typing import Any

from serena.java_refactor.client import JavaRefactorClient


class TransformationClient:
    """Forwards ``transformation.*`` requests to the Java refactoring sidecar.

    Every method returns the sidecar's result payload as a plain ``dict``. An accepted result carries
    ``accepted: true``; a refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``).
    """

    def __init__(self, client: JavaRefactorClient) -> None:
        """:param client: a started (or startable) sidecar client used as the JSON-lines transport."""
        self._client = client

    def create_workspace(
        self,
        operation: str | None = None,
        arguments: dict[str, Any] | None = None,
        *,
        goal: str | None = None,
        operations: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        """Creates a preview-ready transformation workspace from one operation (or a batch of ``operations``).

        Pass either a single ``operation`` + ``arguments`` (the common case) or an ``operations`` list of
        ``{"operation", "arguments"}`` entries. ``goal`` is an optional human-readable label echoed back in the
        summary and ``transformation.list``.
        """
        params: dict[str, Any] = {}
        if goal is not None:
            params["goal"] = goal
        if operations is not None:
            params["operations"] = operations
        else:
            if operation is None:
                raise ValueError("create_workspace requires an operation or an operations list")
            params["operation"] = operation
            params["arguments"] = arguments or {}
        return self._client._request("transformation.createWorkspace", {"params": params})

    def add_operation(
        self, workspace_id: str, operation: str, arguments: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        """Adds a V3 operation to an existing preview-ready transformation workspace."""
        return self._client._request(
            "transformation.addOperation",
            {"params": {"workspaceId": workspace_id, "operation": operation, "arguments": arguments or {}}},
        )

    def add_session(self, workspace_id: str, session_id: str) -> dict[str, Any]:
        """Adds an existing V2 preview session edit to an existing transformation workspace."""
        return self._client._request(
            "transformation.addSession", {"params": {"workspaceId": workspace_id, "sessionId": session_id}}
        )

    def preview(self, workspace_id: str) -> dict[str, Any]:
        return self._client._request("transformation.preview", {"params": {"workspaceId": workspace_id}})

    def apply(self, workspace_id: str, expected_project_revision: Any = None) -> dict[str, Any]:
        """Prepares the authoritative validated edit for a transactional Python applier.

        The sidecar never writes files and does not mark the workspace applied; callers that need disk mutation must
        commit the returned ``workspaceEdit`` through Serena's transactional applier.
        """
        params: dict[str, Any] = {"workspaceId": workspace_id}
        if expected_project_revision is not None:
            params["expectedProjectRevision"] = expected_project_revision
        return self._client._request("transformation.apply", {"params": params})

    def ack_apply(self, workspace_id: str) -> dict[str, Any]:
        """Marks a prepared workspace applied after the caller has committed the returned edit transactionally."""
        return self._client._request("transformation.ackApply", {"params": {"workspaceId": workspace_id}})

    def cancel(self, workspace_id: str) -> dict[str, Any]:
        """Evicts a workspace. Idempotent: cancelling a gone workspace yields a terminal refusal, not an error."""
        return self._client._request("transformation.cancel", {"params": {"workspaceId": workspace_id}})

    def list(self) -> dict[str, Any]:
        return self._client._request("transformation.list", {"params": {}})

    def report(self, workspace_id: str) -> dict[str, Any]:
        """Returns the authoritative five-section impact report for a workspace (refactor-feature-plan-V3.md §17).

        Every section is genuinely computed by the sidecar with real javac — there are no ``computed:false``
        placeholders. The ``report`` object carries ``summary`` (operation/risk/filesChanged/javaFilesMoved/
        resourceFilesChanged/newCompileErrors), ``semanticImpact`` (typesMoved/publicApisChanged/overridesAffected/
        callSitesChanged), ``resourceImpact`` (serviceLoaderFilesChanged/xmlFilesChanged/
        reflectionCandidatesNotChanged), ``tests`` (suggestedTestCommands/likelyAffectedTests), and ``warnings``.
        """
        return self._client._request("transformation.report", {"params": {"workspaceId": workspace_id}})
