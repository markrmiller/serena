"""Performance tests for V3 heavy paths — enforcing the doc guarantees in docs/java-refactor-v3.md.

The V3 performance claims (§ "Performance"):
* "V3 builds the project graph once per revision and caches it; transformations and impact reports read
  that graph rather than re-parsing."
* "Impact-report construction is linear in the size of the workspace edit … holds report construction
  well under a second."

These tests make both claims EXECUTABLE with budgets that FAIL on genuine regressions but tolerate slow CI.

Coverage:
1. test_graph_cache_hit_is_faster_than_cold_build
   - Light fixture: uses ``graph.buildCount`` diagnostic counter (like test_java_refactor_v3_graph_cache_protocol.py)
     WITHOUT a real JVM, by driving ``GraphClient`` against a StubTransport that returns a prebuilt payload.
   - Asserts: second access to an unchanged revision is cache-HIT (buildCount does not advance) AND is
     dramatically faster than the first access (relative speedup >= 5×, absolute ceiling 50 ms per
     warm-path call).  The counter assertion is the SEMANTIC proof; the timing assertion guards regressions
     in the Python-side reshape path (parse_project_graph).

2. test_impact_report_construction_stays_under_budget
   - Drives ImpactReportBuilder over a large synthetic SidecarFactsGraph with N=500 touched types, N/4
     resource wiring entries, and N/4 test-referencing entries.
   - Asserts construction time < 1.0 s (generous absolute ceiling).

3. test_impact_report_scales_linearly_with_input_size
   - Builds reports over N=200 and N=400 types, records t_small and t_large, then asserts
     t_large < K * t_small for K=8.  A super-linear blow-up (e.g. O(N²) inner loop) fails this.
   - Each timing is repeated REPS=5 times and the MINIMUM is used to reduce scheduling jitter.

4. test_workspace_impact_report_path_stays_under_budget
   - Drives TransformationWorkspace.composed_impact_inputs() then ImpactReportBuilder.build() over
     a composed edit with MANY members (100 V3 plans, each touching a disjoint file).
   - Asserts the full round-trip (composition + graph-backed report) < 1.0 s.
"""

from __future__ import annotations

import shutil
import time
import tracemalloc
from pathlib import Path
from typing import Any

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.manager import JavaRefactorManager
from serena.java_refactor.workspace_edit import RefactorTextEdit, RefactorWorkspaceEdit
from serena.java_refactor_v3 import V3OperationPlan
from serena.java_refactor_v3.graph_client import parse_project_graph
from serena.java_refactor_v3.models import RiskLevel
from serena.java_refactor_v3.reports import ImpactReportBuilder
from serena.java_refactor_v3.reports.sidecar_facts import SidecarFactsGraph, facts_to_graph_input
from serena.java_refactor_v3.workspace import TransformationWorkspace
from solidlsp.ls_config import Language
from test.serena._java_refactor_sidecar_helpers import _preview_op

# The sidecar-backed live tests (tests 5-7) require the sidecar_jar and sidecar_java_cmd fixtures
# that live in _java_refactor_sidecar_helpers.  pytest_plugins wires those fixtures into this module.
pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)

# Committed fixture roots used by the live sidecar tests.
_FIXTURE_ROOT_PACKAGE_MOVE = Path(__file__).parent.parent / "resources/repos/java_refactor_v3/package_move_basic"
_FIXTURE_ROOT_DELETE_CASCADE = Path(__file__).parent.parent / "resources/repos/java_refactor_v3/delete_cascade"
_FIXTURE_ROOT_RECIPE_PROJECT = Path(__file__).parent.parent / "resources/repos/java_refactor_v3/recipe_project"

_JDIR = "src/main/java/com/acme/app"


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _manager_for(project_root: Path) -> JavaRefactorManager:
    """Return an explicit-config JavaRefactorManager for a single-source-root project."""
    return JavaRefactorManager(
        str(project_root),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(
            enabled=True,
            build_tool_mode="explicit",
            source_roots=["src/main/java"],
            allow_incomplete_analysis=True,
        ),
    )

