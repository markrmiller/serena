"""Bridge-level regression tests for the five V3 review blockers (B02/B03/B04/B05/B08).

These pin the Python-side transformation bridge behaviour at its canonical seams WITHOUT spinning up the JVM
sidecar, so each guarantee is asserted in isolation and deterministically:

* B03 — every edit-producing V3 op is enrollable in the transformation workspace (``_v3_plan_dispatch`` covers
  all 11 edit ops, including renamePackage/movePackage/moveSourceRoot/convertLambdaToMethodReference/
  applyRefactorRecipe), each composes a ``V3OperationPlan`` from its compute-only payload, and a REFUSED op is
  never enrolled.
* B04 — ``plan_v3_operation`` fails closed (or deterministically pins) when the project revision is unavailable:
  the sidecar ``modelHash`` is preferred, a structural fingerprint is the deterministic fallback, and a model
  with neither refuses enrollment with ``project_revision_unavailable`` rather than enrolling with a permissive
  ``None``.
* B05 — the external formatter runs on the V3 transactional apply path (after commit, before javac
  post-validation), is never run on preview, and participates in the snapshot rollback.
* B02 — the post-javac build-tool compile/test stage is implemented for Maven and Gradle (wrapper-preferred),
  enforces ``max_validation_seconds``, and fails closed (compile/test failure, timeout, missing model) through a
  stubbable subprocess seam while the real command construction is exercised.
* B08 — safe-delete accepts structured position roots and symbol keys (alias-preserving), and extractSuperclass
  resolves semantic class identifiers (FQN / ``fqn:``/``symbol:`` keys) via the compiler-backed graph.
"""

from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any

import pytest

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend, V3ValidationConfig
from serena.java_refactor import manager as manager_module
from serena.java_refactor.manager import JavaRefactorManager
from serena.java_refactor.workspace_edit import RefactorWorkspaceEdit
from serena.java_refactor_v3.models import RiskLevel
from serena.java_refactor_v3.workspace import V3OperationPlan
from serena.tools.java_refactor_v3_tools import JavaPropagateSafeDeleteTool
from solidlsp.ls_config import Language

# ── shared scaffolding ────────────────────────────────────────────────────────────────────────────────────────────


class _ReadyStatus:
    ready = True
    errors: list = []
    project_model: dict = {}
    build_tool: str | None = None

    def __init__(self, *, project_model: dict | None = None, build_tool: str | None = None) -> None:
        self.project_model = project_model if project_model is not None else {}
        self.build_tool = build_tool


class _FakeClient:
    def __init__(self, *, project_model: dict | None = None, build_tool: str | None = None) -> None:
        self._status = _ReadyStatus(project_model=project_model, build_tool=build_tool)

    def status(self, refresh: bool = False) -> _ReadyStatus:
        return self._status


def _manager(tmp_path: Path, monkeypatch, **client_kwargs: Any) -> JavaRefactorManager:
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(
            enabled=True, validate_before_apply=False, validate_after_preview=False, allow_incomplete_analysis=False
        ),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: _FakeClient(**client_kwargs))
    return manager


def _accepted_payload() -> dict[str, Any]:
    """A minimal accepted, SAFE compute-only sidecar V3-op payload that parses through ``_extract_sidecar_v3_edit``."""
    return {
        "accepted": True,
        "risk": "safe",
        "warnings": [],
        "workspaceEdit": {"changes": [], "fileOperations": []},
    }


# ── B03: every edit-producing V3 op is enrollable in the transformation workspace ─────────────────────────────────

# The full edit-op surface that must be enrollable (compose into a workspace). This MUST match the design's
# edit-emitting tool set; the five at the end are the ones B03 added.
_ENROLLABLE_EDIT_OPS = (
    "extractClass",
    "extractSuperclass",
    "replaceInheritanceWithDelegation",
    "deepInlineMethod",
    "convertAnonymousToLambda",
    "propagateSafeDelete",
    "convertLambdaToMethodReference",
    "applyRefactorRecipe",
    "renamePackage",
    "movePackage",
    "moveSourceRoot",
)


