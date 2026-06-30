"""Unit tests for the G011 anti-hybrid migration's Python glue (refactor-feature-plan-V3.md §G011, Decision A1).

The live ``impact.facts`` sidecar op (real javac) is covered end to end by
``test_java_refactor_v3_impact_facts_protocol``. This module covers the two *pure-Python* pieces the
migration adds on top of it, with no sidecar:

* :func:`serena.java_refactor_v3.reports.sidecar_facts.facts_to_graph_input` — the mechanical reshape of the
  sidecar's flat fact arrays into the pre-grouped mapping :class:`SidecarFactsGraph` consumes.
* the fact-4 ``apiBoundary`` javac-visibility gate: with sidecar ``publicApi`` truth, a package-private touched
  type is no longer treated as API surface (the legacy "any main-source type is API surface" approximation).

It also asserts the unchanged formatter still produces the five-section report when fed a sidecar-backed graph,
proving the façade is a drop-in for the retired in-Python project graph.
"""

from __future__ import annotations

from typing import cast

from serena.java_refactor.workspace_edit import RefactorFileOperation, RefactorTextEdit, RefactorWorkspaceEdit
from serena.java_refactor_v3.graph.models import ProjectGraph
from serena.java_refactor_v3.reports import ImpactReportBuilder
from serena.java_refactor_v3.reports.sidecar_facts import SidecarFactsGraph, facts_to_graph_input


def _raw_facts() -> dict:
    """A realistic raw ``impact.facts`` payload (the exact shape ``ImpactFactsAnalyzer`` emits).

    Touched files: a public main type ``Svc`` (referenced by a test) and a package-private main type
    ``Helper`` (referenced by nothing external). ``Svc`` is also wired by a resource provider file.
    """
    return {
        "accepted": True,
        "operation": "impact.facts",
        "touchedPaths": [
            "src/main/java/com/acme/Svc.java",
            "src/main/java/com/acme/Helper.java",
        ],
        "sourceRoots": {
            "main": ["src/main/java"],
            "test": ["src/test/java"],
            "resources": ["src/main/resources"],
        },
        "touchedTypes": [
            {
                "fqn": "com.acme.Svc",
                "relativePath": "src/main/java/com/acme/Svc.java",
                "publicApi": True,
                "testSource": False,
            },
            {
                "fqn": "com.acme.Helper",
                "relativePath": "src/main/java/com/acme/Helper.java",
                "publicApi": False,
                "testSource": False,
            },
        ],
        "incomingRefs": [
            # Two member-level refs from the same test class -> collapse to one (testFqn, relativePath).
            {
                "fromKey": "com.acme.SvcTest#a()",
                "fromFqn": "com.acme.SvcTest",
                "fromRelativePath": "src/test/java/com/acme/SvcTest.java",
                "fromTestSource": True,
                "fromPublicApi": True,
                "toFqn": "com.acme.Svc",
                "toRelativePath": "src/main/java/com/acme/Svc.java",
            },
            {
                "fromKey": "com.acme.SvcTest#b()",
                "fromFqn": "com.acme.SvcTest",
                "fromRelativePath": "src/test/java/com/acme/SvcTest.java",
                "fromTestSource": True,
                "fromPublicApi": True,
                "toFqn": "com.acme.Svc",
                "toRelativePath": "src/main/java/com/acme/Svc.java",
            },
            # A main-source (non-test) referrer must NOT show up as a test or as a touched test.
            {
                "fromKey": "com.acme.Caller#run()",
                "fromFqn": "com.acme.Caller",
                "fromRelativePath": "src/main/java/com/acme/Caller.java",
                "fromTestSource": False,
                "fromPublicApi": True,
                "toFqn": "com.acme.Svc",
                "toRelativePath": "src/main/java/com/acme/Svc.java",
            },
        ],
        "resourceRefs": [
            {
                "resourcePath": "src/main/resources/META-INF/services/com.acme.Svc",
                "target": "com.acme.Svc",
                "kind": "EXACT_CLASS_NAME",
            },
        ],
        "stats": {"touchedTypes": 2, "incomingRefs": 3, "resourceRefs": 1},
    }


def _edit() -> RefactorWorkspaceEdit:
    """A composed edit modifying both touched Java files (the report's input)."""
    return RefactorWorkspaceEdit(
        text_edits=[
            RefactorTextEdit(
                relative_path="src/main/java/com/acme/Svc.java", start_offset=0, end_offset=0, replacement=""
            ),
            RefactorTextEdit(
                relative_path="src/main/java/com/acme/Helper.java", start_offset=0, end_offset=0, replacement=""
            ),
        ],
        file_operations=[],
        warnings=[],
    )


# ── (a) the reshape adapter ───────────────────────────────────────────────────────────────────────────────