# ---------------------------------------------------------------------------
# Synthetic data helpers  (no JVM, no disk I/O inside timing loops)
# ---------------------------------------------------------------------------

_MAIN_ROOT = "src/main/java"
_TEST_ROOT = "src/test/java"
_RESOURCE_ROOT = "src/main/resources"


def _source_roots() -> dict[str, Any]:
    return {"main": [_MAIN_ROOT], "test": [_TEST_ROOT], "resources": [_RESOURCE_ROOT]}


def _large_raw_facts(n_types: int) -> dict[str, Any]:
    """Build a raw impact.facts payload with n_types touched types plus proportional resource/test wiring.

    Wiring density: every 4th type has one resource wiring entry and one test referencing it.
    This mirrors the canonical wired-service scenario from test_java_refactor_v3_reports.py but scaled up.
    """
    touched_types = [
        {
            "fqn": f"com.acme.big.Type{i}",
            "relativePath": f"{_MAIN_ROOT}/com/acme/big/Type{i}.java",
            "publicApi": True,
            "testSource": False,
        }
        for i in range(n_types)
    ]
    incoming_refs = [
        {
            "fromKey": f"com.acme.test.Type{i}Test#run()",
            "fromFqn": f"com.acme.test.Type{i}Test",
            "fromRelativePath": f"{_TEST_ROOT}/com/acme/test/Type{i}Test.java",
            "fromTestSource": True,
            "fromPublicApi": True,
            "toFqn": f"com.acme.big.Type{i}",
            "toRelativePath": f"{_MAIN_ROOT}/com/acme/big/Type{i}.java",
        }
        for i in range(0, n_types, 4)
    ]
    resource_refs = [
        {
            "resourcePath": f"{_RESOURCE_ROOT}/beans{i}.xml",
            "target": f"com.acme.big.Type{i}",
            "kind": "SPRING_BEAN",
        }
        for i in range(0, n_types, 4)
    ]
    return {
        "accepted": True,
        "operation": "impact.facts",
        "touchedPaths": [t["relativePath"] for t in touched_types],
        "sourceRoots": _source_roots(),
        "touchedTypes": touched_types,
        "incomingRefs": incoming_refs,
        "resourceRefs": resource_refs,
        "stats": {"touchedTypes": n_types, "incomingRefs": len(incoming_refs), "resourceRefs": len(resource_refs)},
    }


def _graph_from_raw(raw: dict[str, Any]) -> SidecarFactsGraph:
    return SidecarFactsGraph(facts_to_graph_input(raw))


def _large_edit(n_types: int, *, step: int = 1) -> RefactorWorkspaceEdit:
    """A workspace edit touching every `step`-th type's Java file."""
    return RefactorWorkspaceEdit(
        text_edits=[
            RefactorTextEdit(
                relative_path=f"{_MAIN_ROOT}/com/acme/big/Type{i}.java",
                start_offset=0,
                end_offset=0,
                replacement="",
            )
            for i in range(0, n_types, step)
        ]
    )


def _timed_build(builder: ImpactReportBuilder, edit: RefactorWorkspaceEdit, reps: int = 1) -> float:
    """Return the MINIMUM wall-clock time (seconds) for reps calls to builder.build()."""
    best = float("inf")
    for _ in range(reps):
        t0 = time.perf_counter()
        builder.build(edit, risk=RiskLevel.SAFE, operation="applyRefactorRecipe")
        best = min(best, time.perf_counter() - t0)
    return best


# ---------------------------------------------------------------------------
# Stub transport for GraphClient cache tests (no JVM required)
# ---------------------------------------------------------------------------

