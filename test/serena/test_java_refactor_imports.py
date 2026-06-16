"""G005: shared import management proven per-operation against the real sidecar.

The shared import contract itself (add/remove, static-import collision, wildcard preservation, java.lang and
same-package avoidance, ambiguous-simple-name FQN fallback, AST-driven unused cleanup, and style/ordering
preservation) is unit-proven at the engine level by ``shared/ImportManagerTest`` and ``shared/ImportRewritePlannerTest``
in the Java sidecar. These end-to-end tests prove that *operations* actually inherit that one contract: each refactor
that can introduce or orphan a type reference routes its imports through the same engine, so the resulting import
block obeys the shared rules rather than a per-operation patchwork.

Every operation here drives the genuine sidecar jar through a session/preview and asserts the import block of the
previewed edit, mirroring ``test_java_refactor_sidecar_sessions``. The behaviors are wired into the executable
acceptance matrix (``test_java_refactor_acceptance_matrix``) under "Shared import management (G005)".
"""

from pathlib import Path
from typing import Any

from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)


def _selection_for(source: str, snippet: str) -> dict[str, int]:
    """The 1-based line/column selection span of ``snippet`` within ``source`` (UTF-16-free ASCII fixtures)."""
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
    """Apply the previewed edits for one path so tests assert the resulting text, not edit granularity."""
    edits = [
        edit
        for change in payload["preview"]["workspaceEdit"]["changes"]
        if change["path"] == path
        for edit in change["edits"]
    ]
    for edit in sorted(edits, key=lambda item: item["startOffset"], reverse=True):
        source = source[: edit["startOffset"]] + edit["newText"] + source[edit["endOffset"] :]
    return source


def _replacements(payload: dict[str, Any], path: str) -> list[str]:
    return [
        edit["newText"]
        for change in payload["preview"]["workspaceEdit"]["changes"]
        if change["path"] == path
        for edit in change["edits"]
    ]


def _create_session(sidecar_jar: Path, project_root: Path, operation: str, params: dict[str, Any]) -> dict[str, Any]:
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(project_root), configuration="default"))
        return client.create_session(operation, params)
    finally:
        client.shutdown()


# ── add import for a newly-referenced type ────────────────────────────────────────────────────────────────────────


def test_introduce_field_adds_import_for_fully_qualified_field_type(sidecar_jar: Path, tmp_path: Path) -> None:
    """IntroduceField routes its declared field type through the shared import planner: a non-local FQN type is
    imported and the field is rendered with the simple name.
    """
    # Inline field init only accepts a compile-time constant or a side-effect-free fresh allocation; a fresh
    # ArrayList<> allocation is admitted and forces the declared field type's import through the shared planner.
    source = """public class FieldSample {
    Object items() {
        return new java.util.ArrayList<String>();
    }
}
"""
    (tmp_path / "FieldSample.java").write_text(source, encoding="utf-8")

    created = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {
            "relativePath": "FieldSample.java",
            "fieldName": "items",
            "fieldType": "java.util.ArrayList<String>",
            "selection": _selection_for(source, "new java.util.ArrayList<String>()"),
        },
    )

    assert created["accepted"] is True
    preview = _preview_text(source, created, "FieldSample.java")
    assert "import java.util.ArrayList;" in preview
    assert "private final ArrayList<String> items" in preview


def test_move_instance_method_transplants_body_import_into_target(sidecar_jar: Path, tmp_path: Path) -> None:
    """MoveInstanceMethod transplants the imports the moved body needs into the TARGET file through the shared
    contract: a type imported only in the source becomes an import in the target.
    """
    (tmp_path / "Source.java").write_text(
        """import java.util.List;

public class Source {
    String describe(Target target) {
        List<String> items = java.util.List.of("a");
        return target.tag() + items.get(0);
    }
}
""",
        encoding="utf-8",
    )
    (tmp_path / "Target.java").write_text(
        """public class Target {
    String tag() {
        return "t";
    }
}
""",
        encoding="utf-8",
    )

    created = _create_session(
        sidecar_jar,
        tmp_path,
        "moveInstanceMethod",
        {
            "relativePath": "Source.java",
            "line": 4,
            "column": 12,
            "targetParameter": "target",
        },
    )

    assert created["accepted"] is True
    target_edits = _replacements(created, "Target.java")
    assert any("import java.util.List;" in edit for edit in target_edits)


# ── java.lang and same-package types are not imported ──────────────────────────────────────────────────────────────


def test_introduce_field_skips_java_lang_and_same_package_field_types(sidecar_jar: Path, tmp_path: Path) -> None:
    """A java.lang field type and a same-package field type are implicitly visible: the shared planner adds no import
    for either, matching the engine contract (ImportManagerTest.skipsJavaLangAndSamePackageImports).
    """
    pkg = tmp_path / "demo"
    pkg.mkdir()
    (pkg / "Sibling.java").write_text("package demo;\n\npublic class Sibling {\n}\n", encoding="utf-8")

    java_lang_source = """package demo;

public class JavaLangSample {
    String label() {
        return "x".concat("y");
    }
}
"""
    (pkg / "JavaLangSample.java").write_text(java_lang_source, encoding="utf-8")
    java_lang = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {
            "relativePath": "demo/JavaLangSample.java",
            "fieldName": "prefix",
            "fieldType": "java.lang.String",
            "selection": _selection_for(java_lang_source, '"x"'),
        },
    )
    assert java_lang["accepted"] is True
    java_lang_preview = _preview_text(java_lang_source, java_lang, "demo/JavaLangSample.java")
    assert "import java.lang.String;" not in java_lang_preview
    assert "private final String prefix" in java_lang_preview

    same_pkg_source = """package demo;

public class SamePackageSample {
    Sibling make() {
        return new Sibling();
    }
}
"""
    (pkg / "SamePackageSample.java").write_text(same_pkg_source, encoding="utf-8")
    same_pkg = _create_session(
        sidecar_jar,
        tmp_path,
        "introduceField",
        {
            "relativePath": "demo/SamePackageSample.java",
            "fieldName": "sibling",
            "fieldType": "demo.Sibling",
            "selection": _selection_for(same_pkg_source, "new Sibling()"),
        },
    )
    assert same_pkg["accepted"] is True
    same_pkg_preview = _preview_text(same_pkg_source, same_pkg, "demo/SamePackageSample.java")
    assert "import demo.Sibling;" not in same_pkg_preview
    assert "private final Sibling sibling" in same_pkg_preview


