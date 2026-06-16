"""G010: per-operation "still required before merge" proof obligations not yet pinned by a behavioral test.

Most per-operation obligations are already proven end-to-end by ``test_java_refactor_sidecar_sessions``,
``test_java_refactor_hardblockers`` and ``test_java_refactor_imports``; the acceptance matrix's
"G010 per-operation merge proofs" section maps every obligation (positive + negative) to a named test. This module
adds the handful of obligations that were implemented in the sidecar but lacked a dedicated behavioral test:

* inline method — method-reference call-site refusal (``method_reference_unsupported``);
* introduce field — checked-exception initializer refusal (``checked_exception_initializer``);
* introduce parameter — call-site default portability gate (``CALL_SITE_DEFAULT_NOT_PORTABLE``);
* introduce field — HB-7 scope-binding qualification for try-with-resources and ``instanceof`` pattern variables
  (the catch/lambda spellings of the same rule are covered in ``test_java_refactor_hardblockers``).

Every test drives the genuine sidecar jar through a ``create_session`` preview/refusal, so each obligation is
exercised through the shared import + session-safety contract rather than a unit shim.
"""

from pathlib import Path
from typing import Any

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


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


def _create_session(sidecar_jar: Path, project_root: Path, operation: str, params: dict[str, Any]) -> dict[str, Any]:
    """Runs one real V2 session preview through the sidecar and returns the raw envelope."""
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        return client.create_session(operation, params)
    finally:
        client.shutdown()


def _all_new_texts(session: dict[str, Any]) -> list[str]:
    return [
        edit["newText"]
        for change in session["preview"]["workspaceEdit"]["changes"]
        for edit in change["edits"]
    ]


# ── inline method: method-reference refusal ───────────────────────────────────────────────────────────────────────


def test_inline_method_refuses_method_reference_call_site(sidecar_jar: Path, tmp_path: Path) -> None:
    """InlineMethod refuses to delete a method whose name is still captured as a method reference: a ``this::produce``
    capture cannot be rewritten into an expression substitution, so deleting the declaration would dangle it.
    """
    (tmp_path / "Sample.java").write_text(
        """import java.util.function.IntSupplier;

public class Sample {
    private int produce() {
        return 41 + 1;
    }

    IntSupplier make() {
        return this::produce;
    }
}
""",
        encoding="utf-8",
    )

    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "inlineMethod",
        {"relativePath": "Sample.java", "methodName": "produce", "deleteMethod": True},
    )

    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "method_reference_unsupported"
    assert "session" not in refused


# ── introduce field: checked-exception initializer refusal ─────────────────────────────────────────────────────────


def test_introduce_field_refuses_checked_exception_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    """IntroduceField refuses a selected initializer that can throw a checked exception: a field initializer has no
    throws clause, so a ``new FileReader(...)`` allocation (which declares ``throws IOException``) cannot be hoisted.
    """
    source = """public class Sample {
    Object open() {
        return new java.io.FileReader("data.txt");
    }
}
"""
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")

    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {
            "relativePath": "Sample.java",
            "fieldName": "reader",
            "fieldType": "java.io.Reader",
            "selection": _selection(source, 'new java.io.FileReader("data.txt")'),
        },
    )

    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "checked_exception_initializer"


# ── introduce parameter: call-site default portability gate ────────────────────────────────────────────────────────


def test_introduce_parameter_refuses_captured_state_default(sidecar_jar: Path, tmp_path: Path) -> None:
    """IntroduceParameter refuses a selected expression that reads enclosing-method state: the selected expression is
    re-emitted verbatim as the new parameter's call-site default, and a local read there is not in scope at callers.
    """
    source = """public class Sample {
    int compute(int base) {
        int local = 5;
        return base + (local * 2);
    }
}
"""
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")

    refused = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceParameter",
        {
            "relativePath": "Sample.java",
            "line": 2,
            "parameterName": "scaled",
            "selection": _selection(source, "local * 2"),
        },
    )

    assert refused["accepted"] is False, refused
    assert refused["refusal"]["code"] == "CALL_SITE_DEFAULT_NOT_PORTABLE"


# ── introduce field: HB-7 scope-binding qualification (resource / pattern variables) ───────────────────────────────


def test_introduce_field_qualifies_when_resource_variable_shadows(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-7: a try-with-resources variable named like the new field is in lexical scope at the selection, so the
    replacement must be qualified ``this.value`` rather than the bare field name that would rebind to the resource.
    """
    source = """public class Sample {
    int run() throws Exception {
        try (java.io.Reader value = new java.io.StringReader("x")) {
            return (1 + 1) + value.read();
        }
    }
}
"""
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")

    session = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {"relativePath": "Sample.java", "fieldName": "value", "fieldType": "int", "selection": _selection(source, "1 + 1")},
    )

    assert session["accepted"] is True, session
    assert "this.value" in _all_new_texts(session)


def test_introduce_field_qualifies_when_pattern_variable_shadows(sidecar_jar: Path, tmp_path: Path) -> None:
    """HB-7: an ``instanceof`` pattern variable named like the new field is in scope at the selection, so the
    replacement must be qualified ``this.value`` rather than the bare name that would rebind to the pattern binding.
    """
    source = """public class Sample {
    int run(Object o) {
        if (o instanceof String value) {
            return (1 + 1) + value.length();
        }
        return 0;
    }
}
"""
    (tmp_path / "Sample.java").write_text(source, encoding="utf-8")

    session = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {"relativePath": "Sample.java", "fieldName": "value", "fieldType": "int", "selection": _selection(source, "1 + 1")},
    )

    assert session["accepted"] is True, session
    assert "this.value" in _all_new_texts(session)