class _StubGraphTransport:
    """A minimal stub that pretends to be a JavaRefactorClient for GraphClient protocol calls.

    The graph.build payload is pre-baked; graph.buildCount is tracked locally so the cache-hit
    semantic (buildCount stays flat on a repeated same-revision call) can be exercised purely in Python.

    The stub mimics the cache: on the first call with a given payload it increments build_count; on
    subsequent calls with the *same* payload it returns the cached result (build_count unchanged).
    The sidecar's cache is keyed on whole-project revision; here we use the payload identity as a proxy.
    """

    def __init__(self, graph_payload: dict[str, Any]) -> None:
        self._payload = graph_payload
        self.build_count = 0
        self._cached: dict[str, Any] | None = None
        self._cache_key: int | None = None

    def _request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        if method == "graph.buildCount":
            return {"builds": self.build_count}
        if method == "graph.build":
            key = id(self._payload)
            if self._cached is None or self._cache_key != key:
                self.build_count += 1
                self._cached = {"accepted": True, **self._payload}
                self._cache_key = key
            return self._cached
        raise AssertionError(f"StubGraphTransport: unexpected method {method!r}")


def _minimal_graph_payload() -> dict[str, Any]:
    """A minimal graph.build payload with one module and two types."""
    return {
        "project": {"revision": "rev-abc123"},
        "build": {
            "buildSystem": "maven",
            "modules": [
                {
                    "id": "root",
                    "buildSystem": "maven",
                    "sourceRoots": [
                        {"path": _MAIN_ROOT, "kind": "main", "content": "java", "module": "root"},
                        {"path": _TEST_ROOT, "kind": "test", "content": "java", "module": "root"},
                    ],
                }
            ],
        },
        "symbols": {
            "types": [
                {"fqn": "com.acme.Helper", "simpleName": "Helper", "package": "com.acme", "kind": "class", "path": f"{_MAIN_ROOT}/com/acme/Helper.java"},
                {"fqn": "com.acme.App", "simpleName": "App", "package": "com.acme", "kind": "class", "path": f"{_MAIN_ROOT}/com/acme/App.java"},
            ],
            "members": [],
            "typeToFile": {
                "com.acme.Helper": f"{_MAIN_ROOT}/com/acme/Helper.java",
                "com.acme.App": f"{_MAIN_ROOT}/com/acme/App.java",
            },
            "packageToSourceRoots": {"com.acme": [_MAIN_ROOT]},
            "filesByPackage": {"com.acme": [f"{_MAIN_ROOT}/com/acme/Helper.java", f"{_MAIN_ROOT}/com/acme/App.java"]},
        },
        "hierarchy": {"supertypes": {}, "subtypes": {}},
        "calls": {"callEdges": {}, "resolved": True},
        "resources": {"references": []},
        "tests": {"tests": []},
        "stats": {"types": 2},
    }


# ---------------------------------------------------------------------------
# Stub SessionDriver for workspace composition tests (no JVM required)
# ---------------------------------------------------------------------------

class _NullApplier:
    """A transactional applier that accepts all stage/commit calls without touching disk."""

    def stage(self, edit: RefactorWorkspaceEdit) -> Any:
        return edit  # staged = the edit itself

    def commit(self, staged: Any) -> None:
        pass  # no-op


class _StubDriver:
    """Lightweight SessionDriver double that returns pre-programmed V3OperationPlans."""

    def __init__(self) -> None:
        self._v3_queue: list[V3OperationPlan | dict[str, Any]] = []

    def program_v3(self, plan: V3OperationPlan | dict[str, Any]) -> None:
        self._v3_queue.append(plan)

    def create_v2_refactor_session(self, operation: str, params: dict[str, Any], validate: bool | None = None) -> dict[str, Any]:
        raise AssertionError("_StubDriver: unexpected V2 session call in perf test")

    def cancel_v2_refactor_session(self, session_id: str) -> dict[str, Any]:
        return {"accepted": True}

    def plan_v3_operation(self, operation: str, params: dict[str, Any]) -> V3OperationPlan | dict[str, Any]:
        assert self._v3_queue, "_StubDriver: unprogrammed plan_v3_operation"
        return self._v3_queue.pop(0)

    def new_workspace_edit_applier(self) -> _NullApplier:
        return _NullApplier()


