"""Hard-gate regression suite: the V2 blockers G001-G004 are enforced by the REAL sidecar jar, not just documented.

These drive the actual jar end-to-end through ``JavaRefactorClient.create_session`` and assert the gates hold even
when a hostile ``configuration`` tries to turn them off:

* encapsulateField refuses ``x += n`` / ``x++`` / ``++x`` / ``x--`` / ``--x`` and assignment-value-used contexts
  regardless of config (G002);
* extractMethod refuses multiple outputs and nonlocal control-flow exits regardless of config (G003);
* the capabilities registry does not advertise the blocked sub-features as supported (G004);
* synthesized code honors the inferred brace style and final-parameter convention across every V2 operation that
  generates source (G001).
"""

import json
from pathlib import Path
from typing import Any

import pytest

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from test.serena._java_refactor_sidecar_helpers import sidecar_jar  # noqa: F401

# A configuration that tries to turn OFF every V2 hard gate. The jar must ignore these keys: extractMethod must still
# refuse multi-output/control-flow, and encapsulateField must still refuse compound/increment usages.
_HOSTILE_CONFIG = json.dumps(
    {
        "enabled": True,
        "extract_method": {"allow_multiple_outputs": True, "allow_control_flow_exits": True},
        "encapsulate_field": {"refuse_compound_assignments": False},
    }
)


def _session(sidecar_jar: Path, project_root: Path, operation: str, params: dict[str, Any], configuration: str) -> dict[str, Any]:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration=configuration))
        return client.create_session(operation, params)
    finally:
        client.shutdown()


def _capabilities(sidecar_jar: Path, project_root: Path) -> dict[str, dict[str, Any]]:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        # The public ``capabilities`` map renders op->level strings; the {level,status,description} object lives in
        # the sibling ``capabilityDetails`` (V2 plan contract, G003).
        return client.capabilities()["capabilityDetails"]  # type: ignore[index,return-value]
    finally:
        client.shutdown()


def _name_position(source: str, token: str) -> tuple[int, int]:
    index = source.index(token)
    line = source.count("\n", 0, index) + 1
    column = index - (source.rfind("\n", 0, index) + 1) + 1
    return line, column


def _selection(source: str, snippet: str) -> dict[str, int]:
    start = source.index(snippet)
    end = start + len(snippet)

    def line_column(offset: int) -> tuple[int, int]:
        line = source.count("\n", 0, offset) + 1
        return line, offset - (source.rfind("\n", 0, offset) + 1) + 1

    start_line, start_column = line_column(start)
    end_line, end_column = line_column(end)
    return {"startLine": start_line, "startColumn": start_column, "endLine": end_line, "endColumn": end_column}


def _all_new_texts(session: dict[str, Any]) -> str:
    return "\n".join(
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    )


def _target_texts(session: dict[str, Any], path: str) -> str:
    return "\n".join(
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        if change["path"] == path
        for edit in change["edits"]
    )


def _new_file_contents(session: dict[str, Any]) -> str:
    return "\n".join(op.get("content", "") for op in session["preview"]["workspaceEdit"]["fileOperations"])


# ── G002: encapsulateField refuses every compound/increment form regardless of config ────────────────────────────────


@pytest.mark.parametrize(
    "statement,label",
    [
        ("count += value;", "compound_add"),
        ("count++;", "postfix_increment"),
        ("++count;", "prefix_increment"),
        ("count--;", "postfix_decrement"),
        ("--count;", "prefix_decrement"),
    ],
)
def test_encapsulate_field_refuses_compound_forms_regardless_of_config(
    sidecar_jar: Path, tmp_path: Path, statement: str, label: str
) -> None:
    """G002: every compound-assignment and increment/decrement form is refused even with a config that opts out."""
    source = (
        "package demo;\n"
        "public class Sample {\n"
        "    int count = 1;\n"
        "    void bump(int value) {\n"
        f"        {statement}\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")
    refused = _session(
        sidecar_jar,
        tmp_path,
        "encapsulateField",
        {"relativePath": "Sample.java", "fieldName": "count", "rewriteInternalUsages": True},
        _HOSTILE_CONFIG,
    )
    assert refused["accepted"] is False, (label, refused)
    assert refused["refusal"]["code"] == "compound_field_usage", (label, refused)
    # No expression-preserving accessor rewrite is ever produced.
    assert "session" not in refused, refused


