"""Pure-reshape façade that backs :class:`ImpactReportBuilder` with sidecar javac facts (G011, Decision A).

The impact report's *formatter* (:class:`~serena.java_refactor_v3.reports.impact.ImpactReportBuilder`) is kept
byte-for-byte: it reads its semantic facts off a duck-typed graph exposing ``.symbols`` / ``.build`` / ``.resources``
/ ``.tests``. There is no pure-Python whole-repo Java analyzer (that anti-hybrid skeleton has been removed); this
façade is the sole live producer of that graph shape.

:class:`SidecarFactsGraph` exposes that SAME accessor surface, but every value is a verbatim reshape of the facts the
Java sidecar computed with *real javac* (the ``impact.facts`` op). It performs ZERO inference: there is no graph walk,
no reference resolution, no visibility analysis here — each accessor is an O(1) lookup into a precomputed, touched-set
scoped map the sidecar supplied. The ``_GUARD`` assertions enforce that contract structurally (the façade never holds a
:class:`ProjectGraph` and never iterates a repo-wide structure). All semantic truth lives in the sidecar; all the
report *shape* lives in the unchanged formatter; this file only renames fields between the two.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable, Mapping
from typing import Any


class _SourceRoot:
    """A source root with its project-relative path (mirrors ``graph.models`` root objects' ``.relative_path``)."""

    __slots__ = ("relative_path",)

    def __init__(self, relative_path: str) -> None:
        self.relative_path = relative_path


class _ResourceRef:
    """A single resource→type reference (mirrors ``graph.models.ResourceReference``: ``.fqn`` / ``.relative_path``)."""

    __slots__ = ("fqn", "relative_path")

    def __init__(self, fqn: str, relative_path: str) -> None:
        self.fqn = fqn
        self.relative_path = relative_path


class _TestNode:
    """A test node (mirrors ``graph.models.TestNode``: ``.test_fqn`` / ``.relative_path``)."""

    __slots__ = ("relative_path", "test_fqn")

    def __init__(self, test_fqn: str, relative_path: str) -> None:
        self.test_fqn = test_fqn
        self.relative_path = relative_path


class _SymbolsFacade:
    """Reshapes the sidecar ``typeToFile`` map into the ``graph.symbols`` accessor surface."""

    def __init__(self, type_to_file: Mapping[str, str], public_api_fqns: Iterable[str] = ()) -> None:
        # The ONLY accessor ImpactReportBuilder uses: a fqn -> project-relative declaring-file map.
        self.type_to_file: dict[str, str] = dict(type_to_file)
        # javac-truth visibility gate (fact-4 apiBoundary): the touched FQNs whose declaring type element
        # carries public/protected visibility per the sidecar's real ``node.publicApi()``. The formatter
        # consults this (when present) instead of the legacy "main-source-root == API surface" approximation.
        self.public_api_fqns: frozenset[str] = frozenset(public_api_fqns)


class _BuildFacade:
    """Reshapes the sidecar ``sourceRoots`` classification into the ``graph.build`` accessor surface."""

    def __init__(self, source_roots: Mapping[str, Iterable[str]]) -> None:
        self._main = [_SourceRoot(p) for p in source_roots.get("main", [])]
        self._test = [_SourceRoot(p) for p in source_roots.get("test", [])]
        self._resource = [_SourceRoot(p) for p in source_roots.get("resource", [])]

    def main_java_roots(self) -> list[_SourceRoot]:
        return self._main

    def test_java_roots(self) -> list[_SourceRoot]:
        return self._test

    def resource_roots(self) -> list[_SourceRoot]:
        return self._resource


class _ResourcesFacade:
    """Reshapes the sidecar resource facts into the ``graph.resources`` accessor surface (pure dict lookups)."""

    def __init__(
        self,
        references_in: Mapping[str, Iterable[str]],
        references_to: Mapping[str, Iterable[str]],
    ) -> None:
        # references_in:  resource-relative-path -> [type fqn, ...]
        # references_to:  type fqn               -> [resource-relative-path, ...]
        self._references_in: dict[str, list[_ResourceRef]] = {
            path: [_ResourceRef(fqn, path) for fqn in fqns] for path, fqns in references_in.items()
        }
        self._references_to: dict[str, list[_ResourceRef]] = {
            fqn: [_ResourceRef(fqn, path) for path in paths] for fqn, paths in references_to.items()
        }

    def references_in(self, relative_path: str) -> list[_ResourceRef]:
        # GUARD: O(1) lookup of a precomputed, touched-set scoped list — no scan of any resource tree.
        return self._references_in.get(relative_path, [])

    def references_to(self, fqn: str) -> list[_ResourceRef]:
        # GUARD: O(1) lookup of a precomputed, touched-fqn scoped list — no scan of any resource tree.
        return self._references_to.get(fqn, [])


class _TestsFacade:
    """Reshapes the sidecar test facts into the ``graph.tests`` accessor surface (pure dict lookups)."""

    def __init__(
        self,
        tests_referencing: Mapping[str, Iterable[Mapping[str, str]]],
        touched_tests: Iterable[Mapping[str, str]],
    ) -> None:
        # tests_referencing: type fqn -> [{testFqn, relativePath}, ...]
        self._tests_referencing: dict[str, list[_TestNode]] = {
            fqn: [_TestNode(t["testFqn"], t["relativePath"]) for t in nodes]
            for fqn, nodes in tests_referencing.items()
        }
        # tests: the (touched-file) test nodes ImpactReportBuilder iterates to flag directly-edited test files.
        self.tests: list[_TestNode] = [_TestNode(t["testFqn"], t["relativePath"]) for t in touched_tests]

    def tests_referencing(self, fqn: str) -> list[_TestNode]:
        # GUARD: O(1) lookup of a precomputed, touched-fqn scoped list — no scan of any test source set.
        return self._tests_referencing.get(fqn, [])


class SidecarFactsGraph:
    """A :class:`ProjectGraph`-shaped façade over sidecar javac facts. Pure reshape, zero inference.

    Drop-in for the ``graph`` argument of :class:`ImpactReportBuilder`: it exposes ``.symbols`` / ``.build`` /
    ``.resources`` / ``.tests`` with identical accessor signatures, sourced entirely from the ``impact.facts`` payload.
    """

    def __init__(self, facts: Mapping[str, Any]) -> None:
        # GUARD (no graph walk): the façade is built ONLY from the plain sidecar facts dict. It must never be handed a
        # ProjectGraph, and exposes no whole-repo iteration — re-introducing inference here
        # would defeat the anti-hybrid migration. Every accessor below is an O(1) lookup over a touched-set scoped map.
        assert isinstance(facts, Mapping), "SidecarFactsGraph requires the sidecar facts mapping, not a graph object"
        assert "graph" not in facts and not hasattr(facts, "build_revision"), (
            "SidecarFactsGraph must be fed sidecar facts, never a ProjectGraph (no-graph-walk guard)"
        )
        self.symbols = _SymbolsFacade(facts.get("typeToFile", {}), facts.get("publicApiFqns", ()))
        self.build = _BuildFacade(facts.get("sourceRoots", {}))
        resources = facts.get("resources", {})
        self.resources = _ResourcesFacade(
            resources.get("referencesIn", {}), resources.get("referencesTo", {})
        )
        tests = facts.get("tests", {})
        self.tests = _TestsFacade(tests.get("testsReferencing", {}), tests.get("touchedTests", []))


def facts_to_graph_input(raw: Mapping[str, Any]) -> dict[str, Any]:
    """Reshapes the raw ``impact.facts`` sidecar payload into :class:`SidecarFactsGraph`'s input mapping.

    This is a *pure mechanical reshape* (the anti-hybrid mandate's "presentation adapter" lane): it renames
    fields and groups the sidecar's flat reference arrays into the lookup maps the formatter reads. It performs
    ZERO semantic analysis — every truth value (which references exist, ``publicApi`` visibility, ``testSource``
    classification, resource matches) is computed by real javac in the sidecar and copied here verbatim.

    The sidecar emits a *flat* shape (``touchedTypes`` / ``incomingRefs`` / ``resourceRefs`` arrays + a
    ``sourceRoots`` object whose resource key is ``"resources"``); the façade consumes a *pre-grouped* shape
    (``typeToFile`` map, ``sourceRoots.resource`` singular, ``resources.referencesIn``/``referencesTo`` maps,
    ``tests.testsReferencing``/``touchedTests``). This function is the one place that bridges the two.
    """
    touched_types = raw.get("touchedTypes", []) or []

    # symbols: fqn -> declaring project-relative file; plus the javac publicApi visibility set (fact-4).
    type_to_file: dict[str, str] = {t["fqn"]: t["relativePath"] for t in touched_types}
    public_api_fqns: list[str] = [t["fqn"] for t in touched_types if t.get("publicApi")]

    # build: rename the sidecar's plural "resources" root key to the formatter's singular "resource".
    source_roots = raw.get("sourceRoots", {}) or {}
    build_roots = {
        "main": list(source_roots.get("main", [])),
        "test": list(source_roots.get("test", [])),
        "resource": list(source_roots.get("resources", [])),
    }

    # resources: group the flat resourceRefs both ways (resource-path -> [fqn] and fqn -> [resource-path]).
    references_in: dict[str, list[str]] = defaultdict(list)
    references_to: dict[str, list[str]] = defaultdict(list)
    for ref in raw.get("resourceRefs", []) or []:
        resource_path = ref["resourcePath"]
        target = ref["target"]
        if target not in references_in[resource_path]:
            references_in[resource_path].append(target)
        if resource_path not in references_to[target]:
            references_to[target].append(resource_path)

    # tests: a touched type's test referrers are the test-source incoming refs, collapsed to the referrer's
    # owner type (incomingRefs are member-level, so de-duplicate by (testFqn, relativePath)).
    tests_referencing: dict[str, list[dict[str, str]]] = defaultdict(list)
    for ref in raw.get("incomingRefs", []) or []:
        if not ref.get("fromTestSource"):
            continue
        node = {"testFqn": ref["fromFqn"], "relativePath": ref["fromRelativePath"]}
        bucket = tests_referencing[ref["toFqn"]]
        if node not in bucket:
            bucket.append(node)
    # touched test files (directly-edited test types) come straight from the touched-type test-source flag.
    touched_tests = [
        {"testFqn": t["fqn"], "relativePath": t["relativePath"]} for t in touched_types if t.get("testSource")
    ]

    return {
        "typeToFile": type_to_file,
        "publicApiFqns": public_api_fqns,
        "sourceRoots": build_roots,
        "resources": {"referencesIn": dict(references_in), "referencesTo": dict(references_to)},
        "tests": {"testsReferencing": dict(tests_referencing), "touchedTests": touched_tests},
    }


__all__ = ["SidecarFactsGraph", "facts_to_graph_input"]