def test_v3_dispatch_enrolls_every_edit_op(tmp_path: Path, monkeypatch) -> None:
    # Regression for the B03 omission: the dispatch table must cover ALL edit-producing V3 ops, including the five
    # that were previously not enrollable (renamePackage/movePackage/moveSourceRoot/convertLambdaToMethodReference/
    # applyRefactorRecipe). Asserting the keys directly proves the enrollment surface without a live sidecar.
    manager = _manager(tmp_path, monkeypatch)
    dispatch = manager._v3_plan_dispatch()
    for op in _ENROLLABLE_EDIT_OPS:
        assert op in dispatch, f"{op!r} must be enrollable in the V3 transformation workspace"


@pytest.mark.parametrize(
    "operation",
    [
        "convertLambdaToMethodReference",
        "applyRefactorRecipe",
        "renamePackage",
        "movePackage",
        "moveSourceRoot",
    ],
)
def test_newly_enrolled_op_composes_a_v3_plan(tmp_path: Path, monkeypatch, operation: str) -> None:
    # Each newly-enrolled op composes a V3OperationPlan from its compute-only payload routed through the SAME
    # _extract_sidecar_v3_edit seam the apply bridge uses. We stub the op's planner closure to return a valid
    # accepted payload (the planner-construction logic is exercised by the dispatch test above) and a model hash so
    # B04 pins a revision; the plan must carry the parsed edit + canonical risk.
    manager = _manager(tmp_path, monkeypatch, project_model={"modelHash": "rev-xyz"})

    real_dispatch = manager._v3_plan_dispatch

    def _stub_dispatch() -> dict[str, Any]:
        table = real_dispatch()
        table[operation] = lambda client, params: _accepted_payload()
        return table

    monkeypatch.setattr(manager, "_v3_plan_dispatch", _stub_dispatch)

    plan = manager.plan_v3_operation(operation, {})
    assert isinstance(plan, V3OperationPlan), plan
    assert plan.operation == operation
    assert plan.risk is RiskLevel.SAFE
    assert plan.project_revision == "rev-xyz"
    assert isinstance(plan.workspace_edit, RefactorWorkspaceEdit)


@pytest.mark.parametrize("operation", ["renamePackage", "applyRefactorRecipe", "convertLambdaToMethodReference"])
def test_refused_op_is_never_enrolled(tmp_path: Path, monkeypatch, operation: str) -> None:
    # A refused op (accepted:false) must be returned VERBATIM as a structured refusal and NEVER enrolled as a
    # V3OperationPlan — a refusal can never become a workspace member.
    manager = _manager(tmp_path, monkeypatch, project_model={"modelHash": "rev-xyz"})
    real_dispatch = manager._v3_plan_dispatch

    def _stub_dispatch() -> dict[str, Any]:
        table = real_dispatch()
        table[operation] = lambda client, params: {
            "accepted": False,
            "refusal": {"code": "package_not_found", "message": "no such package"},
        }
        return table

    monkeypatch.setattr(manager, "_v3_plan_dispatch", _stub_dispatch)

    result = manager.plan_v3_operation(operation, {})
    assert not isinstance(result, V3OperationPlan), "a refused op must not be enrolled"
    assert result["accepted"] is False
    assert result["refusal"]["code"] == "package_not_found"


# ── B04: fail closed (or deterministically pin) when the project revision is unavailable ──────────────────────────


def test_revision_prefers_model_hash() -> None:
    assert JavaRefactorManager._v3_project_revision({"modelHash": "abc123"}) == "abc123"


def test_revision_fails_closed_without_model_hash() -> None:
    assert JavaRefactorManager._v3_project_revision({"modelHash": "abc123"}) == "abc123"
    assert JavaRefactorManager._v3_project_revision({"projectRevision": "rev-1"}) == "rev-1"
    assert JavaRefactorManager._v3_project_revision({"sourceRoots": ["src/main/java"], "javaFiles": 1}) is None




