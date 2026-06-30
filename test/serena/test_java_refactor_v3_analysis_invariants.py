from serena.java_refactor.manager import JavaRefactorManager
from serena.tools.java_refactor_v3_tools import (
    JAVA_REFACTOR_V3_CAPABILITY_TOOLS,
    V3_MATRIX_TOOL_BINDINGS,
)
from serena.config.serena_config import V3ResourcesConfig


def test_analysis_invariants_refuse_missing_provenance() -> None:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)

    result = manager._with_v3_analysis_invariants({"accepted": True}, "resources.findReferences")

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "V3_ANALYSIS_INVARIANT_MISSING"
    assert result["refusal"]["missing"] == ["projectRevision", "impact", "riskClassification", "validation"]


def test_analysis_invariants_refuse_missing_risk_classification() -> None:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)

    result = manager._with_v3_analysis_invariants(
        {
            "accepted": True,
            "factGraphRevision": "rev-no-risk",
            "validation": {"kind": "javac-facts", "javacFactsValidated": True},
            "impact": {"summary": {"files": 0}},
        },
        "resources.findReferences",
    )

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "V3_ANALYSIS_INVARIANT_MISSING"
    assert result["refusal"]["missing"] == ["riskClassification"]


def test_analysis_invariants_accept_real_fact_envelope() -> None:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)

    result = manager._with_v3_analysis_invariants(
        {
            "accepted": True,
            "factGraphRevision": "rev-1",
            "validation": {"kind": "javac-facts", "javacFactsValidated": True},
            "riskClassification": "INFO",
            "impact": {"summary": {"files": 0}},
        },
        "resources.findReferences",
    )

    assert result["accepted"] is True
    assert result["projectRevision"] == "rev-1"
    assert result["validation"]["kind"] == "javac-facts"
    assert result["impact"]["summary"] == {"files": 0}


def test_resource_rewrite_policy_keys_are_canonical_in_resources_config() -> None:
    config = V3ResourcesConfig.from_dict(
        {"rewrite_exact_class_names": False, "rewrite_package_prefixes": True}
    )

    assert config.rewrite_exact_class_names is False
    assert config.rewrite_package_prefixes is True

def test_analysis_invariants_derive_impact_for_framework_and_resource_shapes() -> None:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)

    framework = manager._with_v3_analysis_invariants(
        {
            "accepted": True,
            "factGraphRevision": "rev-fw",
            "validation": {"kind": "javac-facts", "javacFactsValidated": True},
            "riskClassification": "SAFE",
            "impact": {"summary": {"frameworkCount": 1}},
            "frameworks": [{"name": "spring", "evidence": ["src/main/resources/beans.xml"]}],
        },
        "frameworks.detect",
    )
    assert framework["accepted"] is True
    assert framework["impact"]["summary"]["frameworkCount"] == 1

    resources = manager._with_v3_analysis_invariants(
        {
            "accepted": True,
            "factGraphRevision": "rev-rs",
            "validation": {"kind": "javac-facts", "javacFactsValidated": True},
            "riskClassification": "SAFE",
            "impact": {"summary": {"matchCount": 2, "editCount": 1, "autoApplyCount": 1, "stats": {"scanned": 1}}},
            "matches": [{"resource": "src/main/resources/beans.xml"}],
            "references": [{"resource": "src/main/resources/app.properties"}],
            "edits": [{"resource": "src/main/resources/app.properties"}],
            "autoApply": [{"resource": "src/main/resources/app.properties"}],
            "reviewOnly": [],
            "stats": {"scanned": 1},
        },
        "resources.planEdits",
    )
    assert resources["accepted"] is True
    assert resources["impact"]["summary"]["matchCount"] == 2
    assert resources["impact"]["summary"]["editCount"] == 1
    assert resources["impact"]["summary"]["autoApplyCount"] == 1
    assert resources["impact"]["summary"]["stats"] == {"scanned": 1}
