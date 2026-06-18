"""Thin Python client for the sidecar ``inlineRefactor.*`` protocol (refactor-feature-plan-V3.md §11).

Phase 5 puts a generalized (multi-statement) inline method in the Java sidecar. V2's inline supports only
single-expression methods; V3 inlines a ``private``, non-recursive method whose body is "straight-line" (local
declarations and expression statements with at most one trailing ``return``) into each of its call sites — substituting
parameters with arguments, hoisting side-effecting arguments into temporaries to preserve evaluation order, and
renaming inlined locals that would collide with the call-site scope. javac's ``Trees``/``Elements`` model is
authoritative for resolution and the conservative refusal lists.

This module is the Python side: a stateless wrapper over a live
:class:`~serena.java_refactor.client.JavaRefactorClient` that forwards each ``inlineRefactor.*`` request and returns the
sidecar's JSON result verbatim. The accepted result is a ``workspaceEdit`` (``changes`` + ``fileOperations``) the
sidecar has already run through its before/after javac validator (``diagnosticDeltaValidated: true``); the sidecar never
writes files, so the caller's transactional applier owns apply. A refusal carries ``accepted: false`` with a
``refusal`` object (``code``/``message``) drawn from the §11 supported-scope list.
"""

from __future__ import annotations

from typing import Any

from serena.java_refactor.client import JavaRefactorClient


class InlineRefactorClient:
    """Forwards ``inlineRefactor.*`` requests to the Java refactoring sidecar.

    Every method returns the sidecar's result payload as a plain ``dict``. An accepted result carries
    ``accepted: true``; a refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``).
    """

    def __init__(self, client: JavaRefactorClient) -> None:
        """:param client: a started (or startable) sidecar client used as the JSON-lines transport."""
        self._client = client

    def deep_inline_method(
        self,
        relative_path: str,
        line: int,
        *,
        column: int | None = None,
        method_name: str | None = None,
        delete_method: bool = False,
        max_call_sites: int | None = None,
        validate: bool = True,
    ) -> dict[str, Any]:
        """Plans a generalized inline method (§11) for the private method at ``line`` (1-based) in ``relative_path``.

        ``column`` (1-based) and ``method_name`` disambiguate the selected declaration when needed. When
        ``delete_method`` is set, the method declaration is removed once every call site is rewritten. Per §11 the
        operation is refused when the target is not a private method, is generic or recursive, has a non-straight-line
        body (loops/branches/early returns/``super``), has no call sites, or a call site is not a standalone statement.
        When ``max_call_sites`` is set it overrides the configured ``java_refactor.v3.inline.max_call_sites`` limit;
        if the found call-site count exceeds the effective limit, the operation is refused with
        ``deep_inline_too_many_call_sites``. ``validate`` runs the sidecar's before/after javac validation.
        """
        params: dict[str, Any] = {
            "relativePath": relative_path,
            "line": line,
            "deleteMethod": delete_method,
            "validate": validate,
        }
        if column is not None:
            params["column"] = column
        if method_name is not None:
            params["methodName"] = method_name
        if max_call_sites is not None:
            params["maxCallSites"] = max_call_sites
        return self._client._request("inlineRefactor.deepInlineMethod", {"params": params})