def test_encapsulate_field_refuses_compound_value_used_regardless_of_config(sidecar_jar: Path, tmp_path: Path) -> None:
    """G002: a compound assignment whose result value is consumed is refused even with a config that opts out."""
    source = (
        "package demo;\n"
        "public class Sample {\n"
        "    int count = 1;\n"
        "    int bump(int value) {\n"
        "        int x = (count += value);\n"
        "        return x;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")
    refused = _session(
        sidecar_jar,
        tmp_path,
        "encapsulateField",
        {"relativePath": "Sample.java", "fieldName": "count", "rewriteInternalUsages": True},
        _HOSTILE_CONFIG,
    )
    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "compound_field_usage", refused


# ── G003: extractMethod refuses multi-output / control-flow regardless of config ─────────────────────────────────────


def test_extract_method_refuses_multiple_outputs_regardless_of_config(sidecar_jar: Path, tmp_path: Path) -> None:
    """G003: a selection that writes two later-read variables is refused even with allow_multiple_outputs in config."""
    source = (
        "public class Multi {\n"
        "    int run(int a, int b) {\n"
        "        int lo = a;\n"
        "        int hi = b;\n"
        "        return lo + hi;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Multi.java").write_text(source, encoding="utf-8")
    refused = _session(
        sidecar_jar,
        tmp_path,
        "extractMethod",
        {"relativePath": "Multi.java", "newMethodName": "compute", "selection": _selection(source, "int lo = a;\n        int hi = b;")},
        _HOSTILE_CONFIG,
    )
    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "multiple_outputs_unsupported", refused


def test_extract_method_refuses_control_flow_exit_regardless_of_config(sidecar_jar: Path, tmp_path: Path) -> None:
    """G003: a selection that escapes via return is refused even with allow_control_flow_exits in config."""
    source = (
        "public class Guarded {\n"
        "    int run(int a) {\n"
        "        if (a < 0) {\n"
        "            return 0;\n"
        "        }\n"
        "        return a + 1;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Guarded.java").write_text(source, encoding="utf-8")
    refused = _session(
        sidecar_jar,
        tmp_path,
        "extractMethod",
        {"relativePath": "Guarded.java", "newMethodName": "guard", "selection": _selection(source, "if (a < 0) {\n            return 0;\n        }")},
        _HOSTILE_CONFIG,
    )
    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "control_flow_unsupported", refused


# ── G004: capabilities do not advertise blocked sub-features as supported ─────────────────────────────────────────────


def test_capabilities_do_not_advertise_blocked_subfeatures(sidecar_jar: Path, tmp_path: Path) -> None:
    """G004: the registry must not advertise compound-assignment / multi-output / control-flow extraction as supported."""
    (tmp_path / "Sample.java").write_text("public class Sample {}\n", encoding="utf-8")
    caps = _capabilities(sidecar_jar, tmp_path)

    encapsulate = caps["encapsulateField"]["description"].lower()
    assert "including compound assignment" not in encapsulate, encapsulate
    # Any mention of "compound" must be a refusal statement, never a supported-feature claim.
    assert "compound" not in encapsulate or "refus" in encapsulate, encapsulate

    extract = caps["extractMethod"]["description"].lower()
    assert "refus" in extract, extract
    # multi-output / control-flow may only appear in the refusal clause.
    assert "multi-output" not in extract or "refuses multi-output" in extract, extract
    assert "control-flow" not in extract or "control-flow-exit" in extract, extract


# ── G001: synthesized code honors brace style + final-parameter convention across V2 code-gen operations ─────────────


def _assert_allman_signature(text: str, signature: str) -> None:
    """Assert ``signature`` appears followed by an own-line opening brace (Allman), never an appended ``{``."""
    assert f"{signature} {{" not in text, f"expected Allman (own-line) brace, found K&R for '{signature}':\n{text}"
    assert f"{signature}\n" in text, f"signature not rendered as expected: '{signature}' in:\n{text}"


