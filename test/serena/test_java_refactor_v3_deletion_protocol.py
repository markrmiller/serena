"""Live-sidecar coverage for the V3 ``deletion.*`` protocol (refactor-feature-plan-V3.md §7).

These boot the real Java sidecar jar and drive ``deletion.propagateSafeDelete`` / ``deletion.findDeadCode`` end to end
via :class:`~serena.java_refactor_v3.deletion_client.DeletionClient`. They prove the cascade and dead-code reachability
are computed by javac inside the sidecar (not by a Python heuristic), that the propagating delete returns the
graph-shaped ``deletePlan`` plus a javac-validated removing ``workspaceEdit``, and that the dead-code scan only ever
produces candidates.

Capabilities exercised (mapping to the team-lead's Phase 3 checklist):
    (a) test_cascade_pulls_in_private_helper      — real member-level cascade (§7.1/§7.3)
    (b) test_blocked_when_live_referrer_remains    — blocked symbol with the live referrer named (§7.2/§7.4)
    (c) test_service_loader_provider_line_removed  — META-INF/services provider rewrite for a deleted impl (§7.3)
    (d) test_find_dead_code_high_and_low / mutates_nothing — java_find_dead_code high+low candidates (§7.5)
"""

from __future__ import annotations

import contextlib
import json
import os
from collections.abc import Iterator
from pathlib import Path

import pytest

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from serena.java_refactor_v3.deletion_client import DeletionClient

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


@contextlib.contextmanager
def _deletion(
    sidecar_jar: Path, project_root: Path, java_command: str = "java", configuration: str = "default"
) -> Iterator[DeletionClient]:
    client = JavaRefactorClient(sidecar_jar, java_command=java_command)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        yield DeletionClient(client)
    finally:
        client.shutdown()


def _symbols(entries: list[dict]) -> set[str]:
    return {entry["symbol"] for entry in entries}


def _reason_for(entries: list[dict], symbol: str) -> str:
    for entry in entries:
        if entry["symbol"] == symbol:
            return entry["reason"]
    raise AssertionError(f"{symbol} not found in {entries}")


# ── (a) real cascade ─────────────────────────────────────────────────────────────────────────────────────────────


