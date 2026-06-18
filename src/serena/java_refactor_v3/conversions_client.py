"""Thin Python client for the sidecar ``conversions.*`` protocol (refactor-feature-plan-V3.md §12–§13).

Phase 5 puts two lambda/anonymous-class conversions in the Java sidecar, where javac's ``Trees``/``Types``/``Elements``
model is authoritative for proving the conversion is semantics-preserving and for the conservative refusal lists. This
module is the Python side: a stateless wrapper over a live
:class:`~serena.java_refactor.client.JavaRefactorClient` that forwards each ``conversions.*`` request and returns the
sidecar's JSON result verbatim.

Both operations return a ``workspaceEdit`` (``changes`` + ``fileOperations``) that the sidecar has already run through
its before/after javac validator (``diagnosticDeltaValidated: true``); the sidecar never writes files, so the caller's
transactional applier owns apply. A refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``)
drawn from the §12.2/§12.4 and §13.3 refusal lists.
"""

from __future__ import annotations

from typing import Any

from serena.java_refactor.client import JavaRefactorClient


class ConversionsClient:
    """Forwards ``conversions.*`` requests to the Java refactoring sidecar.

    Every method returns the sidecar's result payload as a plain ``dict``. An accepted result carries
    ``accepted: true``; a refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``).
    """

    def __init__(self, client: JavaRefactorClient) -> None:
        """:param client: a started (or startable) sidecar client used as the JSON-lines transport."""
        self._client = client

    def anonymous_to_lambda(
        self,
        relative_path: str,
        line: int,
        *,
        column: int | None = None,
        validate: bool = True,
    ) -> dict[str, Any]:
        """Plans a convert-anonymous-class-to-lambda (§12) at ``line`` (1-based) in ``relative_path``.

        ``column`` (1-based) disambiguates when more than one anonymous class starts on the line; omit it to select the
        first one on the line. Per §12.2/§12.4 the conversion is refused when the target is not a single-abstract-method
        functional interface, the body declares fields/initializers/extra methods, overrides an Object method, or uses
        ``this``/``super``. ``validate`` runs the sidecar's before/after javac validation.
        """
        params: dict[str, Any] = {
            "relativePath": relative_path,
            "line": line,
            "validate": validate,
        }
        if column is not None:
            params["column"] = column
        return self._client._request("conversions.anonymousToLambda", {"params": params})

    def lambda_to_method_reference(
        self,
        relative_path: str,
        line: int,
        *,
        column: int | None = None,
        validate: bool = True,
    ) -> dict[str, Any]:
        """Plans a convert-lambda-to-method-reference (§13) at ``line`` (1-based) in ``relative_path``.

        ``column`` (1-based) disambiguates when more than one lambda starts on the line; omit it to select the first one
        on the line. Per §13.3 the conversion is refused unless the lambda body is exactly one method/constructor
        invocation that forwards the parameters in order untransformed (no reordering, partial application, argument
        transformation, or receiver that references a parameter). ``validate`` runs the sidecar's before/after javac
        validation.
        """
        params: dict[str, Any] = {
            "relativePath": relative_path,
            "line": line,
            "validate": validate,
        }
        if column is not None:
            params["column"] = column
        return self._client._request("conversions.lambdaToMethodReference", {"params": params})
