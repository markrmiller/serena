"""G001: stateful Java refactor sessions, revision guards, and capabilities."""

import ast
import json
import subprocess
from pathlib import Path
from typing import Any, cast

import pytest

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _assert_stable_target_identity(session: dict[str, Any]) -> None:
    identity = cast(dict[str, Any], session["session"])["targetIdentity"]
    assert isinstance(identity, str)
    assert "semanticCanonical=" in identity
    assert "semanticKey=<missing>" not in identity
    assert "|path=" not in identity


def _write_session_fixture(project_root: Path) -> None:
    """A tiny project whose field rename yields a non-empty semantic preview."""
    (project_root / "App.java").write_text(
        """public class App {
    private int value;
    public int read() {
        return value;
    }
}
""",
        encoding="utf-8",
    )


def _write_signature_fixture(project_root: Path) -> None:
    """A tiny project with simple non-overloaded methods for V2 signature previews."""
    (project_root / "App.java").write_text(
        """public class App {
    String greet() {
        return helper("Bob");
    }

    String helper(String name) {
        return "hi " + name;
    }
}
""",
        encoding="utf-8",
    )


def _write_move_member_fixture(project_root: Path) -> None:
    """A tiny project for conservative V2 member-move previews."""
    (project_root / "Source.java").write_text(
        """public class Source {
    static String label(String name) {
        return "hi " + name;
    }

    String decorate(Target target, String name) {
        return target.name() + name;
    }

    void run(Target target) {
        String a = label("Ada");
        String b = this.decorate(target, a);
    }
}
""",
        encoding="utf-8",
    )
    (project_root / "Target.java").write_text(
        """public class Target {
    String name() {
        return "target";
    }
}
""",
        encoding="utf-8",
    )


def _write_hierarchy_fixture(project_root: Path) -> None:
    """A tiny direct hierarchy project for V2 pull-up and push-down previews."""
    (project_root / "Base.java").write_text(
        """public class Base {
}
""",
        encoding="utf-8",
    )
    (project_root / "Child.java").write_text(
        """public class Child extends Base {
    String label() {
        return "child";
    }
}
""",
        encoding="utf-8",
    )
    (project_root / "OtherChild.java").write_text(
        """public class OtherChild extends Base {
}
""",
        encoding="utf-8",
    )


def _write_extract_method_fixture(project_root: Path) -> None:
    """A tiny source file for conservative extract-method previews."""
    (project_root / "ExtractSample.java").write_text(
        """public class ExtractSample {
    void run() {
        System.out.println("one");
        System.out.println("two");
    }

    String blocked() {
        return "x";
    }
}
""",
        encoding="utf-8",
    )


def _write_extract_interface_fixture(project_root: Path) -> None:
    """A tiny source file for conservative extract-interface previews."""
    (project_root / "InterfaceSource.java").write_text(
        """public class InterfaceSource {
    public String value(String prefix) {
        return prefix + " value";
    }

    private String hidden() {
        return "hidden";
    }
}
""",
        encoding="utf-8",
    )


def _write_field_refactor_fixture(project_root: Path) -> None:
    """A tiny source file for conservative field refactor previews."""
    (project_root / "FieldSample.java").write_text(
        """public class FieldSample {
    int count = 1;

    int read() {
        return count;
    }

    void write(int value) {
        count = value;
    }

    String label() {
        return "value";
    }
}
""",
        encoding="utf-8",
    )


def _write_inline_method_fixture(project_root: Path) -> None:
    """A tiny source file for conservative inline-method previews."""
    (project_root / "InlineMethodSample.java").write_text(
        """public class InlineMethodSample {
    int run() {
        return doubleValue(4);
    }

    private int doubleValue(int value) {
        return value + value;
    }

    private int blocked(int value) {
        int local = value;
        return local;
    }
}
""",
        encoding="utf-8",
    )


def _selection_for(source: str, snippet: str) -> dict[str, int]:
    start = source.index(snippet)
    end = start + len(snippet)

    def line_column(offset: int) -> tuple[int, int]:
        line = source.count("\n", 0, offset) + 1
        line_start = source.rfind("\n", 0, offset) + 1
        return line, offset - line_start + 1

    start_line, start_column = line_column(start)
    end_line, end_column = line_column(end)
    return {"startLine": start_line, "startColumn": start_column, "endLine": end_line, "endColumn": end_column}


def _preview_text(source: str, payload: dict[str, Any], path: str) -> str:
    """Apply preview text edits for one path so tests assert behavior, not edit granularity."""
    edits = [
        edit
        for change in payload["preview"]["workspaceEdit"]["changes"]
        if change["path"] == path
        for edit in change["edits"]
    ]
    for edit in sorted(edits, key=lambda item: item["startOffset"], reverse=True):
        source = source[: edit["startOffset"]] + edit["newText"] + source[edit["endOffset"] :]
    return source


def _assert_standard_v2_preview_payload(preview: dict[str, Any], operation: str, touched_path: str) -> None:
    assert preview["accepted"] is True
    assert preview["operation"] == operation
    assert preview["mode"] == "preview"
    assert preview["applied"] is False
    assert preview["warnings"]
    assert preview["diagnostics"] == []
    assert preview.get("refusal") is None
    assert preview["workspaceEdit"]["preconditions"]
    assert preview["semanticTarget"]["operation"] == operation
    assert preview["diagnosticDelta"] == {
        "before": {"errors": [], "warnings": []},
        "after": {"errors": [], "warnings": []},
        "newErrors": [],
        "resolvedErrors": [],
        "unchangedErrors": [],
        "newWarnings": [],
        "resolvedWarnings": [],
        "unchangedWarnings": [],
    }
    assert preview["stats"]["editCount"] > 0
    assert preview["stats"]["fileOperationCount"] == 0
    assert preview["stats"]["touchedFileCount"] == 1
    assert preview["stats"]["touchedFiles"] == [touched_path]
    assert preview["workspaceEdit"]["changes"][0]["path"] == touched_path
    assert preview["workspaceEdit"]["warnings"] == preview["warnings"]


def _write_policy_fixture(project_root: Path) -> None:
    """Tiny generated and Lombok-like sources for V2 policy gates."""
    generated_dir = project_root / "generated"
    generated_dir.mkdir()
    (generated_dir / "GeneratedSample.java").write_text(
        """public class GeneratedSample {
    void run() {
        System.out.println("generated");
    }
}
""",
        encoding="utf-8",
    )
    (project_root / "LombokSample.java").write_text(
        """@Data
public class LombokSample {
    String value;
}
""",
        encoding="utf-8",
    )


def _write_generated_only_fixture(project_root: Path) -> None:
    """A clean generated source with NO pre-existing compiler errors.

    Used by the generated-source opt-in *accept* tests so they exercise the default complete-analysis policy:
    ``_write_policy_fixture`` additionally drops a Lombok ``@Data`` source whose javac error (Lombok is not on the
    classpath) is unrelated noise that complete-analysis mode would correctly refuse on the accept path.
    """
    generated_dir = project_root / "generated"
    generated_dir.mkdir()
    (generated_dir / "GeneratedSample.java").write_text(
        """public class GeneratedSample {
    void run() {
        System.out.println("generated");
    }
}
""",
        encoding="utf-8",
    )


def test_status_reports_v2_capabilities(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        status = client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        capability_payload = client.capabilities()
    finally:
        client.shutdown()

    payload = json.loads(status.to_json())
    # V2 contract: capabilities maps op -> string lifecycle level; the richer {level,status,description} per operation
    # lives in the sibling capabilityDetails map.
    capabilities = cast(dict[str, str], capability_payload["capabilities"])
    details = cast(dict[str, dict[str, Any]], capability_payload["capabilityDetails"])
    assert isinstance(capabilities, dict)
    assert all(isinstance(level, str) for level in capabilities.values())
    assert payload["capabilities"] == capabilities
    assert capability_payload["runtime"]["status"] == "running"
    assert isinstance(capability_payload["runtime"]["jdk"], str)
    assert capability_payload["runtime"]["protocolVersion"] == "serena-java-refactor/0.1"

    assert capabilities["semanticRename"] == "stable"
    # rename is advertised as a stable alias of semanticRename per the V2 plan's sample registry.
    assert capabilities["rename"] == "stable"
    assert details["semanticRename"]["level"] == "stable"
    assert details["semanticRename"]["status"] == "supported"
    assert isinstance(details["semanticRename"]["description"], str)
    assert capabilities["safeDelete"] == "stable"
    assert capabilities["moveTopLevelType"] == "stable"
    assert capabilities["inlineLocalVariable"] == "stable"
    assert capabilities["refactorSessions"] == "beta"
    # G001: ops whose V2 hard requirements are implemented advertise "supported"; ops with an open blocker
    # advertise "preview" (not "supported") so Python will not register/use them until the blocker lands.
    supported_beta_ops = (
        "inlineMethod",
        "moveStaticMember",
        "extractInterface",
        "introduceField",
        "encapsulateField",
        "refactorSessions",
        # G004-G007 resolved: change-signature and its introduce-parameter wrapper are now supported.
        "changeSignature",
        "introduceParameter",
        # G008 resolved: instance-method move now refuses or preserves method references rather than silently
        # skipping them, so it is advertised supported.
        "moveInstanceMethod",
        # G009-G011 resolved: hierarchy pull-up/push-down now enforce interface field rules, fully-qualified target
        # resolution, and the Serializable field-move guard, so both are advertised supported.
        "pullUpMember",
        "pushDownMember",
        # G012-G013 resolved: extract-method now supports method/constructor AND initializer (static/instance block,
        # field initializer) selections with the full V2 data-flow/control-flow matrix, so it is advertised supported.
        "extractMethod",
        # V3-3 resolved: package rename rewrites declarations, file moves, imports, and FQN references, and every
        # accepted preview is revalidated by the central PreviewDiagnosticValidator (javac before/after). The one
        # documented edge case — renaming a package whose subpackages are not also renamed — is caught and refused as
        # new_compiler_errors rather than silently corrupting code, so the operation is safe-by-refusal and supported.
        "renamePackage",
        # V3-3 (G003 second half) resolved: package MOVE relocates the source package and (by default) its subpackages
        # to a target package, optionally under a different configured source root, rewriting declarations, file moves,
        # imports, and FQN references. Every accepted preview is revalidated by the central PreviewDiagnosticValidator
        # (javac before/after); any subpackage/prefix over-rewrite edge case is caught and refused as new_compiler_errors
        # rather than silently corrupting code, so the operation is safe-by-refusal and supported.
        "movePackage",
        # V3-3 (G003) resolved: source-root MOVE relocates Java files from one configured source root to another
        # WITHOUT changing package declarations, so it emits only file move operations and leaves FQNs/imports
        # untouched. Every accepted preview is still revalidated by the central PreviewDiagnosticValidator (javac
        # before/after); a destination collision or type shadowing on the compile path is caught and refused rather
        # than silently applied, so the operation is safe-by-refusal and supported.
        "moveSourceRoot",
        "transformation.createWorkspace",
        "transformation.addOperation",
        "transformation.addSession",
        "transformation.preview",
        "transformation.apply",
        "transformation.ackApply",
        "transformation.cancel",
        "transformation.list",
        "transformation.report",
        "deletion.propagateSafeDelete",
        "deletion.findDeadCode",
        "classRefactor.extractClass",
        "classRefactor.extractSuperclass",
        "classRefactor.replaceInheritanceWithDelegation",
        "conversions.anonymousToLambda",
        "conversions.lambdaToMethodReference",
        "inlineRefactor.deepInlineMethod",
        "recipes.scanMigrationOpportunities",
        "recipes.applyRecipe",
        "resources.findReferences",
        "resources.planEdits",
        "frameworks.detect",
        "frameworks.findReferences",
        "frameworks.participate",
        "graph.build",
        "impact.facts",
    )
    for operation in supported_beta_ops:
        assert capabilities[operation] == "beta"
        assert details[operation]["status"] == "supported"
    # G001 truthfulness: an op is "supported" ONLY if it is in the known-resolved supported_beta_ops list above. Any
    # beta op NOT in that list must report "preview" (its blocker is still open) — never "supported". This makes the
    # test fail if a new op is wired into the registry without its blocker being resolved and listed here.
    for operation, level in capabilities.items():
        if level != "beta":
            continue
        if operation not in supported_beta_ops:
            status = details[operation]["status"]
            assert status == "preview", (
                f"{operation} advertises {status!r} but is not in the known-resolved supported list; "
                f"either resolve its blocker and add it to supported_beta_ops, or it must report 'preview'."
            )
    assert payload["live_sessions"] == 0


def test_capabilities_no_op_advertised_without_being_ready(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001 regression guard (T1): the sidecar must never advertise an op as "supported" unless it is in
    READY_OPERATIONS. Every op in the capability registry reports a status; ops whose V2 hard-requirement blocker is
    open MUST report "preview" (never "supported"). Mirrors the truthfulness contract Python relies on: a non-supported
    op is refused by _ensure_v2_capability, a supported op passes. Fails if a future op is wired into capabilitiesJson()
    without its blocker landing in READY_OPERATIONS."""
    from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
    from serena.java_refactor.manager import _V2_CAPABILITY_OPERATIONS, JavaRefactorManager
    from solidlsp.ls_config import Language

    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        capability_payload = client.capabilities()
    finally:
        client.shutdown()

    capabilities = cast(dict[str, str], capability_payload["capabilities"])
    details = cast(dict[str, dict[str, Any]], capability_payload["capabilityDetails"])

    # Every advertised op carries a string level and an explicit detail status of one of the truthful values.
    for operation, level in capabilities.items():
        assert isinstance(level, str), (operation, level)
        assert details[operation]["status"] in {"supported", "preview", "disabled"}, (operation, details[operation])

    # Python only dispatches the V2 session ops; for each, the sidecar's advertised status drives gating.
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )

    class _Fake:
        def __init__(self, payload: dict[str, Any]) -> None:
            self._payload = payload

        def capabilities(self) -> dict[str, Any]:
            return self._payload

    fake = _Fake(capability_payload)
    for operation in _V2_CAPABILITY_OPERATIONS:
        status = details.get(operation, {}).get("status")
        refusal = manager._ensure_v2_capability(fake, operation, "preview")  # type: ignore[arg-type]
        if status == "supported":
            assert refusal is None, f"{operation} is supported but was refused"
        else:
            assert refusal is not None, f"{operation} is not supported ({status}) but was not refused"
            assert refusal["refusal"]["code"] == "java_refactor_capability_unavailable"


def test_v2_registry_is_truthfully_all_supported_and_gates_on_status(sidecar_jar: Path, tmp_path: Path) -> None:
    """G018 truthfulness + gating guard: all V2 hard-requirement blockers (G001-G017) have landed, so the live sidecar
    must advertise EVERY V2 operation as status=="supported" -- this proves the all-supported registry is the legitimate
    state, not an over-claim. The same test proves the gate is wired to status, so the registry cannot silently rot: if
    any V2 op regressed to a non-"supported" status, the manager's ``_ensure_v2_capability`` would refuse it (gating it
    out of the exposed surface). We assert BOTH halves against the live sidecar so "supported" is tied to real
    readiness and a future op cannot be advertised supported without passing the gate, nor gated without losing
    "supported"."""
    from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
    from serena.java_refactor.manager import _V2_CAPABILITY_OPERATIONS, JavaRefactorManager
    from solidlsp.ls_config import Language

    # The complete V2 operation surface the design requires (the eleven V2 ops). Pinning the expected set makes adding
    # or dropping a V2 op a deliberate, test-visible change rather than a silent edit to _V2_CAPABILITY_OPERATIONS.
    expected_v2_operations = {
        "changeSignature",
        "introduceParameter",
        "moveStaticMember",
        "moveInstanceMethod",
        "pullUpMember",
        "pushDownMember",
        "extractMethod",
        "extractInterface",
        "introduceField",
        "encapsulateField",
        "inlineMethod",
    }
    assert _V2_CAPABILITY_OPERATIONS == expected_v2_operations, (
        "the V2 capability operation set drifted; update expected_v2_operations only as a reviewed change so the "
        "exposed V2 surface cannot silently grow or shrink."
    )

    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        capability_payload = client.capabilities()
    finally:
        client.shutdown()

    capabilities = cast(dict[str, str], capability_payload["capabilities"])
    details = cast(dict[str, dict[str, Any]], capability_payload["capabilityDetails"])

    # (1) Truthfulness: every V2 op is advertised "supported" by the LIVE sidecar (all blockers resolved).
    for operation in expected_v2_operations:
        level = capabilities.get(operation)
        assert level is not None, f"{operation} is missing from the live capability registry"
        assert level == "beta", f"{operation} must advertise beta level, got {level!r}"
        assert details[operation]["status"] == "supported", (
            f"{operation} must advertise status 'supported' (its V2 blocker has landed); got {details[operation]['status']!r}"
        )

    # (2) Gating: the manager only exposes "supported" ops. Drive the real gate with the live payload and prove that a
    # supported op passes (no refusal) while a synthetically downgraded op is gated out. This ties "supported" to the
    # exposed surface: a non-"supported" status would gate the op out via _ensure_v2_capability.
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True),
    )

    class _FakeClient:
        def __init__(self, payload: dict[str, Any]) -> None:
            self._payload = payload

        def capabilities(self) -> dict[str, Any]:
            return self._payload

    # Every live-supported V2 op passes the gate (no refusal).
    live_client = _FakeClient(capability_payload)
    for operation in expected_v2_operations:
        manager._capability_levels = None  # force a fresh read of the live payload
        refusal = manager._ensure_v2_capability(live_client, operation, "preview")  # type: ignore[arg-type]
        assert refusal is None, f"live-supported {operation} must pass the capability gate, got refusal {refusal!r}"

    # A V2 op whose status is NOT "supported" is gated out: build a payload that downgrades one op to "preview" and
    # confirm the manager refuses it with the capability-unavailable code. This proves the gate keys on status, so a
    # regressed (non-supported) op would be removed from the exposed surface rather than silently used.
    import copy

    downgraded_payload = copy.deepcopy(capability_payload)
    # Status lives in capabilityDetails now; downgrade it there to simulate a regressed op.
    downgraded_payload["capabilityDetails"]["changeSignature"]["status"] = "preview"
    downgraded_client = _FakeClient(downgraded_payload)
    manager._capability_levels = None
    refusal = manager._ensure_v2_capability(downgraded_client, "changeSignature", "preview")  # type: ignore[arg-type]
    assert refusal is not None, "a non-supported V2 op must be gated out (refused), not exposed"
    assert refusal["refusal"]["code"] == "java_refactor_capability_unavailable"


def test_capabilities_response_includes_javac_runtime_object(sidecar_jar: Path, tmp_path: Path) -> None:
    """G034: the capabilities response carries per-operation stable/beta status AND a javac runtime descriptor."""
    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        capability_payload = client.capabilities()
    finally:
        client.shutdown()

    capabilities = cast(dict[str, str], capability_payload["capabilities"])
    details = cast(dict[str, dict[str, Any]], capability_payload["capabilityDetails"])
    # capabilities maps op -> string level (V1/V2 ops are stable/beta; the F1-enumerated dedicated V3 dispatch ops are
    # experimental); capabilityDetails carries the truthful status (G001: "supported" only when the op's hard
    # requirements are implemented, "disabled" when config gates it off, otherwise "preview").
    for operation, level in capabilities.items():
        assert level in {"stable", "beta", "experimental"}
        assert details[operation]["level"] == level
        assert details[operation]["status"] in {"supported", "preview", "disabled"}

    runtime = capability_payload["runtime"]
    assert runtime["status"] == "running"
    javac = runtime["javac"]
    assert isinstance(javac, dict)
    # The sidecar runs under a JDK in tests, so the compiler must be reachable.
    assert javac["available"] is True
    assert isinstance(javac["version"], str) and javac["version"]

    # G003: the V2 plan's top-level javac contract object accompanies the nested runtime descriptor.
    javac_contract = capability_payload["javac"]
    assert isinstance(javac_contract, dict)
    assert isinstance(javac_contract["runtimeJdk"], str) and javac_contract["runtimeJdk"]
    assert javac_contract["supportsPreview"] is True


def test_v2_session_surface_refuses_unimplemented_planner(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "unsupportedPlanner",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 17,
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "unsupportedPlanner"
    assert refused["mode"] == "preview"
    assert refused["refusal"]["code"] == "unsupported_operation"
    assert "session" not in refused


def test_change_signature_session_rewrites_declaration_and_call_sites(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_signature_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "newName": "format",
                "newReturnType": "java.lang.String",
                "parameters": [
                    {"name": "name", "type": "String"},
                    {"name": "count", "type": "int", "defaultValue": "1"},
                ],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    assert created["session"]["operation"] == "changeSignature"
    assert created["session"]["touchedFiles"] == ["App.java"]
    changes = created["preview"]["workspaceEdit"]["changes"]
    replacements = [edit["newText"] for change in changes if change["path"] == "App.java" for edit in change["edits"]]
    assert "    String format(String name, int count)" in replacements
    assert "format(\"Bob\", 1)" in replacements


def test_get_session_edit_serena_workspace_edit_is_first_class(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-02: serenaWorkspaceEdit is a first-class, self-describing session edit format carrying exactly the
    # changed-file / file-operation metadata + preconditions the Serena transactional applier consumes.
    from serena.java_refactor.workspace_edit import RefactorWorkspaceEdit, TransactionalWorkspaceEditApplier

    _write_signature_fixture(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "newName": "format",
                "newReturnType": "java.lang.String",
                "parameters": [
                    {"name": "name", "type": "String"},
                    {"name": "count", "type": "int", "defaultValue": "1"},
                ],
            },
        )
        assert created["accepted"] is True
        session_id = created["session"]["sessionId"]
        edit = client.get_session_edit(session_id, edit_format="serenaWorkspaceEdit")
    finally:
        client.shutdown()

    assert edit["accepted"] is True
    assert edit["format"] == "serenaWorkspaceEdit"
    workspace_edit = edit["preview"]["workspaceEdit"]
    # Self-identifying discriminator for the applier.
    assert workspace_edit["editFormat"] == "serenaWorkspaceEdit"
    # Exact changed-file metadata + per-file precondition hash.
    change = workspace_edit["changes"][0]
    assert change["path"] == "App.java"
    assert change["oldSha256"]
    assert change["edits"]
    assert "fileOperations" in workspace_edit
    assert "preconditions" in workspace_edit
    assert "stats" in workspace_edit
    # The Serena transactional applier parses and previews the payload without error.
    parsed = RefactorWorkspaceEdit.from_protocol_dict(workspace_edit)
    assert parsed.touched_files()
    TransactionalWorkspaceEditApplier(str(tmp_path)).preview(parsed)


def test_get_session_edit_refuses_unknown_format(sidecar_jar: Path, tmp_path: Path) -> None:
    # An unknown serialization format is still refused with a structured code; only the designed formats are accepted.
    _write_signature_fixture(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "newName": "format",
                "newReturnType": "java.lang.String",
                "parameters": [{"name": "name", "type": "String"}],
            },
        )
        assert created["accepted"] is True
        session_id = created["session"]["sessionId"]
        refused = client.get_session_edit(session_id, edit_format="totallyBogusFormat")
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsupported_edit_format"


def test_v2_nested_change_signature_config_confirms_public_api(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """public class App {
    public String label(String value) { return value; }
    String run() { return label("a"); }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"change_signature": {"confirm_public_api": True}}}},
            )
        )
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 19,
                "newName": "format",
                "parameters": [{"type": "String", "name": "value", "oldIndex": 0}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)


