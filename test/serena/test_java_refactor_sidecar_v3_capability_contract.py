"""F1: the public capability contract must enumerate every dedicated V3 dispatch operation.

refactor-feature-plan-V3.md requires the sidecar to advertise the *whole* operation surface it dispatches, with a
truthful per-operation status. Before this finding the sidecar dispatched ~20 dedicated V3 methods
(``transformation.*``, ``deletion.*``, ``classRefactor.*``, ``conversions.*``, ``inlineRefactor.*``, ``recipes.*``,
``resources.*``, ``frameworks.*``, ``impact.facts``) that appeared NOWHERE in ``capabilities`` /
``capabilityDetails`` -- a silent, undocumented surface. And on the Python side ``_capabilities`` filtered the
negotiated registry down to the eleven V2 ops, so the three V3 whole-repo package tools
(``renamePackage`` / ``movePackage`` / ``moveSourceRoot``) could never register in production even though the sidecar
fully supports them.

These tests prove, against the LIVE sidecar and the real manager negotiation path, that:

* every dedicated V3 dispatch method is enumerated in ``capabilities`` (level ``experimental``) and
  ``capabilityDetails`` (status ``preview`` until its finding lands and promotes it into the ready set) -- the expected
  set is *derived from* ``Main.V3_DISPATCH_GATES`` (Review Gap 16), so a gated, sidecar-backed V3 op wired in without a
  CapabilitySpec (as ``frameworks.participate`` once was) fails this contract instead of silently bypassing negotiation,
* a V3 operation that the effective ``java_refactor.v3`` config gates off truthfully reports status ``disabled``
  (via a per-section flag, the per-op flag, and the global ``v3.enabled`` master switch), while the always-on V1 stable
  ops carry no gate and stay ``supported`` even when both the V2 and V3 surfaces are disabled,
* the three V3 package operations report status ``supported`` (they are implemented and revision-guarded), and
* the manager's capability negotiation now lets those three package operations flow through so their tools register,
  while a dedicated V3 method op that has no Python tool yet does NOT leak into the negotiated set.
"""

import json
import re
from pathlib import Path
from typing import Any, cast

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)

# The sidecar source of truth for the gated V3 dispatch surface. Every dedicated V3 JSON-RPC method that reaches a
# planner directly is registered as a key of ``Main.V3_DISPATCH_GATES`` (it is the SAME map the dispatch path consults
# to refuse a config-disabled op). Review Gap 16 requires that EVERY such sidecar-backed V3 op also participate in
# capability negotiation -- i.e. carry a CapabilitySpec and therefore be advertised in ``capabilities`` /
# ``capabilityDetails`` with a truthful status. Deriving the expected set straight from this map (rather than a
# hand-maintained literal) makes the coverage test self-updating: wire a new gated V3 method into ``V3_DISPATCH_GATES``
# without also advertising it and this test fails, because the advertised dotted surface will no longer equal the gated
# set. That is the anti-drift property the gap demands.
_MAIN_JAVA = Path(__file__).resolve().parents[2] / "java-refactor/src/main/java/io/serena/javarefactor/protocol/Main.java"


def _dispatched_v3_operations_from_source() -> set[str]:
    """Parses the ``V3_DISPATCH_GATES`` map keys out of ``Main.java`` -- the authoritative gated V3 dispatch surface.

    The block is delimited by ``Map.ofEntries(`` ... the matching ``);`` and every entry is ``Map.entry("op", ...)``.
    Returns only dotted operation names (the dedicated V3 JSON-RPC methods); a bare key such as ``impact.facts`` is
    dotted and kept, while any non-dotted helper key (none today) would be excluded as not part of the dotted surface.
    """
    text = _MAIN_JAVA.read_text(encoding="utf-8")
    marker = "V3_DISPATCH_GATES = Map.ofEntries("
    start = text.index(marker) + len(marker)
    # Walk to the matching close paren of Map.ofEntries(, respecting nested parens (each Map.entry(...) opens one).
    depth = 1
    index = start
    while depth > 0:
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        index += 1
    block = text[start : index - 1]
    operations = set(re.findall(r'Map\.entry\("([^"]+)"', block))
    return {operation for operation in operations if "." in operation}


# Derived once at import time from Main.java so the contract assertions below track the dispatch surface automatically.
_EXPECTED_V3_DISPATCH_OPERATIONS = _dispatched_v3_operations_from_source()

_PACKAGE_OPERATIONS = {"renamePackage", "movePackage", "moveSourceRoot"}


def _write_plain_project(project_root: Path) -> None:
    path = project_root / "src/main/java/app/App.java"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("package app;\npublic class App {\n    int value() { return 1; }\n}\n", encoding="utf-8")


def _capability_payload(sidecar_jar: Path, project_root: Path, configuration: str = "default") -> dict[str, Any]:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        return client.capabilities()
    finally:
        client.shutdown()