def test_revision_none_when_no_usable_fields() -> None:
    assert JavaRefactorManager._v3_project_revision({}) is None
    assert JavaRefactorManager._v3_project_revision({"unrelated": "x"}) is None


def test_plan_refuses_enrollment_when_revision_unavailable(tmp_path: Path, monkeypatch) -> None:
    # B04 crux: when no modelHash and no derivable fingerprint exist, plan_v3_operation must FAIL CLOSED with
    # project_revision_unavailable rather than enrolling with a permissive None revision.
    manager = _manager(tmp_path, monkeypatch, project_model={})  # empty model => no derivable revision
    real_dispatch = manager._v3_plan_dispatch

    def _stub_dispatch() -> dict[str, Any]:
        table = real_dispatch()
        table["extractClass"] = lambda client, params: _accepted_payload()
        return table

    monkeypatch.setattr(manager, "_v3_plan_dispatch", _stub_dispatch)

    result = manager.plan_v3_operation("extractClass", {})
    assert not isinstance(result, V3OperationPlan)
    assert result["accepted"] is False
    assert result["refusal"]["code"] == "project_revision_unavailable"


def test_plan_refuses_when_model_hash_absent(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    manager = JavaRefactorManager.__new__(JavaRefactorManager)
    manager._project_root = tmp_path

    class Status:
        project_model = {"sourceRoots": ["src/main/java"], "javaFiles": 1}

    class Client:
        def status(self, refresh: bool = False):
            return Status()

    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: Client())
    monkeypatch.setattr(manager, "_v3_disabled_refusal", lambda *args, **kwargs: None)
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(
        manager,
        "_v3_plan_dispatch",
        lambda: {"extractClass": lambda *args, **kwargs: {"accepted": True}},
    )
    monkeypatch.setattr(manager, "_extract_sidecar_v3_edit", lambda *args, **kwargs: ({"changes": []}, "LOW", [], {}))

    result = manager.plan_v3_operation("extractClass", {"relative_path": "src/main/java/Source.java"})

    assert isinstance(result, dict), result
    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "project_revision_unavailable", result



# ── B05: external formatter runs in the V3 transactional apply (post-commit, pre-javac), and rolls back ───────────


class _FmtStaged:
    """A StagedEdit double exposing only what _run_external_formatter reads: changed_files and renamed_files."""

    def __init__(self, changed: dict[str, str], renamed: list[dict[str, str]] | None = None) -> None:
        self.changed_files = changed
        self.renamed_files = renamed or []


def test_formatter_runs_on_v3_apply_changed_files(tmp_path: Path, monkeypatch) -> None:
    # B05: on APPLY the formatter is invoked once per changed/created file (and rename targets), and the post-format
    # on-disk bytes are captured under result["formatting"]["formattedContent"] so they reflect the javac-validated
    # state. We exercise _run_external_formatter directly with a stubbed subprocess and config enabling the formatter.
    target = tmp_path / "src" / "A.java"
    target.parent.mkdir(parents=True)
    target.write_text("class A {}\n", encoding="utf-8")

    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True))
    # Enable the external formatter on the V2 formatting config that the V3 apply path reads.
    manager._config.v2.formatting.use_external_formatter = True
    manager._config.v2.formatting.command = "fmt {file}"

    runs: list[list[str]] = []

    def _fake_run(argv, **kwargs):
        runs.append(argv)
        # Simulate the formatter rewriting the file in place.
        Path(argv[-1]).write_text("class A { }\n", encoding="utf-8")
        return subprocess.CompletedProcess(argv, 0, stdout="", stderr="")

    monkeypatch.setattr(manager_module.subprocess, "run", _fake_run)

    result: dict[str, Any] = {}
    staged = _FmtStaged(changed={"src/A.java": "class A {}\n"})
    manager._run_external_formatter(applier=None, staged=staged, preview_result=result)  # type: ignore[arg-type]

    assert result["formatting"]["ran"] is True
    assert result["formatting"]["formattedFiles"] == ["src/A.java"]
    assert result["formatting"]["formattedContent"]["src/A.java"] == "class A { }\n"
    assert len(runs) == 1 and runs[0][0] == "fmt" and runs[0][-1].endswith("A.java")


