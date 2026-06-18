"""Data model for the V3 impact-report graph shape.

These dataclasses are a read-only data contract consumed by the impact-report formatter; the only live
producer is :class:`~serena.java_refactor_v3.reports.sidecar_facts.SidecarFactsGraph`, a reshape of sidecar
javac facts. They describe a layered view — build layout, Java symbols, type hierarchy, calls, resource
references and tests — letting the formatter decide which files an edit touches and whether a change crosses
a boundary (public API, resource wiring, test surface) that warrants review.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Any


class BuildSystem(enum.Enum):
    """The build system that defines a module's source layout."""

    MAVEN = "maven"
    GRADLE = "gradle"
    PLAIN = "plain"


class SourceRootKind(enum.Enum):
    """Whether a source root holds main or test inputs."""

    MAIN = "main"
    TEST = "test"


class SourceRootContent(enum.Enum):
    """Whether a source root holds Java sources or non-Java resources."""

    JAVA = "java"
    RESOURCES = "resources"


@dataclass(frozen=True)
class SourceRoot:
    """A single source root within a module (e.g. ``src/main/java``)."""

    relative_path: str
    kind: SourceRootKind
    content: SourceRootContent
    module_id: str

    def to_dict(self) -> dict[str, Any]:
        return {"path": self.relative_path, "kind": self.kind.value, "content": self.content.value, "module": self.module_id}


@dataclass
class ModuleNode:
    """A build module: a unit with its own build descriptor and source roots."""

    module_id: str
    build_system: BuildSystem
    source_roots: list[SourceRoot] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.module_id,
            "buildSystem": self.build_system.value,
            "sourceRoots": [root.to_dict() for root in self.source_roots],
        }


@dataclass
class BuildGraph:
    """Build-layout view: the modules of the project and their source roots."""

    build_system: BuildSystem
    modules: list[ModuleNode] = field(default_factory=list)

    def _roots(self, kind: SourceRootKind, content: SourceRootContent) -> list[SourceRoot]:
        return [root for module in self.modules for root in module.source_roots if root.kind is kind and root.content is content]

    def main_java_roots(self) -> list[SourceRoot]:
        """All main Java source roots across every module."""
        return self._roots(SourceRootKind.MAIN, SourceRootContent.JAVA)

    def test_java_roots(self) -> list[SourceRoot]:
        """All test Java source roots across every module."""
        return self._roots(SourceRootKind.TEST, SourceRootContent.JAVA)

    def resource_roots(self) -> list[SourceRoot]:
        """All resource roots (main + test) across every module."""
        return [root for module in self.modules for root in module.source_roots if root.content is SourceRootContent.RESOURCES]

    def to_dict(self) -> dict[str, Any]:
        return {"buildSystem": self.build_system.value, "modules": [module.to_dict() for module in self.modules]}


@dataclass
class TypeNode:
    """A Java top-level type and where it lives.

    :ivar fqn: fully-qualified name (``package.Simple``; bare ``Simple`` in the default package).
    :ivar kind: ``class``/``interface``/``enum``/``record``/``annotation``.
    :ivar supertypes: resolved supertype FQNs (best-effort; unresolved simple names are dropped here and
        kept in :attr:`unresolved_supertypes`).
    """

    fqn: str
    simple_name: str
    package: str
    kind: str
    relative_path: str
    supertypes: list[str] = field(default_factory=list)
    unresolved_supertypes: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "fqn": self.fqn,
            "simpleName": self.simple_name,
            "package": self.package,
            "kind": self.kind,
            "path": self.relative_path,
            "supertypes": list(self.supertypes),
            "unresolvedSupertypes": list(self.unresolved_supertypes),
        }


@dataclass
class JavaSymbolGraph:
    """Symbol view: top-level types indexed by FQN, with package→roots and type→file maps."""

    types_by_fqn: dict[str, TypeNode] = field(default_factory=dict)
    package_to_source_roots: dict[str, set[str]] = field(default_factory=dict)
    type_to_file: dict[str, str] = field(default_factory=dict)
    files_by_package: dict[str, set[str]] = field(default_factory=dict)

    def types_in_package(self, package: str) -> list[TypeNode]:
        """All top-level types declared in ``package``."""
        return [node for node in self.types_by_fqn.values() if node.package == package]

    def resolve_simple_name(self, simple_name: str, *, in_package: str, imports: list[str]) -> str | None:
        """Resolves a simple type name to an FQN using imports then same-package, else ``None``.

        Conservative: an explicit single-type import wins; otherwise a same-package type; a wildcard import
        or an unknown name yields ``None`` so callers record it as unresolved rather than guessing.
        """
        for imported in imports:
            if imported.endswith("." + simple_name):
                return imported
        candidate = f"{in_package}.{simple_name}" if in_package else simple_name
        if candidate in self.types_by_fqn:
            return candidate
        return None

    def to_dict(self) -> dict[str, Any]:
        return {
            "typeCount": len(self.types_by_fqn),
            "packageToSourceRoots": {pkg: sorted(roots) for pkg, roots in sorted(self.package_to_source_roots.items())},
            "typeToFile": dict(sorted(self.type_to_file.items())),
        }


