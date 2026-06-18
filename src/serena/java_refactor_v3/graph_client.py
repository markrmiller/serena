"""Thin Python client for the sidecar ``graph.*`` protocol (refactor-feature-plan-V3.md §1.2/§3 F-GRAPH).

The unified *transformation graph* — build layout, Java symbols, type hierarchy, call edges, resource references and
tests — is assembled once per project revision by the Java sidecar from its real compiler/build/resource models and
cached, content-addressed, keyed on the same whole-project revision the reachability cache uses. This module is the
Python side of that protocol:

* :class:`GraphClient` forwards the two ``graph.*`` requests over a live
  :class:`~serena.java_refactor.client.JavaRefactorClient` and returns the sidecar's JSON verbatim:

  * ``graph.build`` — the authoritative whole-repo graph for the current revision (a cache HIT on an unchanged repo,
    a coordinated rebuild after any ``.java`` edit). Returns the seven-section graph payload.
  * ``graph.buildCount`` — a diagnostic counter of how many times a graph was actually materialized (advances on a
    cache MISS, flat on a HIT); the caching/invalidation tests assert against it.

* :func:`parse_project_graph` hydrates the read-only
  :class:`~serena.java_refactor_v3.graph.models.ProjectGraph` contract from the ``graph.build`` payload. It performs a
  pure mechanical reshape — every semantic value (resolved supertypes/subtypes, javac ``publicApi`` visibility,
  test→production references, exact resource FQN references) was computed by real javac in the sidecar and is copied
  here verbatim. There is no Python-side graph walk or inference.

The client holds no analysis of its own: it shapes requests, returns results, and reshapes the graph payload into the
existing dataclass contract so :class:`~serena.java_refactor_v3.reports.impact.ImpactReportBuilder` can read a real,
whole-repo graph (not only the touched-set ``SidecarFactsGraph`` façade).
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor_v3.graph.models import (
    BuildGraph,
    BuildSystem,
    CallGraph,
    JavaSymbolGraph,
    MethodDecl,
    ModuleNode,
    ProjectGraph,
    ResourceReference,
    ResourceReferenceGraph,
    SourceRoot,
    SourceRootContent,
    SourceRootKind,
    TestGraph,
    TestNode,
    TypeHierarchyIndex,
    TypeNode,
)


class GraphRefused(Exception):
    """Raised when the sidecar refuses a ``graph.build`` request (carries the structured refusal).

    ``graph.build`` refuses on ``not_initialized`` (no project model), a model gate refusal (the configured V3
    transformations surface is disabled), or ``graph_build_failed`` (an unexpected sidecar error while opening the
    project or walking its resources). Callers that build on top of the graph can catch this and surface the
    ``refusal`` payload verbatim rather than fabricating a partial graph.
    """

    def __init__(self, refusal: Mapping[str, Any]) -> None:
        self.refusal = dict(refusal)
        code = self.refusal.get("code", "graph_build_failed")
        message = self.refusal.get("message", "The sidecar refused to build the transformation graph.")
        super().__init__(f"{code}: {message}")


class GraphClient:
    """Forwards ``graph.*`` requests to the Java refactoring sidecar.

    Every method returns the sidecar's result payload as a plain ``dict``. An accepted ``graph.build`` result carries
    ``accepted: true`` plus the seven-section graph; a refusal carries ``accepted: false`` with a ``refusal`` object
    (``code``/``message``).
    """

    def __init__(self, client: JavaRefactorClient) -> None:
        """:param client: a started (or startable) sidecar client used as the JSON-lines transport."""
        self._client = client

    def build(self) -> dict[str, Any]:
        """Returns the cached, revision-keyed transformation graph for the current project revision.

        The sidecar serves a cache HIT on an unchanged repo and rebuilds after any source edit (content-addressed
        invalidation). The accepted payload carries ``project``, ``build``, ``symbols``, ``hierarchy``, ``calls``,
        ``resources``, ``tests`` and ``stats``. Use :func:`parse_project_graph` to hydrate the
        :class:`~serena.java_refactor_v3.graph.models.ProjectGraph` contract from it.
        """
        return self._client._request("graph.build", {})

    def build_or_raise(self) -> dict[str, Any]:
        """As :meth:`build`, but raises :class:`GraphRefused` on a refusal instead of returning it."""
        raw = self.build()
        if not raw.get("accepted", False):
            raise GraphRefused(raw.get("refusal", {}))
        return raw

    def project_graph(self) -> ProjectGraph:
        """Builds the graph and returns it as a hydrated :class:`ProjectGraph` (raising on a refusal)."""
        return parse_project_graph(self.build_or_raise())

    def build_count(self) -> int:
        """Returns how many times the sidecar has materialized a transformation graph (a cache miss).

        Monotonic and process-wide: it advances on a cache MISS and stays flat on a HIT, so two ``build()`` calls at
        the same revision leave it unchanged while an intervening source edit advances it by one. The
        caching/invalidation protocol tests assert against this counter.
        """
        result = self._client._request("graph.buildCount", {})
        return int(result.get("builds", 0))

    def incremental_update_count(self) -> int:
        """Returns how many times the sidecar served a new revision via an INCREMENTAL update (R05).

        Monotonic and process-wide: it advances when a new revision is materialized by re-extracting only the
        touched files' contributions (the incremental path) and stays flat on a HIT or a full rebuild. Paired with
        :meth:`build_count`, it proves the incremental path was taken — the full build count stays flat while this
        advances across a ``.java`` edit.
        """
        result = self._client._request("graph.incrementalUpdateCount", {})
        return int(result.get("incrementalUpdates", 0))


def parse_project_graph(raw: Mapping[str, Any]) -> ProjectGraph:
    """Hydrates the :class:`ProjectGraph` contract from a ``graph.build`` payload (pure mechanical reshape).

    Maps the sidecar's seven-section JSON onto the read-only dataclass contract field-by-field. No inference is
    performed: resolved supertypes/subtypes come straight from the sidecar's ``hierarchy`` section, ``publicApi``
    visibility from each type's javac-computed flag, resource references from the provider-backed ``resources``
    section, and test→production edges from the ``tests`` section. Unknown/extra payload fields are ignored so the
    contract stays stable as the sidecar payload grows.
    """
    project = raw.get("project", {}) or {}
    revision = str(project.get("revision", ""))

    symbols_raw = raw.get("symbols", {}) or {}
    build = _parse_build(raw.get("build", {}) or {})
    hierarchy = _parse_hierarchy(raw.get("hierarchy", {}) or {})
    symbols = _parse_symbols(symbols_raw, hierarchy)
    # Member declarations are emitted in the symbols section; the call edges live in the calls section. The
    # contract's CallGraph fuses the two (method declaration index + caller->callee edges).
    calls = _parse_calls(raw.get("calls", {}) or {}, symbols_raw.get("members", []) or [])
    resources = _parse_resources(raw.get("resources", {}) or {})
    tests = _parse_tests(raw.get("tests", {}) or {})

    return ProjectGraph(
        revision=revision,
        build=build,
        symbols=symbols,
        hierarchy=hierarchy,
        calls=calls,
        resources=resources,
        tests=tests,
    )


def _parse_build(raw: Mapping[str, Any]) -> BuildGraph:
    build_system = _build_system(raw.get("buildSystem"))
    modules: list[ModuleNode] = []
    for module in raw.get("modules", []) or []:
        module_id = str(module.get("id", ""))
        module_system = _build_system(module.get("buildSystem"))
        roots = [
            SourceRoot(
                relative_path=str(root.get("path", "")),
                kind=_root_kind(root.get("kind")),
                content=_root_content(root.get("content")),
                module_id=str(root.get("module", module_id)),
            )
            for root in module.get("sourceRoots", []) or []
        ]
        modules.append(ModuleNode(module_id=module_id, build_system=module_system, source_roots=roots))
    return BuildGraph(build_system=build_system, modules=modules)


def _parse_hierarchy(raw: Mapping[str, Any]) -> TypeHierarchyIndex:
    supertypes = {fqn: set(parents) for fqn, parents in (raw.get("supertypes", {}) or {}).items()}
    subtypes = {fqn: set(children) for fqn, children in (raw.get("subtypes", {}) or {}).items()}
    return TypeHierarchyIndex(supertypes=supertypes, subtypes=subtypes)


def _parse_symbols(raw: Mapping[str, Any], hierarchy: TypeHierarchyIndex) -> JavaSymbolGraph:
    types_by_fqn: dict[str, TypeNode] = {}
    for type_node in raw.get("types", []) or []:
        fqn = str(type_node.get("fqn", ""))
        if not fqn:
            continue
        types_by_fqn[fqn] = TypeNode(
            fqn=fqn,
            simple_name=str(type_node.get("simpleName", "")),
            package=str(type_node.get("package", "")),
            kind=str(type_node.get("kind", "")),
            relative_path=str(type_node.get("path", "")),
            supertypes=sorted(hierarchy.supertypes.get(fqn, set())),
        )
    package_to_source_roots = {
        pkg: set(roots) for pkg, roots in (raw.get("packageToSourceRoots", {}) or {}).items()
    }
    type_to_file = {fqn: str(path) for fqn, path in (raw.get("typeToFile", {}) or {}).items()}
    files_by_package = {pkg: set(files) for pkg, files in (raw.get("filesByPackage", {}) or {}).items()}
    return JavaSymbolGraph(
        types_by_fqn=types_by_fqn,
        package_to_source_roots=package_to_source_roots,
        type_to_file=type_to_file,
        files_by_package=files_by_package,
    )


def _parse_calls(raw: Mapping[str, Any], members: Any) -> CallGraph:
    methods: dict[str, MethodDecl] = {}
    for member in members or []:
        if str(member.get("memberKind", "")) != "method":
            continue
        decl = MethodDecl(
            owner_fqn=str(member.get("owner", "")),
            name=str(member.get("name", "")),
            arity=int(member.get("arity", 0)),
            relative_path=str(member.get("path", "")),
        )
        methods[decl.key] = decl
    edges = {caller: set(callees) for caller, callees in (raw.get("callEdges", {}) or {}).items()}
    return CallGraph(methods=methods, edges=edges, resolved=bool(raw.get("resolved", False)))


def _parse_resources(raw: Mapping[str, Any]) -> ResourceReferenceGraph:
    references = [
        ResourceReference(
            fqn=str(ref.get("target", "")),
            relative_path=str(ref.get("path", "")),
            line=int(ref.get("line", 0)),
        )
        for ref in raw.get("references", []) or []
    ]
    return ResourceReferenceGraph(references=references)


def _parse_tests(raw: Mapping[str, Any]) -> TestGraph:
    tests = [
        TestNode(
            test_fqn=str(test.get("testFqn", "")),
            relative_path=str(test.get("path", "")),
            referenced_types=frozenset(test.get("references", []) or []),
        )
        for test in raw.get("tests", []) or []
    ]
    return TestGraph(tests=tests)


def _build_system(value: Any) -> BuildSystem:
    try:
        return BuildSystem(str(value))
    except ValueError:
        return BuildSystem.PLAIN


def _root_kind(value: Any) -> SourceRootKind:
    try:
        return SourceRootKind(str(value))
    except ValueError:
        return SourceRootKind.MAIN


def _root_content(value: Any) -> SourceRootContent:
    try:
        return SourceRootContent(str(value))
    except ValueError:
        return SourceRootContent.JAVA


__all__ = ["GraphClient", "GraphRefused", "parse_project_graph"]