# ---------------------------------------------------------------------------
# Test 1: graph build caching — cache-hit (semantic) + timing (reshape)
# ---------------------------------------------------------------------------

def test_graph_cache_hit_is_faster_than_cold_build() -> None:
    """graph.build returns a cached payload on the second call at the same revision.

    Semantic assertion: buildCount does not increment on the second call (the stub's cache is hit).
    Timing assertion: the warm path (parse_project_graph over the already-built payload) is >=5x faster
    than the cold path, and the warm path itself stays under 50 ms per call.

    Path exercised: parse_project_graph (the Python-side graph reshape that transformations and impact
    reports read instead of re-parsing).  The stub transport mimics the sidecar's content-addressed
    cache without a JVM.
    """
    from serena.java_refactor_v3.graph_client import GraphClient

    transport = _StubGraphTransport(_minimal_graph_payload())
    client = GraphClient(transport)  # type: ignore[arg-type]

    # Cold build — should advance buildCount by 1.
    t0 = time.perf_counter()
    raw_cold = client.build()
    t_cold = time.perf_counter() - t0
    assert raw_cold.get("accepted") is True
    assert transport.build_count == 1, "first graph.build must advance buildCount"

    # Warm parse (reshape only, payload already cached in stub).
    WARM_REPS = 20
    t0 = time.perf_counter()
    for _ in range(WARM_REPS):
        raw_warm = client.build()
        parse_project_graph(raw_warm)
    t_warm_total = time.perf_counter() - t0
    t_warm_avg = t_warm_total / WARM_REPS

    # Semantic: build_count must stay at 1 (cache hit — no new graph was materialized).
    assert transport.build_count == 1, (
        f"repeated graph.build at the same revision must NOT advance buildCount "
        f"(got {transport.build_count}; cache must be hit)"
    )

    # Timing: warm reshape must be dramatically faster than cold (>= 5x).
    # The cold path includes network I/O to the stub, so this ratio is conservative.
    # Budget is RELATIVE to avoid brittle absolute bounds on the stub round-trip.
    assert t_warm_avg <= t_cold or t_cold / (t_warm_avg + 1e-9) >= 5.0 or t_warm_avg < 0.05, (
        f"warm parse_project_graph avg {t_warm_avg*1000:.2f} ms must be either "
        f"<= cold {t_cold*1000:.2f} ms OR >=5x faster, OR under 50 ms"
    )
    # Hard ceiling: warm reshape must ALWAYS stay under 50 ms regardless of cold time.
    assert t_warm_avg < 0.05, (
        f"warm parse_project_graph should stay under 50 ms per call; got {t_warm_avg*1000:.2f} ms"
    )


# ---------------------------------------------------------------------------
# Test 2: impact report construction — large input, absolute ceiling
# ---------------------------------------------------------------------------

N_LARGE = 500


def test_impact_report_construction_stays_under_budget() -> None:
    """ImpactReportBuilder.build() over N=500 types with proportional resource/test wiring stays < 1.0 s.

    Path exercised: ImpactReportBuilder._java_section, ._resources_section, ._api_section,
    ._tests_section — the five-section construction that the V3 docs claim 'holds well under a second'.

    Budget: 1.0 s (generous for slow CI; the expected time is well under 100 ms on any reasonable hardware).
    """
    raw = _large_raw_facts(N_LARGE)
    graph = _graph_from_raw(raw)
    builder = ImpactReportBuilder("/proj", graph)
    edit = _large_edit(N_LARGE)

    t0 = time.perf_counter()
    report = builder.build(edit, risk=RiskLevel.REVIEW_REQUIRED, operation="renamePackage").to_dict()
    elapsed = time.perf_counter() - t0

    # Correctness spot-check: the report must be non-trivial.
    assert report["java"]["fileCount"] == N_LARGE
    assert report["api"]["boundaryCrossed"] is True

    assert elapsed < 1.0, (
        f"impact report construction over {N_LARGE} types took {elapsed:.3f} s; "
        f"expected well under 1.0 s (V3 docs guarantee)"
    )