def test_extract_method_synthesis_honors_allman_and_final_params(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: an extracted helper honors Allman braces and the inferred final-parameter style."""
    source = (
        "package demo;\n"
        "public class Sample\n"
        "{\n"
        "    int run(final int a, final int b)\n"
        "    {\n"
        "        int sum = a + b;\n"
        "        return sum;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")
    session = _session(
        sidecar_jar,
        tmp_path,
        "extractMethod",
        {"relativePath": "Sample.java", "newMethodName": "addUp", "selection": _selection(source, "int sum = a + b;")},
        "default",
    )
    assert session["accepted"] is True, session
    text = _all_new_texts(session)
    _assert_allman_signature(text, "private int addUp(final int a, final int b)")


def test_encapsulate_field_synthesis_honors_allman_and_final_params(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: synthesized accessors honor Allman braces and the inferred final-parameter style on the setter."""
    source = (
        "package demo;\n"
        "public class Sample\n"
        "{\n"
        "    int count;\n"
        "    void use(final int v)\n"
        "    {\n"
        "        count = v;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")
    session = _session(
        sidecar_jar,
        tmp_path,
        "encapsulateField",
        {"relativePath": "Sample.java", "fieldName": "count"},
        "default",
    )
    assert session["accepted"] is True, session
    text = _all_new_texts(session)
    _assert_allman_signature(text, "public int getCount()")
    _assert_allman_signature(text, "public void setCount(final int value)")


def test_introduce_field_constructor_synthesis_honors_allman(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: the constructor synthesized by introduceField honors Allman braces."""
    source = (
        "package demo;\n"
        "public class Sample\n"
        "{\n"
        "    int run()\n"
        "    {\n"
        "        return 1 + 1;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")
    session = _session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {"relativePath": "Sample.java", "fieldName": "count", "fieldType": "int", "initializeInConstructor": True, "selection": _selection(source, "1 + 1")},
        "default",
    )
    assert session["accepted"] is True, session
    text = _all_new_texts(session)
    _assert_allman_signature(text, "Sample()")


def test_extract_interface_synthesis_honors_allman(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: the synthesized interface file honors Allman braces on the interface declaration."""
    source = (
        "package demo;\n"
        "public class Greeter\n"
        "{\n"
        "    public String greet()\n"
        "    {\n"
        '        return "hi";\n'
        "    }\n"
        "}\n"
    )
    (tmp_path / "Greeter.java").write_text(source, encoding="utf-8")
    session = _session(
        sidecar_jar,
        tmp_path,
        "extractInterface",
        {"relativePath": "Greeter.java", "interfaceName": "Named", "members": ["greet"]},
        "default",
    )
    assert session["accepted"] is True, session
    content = _new_file_contents(session)
    assert "interface Named {" not in content, content
    assert "interface Named\n{" in content, content


def test_pull_up_delegate_synthesis_honors_allman_and_final_params(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: the forwarding delegate left by a pull-up honors Allman braces and the inferred final-parameter style."""
    (tmp_path / "Base.java").write_text("package demo;\npublic class Base\n{\n}\n", encoding="utf-8")
    child = (
        "package demo;\n"
        "public class Child extends Base\n"
        "{\n"
        "    public int score(final int n)\n"
        "    {\n"
        "        return n + 1;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Child.java").write_text(child, encoding="utf-8")
    line, column = _name_position(child, "score")
    session = _session(
        sidecar_jar,
        tmp_path,
        "pullUpMember",
        {"relativePath": "Child.java", "line": line, "column": column, "targetType": "Base", "leaveDelegate": True, "confirmPublicApi": True},
        "default",
    )
    assert session["accepted"] is True, session
    delegate = _target_texts(session, "Child.java")
    assert "@Override" in delegate, delegate
    _assert_allman_signature(delegate, "public int score(final int n)")


def test_move_instance_delegate_synthesis_honors_allman_and_final_params(sidecar_jar: Path, tmp_path: Path) -> None:
    """G001: the delegate retained by a moveInstanceMethod honors Allman braces and the inferred final-parameter style."""
    # The delegate reproduces the source signature verbatim, so a `final` NON-receiver parameter proves final-parameter
    # preservation; the receiver itself is left non-final (a `final` receiver trips an unrelated move-parser path).
    source = (
        "package demo;\n"
        "public class Source\n"
        "{\n"
        "    String format(Target target, final int n)\n"
        "    {\n"
        "        return target.name() + n;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Source.java").write_text(source, encoding="utf-8")
    (tmp_path / "Target.java").write_text(
        "package demo;\npublic class Target\n{\n    String name()\n    {\n        return \"t\";\n    }\n}\n", encoding="utf-8"
    )
    line, column = _name_position(source, "format")
    session = _session(
        sidecar_jar,
        tmp_path,
        "moveInstanceMethod",
        {"relativePath": "Source.java", "line": line, "column": column, "targetParameter": "target", "keepDelegate": True},
        "default",
    )
    assert session["accepted"] is True, session
    delegate = _target_texts(session, "Source.java")
    _assert_allman_signature(delegate, "String format(Target target, final int n)")