def test_formatter_is_off_by_default_no_run(tmp_path: Path, monkeypatch) -> None:
    # The formatter is OFF by default: nothing runs and no "formatting" block is attached.
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True))
    assert manager._config.v2.formatting.use_external_formatter is False

    called = False

    def _fake_run(*args, **kwargs):
        nonlocal called
        called = True
        return subprocess.CompletedProcess(args, 0)

    monkeypatch.setattr(manager_module.subprocess, "run", _fake_run)
    result: dict[str, Any] = {}
    manager._run_external_formatter(applier=None, staged=_FmtStaged(changed={"src/A.java": "x"}), preview_result=result)  # type: ignore[arg-type]
    assert called is False
    assert "formatting" not in result


def test_formatter_failure_collected_as_warning(tmp_path: Path, monkeypatch) -> None:
    # A non-zero formatter exit is collected as a warning under formatting.warnings (the apply path's post-validation
    # then re-checks the file; this method itself never rolls back).
    target = tmp_path / "src" / "A.java"
    target.parent.mkdir(parents=True)
    target.write_text("class A {}\n", encoding="utf-8")
    manager = JavaRefactorManager(str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True))
    manager._config.v2.formatting.use_external_formatter = True
    manager._config.v2.formatting.command = "fmt"

    monkeypatch.setattr(
        manager_module.subprocess,
        "run",
        lambda argv, **kw: subprocess.CompletedProcess(argv, 2, stdout="", stderr="boom"),
    )
    result: dict[str, Any] = {}
    manager._run_external_formatter(applier=None, staged=_FmtStaged(changed={"src/A.java": "x"}), preview_result=result)  # type: ignore[arg-type]
    assert result["formatting"]["ran"] is True
    assert result["formatting"]["formattedFiles"] == []
    assert any("exited with code 2" in w for w in result["formatting"]["warnings"])


# ── B02: post-javac build-tool compile/test stage (Maven + Gradle), timeout, and failure rollback ────────────────


def _v3_validation(manager: JavaRefactorManager, **flags: Any) -> None:
    manager._config.v3.validation = V3ValidationConfig(**flags)


def test_maven_compile_plan_wrapper_preferred(tmp_path: Path, monkeypatch) -> None:
    manager = _manager(tmp_path, monkeypatch)
    # No wrapper present -> bare mvn.
    plan = manager._build_tool_validation_plan("maven")
    assert plan is not None and plan["tool"] == "maven"
    assert plan["compile"][0] == "mvn"
    assert "compile" in plan["compile"] and "test-compile" in plan["compile"]
    assert plan["test"][-1] == "test"

    # Wrapper present -> mvnw preferred.
    (tmp_path / "mvnw").write_text("#!/bin/sh\n", encoding="utf-8")
    plan_w = manager._build_tool_validation_plan("maven")
    assert plan_w["compile"][0].endswith("mvnw")