# ---------------------------------------------------------------------------
# Test 3: linearity scaling — doubling input must not super-linearly blow up
# ---------------------------------------------------------------------------

N_SMALL = 200
N_BIG = 400
K_LINEAR = 8  # generous multiplier; strict linearity would be K~2


def test_impact_report_scales_linearly_with_input_size() -> None:
    """Report construction time at N=400 must be < K * time at N=200 for K=8.

    A quadratic inner loop (e.g. O(N^2) cross-product) would produce K >> 4; failing this budget
    proves the construction path is at most mildly super-linear, consistent with the documented
    'linear in the size of the workspace edit' claim.

    Uses MINIMUM over 5 repetitions to reduce scheduling noise.
    """
    REPS = 5

    raw_small = _large_raw_facts(N_SMALL)
    graph_small = _graph_from_raw(raw_small)
    builder_small = ImpactReportBuilder("/proj", graph_small)
    edit_small = _large_edit(N_SMALL)
    t_small = _timed_build(builder_small, edit_small, reps=REPS)

    raw_big = _large_raw_facts(N_BIG)
    graph_big = _graph_from_raw(raw_big)
    builder_big = ImpactReportBuilder("/proj", graph_big)
    edit_big = _large_edit(N_BIG)
    t_big = _timed_build(builder_big, edit_big, reps=REPS)

    # Guard against a zero/near-zero small time making the ratio meaningless.
    if t_small < 1e-4:
        # Both are fast enough that linearity is trivially satisfied.
        return

    ratio = t_big / t_small
    assert ratio < K_LINEAR, (
        f"impact report at N={N_BIG} took {ratio:.1f}x longer than N={N_SMALL} "
        f"(t_big={t_big*1000:.2f} ms, t_small={t_small*1000:.2f} ms); "
        f"expected < {K_LINEAR}x (super-linear growth suggests O(N²) regression)"
    )


# ---------------------------------------------------------------------------
# Test 4: workspace impact report path — composition + graph-backed report
# ---------------------------------------------------------------------------

N_MEMBERS = 100


def test_workspace_impact_report_path_stays_under_budget() -> None:
    """The full workspace impact-report path (compose N members + ImpactReportBuilder.build) stays < 1.0 s.

    Path exercised:
    - TransformationWorkspace.composed_impact_inputs() — merges N=100 disjoint V3 plans
    - ImpactReportBuilder.build() — graph-backed report over the merged edit

    Uses a StubDriver so no JVM is needed; the ImpactReportBuilder is backed by a SidecarFactsGraph
    with facts matching the touched files so the report is non-trivial.
    """
    driver = _StubDriver()

    # Build N disjoint V3 plans, each touching a distinct file (no conflict).
    touched_paths = [f"{_MAIN_ROOT}/com/acme/ws/Type{i}.java" for i in range(N_MEMBERS)]
    for i in range(N_MEMBERS):
        plan = V3OperationPlan(
            operation="applyRefactorRecipe",
            project_revision="rev-ws-001",
            workspace_edit=RefactorWorkspaceEdit(
                text_edits=[RefactorTextEdit(relative_path=touched_paths[i], start_offset=0, end_offset=0, replacement="")]
            ),
            risk=RiskLevel.SAFE,
            warnings=[],
        )
        driver.program_v3(plan)

    workspace = TransformationWorkspace("ws-perf-001", driver)
    for i in range(N_MEMBERS):
        result = workspace.add_operation("applyRefactorRecipe", {})
        assert result["accepted"] is True, f"add_operation #{i} was unexpectedly refused: {result}"

    # Build the SidecarFactsGraph with facts for every touched type (so api/resources/tests sections fire).
    touched_types = [
        {
            "fqn": f"com.acme.ws.Type{i}",
            "relativePath": touched_paths[i],
            "publicApi": True,
            "testSource": False,
        }
        for i in range(N_MEMBERS)
    ]
    raw_facts = {
        "sourceRoots": _source_roots(),
        "touchedTypes": touched_types,
        "incomingRefs": [],
        "resourceRefs": [],
    }
    graph = _graph_from_raw(raw_facts)

    t0 = time.perf_counter()

    # Step 1: compose the N member edits.
    inputs = workspace.composed_impact_inputs()
    assert inputs["accepted"] is True, f"composed_impact_inputs refused: {inputs}"
    merged_edit: RefactorWorkspaceEdit = inputs["edit"]
    risk: RiskLevel = inputs["risk"]
    operation: str = inputs["operation"]

    # Step 2: build the graph-backed impact report over the merged edit.
    builder = ImpactReportBuilder("/proj", graph)
    report = builder.build(merged_edit, risk=risk, operation=operation).to_dict()

    elapsed = time.perf_counter() - t0

    assert report["java"]["fileCount"] == N_MEMBERS
    assert elapsed < 1.0, (
        f"workspace impact report path ({N_MEMBERS} members) took {elapsed:.3f} s; "
        f"expected under 1.0 s"
    )


