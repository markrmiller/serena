"""Read-only graph data model for the V3 impact report.

:class:`~serena.java_refactor_v3.graph.models.ProjectGraph` and its components — build layout, Java symbols,
type hierarchy, calls, resource references and tests — describe the *shape* the impact-report formatter reads.
The only live producer of this shape is :class:`~serena.java_refactor_v3.reports.sidecar_facts.SidecarFactsGraph`,
which reshapes facts the Java sidecar computed with real javac (the ``impact.facts`` op); there is no Python
whole-repo analyzer. These dataclasses are therefore a pure, passive data contract.
"""

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

__all__ = [
    "BuildGraph",
    "BuildSystem",
    "CallGraph",
    "JavaSymbolGraph",
    "MethodDecl",
    "ModuleNode",
    "ProjectGraph",
    "ResourceReference",
    "ResourceReferenceGraph",
    "SourceRoot",
    "SourceRootContent",
    "SourceRootKind",
    "TestGraph",
    "TestNode",
    "TypeHierarchyIndex",
    "TypeNode",
]