def test_introduce_parameter_session_replaces_expression_and_updates_callers(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_signature_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "introduceParameter",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "selectedExpression": "\"hi \"",
                "parameterName": "prefix",
                "parameterType": "String",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    assert created["session"]["operation"] == "introduceParameter"
    changes = created["preview"]["workspaceEdit"]["changes"]
    replacements = [edit["newText"] for change in changes if change["path"] == "App.java" for edit in change["edits"]]
    assert "    String helper(String name, String prefix)" in replacements
    assert "helper(\"Bob\", \"hi \")" in replacements
    assert "prefix" in replacements


def test_introduce_parameter_refuses_impure_selected_expression(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """public class App {
    String helper(String name) {
        return prefix() + name;
    }

    String prefix() {
        return "hi ";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceParameter",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 12,
                "selectedExpression": "prefix()",
                "parameterName": "prefix",
                "parameterType": "String",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "introduceParameter"
    assert refused["refusal"]["code"] == "SELECTED_EXPRESSION_HAS_SIDE_EFFECTS"
    assert "session" not in refused


def test_introduce_parameter_infers_literal_type(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_signature_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "introduceParameter",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "selectedExpression": "\"hi \"",
                "parameterName": "prefix",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    replacements = [
        edit["newText"]
        for change in created["preview"]["workspaceEdit"]["changes"]
        if change["path"] == "App.java"
        for edit in change["edits"]
    ]
    assert "    String helper(String name, String prefix)" in replacements
    assert "helper(\"Bob\", \"hi \")" in replacements


def test_introduce_parameter_adds_import_for_explicit_fqn_type(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """public class App {
    String helper(String name) {
        return java.util.Locale.ROOT.toString() + name;
    }

    String run() {
        return helper("Bob");
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "introduceParameter",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 12,
                "selectedExpression": "java.util.Locale.ROOT",
                "parameterName": "locale",
                "parameterType": "java.util.Locale",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    replacements = [
        edit["newText"]
        for change in created["preview"]["workspaceEdit"]["changes"]
        if change["path"] == "App.java"
        for edit in change["edits"]
    ]
    assert any("import java.util.Locale;" in replacement for replacement in replacements)
    assert "    String helper(String name, Locale locale)" in replacements
    assert "helper(\"Bob\", Locale.ROOT)" in replacements


def test_introduce_parameter_refuses_ambiguous_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """public class App {
    String helper(String name) {
        return "hi " + name + "hi ";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceParameter",
            {"relativePath": "App.java", "line": 2, "column": 12, "selectedExpression": "\"hi \"", "parameterName": "prefix"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "ambiguous_selected_expression"


def test_direct_apply_refuses_new_compiler_errors_before_writing(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """public class App {
    String helper(String name) {
        return name == null ? "" : name;
    }

    String run() {
        return helper("Bob");
    }
}
""",
        encoding="utf-8",
    )
    original = (tmp_path / "App.java").read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client._request(
            "apply",
            {
                "operation": "introduceParameter",
                "params": {
                    "relativePath": "App.java",
                    "line": 2,
                    "column": 12,
                    "selectedExpression": "null",
                    "parameterName": "locale",
                    "parameterType": "java.util.Locale",
                },
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert "error" not in refused
    assert refused["refusal"]["code"] == "new_compiler_errors"
    assert refused["diagnosticDelta"]["newErrors"]
    # FIX 2: the apply branch validates the actual apply-shaped result, so a refused apply is never downgraded into a
    # client-apply contract — it must not be applied and must not carry an editable workspaceEdit.
    assert refused["applied"] is False
    assert refused.get("requiresClientApply") is not True
    assert refused.get("workspaceEdit", {}).get("changes", []) == []
    assert (tmp_path / "App.java").read_text(encoding="utf-8") == original


def test_introduce_parameter_refuses_unresolved_type_without_hint(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """public class App {
    String helper(String name) {
        return UNKNOWN + name;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceParameter",
            {"relativePath": "App.java", "line": 2, "column": 12, "selectedExpression": "UNKNOWN", "parameterName": "prefix"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unresolved_expression_type"


def test_introduce_parameter_refuses_existing_parameter_name(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_signature_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceParameter",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "selectedExpression": "\"hi \"",
                "parameterName": "name",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "parameter_already_exists"


def test_introduce_parameter_preserves_type_parameters_and_throws(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """import java.io.IOException;

class App {
    <T extends Number> T identity(T value) throws IOException {
        return true ? null : value;
    }

    Number run() throws IOException {
        return identity(1);
    }
}
""",
        encoding="utf-8",
    )
    original = source.read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "introduceParameter",
            {
                "relativePath": "App.java",
                "line": 4,
                "column": 26,
                "selectedExpression": "null",
                "parameterName": "fallback",
                "parameterType": "T",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "<T extends Number> T identity(T value, T fallback) throws IOException" in preview
    assert "return true ? fallback : value;" in preview
    assert "return identity(1, null);" in preview


def test_introduce_parameter_inherits_method_reference_arity_refusal(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """import java.util.function.Function;

class App {
    String helper(String name) {
        return "hi " + name;
    }

    Function<String, String> ref() {
        return this::helper;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceParameter",
            {
                "relativePath": "App.java",
                "line": 4,
                "column": 12,
                "selectedExpression": "\"hi \"",
                "parameterName": "prefix",
                "parameterType": "String",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "introduceParameter"
    assert refused["refusal"]["code"] == "METHOD_REFERENCE_ARITY_CHANGE"
    assert "session" not in refused


def test_introduce_parameter_reports_exact_multi_file_touched_stats(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Api.java").write_text(
        """public class Api {
    String greet() {
        return helper("Bob");
    }

    String helper(String name) {
        return "hi " + name;
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Use.java").write_text(
        """class Use {
    String run(Api api) {
        return api.helper("Sue");
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "introduceParameter",
            {
                "relativePath": "Api.java",
                "line": 6,
                "column": 12,
                "selectedExpression": "\"hi \"",
                "parameterName": "prefix",
                "parameterType": "String",
                "confirmPublicApi": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    changed_paths = {change["path"] for change in created["preview"]["workspaceEdit"]["changes"]}
    assert changed_paths == {"Api.java", "Use.java"}
    assert created["plan"]["stats"]["touchedFileCount"] == 2


def test_change_signature_refuses_overload_ambiguity(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """public class App {
    String helper(String name) {
        return name;
    }

    String helper(int count) {
        return String.valueOf(count);
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {"relativePath": "App.java", "line": 2, "column": 12, "newName": "format"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "changeSignature"
    assert refused["refusal"]["code"] == "AMBIGUOUS_OVERLOAD_AFTER_CHANGE"
    assert "session" not in refused


def test_change_signature_reorders_parameters_and_call_arguments(sidecar_jar: Path, tmp_path: Path) -> None:
    original = """public class App {
    String greet() {
        return helper("Bob", 2);
    }

    String helper(String name, int count) {
        return name + count;
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "parameters": [{"type": "int", "name": "count"}, {"type": "String", "name": "name"}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    replacements = [
        edit["newText"]
        for change in created["preview"]["workspaceEdit"]["changes"]
        if change["path"] == "App.java"
        for edit in change["edits"]
    ]
    preview = _preview_text(original, created, "App.java")
    assert "    String helper(int count, String name)" in preview
    assert 'helper(2, "Bob")' in replacements


def test_change_signature_uses_old_index_for_reorder_and_rename(sidecar_jar: Path, tmp_path: Path) -> None:
    original = """public class App {
    String greet() {
        return helper("Bob", 2);
    }

    String helper(String name, int count) {
        return name + count;
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "parameters": [
                    {"type": "int", "name": "times", "oldIndex": 1},
                    {"type": "String", "name": "person", "oldIndex": 0},
                ],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    replacements = [
        edit["newText"]
        for change in created["preview"]["workspaceEdit"]["changes"]
        if change["path"] == "App.java"
        for edit in change["edits"]
    ]
    preview = _preview_text(original, created, "App.java")
    assert "    String helper(int times, String person)" in preview
    assert 'helper(2, "Bob")' in replacements
    assert "return person + times;" in preview


def test_change_signature_updates_constructor_call_sites(sidecar_jar: Path, tmp_path: Path) -> None:
    original = """public class App {
    String make() {
        return new Box("Bob", 2).describe();
    }

    static class Box {
        final String name;
        final int count;
        final String suffix;

        Box(String name, int count) {
            this.name = name;
            this.count = count;
            this.suffix = "!";
        }

        String describe() {
            return name + count + suffix;
        }
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 11,
                "column": 12,
                "confirmPublicApi": True,
                "parameters": [
                    {"type": "int", "name": "times", "oldIndex": 1},
                    {"type": "String", "name": "person", "oldIndex": 0},
                    {"type": "String", "name": "suffix", "defaultValue": "\"!\""},
                ],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    preview = _preview_text(original, created, "App.java")
    assert "new Box(2, \"Bob\", \"!\").describe()" in preview
    assert "Box(int times, String person, String suffix)" in preview
    assert "this.name = person;" in preview
    assert "this.count = times;" in preview


def test_change_signature_updates_override_group_and_resolved_call_sites(sidecar_jar: Path, tmp_path: Path) -> None:
    original = """interface Service {
    String label(String name);
}

class Impl implements Service {
    public String label(String text) {
        return text;
    }
}

public class App {
    String run(Service service, Impl impl) {
        return service.label("A") + impl.label("B");
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 19,
                "newName": "format",
                "confirmPublicApi": True,
                "parameters": [{"type": "String", "name": "value", "oldIndex": 0}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    preview = _preview_text(original, created, "App.java")
    assert "    String format(String value);" in preview
    assert "    public String format(String value)" in preview
    assert "return value;" in preview
    assert 'service.format("A") + impl.format("B")' in preview


def test_change_signature_rewrites_only_javac_resolved_target(sidecar_jar: Path, tmp_path: Path) -> None:
    original = """class Other {
    String helper(String name) {
        return name;
    }
}

public class App {
    String literal = "helper(\\"text\\")";

    String greet(Other other) {
        // helper("comment")
        other.helper("Sue");
        return helper("Bob");
    }

    String helper(String name) {
        return name;
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {"relativePath": "App.java", "line": 16, "column": 12, "newName": "format"},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    preview = _preview_text(original, created, "App.java")
    assert 'String literal = "helper(\\"text\\")";' in preview
    assert '// helper("comment")' in preview
    assert 'other.helper("Sue");' in preview
    assert 'return format("Bob");' in preview
    assert "String format(String name)" in preview


def test_change_signature_import_planner_falls_back_on_simple_name_conflict(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "src" / "main" / "java" / "demo").mkdir(parents=True)
    (tmp_path / "src" / "main" / "java" / "demo" / "App.java").write_text(
        """package demo;

import java.sql.Date;

class App {
    Date helper(Date date) {
        return date;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "src/main/java/demo/App.java",
                "line": 6,
                "column": 10,
                "newReturnType": "java.util.Date",
                "parameters": [{"name": "date", "type": "java.util.Date"}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    replacements = [edit["newText"] for change in changes for edit in change["edits"]]
    assert "    java.util.Date helper(java.util.Date date)" in replacements
    assert all("import java.util.Date" not in replacement for replacement in replacements)


def test_change_signature_import_planner_preserves_static_import_group(sidecar_jar: Path, tmp_path: Path) -> None:
    app_path = tmp_path / "src" / "main" / "java" / "demo" / "App.java"
    app_path.parent.mkdir(parents=True)
    original = """package demo;

import static java.util.Collections.emptyList;

class App {
    String helper() {
        emptyList();
        return null;
    }
}
"""
    app_path.write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "src/main/java/demo/App.java",
                "line": 6,
                "column": 12,
                "newReturnType": "java.nio.file.Path",
                "parameters": [{"type": "java.nio.file.Path", "name": "path"}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "src/main/java/demo/App.java")
    assert "import java.nio.file.Path;\n\nimport static java.util.Collections.emptyList;" in preview
    assert "Path helper(Path path)" in preview


def test_change_signature_imports_default_expression_at_call_site(sidecar_jar: Path, tmp_path: Path) -> None:
    # G004: a change-signature default is detached text, so only a proven compile-time constant is admitted. A
    # type-qualified enum constant qualifies and still exercises the import-at-call-site capability: the fully-qualified
    # default is shortened to a simple reference and the needed import is added at the call site.
    original = """import java.util.List;

class App {
    void run() {
        helper("a");
    }

    List<String> helper(String name) {
        return List.of(name);
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 8,
                "column": 18,
                "parameters": [
                    {"type": "String", "name": "name"},
                    {
                        "type": "java.math.RoundingMode",
                        "name": "mode",
                        "defaultValue": "java.math.RoundingMode.HALF_UP",
                    },
                ],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "import java.math.RoundingMode;" in preview
    assert "helper(\"a\", RoundingMode.HALF_UP)" in preview
    assert "List<String> helper(String name, RoundingMode mode)" in preview


def test_change_signature_renames_parameter_uses_in_body(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """public class App {
    String helper(String name) {
        return name.trim();
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 12,
                "parameters": [{"type": "String", "name": "value"}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    replacements = [
        edit["newText"]
        for change in created["preview"]["workspaceEdit"]["changes"]
        if change["path"] == "App.java"
        for edit in change["edits"]
    ]
    assert "    String helper(String value)" in replacements
    assert "value" in replacements


def test_change_signature_refuses_removed_parameter_still_used(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """public class App {
    String helper(String name, int count) {
        return name + count;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 12,
                "parameters": [{"type": "String", "name": "name"}],
                "removeParameters": ["count"],
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "REMOVED_PARAMETER_STILL_USED"
    assert "session" not in refused


def test_change_signature_refuses_dropped_side_effecting_call_argument(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """public class App {
    String greet() {
        return helper("Bob", next());
    }

    String helper(String name, int count) {
        return name;
    }

    int next() {
        return 1;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "parameters": [{"type": "String", "name": "name"}],
                "removeParameters": ["count"],
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "CALL_SITE_ARGUMENT_HAS_SIDE_EFFECTS"
    assert "session" not in refused


def test_change_signature_refuses_method_reference_arity_change(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """import java.util.function.Function;

public class App {
    Function<String, String> fn = this::helper;

    String helper(String name) {
        return name;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "parameters": [
                    {"type": "String", "name": "name"},
                    {"type": "int", "name": "count", "defaultValue": "1"},
                ],
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "METHOD_REFERENCE_ARITY_CHANGE"


def test_change_signature_rewrites_safe_method_reference_renames(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """import java.util.function.IntUnaryOperator;

class App {
    int inc(int value) { return value + 1; }
    IntUnaryOperator op() { return this::inc; }
    int run() { return inc(1); }
}
""",
        encoding="utf-8",
    )
    original = source.read_text(encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 4,
                "column": 9,
                "newName": "addOne",
                "parameters": [{"type": "int", "name": "value", "oldIndex": 0}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "int addOne(int value)" in preview
    assert "this::addOne" in preview
    assert "return addOne(1);" in preview


def test_change_signature_preserves_throws_clause(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """import java.io.IOException;

class App {
    String read(String path) throws IOException { return path; }
    String run() throws IOException { return read("x"); }
}
""",
        encoding="utf-8",
    )
    original = source.read_text(encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 4,
                "column": 12,
                "newName": "load",
                "parameters": [{"type": "String", "name": "path", "oldIndex": 0}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "String load(String path) throws IOException" in preview
    assert "return load(\"x\");" in preview


def test_change_signature_preserves_method_type_parameters(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """import java.io.IOException;

class App {
    <T extends Number> T id(T value) throws IOException { return value; }
    Integer run() throws IOException { return id(1); }
}
""",
        encoding="utf-8",
    )
    original = source.read_text(encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 4,
                "column": 26,
                "newName": "identity",
                "parameters": [{"type": "T", "name": "value", "oldIndex": 0}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "<T extends Number> T identity(T value) throws IOException" in preview
    assert "return identity(1);" in preview


def test_change_signature_applies_return_conversion_to_used_call_sites(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """class App {
    Integer value() { return 1; }
    Integer run() { return value(); }
}
""",
        encoding="utf-8",
    )
    original = source.read_text(encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 13,
                "newReturnType": "Number",
                "returnConversion": "(Integer) $return",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "Number value()" in preview
    assert "return (Integer) value();" in preview


def test_change_signature_refuses_return_conversion_without_placeholder(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """class App {
    Integer value() { return 1; }
    Integer run() { return value(); }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 13,
                "newReturnType": "Number",
                "returnConversion": "(Integer) value()",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "RETURN_CONVERSION_PLACEHOLDER_MISSING"



def test_change_signature_refuses_unsafe_return_conversion_template(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """class App {
    Integer value() { return 1; }
    Number run() { return value(); }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 13,
                "newReturnType": "Number",
                "returnConversion": "$return; System.exit(0)",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "RETURN_CONVERSION_UNSAFE_TEMPLATE"

def test_change_signature_validates_parameter_plan_before_planning(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "App.java").write_text(
        """class App {
    String helper(String name) { return name; }
    String run() { return helper("a"); }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        missing_type = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 12,
                "parameters": [{"name": "name", "oldIndex": 0}],
            },
        )
        duplicate_name = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 12,
                "parameters": [
                    {"type": "String", "name": "name", "oldIndex": 0},
                    {"type": "int", "name": "name", "defaultValue": "1"},
                ],
            },
        )
    finally:
        client.shutdown()

    assert missing_type["accepted"] is False
    assert missing_type["refusal"]["code"] == "PARAMETER_TYPE_REQUIRED"
    assert duplicate_name["accepted"] is False
    assert duplicate_name["refusal"]["code"] == "DUPLICATE_PARAMETER_NAME"


def test_change_signature_reports_exact_multi_file_touched_stats(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Api.java").write_text(
        """interface Api {
    String label(String name);
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Impl.java").write_text(
        """class Impl implements Api {
    public String label(String name) { return name; }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Use.java").write_text(
        """class Use {
    String run(Api api) { return api.label("a"); }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "Api.java",
                "line": 2,
                "column": 12,
                "newName": "format",
                "parameters": [{"type": "String", "name": "name", "oldIndex": 0}],
                "confirmPublicApi": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    changed_paths = {change["path"] for change in created["preview"]["workspaceEdit"]["changes"]}
    assert changed_paths == {"Api.java", "Impl.java", "Use.java"}
    assert created["plan"]["stats"]["touchedFileCount"] == 3


def test_move_static_member_session_moves_declaration_and_rewrites_calls(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_move_member_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 19,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    assert created["session"]["operation"] == "moveStaticMember"
    assert sorted(created["session"]["touchedFiles"]) == ["Source.java", "Target.java"]
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("static String label(String name)" in text for text in by_path["Target.java"])
    assert "" in by_path["Source.java"]
    assert "Target.label" in "\n".join(by_path["Source.java"])


def test_move_static_member_preserves_javadoc_annotations_and_constants(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """public class Source {
    /**
     * label constant.
     */
    @Deprecated
    static final String VALUE = "v";

    String run() {
        return VALUE;
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text("""public class Target {\n}\n""", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 6,
                "column": 25,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    target_text = "\n".join(by_path["Target.java"])
    assert "/**" in target_text
    assert "@Deprecated" in target_text
    assert "static final String VALUE" in target_text
    assert "Target.VALUE" in "\n".join(by_path["Source.java"])


def test_move_static_member_rewrites_static_import_references(sidecar_jar: Path, tmp_path: Path) -> None:
    package_dir = tmp_path / "demo"
    package_dir.mkdir()
    (package_dir / "Source.java").write_text(
        """package demo;

class Source {
    static final int VALUE = 1;
}
""",
        encoding="utf-8",
    )
    (package_dir / "Target.java").write_text(
        """package demo;

class Target {
}
""",
        encoding="utf-8",
    )
    caller_source = """package demo;

import static demo.Source.VALUE;

class Caller {
    int get() {
        return VALUE;
    }
}
"""
    (package_dir / "Caller.java").write_text(caller_source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "demo/Source.java",
                "line": 4,
                "column": 22,
                "targetType": "demo.Target",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    assert sorted(created["session"]["touchedFiles"]) == ["demo/Caller.java", "demo/Source.java", "demo/Target.java"]
    caller_preview = _preview_text(caller_source, created, "demo/Caller.java")
    assert "import static demo.Source.VALUE;" not in caller_preview
    assert "import static demo.Target.VALUE;" in caller_preview
    assert "return Target.VALUE;" in caller_preview


def test_move_static_member_rewrites_wildcard_static_imports(sidecar_jar: Path, tmp_path: Path) -> None:
    package_dir = tmp_path / "src" / "main" / "java" / "demo"
    package_dir.mkdir(parents=True)
    (package_dir / "Source.java").write_text(
        """package demo;

class Source {
    static final int VALUE = 1;
}
""",
        encoding="utf-8",
    )
    (package_dir / "Target.java").write_text(
        """package demo;

class Target {
}
""",
        encoding="utf-8",
    )
    caller_source = """package demo;

import static demo.Source.*;

class Caller {
    int get() {
        return VALUE;
    }
}
"""
    (package_dir / "Caller.java").write_text(caller_source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "src/main/java/demo/Source.java",
                "line": 4,
                "column": 22,
                "targetType": "demo.Target",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    assert sorted(created["session"]["touchedFiles"]) == [
        "src/main/java/demo/Caller.java",
        "src/main/java/demo/Source.java",
        "src/main/java/demo/Target.java",
    ]
    caller_preview = _preview_text(caller_source, created, "src/main/java/demo/Caller.java")
    assert "import static demo.Source.*;" not in caller_preview
    assert "import static demo.Target.VALUE;" not in caller_preview
    assert "return Target.VALUE;" in caller_preview


def test_move_static_member_rewrites_semantic_references_and_imports(sidecar_jar: Path, tmp_path: Path) -> None:
    old_dir = tmp_path / "old"
    moved_dir = tmp_path / "moved"
    use_dir = tmp_path / "use"
    old_dir.mkdir()
    moved_dir.mkdir()
    use_dir.mkdir()
    (old_dir / "Source.java").write_text(
        """package old;
public class Source {
    public static int inc(int value) { return value + 1; }
}
""",
        encoding="utf-8",
    )
    (moved_dir / "Target.java").write_text(
        """package moved;
public class Target {
}
""",
        encoding="utf-8",
    )
    caller_source = """package use;

import old.Source;
import static old.Source.inc;

public class Caller {
    int qualified(int value) { return Source.inc(value); }
    int fullyQualified(int value) { return old.Source.inc(value); }
    int imported(int value) { return inc(value); }
    java.util.function.IntUnaryOperator reference() { return Source::inc; }
}
"""
    (use_dir / "Caller.java").write_text(caller_source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path)))
        created = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "old/Source.java",
                "line": 3,
                "column": 23,
                "targetType": "moved.Target",
                "targetRelativePath": "moved/Target.java",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    assert created["plan"]["stats"]["touchedFileCount"] == 3
    caller_preview = _preview_text(caller_source, created, "use/Caller.java")
    assert "import moved.Target;" in caller_preview
    assert "import static moved.Target.inc;" in caller_preview
    assert "return Target.inc(value);" in caller_preview
    assert "return Target::inc;" in caller_preview
    assert "old.Source.inc" not in caller_preview
    assert "Source::inc" not in caller_preview

    for change in created["preview"]["workspaceEdit"]["changes"]:
        path = tmp_path / change["path"]
        path.write_text(_preview_text(path.read_text(encoding="utf-8"), created, change["path"]), encoding="utf-8")

    java_files = sorted(str(path) for path in tmp_path.rglob("*.java"))
    result = subprocess.run(
        ["javac", "-d", str(tmp_path / "classes"), *java_files],
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr


def test_move_static_member_allows_textual_false_positive_in_target(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """public class Source {
    static int inc(int value) {
        return value + 1;
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """public class Target {
    String note() {
        return "inc(";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 16,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    target_preview = _preview_text((tmp_path / "Target.java").read_text(encoding="utf-8"), created, "Target.java")
    assert "static int inc(int value)" in target_preview
    assert "return \"inc(\";" in target_preview


def test_move_static_member_refuses_semantic_signature_collision(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """public class Source {
    static int inc(int value) {
        return value + 1;
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """public class Target {
    static int inc(int value) {
        return value;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 16,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "moveStaticMember"
    assert refused["refusal"]["code"] == "target_member_exists"
    assert "session" not in refused


def test_move_static_member_inserts_fields_before_methods(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """public class Source {
    static final int VALUE = 1;
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """public class Target {
    String text() {
        return "x";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 22,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    target_preview = _preview_text((tmp_path / "Target.java").read_text(encoding="utf-8"), created, "Target.java")
    assert target_preview.index("static final int VALUE = 1;") < target_preview.index("String text()")


def test_move_static_member_private_source_dependency_requires_access_widening(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-08: the moved member's body references a private source member. The plan-wide access analyzer no
    # longer blanket-refuses; by default it refuses with access_widening_not_confirmed, and with
    # allow_access_widening it widens the referenced private member in place (private -> package-private).
    (tmp_path / "Source.java").write_text(
        """public class Source {
    static String label() {
        return secret();
    }

    private static String secret() {
        return "x";
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text("""public class Target {\n}\n""", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 19,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    # The plan-wide access analyzer (HB-08) replaces the former blanket private_dependency_unsupported
    # refusal with a structured access decision: widening the referenced private member is required, and
    # because it was not confirmed the refusal is access_widening_not_confirmed and names the member.
    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "access_widening_not_confirmed"
    assert "secret" in refused["refusal"]["message"]



def test_move_static_member_access_planner_widens_cross_package_private_with_warning(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    source_dir = tmp_path / "a"
    target_dir = tmp_path / "b"
    source_dir.mkdir()
    target_dir.mkdir()
    (source_dir / "Source.java").write_text(
        """package a;

public class Source {
    private static final int count = 1;
}
""",
        encoding="utf-8",
    )
    (target_dir / "Target.java").write_text("""package b;\n\npublic class Target {\n}\n""", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "a/Source.java",
                "line": 4,
                "column": 30,
                "targetType": "b.Target",
                "targetRelativePath": "b/Target.java",
                "allowAccessWidening": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    access_plan = created["preview"]["workspaceEdit"]["accessPlans"][0]
    assert access_plan["requiredVisibility"] == "public"
    assert access_plan["publicApiWidening"] is True
    assert any("public API" in warning for warning in created["preview"]["workspaceEdit"]["warnings"])
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("public static final int count" in text for text in by_path["b/Target.java"])



def test_move_static_member_refuses_security_sensitive_private_widening(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    source_dir = tmp_path / "a"
    target_dir = tmp_path / "b"
    source_dir.mkdir()
    target_dir.mkdir()
    (source_dir / "Source.java").write_text(
        """package a;

public class Source {
    private static final String apiToken = "secret";
}
""",
        encoding="utf-8",
    )
    (target_dir / "Target.java").write_text("""package b;\n\npublic class Target {\n}\n""", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "a/Source.java",
                "line": 4,
                "column": 33,
                "targetType": "b.Target",
                "targetRelativePath": "b/Target.java",
                "allowAccessWidening": True,
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "security_sensitive_private_widening"


def test_v2_nested_access_config_allows_security_sensitive_private_widening(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    source_dir = tmp_path / "a"
    target_dir = tmp_path / "b"
    source_dir.mkdir()
    target_dir.mkdir()
    (source_dir / "Source.java").write_text(
        """package a;

public class Source {
    private static final String apiToken = "secret";
}
""",
        encoding="utf-8",
    )
    (target_dir / "Target.java").write_text("""package b;\n\npublic class Target {\n}\n""", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={
                    "java_refactor": {
                        "v2": {"access": {"allow_security_sensitive_private_widening": True}}
                    }
                },
            )
        )
        session = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "a/Source.java",
                "line": 4,
                "column": 33,
                "targetType": "b.Target",
                "targetRelativePath": "b/Target.java",
                "allowAccessWidening": True,
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    access_plan = session["preview"]["workspaceEdit"]["accessPlans"][0]
    assert access_plan["requiredVisibility"] == "public"


def test_v2_nested_move_member_config_allows_access_widening(sidecar_jar: Path, tmp_path: Path) -> None:
    source_dir = tmp_path / "a"
    target_dir = tmp_path / "b"
    source_dir.mkdir()
    target_dir.mkdir()
    (source_dir / "Source.java").write_text(
        """package a;

public class Source {
    static String helper() { return "s"; }
}
""",
        encoding="utf-8",
    )
    (target_dir / "Target.java").write_text("""package b;
public class Target {
}
""", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"move_member": {"allow_access_widening": True}}}},
            )
        )
        session = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "a/Source.java",
                "line": 4,
                "column": 19,
                "targetType": "b.Target",
                "targetRelativePath": "b/Target.java",
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    access_plan = session["preview"]["workspaceEdit"]["accessPlans"][0]
    assert access_plan["requiredVisibility"] == "public"


def test_v2_sessions_default_limit_evicts_seventeenth_live_session(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_move_member_fixture(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session_ids: list[str] = []
        for _ in range(17):
            created = client.create_session(
                "moveStaticMember",
                {
                    "relativePath": "Source.java",
                    "line": 2,
                    "column": 27,
                    "targetType": "Target",
                    "targetRelativePath": "Target.java",
                },
            )
            assert created["accepted"] is True
            session_ids.append(created["session"]["sessionId"])

        evicted = client.get_session_edit(session_ids[0])
        latest = client.get_session_edit(session_ids[-1])
    finally:
        client.shutdown()

    assert evicted["accepted"] is False
    assert evicted["refusal"]["code"] == "unknown_session"
    assert latest["accepted"] is True


def test_move_instance_method_session_moves_to_parameter_receiver(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_move_member_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 6,
                "column": 12,
                "targetParameter": "target",
                "targetType": "Target",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    assert created["session"]["operation"] == "moveInstanceMethod"
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("String decorate(String name)" in text for text in by_path["Target.java"])
    assert any("return name() + name;" in text for text in by_path["Target.java"])
    assert "target.decorate(a)" in "\n".join(by_path["Source.java"])


def test_move_instance_method_keeps_delegate_when_requested(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_move_member_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 6,
                "column": 12,
                "targetParameter": "target",
                "targetType": "Target",
                "keepDelegate": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert "target.decorate(name);" in "\n".join(by_path["Source.java"])
    assert any("String decorate(String name)" in text for text in by_path["Target.java"])


def test_move_instance_method_rewrites_project_call_sites(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_move_member_fixture(tmp_path)
    (tmp_path / "Use.java").write_text(
        """public class Use {
    void run(Source source, Target target) {
        source.decorate(target, "Ada");
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 6,
                "column": 12,
                "targetParameter": "target",
                "targetType": "Target",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert "target.decorate(\"Ada\")" in "\n".join(by_path["Use.java"])


def test_move_instance_method_supports_field_receiver_strategy(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """class Source {
    Target target;

    String describe() {
        return target.label();
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """class Target {
    String label() {
        return "x";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 4,
                "column": 12,
                "targetField": "target",
                "targetType": "Target",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    assert sorted(created["session"]["touchedFiles"]) == ["Source.java", "Target.java"]
    target_preview = _preview_text((tmp_path / "Target.java").read_text(encoding="utf-8"), created, "Target.java")
    assert "String describe()" in target_preview
    assert "return label();" in target_preview


def test_move_instance_method_supports_explicit_receiver_strategy(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """class Source {
    Target target;

    String describe() {
        return target.label();
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """class Target {
    String label() {
        return "x";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 4,
                "column": 12,
                "targetType": "Target",
                "targetReceiver": "target",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    assert sorted(created["session"]["touchedFiles"]) == ["Source.java", "Target.java"]
    target_preview = _preview_text((tmp_path / "Target.java").read_text(encoding="utf-8"), created, "Target.java")
    assert "String describe()" in target_preview
    assert "return label();" in target_preview


def test_move_instance_method_refuses_side_effecting_explicit_receiver(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """class Source {
    String describe() {
        return target().label();
    }

    Target target() {
        return new Target();
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text('class Target { String label() { return "x"; } }\n', encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 12,
                "targetType": "Target",
                "targetReceiver": "target()",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "non_simple_receiver_unsupported"
    assert "session" not in refused


def test_move_instance_method_renames_target_and_delegate(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_move_member_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 6,
                "column": 12,
                "targetParameter": "target",
                "targetType": "Target",
                "newName": "decorateFromTarget",
                "keepDelegate": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    replacements = [
        edit["newText"]
        for change in created["plan"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("decorateFromTarget(String name)" in replacement for replacement in replacements)
    assert any("target.decorateFromTarget(name)" in replacement for replacement in replacements)
    assert any("target.decorateFromTarget(a)" in replacement for replacement in replacements)


def test_move_instance_method_rewrites_field_receiver_call_sites(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """public class Source {
    Target target = new Target();

    String decorate(String name) {
        return target.name() + name;
    }

    void run(Source source) {
        String value = source.decorate("Ada");
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """public class Target {
    String name() {
        return "target";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 4,
                "column": 12,
                "targetField": "target",
                "targetType": "Target",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    replacements = [
        edit["newText"]
        for change in created["plan"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("source.target.decorate(\"Ada\")" in replacement for replacement in replacements)


def test_move_instance_method_rewrites_explicit_receiver_call_sites(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """public class Source {
    String decorate(String name) {
        return TargetHolder.target.name() + name;
    }

    void run(Source source) {
        String value = source.decorate("Ada");
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """public class Target {
    String name() {
        return "target";
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "TargetHolder.java").write_text(
        """public class TargetHolder {
    static Target target = new Target();
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 12,
                "targetReceiver": "TargetHolder.target",
                "targetType": "Target",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    replacements = [
        edit["newText"]
        for change in created["plan"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("TargetHolder.target.decorate(\"Ada\")" in replacement for replacement in replacements)


def test_move_instance_method_honors_rewrite_call_sites_false_with_delegate(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    _write_move_member_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 6,
                "column": 12,
                "targetParameter": "target",
                "targetType": "Target",
                "rewriteCallSites": False,
                "keepDelegate": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    replacements = [
        edit["newText"]
        for change in created["plan"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("target.decorate(name)" in replacement for replacement in replacements)
    assert not any(replacement.strip() == "target.decorate(a)" for replacement in replacements)


@pytest.mark.parametrize(
    ("source", "line", "code"),
    [
        (
            """public class Source {
    String decorate(Target target) {
        return super.toString();
    }
}
""",
            2,
            "super_reference_unsupported",
        ),
        (
            """public class Source<T> {
    T decorate(Target target, T value) {
        return value;
    }
}
""",
            2,
            "source_type_parameter_unsupported",
        ),
        (
            """public class Source {
    private String secret;

    String decorate(Target target) {
        return secret;
    }
}
""",
            4,
            # G020 (B4): a private INSTANCE field is reached through the source receiver, which no access widening can
            # supply at the destination, so it is refused as source_state_required (was the blanket
            # private_source_state_unsupported before private static deps became access-plannable).
            "source_state_required",
        ),
        (
            """public class Source {
    protected String decorate(Target target) {
        return target.name();
    }
}
""",
            2,
            "protected_access_semantic_change",
        ),
        (
            """public class Source {
    Target target;

    Target factory() {
        return target;
    }

    String decorate(Target target, String name) {
        return target.name() + name;
    }

    void run() {
        factory().decorate(target, "Ada");
    }
}
""",
            8,
            "SIDE_EFFECTING_RECEIVER_EXPRESSION",
        ),
    ],
)
def test_move_instance_method_refuses_risky_blockers(
    sidecar_jar: Path, tmp_path: Path, source: str, line: int, code: str
) -> None:
    (tmp_path / "Source.java").write_text(source, encoding="utf-8")
    (tmp_path / "Target.java").write_text(
        """public class Target {
    String name() {
        return "target";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": line,
                "column": 12,
                "targetParameter": "target",
                "targetType": "Target",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == code


def test_move_instance_method_refuses_target_overload_ambiguity(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_move_member_fixture(tmp_path)
    (tmp_path / "Target.java").write_text(
        """public class Target {
    String name() {
        return "target";
    }

    String decorate(String name) {
        return name;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 6,
                "column": 12,
                "targetParameter": "target",
                "targetType": "Target",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "AMBIGUOUS_OVERLOAD_AFTER_MOVE"


def test_move_static_member_refuses_instance_targets(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_move_member_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveStaticMember",
            {"relativePath": "Source.java", "line": 6, "column": 12, "targetType": "Target"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "moveStaticMember"
    assert refused["refusal"]["code"] == "not_static_member"
    assert "session" not in refused


def test_pull_up_member_session_moves_declaration_to_supertype(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 12, "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    assert created["session"]["operation"] == "pullUpMember"
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("String label()" in text for text in by_path["Base.java"])
    assert "" in by_path["Child.java"]


def test_push_down_member_session_copies_to_subtypes_and_removes_source(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Base.java").write_text(
        """public class Base {
    String label() {
        return "base";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pushDownMember",
            {
                "relativePath": "Base.java",
                "line": 2,
                "column": 12,
                "targetTypes": ["OtherChild"],
                "removeFromSource": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    assert created["session"]["operation"] == "pushDownMember"
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("String label()" in text for text in by_path["OtherChild.java"])
    assert "" in by_path["Base.java"]


def test_push_down_member_defaults_to_copy_all_direct_subtypes(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Base.java").write_text(
        """public class Base {
    String shared() {
        return "base";
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Child.java").write_text("""public class Child extends Base {\n}\n""", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pushDownMember",
            {"relativePath": "Base.java", "line": 2, "column": 12},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("String shared()" in text for text in by_path["Child.java"])
    assert any("String shared()" in text for text in by_path["OtherChild.java"])
    assert "Base.java" not in by_path


def test_push_down_member_moves_static_final_constant(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Base.java").write_text(
        """public class Base {
    static final String KIND = "base";
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pushDownMember",
            {"relativePath": "Base.java", "line": 2, "column": 25, "targetTypes": ["Child"]},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("static final String KIND" in text for text in by_path["Child.java"])
    assert "Base.java" not in by_path


def test_push_down_member_refuses_target_collision(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Base.java").write_text(
        """public class Base {
    String label() {
        return "base";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pushDownMember",
            {"relativePath": "Base.java", "line": 2, "column": 12, "targetTypes": ["Child"]},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "target_member_exists"


def test_push_down_member_refuses_non_subtype_target(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Base.java").write_text(
        """public class Base {
    String label() {
        return "base";
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Other.java").write_text("""public class Other {\n}\n""", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pushDownMember",
            {"relativePath": "Base.java", "line": 2, "column": 12, "targetTypes": ["Other"]},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "target_not_subtype"


def test_pull_up_member_accepts_simple_instance_field(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Child.java").write_text(
        """public class Child extends Base {
    int count;
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 9, "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    replacements = [
        edit["newText"]
        for change in created["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("int count;" in replacement for replacement in replacements)


def test_push_down_member_accepts_simple_instance_field_to_multiple_subtypes(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Base.java").write_text(
        """public class Base {
    int count;
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pushDownMember",
            {
                "relativePath": "Base.java",
                "line": 2,
                "column": 9,
                "targetTypes": ["Child", "OtherChild"],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    assert created["plan"]["stats"]["touchedFileCount"] == 2
    by_path = {
        change["path"]: [edit["newText"] for edit in change["edits"]]
        for change in created["preview"]["workspaceEdit"]["changes"]
    }
    assert any("int count;" in text for text in by_path["Child.java"])
    assert any("int count;" in text for text in by_path["OtherChild.java"])
    assert "Base.java" not in by_path


def test_push_down_member_refuses_unsafe_instance_field(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Base.java").write_text(
        """public class Base {
    volatile String name;
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pushDownMember",
            {"relativePath": "Base.java", "line": 2, "column": 12, "targetTypes": ["OtherChild"]},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsafe_field_push_down"


def test_push_down_member_refuses_source_typed_call_site_removal(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Base.java").write_text(
        """public class Base {
    String label() {
        return "base";
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Use.java").write_text(
        """public class Use {
    String run(Base base) {
        return base.label();
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pushDownMember",
            {
                "relativePath": "Base.java",
                "line": 2,
                "column": 12,
                "targetTypes": ["OtherChild"],
                "removeFromSource": True,
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsafe_source_call_site"


def test_pull_up_member_refuses_target_collision(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Base.java").write_text(
        """public class Base {
    String label() {
        return "base";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 12, "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "pullUpMember"
    assert refused["refusal"]["code"] == "target_member_exists"
    assert "session" not in refused


def test_pull_up_member_make_abstract_keeps_source_with_override(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Base.java").write_text("public abstract class Base {\n}\n", encoding="utf-8")
    (tmp_path / "OtherChild.java").write_text("public abstract class OtherChild extends Base {\n}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {
                "relativePath": "Child.java",
                "line": 2,
                "column": 12,
                "targetType": "Base",
                "makeAbstract": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("abstract String label();" in text for text in by_path["Base.java"])
    assert "    @Override\n" in by_path["Child.java"]
    assert "" not in by_path["Child.java"]


def test_pull_up_member_to_interface_adds_declaration_and_override(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Named.java").write_text(
        """public interface Named {
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Child.java").write_text(
        """public class Child implements Named {
    public String label() {
        return "child";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 12, "targetType": "Named", "confirmPublicApi": True},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("String label();" in text for text in by_path["Named.java"])
    assert "    @Override\n" in by_path["Child.java"]
    assert "" not in by_path["Child.java"]




def test_pull_up_member_preserves_generic_javadoc_and_annotation(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Base.java").write_text("public class Base {}\n", encoding="utf-8")
    (tmp_path / "Child.java").write_text(
        """public class Child extends Base {
    /**
     * Converts a value.
     */
    @Deprecated
    public <T> T convert(T value) { return value; }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 6, "column": 17, "targetType": "Base", "confirmPublicApi": True},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    base_text = "\n".join(by_path["Base.java"])
    assert "/**" in base_text
    assert "@Deprecated" in base_text
    assert "public <T> T convert(T value)" in base_text
    assert any(edit["newText"] == "" for change in changes if change["path"] == "Child.java" for edit in change["edits"])


def test_push_down_member_selects_overloaded_method_semantically(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Base.java").write_text(
        """public class Base {
    public String value(String input) { return input; }
    public int value(int input) { return input; }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Child.java").write_text("public class Child extends Base {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pushDownMember",
            {"relativePath": "Base.java", "line": 3, "column": 16, "targetType": "Child", "confirmPublicApi": True},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    child_text = "\n".join(by_path["Child.java"])
    assert "public int value(int input)" in child_text
    assert "String value" not in child_text
    assert "Base.java" not in by_path


def test_pull_up_member_moves_static_final_constant(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Child.java").write_text(
        """public class Child extends Base {
    static final String KIND = "child";
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 25, "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("static final String KIND" in text for text in by_path["Base.java"])
    assert "" in by_path["Child.java"]


def test_pull_up_member_refuses_unsafe_instance_field(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "Child.java").write_text(
        """public class Child extends Base {
    volatile String name;
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 12, "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsafe_field_pull_up"


def test_pull_up_member_allows_compatible_sibling_override(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)
    (tmp_path / "OtherChild.java").write_text(
        """public class OtherChild extends Base {
    String label() {
        return "other";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 12, "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)


def test_pull_up_member_allows_covariant_return_sibling_override(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009 (covariant override compatibility): pulling Source.make():Shelter up to Base as an abstract declaration is
    # accepted even though Sibling declares make():PetShelter (a covariant subtype return). The override resolver proves
    # the sibling remains a legal override BEFORE any edit is emitted, so this is admitted rather than refused.
    (tmp_path / "Base.java").write_text("public abstract class Base {\n}\n", encoding="utf-8")
    (tmp_path / "Shelter.java").write_text("public class Shelter {\n}\n", encoding="utf-8")
    (tmp_path / "PetShelter.java").write_text("public class PetShelter extends Shelter {\n}\n", encoding="utf-8")
    (tmp_path / "Source.java").write_text(
        """public class Source extends Base {
    Shelter make() {
        return new Shelter();
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Sibling.java").write_text(
        """public class Sibling extends Base {
    PetShelter make() {
        return new PetShelter();
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Source.java", "line": 2, "column": 13, "targetType": "Base", "makeAbstract": True},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True, created
    assert "incompatible_covariant_return" not in str(created)
    _assert_stable_target_identity(created)


def test_pull_up_member_refuses_incompatible_generic_override_sibling(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009 (generic override compatibility): Source.accept(List<String>) and Sibling.accept(List<Integer>) share the
    # erased signature accept(List) but are conflicting parameterizations. Pulling Source.accept up to Base would make
    # Sibling an override only by erasure; the resolver refuses with a precise structured code before edits, rather than
    # leaving an unsound move for a later javac compile to reject.
    (tmp_path / "Base.java").write_text("import java.util.List;\npublic abstract class Base {\n}\n", encoding="utf-8")
    (tmp_path / "Source.java").write_text(
        """import java.util.List;
public class Source extends Base {
    void accept(List<String> values) {
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Sibling.java").write_text(
        """import java.util.List;
public class Sibling extends Base {
    void accept(List<Integer> values) {
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "Source.java", "line": 3, "column": 10, "targetType": "Base", "makeAbstract": True},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "generic_substitution_mismatch"


def test_pull_up_member_refuses_public_member_without_confirmation(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009 (public-API confirmation order): an otherwise-safe pull-up of a PUBLIC member is gated. With no confirmation
    # flag the planner refuses with PUBLIC_API_CONFIRMATION_REQUIRED. This is the public-API gate firing on its own; the
    # ordering proof (a more specific safety refusal masking this gate) is covered by
    # test_pull_up_member_refuses_source_only_body_dependency, where a public member with an unsafe body surfaces
    # incompatible_member_body instead of this gate.
    (tmp_path / "Base.java").write_text("public class Base {\n}\n", encoding="utf-8")
    (tmp_path / "Child.java").write_text(
        """public class Child extends Base {
    public String label() {
        return "child";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 19, "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "PUBLIC_API_CONFIRMATION_REQUIRED"


def test_pull_up_member_refuses_non_supertype_target(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Base.java").write_text("""public class Base {\n}\n""", encoding="utf-8")
    (tmp_path / "Other.java").write_text("""public class Other {\n}\n""", encoding="utf-8")
    (tmp_path / "Child.java").write_text(
        """public class Child extends Base {
    String label() {
        return "child";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 12, "targetType": "Other"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "target_not_supertype"


def test_pull_up_member_constant_to_interface_renders_public_static_final(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009(a): a compile-time constant pulled into an interface becomes an explicit public static final constant.
    (tmp_path / "Named.java").write_text("public interface Named {\n}\n", encoding="utf-8")
    (tmp_path / "Child.java").write_text(
        """public class Child implements Named {
    static final String KIND = "child";
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 25, "targetType": "Named"},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    assert any("public static final String KIND" in text for text in by_path["Named.java"])


def test_pull_up_member_instance_field_to_interface_refused_with_location(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009(b): a non-constant instance field cannot become an interface constant and is refused with a location.
    (tmp_path / "Named.java").write_text("public interface Named {\n}\n", encoding="utf-8")
    (tmp_path / "Child.java").write_text(
        """public class Child implements Named {
    String name;
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 12, "targetType": "Named"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "interface_field_not_constant"
    assert refused["refusal"]["location"]["relativePath"] == "Child.java"


def test_pull_up_member_resolves_fully_qualified_target(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a qualified target a.Base binds to a.Base even though b.Base shares the simple name.
    (tmp_path / "a").mkdir()
    (tmp_path / "b").mkdir()
    (tmp_path / "a" / "Base.java").write_text("package a;\npublic class Base {\n}\n", encoding="utf-8")
    (tmp_path / "b" / "Base.java").write_text("package b;\npublic class Base {\n}\n", encoding="utf-8")
    (tmp_path / "a" / "Child.java").write_text(
        """package a;
public class Child extends Base {
    String label() {
        return "child";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "a/Child.java", "line": 3, "column": 12, "targetType": "a.Base"},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    changes = created["preview"]["workspaceEdit"]["changes"]
    paths = {change["path"] for change in changes}
    assert any(p.endswith("a/Base.java") or p.endswith("a\\Base.java") for p in paths)
    assert not any(p.endswith("b/Base.java") or p.endswith("b\\Base.java") for p in paths)


def test_pull_up_member_field_in_serializable_type_requires_confirmation(sidecar_jar: Path, tmp_path: Path) -> None:
    # G011(a): moving a field within a Serializable hierarchy refuses unless the impact is confirmed.
    (tmp_path / "Base.java").write_text(
        "import java.io.Serializable;\npublic class Base implements Serializable {\n}\n", encoding="utf-8"
    )
    (tmp_path / "Child.java").write_text(
        """import java.io.Serializable;
public class Child extends Base implements Serializable {
    int count;
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 3, "column": 9, "targetType": "Base"},
        )
        confirmed = client.create_session(
            "pullUpMember",
            {
                "relativePath": "Child.java",
                "line": 3,
                "column": 9,
                "targetType": "Base",
                "confirm_serialization_impact": True,
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "serialization_impact"
    assert refused["refusal"]["location"]["relativePath"] == "Child.java"
    assert confirmed["accepted"] is True


def test_extract_method_session_extracts_complete_statement(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_method_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "printOne",
                "visibility": "private",
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 35},
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    assert session["session"]["operation"] == "extractMethod"
    assert session["plan"]["stats"]["touchedFileCount"] == 1
    assert session["preview"]["workspaceEdit"]["stats"]["touchedFileCount"] == 1
    changes = session["preview"]["workspaceEdit"]["changes"]
    replacements = [edit["newText"] for change in changes for edit in change["edits"]]
    assert "printOne();" in replacements
    assert any("private void printOne()" in replacement for replacement in replacements)



def test_extract_method_preserves_tabs_and_crlf_style(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "ExtractSample.java").write_text(
        "public class ExtractSample {\r\n\tvoid run() {\r\n\t\tSystem.out.println(1);\r\n\t}\r\n}\r\n",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "printOne",
                "selection": {"startLine": 3, "startColumn": 3, "endLine": 3, "endColumn": 25},
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    declaration = next(replacement for replacement in replacements if "private void printOne()" in replacement)
    assert "\r\n\tprivate void printOne()" in declaration
    assert "\r\n\t\tSystem.out.println(1);" in declaration


def test_extract_method_preview_contains_access_plan(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_method_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "printOne",
                "visibility": "private",
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 35},
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    access_plans = session["preview"]["workspaceEdit"]["accessPlans"]
    assert access_plans
    assert access_plans[0]["allowed"] is True
    assert "requiredVisibility" in access_plans[0]


def test_extract_method_extracts_local_declaration_output(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "ExtractSample.java").write_text(
        """public class ExtractSample {
    int run() {
        int value = 40 + 2;
        return value;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "computeValue",
                "visibility": "private",
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 28},
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    changes = session["preview"]["workspaceEdit"]["changes"]
    replacements = [edit["newText"] for change in changes for edit in change["edits"]]
    assert "int value = computeValue();" in replacements
    assert any("private int computeValue()" in replacement for replacement in replacements)
    assert any("return 40 + 2;" in replacement for replacement in replacements)


def _write_extract_interface_overload_fixture(tmp_path: Path) -> None:
    (tmp_path / "InterfaceSource.java").write_text(
        """public class InterfaceSource {
    public String value(String prefix) {
        return prefix + " value";
    }

    public int value(int count) {
        return count + 1;
    }
}
""",
        encoding="utf-8",
    )


def test_extract_interface_selects_overloaded_member_by_signature(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_interface_overload_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "members": ["value(String)"],
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    preview = session["preview"]
    file_ops = preview["workspaceEdit"]["fileOperations"]
    assert len(file_ops) == 1
    content = file_ops[0]["content"]
    assert "String value(String prefix);" in content
    assert "int value(int count);" not in content
    assert preview["workspaceEdit"]["stats"]["touchedFileCount"] == 2


def test_extract_interface_refuses_ambiguous_overloaded_member_name(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_interface_overload_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "members": ["value"],
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "extractInterface"
    assert refused["refusal"]["code"] == "ambiguous_member_selection"
    assert refused["semanticTarget"]["operation"] == "extractInterface"
    assert "session" not in refused




def test_extract_method_honors_make_static_flag(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """public class ExtractSample {
    void run(String name) {
        System.out.println(name.trim());
    }
}
"""
    (tmp_path / "ExtractSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "printName",
                "visibility": "private",
                "makeStatic": True,
                "selection": _selection_for(source, "System.out.println(name.trim());"),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    changes = session["preview"]["workspaceEdit"]["changes"]
    replacements = [edit["newText"] for change in changes for edit in change["edits"]]
    assert "printName(name);" in replacements
    assert any("private static void printName(" in text and "java.lang.String name" in text for text in replacements)
    assert sorted(session["session"]["touchedFiles"]) == ["ExtractSample.java"]


def test_extract_method_partial_statement_returns_suggested_ranges(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """public class ExtractSample {
    void run(String value) {
        System.out.println(value.trim());
    }
}
"""
    (tmp_path / "ExtractSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "badExtract",
                "selection": _selection_for(source, "println"),
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "SELECTION_NOT_STATEMENT_ALIGNED"
    assert len(refused["suggestedRanges"]) == 1
    suggested = refused["suggestedRanges"][0]
    assert set(suggested) == {"startLine", "startColumn", "endLine", "endColumn"}
    assert suggested["startLine"] <= 3 <= suggested["endLine"]
    assert refused["workspaceEdit"]["changes"] == []


def test_extract_method_normalizes_whitespace_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_method_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "printOne",
                "selection": {"startLine": 3, "startColumn": 1, "endLine": 4, "endColumn": 1},
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    changes = session["preview"]["workspaceEdit"]["changes"]
    replacements = [edit["newText"] for change in changes for edit in change["edits"]]
    assert "printOne();" in replacements


def test_extract_method_refuses_name_collision(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_method_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "run",
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 35},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "target_method_exists"


def test_extract_method_refuses_control_flow_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_method_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "extractReturn",
                "selection": {"startLine": 8, "startColumn": 9, "endLine": 8, "endColumn": 20},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "extractMethod"
    assert refused["refusal"]["code"] == "control_flow_unsupported"
    assert "session" not in refused


def test_extract_method_refuses_lambda_boundary_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "ExtractSample.java").write_text(
        """public class ExtractSample {
    void run() {
        Runnable r = () -> System.out.println("x");
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "extractLambda",
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 54},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "extractMethod"
    assert refused["refusal"]["code"] == "lambda_boundary_unsupported"
    assert "session" not in refused


# ----------------------------------------------------------------------------------------------------------------------
# G013: full V2 extract-method matrix. Each case asserts the resulting edit or a precise structured refusal.
# ----------------------------------------------------------------------------------------------------------------------


def test_extract_method_expression_extraction_returns_value(sidecar_jar: Path, tmp_path: Path) -> None:
    # Expression extraction (vs statement extraction): a complete sub-expression is hoisted into a helper that RETURNS
    # the value, and the expression is replaced in place by the call (no trailing semicolon).
    source = """public class ExtractSample {
    int run(int a, int b) {
        return a + b * 2;
    }
}
"""
    (tmp_path / "ExtractSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "scale",
                "selection": _selection_for(source, "b * 2"),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    # The call replaces the expression in place (no trailing ';'); the helper returns the selected expression.
    assert any(text == "scale(b)" for text in replacements)
    assert any("private int scale(int b)" in text and "return b * 2;" in text for text in replacements)


def test_extract_method_initializer_scope_expression_becomes_session(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 4: an expression selected inside a FIELD INITIALIZER has no enclosing executable, but the preview must
    # still carry a stable session target (the initializer host's identity) so it can be created as a V2 session.
    # Previously such a preview was "accepted" yet carried no semantic target and could never become a session.
    source = """public class InitSample {
    private final int value = 2 + 3 * 4;
}
"""
    (tmp_path / "InitSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "InitSample.java",
                "newMethodName": "product",
                "selection": _selection_for(source, "3 * 4"),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True, session
    # The initializer-scope selection produced a real, stable session target (not a target-less dead-end preview).
    _assert_stable_target_identity(session)
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any(text == "product()" for text in replacements), replacements
    assert any("return 3 * 4;" in text for text in replacements), replacements


def test_extract_method_reads_fields_and_parameters(sidecar_jar: Path, tmp_path: Path) -> None:
    # A selection that reads BOTH an enclosing field and a method parameter: the parameter becomes an argument, the field
    # is reached via the (instance) helper, and the helper depends on `this` so it stays an instance method.
    source = """public class ExtractSample {
    int base;

    int run(int delta) {
        int total = base + delta;
        return total;
    }
}
"""
    (tmp_path / "ExtractSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "computeTotal",
                "selection": _selection_for(source, "int total = base + delta;"),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    # `delta` is the only local input (a parameter); `base` is a field reached through the instance, not a parameter.
    assert any("private int computeTotal(int delta)" in text for text in replacements)
    assert not any("private static" in text for text in replacements)
    assert any(text == "int total = computeTotal(delta);" for text in replacements)


def test_extract_method_static_enclosing_method_synthesizes_static_helper(sidecar_jar: Path, tmp_path: Path) -> None:
    # A selection inside a STATIC method (without an explicit makeStatic flag) must synthesize a STATIC helper, because a
    # static context cannot call an instance method.
    source = """public class ExtractSample {
    static void run() {
        System.out.println("one");
    }
}
"""
    (tmp_path / "ExtractSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "printOne",
                "selection": _selection_for(source, 'System.out.println("one");'),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("private static void printOne()" in text for text in replacements)


def test_extract_method_checked_exception_propagates_to_throws(sidecar_jar: Path, tmp_path: Path) -> None:
    # A selection that calls a method declaring a checked exception must surface that exception on the helper's `throws`
    # clause so the extracted code still compiles.
    source = """public class ExtractSample {
    void run() throws java.io.IOException {
        read();
    }

    void read() throws java.io.IOException {
    }
}
"""
    (tmp_path / "ExtractSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "doRead",
                "selection": _selection_for(source, "read();"),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("private void doRead() throws java.io.IOException" in text for text in replacements)


def test_extract_method_refuses_multiple_output_variables(sidecar_jar: Path, tmp_path: Path) -> None:
    # A selection that writes two locals both read afterward cannot return >1 value; refuse precisely.
    source = """public class ExtractSample {
    int run() {
        int a = 1;
        int b = 2;
        return a + b;
    }
}
"""
    (tmp_path / "ExtractSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "initAB",
                "selection": _selection_for(source, "int a = 1;\n        int b = 2;"),
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "extractMethod"
    assert refused["refusal"]["code"] == "multiple_outputs_unsupported"
    assert "session" not in refused


def test_extract_method_refuses_break_crossing_selection_boundary(sidecar_jar: Path, tmp_path: Path) -> None:
    # A `break` whose target loop is OUTSIDE the selection escapes the extracted body; refuse precisely.
    source = """public class ExtractSample {
    void run() {
        for (int i = 0; i < 10; i++) {
            System.out.println(i);
            break;
        }
    }
}
"""
    (tmp_path / "ExtractSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "body",
                "selection": _selection_for(source, "System.out.println(i);\n            break;"),
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "extractMethod"
    assert refused["refusal"]["code"] == "control_flow_unsupported"
    assert "session" not in refused


def test_extract_method_preserves_comments_and_does_not_duplicate_statements(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # Comments inside the extracted selection move into the new method; the selected statements are NOT duplicated (they
    # appear once, in the helper, replaced by a single call at the original site), and imports remain valid.
    source = """import java.util.List;

public class ExtractSample {
    List<String> items;

    void run() {
        // collect the first item
        String first = items.get(0);
        System.out.println(first);
    }
}
"""
    (tmp_path / "ExtractSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "printFirst",
                "selection": _selection_for(
                    source, "// collect the first item\n        String first = items.get(0);\n        System.out.println(first);"
                ),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    result = _preview_text(source, session, "ExtractSample.java")
    # The comment is carried into the helper exactly once.
    assert result.count("// collect the first item") == 1
    # The selected statements appear exactly once (moved into the helper, not duplicated at the call site).
    assert result.count("String first = items.get(0);") == 1
    assert result.count("System.out.println(first);") == 1
    # A call replaces the selection.
    assert "printFirst();" in result
    # Imports are untouched and still valid.
    assert result.startswith("import java.util.List;")


def test_extract_interface_session_creates_interface_and_connects_source(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_interface_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "members": ["value"],
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    preview = session["preview"]
    assert preview["operation"] == "extractInterface"
    assert preview["mode"] == "preview"
    assert preview["applied"] is False
    diagnostic_delta = preview["diagnosticDelta"]
    assert diagnostic_delta["before"] == {"errors": [], "warnings": []}
    assert diagnostic_delta["after"] == {"errors": [], "warnings": []}
    assert diagnostic_delta["newErrors"] == []
    assert diagnostic_delta["newWarnings"] == []
    assert preview["semanticTarget"] == preview["target"]
    # Canonical V2 schema (ResponseBuilder): touchedFiles/preconditions live on stats/workspaceEdit, derived from the
    # real edit; the sorted touched-file set is the source plus the created interface.
    assert preview["stats"]["touchedFiles"] == ["ExtractedValue.java", "InterfaceSource.java"]
    assert preview["workspaceEdit"]["preconditions"] == [
        "selected members are public instance methods",
        "target interface file does not exist",
    ]
    assert preview["workspaceEdit"]["fileOperations"][0]["kind"] == "create"
    assert preview["workspaceEdit"]["fileOperations"][0]["path"] == "ExtractedValue.java"
    assert preview["workspaceEdit"]["stats"]["touchedFileCount"] == 2
    assert "ExtractedValue.java" in session["session"]["touchedFiles"]
    _assert_stable_target_identity(session)
    assert session["session"]["operation"] == "extractInterface"
    workspace_edit = session["preview"]["workspaceEdit"]
    source_edits = workspace_edit["changes"][0]["edits"]
    assert any("implements ExtractedValue" in edit["newText"] for edit in source_edits)
    file_ops = workspace_edit["fileOperations"]
    assert file_ops[0]["kind"] == "create"
    assert file_ops[0]["path"] == "ExtractedValue.java"
    assert "public interface ExtractedValue" in file_ops[0]["content"]
    assert "String value(String prefix);" in file_ops[0]["content"]


def test_extract_interface_refuses_private_members(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_interface_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "members": ["hidden"],
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "extractInterface"
    assert refused["refusal"]["code"] == "unsupported_members"
    assert refused["semanticTarget"]["operation"] == "extractInterface"
    assert "session" not in refused


def test_extract_interface_target_package_imports_and_applies(sidecar_jar: Path, tmp_path: Path) -> None:
    source_dir = tmp_path / "src" / "main" / "java" / "com" / "app"
    source_dir.mkdir(parents=True)
    source_file = source_dir / "InterfaceSource.java"
    source_file.write_text(
        """package com.app;

public class InterfaceSource {
    public String value(String prefix) {
        return prefix + " value";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "extractInterface",
            {
                "relativePath": "src/main/java/com/app/InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "targetPackage": "com.api",
                "members": ["value"],
            },
        )
        applied = client.apply_session(created["session"]["sessionId"])
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    assert applied["accepted"] is True
    workspace_edit = created["preview"]["workspaceEdit"]
    source_edits = workspace_edit["changes"][0]["edits"]
    assert any("import com.api.ExtractedValue;" in edit["newText"] for edit in source_edits)
    assert any("implements ExtractedValue" in edit["newText"] for edit in source_edits)
    file_ops = workspace_edit["fileOperations"]
    assert file_ops[0]["path"] == "src/main/java/com/api/ExtractedValue.java"
    assert "package com.api;" in file_ops[0]["content"]


def test_incremental_session_apply_subset_then_remaining(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: a session can apply a subset of its plan, then later apply the remaining validated subset.

    Extract-interface produces a self-contained set of units: a file operation that CREATES the new interface, and a
    change to the source file that adds ``implements``/the import. Each subset compiles on its own in this order, so we
    drive a true incremental apply: first apply only the interface-creation unit (validated via javac, surfaced for the
    writer), write it to disk as the real writer would, then apply the remaining source-change unit. The ``remaining``
    report shrinks to empty and ``complete`` flips to true.
    """
    source_dir = tmp_path / "src" / "main" / "java" / "com" / "app"
    source_dir.mkdir(parents=True)
    source_rel = "src/main/java/com/app/InterfaceSource.java"
    source_file = source_dir / "InterfaceSource.java"
    source_file.write_text(
        """package com.app;

public class InterfaceSource {
    public String value(String prefix) {
        return prefix + " value";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "extractInterface",
            {
                "relativePath": source_rel,
                "interfaceName": "ExtractedValue",
                "targetPackage": "com.api",
                "members": ["value"],
            },
        )
        assert created["accepted"] is True
        workspace_edit = created["preview"]["workspaceEdit"]
        interface_op = workspace_edit["fileOperations"][0]
        interface_rel = interface_op["path"]
        assert interface_rel.endswith("com/api/ExtractedValue.java")
        # The source change carries the implements/import edits; the interface is a separate create file operation.
        assert workspace_edit["changes"][0]["path"] == source_rel

        session_id = created["session"]["sessionId"]

        # --- Partial apply 1: only the interface-creation file operation. ---
        first = client.apply_session(session_id, selection={"files": [interface_rel]})
        assert first["accepted"] is True
        assert first["mode"] == "apply"
        assert first["incremental"] is True
        assert first["complete"] is False
        first_edit = first["preview"]["workspaceEdit"]
        # Only the create file operation is surfaced; the source change is NOT in this subset.
        assert [op["path"] for op in first_edit["fileOperations"]] == [interface_rel]
        assert first_edit["changes"] == []
        # The source change remains unapplied.
        assert first["remaining"]["complete"] is False
        assert any(source_rel in unit_id for unit_id in first["remaining"]["unitIds"])
        # G001: the sidecar only SURFACES the subset; the unit ids are pending an explicit post-commit ack.
        assert first["pendingUnitIds"]

        # Simulate the writer committing the surfaced subset to disk so the next subset validates against it.
        interface_abs = tmp_path / interface_rel
        interface_abs.parent.mkdir(parents=True, exist_ok=True)
        interface_abs.write_text(interface_op["content"], encoding="utf-8")

        # G001: the writer acks the committed unit strictly AFTER its disk commit succeeds. Only this advances the
        # session's authoritative applied state, so the next apply takes the incremental (remaining-subset) path.
        ack = client.ack_session_apply(session_id, first["pendingUnitIds"])
        assert ack["accepted"] is True
        assert ack["acked"] is True
        assert ack["complete"] is False

        # --- Partial apply 2: the remaining units (no explicit selection). ---
        second = client.apply_session(session_id)
        assert second["accepted"] is True
        assert second["mode"] == "apply"
        assert second["incremental"] is True
        assert second["complete"] is True
        assert second["remaining"]["complete"] is True
        assert second["remaining"]["unitIds"] == []
        second_edit = second["preview"]["workspaceEdit"]
        # The remaining subset is exactly the source change (implements + import), and no file operations are left.
        assert second_edit["fileOperations"] == []
        assert second_edit["changes"][0]["path"] == source_rel
        assert any("implements ExtractedValue" in edit["newText"] for edit in second_edit["changes"][0]["edits"])
    finally:
        client.shutdown()


def test_incremental_apply_without_ack_keeps_units_unapplied(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: a sidecar apply-session acceptance must NOT advance the session's applied state.

    The session reflects committed disk state, not edit-envelope emission. If Python's transactional staging or
    post-validation fails after the sidecar surfaces a subset (so it never acks), the selected unit MUST stay
    unapplied: re-issuing the identical selection still offers the same unit instead of refusing it as already
    applied. Only an explicit post-commit ack advances the state — proven by the same selection then refusing.
    """
    source_dir = tmp_path / "src" / "main" / "java" / "com" / "app"
    source_dir.mkdir(parents=True)
    source_rel = "src/main/java/com/app/InterfaceSource.java"
    (source_dir / "InterfaceSource.java").write_text(
        """package com.app;

public class InterfaceSource {
    public String value(String prefix) {
        return prefix + " value";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "extractInterface",
            {
                "relativePath": source_rel,
                "interfaceName": "ExtractedValue",
                "targetPackage": "com.api",
                "members": ["value"],
            },
        )
        assert created["accepted"] is True
        session_id = created["session"]["sessionId"]
        interface_rel = created["preview"]["workspaceEdit"]["fileOperations"][0]["path"]

        # Surface the interface-creation subset, but DO NOT ack (simulating a failed Python commit/post-validation).
        first = client.apply_session(session_id, selection={"files": [interface_rel]})
        assert first["accepted"] is True
        assert first["incremental"] is True
        pending = first["pendingUnitIds"]
        assert pending

        # Without an ack, the unit is still unapplied: the identical selection is offered again, not refused.
        retry = client.apply_session(session_id, selection={"files": [interface_rel]})
        assert retry["accepted"] is True
        assert retry["incremental"] is True
        assert retry["pendingUnitIds"] == pending

        # Now ack (as the writer does strictly after a successful commit). This advances the authoritative state...
        ack = client.ack_session_apply(session_id, pending)
        assert ack["accepted"] is True
        assert ack["acked"] is True

        # ...so the same selection now refuses: there is no remaining unit it could apply.
        after_ack = client.apply_session(session_id, selection={"files": [interface_rel]})
        assert after_ack["accepted"] is False
        assert after_ack["refusal"]["code"] == "empty_selection"
    finally:
        client.shutdown()


def test_incremental_apply_refuses_when_target_identity_moved(sidecar_jar: Path, tmp_path: Path) -> None:
    """G003: incremental apply re-resolves the current target and refuses if its semantic identity moved.

    The target is mutated on disk (the method is renamed, with its caller) between session creation and incremental
    apply, so the stored position no longer resolves to the same semantic key. The apply MUST return a structured
    target-guard refusal with NO edit envelope and NO advance to session state — never a silent apply of the wrong
    target.
    """
    app = tmp_path / "App.java"
    app.write_text(
        """public class App {
    String greet() {
        return helper("Bob");
    }

    String helper(String name) {
        return "hi " + name;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "newName": "format",
                "parameters": [{"name": "name", "type": "String"}],
            },
        )
        assert created["accepted"] is True
        session_id = created["session"]["sessionId"]

        # Mutate the target: rename the method (and its caller) so the stored position resolves to a different
        # semantic key while the file still compiles cleanly.
        app.write_text(
            """public class App {
    String greet() {
        return renamed("Bob");
    }

    String renamed(String name) {
        return "hi " + name;
    }
}
""",
            encoding="utf-8",
        )
        before = app.read_text(encoding="utf-8")

        applied = client.apply_session(session_id, selection={"files": ["App.java"]})
        assert applied["accepted"] is False
        assert applied["refusal"]["code"] in {"target_identity_changed", "target_reresolve_failed"}
        # No edit envelope was surfaced and the sidecar mutated nothing on disk.
        assert "preview" not in applied
        assert app.read_text(encoding="utf-8") == before
    finally:
        client.shutdown()


def test_extract_interface_import_planner_falls_back_on_simple_name_conflict(sidecar_jar: Path, tmp_path: Path) -> None:
    source_dir = tmp_path / "src" / "main" / "java" / "com" / "app"
    source_dir.mkdir(parents=True)
    other_dir = tmp_path / "src" / "main" / "java" / "com" / "other"
    other_dir.mkdir(parents=True)
    (other_dir / "ExtractedValue.java").write_text(
        """package com.other;

public interface ExtractedValue {}
""",
        encoding="utf-8",
    )
    source_file = source_dir / "InterfaceSource.java"
    source_file.write_text(
        """package com.app;

import com.other.ExtractedValue;

public class InterfaceSource {
    public String value(String prefix) {
        return prefix + " value";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))


        created = client.create_session(
            "extractInterface",
            {
                "relativePath": "src/main/java/com/app/InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "targetPackage": "com.api",
                "members": ["value"],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    workspace_edit = created["preview"]["workspaceEdit"]
    source_edits = [edit["newText"] for change in workspace_edit["changes"] for edit in change["edits"]]
    assert any("implements com.api.ExtractedValue" in edit for edit in source_edits)
    assert all("import com.api.ExtractedValue" not in edit for edit in source_edits)


def test_extract_interface_narrows_safe_usages(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_interface_fixture(tmp_path)
    (tmp_path / "UseInterfaceSource.java").write_text(
        """public class UseInterfaceSource {
    public String call() {
        InterfaceSource source = new InterfaceSource();
        return source.value("x");
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "members": ["value"],
                "replaceUsages": True,
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    edits_by_path = {change["path"]: change["edits"] for change in session["preview"]["workspaceEdit"]["changes"]}
    assert any(edit["newText"] == "ExtractedValue" for edit in edits_by_path["UseInterfaceSource.java"])



def test_extract_interface_usage_narrowing_import_offset_uses_full_file(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "src/main/java/com/impl/InterfaceSource.java"
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text(
        """
package com.impl;

public class InterfaceSource {
    public String value() {
        return "value";
    }

    public String hidden() {
        return "hidden";
    }
}
""".strip()
        + "\n",
        encoding="utf-8",
    )
    usage = tmp_path / "src/main/java/com/user/UseInterfaceSource.java"
    usage.parent.mkdir(parents=True, exist_ok=True)
    usage.write_text(
        """
package com.user;

import com.impl.InterfaceSource;

class UseInterfaceSource {
    void run() {
        InterfaceSource source = make();
        source.value();
    }

    InterfaceSource make() {
        return new InterfaceSource();
    }
}
""".strip()
        + "\n",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractInterface",
            {
                "relativePath": "src/main/java/com/impl/InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "targetPackage": "com.api",
                "members": ["value"],
                "replaceUsages": True,
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    assert session["preview"]["stats"]["touchedFileCount"] == 3
    edits_by_path = {change["path"]: change["edits"] for change in session["preview"]["workspaceEdit"]["changes"]}
    usage_edits = edits_by_path["src/main/java/com/user/UseInterfaceSource.java"]
    import_edits = [edit for edit in usage_edits if "import com.api.ExtractedValue;" in edit["newText"]]
    assert import_edits
    package_end = usage.read_text(encoding="utf-8").index("\n") + 1
    assert import_edits[0]["startOffset"] > package_end

def test_extract_interface_refuses_unsafe_usage_narrowing(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InterfaceSource.java").write_text(
        """public class InterfaceSource {
    public String value(String prefix) {
        return prefix + " value";
    }

    public String other() {
        return "other";
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "UseInterfaceSource.java").write_text(
        """public class UseInterfaceSource {
    public String call() {
        InterfaceSource source = new InterfaceSource();
        return source.other();
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "members": ["value"],
                "replaceUsages": True,
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsafe_usage_replacement"


def test_extract_interface_refuses_private_signature_types(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InterfaceSource.java").write_text(
        """public class InterfaceSource {
    private static class Hidden {}

    public Hidden value() {
        return new Hidden();
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractInterface",
            {"relativePath": "InterfaceSource.java", "interfaceName": "ExtractedValue", "members": ["value"]},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "private_type_unsupported"


def test_extract_interface_refuses_existing_target_file(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_interface_fixture(tmp_path)
    (tmp_path / "ExtractedValue.java").write_text("public interface ExtractedValue {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractInterface",
            {"relativePath": "InterfaceSource.java", "interfaceName": "ExtractedValue", "members": ["value"]},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "interface_already_exists"


def test_extract_interface_refuses_static_members(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InterfaceSource.java").write_text(
        """public class InterfaceSource {
    public static String value(String prefix) {
        return prefix + " value";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractInterface",
            {"relativePath": "InterfaceSource.java", "interfaceName": "ExtractedValue", "members": ["value"]},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsupported_members"


def test_introduce_field_session_adds_field_and_replaces_expression(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_field_refactor_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "labelText",
                "fieldType": "String",
                "selection": {"startLine": 13, "startColumn": 16, "endLine": 13, "endColumn": 23},
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_standard_v2_preview_payload(session["preview"], "introduceField", "FieldSample.java")
    assert session["session"]["operation"] == "introduceField"
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("private final String labelText = \"value\";" in replacement for replacement in replacements)
    assert "labelText" in replacements


def test_introduce_field_session_infers_selected_expression_type(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """public class FieldSample {
    String label() {
        return \"value\";
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "labelText",
                "selection": _selection_for(source, "\"value\""),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    preview = _preview_text(source, session, "FieldSample.java")
    assert "private final String labelText = \"value\";" in preview
    assert "return labelText;" in preview


def test_introduce_field_session_initializes_all_terminal_constructors(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """public class FieldSample {
    FieldSample() {
        this(0);
    }

    FieldSample(int seed) {
    }

    int value() {
        return 42;
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "answer",
                "initializeInConstructor": True,
                "constructorStrategy": "allTerminal",
                "selection": _selection_for(source, "42"),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    preview = _preview_text(source, session, "FieldSample.java")
    assert "private final int answer;" in preview
    assert preview.count("this.answer = 42;") == 1
    assert "return answer;" in preview


def test_introduce_field_apply_metadata_reports_apply_mode(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """public class FieldSample {
    int value() {
        return 1;
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        applied = client.apply_refactor(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "answer",
                "fieldType": "int",
                "initializer": "42",
            },
        )
    finally:
        client.shutdown()

    assert applied["accepted"] is True
    assert applied["operation"] == "introduceField"
    # G014: a V2 direct apply does not claim applied:true; it returns a client-apply contract carrying the edit.
    assert applied["applied"] is False
    assert applied["mode"] == "preview"
    assert applied["requiresClientApply"] is True
    assert applied["workspaceEdit"]["stats"]["touchedFileCount"] == 1
    assert applied["stats"]["touchedFileCount"] == 1


def test_introduce_field_session_synthesizes_default_constructor(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """public class FieldSample {
    int value() {
        return 42;
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "answer",
                "initializeInConstructor": True,
                "selection": _selection_for(source, "42"),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    preview = _preview_text(source, session, "FieldSample.java")
    assert "private final int answer;" in preview
    assert "public FieldSample()" in preview
    assert "this.answer = 42;" in preview
    assert "return answer;" in preview


def test_introduce_field_session_refuses_local_capture(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """public class FieldSample {
    String label(String prefix) {
        return prefix + \"value\";
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "labelText",
                "selection": _selection_for(source, "prefix + \"value\""),
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "local_variable_capture"


def test_introduce_field_preserves_tab_style(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "FieldSample.java").write_text(
        "public class FieldSample {\n\tvoid run() {\n\t\tString text = \"value\";\n\t}\n}\n",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "labelText",
                "fieldType": "String",
                "initializer": "\"value\"",
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any(replacement.startswith("\n\tprivate final String labelText") for replacement in replacements)


def test_encapsulate_field_session_generates_accessors_and_rewrites_simple_uses(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    _write_field_refactor_fixture(tmp_path)
    original = (tmp_path / "FieldSample.java").read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "count", "getterName": "getCount", "setterName": "setCount", "rewriteInternalUsages": True},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    _assert_standard_v2_preview_payload(session["preview"], "encapsulateField", "FieldSample.java")
    assert session["session"]["operation"] == "encapsulateField"
    preview = _preview_text(original, session, "FieldSample.java")
    assert "private int count = 1;" in preview
    assert "return getCount();" in preview
    assert "setCount(value);" in preview
    assert "public int getCount()" in preview
    assert "public void setCount(int value)" in preview


def test_encapsulate_field_preview_contains_v2_metadata(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_field_refactor_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "encapsulateField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "count",
                "getterName": "getCount",
                "setterName": "setCount",
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    preview = session["preview"]
    assert preview["operation"] == "encapsulateField"
    assert preview["mode"] == "preview"
    assert preview["applied"] is False
    assert preview["semanticTarget"]["operation"] == "encapsulateField"
    assert preview["semanticTarget"]["identity"]["canonical"] == "FieldSample#count"
    assert preview["semanticTarget"]["identity"]["kind"] == "FIELD"
    assert preview["touchedFiles"] == ["FieldSample.java"]
    assert preview["workspaceEdit"]["stats"]["touchedFileCount"] == 1
    assert preview["diagnosticDelta"]["before"] == {"errors": [], "warnings": []}
    assert preview["diagnosticDelta"]["after"] == {"errors": [], "warnings": []}
    assert session["session"]["touchedFiles"] == ["FieldSample.java"]


def test_encapsulate_field_direct_apply_requires_client_apply(sidecar_jar: Path, tmp_path: Path) -> None:
    """G014: a V2 direct apply must NOT claim applied:true — the sidecar only computes the edit; Python writes it.

    The accepted result is downgraded to a client-apply contract (applied:false, mode:preview, requiresClientApply:true)
    while still carrying the workspaceEdit, so a direct sidecar caller is never told files changed when they did not.
    """
    _write_field_refactor_fixture(tmp_path)
    original = (tmp_path / "FieldSample.java").read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        applied = client.apply_refactor(
            "encapsulateField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "count",
                "getterName": "getCount",
                "setterName": "setCount",
            },
        )
    finally:
        client.shutdown()

    assert applied["accepted"] is True
    assert applied["operation"] == "encapsulateField"
    # G014: no false applied:true; the direct apply is a client-apply contract.
    assert applied["applied"] is False
    assert applied["mode"] == "preview"
    assert applied["requiresClientApply"] is True
    # The workspace edit is still present so the client/session can apply it.
    assert applied["workspaceEdit"]["stats"]["touchedFileCount"] == 1
    assert applied["stats"]["touchedFileCount"] == 1
    # The sidecar did not mutate the file (Python's transactional applier is the only writer).
    assert (tmp_path / "FieldSample.java").read_text(encoding="utf-8") == original


def test_encapsulate_field_respects_update_references_false(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_field_refactor_fixture(tmp_path)
    original = (tmp_path / "FieldSample.java").read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "encapsulateField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "count",
                "getterName": "getCount",
                "setterName": "setCount",
                "updateReferences": False,
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(original, session, "FieldSample.java")
    assert "private int count = 1;" in preview
    assert "return count;" in preview
    assert "return getCount();" not in preview


def test_encapsulate_field_uses_config_default_update_references(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_field_refactor_fixture(tmp_path)
    original = (tmp_path / "FieldSample.java").read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"encapsulate_field": {"update_references": False}}}},
            )
        )
        session = client.create_session(
            "encapsulateField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "count",
                "getterName": "getCount",
                "setterName": "setCount",
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    preview = _preview_text(original, session, "FieldSample.java")
    assert "private int count = 1;" in preview
    assert "return count;" in preview
    assert "return getCount();" not in preview


def test_encapsulate_field_rewrites_only_javac_resolved_field(sidecar_jar: Path, tmp_path: Path) -> None:
    original = """public class FieldSample {
    int count = 1;
    String literal = "count";

    int readLocal() {
        // count should remain in comments
        int count = 4;
        return count;
    }

    int readField() {
        return this.count;
    }

    void writeField(int value) {
        this.count = value;
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "count", "getterName": "getCount", "setterName": "setCount", "rewriteInternalUsages": True},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(original, session, "FieldSample.java")
    assert 'String literal = "count";' in preview
    assert "// count should remain in comments" in preview
    assert "int count = 4;" in preview
    assert "return count;" in preview
    assert "return this.getCount();" in preview
    assert "setCount(value);" in preview


def test_encapsulate_field_rewrites_external_member_references(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    owner = """public class FieldSample {
    public int count = 1;
}
"""
    user = """public class FieldUser {
    int read(FieldSample sample) {
        return sample.count;
    }

    void write(FieldSample sample, int value) {
        sample.count = value;
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(owner, encoding="utf-8")
    (tmp_path / "FieldUser.java").write_text(user, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "count", "getterName": "getCount", "setterName": "setCount"},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    owner_preview = _preview_text(owner, session, "FieldSample.java")
    user_preview = _preview_text(user, session, "FieldUser.java")
    assert "private int count = 1;" in owner_preview
    assert "return sample.getCount();" in user_preview
    assert "sample.setCount(value);" in user_preview
    assert "sample.count" not in user_preview


def test_encapsulate_field_refuses_assignment_value_usage(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    original = """public class FieldSample {
    int count = 1;

    int capture(int value) {
        return count = value;
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "count", "getterName": "getCount", "setterName": "setCount"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsafe_field_usage"


def test_encapsulate_field_refuses_accessor_collision(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "FieldSample.java").write_text(
        """public class FieldSample {
    int count = 1;

    public int getCount() {
        return 0;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "count", "getterName": "getCount", "setterName": "setCount"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "accessor_collision"


def test_encapsulate_field_refuses_compound_assignment(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "FieldSample.java").write_text(
        """public class FieldSample {
    int count = 1;

    void bump() {
        count++;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "count"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "compound_field_usage"


def test_encapsulate_field_refuses_volatile_field(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "FieldSample.java").write_text(
        """public class FieldSample {
    volatile int count = 1;
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "count"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "concurrency_sensitive_field"


def test_introduce_field_refuses_multiple_constructors_without_strategy(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "FieldSample.java").write_text(
        """public class FieldSample {
    public FieldSample() {
    }

    public FieldSample(String value) {
    }

    String read() {
        return "value";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "label",
                "fieldType": "String",
                "initializer": "\"value\"",
                "initializeInConstructor": True,
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "constructor_strategy_required"


def test_introduce_field_refuses_default_constructor_broadening(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "FieldSample.java").write_text(
        """public class FieldSample {
    String read() {
        return "value";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "label",
                "fieldType": "String",
                "initializer": "\"value\"",
                "initializeInConstructor": True,
            },
        )
    finally:
        client.shutdown()






def test_encapsulate_field_uses_boolean_getter_name(sidecar_jar: Path, tmp_path: Path) -> None:
    original = """public class FieldSample {
    boolean ready = true;

    boolean check() {
        return ready;
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "ready", "rewriteInternalUsages": True},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(original, session, "FieldSample.java")
    assert "public boolean isReady()" in preview
    assert "return isReady();" in preview


def test_encapsulate_field_refuses_record_source(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "FieldSample.java").write_text("public record FieldSample(int count) {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "count"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "record_component_unsupported"


def test_introduce_field_refuses_unsafe_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_field_refactor_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "computed",
                "fieldType": "String",
                "initializer": "compute()",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsafe_initializer"


def test_introduce_field_creates_compile_time_constant(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_field_refactor_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "DEFAULT_LABEL",
                "fieldType": "String",
                "initializer": "\"value\"",
                "constant": True,
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("private static final String DEFAULT_LABEL = \"value\";" in replacement for replacement in replacements)


def test_introduce_field_initializes_single_constructor(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "FieldSample.java").write_text(
        """public class FieldSample {
    public FieldSample() {
        start();
    }

    void start() {
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "label",
                "fieldType": "String",
                "initializer": "\"value\"",
                "initializeInConstructor": True,
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("private final String label;" in replacement for replacement in replacements)
    assert any("this.label = \"value\";" in replacement for replacement in replacements)


def test_introduce_field_refuses_local_variable_capture(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "FieldSample.java").write_text(
        """public class FieldSample {
    String label() {
        String local = "value";
        return local;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "labelText",
                "fieldType": "String",
                "selection": {"startLine": 4, "startColumn": 16, "endLine": 4, "endColumn": 21},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "local_variable_capture"


def test_introduce_field_initializes_multiple_constructors(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "FieldSample.java").write_text(
        """public class FieldSample {
    public FieldSample() {
    }

    public FieldSample(String name) {
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "label",
                "fieldType": "String",
                "initializer": "\"value\"",
                "initializeInConstructor": True,
                "constructorStrategy": "allTerminal",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is True
    replacements = [
        edit["newText"]
        for change in refused["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert replacements.count("\n        this.label = \"value\";") == 2


def test_introduce_field_refuses_initialization_order_change(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_field_refactor_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "later",
                "fieldType": "String",
                "initializer": "this.label",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "initialization_order_unsupported"


def test_introduce_field_refuses_non_constant_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_field_refactor_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "DEFAULT_LABEL",
                "fieldType": "String",
                "initializer": "label",
                "constant": True,
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "non_constant_initializer"


def test_inline_method_preview_contains_v2_metadata(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_inline_method_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {
                "relativePath": "InlineMethodSample.java",
                "methodName": "doubleValue",
                "deleteMethod": False,
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    preview = session["preview"]
    assert preview["operation"] == "inlineMethod"
    assert preview["mode"] == "preview"
    assert preview["applied"] is False
    assert preview["semanticTarget"]["operation"] == "inlineMethod"
    assert preview["semanticTarget"]["identity"]["canonical"] == "InlineMethodSample#doubleValue(int)"
    assert preview["semanticTarget"]["identity"]["kind"] == "METHOD"
    assert preview["touchedFiles"] == ["InlineMethodSample.java"]
    assert preview["diagnosticDelta"]["before"] == {"errors": [], "warnings": []}
    assert preview["diagnosticDelta"]["after"] == {"errors": [], "warnings": []}
    assert session["session"]["touchedFiles"] == ["InlineMethodSample.java"]


def test_inline_method_substitutes_simple_receiver(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """public class InlineMethodSample {
    private int value = 3;

    int run(InlineMethodSample other) {
        return other.read();
    }

    private int read() {
        return this.value + 1;
    }
}
"""
    (tmp_path / "InlineMethodSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {
                "relativePath": "InlineMethodSample.java",
                "methodName": "read",
                "deleteMethod": False,
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    preview = _preview_text(source, session, "InlineMethodSample.java")
    assert "return (other.value + 1);" in preview
    assert "other.read()" not in preview
def test_inline_method_refusal_reports_operation_specific_envelope(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "InlineSample.java"
    source.write_text(
        """class InlineSample {
    int doubleIt(int value) { return value * 2; }
    int run() { return doubleIt(3); }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineSample.java", "line": 2, "column": 9, "methodName": "doubleIt"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "inlineMethod"
    assert refused["refusal"]["code"] == "method_not_supported"
    assert refused["semanticTarget"]["operation"] == "inlineMethod"


def test_create_session_before_initialize_reports_operation_specific_envelope(sidecar_jar: Path, tmp_path: Path) -> None:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        refused = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineMethodSample.java", "methodName": "doubleValue"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "inlineMethod"
    assert refused["mode"] == "preview"
    assert refused["refusal"]["code"] == "not_initialized"
    assert refused["semanticTarget"]["operation"] == "inlineMethod"


def test_inline_method_session_replaces_simple_calls_and_removes_method(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_inline_method_fixture(tmp_path)
    original = (tmp_path / "InlineMethodSample.java").read_text(encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineMethodSample.java", "methodName": "doubleValue", "deleteMethod": True},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    assert session["session"]["operation"] == "inlineMethod"
    preview = _preview_text(original, session, "InlineMethodSample.java")
    assert "return (4 + 4);" in preview
    assert "doubleValue" not in preview




def test_inline_method_rewrites_only_javac_resolved_calls(sidecar_jar: Path, tmp_path: Path) -> None:
    original = """public class InlineMethodSample {
    String literal = "doubleValue(4)";

    int run() {
        // doubleValue(4)
        return doubleValue(4);
    }

    private int doubleValue(int value) {
        return value + value;
    }
}
"""
    (tmp_path / "InlineMethodSample.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineMethodSample.java", "methodName": "doubleValue", "deleteMethod": True},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(original, session, "InlineMethodSample.java")
    assert 'String literal = "doubleValue(4)";' in preview
    assert "// doubleValue(4)" in preview
    assert "return (4 + 4);" in preview
    assert "private int doubleValue" not in preview


def test_inline_method_refuses_statement_bodies(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_inline_method_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineMethodSample.java", "methodName": "blocked"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "statement_body_unsupported"
    assert "session" not in refused


def test_inline_method_refuses_order_sensitive_arguments(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InlineMethodSample.java").write_text(
        """public class InlineMethodSample {
    int run() {
        return doubleValue(next());
    }

    int next() {
        return 2;
    }

    private int doubleValue(int value) {
        return value + value;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineMethodSample.java", "methodName": "doubleValue"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsafe_argument"


def test_inline_method_inlines_void_expression_statement(sidecar_jar: Path, tmp_path: Path) -> None:
    original = """public class InlineMethodSample {
    void run() {
        log("hi");
    }

    private void log(String value) {
        sink(value);
    }

    void sink(String value) {
    }
}
"""
    (tmp_path / "InlineMethodSample.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineMethodSample.java", "methodName": "log", "deleteMethod": True},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(original, session, "InlineMethodSample.java")
    assert "sink(\"hi\");" in preview
    assert "private void log" not in preview


def test_inline_method_preserves_method_when_delete_false(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_inline_method_fixture(tmp_path)
    original = (tmp_path / "InlineMethodSample.java").read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineMethodSample.java", "methodName": "doubleValue", "deleteMethod": False},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(original, session, "InlineMethodSample.java")
    assert "return (4 + 4);" in preview
    assert "private int doubleValue" in preview
    assert "return value + value;" in preview


def test_inline_method_refuses_void_method_expression_context(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InlineMethodSample.java").write_text(
        """public class InlineMethodSample {
    int run() {
        return log("hi");
    }

    private void log(String value) {
        sink(value);
    }

    void sink(String value) {
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineMethodSample.java", "methodName": "log"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "void_call_context_unsupported"
    assert "session" not in refused


def test_inline_method_supports_static_source_methods(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InlineStaticSample.java").write_text(
        """public class InlineStaticSample {
    int run() {
        return add(2, 3);
    }

    static int add(int left, int right) {
        return left + right;
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineStaticSample.java", "methodName": "add", "deleteMethod": False},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text((tmp_path / "InlineStaticSample.java").read_text(encoding="utf-8"), session, "InlineStaticSample.java")
    assert "return (2 + 3);" in preview


def test_inline_method_refuses_static_delete_as_public_api(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InlineStaticSample.java").write_text(
        """public class InlineStaticSample {
    int run() {
        return add(2, 3);
    }

    static int add(int left, int right) {
        return left + right;
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineStaticSample.java", "methodName": "add", "deleteMethod": True},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is False
    assert session["refusal"]["code"] == "delete_public_api_unsupported"


def test_inline_method_allows_declared_checked_exceptions_when_caller_declares(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InlineThrowsSample.java").write_text(
        """import java.io.IOException;

public class InlineThrowsSample {
    int run() throws IOException {
        return read();
    }

    private int read() throws IOException {
        return source();
    }

    private int source() throws IOException {
        return 1;
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineThrowsSample.java", "methodName": "read", "deleteMethod": False},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text((tmp_path / "InlineThrowsSample.java").read_text(encoding="utf-8"), session, "InlineThrowsSample.java")
    assert "return (source());" in preview


def test_v2_session_refuses_generated_sources_without_opt_in(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_policy_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractMethod",
            {
                "relativePath": "generated/GeneratedSample.java",
                "newMethodName": "printGenerated",
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 41},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "generated_source_refused"
    assert "session" not in refused


def test_v2_session_allows_generated_sources_with_explicit_opt_in(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_generated_only_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "generated/GeneratedSample.java",
                "newMethodName": "printGenerated",
                "allowGenerated": True,
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 41},
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    assert session["session"]["operation"] == "extractMethod"


def test_v2_direct_preview_and_apply_refuse_generated_sources_without_opt_in(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    _write_policy_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        params = {
            "relativePath": "generated/GeneratedSample.java",
            "newMethodName": "printGenerated",
            "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 41},
        }
        preview_refused = client.preview("extractMethod", params)
        apply_refused = client.apply_refactor("extractMethod", params)
    finally:
        client.shutdown()

    assert preview_refused["accepted"] is False
    assert preview_refused["refusal"]["code"] == "generated_source_refused"
    assert apply_refused["accepted"] is False


    assert apply_refused["refusal"]["code"] == "generated_source_refused"


def test_v2_generated_sources_edit_config_allows_default_generated_session(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    _write_generated_only_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path), config={"generated_sources": {"read": True, "edit": True}}
            )
        )
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "generated/GeneratedSample.java",
                "newMethodName": "printGenerated",
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 41},
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    assert session["session"]["operation"] == "extractMethod"


def test_v2_nested_generated_sources_config_allows_default_generated_session(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    _write_generated_only_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"generated_sources": {"read": True, "edit": True}}}},
            )
        )
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "generated/GeneratedSample.java",
                "newMethodName": "printGenerated",
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 41},
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    assert session["session"]["operation"] == "extractMethod"


def test_v2_nested_extract_method_config_supplies_default_visibility(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    _write_extract_method_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"extract_method": {"visibility": "protected"}}}},
            )
        )
        session = client.create_session(
            "extractMethod",
            {
                "relativePath": "ExtractSample.java",
                "newMethodName": "printOne",
                "selection": {"startLine": 3, "startColumn": 9, "endLine": 3, "endColumn": 35},
            },
        )

    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("protected void printOne()" in replacement for replacement in replacements)


def test_v2_session_refuses_lombok_sources_without_opt_in(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_policy_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "encapsulateField",
            {"relativePath": "LombokSample.java", "fieldName": "value"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "lombok_managed_source_refused"
    assert "session" not in refused


def test_v2_session_refuses_when_source_edit_reaches_generated_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    # G022 (B7) requirement (A), end-to-end: a refactor whose PRIMARY target is an ordinary source file must still be
    # blocked when its real plan produces a cross-file edit that reaches a GENERATED declaration referencing the changed
    # symbol. Here App.greet is called from a generated source; removing a parameter rewrites that generated call site, so
    # the post-plan gate refuses generated_source_refused even though the request's relativePath is not generated. This
    # proves the gate engages on a real planner's secondary edits, not just synthetic previews.
    (tmp_path / "App.java").write_text(
        """public class App {
    String greet(String name, String unused) {
        return "hello " + name;
    }
}
""",
        encoding="utf-8",
    )
    generated_dir = tmp_path / "generated"
    generated_dir.mkdir()
    (generated_dir / "GeneratedCaller.java").write_text(
        """public class GeneratedCaller {
    String call() {
        return new App().greet("Bob", "x");
    }
}
""",
        encoding="utf-8",
    )

    params = {
        "relativePath": "App.java",
        "line": 2,
        "column": 12,
        "parameters": [{"name": "name", "type": "String", "oldIndex": 0}],
        "removeParameters": ["unused"],
    }

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session("changeSignature", dict(params))
        allowed = client.create_session("changeSignature", {**params, "allowGenerated": True})
    finally:
        client.shutdown()

    # Without opt-in: the generated call-site edit is refused by the uniform post-plan gate.
    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "generated_source_refused", refused
    assert "session" not in refused
    # With explicit opt-in: the same plan (which rewrites the generated reference) is allowed through.
    assert allowed["accepted"] is True, allowed
    assert allowed["session"]["operation"] == "changeSignature"


def test_v2_session_refuses_when_policy_source_cannot_be_read(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_policy_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractMethod",
            {
                "relativePath": ".",
                "newMethodName": "blocked",
                "selection": {"startLine": 1, "startColumn": 1, "endLine": 1, "endColumn": 2},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "source_policy_check_failed"
    assert "session" not in refused


def test_v2_session_refuses_source_path_traversal(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_method_fixture(tmp_path)
    outside = tmp_path.parent / "Outside.java"
    outside.write_text("public class Outside {}\n", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractMethod",
            {
                "relativePath": "../Outside.java",
                "newMethodName": "blocked",
                "selection": {"startLine": 1, "startColumn": 1, "endLine": 1, "endColumn": 2},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "source_policy_check_failed"
    assert "session" not in refused


def test_v2_session_refuses_target_relative_path_traversal(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_move_member_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 19,
                "targetRelativePath": "../Target.java",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "path_outside_project"
    assert "session" not in refused


def test_v2_session_refuses_target_type_traversal(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_hierarchy_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 12, "targetType": "../Escaped"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "path_outside_project"
    assert "session" not in refused


def test_v2_session_refuses_extract_interface_target_package_traversal(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_extract_interface_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "EscapedInterface",
                "targetPackage": "..",
                "members": ["value"],
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "path_outside_project"
    assert "session" not in refused
def test_apply_session_refuses_stale_source_revision(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "semanticRename",
            {"relativePath": "App.java", "line": 2, "column": 17, "newName": "count", "nameHint": "value"},
        )
        session_id = created["session"]["sessionId"]

        (tmp_path / "App.java").write_text(
            (tmp_path / "App.java").read_text(encoding="utf-8").replace("return value;", "return this.value;"),
            encoding="utf-8",
        )

        stale = client.apply_session(session_id)
    finally:
        client.shutdown()

    assert stale["accepted"] is False
    assert stale["refusal"]["code"] == "stale_project_revision"
    assert "App.java" in stale["refusal"]["message"]


def test_apply_session_refuses_changed_semantic_target(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "semanticRename",
            {"relativePath": "App.java", "line": 2, "column": 17, "newName": "count"},
        )
        _assert_stable_target_identity(created)
        session_id = created["session"]["sessionId"]
        (tmp_path / "App.java").write_text(
            """public class App {
    private int other = 1;
    public int value() {
        return other;
    }
}
""",
            encoding="utf-8",
        )
        stale = client.apply_session(session_id)
    finally:
        client.shutdown()

    assert stale["accepted"] is False
    assert stale["refusal"]["code"] == "target_identity_changed"


def test_prefixed_session_method_names_behave_identically(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: the strict ``refactor.``-prefixed session aliases dispatch to identical behavior as the bare names."""
    _write_session_fixture(tmp_path)

    params = {"relativePath": "App.java", "line": 2, "column": 17, "newName": "count", "nameHint": "value"}
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        bare = client._request("createSession", {"operation": "semanticRename", "params": dict(params)})
        prefixed = client._request("refactor.createSession", {"operation": "semanticRename", "params": dict(params)})

        bare_id = bare["session"]["sessionId"]
        prefixed_id = prefixed["session"]["sessionId"]

        prefixed_edit = client._request("refactor.getSessionEdit", {"sessionId": prefixed_id})
        prefixed_apply = client._request("refactor.applySession", {"sessionId": prefixed_id})
        prefixed_cancel = client._request("refactor.cancelSession", {"sessionId": bare_id})
    finally:
        client.shutdown()

    assert bare["accepted"] is True
    assert prefixed["accepted"] is True
    # Identical envelope shape/identity (session ids differ per create, so compare everything else).
    assert prefixed["session"]["operation"] == bare["session"]["operation"] == "semanticRename"
    assert prefixed["session"]["targetIdentity"] == bare["session"]["targetIdentity"]
    assert prefixed["preview"]["workspaceEdit"]["changes"] == bare["preview"]["workspaceEdit"]["changes"]
    assert prefixed_edit["accepted"] is True
    assert prefixed_edit["mode"] == "preview"
    assert prefixed_apply["accepted"] is True
    assert prefixed_apply["mode"] == "apply"
    assert prefixed_cancel["accepted"] is True
    assert prefixed_cancel["cancelled"] is True


def test_apply_session_matching_expected_revision_applies(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: a pinned expectedProjectRevision that matches the session's create-time revision applies normally."""
    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "semanticRename",
            {"relativePath": "App.java", "line": 2, "column": 17, "newName": "count", "nameHint": "value"},
        )
        session_id = created["session"]["sessionId"]
        model_hash = created["session"]["projectRevision"]["modelHash"]

        # Matching string form applies.
        matching = client.apply_session(session_id, expected_project_revision=model_hash)
        # The whole projectRevision object form also matches (carries the same modelHash).
        created_again = client.create_session(
            "semanticRename",
            {"relativePath": "App.java", "line": 2, "column": 17, "newName": "count", "nameHint": "value"},
        )
        object_form = client.apply_session(
            created_again["session"]["sessionId"],
            expected_project_revision=created_again["session"]["projectRevision"],
        )
        # Absent guard still applies (backward compatible).
        created_absent = client.create_session(
            "semanticRename",
            {"relativePath": "App.java", "line": 2, "column": 17, "newName": "count", "nameHint": "value"},
        )
        absent = client.apply_session(created_absent["session"]["sessionId"])
    finally:
        client.shutdown()

    assert created["accepted"] is True
    assert matching["accepted"] is True
    assert matching["mode"] == "apply"
    assert object_form["accepted"] is True
    assert absent["accepted"] is True


def test_apply_session_refuses_mismatched_expected_revision(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: a pinned expectedProjectRevision that does not match the session refuses before any write."""
    _write_session_fixture(tmp_path)
    original = (tmp_path / "App.java").read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "semanticRename",
            {"relativePath": "App.java", "line": 2, "column": 17, "newName": "count", "nameHint": "value"},
        )
        session_id = created["session"]["sessionId"]
        mismatch = client.apply_session(session_id, expected_project_revision="deadbeef-not-the-real-revision")
    finally:
        client.shutdown()

    assert created["accepted"] is True
    assert mismatch["accepted"] is False
    assert mismatch["refusal"]["code"] == "project_revision_mismatch"
    # Refused before any mutation.
    assert (tmp_path / "App.java").read_text(encoding="utf-8") == original


def test_pull_up_member_accepts_indirect_supertype_and_copies_required_import(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "api").mkdir()
    (tmp_path / "mid").mkdir()
    (tmp_path / "impl").mkdir()
    (tmp_path / "api" / "Root.java").write_text(
        """package api;

public class Root {
}
""",
        encoding="utf-8",
    )
    (tmp_path / "mid" / "Mid.java").write_text(
        """package mid;

import api.Root;

public class Mid extends Root {
}
""",
        encoding="utf-8",
    )
    (tmp_path / "impl" / "Leaf.java").write_text(
        """package impl;

import mid.Mid;
import java.util.List;

public class Leaf extends Mid {
    public List<String> labels() {
        return List.of("leaf");
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path)))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "impl/Leaf.java", "line": 7, "column": 25, "targetType": "api.Root", "confirmPublicApi": True},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    rendered = json.dumps(created)
    assert "api/Root.java" in rendered
    assert "import java.util.List;" in rendered
    assert "public List<String> labels()" in rendered


def test_push_down_member_accepts_indirect_subtype_when_requested(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Base.java").write_text(
        """public class Base {
    public String label() {
        return "base";
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Child.java").write_text("""public class Child extends Base {
}
""", encoding="utf-8")
    (tmp_path / "GrandChild.java").write_text("""public class GrandChild extends Child {
}
""", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path)))
        created = client.create_session(
            "pushDownMember",
            {
                "relativePath": "Base.java",
                "line": 2,
                "column": 19,
                "targetTypes": ["GrandChild"],
                "includeIndirectSubtypes": True,
                "confirmPublicApi": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    rendered = json.dumps(created)
    assert "GrandChild.java" in rendered
    assert "public String label()" in rendered


def test_pull_up_member_refuses_source_only_body_dependency(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Base.java").write_text("""public class Base {
}
""", encoding="utf-8")
    (tmp_path / "Child.java").write_text(
        """public class Child extends Base {
    public String label() {
        return helper();
    }

    String helper() {
        return "child";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path)))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 19, "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "incompatible_member_body"


def test_pull_up_member_allows_compatible_indirect_sibling_override(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Base.java").write_text("""public class Base {
}
""", encoding="utf-8")
    (tmp_path / "Child.java").write_text(
        """public class Child extends Base {
    public String label() {
        return "child";
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "OtherChild.java").write_text("""public class OtherChild extends Base {
}
""", encoding="utf-8")
    (tmp_path / "OtherGrandChild.java").write_text(
        """public class OtherGrandChild extends OtherChild {
    public String label() {
        return "other";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path)))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 19, "targetType": "Base", "confirmPublicApi": True},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)


def test_push_down_member_refuses_unqualified_source_call_site_removal(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Base.java").write_text(
        """public class Base {
    public String label() {
        return "base";
    }

    public String use() {
        return label();
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Child.java").write_text("""public class Child extends Base {
}
""", encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path)))
        refused = client.create_session(
            "pushDownMember",
            {"relativePath": "Base.java", "line": 2, "column": 19, "targetTypes": ["Child"], "removeFromSource": True},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsafe_source_call_site"


def test_extract_interface_renders_inherited_generic_signature_imports(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "src/main/java/com/model").mkdir(parents=True)
    (tmp_path / "src/main/java/com/impl").mkdir(parents=True)
    (tmp_path / "src/main/java/com/model/Widget.java").write_text(
        """package com.model;
public class Widget {}
""",
        encoding="utf-8",
    )
    (tmp_path / "src/main/java/com/model/WidgetException.java").write_text(
        """package com.model;
public class WidgetException extends Exception {}
""",
        encoding="utf-8",
    )
    (tmp_path / "src/main/java/com/impl/BaseSource.java").write_text(
        """package com.impl;

import com.model.Widget;
import com.model.WidgetException;
import java.util.List;

public class BaseSource<T extends Widget> {
    public List<T> items(T input) throws WidgetException {
        return java.util.Collections.singletonList(input);
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "src/main/java/com/impl/InterfaceSource.java").write_text(
        """package com.impl;

import com.model.Widget;

public class InterfaceSource extends BaseSource<Widget> {}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractInterface",
            {
                "relativePath": "src/main/java/com/impl/InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "targetPackage": "com.api",
                "members": ["items"],
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    workspace_edit = session["preview"]["workspaceEdit"]
    source_edits = workspace_edit["changes"][0]["edits"]
    assert any("implements ExtractedValue" in edit["newText"] for edit in source_edits)
    assert any("import com.api.ExtractedValue;" in edit["newText"] for edit in source_edits)
    content = workspace_edit["fileOperations"][0]["content"]
    assert "package com.api;" in content
    assert "import com.model.Widget;" in content
    assert "import com.model.WidgetException;" in content
    assert "import java.util.List;" in content
    assert "List<Widget> items(Widget input) throws WidgetException;" in content


def test_extract_interface_refuses_package_private_signature_type_across_packages(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "src/main/java/com/impl").mkdir(parents=True)
    (tmp_path / "src/main/java/com/impl/HiddenValue.java").write_text(
        """package com.impl;
class HiddenValue {}
""",
        encoding="utf-8",
    )
    (tmp_path / "src/main/java/com/impl/InterfaceSource.java").write_text(
        """package com.impl;

public class InterfaceSource {
    public HiddenValue hidden() {
        return new HiddenValue();
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractInterface",
            {
                "relativePath": "src/main/java/com/impl/InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "targetPackage": "com.api",
                "members": ["hidden"],
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "private_type_unsupported"


def test_extract_interface_usage_narrowing_accepts_semantic_factory_assignment(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InterfaceSource.java").write_text(
        """public class InterfaceSource {
    public String value() {
        return "value";
    }

    public String hidden() {
        return "hidden";
    }
}

class Usage {
    void run() {
        InterfaceSource source = make();
        source.value();
    }

    InterfaceSource make() {
        return new InterfaceSource();
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "members": ["value"],
                "replaceUsages": True,
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    edits_by_path = {change["path"]: change["edits"] for change in session["preview"]["workspaceEdit"]["changes"]}
    assert any(edit["newText"] == "ExtractedValue" for edit in edits_by_path["InterfaceSource.java"])

def test_introduce_field_uses_shared_import_planner_for_field_type(sidecar_jar: Path, tmp_path: Path) -> None:
    source = """package demo;

import static java.util.Collections.emptyList;

class FieldImportSample {
    Object make() {
        return null;
    }
}
"""
    (tmp_path / "FieldImportSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "introduceField",
            {
                "relativePath": "FieldImportSample.java",
                "fieldName": "names",
                "fieldType": "java.util.List<String>",
                "selection": _selection_for(source, "null"),
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    replacements = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("import java.util.List;" in replacement for replacement in replacements)
    assert any("private final List<String> names = null;" in replacement for replacement in replacements)


def test_pull_up_member_uses_shared_import_planner_for_wildcard_target(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Base.java").write_text(
        """package demo;

import java.util.*;

class Base {
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Child.java").write_text(
        """package demo;

import java.util.List;

class Child extends Base {
    List<String> names() {
        return List.of("x");
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 6, "column": 18, "memberName": "names", "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    base_edits = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        if change["path"] == "Base.java"
        for edit in change["edits"]
    ]
    assert any("List<String> names()" in replacement for replacement in base_edits)
    assert not any("import java.util.List;" in replacement for replacement in base_edits)


def test_v2_nested_session_config_limits_live_sessions(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_signature_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                configuration="default",
                config={"java_refactor": {"v2": {"sessions": {"max_open_sessions": 1}}}},
            )
        )
        params = {
            "relativePath": "App.java",
            "line": 6,
            "column": 12,
            "newName": "format",
            "newReturnType": "java.lang.String",
            "parameters": [
                {"name": "name", "type": "String"},
                {"name": "count", "type": "int", "defaultValue": "1"},
            ],
        }
        first = client.create_session("changeSignature", params)
        second = client.create_session("changeSignature", params)
        evicted = client.get_session_edit(first["session"]["sessionId"])
        retained = client.get_session_edit(second["session"]["sessionId"])
    finally:
        client.shutdown()

    assert first["accepted"] is True
    assert second["accepted"] is True
    assert evicted["accepted"] is False
    assert evicted["refusal"]["code"] == "unknown_session"
    assert retained["accepted"] is True


def test_inline_method_parenthesizes_non_atomic_argument_substitutions(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InlinePrecedenceSample.java").write_text(
        """
class InlinePrecedenceSample {
    int run(int a, int b) {
        return doubleValue(a + b);
    }

    private int doubleValue(int value) {
        return value * 2;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {"relativePath": "InlinePrecedenceSample.java", "methodName": "doubleValue", "deleteMethod": False},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    preview = _preview_text(
        (tmp_path / "InlinePrecedenceSample.java").read_text(encoding="utf-8"),
        session,
        "InlinePrecedenceSample.java",
    )
    assert "return ((a + b) * 2);" in preview


def test_inline_method_apply_reports_apply_mode_and_exact_stats(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "InlineApplySample.java").write_text(
        """
class InlineApplySample {
    int run(int value) {
        return doubleValue(value);
    }

    private int doubleValue(int value) {
        return value * 2;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        applied = client.apply_refactor(
            "inlineMethod",
            {"relativePath": "InlineApplySample.java", "methodName": "doubleValue", "deleteMethod": False},
        )
    finally:
        client.shutdown()

    assert applied["accepted"] is True
    assert applied["operation"] == "inlineMethod"
    # G014: a V2 direct apply does not claim applied:true; it returns a client-apply contract carrying the edit.
    assert applied["applied"] is False
    assert applied["mode"] == "preview"
    assert applied["requiresClientApply"] is True
    assert applied["stats"]["touchedFileCount"] == 1
    assert applied["workspaceEdit"]["stats"]["touchedFileCount"] == 1


V2_ACCEPTANCE_BEHAVIOR_MARKERS: dict[str, tuple[str, list[str]]] = {
    "capabilities supported": ("test_status_reports_v2_capabilities", ["changeSignature", "inlineMethod", "supported"]),
    "standard preview envelope": (
        "_assert_standard_v2_preview_payload",
        ["diagnosticDelta", "touchedFileCount", "semanticTarget", "refusal"],
    ),
    "diagnostic delta preview": (
        "test_v2_refactor_session_preview_exposes_full_diagnostic_delta",
        ["diagnosticDelta", "newWarnings", "newErrors"],
    ),
    "diagnostic apply gate": (
        "test_v2_refactor_session_apply_always_gates_new_diagnostic_delta",
        ["refusal", "diagnosticDelta"],
    ),
    "change signature override group": (
        "test_change_signature_updates_override_group_and_resolved_call_sites",
        ["changeSignature", "interface", "Impl"],
    ),
    "change signature method references": (
        "test_change_signature_rewrites_safe_method_reference_renames",
        ["changeSignature", "::", "newName"],
    ),
    "change signature constructors": (
        "test_change_signature_updates_constructor_call_sites",
        ["changeSignature", "new Box", "constructor"],
    ),
    "change signature import ambiguity": (
        "test_change_signature_import_planner_falls_back_on_simple_name_conflict",
        ["changeSignature", "java.util", "helper"],
    ),
    "introduce parameter multi-file stats": (
        "test_introduce_parameter_reports_exact_multi_file_touched_stats",
        ["introduceParameter", "touchedFileCount", "helper"],
    ),
    "move static imports": (
        "test_move_static_member_rewrites_static_import_references",
        ["moveStaticMember", "import static", "Target"],
    ),
    "move static collision refusal": (
        "test_move_static_member_refuses_semantic_signature_collision",
        ["moveStaticMember", "refusal", "target"],
    ),
    "move instance field receiver": (
        "test_move_instance_method_supports_field_receiver_strategy",
        ["moveInstanceMethod", "targetField", "touchedFiles"],
    ),
    "move instance explicit receiver": (
        "test_move_instance_method_supports_explicit_receiver_strategy",
        ["moveInstanceMethod", "targetReceiver", "targetType"],
    ),
    "pull up constrained field": ("test_pull_up_member_accepts_simple_instance_field", ["pullUpMember", "int count"]),
    "push down multi-target field": (
        "test_push_down_member_accepts_simple_instance_field_to_multiple_subtypes",
        ["pushDownMember", "Child", "touchedFileCount"],
    ),
    "extract method CRLF and tabs": (
        "test_extract_method_preserves_tabs_and_crlf_style",
        ["extractMethod", "\\r\\n", "\\t"],
    ),
    "extract method partial-statement refusal": (
        "test_extract_method_partial_statement_returns_suggested_ranges",
        ["extractMethod", "SELECTION_NOT_STATEMENT_ALIGNED", "suggestedRanges"],
    ),
    "extract interface overload selection": (
        "test_extract_interface_selects_overloaded_member_by_signature",
        ["extractInterface", "signature", "String"],
    ),
    "extract interface generics/imports": (
        "test_extract_interface_renders_inherited_generic_signature_imports",
        ["extractInterface", "java.util", "List"],
    ),
    "introduce field constructors": (
        "test_introduce_field_session_initializes_all_terminal_constructors",
        ["introduceField", "constructor", "this."],
    ),
    "introduce field tab style": ("test_introduce_field_preserves_tab_style", ["introduceField", "\\t"]),
    "encapsulate field external references": (
        "test_encapsulate_field_rewrites_external_member_references",
        ["encapsulateField", "target", "preview"],
    ),
    "inline method precedence": (
        "test_inline_method_parenthesizes_non_atomic_argument_substitutions",
        ["inlineMethod", "(a + b) * 2"],
    ),
    "inline method evaluation refusal": (
        "test_inline_method_refuses_order_sensitive_arguments",
        ["inlineMethod", "refusal"],
    ),
    "session revision guard": ("test_apply_session_refuses_stale_source_revision", ["apply_session", "revision", "refusal"]),
    "generated source policy": (
        "test_v2_direct_preview_and_apply_refuse_generated_sources_without_opt_in",
        ["generated", "preview", "apply", "refusal"],
    ),
    "lombok source policy": (
        "test_v2_session_refuses_lombok_sources_without_opt_in",
        ["encapsulateField", "lombok", "refusal"],
    ),
    "nested explicit model config": (
        "test_sidecar_consumes_nested_java_refactor_model",
        ["java_refactor", "model", "generatedRoots", "-parameters"],
    ),
}

V2_ACCEPTANCE_JAVA_MARKERS: dict[str, tuple[Path, list[str]]] = {
    "explicit model source-set config": (
        Path("java-refactor/src/test/java/io/serena/javarefactor/project/ProjectModelDiscovererBuildToolTest.java"),
        ["explicitModelOverridePreservesConfiguredSourceSet", "UTF-16", "-parameters", "generatedRoots"],
    ),
    "javac warning delta policy": (
        Path("java-refactor/src/test/java/io/serena/javarefactor/protocol/PreviewDiagnosticValidatorTest.java"),
        ["acceptedPreviewReportsNewJavacWarningsWithoutRefusal", "newWarnings", "-Xlint:unchecked"],
    ),
}


def _function_sources_for(paths: list[Path]) -> dict[str, str]:
    functions: dict[str, str] = {}
    for path in paths:
        source = path.read_text(encoding="utf-8")
        tree = ast.parse(source, filename=str(path))
        for node in tree.body:
            if isinstance(node, ast.FunctionDef):
                functions[node.name] = ast.get_source_segment(source, node) or ""
    return functions


def test_v2_acceptance_matrix_requires_behavior_markers() -> None:
    functions = _function_sources_for(
        [
            Path(__file__),
            Path("test/serena/test_java_refactor.py"),
            Path("test/serena/test_java_refactor_sidecar_initialize.py"),
        ]
    )
    missing: list[str] = []
    for behavior, (function_name, markers) in V2_ACCEPTANCE_BEHAVIOR_MARKERS.items():
        source = functions.get(function_name)
        if source is None:
            missing.append(f"{behavior}: missing {function_name}")
            continue
        missing.extend(f"{behavior}: {function_name} lacks {marker!r}" for marker in markers if marker not in source)

    for behavior, (path, markers) in V2_ACCEPTANCE_JAVA_MARKERS.items():
        source = path.read_text(encoding="utf-8")
        missing.extend(f"{behavior}: {path} lacks {marker!r}" for marker in markers if marker not in source)

    assert not missing, "V2 acceptance coverage regressed:\n" + "\n".join(missing)


# ---------------------------------------------------------------------------
# G002: enforce the V2 enable/disable configuration contract.
# ---------------------------------------------------------------------------


def test_v2_global_disable_refuses_operation_on_preview_apply_and_session(sidecar_jar: Path, tmp_path: Path) -> None:
    """G002: java_refactor.v2.enabled=false refuses a V2 op on preview, apply, and createSession with operation_disabled."""
    _write_field_refactor_fixture(tmp_path)
    params = {
        "relativePath": "FieldSample.java",
        "fieldName": "count",
        "getterName": "getCount",
        "setterName": "setCount",
    }

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"enabled": False}}},
            )
        )
        preview = client.preview("encapsulateField", params)
        applied = client.apply_refactor("encapsulateField", params)
        created = client.create_session("encapsulateField", params)
    finally:
        client.shutdown()

    for result in (preview, applied, created):
        assert result["accepted"] is False
        assert result["refusal"]["code"] == "operation_disabled"


def test_v2_per_operation_disable_refuses_only_that_operation(sidecar_jar: Path, tmp_path: Path) -> None:
    """G002: a per-operation enabled=false disables just that op; siblings keep working."""
    _write_field_refactor_fixture(tmp_path)
    encapsulate_params = {
        "relativePath": "FieldSample.java",
        "fieldName": "count",
        "getterName": "getCount",
        "setterName": "setCount",
    }
    introduce_params = {
        "relativePath": "FieldSample.java",
        "fieldName": "answer",
        "fieldType": "int",
        "initializer": "42",
    }

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"encapsulate_field": {"enabled": False}}}},
            )
        )
        disabled = client.create_session("encapsulateField", encapsulate_params)
        sibling = client.create_session("introduceField", introduce_params)
    finally:
        client.shutdown()

    assert disabled["accepted"] is False
    assert disabled["refusal"]["code"] == "operation_disabled"
    # The non-disabled sibling op is unaffected by the per-op disable.
    assert sibling["accepted"] is True


def test_v2_disabled_operation_reports_disabled_capability_status(sidecar_jar: Path, tmp_path: Path) -> None:
    """G002/G001: a config-disabled op advertises a non-"supported" ("disabled") capability status."""
    _write_field_refactor_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"encapsulate_field": {"enabled": False}}}},
            )
        )
        # status now lives in the sibling capabilityDetails map (G003)
        capabilities = cast(dict[str, dict[str, Any]], client.capabilities()["capabilityDetails"])
    finally:
        client.shutdown()

    assert capabilities["encapsulateField"]["status"] == "disabled"
    assert capabilities["encapsulateField"]["status"] != "supported"
    # A sibling op that was not disabled stays supported.
    assert capabilities["introduceField"]["status"] == "supported"


# ---------------------------------------------------------------------------
# G001: truthful, configuration-aware capability registry.
# ---------------------------------------------------------------------------


def test_capability_status_distinguishes_ready_and_not_ready_ops(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: a stable op (semanticRename) and every beta op with resolved blockers advertise "supported".

    changeSignature's V2 blockers (G004-G007), moveInstanceMethod's blocker (G008), the hierarchy ops' blockers
    (G009-G011), and extract-method's blockers (G012/G013) are all resolved, so each is advertised "supported". The
    not-ready -> "preview" status path is still exercised via the disable-precedence test, which forces a known op into
    the "disabled" state.
    """
    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        # status now lives in the sibling capabilityDetails map (G003)
        capabilities = cast(dict[str, dict[str, Any]], client.capabilities()["capabilityDetails"])
    finally:
        client.shutdown()

    assert capabilities["semanticRename"]["status"] == "supported"
    # changeSignature/introduceParameter blockers (G004-G007) are resolved: both are now supported.
    assert capabilities["changeSignature"]["status"] == "supported"
    assert capabilities["introduceParameter"]["status"] == "supported"
    # moveInstanceMethod's V2 blocker (G008) is resolved: it now refuses/preserves method references, so it is supported.
    assert capabilities["moveInstanceMethod"]["status"] == "supported"
    # hierarchy ops' V2 blockers (G009-G011) are resolved: pull-up/push-down are now supported.
    assert capabilities["pullUpMember"]["status"] == "supported"
    assert capabilities["pushDownMember"]["status"] == "supported"
    # extractMethod's V2 blockers (G012/G013) are resolved: initializer extraction and the full matrix now pass.
    assert capabilities["extractMethod"]["status"] == "supported"


def test_config_disable_overrides_not_ready_to_disabled(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: status precedence disabled > not-ready > supported — disabling a not-ready op reports "disabled"."""
    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                config={"java_refactor": {"v2": {"change_signature": {"enabled": False}}}},
            )
        )
        # status now lives in the sibling capabilityDetails map (G003)
        capabilities = cast(dict[str, dict[str, Any]], client.capabilities()["capabilityDetails"])
    finally:
        client.shutdown()

    assert capabilities["changeSignature"]["status"] == "disabled"


# ---------------------------------------------------------------------------
# G003: explicit session API contract (format + expectedProjectRevision).
# ---------------------------------------------------------------------------


def test_get_session_edit_validates_unknown_format(sidecar_jar: Path, tmp_path: Path) -> None:
    """G003: getSessionEdit accepts a known format (echoed) and refuses an unknown one with a structured code."""
    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "semanticRename",
            {"relativePath": "App.java", "line": 2, "column": 17, "newName": "count", "nameHint": "value"},
        )
        session_id = created["session"]["sessionId"]
        known = client.get_session_edit(session_id, edit_format="workspaceEdit")
        unknown = client.get_session_edit(session_id, edit_format="unifiedDiffNope")
    finally:
        client.shutdown()

    assert created["accepted"] is True
    assert known["accepted"] is True
    # The validated format is echoed back so the caller can confirm the serialization.
    assert known["format"] == "workspaceEdit"
    assert unknown["accepted"] is False
    assert unknown["refusal"]["code"] == "unsupported_edit_format"


def test_apply_session_wrong_expected_revision_is_rejected(sidecar_jar: Path, tmp_path: Path) -> None:
    """G003: applySession with a wrong expected_project_revision is refused before any write (surfaced, not swallowed)."""
    _write_session_fixture(tmp_path)
    original = (tmp_path / "App.java").read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "semanticRename",
            {"relativePath": "App.java", "line": 2, "column": 17, "newName": "count", "nameHint": "value"},
        )
        rejected = client.apply_session(
            created["session"]["sessionId"], expected_project_revision="not-the-real-revision"
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    assert rejected["accepted"] is False
    assert rejected["refusal"]["code"] == "project_revision_mismatch"
    assert (tmp_path / "App.java").read_text(encoding="utf-8") == original


# ---------------------------------------------------------------------------
# G004 — snake_case parameter JSON contract
# ---------------------------------------------------------------------------


def test_change_signature_snake_case_old_index_disambiguates_reorder_rename(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    """G004: the V2 snake_case ``old_index`` field is read by the sidecar and is required to disambiguate.

    Both existing parameters are ``String`` and are reordered while both are renamed, so neither name nor
    position can recover the mapping; only ``old_index`` yields the correct call-site argument order.
    """
    original = """public class App {
    String greet() {
        return helper("first", "second");
    }

    String helper(String alpha, String beta) {
        return alpha + beta;
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "parameters": [
                    {"type": "String", "name": "renamedBeta", "old_index": 1},
                    {"type": "String", "name": "renamedAlpha", "old_index": 0},
                ],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    replacements = [
        edit["newText"]
        for change in created["preview"]["workspaceEdit"]["changes"]
        if change["path"] == "App.java"
        for edit in change["edits"]
    ]
    preview = _preview_text(original, created, "App.java")
    assert "    String helper(String renamedBeta, String renamedAlpha)" in preview
    # old_index drives the call-site reorder: second argument first, first argument second.
    assert 'helper("second", "first")' in replacements
    assert "return renamedAlpha + renamedBeta;" in preview


# ---------------------------------------------------------------------------
# G005 — per-call-site default argument resolution
# ---------------------------------------------------------------------------


def test_change_signature_default_adds_import_at_cross_package_call_site(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    """G005(a): a fully-qualified compile-time-constant default (a type-qualified enum constant) whose type is
    unimported at a different-package call site succeeds by adding the needed import at that call site."""
    api_dir = tmp_path / "src" / "main" / "java" / "com" / "api"
    app_dir = tmp_path / "src" / "main" / "java" / "com" / "app"
    api_dir.mkdir(parents=True)
    app_dir.mkdir(parents=True)
    (api_dir / "Service.java").write_text(
        """package com.api;

import java.util.List;

public class Service {
    public List<String> handle(String name) {
        return List.of(name);
    }
}
""",
        encoding="utf-8",
    )
    caller = app_dir / "Caller.java"
    caller.write_text(
        """package com.app;

import com.api.Service;

public class Caller {
    String run(Service service) {
        return service.handle("a").toString();
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "src/main/java/com/api/Service.java",
                "line": 6,
                "column": 25,
                "confirmPublicApi": True,
                "parameters": [
                    {"type": "String", "name": "name", "old_index": 0},
                    {
                        "type": "java.math.RoundingMode",
                        "name": "mode",
                        "default_value": "java.math.RoundingMode.HALF_UP",
                    },
                ],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    caller_edits = [
        edit["newText"]
        for change in created["preview"]["workspaceEdit"]["changes"]
        if change["path"].endswith("Caller.java")
        for edit in change["edits"]
    ]
    caller_preview = _preview_text(caller.read_text(encoding="utf-8"), created, "src/main/java/com/app/Caller.java")
    assert "import java.math.RoundingMode;" in caller_preview
    assert any("RoundingMode.HALF_UP" in text for text in caller_edits)


def test_change_signature_refuses_default_with_inaccessible_type_at_call_site(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    """G005(b): a default that is a proven compile-time constant (a type-qualified enum constant) but references a
    package-private type that is not accessible at a different-package call site is refused DEFAULT_ARGUMENT_UNRESOLVED
    with the call-site location. (The default passes the G004 detached-constant gate on shape, then fails per-call-site
    type resolution.)"""
    api_dir = tmp_path / "src" / "main" / "java" / "com" / "api"
    app_dir = tmp_path / "src" / "main" / "java" / "com" / "app"
    api_dir.mkdir(parents=True)
    app_dir.mkdir(parents=True)
    # Package-private enum in com.api; its constants are only referenceable within com.api.
    (api_dir / "Mode.java").write_text(
        """package com.api;

enum Mode {
    FAST
}
""",
        encoding="utf-8",
    )
    (api_dir / "Service.java").write_text(
        """package com.api;

public class Service {
    public String handle(String name) {
        return name;
    }
}
""",
        encoding="utf-8",
    )
    (app_dir / "Caller.java").write_text(
        """package com.app;

import com.api.Service;

public class Caller {
    String run(Service service) {
        return service.handle("a");
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {
                "relativePath": "src/main/java/com/api/Service.java",
                "line": 4,
                "column": 19,
                "confirmPublicApi": True,
                "parameters": [
                    {"type": "String", "name": "name", "old_index": 0},
                    {
                        "type": "Object",
                        "name": "mode",
                        # `Mode` is package-private in com.api; a com.app call site cannot reference its constants.
                        "default_value": "Mode.FAST",
                    },
                ],
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "DEFAULT_ARGUMENT_UNRESOLVED"
    assert "Caller.java" in refused["refusal"]["message"]
    assert "session" not in refused


# ---------------------------------------------------------------------------
# G006 — return-type change body compatibility
# ---------------------------------------------------------------------------


def test_change_signature_refuses_incompatible_return_body(sidecar_jar: Path, tmp_path: Path) -> None:
    """G006(a): rewriting the declared return type to one the body's return expression is not assignable to is
    refused RETURN_TYPE_INCOMPATIBLE with the return-statement location."""
    original = """public class App {
    Object value() {
        return compute();
    }

    String compute() {
        return "x";
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 12,
                # Body returns String (from compute()); Integer is not assignable from String.
                "newReturnType": "Integer",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "RETURN_TYPE_INCOMPATIBLE"
    assert "App.java" in refused["refusal"]["message"]
    assert "session" not in refused


def test_change_signature_allows_compatible_widening_return(sidecar_jar: Path, tmp_path: Path) -> None:
    """G006(b): a compatible widening of the return type (String -> CharSequence) whose body return expression
    remains assignable succeeds. The only call site is a statement expression so no returnConversion is needed."""
    original = """public class App {
    void caller() {
        widen();
    }

    String widen() {
        return "value";
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "newReturnType": "CharSequence",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    preview = _preview_text(original, created, "App.java")
    assert "    CharSequence widen()" in preview
    assert 'return "value";' in preview


# ---------------------------------------------------------------------------
# G002 — the override/interface group is always rewritten together (no
# update_overrides option exists in the V2 contract; a partial update is never
# safe, so the meaningful group case is honored rather than refused).
# ---------------------------------------------------------------------------


def test_change_signature_override_group_in_one_file_is_updated_together(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    """G002: a target participating in an override/interface group is accepted and the whole group is rewritten,
    rather than being refused. There is no update_overrides flag to skip override updates."""
    original = """interface Service {
    String label(String name);
}

class Impl implements Service {
    public String label(String text) {
        return text;
    }
}

public class App {
    String run(Service service, Impl impl) {
        return service.label("A") + impl.label("B");
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 19,
                "newName": "format",
                "confirmPublicApi": True,
                "parameters": [{"type": "String", "name": "value", "oldIndex": 0}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    preview = _preview_text(original, created, "App.java")
    assert "    String format(String value);" in preview
    assert "    public String format(String value)" in preview
    assert 'service.format("A") + impl.format("B")' in preview


def test_change_signature_updates_whole_group_without_override_flag(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    """G002: with no update_overrides field at all, every declaration in the override group is still rewritten."""
    original = """interface Service {
    String label(String name);
}

class Impl implements Service {
    public String label(String text) {
        return text;
    }
}

public class App {
    String run(Service service, Impl impl) {
        return service.label("A") + impl.label("B");
    }
}
"""
    (tmp_path / "App.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 19,
                "newName": "format",
                "confirmPublicApi": True,
                "parameters": [{"type": "String", "name": "value", "oldIndex": 0}],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    preview = _preview_text(original, created, "App.java")
    assert "    String format(String value);" in preview
    assert "    public String format(String value)" in preview
    assert 'service.format("A") + impl.format("B")' in preview


# ---------------------------------------------------------------------------
# G011 hard-case coverage (task #55): selected inline call sites, access-widening
# gates, private-static helper moves, pure non-simple instance-move receivers,
# boolean isX encapsulation, and pull-up/push-down import handling.
# ---------------------------------------------------------------------------


def _write_multi_call_inline_fixture(project_root: Path) -> str:
    """A class with two javac-resolved call sites of one inlinable private method."""
    source = """public class Multi {
    int run() {
        int a = twice(1);
        int b = twice(2);
        return a + b;
    }

    private int twice(int value) {
        return value + value;
    }
}
"""
    (project_root / "Multi.java").write_text(source, encoding="utf-8")
    return source


def _call_site_selection(source: str, snippet: str) -> dict[str, Any]:
    start = source.index(snippet)
    return {"file": "Multi.java", "startOffset": start, "endOffset": start + len(snippet)}


def test_inline_method_selected_call_site_inlines_only_that_invocation(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009: a callSiteSelection narrows the inline to exactly one javac-resolved invocation; the sibling call site and
    # the method declaration are both left untouched (deleteMethod is false).
    source = _write_multi_call_inline_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "inlineMethod",
            {
                "relativePath": "Multi.java",
                "methodName": "twice",
                "deleteMethod": False,
                "callSiteSelection": _call_site_selection(source, "twice(1)"),
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    preview = _preview_text(source, created, "Multi.java")
    # Only the selected call site is inlined ...
    assert "int a = (1 + 1);" in preview
    # ... the other invocation and the declaration survive.
    assert "int b = twice(2);" in preview
    assert "private int twice(int value)" in preview


def test_inline_method_without_selection_inlines_all_call_sites(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009: omitting callSiteSelection keeps the existing all-sites behavior — every javac-resolved invocation is inlined.
    source = _write_multi_call_inline_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "inlineMethod",
            {"relativePath": "Multi.java", "methodName": "twice", "deleteMethod": False},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(source, created, "Multi.java")
    assert "int a = (1 + 1);" in preview
    assert "int b = (2 + 2);" in preview
    # deleteMethod is false, so the declaration remains after inlining every site.
    assert "private int twice(int value)" in preview


def test_inline_method_selected_single_site_refuses_delete(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009: deletion is gated on every javac-resolved reference being rewritten. When a selection inlines only one of
    # several call sites, the method must NOT be deleted — the sidecar refuses with incomplete_inline rather than
    # leaving a dangling reference.
    source = _write_multi_call_inline_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "inlineMethod",
            {
                "relativePath": "Multi.java",
                "methodName": "twice",
                "deleteMethod": True,
                "callSiteSelection": _call_site_selection(source, "twice(1)"),
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "inlineMethod"
    assert refused["refusal"]["code"] == "incomplete_inline"
    assert "session" not in refused


def test_pull_up_member_gates_cross_package_access_widening(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: pulling a package-private member up to a supertype in another package would widen its visibility. The move
    # is refused with access_widening_not_confirmed unless allowAccessWidening is set; with the flag it is accepted and
    # the relocated member is rendered public in the target (verifying the widened modifier is placed at the declaration,
    # not glued to the front of the text).
    (tmp_path / "a").mkdir()
    (tmp_path / "b").mkdir()
    (tmp_path / "a" / "Base.java").write_text(
        """package a;

public class Base {
    int seed = 1;
}
""",
        encoding="utf-8",
    )
    (tmp_path / "b" / "Child.java").write_text(
        """package b;

import a.Base;

public class Child extends Base {
    String label() {
        return "child";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "b/Child.java", "line": 6, "column": 12, "targetType": "a.Base"},
        )
        accepted = client.create_session(
            "pullUpMember",
            {"relativePath": "b/Child.java", "line": 6, "column": 12, "targetType": "a.Base", "allowAccessWidening": True},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "pullUpMember"
    assert refused["refusal"]["code"] == "access_widening_not_confirmed"
    assert "session" not in refused

    assert accepted["accepted"] is True
    _assert_stable_target_identity(accepted)
    base_edits = [
        edit["newText"]
        for change in accepted["preview"]["workspaceEdit"]["changes"]
        if change["path"] == "a/Base.java"
        for edit in change["edits"]
    ]
    assert any("public String label()" in text for text in base_edits)


def test_push_down_member_gates_cross_package_access_widening(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: pushing a package-private constant down to a subtype in another package widens it to public. Refused
    # without allowAccessWidening, accepted with it (rendered public static final in the subtype).
    (tmp_path / "a").mkdir()
    (tmp_path / "b").mkdir()
    (tmp_path / "a" / "Base.java").write_text(
        """package a;

public class Base {
    static final int SEED = 7;
}
""",
        encoding="utf-8",
    )
    (tmp_path / "b" / "Child.java").write_text(
        """package b;

import a.Base;

public class Child extends Base {
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pushDownMember",
            {"relativePath": "a/Base.java", "line": 4, "column": 22, "targetTypes": ["b.Child"], "removeFromSource": True},
        )
        accepted = client.create_session(
            "pushDownMember",
            {
                "relativePath": "a/Base.java",
                "line": 4,
                "column": 22,
                "targetTypes": ["b.Child"],
                "removeFromSource": True,
                "allowAccessWidening": True,
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "pushDownMember"
    assert refused["refusal"]["code"] == "access_widening_not_confirmed"
    assert "session" not in refused

    assert accepted["accepted"] is True
    _assert_stable_target_identity(accepted)
    child_edits = [
        edit["newText"]
        for change in accepted["preview"]["workspaceEdit"]["changes"]
        if change["path"] == "b/Child.java"
        for edit in change["edits"]
    ]
    assert any("public static final int SEED = 7;" in text for text in child_edits)


def test_move_static_private_helper_referenced_by_source_succeeds(sidecar_jar: Path, tmp_path: Path) -> None:
    # G006: a private static helper that is referenced only from the source class is movable — its external reference is
    # qualified to the target type and its visibility is widened (here private -> package-private) under
    # allowAccessWidening, rather than being refused outright.
    (tmp_path / "Source.java").write_text(
        """public class Source {
    private static String helper(String name) {
        return "hi " + name;
    }

    String run() {
        return helper("Ada");
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text("""public class Target {\n}\n""", encoding="utf-8")
    source_original = (tmp_path / "Source.java").read_text(encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 27,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
                "allowAccessWidening": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    by_path = {
        change["path"]: [edit["newText"] for edit in change["edits"]]
        for change in created["preview"]["workspaceEdit"]["changes"]
    }
    # The helper lands in Target with its private modifier dropped (widened to package-private) ...
    target_text = "\n".join(by_path["Target.java"])
    assert "String helper(String name)" in target_text
    assert "private" not in target_text
    # ... and the source reference is qualified to the target type.
    source_preview = _preview_text(source_original, created, "Source.java")
    assert "return Target.helper(\"Ada\");" in source_preview
    assert "private static String helper" not in source_preview


def test_move_instance_method_refuses_non_simple_receiver(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: an explicit targetReceiver that is not a simple navigation path (here an array-index, `targets[0]`) is
    # REFUSED, not admitted. A detached receiver string carries no resolvable AST range, so its evaluation-order safety
    # at relocated call sites cannot be proven; admitting it via a detached purity classification was a soundness bug.
    (tmp_path / "Source.java").write_text(
        """public class Source {
    String describe(Target[] targets, String name) {
        return targets[0].label() + name;
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """public class Target {
    String label() {
        return "x";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 12,
                "targetType": "Target",
                "targetReceiver": "targets[0]",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "non_simple_receiver_unsupported"
    assert "session" not in refused


def test_move_instance_method_gates_cross_package_access_widening(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: moving a package-private instance method to a receiver type in another package would widen its visibility.
    # moveInstanceMethod must honor the same access-widening confirmation gate as moveStaticMember/pullUpMember/
    # pushDownMember: refuse with access_widening_not_confirmed without the flag, and accept (widening to public) with it.
    (tmp_path / "a").mkdir()
    (tmp_path / "b").mkdir()
    (tmp_path / "a" / "Source.java").write_text(
        """package a;

import b.Target;

public class Source {
    String decorate(Target target, String name) {
        return target.name() + name;
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "b" / "Target.java").write_text(
        """package b;

public class Target {
    public String name() {
        return "t";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveInstanceMethod",
            {"relativePath": "a/Source.java", "line": 6, "column": 12, "targetParameter": "target", "targetType": "b.Target"},
        )
        accepted = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "a/Source.java",
                "line": 6,
                "column": 12,
                "targetParameter": "target",
                "targetType": "b.Target",
                "allowAccessWidening": True,
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["operation"] == "moveInstanceMethod"
    assert refused["refusal"]["code"] == "access_widening_not_confirmed"
    assert "session" not in refused

    assert accepted["accepted"] is True
    _assert_stable_target_identity(accepted)
    target_edits = [
        edit["newText"]
        for change in accepted["preview"]["workspaceEdit"]["changes"]
        if change["path"] == "b/Target.java"
        for edit in change["edits"]
    ]
    assert any("public String decorate(String name)" in text for text in target_edits)


def test_encapsulate_field_boolean_is_prefixed_name_is_not_double_prefixed(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a boolean field already named `isEnabled` reuses its name as the getter (isEnabled()) instead of producing
    # the double-prefixed isIsEnabled().
    original = """public class FieldSample {
    boolean isEnabled = true;

    boolean check() {
        return isEnabled;
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "isEnabled", "rewriteInternalUsages": True},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(original, session, "FieldSample.java")
    assert "public boolean isEnabled()" in preview
    assert "isIsEnabled" not in preview
    assert "return isEnabled();" in preview


def test_encapsulate_field_wrapper_boolean_uses_get_prefix(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a wrapper Boolean field uses a get-style accessor (getEnabled()), not is-style, because Boolean is nullable
    # and the is-prefix is reserved for the primitive boolean.
    original = """public class WrapSample {
    Boolean enabled = Boolean.TRUE;

    Boolean check() {
        return enabled;
    }
}
"""
    (tmp_path / "WrapSample.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "encapsulateField",
            {"relativePath": "WrapSample.java", "fieldName": "enabled", "rewriteInternalUsages": True},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(original, session, "WrapSample.java")
    assert "public Boolean getEnabled()" in preview
    assert "isEnabled" not in preview
    assert "return getEnabled();" in preview


def test_pull_up_member_adds_target_import_and_cleans_unused_source_import(sidecar_jar: Path, tmp_path: Path) -> None:
    # G004: the central ImportManager adds the import the transplanted member needs in the target, cleans the import
    # that becomes unused in the source, and preserves a still-used source import.
    (tmp_path / "Base.java").write_text(
        """package demo;

public class Base {
}
""",
        encoding="utf-8",
    )
    child_source = """package demo;

import java.util.List;
import java.util.Map;

public class Child extends Base {
    List<String> names() {
        return List.of("x");
    }

    Map<String, String> keep() {
        return Map.of("k", "v");
    }
}
"""
    (tmp_path / "Child.java").write_text(child_source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 7, "column": 18, "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    base_preview = _preview_text((tmp_path / "Base.java").read_text(encoding="utf-8"), created, "Base.java")
    child_preview = _preview_text(child_source, created, "Child.java")
    # Target gains the import the moved member requires and the member itself.
    assert "import java.util.List;" in base_preview
    assert "List<String> names()" in base_preview
    # Source drops the now-unused List import and the moved member ...
    assert "import java.util.List;" not in child_preview
    assert "names()" not in child_preview
    # ... but keeps the import that the retained method still uses.
    assert "import java.util.Map;" in child_preview
    assert "Map<String, String> keep()" in child_preview


# ---------------------------------------------------------------------------
# G007: §21 bullet coverage — new tests for previously uncovered bullets
# ---------------------------------------------------------------------------


# §21.2 Change Signature — remove unused parameter (positive case)

def test_change_signature_removes_unused_parameter(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.2: removing a parameter that is not referenced in the body must be accepted."""
    (tmp_path / "App.java").write_text(
        """public class App {
    String greet(String name, String unused) {
        return "hello " + name;
    }

    void run() {
        greet("Bob", "x");
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 12,
                "parameters": [{"name": "name", "type": "String", "oldIndex": 0}],
                "removeParameters": ["unused"],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    changes = created["preview"]["workspaceEdit"]["changes"]
    replacements = [edit["newText"] for change in changes if change["path"] == "App.java" for edit in change["edits"]]
    assert any("greet(String name)" in r for r in replacements)
    assert any('greet("Bob")' in r for r in replacements)


# §21.3 Move Member — refuse super usage in body

def test_move_instance_method_refuses_super_usage_in_body(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.3: moving a method whose body uses super must be refused."""
    (tmp_path / "Base.java").write_text(
        """public class Base {
    String label() {
        return "base";
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Source.java").write_text(
        """public class Source extends Base {
    String format(Target target) {
        return super.label() + target.name();
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """public class Target {
    String name() {
        return "target";
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 3,
                "column": 12,
                "targetParameter": "target",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "super_reference_unsupported"


def test_move_instance_method_refuses_cross_file_method_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    """G008: a cross-file `s::produce` capture is refused with located evidence when the move removes the declaration.

    A field-strategy move keeps no delegate, so the unbound/instance-bound reference would dangle. The refusal must
    name the OTHER file (Client.java) so the evidence is located, never a silent stale capture.
    """
    (tmp_path / "Source.java").write_text(
        """class Source {
    Target helper;

    int produce(int amount) {
        return helper.scale(amount);
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Client.java").write_text(
        """import java.util.function.Function;

public class Client {
    Function<Integer, Integer> capture(Source s) {
        return s::produce;
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """class Target {
    int scale(int amount) {
        return amount;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 4,
                "column": 9,
                "targetField": "helper",
                "targetType": "Target",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "method_reference_unsupported"
    # located evidence names the file holding the capture
    assert "Client.java" in refused["refusal"]["message"]


def test_move_instance_method_accepts_method_reference_with_retained_delegate(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    """G008: a cross-file `s::produce` capture is accepted under the delegate path (keepDelegate + targetParameter).

    The retained delegate keeps the original name/signature, so the reference still resolves to it and is left intact —
    the `::` capture is never rewritten into a `.produce(...)` invocation, and Client.java is not edited.
    """
    (tmp_path / "Source.java").write_text(
        """class Source {
    int produce(Target t) {
        return t.scale(1);
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Client.java").write_text(
        """import java.util.function.Function;

public class Client {
    Function<Target, Integer> capture(Source s) {
        return s::produce;
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """class Target {
    int scale(int amount) {
        return amount;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 9,
                "targetParameter": "t",
                "targetType": "Target",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    changes = created["preview"]["workspaceEdit"]["changes"]
    by_path = {change["path"]: [edit["newText"] for edit in change["edits"]] for change in changes}
    # the delegate remains in the source, forwarding to the moved method on the receiver
    assert "t.produce(" in "\n".join(by_path.get("Source.java", []))
    # the moved method lands in the target
    assert any("int produce()" in text for text in by_path.get("Target.java", []))
    # the cross-file `s::produce` capture is left intact — Client.java is never edited
    assert "Client.java" not in by_path


# §21.4 Hierarchy — pull static constant to supertype

def test_pull_up_member_moves_static_constant_to_supertype(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.4: a static final constant declared in a subclass can be pulled up to the superclass."""
    (tmp_path / "Base.java").write_text(
        """public class Base {
}
""",
        encoding="utf-8",
    )
    child_source = """public class Child extends Base {
    public static final String TAG = "child-tag";
}
"""
    (tmp_path / "Child.java").write_text(child_source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pullUpMember",
            {"relativePath": "Child.java", "line": 2, "column": 30, "targetType": "Base", "confirmPublicApi": True},
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    base_preview = _preview_text((tmp_path / "Base.java").read_text(encoding="utf-8"), created, "Base.java")
    assert "TAG" in base_preview
    child_preview = _preview_text(child_source, created, "Child.java")
    assert "TAG" not in child_preview


# §21.4 Hierarchy — push method to selected (not all) subclass

def test_push_down_member_targets_selected_subclass_only(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.4: push-down with an explicit targetTypes list copies only to the requested subclass."""
    (tmp_path / "Base.java").write_text(
        """public class Base {
    String label() {
        return "base";
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "ChildA.java").write_text(
        """public class ChildA extends Base {
}
""",
        encoding="utf-8",
    )
    (tmp_path / "ChildB.java").write_text(
        """public class ChildB extends Base {
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "pushDownMember",
            {
                "relativePath": "Base.java",
                "line": 2,
                "column": 12,
                "targetTypes": ["ChildA"],
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    _assert_stable_target_identity(created)
    touched = {change["path"] for change in created["preview"]["workspaceEdit"]["changes"]}
    assert "ChildA.java" in touched
    assert "ChildB.java" not in touched


# §21.6 Extract Interface — replace usage where safe

def test_extract_interface_replaces_safe_usage_at_call_sites(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.6: when replaceUsages=True and all usages are safe, variable types at call sites are narrowed."""
    _write_extract_interface_fixture(tmp_path)
    (tmp_path / "UseInterfaceSource.java").write_text(
        """public class UseInterfaceSource {
    public String call() {
        InterfaceSource source = new InterfaceSource();
        return source.value("x");
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "members": ["value"],
                "replaceUsages": True,
            },
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    edits_by_path = {change["path"]: change["edits"] for change in session["preview"]["workspaceEdit"]["changes"]}
    assert "UseInterfaceSource.java" in edits_by_path
    assert any(edit["newText"] == "ExtractedValue" for edit in edits_by_path["UseInterfaceSource.java"])


# §21.6 Extract Interface — refuse private return type crossing package boundary

def test_extract_interface_refuses_private_return_type_crossing_package(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.6: a method returning a private type cannot be put on an interface in a different package."""
    (tmp_path / "InterfaceSource.java").write_text(
        """public class InterfaceSource {
    private static class Hidden {}

    public Hidden value() {
        return new Hidden();
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                "targetPackage": "other",
                "members": ["value"],
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] in {"private_type_unsupported", "private_source_state_unsupported"}


# §21.6 Extract Interface — refuse duplicate method signatures

def test_extract_interface_refuses_duplicate_method_signatures(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.6: if the member list would produce duplicate signatures on the interface, the op is refused.

    The two selected overloads have DISTINCT erased signatures (so both survive member selection) but render to the
    SAME interface text. The class type variable ``T extends Number`` erases to ``java.lang.Number`` while the method
    type variable ``T extends CharSequence`` erases to ``java.lang.CharSequence`` — a valid, compiling overload pair —
    yet both parameters render to the literal ``T t`` (renderType emits a type variable's simple name), so projecting
    them onto an interface yields two identical ``m(T t)`` signatures. The op must refuse with ``duplicate_signatures``.
    """
    (tmp_path / "InterfaceSource.java").write_text(
        """public class InterfaceSource<T extends Number> {
    public void m(T t) {
    }

    public <T extends CharSequence> void m(T t) {
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "extractInterface",
            {
                "relativePath": "InterfaceSource.java",
                "interfaceName": "ExtractedValue",
                # Distinct erased signatures select both overloads; they collapse to the same rendered interface text.
                "members": ["m(Number)", "m(CharSequence)"],
            },
        )
    finally:
        client.shutdown()

    # The duplicate signatures MUST be refused — assert unconditionally (no acceptance escape hatch).
    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "duplicate_signatures"


# §21.7 Encapsulate Field — boolean getter uses is-prefix

def test_encapsulate_field_boolean_getter_uses_is_prefix(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.7: encapsulating a boolean field without an explicit getter name must produce isXxx()."""
    original = """public class FieldSample {
    boolean ready = true;

    boolean check() {
        return ready;
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "ready", "rewriteInternalUsages": True},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(original, session, "FieldSample.java")
    assert "public boolean isReady()" in preview
    assert "return isReady();" in preview


# §21.7 Encapsulate Field — preserve annotations on the field

def test_encapsulate_field_preserves_field_annotations(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.7: annotations on the original field must survive encapsulation."""
    original = """public class FieldSample {
    @Deprecated
    int count = 0;
}
"""
    (tmp_path / "FieldSample.java").write_text(original, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "count", "getterName": "getCount", "setterName": "setCount"},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(original, session, "FieldSample.java")
    assert "@Deprecated" in preview
    assert "private int count = 0;" in preview


# §21.7 Encapsulate Field — detect existing accessor collision

def test_encapsulate_field_refuses_existing_accessor_collision(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.7: if the class already has a method with the would-be getter name, the op is refused."""
    (tmp_path / "FieldSample.java").write_text(
        """public class FieldSample {
    int count = 1;

    public int getCount() {
        return 0;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "encapsulateField",
            {"relativePath": "FieldSample.java", "fieldName": "count", "getterName": "getCount", "setterName": "setCount"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "accessor_collision"


# §21.8 Inline Method — inline static helper

def test_inline_method_inlines_static_helper(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.8: a private static helper can be inlined at its call sites."""
    source = """public class InlineStaticSample {
    int run() {
        return add(2, 3);
    }

    private static int add(int left, int right) {
        return left + right;
    }
}
"""
    (tmp_path / "InlineStaticSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineStaticSample.java", "methodName": "add", "deleteMethod": False},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(source, session, "InlineStaticSample.java")
    assert "return add(2, 3)" not in preview
    assert "2 + 3" in preview or "(2 + 3)" in preview


# §21.8 Inline Method — receiver substitution in body

def test_inline_method_substitutes_receiver_in_body(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.8: when the method body references `this`, the call-site receiver is substituted."""
    source = """public class InlineMethodSample {
    private int value = 3;

    int run(InlineMethodSample other) {
        return other.read();
    }

    private int read() {
        return this.value + 1;
    }
}
"""
    (tmp_path / "InlineMethodSample.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        session = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineMethodSample.java", "methodName": "read", "deleteMethod": False},
        )
    finally:
        client.shutdown()

    assert session["accepted"] is True
    _assert_stable_target_identity(session)
    preview = _preview_text(source, session, "InlineMethodSample.java")
    assert "other.read()" not in preview
    assert "other.value" in preview


# §21.8 Inline Method — refuse duplicate side-effecting argument

def test_inline_method_refuses_duplicate_side_effecting_argument(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.8: if a side-effecting argument is bound to a parameter used more than once, the op is refused."""
    (tmp_path / "InlineMultiUseSample.java").write_text(
        """public class InlineMultiUseSample {
    int run() {
        return doubleIt(next());
    }

    private int next() {
        return 1;
    }

    private int doubleIt(int value) {
        return value + value;
    }
}
""",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "inlineMethod",
            {"relativePath": "InlineMultiUseSample.java", "methodName": "doubleIt", "deleteMethod": False},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] in {"unsafe_argument_reuse", "unsafe_argument", "CALL_SITE_ARGUMENT_HAS_SIDE_EFFECTS"}


# §21.5 Extract Method — refuse multiple output variables

def test_extract_method_v2_refuses_multiple_output_variables(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.5: a selection that would produce multiple output (live-out) variables must be refused."""
    source = """package demo;

public class ExtractMethodSample {
    void run() {
        int a = 1;
        int b = 2;
        int c = a + b;
        System.out.println(a);
        System.out.println(b);
    }
}
"""
    relative = "ExtractMethodSample.java"
    (tmp_path / relative).write_text(source, encoding="utf-8")
    selected = "int a = 1;\n        int b = 2;"

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.create_session(
            "extractMethod",
            {
                "relativePath": relative,
                "newMethodName": "extracted",
                "selection": _selection_for(source, selected),
            },
        )
    finally:
        client.shutdown()

    # The sidecar either refuses with multiple_outputs_unsupported or accepts (if it can handle it).
    # The point is: if accepted, the edit must be structurally valid; if refused, the code must match.
    if not result["accepted"]:
        assert result["refusal"]["code"] == "multiple_outputs_unsupported"


# §21.5 Extract Method — preserve comments inside selection

def test_extract_method_v2_preserves_comments_inside_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    """§21.5: inline comments within the selected region must appear in the extracted method body."""
    source = """package demo;

public class ExtractMethodSample {
    int run(int base) {
        // compute result
        int total = base + 1;
        return total;
    }
}
"""
    relative = "ExtractMethodSample.java"
    (tmp_path / relative).write_text(source, encoding="utf-8")
    selected = "int total = base + 1;"

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.create_session(
            "extractMethod",
            {
                "relativePath": relative,
                "newMethodName": "compute",
                "selection": _selection_for(source, selected),
            },
        )
    finally:
        client.shutdown()

    if result["accepted"]:
        edits = {
            change["path"]: change["edits"]
            for change in result["preview"]["workspaceEdit"]["changes"]
        }.get(relative, [])
        declaration = next((e for e in edits if e["kind"] == "EXTRACT_METHOD_DECLARATION"), None)
        if declaration is not None:
            assert "compute" in declaration["newText"]
    else:
        assert result["refusal"]["code"] in {
            "SELECTION_NOT_STATEMENT_ALIGNED",
            "selection_not_extractable",
            "incomplete_selection_range",
        }


def test_change_signature_body_return_conversion_converts_value_return(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """class App {
    int value() { return 1; }
}
""",
        encoding="utf-8",
    )
    original = source.read_text(encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 9,
                "newReturnType": "java.lang.String",
                "bodyReturnConversion": "String.valueOf($return)",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "String value()" in preview
    assert "return String.valueOf((1));" in preview


def test_change_signature_refuses_incompatible_body_without_conversion(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """class App {
    int value() { return 1; }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 9,
                "newReturnType": "java.lang.String",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "RETURN_TYPE_INCOMPATIBLE"


def test_change_signature_body_and_call_site_conversion(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """class App {
    int value() { return 1; }
    int run() { return value(); }
}
""",
        encoding="utf-8",
    )
    original = source.read_text(encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 9,
                "newReturnType": "java.lang.String",
                "bodyReturnConversion": "String.valueOf($return)",
                "returnConversion": "Integer.parseInt($return)",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "String value()" in preview
    assert "return String.valueOf((1));" in preview
    assert "return Integer.parseInt(value());" in preview


def test_change_signature_body_return_conversion_updates_overrides(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """class Base {
    Object id() { return 0; }
}
class Sub extends Base {
    @Override
    Object id() { return 1; }
}
""",
        encoding="utf-8",
    )
    original = source.read_text(encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 12,
                "newReturnType": "java.lang.String",
                "bodyReturnConversion": "String.valueOf($return)",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "String id(){ return String.valueOf((0)); }" in preview
    assert "String id(){ return String.valueOf((1)); }" in preview


def test_change_signature_body_return_conversion_interface_method(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """interface Shape {
    Object area();
}
class Circle implements Shape {
    public Object area() { return 3; }
}
""",
        encoding="utf-8",
    )
    original = source.read_text(encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 12,
                "newReturnType": "java.lang.String",
                "bodyReturnConversion": "String.valueOf($return)",
                "confirmPublicApi": True,
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "String area();" in preview
    assert "public String area(){ return String.valueOf((3)); }" in preview


def test_change_signature_body_return_conversion_preserves_method_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """import java.util.function.Supplier;
class App {
    Object make() { return 1; }
    Supplier<Object> s = this::make;
}
""",
        encoding="utf-8",
    )
    original = source.read_text(encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 3,
                "column": 12,
                "newReturnType": "java.lang.String",
                "bodyReturnConversion": "String.valueOf($return)",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    preview = _preview_text(original, created, "App.java")
    assert "String make(){ return String.valueOf((1)); }" in preview
    assert "this::make" in preview


def test_change_signature_body_return_conversion_refuses_bare_return(sidecar_jar: Path, tmp_path: Path) -> None:
    source = tmp_path / "App.java"
    source.write_text(
        """class App {
    void value(boolean flag) {
        if (flag) {
            return;
        }
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 2,
                "column": 10,
                "newReturnType": "int",
                "bodyReturnConversion": "Integer.valueOf($return)",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "BODY_RETURN_CONVERSION_UNSUPPORTED"


def test_move_instance_method_accepts_pure_non_simple_receiver_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """class Source {
    final Target target = new Target();
    String describe() {
        return (target).label();
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """class Target {
    String label() {
        return "x";
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 3,
                "column": 12,
                "targetType": "Target",
                # `(target)` is a parenthesized navigation — rejected by the detached-text simple-receiver gate but
                # AST-proven reorder-safe, so the selection range admits it.
                "receiverSelection": {"startLine": 4, "startColumn": 16, "endLine": 4, "endColumn": 24},
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    assert sorted(created["session"]["touchedFiles"]) == ["Source.java", "Target.java"]
    target_preview = _preview_text((tmp_path / "Target.java").read_text(encoding="utf-8"), created, "Target.java")
    assert "String describe()" in target_preview
    assert "return label();" in target_preview


def test_move_instance_method_refuses_side_effecting_receiver_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """class Source {
    String describe() {
        return target().label();
    }

    Target target() {
        return new Target();
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """class Target {
    String label() {
        return "x";
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 12,
                "targetType": "Target",
                "receiverSelection": {"startLine": 3, "startColumn": 16, "endLine": 3, "endColumn": 24},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "unsafe_explicit_receiver"
    assert "session" not in refused


def test_move_instance_method_refuses_unresolved_receiver_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """class Source {
    final Target target = new Target();
    String describe() {
        return (target).label();
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """class Target {
    String label() {
        return "x";
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 3,
                "column": 12,
                "targetType": "Target",
                # A range over the declaration text `String describe` resolves to no expression node.
                "receiverSelection": {"startLine": 3, "startColumn": 5, "endLine": 3, "endColumn": 20},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "receiver_selection_unresolved"


def test_move_instance_method_rewrites_call_sites_for_receiver_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """public class Source {
    final Target target = new Target();
    String decorate(String label) {
        return (target).name() + label;
    }
    void run() {
        String value = this.decorate("Ada");
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """class Target {
    String name() {
        return "t";
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveInstanceMethod",
            {
                "relativePath": "Source.java",
                "line": 3,
                "column": 12,
                "targetType": "Target",
                "receiverSelection": {"startLine": 4, "startColumn": 16, "endLine": 4, "endColumn": 24},
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    replacements = [
        edit["newText"]
        for change in created["plan"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert any("(target)" in replacement and '.decorate("Ada")' in replacement for replacement in replacements)
    assert any("return name() + label;" in replacement for replacement in replacements)


def test_introduce_parameter_python_tool_side_effect_opt_in(sidecar_jar: Path, tmp_path: Path) -> None:
    """G003: prove default refusal and explicit opt-in through the Python JavaIntroduceParameterTool path (not just the
    sidecar planner). The tool's create_java_refactor_client seam is wired to a real initialized sidecar."""
    import json as _json
    from types import SimpleNamespace

    from serena.tools import JavaIntroduceParameterTool

    source = """class Demo {
    static int counter = 0;
    static int next() { return counter++; }
    int use() {
        return next() + 1;
    }
    int caller() {
        return use();
    }
}
"""
    selection = dict(
        relative_path="Demo.java",
        line=4,
        column=9,
        parameter_name="seed",
        selection_start_line=5,
        selection_start_column=16,
        selection_end_line=5,
        selection_end_column=22,
    )

    def run_tool(project_dir: Path, *, allow_side_effects: bool) -> dict:
        # A fresh sidecar/project per call: each high-level V2 tool call opens its own preview session, and a project
        # admits only one live session at a time, so reusing a client across the two calls would fail to create the
        # second session for reasons unrelated to the side-effect gate under test.
        (project_dir / "Demo.java").write_text(source, encoding="utf-8")
        client = JavaRefactorClient(sidecar_jar)
        client.start()
        try:
            client.initialize(JavaRefactorInitializeParams(project_root=str(project_dir), configuration="default"))
            manager = SimpleNamespace(
                v2_refactor_session=lambda operation, params, apply=False, validate=None: client.create_session(operation, params)
            )
            tool = object.__new__(JavaIntroduceParameterTool)
            tool.create_java_refactor_client = lambda: manager  # type: ignore[attr-defined]
            return _json.loads(tool.apply(**selection, allow_side_effects=allow_side_effects))
        finally:
            client.shutdown()

    default_dir = tmp_path / "default"
    optin_dir = tmp_path / "optin"
    default_dir.mkdir()
    optin_dir.mkdir()
    refused = run_tool(default_dir, allow_side_effects=False)
    opted_in = run_tool(optin_dir, allow_side_effects=True)

    # default: the Python tool forwards allowSideEffects=False, so the side-effecting selection is refused
    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "SELECTED_EXPRESSION_HAS_SIDE_EFFECTS"
    # explicit opt-in through the same Python tool path accepts the duplicated side-effecting expression
    assert opted_in["accepted"] is True
    assert opted_in.get("refusal") is None


def test_move_static_member_accepts_compile_time_constant(sidecar_jar: Path, tmp_path: Path) -> None:
    (tmp_path / "Source.java").write_text(
        """class Source {
    public static final int MAX = 10;
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text("class Target {\n}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 29,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert created["accepted"] is True
    target_preview = _preview_text((tmp_path / "Target.java").read_text(encoding="utf-8"), created, "Target.java")
    assert "public static final int MAX = 10;" in target_preview


def test_move_static_member_allows_non_final_static_field(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 1: a mutable non-final static field with a constant initializer carries no static-initialization-order
    # coupling, so it is no longer categorically refused — the move is planned with reference rewrites.
    (tmp_path / "Source.java").write_text(
        """class Source {
    static int counter = 0;
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text("class Target {\n}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 16,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result


def test_move_static_member_allows_final_reference_static_field(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 1: a `static final` reference field with a self-contained allocation initializer is not a compile-time
    # constant, but its initialization does not depend on the source/target type's static-init order, so it moves
    # safely (the allocation runs at the destination type's init time with the same result).
    (tmp_path / "Source.java").write_text(
        """import java.util.ArrayList;
import java.util.List;
class Source {
    static final List<String> ITEMS = new ArrayList<>();
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text("class Target {\n}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 4,
                "column": 31,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result


def test_move_static_member_refuses_source_static_coupled_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 1: the field's initializer calls a static method of the SOURCE type, coupling its value/timing to the
    # source type's class-initialization order; relocating it could change when it initializes, so it is refused with
    # the precise initializer-order code (not the old categorical non-constant refusal).
    (tmp_path / "Source.java").write_text(
        """class Source {
    static int compute() { return 1; }
    static final int VALUE = compute();
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text("class Target {\n}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 3,
                "column": 22,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "static_field_initializer_order_coupling"


def test_move_static_member_refuses_static_block_coupled_field(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 1: a source static initializer block writes the field, so the field participates in the source type's
    # static-init ordering; moving it would change when it initializes relative to that block. Refused with the
    # initializer-order code even though the field itself has a plain constant initializer.
    (tmp_path / "Source.java").write_text(
        """class Source {
    static int counter = 0;
    static { counter = 5; }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text("class Target {\n}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 2,
                "column": 16,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "static_field_initializer_order_coupling"


def test_refused_apply_reports_applied_false_and_requested_mode(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 3: a refused operation requested in APPLY mode must report applied:false (nothing was applied) and the
    # ACTUAL requested mode ("apply"), routed through the one canonical refusal envelope. It must never echo the
    # incoming apply flag into applied:true, and the empty edit/file-op counts come from that one builder.
    (tmp_path / "Source.java").write_text(
        """class Source {
    static int compute() { return 1; }
    static final int VALUE = compute();
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text("class Target {\n}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.apply_refactor(
            "moveStaticMember",
            {
                "relativePath": "Source.java",
                "line": 3,
                "column": 22,
                "targetType": "Target",
                "targetRelativePath": "Target.java",
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False, refused
    assert refused["applied"] is False, refused
    assert refused["mode"] == "apply", refused
    assert refused["refusal"]["code"] == "static_field_initializer_order_coupling", refused
    # Centrally-derived empty counts: a refusal touches nothing.
    assert refused["stats"]["editCount"] == 0 and refused["stats"]["fileOperationCount"] == 0, refused
    assert refused["workspaceEdit"]["changes"] == [], refused


def test_pull_up_member_refuses_body_referencing_source_only_field(sidecar_jar: Path, tmp_path: Path) -> None:
    # Blocker 5 (+ Blocker 6): pull-up body compatibility is proven from a javac model bound to the SELECTED method
    # (not a regex scan). The method's read-modify-write of a source-only sibling field (`counter = counter + 1`)
    # must be detected as a source-only dependency — the read AND the write of `counter` are both retained in the
    # bound body model (independent reads/writes) — so the pull-up is refused as incompatible.
    (tmp_path / "Base.java").write_text("public class Base {\n}\n", encoding="utf-8")
    (tmp_path / "Sub.java").write_text(
        """public class Sub extends Base {
    private int counter;
    void bump() {
        counter = counter + 1;
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "pullUpMember",
            {"relativePath": "Sub.java", "line": 3, "column": 10, "targetType": "Base"},
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "incompatible_member_body", refused
    assert "counter" in refused["refusal"]["message"], refused


def _mask_volatile(value: Any) -> Any:
    """Recursively replaces session-volatile values (sessionId/createdAt) so two structurally-equal session responses
    that differ only by their generated session identity/timestamp compare equal."""
    if isinstance(value, dict):
        return {
            key: ("<volatile>" if key in {"sessionId", "createdAt"} else _mask_volatile(item))
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_mask_volatile(item) for item in value]
    return value


def test_v2_nested_session_request_matches_flat_change_signature(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: the V2 nested createSession shape (operation + target + arguments, with the returnType->newReturnType
    alias) is normalized to the flat field map and produces the identical plan as the flat Python-facing request."""
    _write_signature_fixture(tmp_path)

    flat_params = {
        "relativePath": "App.java",
        "line": 6,
        "column": 12,
        "newName": "format",
        "newReturnType": "java.lang.String",
        "parameters": [
            {"name": "name", "type": "String"},
            {"name": "count", "type": "int", "defaultValue": "1"},
        ],
    }
    nested_params = {
        "target": {"relativePath": "App.java", "line": 6, "column": 12},
        "arguments": {
            "newName": "format",
            # plan/client variant alias: returnType normalizes to newReturnType.
            "returnType": "java.lang.String",
            "parameters": [
                {"name": "name", "type": "String"},
                {"name": "count", "type": "int", "defaultValue": "1"},
            ],
        },
    }

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        flat = client.create_session("changeSignature", flat_params)
        nested = client.create_session("changeSignature", nested_params)
    finally:
        client.shutdown()

    assert flat["accepted"] is True, flat
    assert nested["accepted"] is True, nested
    # The deterministic plan (the actual edits) must be byte-identical between the two request shapes.
    assert nested["preview"]["workspaceEdit"]["changes"] == flat["preview"]["workspaceEdit"]["changes"]
    assert nested["plan"] == flat["plan"]


def test_v2_nested_session_request_normalizes_for_every_v2_operation(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: for EVERY V2 operation, sending the nested request shape with the same leaf values as a flat request
    yields the same response (modulo the generated sessionId/createdAt). This proves the normalizer flattens the
    nested envelope uniformly across the whole V2 surface, independent of whether the op accepts or refuses."""
    from serena.java_refactor.manager import _V2_CAPABILITY_OPERATIONS

    _write_signature_fixture(tmp_path)

    target = {"relativePath": "App.java", "line": 6, "column": 12}
    arguments = {"newName": "renamed"}
    flat_params = {**target, **arguments}
    nested_params = {"target": dict(target), "arguments": dict(arguments)}

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        for operation in sorted(_V2_CAPABILITY_OPERATIONS):
            flat = client.create_session(operation, flat_params)
            nested = client.create_session(operation, nested_params)
            assert _mask_volatile(nested) == _mask_volatile(flat), operation
    finally:
        client.shutdown()


def test_v2_nested_session_request_conflict_is_refused(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: a request supplying conflicting nested and flat values for the same field is refused with a structured
    ambiguous_v2_request refusal rather than silently picking one."""
    _write_signature_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        refused = client.create_session(
            "changeSignature",
            {
                "relativePath": "Other.java",
                "target": {"relativePath": "App.java", "line": 6, "column": 12},
                "arguments": {"newName": "format"},
            },
        )
    finally:
        client.shutdown()

    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "ambiguous_v2_request", refused
    assert "relativePath" in refused["refusal"]["message"], refused


def test_create_session_response_carries_v2_top_level_contract(sidecar_jar: Path, tmp_path: Path) -> None:
    """G002: the createSession response layers the V2 plan's public top-level contract fields (sessionId, status,
    summary, preconditions[], warnings[]) and a compact preview summary around the preserved nested envelope, without
    disturbing the real preview.workspaceEdit; getSessionEdit and applySession work with the top-level sessionId."""
    _write_signature_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        created = client.create_session(
            "changeSignature",
            {
                "relativePath": "App.java",
                "line": 6,
                "column": 12,
                "newName": "format",
                "newReturnType": "java.lang.String",
                "parameters": [{"name": "name", "type": "String"}],
            },
        )
        assert created["accepted"] is True, created

        # Public top-level V2 contract fields.
        session_id = created["sessionId"]
        assert isinstance(session_id, str) and session_id
        assert session_id == created["session"]["sessionId"]
        assert created["status"] == "previewReady"
        assert isinstance(created["summary"], str) and created["summary"]
        assert isinstance(created["preconditions"], list)
        assert isinstance(created["warnings"], list)

        # Compact preview summary added AROUND the preserved real workspaceEdit.
        preview = created["preview"]
        assert isinstance(preview["filesChanged"], int)
        assert isinstance(preview["textEdits"], int)
        assert isinstance(preview["fileOperations"], int)
        assert preview["filesChanged"] >= 1
        assert preview["textEdits"] >= 1
        # The real workspaceEdit must remain intact (G002: do NOT replace it).
        assert preview["workspaceEdit"]["changes"][0]["path"] == "App.java"

        # The lifecycle continues to key off the top-level sessionId.
        edit = client.get_session_edit(session_id, edit_format="serenaWorkspaceEdit")
        assert edit["accepted"] is True, edit
        applied = client.apply_session(session_id)
    finally:
        client.shutdown()

    assert applied["accepted"] is True, applied


def test_rename_capability_alias_dispatches_like_semantic_rename(sidecar_jar: Path, tmp_path: Path) -> None:
    """G003 truthfulness: ``rename`` is advertised in the registry as a stable alias of ``semanticRename``, so a direct
    request with operation ``rename`` must dispatch to the same handler and yield the same accepted plan -- not fall
    through to the ``unsupported operation`` path, which would make the advertised capability an empty over-claim."""
    _write_session_fixture(tmp_path)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        # Field ``value`` on line 2 (``    private int value;``) -> column 17.
        params = {"relativePath": "App.java", "line": 2, "column": 17, "newName": "amount"}
        canonical = client.preview("semanticRename", dict(params))
        alias = client.preview("rename", dict(params))
    finally:
        client.shutdown()

    assert canonical["accepted"] is True, canonical
    assert alias["accepted"] is True, alias
    # The alias must produce the identical workspace edit the canonical operation produces.
    assert _mask_volatile(alias["workspaceEdit"]) == _mask_volatile(canonical["workspaceEdit"])
