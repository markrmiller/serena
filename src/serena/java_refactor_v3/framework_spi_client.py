"""Thin Python client for the sidecar ``frameworks.*`` protocol (refactor-feature-plan-V3.md §16).

Phase 6 puts a framework SPI in the Java sidecar, backed by exact compiler-resolved annotation facts (never
package-name heuristics). It knows Spring, JPA (jakarta + legacy javax), Jackson, and JUnit (Jupiter + legacy JUnit 4)
by the fully-qualified annotations they own, each mapped to a semantic role (e.g. Spring ``@Service`` → ``SERVICE``,
JPA ``@Entity`` → ``ENTITY``). ``detect`` reports which frameworks are present with annotation-count evidence;
``find_references`` reports framework-significant references to a target type — where the target's own
declaration/members carry framework annotations (``matchKind: "declares"``) and where the target is named inside a
framework annotation's arguments elsewhere (``matchKind: "names"``).

Both ops exposed by this client are read-only. Framework participation — wiring these facts into planners so a
framework-managed type only makes deletion *more* conservative, never more aggressive — is implemented and live in
the sidecar via ``FrameworkPlugin.participate`` and ``FrameworkParticipationCoordinator`` (joined into the deletion
and package planner paths); it is driven by those planners rather than surfaced as a standalone op here. This module
is the Python side of the read-only ``frameworks.*`` ops: a stateless wrapper over a live
:class:`~serena.java_refactor.client.JavaRefactorClient` that forwards the request and returns the sidecar's JSON
verbatim. A refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``):
``framework_target_unresolved`` (missing target).
"""

from __future__ import annotations

from typing import Any

from serena.java_refactor.client import JavaRefactorClient


class FrameworkSpiClient:
    """Forwards ``frameworks.*`` requests to the Java refactoring sidecar.

    Every method returns the sidecar's result payload as a plain ``dict``. An accepted result carries
    ``accepted: true``; a refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``).
    """

    def __init__(self, client: JavaRefactorClient) -> None:
        """:param client: a started (or startable) sidecar client used as the JSON-lines transport."""
        self._client = client

    def detect(self) -> dict[str, Any]:
        """Detects which known frameworks are present in the project (§16), by the annotations actually applied.

        The result carries a ``frameworks`` array — one entry per known framework with ``framework``/``detected`` and
        an ``evidence`` array of ``{annotation, count}`` pairs for the annotations found.
        """
        return self._client._request("frameworks.detect", {"params": {}})

    def find_references(self, target: str) -> dict[str, Any]:
        """Finds framework-significant references to ``target`` (a fully-qualified class name) (§16).

        The result carries a ``references`` array (each with ``framework``/``role``/``matchKind``/``annotation``/
        ``path``/offsets/``enclosingType``/``elementKind``/``elementName``/``confidence``) plus ``stats``. Refused with
        ``framework_target_unresolved`` when ``target`` is empty.
        """
        return self._client._request("frameworks.findReferences", {"params": {"target": target}})

    def participate(self, change_kind: str, target: str = "", new_name: str = "") -> dict[str, Any]:
        """Asks every framework plugin to participate in a pending ``change_kind`` (§16, transformation-participant half).

        ``change_kind`` is one of ``safeDelete``/``renameType``/``renamePackage``/``deadCodeScan``. ``target`` is the
        fully-qualified type (or package, for ``renamePackage``) being changed; ``new_name`` is the new fully-qualified
        name for the rename kinds. The accepted result carries ``blocks`` (deletion vetoes, each ``{symbol, reason}``),
        ``warnings`` (review-required notes), ``resourceEdits`` (framework-owned resource-edit descriptions), ``roots``
        (framework-contributed reachability roots), and ``stats``. Refused with ``framework_change_unrecognized`` when
        ``change_kind`` is not one of the four known kinds.

        The accepted result additionally carries ``frameworkResourceEdits`` (§16, B07): the structured counterpart to
        the human-readable ``resourceEdits``. Each entry is ``{targetResource, kind, manualReviewRequired,
        description}``; an entry that the coordinator could prove against the descriptor grammar also carries a concrete
        ``textEdit`` (``{path, startOffset, endOffset, newText, kind}``) for the Spring ``<bean class>`` /
        exact-dotted-FQN-token / JPA ``<class>`` cases. Entries with ``manualReviewRequired`` true (and no ``textEdit``)
        denote constructs that remain ambiguous (string bean names, JPQL, component-scan heuristics). Per the
        ``FrameworkResourceEdit`` contract these concrete edits are surfaced for the caller to apply within a reviewed
        transaction and are never silently auto-applied.
        """
        return self._client._request(
            "frameworks.participate",
            {"params": {"changeKind": change_kind, "target": target, "newName": new_name}},
        )
