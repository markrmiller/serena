"""Whole-repo impact reports for V3 transformations (G011).

Every V3 tool returns a preview-first workspace edit. :class:`ImpactReportBuilder` turns that edit, read
against the :class:`~serena.java_refactor_v3.graph.models.ProjectGraph`, into the five-section
:class:`~serena.java_refactor_v3.models.ImpactReport` a reviewer reads before applying: which Java sources
and types change, which non-Java resources are touched or wire a changed type, whether a public-API
boundary (a main-source type referenced from outside its own file) is crossed, which tests are impacted,
and an overall risk roll-up. The builder is read-only and deterministic: it never mutates the workspace and
sorts every collection so reports diff cleanly.
"""

from __future__ import annotations

from collections import Counter, defaultdict

from serena.java_refactor.workspace_edit import RefactorWorkspaceEdit
from serena.java_refactor_v3.graph.models import ProjectGraph
from serena.java_refactor_v3.models import FileChangeKind, ImpactReport, RiskLevel


def _normalise_risk(risk: RiskLevel | str) -> str:
    """Accepts a :class:`RiskLevel` or its string value and returns the canonical string."""
    return risk.value if isinstance(risk, RiskLevel) else str(risk)


def _under(path: str, root: str) -> bool:
    """Whether project-relative ``path`` lies within source ``root`` (POSIX, root-inclusive)."""
    if not root:
        return True
    return path == root or path.startswith(root + "/")


def _under_any(path: str, roots: list[str]) -> bool:
    return any(_under(path, root) for root in roots)