@dataclass
class TypeHierarchyIndex:
    """Hierarchy view: best-effort supertype/subtype closure over resolved type references."""

    supertypes: dict[str, set[str]] = field(default_factory=dict)
    subtypes: dict[str, set[str]] = field(default_factory=dict)

    def ancestors_of(self, fqn: str) -> set[str]:
        """Transitive resolved supertypes of ``fqn``."""
        return self._closure(fqn, self.supertypes)

    def descendants_of(self, fqn: str) -> set[str]:
        """Transitive resolved subtypes of ``fqn``."""
        return self._closure(fqn, self.subtypes)

    @staticmethod
    def _closure(start: str, edges: dict[str, set[str]]) -> set[str]:
        seen: set[str] = set()
        stack = list(edges.get(start, ()))
        while stack:
            current = stack.pop()
            if current in seen:
                continue
            seen.add(current)
            stack.extend(edges.get(current, ()))
        return seen

    def to_dict(self) -> dict[str, Any]:
        return {
            "supertypes": {fqn: sorted(parents) for fqn, parents in sorted(self.supertypes.items())},
            "subtypes": {fqn: sorted(children) for fqn, children in sorted(self.subtypes.items())},
        }


@dataclass(frozen=True)
class MethodDecl:
    """A method declaration recovered from source."""

    owner_fqn: str
    name: str
    arity: int
    relative_path: str

    @property
    def key(self) -> str:
        """Stable ``owner#name/arity`` identifier."""
        return f"{self.owner_fqn}#{self.name}/{self.arity}"

    def to_dict(self) -> dict[str, Any]:
        return {"owner": self.owner_fqn, "name": self.name, "arity": self.arity, "path": self.relative_path}


@dataclass
class CallGraph:
    """Call view: a declaration index plus conservatively-resolved intra-type self-call edges.

    Source text cannot resolve overloads or cross-type dispatch precisely, so only unambiguous intra-type
    self-calls (a call to a name the enclosing type declares exactly once) become edges; everything else is
    left for the sidecar to fill in. The declaration index is exact and is the part downstream tools rely on.
    """

    methods: dict[str, MethodDecl] = field(default_factory=dict)
    edges: dict[str, set[str]] = field(default_factory=dict)
    resolved: bool = False

    def methods_of(self, owner_fqn: str) -> list[MethodDecl]:
        """All declared methods owned by ``owner_fqn``."""
        return [decl for decl in self.methods.values() if decl.owner_fqn == owner_fqn]

    def to_dict(self) -> dict[str, Any]:
        return {
            "methodCount": len(self.methods),
            "resolved": self.resolved,
            "edges": {caller: sorted(callees) for caller, callees in sorted(self.edges.items())},
        }


@dataclass(frozen=True)
class ResourceReference:
    """A fully-qualified type reference located inside a non-Java resource file."""

    fqn: str
    relative_path: str
    line: int

    def to_dict(self) -> dict[str, Any]:
        return {"fqn": self.fqn, "path": self.relative_path, "line": self.line}


@dataclass
class ResourceReferenceGraph:
    """Resource view: every exact FQN reference found across scanned resource files."""

    references: list[ResourceReference] = field(default_factory=list)

    def references_to(self, fqn: str) -> list[ResourceReference]:
        """All resource references naming ``fqn`` exactly."""
        return [ref for ref in self.references if ref.fqn == fqn]

    def references_in(self, relative_path: str) -> list[ResourceReference]:
        """All references located in a given resource file."""
        return [ref for ref in self.references if ref.relative_path == relative_path]

    def to_dict(self) -> dict[str, Any]:
        return {"referenceCount": len(self.references), "references": [ref.to_dict() for ref in self.references]}


@dataclass(frozen=True)
class TestNode:
    """A test type and the production types it references via imports/same-package."""

    test_fqn: str
    relative_path: str
    referenced_types: frozenset[str] = frozenset()

    def to_dict(self) -> dict[str, Any]:
        return {"testFqn": self.test_fqn, "path": self.relative_path, "references": sorted(self.referenced_types)}


@dataclass
class TestGraph:
    """Test view: test types and which production types each exercises (best-effort by reference)."""

    tests: list[TestNode] = field(default_factory=list)

    def tests_referencing(self, fqn: str) -> list[TestNode]:
        """Test types that reference ``fqn``."""
        return [test for test in self.tests if fqn in test.referenced_types]

    def to_dict(self) -> dict[str, Any]:
        return {"testCount": len(self.tests), "tests": [test.to_dict() for test in self.tests]}


@dataclass
class ProjectGraph:
    """The whole-repo transformation graph for one project revision."""

    revision: str
    build: BuildGraph
    symbols: JavaSymbolGraph
    hierarchy: TypeHierarchyIndex
    calls: CallGraph
    resources: ResourceReferenceGraph
    tests: TestGraph

    def to_summary_dict(self) -> dict[str, Any]:
        """A compact, tool-facing summary (counts + maps, not the full per-node detail)."""
        return {
            "revision": self.revision,
            "build": self.build.to_dict(),
            "symbols": self.symbols.to_dict(),
            "hierarchy": {"types": len(self.hierarchy.supertypes)},
            "calls": {"methods": len(self.calls.methods), "resolved": self.calls.resolved},
            "resources": {"references": len(self.resources.references)},
            "tests": {"count": len(self.tests.tests)},
        }
