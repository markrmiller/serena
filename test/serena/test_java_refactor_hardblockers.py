"""HB v2-hardblockers: end-to-end acceptance tests for the NEW HB-1..HB-11 hard-blocker set.

These drive the REAL sidecar jar (the ``sidecar_jar`` fixture compiles/locates it) through the canonical V2
session entry points -- ``JavaRefactorClient.create_session`` for raw sidecar planning and the
``JavaRefactorManager`` for the manager-level preview envelope (validated diagnostic delta, one-shot-apply
refusal). Every blocker's key edge cases are asserted as concrete outcomes (accepted + exact rendered member
text, or a structured refusal code), never as smoke tests. The acceptance matrix in
``test_java_refactor_acceptance_matrix.py`` maps each row here so the design-audit guards cover them.
"""

from pathlib import Path
from typing import Any

import pytest

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.manager import _V2_CAPABILITY_OPERATIONS, JavaRefactorManager
from serena.java_refactor.models import JavaRefactorInitializeParams
from solidlsp.ls_config import Language
from test.serena._java_refactor_sidecar_helpers import sidecar_jar  # noqa: F401


# --- shared sidecar drivers ------------------------------------------------------------------------------------------


def _create_session(sidecar_jar: Path, project_root: Path, operation: str, params: dict[str, Any]) -> dict[str, Any]:
    """Runs one real V2 session preview through the sidecar and returns the raw envelope."""
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
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


def _manager(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, sidecar_jar: Path, **config: bool) -> JavaRefactorManager:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    return JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, **config),
    )


def _target_texts(session: dict[str, Any], path: str) -> str:
    """The concatenation of every new-text inserted into ``path`` by a session preview's workspace edit."""
    return "\n".join(
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        if change["path"] == path
        for edit in change["edits"]
    )


def _all_new_texts(session: dict[str, Any]) -> str:
    return "\n".join(
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    )


def _apply_to_path(session: dict[str, Any], path: str, original: str) -> str:
    """Splices a session preview's edits for ``path`` into ``original`` (high offsets first)."""
    edits = [
        edit
        for change in session["preview"]["workspaceEdit"]["changes"]
        if change["path"] == path
        for edit in change["edits"]
    ]
    for edit in sorted(edits, key=lambda item: item["startOffset"], reverse=True):
        original = original[: edit["startOffset"]] + edit["newText"] + original[edit["endOffset"] :]
    return original


def _name_position(source: str, token: str) -> tuple[int, int]:
    """One-based (line, column) of the first occurrence of ``token`` -- the member-name selection the planner expects."""
    index = source.index(token)
    line = source.count("\n", 0, index) + 1
    column = index - (source.rfind("\n", 0, index) + 1) + 1
    return line, column


def _selection(source: str, snippet: str) -> dict[str, int]:
    """A one-based start/end line/column selection range for ``snippet`` within ``source``."""
    start = source.index(snippet)
    end = start + len(snippet)

    def line_column(offset: int) -> tuple[int, int]:
        line = source.count("\n", 0, offset) + 1
        return line, offset - (source.rfind("\n", 0, offset) + 1) + 1

    start_line, start_column = line_column(start)
    end_line, end_column = line_column(end)
    return {"startLine": start_line, "startColumn": start_column, "endLine": end_line, "endColumn": end_column}


# --- HB-3: moveStaticMember semantic collision (same erased signature) ------------------------------------------------