class ImpactReportBuilder:
    """Builds an :class:`ImpactReport` for a composed workspace edit against a project graph."""

    def __init__(self, project_root: str, graph: ProjectGraph) -> None:
        self._project_root = str(project_root)
        self._graph = graph
        # reverse index: project-relative file -> sorted fqns declared in it.
        self._file_to_fqns: dict[str, list[str]] = defaultdict(list)
        for fqn, rel in graph.symbols.type_to_file.items():
            self._file_to_fqns[rel].append(fqn)
        for rel in self._file_to_fqns:
            self._file_to_fqns[rel].sort()
        self._main_roots = [r.relative_path for r in graph.build.main_java_roots()]
        self._test_roots = [r.relative_path for r in graph.build.test_java_roots()]
        self._resource_roots = [r.relative_path for r in graph.build.resource_roots()]
        # fact-4 apiBoundary: when the graph carries real javac visibility (the sidecar-backed
        # SidecarFactsGraph), gate API surface on it; ``None`` (the legacy in-Python graph) keeps the
        # historic main-source-root approximation so existing direct-graph reports are unaffected.
        self._public_api_fqns: frozenset[str] | None = getattr(graph.symbols, "public_api_fqns", None)

    def build(
        self,
        workspace_edit: RefactorWorkspaceEdit,
        *,
        risk: RiskLevel | str = RiskLevel.SAFE,
        operation: str = "",
    ) -> ImpactReport:
        """Produces the five-section impact report for ``workspace_edit``."""
        kinds, rename_sources = self._change_kinds(workspace_edit)
        edit_counts = Counter(te.relative_path for te in workspace_edit.text_edits)
        for path in edit_counts:
            kinds.setdefault(path, FileChangeKind.MODIFY)

        # rename sources are represented under their destination, not as standalone touched files.
        reported_paths = sorted(p for p in kinds if p not in rename_sources.values())

        java = self._java_section(reported_paths, kinds, edit_counts, rename_sources)
        touched_fqns = sorted({fqn for path in reported_paths for fqn in self._file_to_fqns.get(path, [])})

        resources = self._resources_section(reported_paths, touched_fqns)
        api = self._api_section(reported_paths, touched_fqns)
        tests = self._tests_section(reported_paths, touched_fqns)
        risk_section = self._risk_section(
            _normalise_risk(risk),
            operation=operation,
            api=api,
            resources=resources,
            tests=tests,
            warnings=list(workspace_edit.warnings),
        )
        return ImpactReport(java=java, resources=resources, api=api, tests=tests, risk=risk_section)

    @staticmethod
    def _change_kinds(
        workspace_edit: RefactorWorkspaceEdit,
    ) -> tuple[dict[str, FileChangeKind], dict[str, str]]:
        """Maps each file operation to its change kind; returns (kinds, rename_dest->source)."""
        kinds: dict[str, FileChangeKind] = {}
        rename_sources: dict[str, str] = {}
        for op in workspace_edit.file_operations:
            if op.kind == "create":
                kinds[op.relative_path] = FileChangeKind.CREATE
            elif op.kind == "delete":
                kinds[op.relative_path] = FileChangeKind.DELETE
            elif op.kind == "rename":
                dest = op.new_relative_path or op.relative_path
                kinds[dest] = FileChangeKind.RENAME
                rename_sources[dest] = op.relative_path
        return kinds, rename_sources

    def _java_section(
        self,
        paths: list[str],
        kinds: dict[str, FileChangeKind],
        edit_counts: Counter[str],
        rename_sources: dict[str, str],
    ) -> dict[str, object]:
        files: list[dict[str, object]] = []
        total_edits = 0
        for path in paths:
            if not path.endswith(".java"):
                continue
            kind = kinds.get(path, FileChangeKind.MODIFY)
            count = int(edit_counts.get(path, 0))
            total_edits += count
            entry: dict[str, object] = {
                "path": path,
                "kind": kind.value,
                "editCount": count,
                "types": list(self._file_to_fqns.get(path, [])),
            }
            if path in rename_sources:
                entry["renameSource"] = rename_sources[path]
            files.append(entry)
        return {"fileCount": len(files), "editCount": total_edits, "files": files}

    def _resources_section(self, paths: list[str], touched_fqns: list[str]) -> dict[str, object]:
        resource_files: list[dict[str, object]] = []
        for path in paths:
            if path.endswith(".java"):
                continue
            refs = self._graph.resources.references_in(path)
            resource_files.append(
                {
                    "path": path,
                    "isResourceRoot": _under_any(path, self._resource_roots),
                    "referencedTypes": sorted({ref.fqn for ref in refs}),
                }
            )
        # resource files elsewhere in the repo that wire a changed type (e.g. META-INF/services, spring xml).
        wiring: list[dict[str, object]] = []
        for fqn in touched_fqns:
            providers = sorted({ref.relative_path for ref in self._graph.resources.references_to(fqn)})
            if providers:
                wiring.append({"type": fqn, "resources": providers})
        return {
            "fileCount": len(resource_files),
            "files": resource_files,
            "wiredTypeReferences": wiring,
        }

    def _api_section(self, paths: list[str], touched_fqns: list[str]) -> dict[str, object]:
        """Reports the public-API boundary for touched main-source types.

        A touched type declared under a *main* (non-test) source root is API surface; it is reported as
        crossing the boundary when something outside its own file (a resource provider or a test) references
        it. When the graph carries real javac visibility (``self._public_api_fqns`` is not ``None``), only
        public/protected types qualify as API surface — the compiler-grade ``node.publicApi()`` gate replaces
        the historic "any main-source type is potential API surface" approximation used when no visibility
        data is available.
        """
        main_types: list[dict[str, object]] = []
        boundary_crossed = False
        for path in sorted(set(paths)):
            if not path.endswith(".java"):
                continue
            if not _under_any(path, self._main_roots) or _under_any(path, self._test_roots):
                continue
            for fqn in self._file_to_fqns.get(path, []):
                if self._public_api_fqns is not None and fqn not in self._public_api_fqns:
                    continue  # javac says this type is not public/protected: not API surface.
                external = self._external_references(fqn, declaring_file=path)
                if external:
                    boundary_crossed = True
                main_types.append({"type": fqn, "externalReferences": external})
        return {
            "boundaryCrossed": boundary_crossed,
            "mainTypesTouched": main_types,
        }

    def _external_references(self, fqn: str, *, declaring_file: str) -> list[str]:
        """Cross-file references to ``fqn``: resource providers and tests (sorted, de-duplicated)."""
        refs: set[str] = set()
        for ref in self._graph.resources.references_to(fqn):
            refs.add(f"resource:{ref.relative_path}")
        for test in self._graph.tests.tests_referencing(fqn):
            if test.relative_path != declaring_file:
                refs.add(f"test:{test.test_fqn}")
        return sorted(refs)

    def _tests_section(self, paths: list[str], touched_fqns: list[str]) -> dict[str, object]:
        impacted: set[str] = set()
        for fqn in touched_fqns:
            for test in self._graph.tests.tests_referencing(fqn):
                impacted.add(test.test_fqn)
        touched_test_files = sorted(
            p for p in paths if p.endswith(".java") and _under_any(p, self._test_roots)
        )
        # a directly-edited test file is itself impacted.
        path_set = set(paths)
        for test in self._graph.tests.tests:
            if test.relative_path in path_set:
                impacted.add(test.test_fqn)
        return {
            "impactedCount": len(impacted),
            "impacted": sorted(impacted),
            "touchedTestFiles": touched_test_files,
        }

    def _risk_section(
        self,
        level: str,
        *,
        operation: str,
        api: dict[str, object],
        resources: dict[str, object],
        tests: dict[str, object],
        warnings: list[str],
    ) -> dict[str, object]:
        api_affected = bool(api.get("boundaryCrossed"))
        resources_affected = bool(resources.get("wiredTypeReferences")) or bool(resources.get("fileCount"))
        tests_affected = bool(tests.get("impactedCount"))
        reasons: list[str] = []
        if api_affected:
            reasons.append("A touched main-source type is referenced outside its own file (API boundary).")
        if resources_affected:
            reasons.append("A touched type is wired by a non-Java resource, or a resource file is edited.")
        if tests_affected:
            reasons.append("One or more tests reference a touched type and should be re-run.")
        return {
            "operation": operation,
            "level": level,
            "apiAffected": api_affected,
            "resourcesAffected": resources_affected,
            "testsAffected": tests_affected,
            "reasons": reasons,
            "warnings": list(warnings),
        }


__all__ = ["ImpactReportBuilder"]