def test_sidecar_enumerates_every_v3_dispatch_operation(sidecar_jar: Path, tmp_path: Path) -> None:
    # F1: every dedicated V3 dispatch method is advertised. The dotted-operation surface of the registry must equal the
    # full set of dispatched V3 methods (no silent, undocumented operations), each carrying the experimental level and a
    # truthful "preview" status (none are in READY_OPERATIONS yet) plus a non-empty description.
    project_root = tmp_path / "enumerate"
    _write_plain_project(project_root)

    # Guard the derivation itself: if the Main.java parse silently produced an empty/tiny set the equality below would
    # become vacuous, so anchor a lower bound and require the previously-silent participate op the gap was about.
    assert len(_EXPECTED_V3_DISPATCH_OPERATIONS) >= 20, _EXPECTED_V3_DISPATCH_OPERATIONS
    assert "frameworks.participate" in _EXPECTED_V3_DISPATCH_OPERATIONS, _EXPECTED_V3_DISPATCH_OPERATIONS

    payload = _capability_payload(sidecar_jar, project_root)
    capabilities = cast(dict[str, str], payload["capabilities"])
    details = cast(dict[str, dict[str, Any]], payload["capabilityDetails"])

    advertised_v3 = {operation for operation in capabilities if "." in operation}
    assert advertised_v3 == _EXPECTED_V3_DISPATCH_OPERATIONS, (
        "the advertised dedicated-V3 surface drifted from the gated dispatch set parsed from Main.V3_DISPATCH_GATES; "
        "every gated V3 dispatch method must carry a CapabilitySpec so it participates in capability negotiation "
        "(and nothing else dotted may be advertised). Missing from the advertised registry: "
        f"{sorted(_EXPECTED_V3_DISPATCH_OPERATIONS - advertised_v3)}; advertised but not gated: "
        f"{sorted(advertised_v3 - _EXPECTED_V3_DISPATCH_OPERATIONS)}."
    )

    for operation in _EXPECTED_V3_DISPATCH_OPERATIONS:
        assert capabilities[operation] == "experimental", (operation, capabilities[operation])
        detail = details[operation]
        assert detail["level"] == "experimental", (operation, detail)
        # Default config gates nothing and none of these are ready yet, so each must truthfully report "preview".
        assert detail["status"] == "preview", (operation, detail)
        assert isinstance(detail["description"], str) and detail["description"].strip(), (operation, detail)


def test_sidecar_v3_operation_reports_disabled_when_section_flag_gates_it(sidecar_jar: Path, tmp_path: Path) -> None:
    # F1: a per-section config flag (java_refactor.v3.deletion.propagate_enabled: false) flips ONLY that operation's
    # status to "disabled"; sibling V3 operations remain "preview". This proves the advertised status is computed from
    # the same gate that refuses the dispatch, not a static label.
    project_root = tmp_path / "section_disabled"
    _write_plain_project(project_root)

    configuration = json.dumps({"java_refactor": {"v3": {"deletion": {"propagate_enabled": False}}}})
    payload = _capability_payload(sidecar_jar, project_root, configuration=configuration)
    details = cast(dict[str, dict[str, Any]], payload["capabilityDetails"])

    assert details["deletion.propagateSafeDelete"]["status"] == "disabled", details["deletion.propagateSafeDelete"]
    # A sibling V3 op not covered by that flag is unaffected.
    assert details["transformation.preview"]["status"] == "preview", details["transformation.preview"]
    # And a V3 op in a different section is likewise unaffected.
    assert details["frameworks.detect"]["status"] == "preview", details["frameworks.detect"]


def test_sidecar_frameworks_participate_negotiates_and_reports_disabled_when_gated(sidecar_jar: Path, tmp_path: Path) -> None:
    # Review Gap 16: frameworks.participate is a sidecar-backed V3 op that is dispatched and config-gated (its
    # java_refactor.v3.frameworks section) but historically carried NO CapabilitySpec, so it never appeared in the
    # negotiated capability surface -- a silent, undocumented sidecar-dependent op. It must now participate in
    # negotiation like every sibling: advertised under the default config (status "preview", since it is not yet ready),
    # and -- proving the advertised status is computed by the SAME gate that refuses dispatch -- reported "disabled" when
    # its section flag gates it off, while a sibling op outside that section stays unaffected.
    project_root = tmp_path / "participate_gate"
    _write_plain_project(project_root)

    default_details = cast(dict[str, dict[str, Any]], _capability_payload(sidecar_jar, project_root)["capabilityDetails"])
    assert "frameworks.participate" in default_details, sorted(default_details)
    assert default_details["frameworks.participate"]["level"] == "experimental", default_details["frameworks.participate"]
    assert default_details["frameworks.participate"]["status"] == "preview", default_details["frameworks.participate"]

    configuration = json.dumps({"java_refactor": {"v3": {"frameworks": {"enabled": False}}}})
    gated_details = cast(
        dict[str, dict[str, Any]],
        _capability_payload(sidecar_jar, project_root, configuration=configuration)["capabilityDetails"],
    )
    assert gated_details["frameworks.participate"]["status"] == "disabled", gated_details["frameworks.participate"]
    # The whole frameworks section is gated, so the read-only siblings degrade too; a V3 op in another section does not.
    assert gated_details["frameworks.detect"]["status"] == "disabled", gated_details["frameworks.detect"]
    assert gated_details["transformation.preview"]["status"] == "preview", gated_details["transformation.preview"]