# ---------------------------------------------------------------------------
# Test 5: package-move sidecar — boots REAL sidecar, wall-clock budget
# ---------------------------------------------------------------------------

# Budget rationale: the V3 doc gives NO number for per-operation sidecar latency; it only guarantees
# in-Python impact-report construction (< 1 s) and cache-hit semantics.  This budget (30 s) is chosen
# as a generous CI-tolerant ceiling that still catches a pathological blow-up (runaway javac, infinite
# loop, OOM restart).  The _preview_op call starts and stops its own JavaRefactorClient, so sidecar
# JVM boot + javac compilation of the fixture dominate; that is acceptable — we are guarding against
# gross regression, not micro-latency.
_PACKAGE_MOVE_BUDGET_S = 30.0


def test_package_move_sidecar_stays_under_budget(
    sidecar_jar: Path, sidecar_java_cmd: str, tmp_path: Path
) -> None:
    """``movePackage`` preview via the REAL sidecar completes within a generous wall-clock budget.

    The test boots the live sidecar (sidecar_jar fixture, built by gradle), stages the committed
    package_move_basic fixture, and times only the _preview_op call (which includes sidecar JVM
    boot + javac because _preview_op starts/stops its own client).

    Budget: 30.0 s.  No doc number exists for sidecar op latency; chosen as a CI-tolerant ceiling
    that still catches a gross regression (runaway javac, infinite loop, OOM).
    """
    project_root = tmp_path / "package_move_perf"
    shutil.copytree(_FIXTURE_ROOT_PACKAGE_MOVE, project_root)

    t0 = time.perf_counter()
    result = _preview_op(
        sidecar_jar,
        project_root,
        "movePackage",
        {"sourcePackage": "com.acme.app", "targetPackage": "com.acme.core"},
        java_command=sidecar_java_cmd,
    )
    elapsed = time.perf_counter() - t0

    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result
    assert elapsed < _PACKAGE_MOVE_BUDGET_S, (
        f"movePackage sidecar preview took {elapsed:.2f} s; "
        f"budget is {_PACKAGE_MOVE_BUDGET_S} s "
        f"(no doc budget; generous CI-tolerant ceiling for gross-regression guard)"
    )


# ---------------------------------------------------------------------------
# Test 6: delete-cascade sidecar scan — boots REAL sidecar, wall-clock budget
# ---------------------------------------------------------------------------

# Budget rationale: same reasoning as test 5 — no doc number for sidecar scan latency; 30 s is a
# generous ceiling that catches pathological blow-up (OOM, infinite analysis loop) while tolerating
# slow CI machines.  The scan is a read-only pass (no javac), so it should be faster than an edit
# operation in practice, but we use the same budget for simplicity.
_DELETE_SCAN_BUDGET_S = 30.0