def test_change_signature_does_not_import_same_package_or_java_lang_types(sidecar_jar: Path, tmp_path: Path) -> None:
    """ChangeSignature uses the same shared planner: a same-package parameter type and a java.lang return type are
    rendered with simple names and never imported.
    """
    pkg = tmp_path / "src" / "main" / "java" / "demo"
    pkg.mkdir(parents=True)
    (pkg / "Helper.java").write_text("package demo;\n\npublic class Helper {\n}\n", encoding="utf-8")
    source = """package demo;

class App {
    String helper() {
        return "hi";
    }
}
"""
    (pkg / "App.java").write_text(source, encoding="utf-8")

    created = _create_session(
        sidecar_jar,
        tmp_path,
        "changeSignature",
        {
            "relativePath": "src/main/java/demo/App.java",
            "line": 4,
            "column": 12,
            "newReturnType": "java.lang.String",
            "parameters": [{"name": "helper", "type": "demo.Helper"}],
        },
    )

    assert created["accepted"] is True
    preview = _preview_text(source, created, "src/main/java/demo/App.java")
    assert "import demo.Helper;" not in preview
    assert "import java.lang.String;" not in preview
    assert "String helper(Helper helper)" in preview


# ── remove an import that becomes unused after the edit ────────────────────────────────────────────────────────────


def test_pull_up_member_removes_source_import_made_unused(sidecar_jar: Path, tmp_path: Path) -> None:
    """PullUpMember cleans up the SOURCE import the relocated member orphaned (AST-proven unused) and transfers it to
    the supertype — the shared add+remove contract across two files in one operation.
    """
    (tmp_path / "Base.java").write_text("public class Base {\n}\n", encoding="utf-8")
    child_source = """import java.util.List;

public class Child extends Base {
    String labels() {
        List<String> tmp = java.util.List.of("c");
        return tmp.get(0);
    }
}
"""
    (tmp_path / "Child.java").write_text(child_source, encoding="utf-8")

    created = _create_session(
        sidecar_jar,
        tmp_path,
        "pullUpMember",
        {"relativePath": "Child.java", "line": 4, "column": 12, "targetType": "Base"},
    )

    assert created["accepted"] is True
    base_edits = _replacements(created, "Base.java")
    assert any("import java.util.List;" in edit for edit in base_edits)
    assert any("String labels()" in edit for edit in base_edits)
    child_preview = _preview_text(child_source, created, "Child.java")
    assert "import java.util.List;" not in child_preview


def test_pull_up_member_preserves_import_still_used_by_remaining_code(sidecar_jar: Path, tmp_path: Path) -> None:
    """Unused-import cleanup is driven by semantic references: an import the remaining source code still uses is NOT
    removed when only one of two using members is pulled up.
    """
    (tmp_path / "Base.java").write_text("public class Base {\n}\n", encoding="utf-8")
    child_source = """import java.util.List;

public class Child extends Base {
    String labels() {
        List<String> tmp = java.util.List.of("c");
        return tmp.get(0);
    }

    String other() {
        List<String> z = java.util.List.of("d");
        return z.get(0);
    }
}
"""
    (tmp_path / "Child.java").write_text(child_source, encoding="utf-8")

    created = _create_session(
        sidecar_jar,
        tmp_path,
        "pullUpMember",
        {"relativePath": "Child.java", "line": 4, "column": 12, "targetType": "Base"},
    )

    assert created["accepted"] is True
    child_preview = _preview_text(child_source, created, "Child.java")
    # The remaining other() still references List, so the source import must survive the cleanup.
    assert "import java.util.List;" in child_preview
    assert "String other()" in child_preview


# ── wildcard import preservation ──────────────────────────────────────────────────────────────────────────────────


def test_pull_up_member_preserves_unrelated_wildcard_import(sidecar_jar: Path, tmp_path: Path) -> None:
    """A wildcard import unrelated to the moved member is preserved verbatim by the shared engine (wildcards are never
    auto-removed), even while the operation rewrites the regular import block.
    """
    (tmp_path / "Base.java").write_text("public class Base {\n}\n", encoding="utf-8")
    child_source = """import java.util.*;
import java.time.Duration;

public class Child extends Base {
    Duration span() {
        List<String> live = new ArrayList<>();
        live.add("x");
        return java.time.Duration.ofSeconds(live.size());
    }
}
"""
    (tmp_path / "Child.java").write_text(child_source, encoding="utf-8")

    created = _create_session(
        sidecar_jar,
        tmp_path,
        "pullUpMember",
        {"relativePath": "Child.java", "line": 5, "column": 14, "targetType": "Base"},
    )

    assert created["accepted"] is True
    child_preview = _preview_text(child_source, created, "Child.java")
    # The wildcard is still referenced (nothing removes it) and must remain after the regular block is rewritten.
    assert "import java.util.*;" in child_preview