def _move_static_collision_project(tmp_path: Path, target_param: str) -> None:
    (tmp_path / "Source.java").write_text(
        "import java.util.List;\n"
        "public class Source {\n"
        "    static int inc(java.util.List value) {\n"
        "        return 1;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        "import java.util.List;\n"
        "public class Target {\n"
        f"    static int inc({target_param} value) {{\n"
        "        return 2;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )


def test_hb3_move_static_refuses_collision_simple_vs_fqn_param(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-3: a simple-name target param (``List``) collides with the source's FQN param of the same erasure."""
    _move_static_collision_project(tmp_path, "List")
    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "moveStaticMember",
        {"relativePath": "Source.java", "line": 3, "column": 16, "targetType": "Target", "targetRelativePath": "Target.java"},
    )
    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "target_member_exists"
    assert "session" not in refused


def test_hb3_move_static_refuses_generic_instantiation_vs_erasure(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-3: ``List<String>`` in the target erases to the same signature as the source's raw ``List`` param."""
    _move_static_collision_project(tmp_path, "List<String>")
    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "moveStaticMember",
        {"relativePath": "Source.java", "line": 3, "column": 16, "targetType": "Target", "targetRelativePath": "Target.java"},
    )
    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "target_member_exists"


def test_hb3_move_static_refuses_annotation_and_formatting_differences(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-3: an annotation/formatting-only difference on the target param does not change the erased signature."""
    (tmp_path / "Source.java").write_text(
        "public class Source {\n"
        "    static int inc(int value) {\n"
        "        return value + 1;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        "public class Target {\n"
        "    static int inc(final int  value) {\n"
        "        return value;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "moveStaticMember",
        {"relativePath": "Source.java", "line": 2, "column": 16, "targetType": "Target", "targetRelativePath": "Target.java"},
    )
    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "target_member_exists"


def test_hb3_move_static_allows_genuinely_different_erasure(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-3: a target method whose erased parameter type genuinely differs is NOT a collision; the move is accepted."""
    (tmp_path / "Source.java").write_text(
        "public class Source {\n"
        "    static int inc(int value) {\n"
        "        return value + 1;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        "public class Target {\n"
        "    static int inc(long value) {\n"
        "        return 2;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "moveStaticMember",
        {"relativePath": "Source.java", "line": 2, "column": 16, "targetType": "Target", "targetRelativePath": "Target.java"},
    )
    assert session["accepted"] is True, session
    assert "static int inc(int value)" in _target_texts(session, "Target.java")


# --- HB-4: moveInstanceMethod AST safety -----------------------------------------------------------------------------


def test_hb4_move_instance_refuses_spaced_super_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-4: a tokenized ``super . label()`` (spaced) still depends on super dispatch and is refused."""
    (tmp_path / "Base.java").write_text(
        'public class Base {\n    String label() {\n        return "base";\n    }\n}\n', encoding="utf-8"
    )
    (tmp_path / "Source.java").write_text(
        "public class Source extends Base {\n"
        "    String format(Target target) {\n"
        "        return super . label() + target.name();\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        'public class Target {\n    String name() {\n        return "t";\n    }\n}\n', encoding="utf-8"
    )
    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "moveInstanceMethod",
        {"relativePath": "Source.java", "line": 3, "column": 12, "targetParameter": "target"},
    )
    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "super_reference_unsupported"


def test_hb4_move_instance_allows_super_token_in_comment_or_string(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-4: a ``super.``/``synchronized(this)`` token that lives only in a comment or string is NOT a real reference."""
    (tmp_path / "Source.java").write_text(
        "public class Source {\n"
        "    String format(Target target) {\n"
        "        // super.foo() and synchronized(this) appear only here\n"
        '        String s = "super.label() synchronized(this)";\n'
        "        return target.name() + s;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        'public class Target {\n    String name() {\n        return "t";\n    }\n}\n', encoding="utf-8"
    )
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "moveInstanceMethod",
        {"relativePath": "Source.java", "line": 2, "column": 12, "targetParameter": "target"},
    )
    assert session["accepted"] is True, session
    assert session["session"]["operation"] == "moveInstanceMethod"


def test_hb4_move_instance_refuses_source_type_variable_in_signature(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-4: a source type-variable that appears in the moved method's signature cannot bind at the target; refused."""
    (tmp_path / "Source.java").write_text(
        "public class Source<T> {\n"
        "    T format(Target target, T value) {\n"
        "        return value;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        'public class Target {\n    String name() {\n        return "t";\n    }\n}\n', encoding="utf-8"
    )
    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "moveInstanceMethod",
        {"relativePath": "Source.java", "line": 2, "column": 7, "targetParameter": "target"},
    )
    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "source_type_parameter_unsupported"


def test_hb4_move_instance_allows_type_parameter_named_only_in_javadoc(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-4: a ``T`` mentioned only in Javadoc text (not the real signature) does not block the move."""
    (tmp_path / "Source.java").write_text(
        "public class Source {\n"
        "    /** @param target the T receiver; mentions T only here. */\n"
        "    String format(Target target) {\n"
        "        return target.name();\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        'public class Target {\n    String name() {\n        return "t";\n    }\n}\n', encoding="utf-8"
    )
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "moveInstanceMethod",
        {"relativePath": "Source.java", "line": 3, "column": 12, "targetParameter": "target"},
    )
    assert session["accepted"] is True, session
    assert session["session"]["operation"] == "moveInstanceMethod"


# --- HB-5: hierarchy member rendering (verbatim javac slices) --------------------------------------------------------


def test_hb5_pull_up_renders_multiline_generic_return_type(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-5: a return type that spans lines is relocated verbatim, with the method name unbroken."""
    (tmp_path / "Base.java").write_text("import java.util.List;\npublic class Base {\n}\n", encoding="utf-8")
    child = (
        "import java.util.List;\n"
        "public class Child extends Base {\n"
        "    java.util.List<\n"
        "            String> labels() {\n"
        "        return java.util.Collections.emptyList();\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Child.java").write_text(child, encoding="utf-8")
    line, column = _name_position(child, "labels")
    session = _create_session(
        sidecar_jar, tmp_path, "pullUpMember", {"relativePath": "Child.java", "line": line, "column": column, "targetType": "Base"}
    )
    assert session["accepted"] is True, session
    base_text = _target_texts(session, "Base.java")
    assert "String> labels" in base_text
    assert "labels()" in base_text


def test_hb5_pull_up_renders_type_use_annotation_on_return_type(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-5: a type-use annotation stays glued to the return type (not mistaken for a modifier)."""
    (tmp_path / "Anno.java").write_text(
        "import java.lang.annotation.*;\n"
        "@Target(ElementType.TYPE_USE)\n"
        "@Retention(RetentionPolicy.RUNTIME)\n"
        "public @interface Anno {\n}\n",
        encoding="utf-8",
    )
    (tmp_path / "Base.java").write_text("public class Base {\n}\n", encoding="utf-8")
    child = (
        "public class Child extends Base {\n"
        "    @Anno String label(int n) {\n"
        '        return "x" + n;\n'
        "    }\n"
        "}\n"
    )
    (tmp_path / "Child.java").write_text(child, encoding="utf-8")
    line, column = _name_position(child, "label")
    session = _create_session(
        sidecar_jar, tmp_path, "pullUpMember", {"relativePath": "Child.java", "line": line, "column": column, "targetType": "Base"}
    )
    assert session["accepted"] is True, session
    assert "@Anno String label(int n)" in _target_texts(session, "Base.java")


def test_hb5_pull_up_renders_annotation_between_modifiers_and_type(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-5: ``public @Anno int`` (annotation interleaved with modifiers) is preserved verbatim."""
    (tmp_path / "Anno.java").write_text(
        "import java.lang.annotation.*;\n"
        "@Target(ElementType.TYPE_USE)\n"
        "@Retention(RetentionPolicy.RUNTIME)\n"
        "public @interface Anno {\n}\n",
        encoding="utf-8",
    )
    (tmp_path / "Base.java").write_text("public class Base {\n}\n", encoding="utf-8")
    child = (
        "public class Child extends Base {\n"
        "    public @Anno int score(int n) {\n"
        "        return n + 1;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Child.java").write_text(child, encoding="utf-8")
    line, column = _name_position(child, "score")
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "pullUpMember",
        {"relativePath": "Child.java", "line": line, "column": column, "targetType": "Base", "confirmPublicApi": True},
    )
    assert session["accepted"] is True, session
    assert "public @Anno int score(int n)" in _target_texts(session, "Base.java")


def test_hb5_pull_up_renders_body_with_braces_in_comment_and_string(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-5: braces inside a comment AND a string literal do not terminate the member early; the full body moves."""
    (tmp_path / "Base.java").write_text("public class Base {\n}\n", encoding="utf-8")
    child = (
        "public class Child extends Base {\n"
        "    String pick() {\n"
        "        // a closing brace } in a comment must not end the method {\n"
        '        String s = "a } brace { in a string";\n'
        "        return s;\n"
        "    }\n"
        "    int trailing = 7;\n"
        "}\n"
    )
    (tmp_path / "Child.java").write_text(child, encoding="utf-8")
    line, column = _name_position(child, "pick")
    session = _create_session(
        sidecar_jar, tmp_path, "pullUpMember", {"relativePath": "Child.java", "line": line, "column": column, "targetType": "Base"}
    )
    assert session["accepted"] is True, session
    base_text = _target_texts(session, "Base.java")
    assert "a } brace { in a string" in base_text
    assert "must not end the method" in base_text


# --- HB-6: hierarchy import transfer / cleanup -----------------------------------------------------------------------


def test_hb6_pull_up_preserves_import_used_only_in_comment_or_string(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-6: an import still referenced (only in a comment/string) by the remaining source is preserved."""
    (tmp_path / "Base.java").write_text("public class Base {\n}\n", encoding="utf-8")
    child = (
        "import java.util.List;\n"
        "public class Child extends Base {\n"
        "    int moved() {\n"
        "        return 1;\n"
        "    }\n"
        "    int stays() {\n"
        "        // returns a List of nothing\n"
        '        String s = "List based";\n'
        "        return 2;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Child.java").write_text(child, encoding="utf-8")
    line, column = _name_position(child, "moved")
    session = _create_session(
        sidecar_jar, tmp_path, "pullUpMember", {"relativePath": "Child.java", "line": line, "column": column, "targetType": "Base"}
    )
    assert session["accepted"] is True, session
    assert "import java.util.List;" in _apply_to_path(session, "Child.java", child)


def test_hb6_pull_up_preserves_static_wildcard_import(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-6: a static wildcard import still used by the remaining source is preserved after pull-up."""
    (tmp_path / "Base.java").write_text("public class Base {\n}\n", encoding="utf-8")
    child = (
        "import static java.lang.Math.*;\n"
        "public class Child extends Base {\n"
        "    double moved() {\n"
        "        return 1.0;\n"
        "    }\n"
        "    double stays() {\n"
        "        return abs(-2.0);\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Child.java").write_text(child, encoding="utf-8")
    line, column = _name_position(child, "moved")
    session = _create_session(
        sidecar_jar, tmp_path, "pullUpMember", {"relativePath": "Child.java", "line": line, "column": column, "targetType": "Base"}
    )
    assert session["accepted"] is True, session
    assert "import static java.lang.Math.*;" in _apply_to_path(session, "Child.java", child)


def test_hb6_pull_up_same_package_reference_needs_no_import(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-6: a same-package reference in the pulled-up body needs no import added to the supertype."""
    package_dir = tmp_path / "demo"
    package_dir.mkdir()
    (package_dir / "Base.java").write_text("package demo;\npublic class Base {\n}\n", encoding="utf-8")
    (package_dir / "Helper.java").write_text(
        "package demo;\npublic class Helper {\n    static int v() { return 5; }\n}\n", encoding="utf-8"
    )
    child = (
        "package demo;\n"
        "public class Child extends Base {\n"
        "    int moved() {\n"
        "        return Helper.v();\n"
        "    }\n"
        "}\n"
    )
    (package_dir / "Child.java").write_text(child, encoding="utf-8")
    line, column = _name_position(child, "moved")
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "pullUpMember",
        {"relativePath": "demo/Child.java", "line": line, "column": column, "targetType": "demo.Base"},
    )
    assert session["accepted"] is True, session
    base_text = _target_texts(session, "demo/Base.java")
    assert "Helper.v()" in base_text
    assert "import demo.Helper" not in base_text


# --- HB-7: introduceField scope binding ------------------------------------------------------------------------------


def test_hb7_introduce_field_qualifies_when_lambda_param_shadows(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-7: an implicit lambda parameter named like the new field forces a ``this.field`` qualified reference."""
    source = (
        "import java.util.function.IntUnaryOperator;\n"
        "public class Sample {\n"
        "    IntUnaryOperator make() {\n"
        "        return count -> count + (1 + 1);\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {"relativePath": "Sample.java", "fieldName": "count", "fieldType": "int", "selection": _selection(source, "1 + 1")},
    )
    assert session["accepted"] is True, session
    new_texts = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert "this.count" in new_texts
    assert any("private final int count = 1 + 1;" in text for text in new_texts)


def test_hb7_introduce_field_qualifies_when_catch_parameter_shadows(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-7: a catch parameter named like the new field at the selection forces a ``this.field`` reference."""
    source = (
        "public class Sample {\n"
        "    int run() {\n"
        "        try {\n"
        "            return 0;\n"
        "        } catch (RuntimeException value) {\n"
        "            return (1 + 1) + value.hashCode();\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {"relativePath": "Sample.java", "fieldName": "value", "fieldType": "int", "selection": _selection(source, "1 + 1")},
    )
    assert session["accepted"] is True, session
    new_texts = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert "this.value" in new_texts


def test_hb7_introduce_field_unqualified_for_sibling_block_local(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-7: a local declared in a sibling block (out of scope at the selection) does NOT force qualification."""
    source = (
        "public class Sample {\n"
        "    int run() {\n"
        "        { int value = 9; System.out.println(value); }\n"
        "        return 1 + 1;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {"relativePath": "Sample.java", "fieldName": "value", "fieldType": "int", "selection": _selection(source, "1 + 1")},
    )
    assert session["accepted"] is True, session
    new_texts = [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]
    assert "value" in new_texts
    assert "this.value" not in new_texts


# --- G003: extract-method multi-output / control-flow are out of V2 scope --------------------------------------------


def test_g003_extract_method_refuses_multi_output_even_when_requested(sidecar_jar: Path, tmp_path: Path) -> None:
    """G003: multi-output extraction is refused in V2; an ``allowMultipleOutputs`` request is ignored, not honored."""
    source = (
        "public class MultiClash {\n"
        "    int run(int a, int b, int result) {\n"
        "        int lo = a;\n"
        "        int hi = b;\n"
        "        return lo + hi + result;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "MultiClash.java").write_text(source, encoding="utf-8")
    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "extractMethod",
        {
            "relativePath": "MultiClash.java",
            "newMethodName": "compute",
            "selection": _selection(source, "int lo = a;\n        int hi = b;"),
            "allowMultipleOutputs": True,
            "allowControlFlowExits": False,
        },
    )
    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "multiple_outputs_unsupported"


def test_g003_extract_method_refuses_control_flow_even_when_requested(sidecar_jar: Path, tmp_path: Path) -> None:
    """G003: control-flow-exit extraction is refused in V2; an ``allowControlFlowExits`` request is ignored."""
    source = (
        "public class SignalTypeClash {\n"
        "    int run(int a) {\n"
        "        if (a < 0) {\n"
        "            return 0;\n"
        "        }\n"
        "        return a + 1;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "SignalTypeClash.java").write_text(source, encoding="utf-8")
    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "extractMethod",
        {
            "relativePath": "SignalTypeClash.java",
            "newMethodName": "guard",
            "selection": _selection(source, "if (a < 0) {\n            return 0;\n        }"),
            "allowMultipleOutputs": False,
            "allowControlFlowExits": True,
        },
    )
    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "control_flow_unsupported"


def test_g003_extract_method_accepts_single_output_statement_selection(sidecar_jar: Path, tmp_path: Path) -> None:
    """G003: a zero/single-output complete-statement selection remains the supported V2 extract-method surface."""
    source = (
        "public class NoClash {\n"
        "    int run(int a, int b) {\n"
        "        int lo = a;\n"
        "        return lo + b;\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "NoClash.java").write_text(source, encoding="utf-8")
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "extractMethod",
        {
            "relativePath": "NoClash.java",
            "newMethodName": "compute",
            "selection": _selection(source, "int lo = a;"),
            "allowMultipleOutputs": True,
            "allowControlFlowExits": True,
        },
    )
    assert session["accepted"] is True, session


# --- HB-9: inline single-throw body, no token fallback ---------------------------------------------------------------


def test_hb9_inline_single_throw_body_at_statement_site(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-9: a single-``throw`` method body inlines as ``throw <expr>;`` at a statement call site."""
    source = (
        "public class Sample {\n"
        "    void run() {\n"
        '        fail("boom");\n'
        "    }\n"
        "    private void fail(String m) {\n"
        "        throw new IllegalStateException(m);\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "inlineMethod",
        {"relativePath": "Sample.java", "methodName": "fail", "deleteMethod": True},
    )
    assert session["accepted"] is True, session
    preview = _apply_to_path(session, "Sample.java", source)
    assert 'throw new IllegalStateException("boom");' in preview
    assert "private void fail" not in preview


def test_hb9_inline_single_throw_body_with_checked_exception(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-9: a single-``throw`` body of a checked exception inlines where the caller already declares ``throws``."""
    source = (
        "public class Sample {\n"
        "    void run() throws Exception {\n"
        '        fail("boom");\n'
        "    }\n"
        "    private void fail(String m) throws Exception {\n"
        "        throw new Exception(m);\n"
        "    }\n"
        "}\n"
    )
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "inlineMethod",
        {"relativePath": "Sample.java", "methodName": "fail", "deleteMethod": True},
    )
    assert session["accepted"] is True, session
    preview = _apply_to_path(session, "Sample.java", source)
    assert 'throw new Exception("boom");' in preview


def test_hb9_inline_refuses_unmodellable_body_instead_of_token_substitution(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-9: an unmodellable body (an anonymous class capturing a parameter) is refused, never token-substituted."""
    package_dir = tmp_path / "demo"
    package_dir.mkdir()
    (package_dir / "Sample.java").write_text(
        "package demo;\n"
        "public class Sample {\n"
        "    private Object box(int seed) {\n"
        "        return new Object() { int v = seed; };\n"
        "    }\n"
        "    int use(int a) {\n"
        "        return box(a).hashCode();\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "inlineMethod",
        {"relativePath": "demo/Sample.java", "methodName": "box", "deleteMethod": False},
    )
    assert refused["accepted"] is False
    assert refused["refusal"]["code"] == "inline_body_unmodellable"


# --- HB-10: every accepted V2 preview carries a validated diagnostic delta -------------------------------------------


def test_hb10_v2_preview_carries_validated_diagnostic_delta(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-10: an accepted raw V2 session preview carries ``preview.diagnosticDeltaValidated == True``."""
    (tmp_path / "FieldSample.java").write_text(
        'public class FieldSample {\n    String label() {\n        return "value";\n    }\n}\n', encoding="utf-8"
    )
    session = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {
            "relativePath": "FieldSample.java",
            "fieldName": "labelText",
            "fieldType": "String",
            "selection": {"startLine": 3, "startColumn": 16, "endLine": 3, "endColumn": 23},
        },
    )
    assert session["accepted"] is True, session
    assert session["preview"]["diagnosticDeltaValidated"] is True


def test_hb10_manager_v2_preview_carries_validated_diagnostic_delta(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """HB-10: the manager-level preview envelope also surfaces the validated-delta marker on its accepted result."""
    (tmp_path / "FieldSample.java").write_text(
        'public class FieldSample {\n    String label() {\n        return "value";\n    }\n}\n', encoding="utf-8"
    )
    manager = _manager(tmp_path, monkeypatch, sidecar_jar)
    try:
        result = manager.create_v2_refactor_session(
            "introduceField",
            {
                "relativePath": "FieldSample.java",
                "fieldName": "labelText",
                "fieldType": "String",
                "selection": {"startLine": 3, "startColumn": 16, "endLine": 3, "endColumn": 23},
            },
        )
    finally:
        manager.shutdown()
    assert result["accepted"] is True, result
    assert result["preview"]["diagnosticDeltaValidated"] is True


# --- HB-2: registry truthfulness cross-check (all blockers landed) ---------------------------------------------------


def test_hb2_every_v2_capability_operation_reports_supported(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-2: now that the blockers landed, every V2 capability op reports ``status == "supported"`` and the set of
    supported ops equals the manager's ``_V2_CAPABILITY_OPERATIONS`` -- tying ``supported`` to the complete blocker set.
    """
    (tmp_path / "App.java").write_text(
        "public class App {\n    private int value;\n    public int read() {\n        return value;\n    }\n}\n",
        encoding="utf-8",
    )
    capabilities = _capabilities(sidecar_jar, tmp_path)

    supported_ops = {
        operation
        for operation in _V2_CAPABILITY_OPERATIONS
        if capabilities.get(operation, {}).get("status") == "supported"
    }
    assert supported_ops == _V2_CAPABILITY_OPERATIONS, {
        operation: capabilities.get(operation, {}).get("status") for operation in _V2_CAPABILITY_OPERATIONS
    }
