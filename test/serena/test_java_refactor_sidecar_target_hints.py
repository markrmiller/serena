"""Target-identity verification at the sidecar seam.

Serena selects refactoring targets by ``name_path`` and resolves them to a line/column via its language server; the
sidecar re-resolves that POSITION with javac. These tests pin the contract that closes the identity gap of that lossy
round-trip: when the request carries ``nameHint``/``kindHint``/``arityHint``, the sidecar must prove the
position-resolved element IS the caller-named symbol before planning any edit, refusing with ``target_mismatch``
otherwise — a position on an enclosing declaration, a same-line sibling, the wrong overload, or a parameter/field
simple-name collision must never be silently refactored as if it were the requested symbol.
"""

from pathlib import Path

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams
from test.serena._java_refactor_sidecar_helpers import (  # noqa: F401
    _preview_op,
    sidecar_jar,
)

_APP_JAVA = """package demo;
public class App {
    private int amount;
    public App(int amount) { this.amount = amount; }
    public int calc(int a) { return a + amount; }
    public int calc(int a, int b) { return a + b + amount; }
    public void run() { int first = 1; int second = first + 1; use(second); }
    public void use(int x) { }
}
"""


def _write_app(tmp_path: Path) -> str:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "App.java").write_text(_APP_JAVA, encoding="utf-8")
    return "src/main/java/demo/App.java"


def _position_of(needle: str, occurrence: int = 1) -> tuple[int, int]:
    """One-based (line, column) of the nth occurrence of ``needle`` in the App.java fixture."""
    index = -1
    for _ in range(occurrence):
        index = _APP_JAVA.index(needle, index + 1)
    line = _APP_JAVA.count("\n", 0, index) + 1
    column = index - (_APP_JAVA.rfind("\n", 0, index) + 1) + 1
    return line, column


def test_sidecar_refuses_position_on_enclosing_declaration_with_mismatched_hints(sidecar_jar: Path, tmp_path: Path) -> None:
    # The LSP position landed on the enclosing class declaration, but the caller named the METHOD 'calc'. Without the
    # identity gate this would semantically rename the whole class.
    relative_path = _write_app(tmp_path)
    line, column = _position_of("App", occurrence=1)  # the class identifier in "public class App"

    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "semanticRename",
        {"relativePath": relative_path, "line": line, "column": column, "newName": "compute", "nameHint": "calc", "kindHint": "method"},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "target_mismatch"
    assert "'App'" in result["refusal"]["message"]
    assert "'calc'" in result["refusal"]["message"]


def test_sidecar_refuses_same_line_sibling_declaration(sidecar_jar: Path, tmp_path: Path) -> None:
    # Two locals are declared on ONE line; the position resolves to 'second' but the caller named 'first'. An off-by-
    # a-few-columns position must not inline the sibling.
    relative_path = _write_app(tmp_path)
    line, column = _position_of("second", occurrence=1)

    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "inlineLocalVariable",
        {"relativePath": relative_path, "line": line, "column": column, "nameHint": "first", "kindHint": "variable"},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "target_mismatch"
    assert "'second'" in result["refusal"]["message"]


def test_sidecar_refuses_wrong_overload_arity(sidecar_jar: Path, tmp_path: Path) -> None:
    # The position resolves to the 1-parameter calc overload, but the caller's name path identified the 2-parameter
    # one. Arity is part of Java method identity, so this must refuse rather than rename the wrong overload.
    relative_path = _write_app(tmp_path)
    line, column = _position_of("calc", occurrence=1)

    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "semanticRename",
        {
            "relativePath": relative_path,
            "line": line,
            "column": column,
            "newName": "compute",
            "nameHint": "calc",
            "kindHint": "method",
            "arityHint": 2,
        },
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "target_mismatch"
    assert "1-parameter overload" in result["refusal"]["message"]


def test_sidecar_accepts_matching_overload_arity(sidecar_jar: Path, tmp_path: Path) -> None:
    # Identity proven: same position, but the arity hint matches the resolved overload — the rename plans normally.
    relative_path = _write_app(tmp_path)
    line, column = _position_of("calc", occurrence=1)

    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "semanticRename",
        {
            "relativePath": relative_path,
            "line": line,
            "column": column,
            "newName": "compute",
            "nameHint": "calc",
            "kindHint": "method",
            "arityHint": 1,
        },
    )

    assert result.get("accepted") is True, result


def test_sidecar_refuses_parameter_when_field_was_requested(sidecar_jar: Path, tmp_path: Path) -> None:
    # The constructor parameter and the field share the simple name 'amount'. A position that landed on the PARAMETER
    # must not satisfy a request that named the FIELD: the kinds differ, so the identity gate refuses.
    relative_path = _write_app(tmp_path)
    line, column = _position_of("amount", occurrence=2)  # the parameter in "App(int amount)"

    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "semanticRename",
        {"relativePath": relative_path, "line": line, "column": column, "newName": "total", "nameHint": "amount", "kindHint": "field"},
    )

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "target_mismatch"
    assert "PARAMETER" in result["refusal"]["message"]


def test_sidecar_accepts_parameter_when_parameter_was_requested(sidecar_jar: Path, tmp_path: Path) -> None:
    # Same position and name, correct kind: the constructor-parameter rename plans normally (constructors take part in
    # no override hierarchy, so parameter renames there are allowed).
    relative_path = _write_app(tmp_path)
    line, column = _position_of("amount", occurrence=2)

    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "semanticRename",
        {"relativePath": relative_path, "line": line, "column": column, "newName": "total", "nameHint": "amount", "kindHint": "parameter"},
    )

    assert result.get("accepted") is True, result


def test_sidecar_resolve_target_verifies_name_hint(sidecar_jar: Path, tmp_path: Path) -> None:
    # The semantic-analysis surface (resolveTarget/scanReferences) enforces the same identity gate the planners do:
    # the cursor is INSIDE the class identifier (so the hint cannot re-disambiguate the resolution), and the resolved
    # element does not match the hinted name.
    relative_path = _write_app(tmp_path)
    line, column = _position_of("App", occurrence=1)

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.resolve_target(relative_path, line, column, name_hint="calc")
    finally:
        client.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "target_mismatch"


def test_sidecar_scan_references_enforces_full_overload_identity(sidecar_jar: Path, tmp_path: Path) -> None:
    # The rename old-key baseline is captured via scanReferences, NOT the planner. It must therefore resolve the SAME
    # overload the rename plans against: a position on the 1-parameter calc with a 2-parameter arity hint must refuse,
    # while the matching arity scans references. Resolving on name alone would bind the baseline to a sibling overload
    # and weaken the residual check below the operation it guards (HB-1).
    relative_path = _write_app(tmp_path)
    line, column = _position_of("calc", occurrence=1)  # resolves to the 1-parameter overload

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        mismatched = client.scan_references(relative_path, line, column, name_hint="calc", kind_hint="method", arity_hint=2)
        matched = client.scan_references(relative_path, line, column, name_hint="calc", kind_hint="method", arity_hint=1)
    finally:
        client.shutdown()

    assert mismatched.get("accepted") is False, mismatched
    assert mismatched["refusal"]["code"] == "target_mismatch"
    assert matched.get("accepted") is True, matched
    assert isinstance(matched.get("references"), list) and matched["references"]