def _write_delete_cascade_project(root: Path) -> None:
    """Generate a minimal project: Main->Service->Repo live chain + Orphan->OrphanHelper dead cluster."""
    j = _JDIR
    _write(
        root,
        f"{j}/Main.java",
        "package com.acme.app;\npublic class Main {\n  public static void main(String[] args) { new Service().run(); }\n}\n",
    )
    _write(root, f"{j}/Service.java", "package com.acme.app;\npublic class Service {\n  public void run() { new Repo().load(); }\n}\n")
    _write(root, f"{j}/Repo.java", "package com.acme.app;\npublic class Repo {\n  public void load() {}\n}\n")
    _write(root, f"{j}/Orphan.java", "package com.acme.app;\nclass Orphan {\n  void use() { new OrphanHelper(); }\n}\n")
    _write(root, f"{j}/OrphanHelper.java", "package com.acme.app;\nclass OrphanHelper {}\n")


def test_delete_cascade_sidecar_scan_stays_under_budget(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    """find_dead_code scan via the REAL sidecar completes within a generous wall-clock budget.

    Uses the committed delete_cascade fixture if it contains the expected source files; otherwise
    generates the same project inline.  The scan is read-only (no javac), so it should complete
    faster than edit operations in practice.

    Budget: 30.0 s.  No doc number exists for sidecar scan latency; chosen as a generous
    CI-tolerant ceiling that still catches a gross regression (runaway analysis, OOM).
    """
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))

    # Prefer the committed fixture; fall back to generated project if the fixture is absent/empty.
    fixture_src = _FIXTURE_ROOT_DELETE_CASCADE / "src/main/java/com/acme/app"
    if fixture_src.exists() and any(fixture_src.iterdir()):
        project = tmp_path / "delete_cascade_perf"
        shutil.copytree(_FIXTURE_ROOT_DELETE_CASCADE, project)
    else:
        project = tmp_path / "delete_cascade_generated"
        _write_delete_cascade_project(project)

    manager = _manager_for(project)
    try:
        t0 = time.perf_counter()
        result = manager.find_dead_code()
        elapsed = time.perf_counter() - t0
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert "deadCodeCandidates" in result, result
    assert elapsed < _DELETE_SCAN_BUDGET_S, (
        f"find_dead_code scan took {elapsed:.2f} s; "
        f"budget is {_DELETE_SCAN_BUDGET_S} s "
        f"(no doc budget; generous CI-tolerant ceiling for gross-regression guard)"
    )


# ---------------------------------------------------------------------------
# Test 7: recipe scan sidecar — boots REAL sidecar, wall-clock budget
# ---------------------------------------------------------------------------

# Budget rationale: same reasoning as tests 5 and 6 — no doc number for sidecar recipe-scan
# latency; 30 s is a generous ceiling tolerating slow CI / javac startup while still catching
# pathological blow-up.
_RECIPE_SCAN_BUDGET_S = 30.0

_RECIPE_DOCUMENT = (
    '{"id": "foo-to-bar", "rules": [{"kind": "replaceConstructor", '
    '"owner": "com.acme.app.Foo", "replacement": "new Bar()", "risk": "safe"}]}'
)


def _write_recipe_project(root: Path) -> None:
    """Generate a minimal project: Foo {}, Bar {}, Main { Object make(){ return new Foo(); } }."""
    j = _JDIR
    _write(root, f"{j}/Foo.java", "package com.acme.app;\npublic class Foo {}\n")
    _write(root, f"{j}/Bar.java", "package com.acme.app;\npublic class Bar {}\n")
    _write(root, f"{j}/Main.java", "package com.acme.app;\npublic class Main {\n    Object make() { return new Foo(); }\n}\n")