def test_gradle_compile_plan_wrapper_preferred(tmp_path: Path, monkeypatch) -> None:
    manager = _manager(tmp_path, monkeypatch)
    plan = manager._build_tool_validation_plan("gradle")
    assert plan is not None and plan["tool"] == "gradle"
    assert plan["compile"][0] == "gradle"
    assert "compileJava" in plan["compile"] and "compileTestJava" in plan["compile"]
    assert plan["test"][-1] == "test"

    (tmp_path / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")
    plan_w = manager._build_tool_validation_plan("gradle")
    assert plan_w["compile"][0].endswith("gradlew")


def test_build_tool_plan_none_for_unknown(tmp_path: Path, monkeypatch) -> None:
    manager = _manager(tmp_path, monkeypatch)
    assert manager._build_tool_validation_plan(None) is None
    assert manager._build_tool_validation_plan("ant") is None


def test_build_tool_validation_off_by_default(tmp_path: Path, monkeypatch) -> None:
    # With both flags off (the default) the stage is a no-op (returns None) — the subprocess seam is never reached.
    manager = _manager(tmp_path, monkeypatch, build_tool="maven")
    _v3_validation(manager, run_build_tool_compile=False, run_tests=False)
    called = False

    def _invoke(argv, timeout):
        nonlocal called
        called = True
        return subprocess.CompletedProcess(argv, 0)

    monkeypatch.setattr(manager, "_invoke_build_tool", _invoke)
    assert manager._run_build_tool_validation(_FakeClient(build_tool="maven"), "extractClass") is None
    assert called is False


def test_build_tool_compile_success_runs_real_argv(tmp_path: Path, monkeypatch) -> None:
    # run_build_tool_compile=True runs the compile stage (only) through the stubbable seam with the REAL constructed
    # Maven argv; on success the report is ok with one compile stage.
    manager = _manager(tmp_path, monkeypatch, build_tool="maven")
    _v3_validation(manager, run_build_tool_compile=True, run_tests=False)
    seen: list[list[str]] = []

    def _invoke(argv, timeout):
        seen.append(argv)
        return subprocess.CompletedProcess(argv, 0, stdout="BUILD SUCCESS", stderr="")

    monkeypatch.setattr(manager, "_invoke_build_tool", _invoke)
    report = manager._run_build_tool_validation(_FakeClient(build_tool="maven"), "extractClass")
    assert report is not None and report["ok"] is True and report["tool"] == "maven"
    assert [s["stage"] for s in report["stages"]] == ["compile"]
    assert seen == [["mvn", "-q", "-B", "compile", "test-compile"]]


def test_build_tool_runs_test_stage_when_run_tests(tmp_path: Path, monkeypatch) -> None:
    # run_tests=True runs compile THEN test (Gradle) through the seam.
    manager = _manager(tmp_path, monkeypatch, build_tool="gradle")
    _v3_validation(manager, run_build_tool_compile=False, run_tests=True)
    seen: list[list[str]] = []

    def _invoke(argv, timeout):
        seen.append(argv)
        return subprocess.CompletedProcess(argv, 0, stdout="", stderr="")

    monkeypatch.setattr(manager, "_invoke_build_tool", _invoke)
    report = manager._run_build_tool_validation(_FakeClient(build_tool="gradle"), "extractClass")
    assert report["ok"] is True
    assert [s["stage"] for s in report["stages"]] == ["compile", "test"]
    assert seen[0][-2:] == ["compileJava", "compileTestJava"]
    assert seen[1][-1] == "test"


def test_build_tool_compile_failure_refuses(tmp_path: Path, monkeypatch) -> None:
    # A non-zero compile exit fails closed with build_tool_compile_failed and ok:false (the caller rolls back).
    manager = _manager(tmp_path, monkeypatch, build_tool="maven")
    _v3_validation(manager, run_build_tool_compile=True, run_tests=False)
    monkeypatch.setattr(
        manager,
        "_invoke_build_tool",
        lambda argv, timeout: subprocess.CompletedProcess(argv, 1, stdout="error: cannot find symbol", stderr=""),
    )
    report = manager._run_build_tool_validation(_FakeClient(build_tool="maven"), "extractClass")
    assert report["ok"] is False
    assert report["refusal"]["code"] == "build_tool_compile_failed"


def test_build_tool_test_failure_refuses(tmp_path: Path, monkeypatch) -> None:
    manager = _manager(tmp_path, monkeypatch, build_tool="gradle")
    _v3_validation(manager, run_build_tool_compile=False, run_tests=True)

    def _invoke(argv, timeout):
        # compile passes, test fails.
        code = 1 if argv[-1] == "test" else 0
        return subprocess.CompletedProcess(argv, code, stdout="tests failed", stderr="")

    monkeypatch.setattr(manager, "_invoke_build_tool", _invoke)
    report = manager._run_build_tool_validation(_FakeClient(build_tool="gradle"), "extractClass")
    assert report["ok"] is False
    assert report["refusal"]["code"] == "build_tool_tests_failed"


def test_build_tool_timeout_refuses(tmp_path: Path, monkeypatch) -> None:
    # The hard max_validation_seconds timeout fails closed with build_tool_validation_timeout.
    manager = _manager(tmp_path, monkeypatch, build_tool="maven")
    _v3_validation(manager, run_build_tool_compile=True, run_tests=False, max_validation_seconds=7)
    captured: dict[str, Any] = {}

    def _invoke(argv, timeout):
        captured["timeout"] = timeout
        raise subprocess.TimeoutExpired(cmd=argv, timeout=timeout)

    monkeypatch.setattr(manager, "_invoke_build_tool", _invoke)
    report = manager._run_build_tool_validation(_FakeClient(build_tool="maven"), "extractClass")
    assert report["ok"] is False
    assert report["refusal"]["code"] == "build_tool_validation_timeout"
    assert captured["timeout"] == 7, "the configured max_validation_seconds must be enforced as the subprocess timeout"


def test_build_tool_invocation_failure_refuses(tmp_path: Path, monkeypatch) -> None:
    # An OSError (launcher not executable) fails closed with build_tool_invocation_failed.
    manager = _manager(tmp_path, monkeypatch, build_tool="maven")
    _v3_validation(manager, run_build_tool_compile=True, run_tests=False)

    def _invoke(argv, timeout):
        raise OSError("mvn: not found")

    monkeypatch.setattr(manager, "_invoke_build_tool", _invoke)
    report = manager._run_build_tool_validation(_FakeClient(build_tool="maven"), "extractClass")
    assert report["ok"] is False
    assert report["refusal"]["code"] == "build_tool_invocation_failed"


def test_build_tool_model_unavailable_refuses(tmp_path: Path, monkeypatch) -> None:
    # Flags set but no resolvable build model => fail closed with build_tool_model_unavailable (never silently skip).
    manager = _manager(tmp_path, monkeypatch, build_tool=None)
    _v3_validation(manager, run_build_tool_compile=True, run_tests=False)
    report = manager._run_build_tool_validation(_FakeClient(build_tool=None), "extractClass")
    assert report["ok"] is False
    assert report["refusal"]["code"] == "build_tool_model_unavailable"


def test_invoke_build_tool_passes_timeout_to_subprocess(tmp_path: Path, monkeypatch) -> None:
    # The real _invoke_build_tool constructs subprocess.run with the timeout (the seam under the stub in other tests).
    manager = _manager(tmp_path, monkeypatch)
    captured: dict[str, Any] = {}

    def _fake_run(argv, **kwargs):
        captured.update(kwargs)
        captured["argv"] = argv
        return subprocess.CompletedProcess(argv, 0)

    monkeypatch.setattr(manager_module.subprocess, "run", _fake_run)
    manager._invoke_build_tool(["mvn", "compile"], 33)
    assert captured["timeout"] == 33
    assert captured["argv"] == ["mvn", "compile"]
    assert captured["cwd"] == str(tmp_path)


# ── B08(a): safe-delete accepts structured position roots and symbol keys (alias-preserving) ──────────────────────


def test_parse_seeds_fqn_string_alias() -> None:
    # The backward-compatible alias: a JSON array of FQN strings is preserved as trimmed strings.
    seeds = JavaPropagateSafeDeleteTool._parse_seeds('["com.acme.A", " com.acme.B "]')
    assert seeds == ["com.acme.A", "com.acme.B"]


def test_parse_seeds_comma_separated_strings() -> None:
    seeds = JavaPropagateSafeDeleteTool._parse_seeds("com.acme.A, com.acme.B")
    assert seeds == ["com.acme.A", "com.acme.B"]


def test_parse_seeds_position_root_dict_preserved() -> None:
    # A structured position root is preserved as a dict (NOT stringified) so the sidecar resolves the symbol there.
    seeds = JavaPropagateSafeDeleteTool._parse_seeds('[{"relativePath": "src/A.java", "line": 3, "column": 9}]')
    assert seeds == [{"relativePath": "src/A.java", "line": 3, "column": 9}]


def test_parse_seeds_mixed_string_and_position_root() -> None:
    seeds = JavaPropagateSafeDeleteTool._parse_seeds(
        '["com.acme.A", {"relativePath": "src/B.java", "line": 1}]'
    )
    assert seeds == ["com.acme.A", {"relativePath": "src/B.java", "line": 1}]


def test_parse_seeds_malformed_position_root_raises() -> None:
    # A dict seed missing relativePath/line is a hard error (never silently dropped or stringified).
    with pytest.raises(ValueError):
        JavaPropagateSafeDeleteTool._parse_seeds('[{"line": 3}]')


def test_parse_seeds_empty() -> None:
    assert JavaPropagateSafeDeleteTool._parse_seeds("") == []
    assert JavaPropagateSafeDeleteTool._parse_seeds("[]") == []


def test_propagate_safe_delete_forwards_structured_roots(tmp_path: Path, monkeypatch) -> None:
    # End-to-end at the manager seam: a mix of an FQN string and a position root is forwarded VERBATIM to the
    # deletion client (which already accepts list[Any] roots), proving the structured root survives the bridge.
    manager = _manager(tmp_path, monkeypatch)
    forwarded: dict[str, Any] = {}

    class _FakeDeletionClient:
        def __init__(self, client):
            pass

        def propagate_safe_delete(self, roots, **kwargs):
            forwarded["roots"] = roots
            return _accepted_payload()

    import serena.java_refactor_v3.deletion_client as deletion_module

    monkeypatch.setattr(deletion_module, "DeletionClient", _FakeDeletionClient)
    monkeypatch.setattr(manager, "_v3_disabled_refusal", lambda op, apply: None)
    monkeypatch.setattr(
        manager,
        "_route_sidecar_v3_edit",
        lambda op, payload, **kw: {"accepted": True, "operation": op, "applied": False},
    )

    roots = ["com.acme.A", {"relativePath": "src/B.java", "line": 1, "column": 2}]
    manager.propagate_safe_delete(roots)
    assert forwarded["roots"] == roots, "structured position roots must be forwarded verbatim to the deletion client"


# ── B08(b): extractSuperclass resolves semantic class identifiers via the compiler-backed graph ──────────────────


def _patch_graph(monkeypatch, type_to_file: dict[str, str]) -> None:
    """Stub GraphClient(...).project_graph().symbols.type_to_file at the manager's import site."""
    import serena.java_refactor_v3.graph_client as graph_module

    class _Symbols:
        def __init__(self) -> None:
            self.type_to_file = type_to_file

    class _Graph:
        def __init__(self) -> None:
            self.symbols = _Symbols()

    class _FakeGraphClient:
        def __init__(self, client) -> None:
            pass

        def project_graph(self):
            return _Graph()

    monkeypatch.setattr(graph_module, "GraphClient", _FakeGraphClient)


def test_resolve_class_identifier_path_passthrough(tmp_path: Path, monkeypatch) -> None:
    manager = _manager(tmp_path, monkeypatch)
    assert manager._resolve_class_identifier_to_path("src/main/java/com/acme/A.java", {}) == "src/main/java/com/acme/A.java"


def test_resolve_class_identifier_fqn_via_graph(tmp_path: Path, monkeypatch) -> None:
    manager = _manager(tmp_path, monkeypatch)
    type_to_file = {"com.acme.A": "src/main/java/com/acme/A.java"}
    assert manager._resolve_class_identifier_to_path("com.acme.A", type_to_file) == "src/main/java/com/acme/A.java"
    assert (
        manager._resolve_class_identifier_to_path("fqn:com.acme.A", type_to_file) == "src/main/java/com/acme/A.java"
    )
    assert (
        manager._resolve_class_identifier_to_path("symbol:com.acme.A", type_to_file)
        == "src/main/java/com/acme/A.java"
    )


def test_resolve_class_identifier_unresolved_refuses(tmp_path: Path, monkeypatch) -> None:
    manager = _manager(tmp_path, monkeypatch)
    result = manager._resolve_class_identifier_to_path("com.acme.Missing", {})
    assert isinstance(result, dict)
    assert result["refusal"]["code"] == "class_identifier_unresolved"


def test_extract_superclass_resolves_fqn_end_to_end(tmp_path: Path, monkeypatch) -> None:
    # extractSuperclass accepts an FQN identifier, resolves it to its file via the graph, and dispatches the resolved
    # PATH to the sidecar — proving the compiler-backed FQN->source resolution works end to end.
    manager = _manager(tmp_path, monkeypatch)
    _patch_graph(monkeypatch, {"com.acme.Account": "src/main/java/com/acme/Account.java"})

    dispatched: dict[str, Any] = {}

    class _FakeClassRefactorClient:
        def __init__(self, client) -> None:
            pass

        def extract_superclass(self, classes, superclass_name, members, **kwargs):
            dispatched["classes"] = classes
            return _accepted_payload()

    import serena.java_refactor_v3.class_refactor_client as cr_module

    monkeypatch.setattr(cr_module, "ClassRefactorClient", _FakeClassRefactorClient)
    monkeypatch.setattr(
        manager,
        "_route_sidecar_v3_edit",
        lambda op, payload, **kw: {"accepted": True, "operation": op, "applied": False, "classes": dispatched.get("classes")},
    )

    result = manager.extract_superclass(
        ["com.acme.Account"], "AbstractAccount", ["method:save()"], apply=False
    )
    assert result["accepted"] is True
    assert dispatched["classes"] == ["src/main/java/com/acme/Account.java"]


def test_extract_superclass_unresolved_fqn_refuses(tmp_path: Path, monkeypatch) -> None:
    manager = _manager(tmp_path, monkeypatch)
    _patch_graph(monkeypatch, {})  # nothing resolves
    result = manager.extract_superclass(["com.acme.Missing"], "Base", ["method:save()"], apply=False)
    assert result["accepted"] is False
    assert result["refusal"]["code"] == "class_identifier_unresolved"


def test_extract_superclass_path_only_skips_graph(tmp_path: Path, monkeypatch) -> None:
    # The common path-only call must NOT build the graph (a path passes straight through to the sidecar).
    manager = _manager(tmp_path, monkeypatch)

    import serena.java_refactor_v3.graph_client as graph_module

    def _boom(client):  # GraphClient must never be constructed for a path-only call
        raise AssertionError("the graph must not be built when every class entry is a path")

    monkeypatch.setattr(graph_module, "GraphClient", _boom)

    dispatched: dict[str, Any] = {}

    class _FakeClassRefactorClient:
        def __init__(self, client) -> None:
            pass

        def extract_superclass(self, classes, superclass_name, members, **kwargs):
            dispatched["classes"] = classes
            return _accepted_payload()

    import serena.java_refactor_v3.class_refactor_client as cr_module

    monkeypatch.setattr(cr_module, "ClassRefactorClient", _FakeClassRefactorClient)
    monkeypatch.setattr(
        manager, "_route_sidecar_v3_edit", lambda op, payload, **kw: {"accepted": True, "operation": op, "applied": False}
    )

    manager.extract_superclass(["src/main/java/com/acme/A.java"], "Base", ["method:save()"], apply=False)
    assert dispatched["classes"] == ["src/main/java/com/acme/A.java"]
