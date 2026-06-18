"""Thin Python client for the sidecar ``impact.facts`` protocol (refactor-feature-plan-V3.md §7/Phase 7).

``impact.facts`` is a stateless, read-only sidecar op that accepts a list of touched project-relative paths and
returns a structured fact report for impact-analysis planning:

* Source roots classified as main-Java, test-Java, and resource roots.
* Top-level types declared in the touched files (FQN, relativePath, publicApi, testSource).
* Incoming semantic references to those types from the rest of the project, split by main vs. test source set.
* Resource-file text references to those types.

No files are ever written — the op is purely analytical. The caller may feed these facts to a planning layer to decide
whether a change is safe, what needs updating, and whether tests will be affected.

This module is the Python side: a stateless wrapper over a live
:class:`~serena.java_refactor.client.JavaRefactorClient` that forwards the ``impact.facts`` request and returns the
sidecar's JSON result verbatim. An accepted result carries ``accepted: true``; a refusal carries ``accepted: false``
with a ``refusal`` object (``code``/``message``).
"""

from __future__ import annotations

from typing import Any

from serena.java_refactor.client import JavaRefactorClient


class ImpactFactsRefused(Exception):
    """Raised when the sidecar refuses an ``impact.facts`` request (carries the structured refusal).

    ``impact.facts`` refuses on ``not_initialized`` (no project model) or ``impact_facts_failed`` (an
    unexpected sidecar error). Callers that build a report on top of the facts can catch this and surface the
    ``refusal`` payload verbatim in their own envelope rather than fabricating a partial report.
    """

    def __init__(self, refusal: dict[str, Any]) -> None:
        self.refusal = refusal
        code = refusal.get("code", "impact_facts_failed")
        message = refusal.get("message", "The sidecar refused to compute impact facts.")
        super().__init__(f"{code}: {message}")


class ImpactFactsClient:
    """Forwards ``impact.facts`` requests to the Java refactoring sidecar.

    Every method returns the sidecar's result payload as a plain ``dict``. An accepted result carries
    ``accepted: true``; a refusal carries ``accepted: false`` with a ``refusal`` object (``code``/``message``).
    """

    def __init__(self, client: JavaRefactorClient) -> None:
        """:param client: a started (or startable) sidecar client used as the JSON-lines transport."""
        self._client = client

    def facts(
        self,
        touched_paths: list[str],
    ) -> dict[str, Any]:
        """Computes impact facts for the given touched project-relative paths.

        Returns a structured fact report with the following top-level keys:

        * ``touchedPaths`` — echo of the request paths (normalised).
        * ``sourceRoots`` — ``{"main": [...], "test": [...], "resources": [...]}`` of project-relative root paths.
        * ``touchedTypes`` — array of ``{"fqn", "relativePath", "canonicalKey", "publicApi", "testSource"}`` for
          every top-level type declared in a touched file.
        * ``incomingRefs`` — array of ``{"referrerKey", "fromTestSource", "fromPublicApi", "toFqn",
          "toRelativePath"}`` — every semantic reference into a touched type from anywhere in the project.
        * ``resourceRefs`` — array of ``{"resourcePath", "target", "matchType"}`` for resource files that contain
          a touched FQN as a substring.
        * ``stats`` — ``{"touchedTypes", "incomingRefs", "resourceRefs"}`` counts.

        Refused with ``no_java_files`` when the project has no Java source, or ``impact_facts_failed`` on an
        unexpected sidecar error.
        """
        return self._client._request("impact.facts", {"touchedPaths": touched_paths})