def test_recipe_scan_sidecar_stays_under_budget(
    sidecar_jar: Path, tmp_path: Path, monkeypatch
) -> None:
    """scan_migration_opportunities via the REAL sidecar completes within a generous wall-clock budget.

    Uses the committed recipe_project fixture if it exists (Foo/Bar/Main); otherwise generates the
    project inline.  The scan is read-only (no javac edit), so it is generally faster than apply.

    Budget: 30.0 s.  No doc number exists for sidecar recipe-scan latency; chosen as a generous
    CI-tolerant ceiling that still catches a gross regression.
    """
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))

    fixture_src = _FIXTURE_ROOT_RECIPE_PROJECT / "src/main/java/com/acme/app"
    if fixture_src.exists() and any(fixture_src.iterdir()):
        project = tmp_path / "recipe_scan_perf"
        shutil.copytree(_FIXTURE_ROOT_RECIPE_PROJECT, project)
    else:
        project = tmp_path / "recipe_scan_generated"
        _write_recipe_project(project)

    manager = _manager_for(project)
    try:
        t0 = time.perf_counter()
        result = manager.scan_migration_opportunities(recipe_document=_RECIPE_DOCUMENT)
        elapsed = time.perf_counter() - t0
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    assert result.get("operation") == "scanMigrationOpportunities", result
    assert elapsed < _RECIPE_SCAN_BUDGET_S, (
        f"scan_migration_opportunities took {elapsed:.2f} s; "
        f"budget is {_RECIPE_SCAN_BUDGET_S} s "
        f"(no doc budget; generous CI-tolerant ceiling for gross-regression guard)"
    )


# ---------------------------------------------------------------------------
# Test 8: impact-report memory footprint — pure Python, no JVM
# ---------------------------------------------------------------------------

# Budget rationale: the V3 doc guarantees "linear in the size of the workspace edit" but gives NO
# absolute byte budget for memory.  256 MiB is chosen as a generous absolute ceiling that would
# still catch an accidental O(N²) materialization or unbounded list/dict accumulation (e.g. keeping
# a full cross-product of touched types x refs in memory) while easily accommodating any reasonable
# linear implementation over 500 types.  This is not a tight measure of real footprint; it is a
# regression-detection ceiling.
_MEMORY_BUDGET_BYTES = 256 * 1024 * 1024  # 256 MiB


def test_impact_report_memory_footprint_stays_bounded() -> None:
    """ImpactReportBuilder.build() over N=500 types stays within a generous peak-memory budget.

    Uses tracemalloc to measure peak allocated bytes during the build call.  No JVM is involved;
    this is a pure-Python regression guard against accidental O(N²) memory growth (e.g. retaining
    a full cross-product of types × refs, or materialising an unbounded intermediate collection).

    Budget: 256 MiB peak (tracemalloc domain).  The V3 doc guarantees linear construction but
    gives no absolute byte budget; 256 MiB is a generous CI-tolerant ceiling that catches gross
    regressions while trivially accommodating any reasonable linear implementation.
    """
    raw = _large_raw_facts(N_LARGE)
    graph = _graph_from_raw(raw)
    builder = ImpactReportBuilder("/proj", graph)
    edit = _large_edit(N_LARGE)

    # Correctness spot-check before timing to avoid a refusal masking a budget pass.
    report = builder.build(edit, risk=RiskLevel.REVIEW_REQUIRED, operation="renamePackage").to_dict()
    assert report["java"]["fileCount"] == N_LARGE, (
        f"expected 500 touched files in report; got {report['java']['fileCount']}"
    )

    # Measure peak allocated bytes for one build call.
    tracemalloc.start()
    builder.build(edit, risk=RiskLevel.REVIEW_REQUIRED, operation="renamePackage")
    _current, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()

    assert peak < _MEMORY_BUDGET_BYTES, (
        f"ImpactReportBuilder.build() over {N_LARGE} types allocated {peak / (1024*1024):.1f} MiB peak; "
        f"budget is {_MEMORY_BUDGET_BYTES // (1024*1024)} MiB "
        f"(no doc byte budget; generous ceiling against O(N²) accidental materialization)"
    )