def test_facts_to_graph_input_reshapes_every_section() -> None:
    shaped = facts_to_graph_input(_raw_facts())

    assert shaped["typeToFile"] == {
        "com.acme.Svc": "src/main/java/com/acme/Svc.java",
        "com.acme.Helper": "src/main/java/com/acme/Helper.java",
    }
    # only the public type is in the javac visibility gate set.
    assert shaped["publicApiFqns"] == ["com.acme.Svc"]
    # the sidecar's plural "resources" root key is renamed to the formatter's singular "resource".
    assert shaped["sourceRoots"] == {
        "main": ["src/main/java"],
        "test": ["src/test/java"],
        "resource": ["src/main/resources"],
    }
    # resourceRefs grouped both directions.
    assert shaped["resources"]["referencesIn"] == {
        "src/main/resources/META-INF/services/com.acme.Svc": ["com.acme.Svc"]
    }
    assert shaped["resources"]["referencesTo"] == {
        "com.acme.Svc": ["src/main/resources/META-INF/services/com.acme.Svc"]
    }
    # only test-source incoming refs become test referrers, collapsed to one node per (testFqn, path).
    assert shaped["tests"]["testsReferencing"] == {
        "com.acme.Svc": [{"testFqn": "com.acme.SvcTest", "relativePath": "src/test/java/com/acme/SvcTest.java"}]
    }
    # no touched type lives under a test root, so no directly-edited test files.
    assert shaped["tests"]["touchedTests"] == []


def test_facts_to_graph_input_collects_touched_tests() -> None:
    raw = {
        "sourceRoots": {"main": [], "test": ["src/test/java"], "resources": []},
        "touchedTypes": [
            {
                "fqn": "com.acme.SvcTest",
                "relativePath": "src/test/java/com/acme/SvcTest.java",
                "publicApi": True,
                "testSource": True,
            }
        ],
        "incomingRefs": [],
        "resourceRefs": [],
    }
    shaped = facts_to_graph_input(raw)
    assert shaped["tests"]["touchedTests"] == [
        {"testFqn": "com.acme.SvcTest", "relativePath": "src/test/java/com/acme/SvcTest.java"}
    ]
    # a test type is never API surface even though publicApi is True — it lives under a test root, which the
    # formatter's API section excludes; the visibility set still records it (the gate is path-AND-visibility).
    assert shaped["publicApiFqns"] == ["com.acme.SvcTest"]


# ── (b) the formatter over a sidecar-backed graph + the fact-4 publicApi gate ────────────────────────────────


def test_report_over_sidecar_facts_has_all_five_sections() -> None:
    graph = SidecarFactsGraph(facts_to_graph_input(_raw_facts()))
    report = ImpactReportBuilder("/proj", cast(ProjectGraph, graph)).build(_edit(), operation="renameMember").to_dict()

    assert set(report) == {"summary", "semanticImpact", "resourceImpact", "tests", "warnings"}
    java_paths = {f["path"] for f in report["java"]["files"]}
    assert java_paths == {"src/main/java/com/acme/Svc.java", "src/main/java/com/acme/Helper.java"}


def test_api_boundary_uses_javac_public_api_gate() -> None:
    # Svc is public and externally referenced -> API surface, boundary crossed. Helper is package-private and
    # must be excluded entirely, even though it is a touched main-source type (the legacy approximation would
    # have listed it). This is the fact-4 javac-visibility behaviour change.
    graph = SidecarFactsGraph(facts_to_graph_input(_raw_facts()))
    api = ImpactReportBuilder("/proj", cast(ProjectGraph, graph)).build(_edit(), operation="renameMember").to_dict()["api"]

    assert api["boundaryCrossed"] is True
    surfaced = {t["type"] for t in api["mainTypesTouched"]}
    assert surfaced == {"com.acme.Svc"}
    assert "com.acme.Helper" not in surfaced


def test_tests_section_reports_collapsed_test_referrers() -> None:
    graph = SidecarFactsGraph(facts_to_graph_input(_raw_facts()))
    tests = ImpactReportBuilder("/proj", cast(ProjectGraph, graph)).build(_edit(), operation="renameMember").to_dict()["tests"]

    # the two member-level refs from SvcTest collapse to a single impacted test; Caller (main source) is not a test.
    assert tests["impacted"] == ["com.acme.SvcTest"]
    assert tests["impactedCount"] == 1


def test_resources_section_reports_wired_provider() -> None:
    graph = SidecarFactsGraph(facts_to_graph_input(_raw_facts()))
    resources = ImpactReportBuilder("/proj", cast(ProjectGraph, graph)).build(_edit(), operation="renameMember").to_dict()["resources"]

    assert resources["wiredTypeReferences"] == [
        {"type": "com.acme.Svc", "resources": ["src/main/resources/META-INF/services/com.acme.Svc"]}
    ]
