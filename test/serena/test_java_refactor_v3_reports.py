"""Tests for the V3 hardening deliverables (G011): impact reports, the acceptance matrix,
the complete refusal-code registry, the no-JetBrains guarantee, and a large-repo performance smoke test.

The impact-report section drives the LIVE formatter (:class:`ImpactReportBuilder`) over a
:class:`SidecarFactsGraph` — the same sidecar-javac-facts façade the manager feeds it in production. The raw
``impact.facts`` payloads are hand-built here (the reshape adapter ``facts_to_graph_input`` is itself covered,
sidecar-free, by ``test_java_refactor_v3_sidecar_facts``; the live javac op by
``test_java_refactor_v3_impact_facts_protocol``). There is no in-Python project-graph builder: that anti-hybrid
skeleton has been removed, so every Java/resource/API/test fact below originates from the sidecar's shape.
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any, cast

from serena.java_refactor.workspace_edit import RefactorFileOperation, RefactorTextEdit, RefactorWorkspaceEdit
from serena.java_refactor_v3.models import RiskLevel
from serena.java_refactor_v3.reports import (
    ACCEPTANCE_MATRIX,
    V3_INVARIANTS,
    ImpactReportBuilder,
    acceptance_matrix,
    all_refusal_codes,
    jetbrains_references,
)
from serena.java_refactor_v3.reports.sidecar_facts import SidecarFactsGraph, facts_to_graph_input

SERVICE_JAVA = "src/main/java/com/acme/app/Service.java"
SERVICE_FQN = "com.acme.app.Service"
SERVICE_TEST_JAVA = "src/test/java/com/acme/app/ServiceTest.java"
SERVICE_TEST_FQN = "com.acme.app.ServiceTest"
BEANS_XML = "src/main/resources/beans.xml"


def _source_roots() -> dict:
    # The sidecar uses the plural "resources" key; facts_to_graph_input renames it to the singular "resource".
    return {"main": ["src/main/java"], "test": ["src/test/java"], "resources": ["src/main/resources"]}


def _service_facts() -> dict:
    """A raw ``impact.facts`` payload for the canonical wired-service scenario.

    ``Service`` is a public main type, wired by ``beans.xml`` and exercised by ``ServiceTest`` — so the API,
    resource and test sections all light up, exactly as the retired Maven fixture used to drive them.
    """
    return {
        "accepted": True,
        "operation": "impact.facts",
        "touchedPaths": [SERVICE_JAVA],
        "sourceRoots": _source_roots(),
        "touchedTypes": [
            {"fqn": SERVICE_FQN, "relativePath": SERVICE_JAVA, "publicApi": True, "testSource": False},
        ],
        "incomingRefs": [
            {
                "fromKey": f"{SERVICE_TEST_FQN}#run()",
                "fromFqn": SERVICE_TEST_FQN,
                "fromRelativePath": SERVICE_TEST_JAVA,
                "fromTestSource": True,
                "fromPublicApi": True,
                "toFqn": SERVICE_FQN,
                "toRelativePath": SERVICE_JAVA,
            },
        ],
        "resourceRefs": [
            {"resourcePath": BEANS_XML, "target": SERVICE_FQN, "kind": "SPRING_BEAN"},
        ],
        "stats": {"touchedTypes": 1, "incomingRefs": 1, "resourceRefs": 1},
    }


def _graph_from(raw: dict) -> SidecarFactsGraph:
    return SidecarFactsGraph(facts_to_graph_input(raw))


def _builder(raw: dict | None = None) -> ImpactReportBuilder:
    return ImpactReportBuilder("/proj", cast(Any, _graph_from(raw if raw is not None else _service_facts())))


# -- impact report: java section -------------------------------------------------------------------


def test_impact_report_java_section_lists_touched_types() -> None:
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit(relative_path=SERVICE_JAVA, start_offset=0, end_offset=0, replacement="")]
    )
    report = _builder().build(edit, risk=RiskLevel.REVIEW_REQUIRED, operation="renamePackage").to_dict()
    java = report["java"]
    assert java["fileCount"] == 1
    assert java["editCount"] == 1
    entry = java["files"][0]
    assert entry["path"] == SERVICE_JAVA
    assert entry["kind"] == "modify"
    assert SERVICE_FQN in entry["types"]


def test_impact_report_flags_api_resources_and_tests() -> None:
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit(relative_path=SERVICE_JAVA, start_offset=0, end_offset=0, replacement="")]
    )
    report = _builder().build(edit, risk=RiskLevel.REVIEW_REQUIRED, operation="renamePackage").to_dict()

    # API boundary: Service is a public main-source type referenced from beans.xml and ServiceTest.
    api = report["api"]
    assert api["boundaryCrossed"] is True
    touched = {row["type"]: row for row in api["mainTypesTouched"]}
    assert SERVICE_FQN in touched
    refs = touched[SERVICE_FQN]["externalReferences"]
    assert any(r.startswith("resource:") for r in refs)
    assert any(r.startswith("test:") for r in refs)

    # resources: beans.xml wires Service.
    wiring = {row["type"]: row["resources"] for row in report["resources"]["wiredTypeReferences"]}
    assert BEANS_XML in wiring[SERVICE_FQN]

    # tests: ServiceTest references Service and must be re-run.
    assert SERVICE_TEST_FQN in report["tests"]["impacted"]

    risk = report["risk"]
    assert risk["operation"] == "renamePackage"
    assert risk["level"] == "REVIEW_REQUIRED"
    assert risk["apiAffected"] and risk["resourcesAffected"] and risk["testsAffected"]
    assert risk["reasons"]


def test_impact_report_resource_file_edit_is_classified() -> None:
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit(relative_path=BEANS_XML, start_offset=0, end_offset=0, replacement="")]
    )
    report = _builder().build(edit, risk=RiskLevel.SAFE, operation="applyRefactorRecipe").to_dict()
    assert report["java"]["fileCount"] == 0
    res = report["resources"]
    assert res["fileCount"] == 1
    assert res["files"][0]["path"] == BEANS_XML
    assert SERVICE_FQN in res["files"][0]["referencedTypes"]
    assert report["risk"]["resourcesAffected"] is True


def test_impact_report_rename_records_source_under_destination() -> None:
    edit = RefactorWorkspaceEdit(
        file_operations=[
            RefactorFileOperation(
                kind="rename",
                relative_path="src/main/java/com/acme/app/Dog.java",
                new_relative_path="src/main/java/com/acme/app/Canine.java",
            )
        ]
    )
    report = _builder().build(edit, risk=RiskLevel.REVIEW_REQUIRED, operation="moveType").to_dict()
    files = {row["path"]: row for row in report["java"]["files"]}
    dest = "src/main/java/com/acme/app/Canine.java"
    assert dest in files
    assert files[dest]["kind"] == "rename"
    assert files[dest]["renameSource"] == "src/main/java/com/acme/app/Dog.java"
    # the source path is not double-listed as its own touched file.
    assert "src/main/java/com/acme/app/Dog.java" not in files


def test_impact_report_test_only_edit_has_no_api_boundary() -> None:
    # A directly-edited test type: it carries the sidecar's testSource flag, so it is a touched test (impacted +
    # listed) but never API surface (the API section excludes test-root paths).
    raw = {
        "sourceRoots": _source_roots(),
        "touchedTypes": [
            {"fqn": SERVICE_TEST_FQN, "relativePath": SERVICE_TEST_JAVA, "publicApi": True, "testSource": True},
        ],
        "incomingRefs": [],
        "resourceRefs": [],
    }
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit(relative_path=SERVICE_TEST_JAVA, start_offset=0, end_offset=0, replacement="")]
    )
    report = _builder(raw).build(edit, risk=RiskLevel.SAFE, operation="applyRefactorRecipe").to_dict()
    assert report["api"]["boundaryCrossed"] is False
    assert report["api"]["mainTypesTouched"] == []
    assert SERVICE_TEST_JAVA in report["tests"]["touchedTestFiles"]
    assert SERVICE_TEST_FQN in report["tests"]["impacted"]


def test_impact_report_accepts_plain_risk_string() -> None:
    edit = RefactorWorkspaceEdit()
    report = _builder().build(edit, risk="SAFE", operation="x").to_dict()
    assert report["risk"]["level"] == "SAFE"
    assert report["java"]["fileCount"] == 0


# -- acceptance matrix -----------------------------------------------------------------------------


def test_acceptance_matrix_covers_every_goal_and_invariant() -> None:
    matrix = acceptance_matrix()
    goals = {cast(str, row["goal"]) for row in matrix}
    # G002 (the transformation graph) is a real shipped row bound to an exposed graph tool — every goal in the
    # full G001..G011 range maps to at least one real tool, with NO gap.
    expected = {f"G{n:03d}" for n in range(1, 12)}
    assert expected <= goals, expected - goals
    assert "G002" in goals
    # every tool carries the full invariant vector and satisfies ALL eight invariants — there is no per-row
    # exception that blesses a missing guarantee.
    for row in matrix:
        assert set(cast(dict[str, bool], row["invariants"])) == set(V3_INVARIANTS)
        assert all(cast(dict[str, bool], row["invariants"]).values()), f"{row['tool']} fails an invariant: {row['invariants']}"


def test_acceptance_matrix_javac_validated_is_universal_with_honest_provenance() -> None:
    # javacValidated (the eighth invariant) is the universal real-javac guarantee and is True for EVERY row: the
    # design requires every V3 surface to be compiler-backed, with no read-only exception. The only per-row
    # distinction is PROVENANCE — how the row is compiler-backed — never whether the invariant holds.
    matrix = acceptance_matrix()
    assert "javacValidated" in V3_INVARIANTS
    assert all(cast(dict[str, object], row["invariants"])["javacValidated"] for row in matrix)

    provenance = {row["tool"]: row["provenance"] for row in matrix}
    # edit-emitting tools validate via a real before/after javac diagnostic delta.
    delta = {tool for tool, prov in provenance.items() if prov == "javac-delta"}
    assert delta == {
        "transformationWorkspace",
        "renamePackage",
        "movePackage",
        "moveSourceRoot",
        "propagatingSafeDelete",
        "extractClass",
        "extractSuperclass",
        "replaceInheritanceWithDelegation",
        "deepInlineMethod",
        "convertAnonymousToLambda",
        "convertLambdaToMethodReference",
        "applyRefactorRecipe",
    }
    # read-only tools emit no edit but derive their analysis from real javac facts (not text heuristics).
    facts = {tool for tool, prov in provenance.items() if prov == "javac-facts"}
    assert facts == {
        "transformationGraph",
        "deadCodeScan",
        "resourceProviders",
        "frameworkDetect",
        "frameworkReferences",
        "scanMigrationOpportunities",
        "impactReport",
        "transformationGraph",
    }
    # provenance is one of the two honest, compiler-backed kinds — never an "unvalidated" marker.
    assert set(provenance.values()) == {"javac-delta", "javac-facts"}


def test_acceptance_matrix_is_defensively_copied() -> None:
    cast(dict[str, object], acceptance_matrix()[0]["invariants"])["previewFirst"] = False
    first_invariants = cast(dict[str, object], ACCEPTANCE_MATRIX[0]["invariants"])
    assert first_invariants["previewFirst"] is True


# -- refusal-code registry -------------------------------------------------------------------------


def test_every_refusal_code_is_documented() -> None:
    codes = all_refusal_codes()
    assert codes, "registry must not be empty"
    for code, description in codes.items():
        assert code and code == code.lower(), code
        assert isinstance(description, str) and description.strip(), code
    # spot-check codes from across the goals are all present in the single registry.
    expected = {
        "workspace_apply_failed",
        "package_collision",
        "deadcode_blocked_root",
        "extract_name_collision",
        "delegation_super_call_hazard",
        "inline_recursion_hazard",
        "anon_declares_field",
        "lambda_unsupported_shape",
        "recipe_no_matches",
    }
    assert expected <= set(codes)


# -- no JetBrains ----------------------------------------------------------------------------------


def test_no_jetbrains_execution_path() -> None:
    findings = jetbrains_references()
    assert findings == [], f"V3 must not depend on JetBrains/IntelliJ: {findings}"


def test_jetbrains_scanner_detects_a_planted_reference(tmp_path: Path) -> None:
    (tmp_path / "bad.py").write_text("import com.intellij.openapi  # noqa\n", encoding="utf-8")
    (tmp_path / "ok.py").write_text("x = 'the ideal solution'\n", encoding="utf-8")
    findings = jetbrains_references(tmp_path)
    assert len(findings) == 1
    assert findings[0]["path"] == "bad.py"


# -- large-repo performance smoke ------------------------------------------------------------------


def test_impact_report_scales_to_a_large_synthetic_repo() -> None:
    type_count = 400
    touched_types = [
        {
            "fqn": f"com.acme.big.Type{i}",
            "relativePath": f"src/main/java/com/acme/big/Type{i}.java",
            "publicApi": True,
            "testSource": False,
        }
        for i in range(type_count)
    ]
    raw = {
        "sourceRoots": {"main": ["src/main/java"], "test": [], "resources": []},
        "touchedTypes": touched_types,
        "incomingRefs": [],
        "resourceRefs": [],
    }
    builder = ImpactReportBuilder("/proj", cast(Any, _graph_from(raw)))
    edit = RefactorWorkspaceEdit(
        text_edits=[
            RefactorTextEdit(
                relative_path=f"src/main/java/com/acme/big/Type{i}.java", start_offset=0, end_offset=0, replacement=""
            )
            for i in range(0, type_count, 4)
        ]
    )
    start = time.perf_counter()
    report = builder.build(edit, risk=RiskLevel.SAFE, operation="applyRefactorRecipe").to_dict()
    elapsed = time.perf_counter() - start
    assert report["java"]["fileCount"] == type_count // 4
    # generous bound: report building over a large edit must stay well under a second.
    assert elapsed < 2.0, f"impact report took {elapsed:.3f}s"