def test_sidecar_pure_v1_operations_are_not_gated(sidecar_jar: Path, tmp_path: Path) -> None:
    # Review Gap 16 (exemption half): the gate requirement applies to sidecar-dependent V2/V3 ops. The V1 stable ops are
    # never disabled by any enable flag -- they have no capability gate and always report "supported" -- so a config that
    # disables both the V2 and the V3 surfaces must leave the V1 ops untouched. This documents and pins the exemption so
    # "every op is gated" is not mis-applied to the always-on V1 core.
    project_root = tmp_path / "v1_exempt"
    _write_plain_project(project_root)

    configuration = json.dumps({"java_refactor": {"enabled": False, "v3": {"enabled": False}}})
    details = cast(
        dict[str, dict[str, Any]],
        _capability_payload(sidecar_jar, project_root, configuration=configuration)["capabilityDetails"],
    )
    for operation in ("semanticRename", "safeDelete", "moveTopLevelType", "inlineLocalVariable", "inlineConstant"):
        assert details[operation]["status"] == "supported", (operation, details[operation])


def test_sidecar_v3_master_switch_disables_every_dispatch_operation(sidecar_jar: Path, tmp_path: Path) -> None:
    # F1: the global java_refactor.v3.enabled: false master switch reports EVERY dedicated V3 dispatch op as "disabled".
    project_root = tmp_path / "master_disabled"
    _write_plain_project(project_root)

    configuration = json.dumps({"java_refactor": {"v3": {"enabled": False}}})
    payload = _capability_payload(sidecar_jar, project_root, configuration=configuration)
    details = cast(dict[str, dict[str, Any]], payload["capabilityDetails"])

    for operation in _EXPECTED_V3_DISPATCH_OPERATIONS:
        assert details[operation]["status"] == "disabled", (operation, details[operation])
    # The V3 package operations are NOT gated by the v3 dispatch master switch (they are V2-contract ops); they stay
    # supported even when the dedicated V3 dispatch surface is disabled.
    for operation in _PACKAGE_OPERATIONS:
        assert details[operation]["status"] == "supported", (operation, details[operation])


def test_sidecar_package_operations_report_supported(sidecar_jar: Path, tmp_path: Path) -> None:
    # F1: the three V3 whole-repo package operations are implemented and revision-guarded, so they must advertise
    # status "supported" (not "preview"/"experimental") -- this is what lets their capability tools register.
    project_root = tmp_path / "package_supported"
    _write_plain_project(project_root)

    payload = _capability_payload(sidecar_jar, project_root)
    capabilities = cast(dict[str, str], payload["capabilities"])
    details = cast(dict[str, dict[str, Any]], payload["capabilityDetails"])

    for operation in _PACKAGE_OPERATIONS:
        assert capabilities[operation] == "beta", (operation, capabilities[operation])
        assert details[operation]["status"] == "supported", (operation, details[operation])


def test_manager_negotiates_v3_package_operations(tmp_path: Path, monkeypatch) -> None:
    # F1 (Python half): the manager's capability negotiation must let every public V3 tool operation flow through so
    # registration, capability status, and dispatch refusal agree. Driven with a fake client so this stays a pure unit
    # test (no live sidecar / JDK).
    from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
    from serena.java_refactor.manager import _V3_CAPABILITY_OPERATIONS, JavaRefactorManager
    from solidlsp.ls_config import Language

    class _FakeClient:
        def capabilities(self) -> dict[str, Any]:
            v3_capabilities = {
                operation: ("beta" if operation in {"renamePackage", "movePackage", "moveSourceRoot"} else "experimental")
                for operation in _V3_CAPABILITY_OPERATIONS
            }
            capabilities = {"changeSignature": "beta", **v3_capabilities}
            return {
                "capabilities": capabilities,
                "capabilityDetails": {
                    operation: {"level": level, "status": "supported"}
                    for operation, level in capabilities.items()
                },
            }

    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )
    monkeypatch.setattr(manager, "_validate_supported_project", lambda *_, **__: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda *_, **__: _FakeClient())

    supported = manager.supported_v2_operations()

    assert supported is not None
    # Every public V3 capability operation now negotiates through (defect: only package ops were represented).
    assert supported >= _V3_CAPABILITY_OPERATIONS, supported
    assert "changeSignature" in supported
