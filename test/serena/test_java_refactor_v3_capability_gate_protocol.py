"""Live-sidecar coverage for the V3 dispatch capability gate (refactor-feature-plan-V3.md §20; hard blocker B14).

The dedicated V3 JSON-RPC methods (``transformation.*``, ``deletion.*``, ``classRefactor.*``, ``conversions.*``,
``inlineRefactor.*``, ``recipes.*``, ``resources.*``, ``frameworks.*``, ``impact.facts``) reach their planners directly
and so bypass the preview/apply ``operationEnabled()`` gate. Before B14 they ran even when ``java_refactor.v3`` config
disabled them. These tests boot the real sidecar twice per op — once with the op disabled via config and once with the
default config — and prove the gate is BOTH real (the disabled run is refused with the canonical ``operation_disabled``
code naming the offending config key) AND non-silent (the default run is never refused with ``operation_disabled``, so
the refusal in the disabled run is caused by the gate, not by some unrelated planner precondition).
"""

from __future__ import annotations

import contextlib
import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


@contextlib.contextmanager
def _sidecar(sidecar_jar: Path, project_root: Path, configuration: str, java_command: str = "java") -> Iterator[JavaRefactorClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        yield client
    finally:
        client.shutdown()


def _config(v3_overrides: dict[str, Any]) -> str:
    """Serializes a ``java_refactor.v3`` config block as the legacy ``configuration`` JSON string the sidecar parses."""
    return json.dumps({"java_refactor": {"v3": v3_overrides}})


# (method, request params, the v3 config block that disables it). Each entry exercises one gate family. The disabled run
# only needs the gate to fire — the gate runs BEFORE the planner, so even minimal params are refused first.
_CASES = [
    pytest.param("impact.facts", {}, {"enabled": False}, id="global-v3-enabled"),
    pytest.param("transformation.list", {}, {"enabled": False}, id="global-gates-transformation"),
    pytest.param(
        "deletion.findDeadCode", {"scope": "project"}, {"enabled": False}, id="global-gates-find-dead-code"
    ),
    pytest.param(
        "deletion.propagateSafeDelete",
        {"roots": []},
        {"deletion": {"propagate_enabled": False}},
        id="deletion.propagate_enabled",
    ),
    pytest.param(
        "classRefactor.extractClass",
        {},
        {"class_refactors": {"extract_class_enabled": False}},
        id="class_refactors.extract_class_enabled",
    ),
    pytest.param(
        "classRefactor.extractSuperclass",
        {},
        {"class_refactors": {"extract_superclass_enabled": False}},
        id="class_refactors.extract_superclass_enabled",
    ),
    pytest.param(
        "classRefactor.replaceInheritanceWithDelegation",
        {},
        {"class_refactors": {"replace_inheritance_with_delegation_enabled": False}},
        id="class_refactors.replace_inheritance_with_delegation_enabled",
    ),
    pytest.param(
        "conversions.anonymousToLambda",
        {},
        {"conversions": {"anonymous_to_lambda_enabled": False}},
        id="conversions.anonymous_to_lambda_enabled",
    ),
    pytest.param(
        "conversions.lambdaToMethodReference",
        {},
        {"conversions": {"lambda_to_method_reference_enabled": False}},
        id="conversions.lambda_to_method_reference_enabled",
    ),
    pytest.param(
        "inlineRefactor.deepInlineMethod",
        {},
        {"inline": {"deep_inline_enabled": False}},
        id="inline.deep_inline_enabled",
    ),
    pytest.param(
        "recipes.scanMigrationOpportunities",
        {"recipeId": "date-calendar-to-java-time-basic"},
        {"recipes": {"enabled": False}},
        id="recipes.enabled-scan",
    ),
    pytest.param(
        "recipes.applyRecipe",
        {"recipe": {"id": "noop", "rules": []}},
        {"recipes": {"enabled": False}},
        id="recipes.enabled-apply",
    ),
    pytest.param(
        "resources.findReferences", {}, {"resources": {"enabled": False}}, id="resources.enabled"
    ),
    pytest.param("frameworks.detect", {}, {"frameworks": {"enabled": False}}, id="frameworks.enabled-detect"),
    pytest.param(
        "frameworks.findReferences", {}, {"frameworks": {"enabled": False}}, id="frameworks.enabled-find"
    ),
]


def _refusal_code(result: dict[str, Any]) -> str | None:
    refusal = result.get("refusal")
    return refusal.get("code") if isinstance(refusal, dict) else None


@pytest.mark.parametrize(("method", "params", "disable_v3"), _CASES)
def test_v3_op_refused_when_disabled_by_config(
    sidecar_jar: Path,
    tmp_path: Path,
    sidecar_java_cmd: str,
    method: str,
    params: dict[str, Any],
    disable_v3: dict[str, Any],
) -> None:
    # A trivial compilable project so the only thing that can differ between the two runs is the config gate.
    (tmp_path / "src/main/java/com/acme").mkdir(parents=True, exist_ok=True)
    (tmp_path / "src/main/java/com/acme/A.java").write_text(
        "package com.acme;\npublic class A {}\n", encoding="utf-8"
    )
    request = {"params": params}

    with _sidecar(sidecar_jar, tmp_path, _config(disable_v3), java_command=sidecar_java_cmd) as client:
        disabled = client._request(method, request)
    assert disabled.get("accepted") is False, disabled
    assert _refusal_code(disabled) == "operation_disabled", disabled
    # The refusal must name the offending config key so the disablement is honest, not a generic catch-all.
    assert "java_refactor.v3" in disabled["refusal"]["message"], disabled

    # Non-silence: under the default config the SAME call is never refused with operation_disabled, proving the gate
    # (not an unrelated planner precondition) caused the refusal above. It may be accepted or refused for another reason.
    with _sidecar(sidecar_jar, tmp_path, "default", java_command=sidecar_java_cmd) as client:
        enabled = client._request(method, request)
    assert _refusal_code(enabled) != "operation_disabled", enabled