def test_cascade_pulls_in_private_helper(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # legacyCompute (package-private) is the requested root; helper (private) is used ONLY by legacyCompute, so deleting
    # the root cascades into helper. run() is a public API root that keeps Service alive and must be untouched.
    _write(
        tmp_path,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\n"
        "public class Service {\n"
        "    public int run() { return 1; }\n"
        "    int legacyCompute() { return helper() + 1; }\n"
        "    private int helper() { return 41; }\n"
        "}\n",
    )
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.propagate_safe_delete(["com.acme.app.Service#legacyCompute()"])

    assert result.get("accepted") is True, result
    plan = result["deletePlan"]
    assert "com.acme.app.Service#legacyCompute()" in plan["requested"], plan
    cascade_symbols = _symbols(plan["cascade"])
    assert "com.acme.app.Service#helper()" in cascade_symbols, plan
    assert "Only referenced by deleted" in _reason_for(plan["cascade"], "com.acme.app.Service#helper()")
    assert plan["blocked"] == [], plan
    # The composed removal really compiled (no dangling reference left behind).
    assert result.get("diagnosticDeltaValidated") is True, result
    changed_files = {change["path"] for change in result["workspaceEdit"]["changes"]}
    assert any(path.endswith("Service.java") for path in changed_files), result


# ── (b) blocked by a live referrer ───────────────────────────────────────────────────────────────────────────────


def test_blocked_when_live_referrer_remains(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # helper2 is referenced by BOTH the deleted legacyCompute and the public publicApi(); it must stay and be reported
    # blocked, naming the public referrer — never silently cascaded.
    _write(
        tmp_path,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\n"
        "public class Service {\n"
        "    public int publicApi() { return helper2(); }\n"
        "    int legacyCompute() { return helper2() + 1; }\n"
        "    private int helper2() { return 7; }\n"
        "}\n",
    )
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.propagate_safe_delete(["com.acme.app.Service#legacyCompute()"])

    assert result.get("accepted") is True, result
    plan = result["deletePlan"]
    assert "com.acme.app.Service#legacyCompute()" in plan["requested"], plan
    assert _symbols(plan["cascade"]) == set(), plan
    blocked_symbols = _symbols(plan["blocked"])
    assert "com.acme.app.Service#helper2()" in blocked_symbols, plan
    reason = _reason_for(plan["blocked"], "com.acme.app.Service#helper2()")
    assert "Referenced by public method com.acme.app.Service#publicApi()" in reason, reason
    assert result.get("diagnosticDeltaValidated") is True, result


# ── (c) service-loader provider line removal ─────────────────────────────────────────────────────────────────────


def _service_loader_project(root: Path) -> None:
    _write(
        root,
        "src/main/java/com/acme/spi/Greeter.java",
        "package com.acme.spi;\npublic interface Greeter {\n    String greet();\n}\n",
    )
    _write(
        root,
        "src/main/java/com/acme/spi/ActiveGreeter.java",
        "package com.acme.spi;\npublic class ActiveGreeter implements Greeter {\n"
        "    public String greet() { return \"hi\"; }\n}\n",
    )
    _write(
        root,
        "src/main/java/com/acme/spi/LegacyGreeter.java",
        "package com.acme.spi;\nclass LegacyGreeter implements Greeter {\n"
        "    public String greet() { return \"legacy\"; }\n}\n",
    )
    _write(
        root,
        "src/main/resources/META-INF/services/com.acme.spi.Greeter",
        "com.acme.spi.ActiveGreeter\ncom.acme.spi.LegacyGreeter\n",
    )


def test_service_loader_provider_line_removed(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _service_loader_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.propagate_safe_delete(["com.acme.spi.LegacyGreeter"])

    assert result.get("accepted") is True, result
    plan = result["deletePlan"]
    assert "com.acme.spi.LegacyGreeter" in plan["requested"], plan

    edit = result["workspaceEdit"]
    deleted_files = [op["path"] for op in edit["fileOperations"] if op["kind"] == "delete"]
    assert any(path.endswith("LegacyGreeter.java") for path in deleted_files), result

    service_changes = [c for c in edit["changes"] if c["path"].endswith("META-INF/services/com.acme.spi.Greeter")]
    assert service_changes, result
    # The provider line for the deleted impl is excised; the surviving ActiveGreeter line is left alone.
    assert all(e["newText"] == "" for e in service_changes[0]["edits"]), service_changes
    assert result.get("diagnosticDeltaValidated") is True, result


# ── (d) find_dead_code: high + low candidates, no mutation ────────────────────────────────────────────────────────


def _dead_code_project(root: Path) -> None:
    # The REAL Spring @RequestMapping FQN — framework-entry detection resolves annotations by exact compiler FQN (never a
    # simple-name heuristic), so a lookalike annotation in another package would NOT count; declaring it here at its true
    # FQN is what makes handle() a genuine framework entry point.
    _write(
        root,
        "src/main/java/org/springframework/web/bind/annotation/RequestMapping.java",
        "package org.springframework.web.bind.annotation;\n"
        "import java.lang.annotation.*;\n"
        "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)\n"
        "public @interface RequestMapping {}\n",
    )
    # Endpoint is a public root; handle() is package-private with no Java callers but is @RequestMapping-annotated, so it
    # is a LOW-confidence candidate (a framework may still invoke it reflectively).
    _write(
        root,
        "src/main/java/com/acme/web/Endpoint.java",
        "package com.acme.web;\n"
        "import org.springframework.web.bind.annotation.RequestMapping;\n"
        "public class Endpoint {\n"
        "    @RequestMapping\n"
        "    int handle() { return 0; }\n"
        "}\n",
    )
    # Unused is a package-private class nothing references — a HIGH-confidence candidate.
    _write(
        root,
        "src/main/java/com/acme/web/Unused.java",
        "package com.acme.web;\nclass Unused {\n}\n",
    )


def test_find_dead_code_high_and_low(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _dead_code_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.find_dead_code()

    assert result.get("accepted") is True, result
    candidates = {c["symbol"]: c for c in result["deadCodeCandidates"]}

    assert "com.acme.web.Unused" in candidates, result
    assert candidates["com.acme.web.Unused"]["confidence"] == "high", candidates["com.acme.web.Unused"]

    assert "com.acme.web.Endpoint#handle()" in candidates, result
    handle = candidates["com.acme.web.Endpoint#handle()"]
    assert handle["confidence"] == "low", handle
    assert "RequestMapping" in handle["reason"], handle

    # Public API (the annotation type, the Endpoint class) is kept under the default policy.
    assert "com.acme.web.Endpoint" not in candidates, candidates
    assert "com.acme.web.RequestMapping" not in candidates, candidates


def test_find_dead_code_mutates_nothing(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _dead_code_project(tmp_path)
    before = {p.read_text(encoding="utf-8") for p in tmp_path.rglob("*.java")}
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.find_dead_code()

    assert result.get("accepted") is True, result
    # A dead-code scan is purely analytical: it never produces an edit and never touches the working tree.
    assert "workspaceEdit" not in result, result
    after = {p.read_text(encoding="utf-8") for p in tmp_path.rglob("*.java")}
    assert before == after, "find_dead_code must not modify any source file"


# ── (e) find_dead_code scope: package-subtree restriction is real, not a silent no-op ──────────────────────────────


def _two_package_dead_project(root: Path) -> None:
    # Two unrelated, package-private dead classes in disjoint packages. Both are HIGH-confidence candidates project-wide;
    # a scope must keep only the one whose package is in the subtree.
    _write(root, "src/main/java/com/acme/web/UnusedWeb.java", "package com.acme.web;\nclass UnusedWeb {}\n")
    _write(root, "src/main/java/com/acme/data/UnusedData.java", "package com.acme.data;\nclass UnusedData {}\n")


def test_find_dead_code_scope_restricts_to_package_subtree(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    _two_package_dead_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        unscoped = deletion.find_dead_code()
        scoped = deletion.find_dead_code(scope="com.acme.web")

    assert unscoped.get("accepted") is True, unscoped
    unscoped_symbols = _symbols(unscoped["deadCodeCandidates"])
    # Baseline: without a scope BOTH dead classes are flagged, so the scoped run below proves filtering, not absence.
    assert "com.acme.web.UnusedWeb" in unscoped_symbols, unscoped
    assert "com.acme.data.UnusedData" in unscoped_symbols, unscoped

    assert scoped.get("accepted") is True, scoped
    scoped_symbols = _symbols(scoped["deadCodeCandidates"])
    # The scope is honoured in the sidecar: the in-subtree dead class survives, the out-of-subtree one is filtered out.
    assert "com.acme.web.UnusedWeb" in scoped_symbols, scoped
    assert "com.acme.data.UnusedData" not in scoped_symbols, scoped


# ── (f) find_dead_code public-API policy: report flips public symbols on, not a silent no-op ────────────────────────


def _public_and_private_dead_project(root: Path) -> None:
    # A public type nothing references (kept under "keep", reportable under "report") alongside a package-private dead
    # type (a candidate under BOTH policies) — so the report run proves the policy *adds* the public one rather than
    # being the only thing that produces any output at all.
    _write(root, "src/main/java/com/acme/api/Widget.java", "package com.acme.api;\npublic class Widget {}\n")
    _write(root, "src/main/java/com/acme/api/Helper.java", "package com.acme.api;\nclass Helper {}\n")


def test_find_dead_code_public_api_policy_report_is_not_a_no_op(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    _public_and_private_dead_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        kept = deletion.find_dead_code()  # default public_api_policy="keep"
        reported = deletion.find_dead_code(public_api_policy="report")

    assert kept.get("accepted") is True, kept
    kept_symbols = _symbols(kept["deadCodeCandidates"])
    # Baseline: the package-private dead type is flagged under "keep", but the public one is held back.
    assert "com.acme.api.Helper" in kept_symbols, kept
    assert "com.acme.api.Widget" not in kept_symbols, kept

    assert reported.get("accepted") is True, reported
    reported_symbols = _symbols(reported["deadCodeCandidates"])
    candidates = {c["symbol"]: c for c in reported["deadCodeCandidates"]}
    # "report" honestly surfaces the unreferenced public type as a high-confidence candidate.
    assert "com.acme.api.Widget" in reported_symbols, reported
    assert candidates["com.acme.api.Widget"]["confidence"] == "high", candidates["com.acme.api.Widget"]
    assert "public/protected" in candidates["com.acme.api.Widget"]["reason"], candidates["com.acme.api.Widget"]


def test_find_dead_code_public_api_policy_warn_matches_legacy_report(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # Canonical "warn" is the modern spelling of legacy "report": both surface the unreachable public type as a
    # high-confidence candidate AND emit a public-API-boundary review warning (R14, refactor-feature-plan-V3.md §7.5).
    _public_and_private_dead_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        warned = deletion.find_dead_code(public_api_policy="warn")
        reported = deletion.find_dead_code(public_api_policy="report")

    assert warned.get("accepted") is True, warned
    assert _symbols(warned["deadCodeCandidates"]) == _symbols(reported["deadCodeCandidates"]), (warned, reported)
    warnings_text = " ".join(warned.get("warnings", []))
    assert "com.acme.api.Widget" in warnings_text, warned
    assert "public-API boundary" in warnings_text, warned


def test_find_dead_code_public_api_policy_allow_drops_the_boundary_warning(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # "allow" ignores public-API status: the unreachable public type is reported like any internal symbol, with NO
    # public-API-boundary warning (R14, refactor-feature-plan-V3.md §7.5).
    _public_and_private_dead_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        allowed = deletion.find_dead_code(public_api_policy="allow")

    assert allowed.get("accepted") is True, allowed
    assert "com.acme.api.Widget" in _symbols(allowed["deadCodeCandidates"]), allowed
    assert "com.acme.api.Widget" not in " ".join(allowed.get("warnings", [])), allowed


def test_config_default_public_api_policy_is_injected_when_request_omits_it(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # B01 behavioral proof: java_refactor.v3.deletion.public_api_policy is an ABSENT-ONLY default — it changes the
    # planner's behavior only for a request that omits publicApiPolicy, and an explicit per-request value always wins.
    # Driven against the live sidecar so the injected default reaches the real DeadCodeAnalyzer, not a Python stub.
    _public_and_private_dead_project(tmp_path)
    # A findDeadCode request that genuinely OMITS publicApiPolicy. The DeletionClient always sends the field, so to
    # exercise absent-only injection we issue the raw request the same way the client forwards every deletion.* call.
    omit_policy = {"params": {"scope": "project", "includeTests": False}}
    allow_config = json.dumps({"java_refactor": {"v3": {"deletion": {"public_api_policy": "allow"}}}})

    # Control: with no V3 config the omitted field falls back to the hard-coded "keep" — the public type is held back.
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        default_result = deletion._client._request("deletion.findDeadCode", omit_policy)
    assert default_result.get("accepted") is True, default_result
    default_symbols = _symbols(default_result["deadCodeCandidates"])
    assert "com.acme.api.Helper" in default_symbols, default_result
    assert "com.acme.api.Widget" not in default_symbols, default_result

    # Configured default "allow": the SAME field-omitting request now sees the injected policy and reports the public
    # type like any internal symbol (no public-API-boundary warning) — proving the config default reached the planner.
    with _deletion(
        sidecar_jar, tmp_path, java_command=sidecar_java_cmd, configuration=allow_config
    ) as deletion:
        injected_result = deletion._client._request("deletion.findDeadCode", omit_policy)
        # Absent-only: an EXPLICIT per-request policy still overrides the configured default.
        explicit_keep = deletion.find_dead_code(public_api_policy="keep")
    assert injected_result.get("accepted") is True, injected_result
    injected_symbols = _symbols(injected_result["deadCodeCandidates"])
    assert "com.acme.api.Widget" in injected_symbols, injected_result
    assert "com.acme.api.Widget" not in " ".join(injected_result.get("warnings", [])), injected_result

    assert explicit_keep.get("accepted") is True, explicit_keep
    assert "com.acme.api.Widget" not in _symbols(explicit_keep["deadCodeCandidates"]), explicit_keep


# ── (g) find_dead_code: an unreadable META-INF/services file forces an incomplete scan and downgrades every candidate ─
# (refactor-feature-plan-V3.md §7.5 / §16, R09): if the service-loader registry cannot be fully read we may have MISSED
# a reflectively-loaded provider, so we must not present ANY symbol as high-confidence dead code, and must surface the
# incompleteness as a review warning rather than silently trusting a partial scan.


def _provider_and_unrelated_dead_project(root: Path) -> Path:
    _write(
        root,
        "src/main/java/com/acme/spi/Greeter.java",
        "package com.acme.spi;\npublic interface Greeter {\n    String greet();\n}\n",
    )
    # A package-private provider with no Java callers: a dead-code candidate, but it is registered in META-INF/services,
    # so when the registry is readable it is LOW (reflectively reachable), never HIGH.
    _write(
        root,
        "src/main/java/com/acme/spi/HiddenProvider.java",
        "package com.acme.spi;\nclass HiddenProvider implements Greeter {\n"
        "    public String greet() { return \"hi\"; }\n}\n",
    )
    # A package-private dead class that is NOT a provider: HIGH-confidence when the scan is complete.
    _write(root, "src/main/java/com/acme/web/Unused.java", "package com.acme.web;\nclass Unused {}\n")
    services = root / "src/main/resources/META-INF/services/com.acme.spi.Greeter"
    services.parent.mkdir(parents=True, exist_ok=True)
    services.write_text("com.acme.spi.HiddenProvider\n", encoding="utf-8")
    return services


def test_find_dead_code_incomplete_service_loader_scan_downgrades_all_to_low(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    if os.geteuid() == 0:
        pytest.skip("chmod 0o000 does not deny access to root, so the unreadable-file path cannot be exercised")

    services = _provider_and_unrelated_dead_project(tmp_path)

    # Baseline — the registry is readable: the provider is LOW (reflectively reachable), the unrelated dead class is HIGH,
    # and the scan reports itself complete with no service-loader warning. This proves the second run flips real state.
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        complete = deletion.find_dead_code()
    assert complete.get("accepted") is True, complete
    assert complete.get("serviceLoaderScanIncomplete") is False, complete
    complete_by_symbol = {c["symbol"]: c for c in complete["deadCodeCandidates"]}
    assert complete_by_symbol["com.acme.spi.HiddenProvider"]["confidence"] == "low", complete
    assert complete_by_symbol["com.acme.web.Unused"]["confidence"] == "high", complete
    assert not any(
        "service-loader provider scan was incomplete" in w.lower() for w in complete.get("warnings", [])
    ), complete

    # Now make the single registration file unreadable: the directory still lists it (walk succeeds) but readString
    # fails, so a provider it lists is invisible to the analysis.
    os.chmod(services, 0o000)
    try:
        with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
            incomplete = deletion.find_dead_code()
    finally:
        os.chmod(services, 0o644)

    assert incomplete.get("accepted") is True, incomplete
    assert incomplete.get("serviceLoaderScanIncomplete") is True, incomplete
    candidates = incomplete["deadCodeCandidates"]
    # No candidate may be presented as high-confidence dead code: the missed registration could make any of them a
    # reflectively-loaded provider.
    assert candidates, incomplete
    assert all(c["confidence"] == "low" for c in candidates), candidates
    incomplete_by_symbol = {c["symbol"]: c for c in candidates}
    # The previously HIGH unrelated class is now LOW and its reason cites the incomplete scan.
    unused = incomplete_by_symbol["com.acme.web.Unused"]
    assert unused["confidence"] == "low", unused
    assert "service-loader provider scan was incomplete" in unused["reason"].lower(), unused
    # The incompleteness is surfaced as a review warning, not silently swallowed.
    assert any(
        "service-loader provider scan was incomplete" in w.lower() for w in incomplete.get("warnings", [])
    ), incomplete


# ── include_tests note ─────────────────────────────────────────────────────────────────────────────────────────────
#
# include_tests IS wired (ReachabilityGraph.build takes it, and structuralReason marks a symbol a structural root when
# "!includeTests && testSource", so a recognised test symbol is kept under the default and becomes eligible under
# include_tests=true). It has no behavioural proof in THIS file because the proof needs a source set whose name contains
# "test" (ReachabilityGraph.testSourceRoots keys off that), and only real Maven/Gradle build-model extraction emits a
# "test" source set. The descriptor-less projects these live-javac fixtures build go through ProjectModelDiscoverer's
# conventional discovery, which folds every root — including src/test/java — into a single "main" source set, so no
# symbol is ever testSource and the flag is unobservable here. Spinning up real Maven/Gradle in-test would be heavy and
# flaky for one flag; its wiring is covered by reading the sidecar source, and the shared cascade/graph it feeds is
# exercised by tests (a) through (e).


# ── (h) propagate delete_private_only=false: deleting public API is gated by the flag, not a silent no-op ───────────


def test_propagate_delete_private_only_false_allows_public_delete(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # unusedPublic() is public with no callers; run() keeps the class alive. Under the default it is BLOCKED as public
    # API; passing delete_private_only=false opts into actually deleting it.
    _write(
        tmp_path,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\n"
        "public class Service {\n"
        "    public int run() { return 1; }\n"
        "    public int unusedPublic() { return 2; }\n"
        "}\n",
    )
    root = "com.acme.app.Service#unusedPublic()"
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        guarded = deletion.propagate_safe_delete([root])  # default delete_private_only=True
        opted_in = deletion.propagate_safe_delete([root], delete_private_only=False)

    assert guarded.get("accepted") is True, guarded
    guarded_plan = guarded["deletePlan"]
    # Default policy refuses to delete public API and says exactly how to override it.
    assert root in _symbols(guarded_plan["blocked"]), guarded_plan
    assert root not in _symbols(guarded_plan["cascade"]), guarded_plan
    assert "delete_private_only=false" in _reason_for(guarded_plan["blocked"], root), guarded_plan

    assert opted_in.get("accepted") is True, opted_in
    opted_plan = opted_in["deletePlan"]
    # With the flag flipped the public method is no longer a root, so it is deleted (and the removal really compiled).
    assert root not in _symbols(opted_plan["blocked"]), opted_plan
    assert opted_in.get("diagnosticDeltaValidated") is True, opted_in
    changed_files = {change["path"] for change in opted_in["workspaceEdit"]["changes"]}
    assert any(path.endswith("Service.java") for path in changed_files), opted_in


# ── (i) propagate include_resources=false: provider-line rewrite is gated by the flag, not a silent no-op ───────────


def test_propagate_include_resources_false_refuses_dangling_provider_line(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    # Test (c) proves the META-INF/services line IS excised by default; the mirror proves include_resources=false
    # suppresses that resource rewrite (the type is still deleted, the provider entry is deliberately left untouched).
    _service_loader_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.propagate_safe_delete(["com.acme.spi.LegacyGreeter"], include_resources=False)

    # V3 validation is resource-aware: disabling resource rewrites would leave the exact
    # service-loader provider dangling, so preview must refuse rather than stage an unsafe edit.
    assert result.get("accepted") is False, result
    assert result.get("refusal", {}).get("code") == "validation_findings_not_ready", result
    assert result.get("diagnosticDelta", {}).get("newErrors") == [], result


# ── (j) find_dead_code: unused constructor + unused overload of a LIVE type (§7.1) ──────────────────────────────────


def _overload_and_constructor_project(root: Path) -> None:
    # Box is kept alive by run() (a public root) which calls Box() and box.tag("x"). The no-arg constructor and tag("x")
    # are therefore live; the unused Box(int) constructor and the unused tag(int) overload are dead members of a LIVE
    # type, which §7.1 requires be surfaced as candidates ("unused constructor" / "unused method overload"), distinct
    # from the live siblings which must NOT be reported.
    _write(
        root,
        "src/main/java/com/acme/app/Box.java",
        "package com.acme.app;\n"
        "public class Box {\n"
        "    Box() {}\n"
        "    Box(int seed) {}\n"
        "    int tag(String s) { return s.length(); }\n"
        "    int tag(int n) { return n; }\n"
        "}\n",
    )
    _write(
        root,
        "src/main/java/com/acme/app/Runner.java",
        "package com.acme.app;\n"
        "public class Runner {\n"
        "    public int run() {\n"
        "        Box box = new Box();\n"
        "        return box.tag(\"x\");\n"
        "    }\n"
        "}\n",
    )


def test_find_dead_code_reports_unused_constructor_and_overload(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    _overload_and_constructor_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.find_dead_code(public_api_policy="report")

    assert result.get("accepted") is True, result
    candidates = {c["symbol"]: c for c in result["deadCodeCandidates"]}

    # The unused secondary constructor is a candidate, named as an unused constructor.
    assert "com.acme.app.Box#<init>(int)" in candidates, candidates
    ctor = candidates["com.acme.app.Box#<init>(int)"]
    assert ctor["confidence"] == "high", ctor
    assert "unused constructor" in ctor["reason"], ctor

    # The unused tag(int) overload is a candidate, named as an unused overload that points at the live sibling.
    assert "com.acme.app.Box#tag(int)" in candidates, candidates
    overload = candidates["com.acme.app.Box#tag(int)"]
    assert overload["confidence"] == "high", overload
    assert "unused method overload" in overload["reason"], overload
    assert "another overload of 'tag' is still referenced" in overload["reason"], overload

    # The LIVE constructor and the LIVE overload are referenced, so they are NOT reported dead.
    assert "com.acme.app.Box#<init>()" not in candidates, candidates
    assert "com.acme.app.Box#tag(java.lang.String)" not in candidates, candidates


# ── (k) reachability roots: a required §7.2 root (main + framework entry) is NEVER reported dead ─────────────────────


def _required_roots_project(root: Path) -> None:
    # The real Spring @RequestMapping FQN so framework-entry detection resolves by exact compiler FQN.
    _write(
        root,
        "src/main/java/org/springframework/web/bind/annotation/RequestMapping.java",
        "package org.springframework.web.bind.annotation;\n"
        "import java.lang.annotation.*;\n"
        "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)\n"
        "public @interface RequestMapping {}\n",
    )
    # main() is a §7.2 structural root (program entry point) — it has no in-project Java caller, so a naive "no incoming
    # references => dead" scan would wrongly flag it; the required-root set must keep it off the candidate list entirely.
    # handle() is a §7.2 framework-annotated member: not excluded outright, but downgraded to a LOW (review-only)
    # candidate because a framework may invoke it reflectively — never a HIGH "delete me" candidate.
    _write(
        root,
        "src/main/java/com/acme/app/Launcher.java",
        "package com.acme.app;\n"
        "import org.springframework.web.bind.annotation.RequestMapping;\n"
        "public class Launcher {\n"
        "    public static void main(String[] args) {}\n"
        "    @RequestMapping\n"
        "    void handle() {}\n"
        "}\n",
    )
    # A genuinely dead package-private type so the scan is proven to produce SOME output (not an accidental empty run).
    _write(root, "src/main/java/com/acme/app/Orphan.java", "package com.acme.app;\nclass Orphan {}\n")


def test_find_dead_code_never_reports_required_roots(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    _required_roots_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        # "report" is the most aggressive policy; even so, the structural root must never appear.
        result = deletion.find_dead_code(public_api_policy="report")

    assert result.get("accepted") is True, result
    candidates = {c["symbol"]: c for c in result["deadCodeCandidates"]}
    symbols = set(candidates)
    # Baseline: the scan is live and DOES find the genuinely-dead type.
    assert "com.acme.app.Orphan" in symbols, result
    # The program entry point is a required structural root — never reported dead under any policy.
    assert "com.acme.app.Launcher#main(java.lang.String[])" not in symbols, result
    # The framework-annotated member is surfaced only as a LOW-confidence (review-only) candidate, never HIGH.
    assert "com.acme.app.Launcher#handle()" in candidates, result
    assert candidates["com.acme.app.Launcher#handle()"]["confidence"] == "low", candidates


# ── (l) propagate delete: an UNAMBIGUOUS Spring <bean> is removed; an AMBIGUOUS one is left review-only ──────────────


def _spring_bean_project(root: Path) -> None:
    # Two types whose ONLY use is being instantiated by Spring XML (no Java references at all), so both are safely
    # deletable. The distinction is in the XML wiring, not the Java:
    #   * Orphan's bean has an id nothing references -> its sole role is instantiating Orphan -> UNAMBIGUOUS removal.
    #   * Shared's bean is wired into another bean via <ref bean="shared"/> -> removing it would dangle that wiring
    #     -> AMBIGUOUS, so it must be left in place and surfaced as a warning for human review.
    _write(root, "src/main/java/com/acme/app/Orphan.java", "package com.acme.app;\npublic class Orphan {}\n")
    _write(root, "src/main/java/com/acme/app/Shared.java", "package com.acme.app;\npublic class Shared {}\n")
    _write(root, "src/main/java/com/acme/app/Holder.java", "package com.acme.app;\npublic class Holder {}\n")
    _write(
        root,
        "src/main/resources/beans.xml",
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        "<beans>\n"
        '    <bean id="orphan" class="com.acme.app.Orphan"/>\n'
        '    <bean id="shared" class="com.acme.app.Shared"/>\n'
        '    <bean id="holder" class="com.acme.app.Holder">\n'
        '        <property name="dep" ref="shared"/>\n'
        "    </bean>\n"
        "</beans>\n",
    )


def test_propagate_refuses_when_spring_ref_still_points_at_deleted_bean(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    _spring_bean_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.propagate_safe_delete(
            ["com.acme.app.Orphan", "com.acme.app.Shared"], delete_private_only=False
        )

    # Removing Shared while a Spring XML ref still points at it is a blocking exact-resource
    # finding. V3 must refuse the composed preview instead of returning a partial accepted edit.
    assert result.get("accepted") is False, result
    assert result.get("refusal", {}).get("code") == "validation_findings_not_ready", result
    assert result.get("diagnosticDelta", {}).get("newErrors") == [], result


def _apply_text_edits(path: Path, edits: list[dict]) -> str:
    content = path.read_text(encoding="utf-8")
    # Apply right-to-left so earlier offsets stay valid as later spans are replaced.
    for e in sorted(edits, key=lambda x: x["startOffset"], reverse=True):
        content = content[: e["startOffset"]] + e["newText"] + content[e["endOffset"] :]
    return content


# ── (m) propagate delete: a genuine §7.6 precondition refusal still refuses (not warning-only) ──────────────────────


def test_propagate_refuses_when_no_roots(sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str) -> None:
    # §7.6: a request with no deletion root has nothing to delete; the planner must REFUSE (accepted=false with a coded
    # refusal), not silently accept an empty plan.
    _write(
        tmp_path,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\npublic class Service { public int run() { return 1; } }\n",
    )
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.propagate_safe_delete([])

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "no_roots", result
    assert "root" in result["refusal"]["message"].lower(), result


# ── (n) framework participant joins the delete plan: a @Entity delete is VETOED with the plugin's reason ─────────────


def _jpa_entity_project(root: Path) -> None:
    # Real jakarta.persistence FQNs so framework participation resolves them by exact compiler FQN. Customer is a
    # package-private @Entity with no Java references — a naive safe-delete would happily remove it, but the JPA plugin
    # vetoes deleting a persistence entry point (it is mapped to a table and may be reached only through JPA metadata).
    _write(
        root,
        "src/main/java/jakarta/persistence/Entity.java",
        "package jakarta.persistence;\npublic @interface Entity {}\n",
    )
    _write(
        root,
        "src/main/java/jakarta/persistence/Id.java",
        "package jakarta.persistence;\npublic @interface Id {}\n",
    )
    _write(
        root,
        "src/main/java/com/acme/model/Customer.java",
        "package com.acme.model;\n"
        "import jakarta.persistence.Entity;\n"
        "import jakarta.persistence.Id;\n"
        "@Entity\n"
        "public class Customer {\n"
        "    @Id long id;\n"
        "}\n",
    )


def test_propagate_delete_blocked_by_framework_participant(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    _jpa_entity_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.propagate_safe_delete(["com.acme.model.Customer"], delete_private_only=False)

    assert result.get("accepted") is True, result
    plan = result["deletePlan"]
    # The JPA framework plugin vetoes the deletion in the actual plan — the type is blocked, never cascaded.
    blocked = _symbols(plan["blocked"])
    assert "com.acme.model.Customer" in blocked, plan
    assert "com.acme.model.Customer" not in _symbols(plan["cascade"]), plan
    reason = _reason_for(plan["blocked"], "com.acme.model.Customer")
    assert "persistence entry point" in reason, reason


# ── (o) framework participant joins the dead-code scan: a JUnit @Test method/class is NEVER reported dead ────────────


def _junit_dead_code_project(root: Path) -> None:
    # Real JUnit Jupiter @Test FQN so framework participation resolves it by exact compiler FQN. MyTest is a
    # package-private test class whose only "use" is reflective invocation by the runner; without the JUnit participant
    # rooting it, a "no Java references => dead" scan would wrongly flag both the class and its @Test method. A genuinely
    # dead type is included so the scan is proven live (not an accidental empty run).
    _write(
        root,
        "src/main/java/org/junit/jupiter/api/Test.java",
        "package org.junit.jupiter.api;\npublic @interface Test {}\n",
    )
    _write(
        root,
        "src/main/java/com/acme/test/MyTest.java",
        "package com.acme.test;\n"
        "import org.junit.jupiter.api.Test;\n"
        "class MyTest {\n"
        "    @Test void runs() {}\n"
        "}\n",
    )
    _write(root, "src/main/java/com/acme/test/Orphan.java", "package com.acme.test;\nclass Orphan {}\n")


def test_find_dead_code_keeps_junit_test_via_framework_participant(
    sidecar_jar: Path, tmp_path: Path, sidecar_java_cmd: str
) -> None:
    _junit_dead_code_project(tmp_path)
    with _deletion(sidecar_jar, tmp_path, java_command=sidecar_java_cmd) as deletion:
        result = deletion.find_dead_code(public_api_policy="report")

    assert result.get("accepted") is True, result
    symbols = _symbols(result["deadCodeCandidates"])
    # Baseline: the scan is live and finds the genuinely-dead type.
    assert "com.acme.test.Orphan" in symbols, result
    # The JUnit @Test method and its enclosing test class are framework reachability roots — never reported dead.
    assert "com.acme.test.MyTest" not in symbols, result
    assert "com.acme.test.MyTest#runs()" not in symbols, result


# ── max_cascade_depth note ─────────────────────────────────────────────────────────────────────────────────────────
#
# max_cascade_depth IS wired (PropagatingSafeDeletePlanner bounds the fixed-point cascade loop by it), but it has no
# order-INDEPENDENT behavioural proof: each loop sweep visits every node once, so any DAG whose nodes happen to be
# visited in topological order cascades fully in a single sweep regardless of the bound. A test that forced the bound to
# bite would have to pin the sidecar's graph.nodes() iteration order, which is an implementation detail we deliberately
# do not couple to. Its wiring is covered by reading the planner source; the cascade itself is exercised by test (a).
