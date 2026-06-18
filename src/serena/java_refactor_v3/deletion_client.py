"""Thin Python client for the sidecar ``deletion.*`` protocol (refactor-feature-plan-V3.md §7).

Phase 3 puts the propagating safe-delete cascade and the dead-code scan in the Java sidecar, where javac's
``Trees``/``Elements`` reachability is authoritative. This module is the Python side: a stateless wrapper over a live
:class:`~serena.java_refactor.client.JavaRefactorClient` that forwards each ``deletion.*`` request and returns the
sidecar's JSON result verbatim.

``propagate_safe_delete`` returns the graph-shaped ``deletePlan`` (``requested``/``cascade``/``blocked``) plus a
removing ``workspaceEdit`` that the sidecar has already run through its before/after javac validator; the sidecar never
writes files, so the caller's transactional applier owns apply. ``find_dead_code`` returns ``deadCodeCandidates`` and is
purely analytical — it never produces an edit.
"""

from __future__ import annotations

from typing import Any

from serena.java_refactor.client import JavaRefactorClient


class DeletionClient:
    """Forwards ``deletion.*`` requests to the Java refactoring sidecar.

    Every method returns the sidecar's result payload as a plain ``dict``. An accepted result carries
    ``accepted: true``; a refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``).
    """

    def __init__(self, client: JavaRefactorClient) -> None:
        """:param client: a started (or startable) sidecar client used as the JSON-lines transport."""
        self._client = client

    def propagate_safe_delete(
        self,
        roots: list[Any],
        *,
        delete_private_only: bool = True,
        include_tests: bool = False,
        include_resources: bool = True,
        max_cascade_depth: int = 5,
        validate: bool = True,
    ) -> dict[str, Any]:
        """Plans a propagating safe delete from ``roots`` (canonical symbol keys or ``{relativePath, line, column}``).

        Returns the ``deletePlan`` graph plus the javac-validated ``workspaceEdit``. ``delete_private_only`` keeps
        public/protected API as never-auto-deletable roots; ``include_tests`` lets the cascade reach test symbols;
        ``include_resources`` enables ``META-INF/services`` provider-line rewriting; ``validate`` runs the sidecar's
        before/after javac validation over the composed overlay (leave on unless previewing a known-broken cascade).
        """
        params: dict[str, Any] = {
            "roots": roots,
            "deletePrivateOnly": delete_private_only,
            "includeTests": include_tests,
            "includeResources": include_resources,
            "maxCascadeDepth": max_cascade_depth,
            "validate": validate,
        }
        return self._client._request("deletion.propagateSafeDelete", {"params": params})

    def find_dead_code(
        self,
        *,
        scope: str = "project",
        include_tests: bool = False,
        public_api_policy: str = "keep",
    ) -> dict[str, Any]:
        """Scans for dead-code candidates (``deadCodeCandidates``); never applies a deletion.

        ``public_api_policy`` is one of ``keep`` (public/protected API is kept and never reported, the default),
        ``warn`` (unreferenced public/protected symbols are reported as candidates with a public-API-boundary warning),
        or ``allow`` (public-API status is ignored, such symbols are treated like internal ones). The legacy value
        ``report`` is accepted as an alias for ``warn``. ``include_tests`` includes test source sets in the scan.
        """
        params: dict[str, Any] = {
            "scope": scope,
            "includeTests": include_tests,
            "publicApiPolicy": public_api_policy,
        }
        return self._client._request("deletion.findDeadCode", {"params": params})
