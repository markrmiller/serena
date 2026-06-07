import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.manager import JavaRefactorManager
from serena.java_refactor.models import JavaRefactorInitializeParams
from solidlsp.ls_config import Language


from test.serena._java_refactor_sidecar_helpers import (
    CROSS_SOURCE_SET_CONFIG,
    text_edits,
    file_ops,
    sidecar_jar,
    maven_offline_repo,
    maven_offline_config,
    run_status,
    _build_vendored_jar,
    write_maven_offline_project,
    _write_gradle_java_project,
    _preview_rename,
    _preview_safe_delete,
    _preview_op,
    _write_two_module_project,
    _write_cross_source_set_project,
    _write_demo_main,
    _crafted_apply,
    _plain_project,
    _build_processor_jar,
    _write_divergent_gradle_project,
    _write_source_level_divergent_project,
    _utf16_offset,
    _write_generated_root_project,
)


def test_sidecar_rename_refuses_same_arity_overload_ambiguity(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008: renaming f(Object) to g, where g(String) already exists, forms a same-arity overload set whose resolution a
    # call like g(null) could change/ambiguate. This must be refused even though it may compile.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Demo.java").write_text(
        "package demo;\npublic class Demo {\n    void f(Object o) {}\n    void g(String s) {}\n}\n", encoding="utf-8"
    )

    result = _preview_rename(sidecar_jar, tmp_path, "src/main/java/demo/Demo.java", 3, 10, "g")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "name_conflict"
    assert "overload" in result["refusal"]["message"].lower()


def test_sidecar_rename_allows_different_arity_same_name(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008 (no false positive): renaming a() to b, where only b(int) exists, cannot change overload resolution (different
    # arity), so it is allowed.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Demo.java").write_text("package demo;\npublic class Demo {\n    void a() {}\n    void b(int x) {}\n}\n", encoding="utf-8")

    result = _preview_rename(sidecar_jar, tmp_path, "src/main/java/demo/Demo.java", 3, 10, "b")

    assert result.get("accepted") is True, result


def test_sidecar_rename_refuses_inherited_same_arity_overload(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008: the same-arity overload check also spans the type hierarchy: renaming Child.f(Object) to g collides with the
    # inherited Base.g(String).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Base.java").write_text("package demo;\npublic class Base {\n    void g(String s) {}\n}\n", encoding="utf-8")
    (src / "Child.java").write_text("package demo;\npublic class Child extends Base {\n    void f(Object o) {}\n}\n", encoding="utf-8")

    result = _preview_rename(sidecar_jar, tmp_path, "src/main/java/demo/Child.java", 3, 10, "g")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "name_conflict"


def test_sidecar_semantic_rename_rewrites_cross_source_set_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_cross_source_set_project(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=CROSS_SOURCE_SET_CONFIG))

        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Service.java", "line": 3, "column": 9, "newName": "amount"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True
    touched = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert touched == {"src/main/java/demo/Service.java", "src/test/java/demo/ServiceTest.java"}


def test_sidecar_semantic_rename_preview_uses_symbol_references_only(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    void helper() {}
    void run() {
        helper();
        String text = \"helper\";
        // helper should not change in comments
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview(
            "semanticRename", {"relativePath": "src/main/java/demo/Main.java", "line": 5, "column": 9, "newName": "renamed"}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True
    edit = result["workspaceEdit"]
    assert edit["stats"]["editCount"] == 2
    assert file_ops(edit) == []
    assert {item["replacement"] for item in text_edits(edit)} == {"renamed"}
    assert result["target"]["semanticKey"]["signature"] == "()"


def test_java_semantic_rename_manager_applies_transactional_edit(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
class Main {
    void helper() {}
    void run() {
        helper();
        String text = \"helper\";
        // helper should not change in comments
    }
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        preview = manager.semantic_rename("src/main/java/demo/Main.java", 5, 9, "renamed", apply=False)
        result = manager.semantic_rename("src/main/java/demo/Main.java", 5, 9, "renamed", apply=True)
    finally:
        manager.shutdown()

    assert preview["accepted"] is True
    assert preview["applied"] is False
    assert result["accepted"] is True
    assert result["applied"] is True
    updated = source.read_text(encoding="utf-8")
    assert "void renamed() {}" in updated
    assert "renamed();" in updated
    assert 'String text = "helper";' in updated
    assert "// helper should not change in comments" in updated


def test_sidecar_apply_rename_after_non_bmp_char_uses_utf16_offsets(sidecar_jar: Path, tmp_path: Path) -> None:
    # Regression (code-review HIGH): the sidecar emits UTF-16 code-unit offsets (a Java char is a UTF-16 code unit), so
    # the Python applier must slice in UTF-16 space, not by Python code points. A non-BMP character declared BEFORE the
    # renamed identifier would otherwise shift every later edit by one per surrogate pair and corrupt the applied file.
    non_bmp = chr(0x1D52C)  # MATHEMATICAL FRAKTUR SMALL O (a surrogate pair: 2 UTF-16 code units, 1 code point).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        "package demo;\n"
        "class Main {\n"
        f'    String {non_bmp} = "x";\n'
        "    int target = 0;\n"
        "    void run() {\n"
        "        this.target = 1;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        # `target` is on line 4 after `    int ` (8 chars) -> column 9.
        result = manager.semantic_rename("src/main/java/demo/Main.java", 4, 9, "renamedTarget", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True, result
    assert result["applied"] is True, result
    updated = source.read_text(encoding="utf-8")
    # The non-BMP field is untouched; both `target` occurrences renamed at the correct (UTF-16-aware) offsets.
    assert f'String {non_bmp} = "x";' in updated
    assert "int renamedTarget = 0;" in updated
    assert "this.renamedTarget = 1;" in updated
    assert "target" not in updated.replace("renamedTarget", "")


def test_sidecar_rename_resolves_target_after_non_bmp_on_same_line(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-2 / name_path UTF-16 contract: the column Serena forwards for a `name_path` target is the language server's
    # UTF-16 column, unchanged. The sidecar resolves it through javac's LineMap, which counts UTF-16 code units, so a
    # non-BMP character earlier on the SAME line must NOT shift resolution onto the wrong identifier. This pins that the
    # main ergonomic targeting path (not just a different-line case) is UTF-16-correct end to end.
    non_bmp = chr(0x1D52C)  # MATHEMATICAL FRAKTUR SMALL O: 1 code point, 2 UTF-16 code units.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    # Two field declarations share one line; `target` follows the non-BMP-named field on that line.
    field_line = f'    String {non_bmp} = "x"; int target = 0;'
    source.write_text(
        "package demo;\nclass Main {\n" + field_line + "\n    void run() { this.target = 1; }\n}\n",
        encoding="utf-8",
    )
    # The UTF-16 column Serena would forward from the LSP symbol: 1-based code-unit offset of `target` on its line.
    column = _utf16_offset(field_line, field_line.index("target")) + 1
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, column, "renamedTarget", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True, result
    assert result["applied"] is True, result
    updated = source.read_text(encoding="utf-8")
    # The non-BMP field is untouched and the intended identifier (not the sibling field) was renamed at both sites.
    assert f'String {non_bmp} = "x";' in updated
    assert "int renamedTarget = 0;" in updated
    assert "this.renamedTarget = 1;" in updated


def test_sidecar_semantic_rename_refuses_name_collision(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    void helper() {}
    void taken() {}
    void run() { helper(); }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview(
            "semanticRename", {"relativePath": "src/main/java/demo/Main.java", "line": 5, "column": 18, "newName": "taken"}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "name_conflict"


def test_sidecar_semantic_rename_plans_top_level_type_file_rename(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "OldName.java").write_text("package demo; public class OldName { OldName self() { return new OldName(); } }\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview(
            "semanticRename", {"relativePath": "src/main/java/demo/OldName.java", "line": 1, "column": 28, "newName": "NewName"}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True
    assert file_ops(result["workspaceEdit"]) == [
        {
            "kind": "rename",
            "relativePath": "src/main/java/demo/OldName.java",
            "newRelativePath": "src/main/java/demo/NewName.java",
        }
    ]
    # V1 transaction ordering: the declaration edit targets the file's CURRENT (old) path; the rename operation moves
    # the already-edited content to the new path.
    assert {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])} == {"src/main/java/demo/OldName.java"}


def test_sidecar_rename_preserves_non_utf8_encoding(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    source = src / "M.java"
    # Latin-1 (single-byte) accented characters in a comment and a string literal that must survive the edit verbatim.
    text = 'package ex;\nclass M {\n    // café déjà vu\n    String cafe() { return "résumé"; }\n    String u() { return cafe(); }\n}\n'
    source.write_bytes(text.encode("iso-8859-1"))
    assert b"\xe9" in source.read_bytes()  # 'é' is one byte in Latin-1

    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, encoding="ISO-8859-1"),
    )
    try:
        column = text.splitlines()[4].index("cafe()") + 1
        result = manager.semantic_rename("src/main/java/ex/M.java", 5, column, "coffee", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True and not result.get("rolledBack"), result
    raw = source.read_bytes()
    updated = raw.decode("iso-8859-1")
    assert "String coffee()" in updated and "return coffee();" in updated
    # Non-ASCII content is preserved byte-for-byte (no UTF-8 re-encoding / mojibake).
    assert "café déjà vu" in updated and '"résumé"' in updated
    assert b"\xe9" in raw and b"\xc3" not in raw


def test_sidecar_rename_includes_method_reference_expression(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
import java.util.function.Supplier;
class Main {
    String bar() { return "x"; }
    void run() {
        Supplier<String> s = this::bar;
        bar();
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        # Target the method from its plain call site; references must include the `this::bar` member reference.
        result = client.preview(
            "semanticRename", {"relativePath": "src/main/java/demo/Main.java", "line": 7, "column": 9, "newName": "baz"}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    # declaration + member reference (this::bar) + plain call.
    assert edit["stats"]["editCount"] == 3
    source_text = (src / "Main.java").read_text(encoding="utf-8")
    method_reference_index = source_text.index("this::bar") + len("this::")
    assert any(item["startOffset"] == method_reference_index for item in text_edits(edit)), text_edits(edit)


def test_sidecar_rename_method_applies_to_member_reference(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
import java.util.function.Supplier;
class Main {
    String bar() { return "x"; }
    void run() {
        Supplier<String> s = this::bar;
        bar();
    }
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.semantic_rename("src/main/java/demo/Main.java", 7, 9, "baz", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True, result
    updated = source.read_text(encoding="utf-8")
    assert "String baz()" in updated
    assert "this::baz" in updated
    assert "baz();" in updated
    assert "this::bar" not in updated


def test_sidecar_rename_record_type_updates_constructions(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Point.java").write_text("package demo; public record Point(int x, int y) {}\n", encoding="utf-8")
    use = src / "Use.java"
    use.write_text(
        """package demo;
class Use {
    Point a = new Point(1, 2);
    Point b = new Point(3, 4);
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        # Target the record type at its declaration name; rename to Coord with the top-level file rename.
        result = manager.semantic_rename("src/main/java/demo/Point.java", 1, 29, "Coord", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True, result
    assert (src / "Coord.java").exists()
    assert not (src / "Point.java").exists()
    renamed = (src / "Coord.java").read_text(encoding="utf-8")
    assert "public record Coord(int x, int y)" in renamed
    updated_use = use.read_text(encoding="utf-8")
    assert updated_use.count("new Coord(") == 2
    assert "Coord a" in updated_use and "Coord b" in updated_use
    assert "Point" not in updated_use


def test_sidecar_rename_method_renames_override_group(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    (src / "Shape.java").write_text("package ex; public interface Shape { double area(); }\n", encoding="utf-8")
    (src / "Circle.java").write_text(
        "package ex; public class Circle implements Shape { public double area(){ return 3.14; } }\n", encoding="utf-8"
    )
    (src / "Square.java").write_text(
        "package ex; public class Square implements Shape { @Override public double area(){ return 4.0; } }\n", encoding="utf-8"
    )
    (src / "Use.java").write_text(
        "package ex; class Use { double t(Shape s){ return s.area(); } double c(){ return new Circle().area(); } }\n",
        encoding="utf-8",
    )
    declaration_line = "package ex; public interface Shape { double area(); }"
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/ex/Shape.java", "line": 1, "column": declaration_line.index("area") + 1, "newName": "surface"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    # interface declaration + 2 implementation declarations + 2 call sites.
    assert edit["stats"]["editCount"] == 5
    touched = {item["relativePath"] for item in text_edits(edit)}
    assert touched == {
        "src/main/java/ex/Shape.java",
        "src/main/java/ex/Circle.java",
        "src/main/java/ex/Square.java",
        "src/main/java/ex/Use.java",
    }


def test_sidecar_rename_refuses_method_overriding_library(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    source_line = 'package ex; class Obj { @Override public String toString(){ return "x"; } }'
    (src / "Obj.java").write_text(source_line + "\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/ex/Obj.java", "line": 1, "column": source_line.index("toString") + 1, "newName": "describe"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "overrides_library_method"


def test_sidecar_rename_constructor_through_type_rename_applies(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    foo = src / "Foo.java"
    declaration_line = "package ex; public class Foo { private int v; public Foo(int v){ this.v=v; } public Foo(){ this(0); } }"
    foo.write_text(declaration_line + "\n", encoding="utf-8")
    use = src / "UseF.java"
    use.write_text("package ex; class UseF { Foo a = new Foo(1); Foo b = new Foo(); }\n", encoding="utf-8")
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.semantic_rename(
            "src/main/java/ex/Foo.java", 1, declaration_line.index("class Foo") + len("class ") + 1, "Bar", apply=True
        )
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True, result
    assert (src / "Bar.java").exists() and not foo.exists()
    renamed = (src / "Bar.java").read_text(encoding="utf-8")
    # Both explicit constructor declarations are renamed along with the class.
    assert renamed.count("public Bar(") == 2
    assert "class Bar" in renamed
    assert "Foo" not in renamed
    updated_use = use.read_text(encoding="utf-8")
    assert "new Bar(1)" in updated_use and "new Bar()" in updated_use


def test_sidecar_rename_refuses_direct_constructor_rename(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    declaration_line = "package ex; public class Foo { private int x; public Foo(int x){ this.x = x; } }"
    (src / "Foo.java").write_text(declaration_line + "\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        # Point at the constructor declaration name (the `Foo` in `public Foo(`).
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/ex/Foo.java", "line": 1, "column": declaration_line.index("Foo(") + 1, "newName": "Renamed"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "unsupported_constructor_rename"


def test_sidecar_rename_refuses_field_name_collision(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    source_line = "package ex; class C { int count; int total; void use(){ count = 1; } }"
    (src / "C.java").write_text(source_line + "\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/ex/C.java", "line": 1, "column": source_line.index("count = 1") + 1, "newName": "total"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "name_conflict"


def test_sidecar_rename_field_refuses_inherited_field_hiding(sidecar_jar: Path, tmp_path: Path) -> None:
    # G011: renaming a subclass field to a name declared by an accessible (non-private) supertype field must be refused
    # — the rename would HIDE the inherited field, silently rebinding unqualified / super. accesses. The old check only
    # looked at the declaring type's own fields and missed this.
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    source = "package ex; class Base { protected int shared = 1; } class Sub extends Base { int local = 2; }"
    (src / "Base.java").write_text(source + "\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/ex/Base.java", "line": 1, "column": source.index("local = 2") + 1, "newName": "shared"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "name_conflict", result
    assert "inherited" in result["refusal"]["message"].lower(), result


def test_sidecar_rename_field_allows_name_of_private_super_field(sidecar_jar: Path, tmp_path: Path) -> None:
    # G011 (negative): a PRIVATE supertype field is not inherited, so it cannot be hidden — renaming a subclass field to
    # that name must remain allowed (no over-refusal).
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    source = "package ex; class Base2 { private int secret = 1; } class Sub2 extends Base2 { int local = 2; void u(){ local = 3; } }"
    (src / "Base2.java").write_text(source + "\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/ex/Base2.java", "line": 1, "column": source.index("local = 2") + 1, "newName": "secret"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    assert {e["replacement"] for e in text_edits(result["workspaceEdit"])} == {"secret"}


def test_sidecar_rename_type_updates_anonymous_class_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    (src / "Greeter.java").write_text("package ex; public interface Greeter { String hi(); }\n", encoding="utf-8")
    (src / "Use.java").write_text(
        'package ex; class Use { Greeter g = new Greeter(){ public String hi(){ return "h"; } }; }\n', encoding="utf-8"
    )
    declaration_line = "package ex; public interface Greeter { String hi(); }"
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {
                "relativePath": "src/main/java/ex/Greeter.java",
                "line": 1,
                "column": declaration_line.index("Greeter") + 1,
                "newName": "Welcomer",
            },
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    # field type + anonymous-class `new Greeter(){}` supertype reference + the declaration.
    edit = result["workspaceEdit"]
    assert edit["stats"]["editCount"] == 3
    use_text = 'package ex; class Use { Greeter g = new Greeter(){ public String hi(){ return "h"; } }; }'
    anonymous_reference_offset = use_text.index("new Greeter") + len("new ")
    use_edits = [item for item in text_edits(edit) if item["relativePath"] == "src/main/java/ex/Use.java"]
    assert any(item["startOffset"] == anonymous_reference_offset for item in use_edits), use_edits


def test_sidecar_rename_record_component_updates_atomic_surface(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    declaration_line = (
        "package ex; public record P(int x, int y) { public P(int x, int y){ this.x=x; this.y=y; } int sum(){ return x() + x + y; } }"
    )
    (src / "P.java").write_text(declaration_line + "\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {
                "relativePath": "src/main/java/ex/P.java",
                "line": 1,
                "column": declaration_line.index("int x") + len("int ") + 1,
                "newName": "col",
            },
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edits = text_edits(result["workspaceEdit"])
    assert {edit["replacement"] for edit in edits} == {"col"}
    assert result["workspaceEdit"]["stats"]["editCount"] >= 3


def test_sidecar_rename_type_surfaces_reflection_resource_warning(sidecar_jar: Path, tmp_path: Path) -> None:
    # G012: renaming a type changes a name external references may target by string; an accepted rename must surface a
    # warning-only reflection/resource caveat (Class.forName, ServiceLoader, Spring/XML, serialization).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo; public class Widget { }"
    (src / "Widget.java").write_text(source + "\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Widget.java", "line": 1, "column": source.index("Widget {") + 1, "newName": "Gadget"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    warnings = result["workspaceEdit"]["warnings"]
    assert any("reflection" in warning.lower() for warning in warnings), warnings


def test_sidecar_divergent_classpath_valid_main_rename_not_rolled_back(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A valid rename of a main symbol referenced from the test source set applies and is NOT rolled back, even though
    the test source set carries an extra test-only dependency and main's output is not on its compile classpath.
    """
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    _write_divergent_gradle_project(tmp_path)
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, build_tool_mode="gradle", offline=True),
    )
    try:
        result = manager.semantic_rename("src/main/java/demo/Service.java", 3, 16, "amount", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True and not result.get("rolledBack"), result
    assert (tmp_path / "src/main/java/demo/Service.java").read_text(encoding="utf-8").count("amount") == 1
    assert "amount" in (tmp_path / "src/test/java/demo/ServiceTest.java").read_text(encoding="utf-8")


# --- G006: per-source-set semantic indexing (each source set analyzed with its OWN compiler options) ------------------
#
# SemanticIndex now builds one JavacTask per source set, each compiled with that set's own javacOptions (+ the other
# sets' roots on -sourcepath, -implicit:none), instead of flattening every file into one task compiled with only the
# target set's options. References are matched across tasks by canonical key (not Element identity, which is not
# comparable across tasks). This pins that a cross-set rename rewrites BOTH the main declaration and the test reference
# while the test set's divergent compile classpath (a test-only dependency absent from main) is honored, so no spurious
# "cannot find symbol" is attributed to compiling the test file with main's options.


def test_sidecar_per_source_set_options_cross_set_rename_no_spurious_errors(sidecar_jar: Path, tmp_path: Path) -> None:
    """The test source set carries an extra test-only dependency (divergent classpath vs. main) AND references a main
    symbol. Renaming that main symbol must rewrite both the main declaration and the test reference, and the project
    must still validate clean: indexing the test file with the test set's OWN classpath (not main's) means the test-only
    dependency resolves, so there is no false "cannot find symbol" from using the wrong source set's options.

    NOTE: divergent *classpath* is used here (not divergent *release* levels), because exercising divergent release
    levels would require multiple JDK toolchains. The test-only ``vendor.TestKit`` dependency is the divergence: it is on
    the test compile classpath but absent from main's, so a single-task index using main's options would mis-compile the
    test file.
    """
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for Gradle build-model extraction tests")
    _write_divergent_gradle_project(tmp_path)
    gradle_config = json.dumps({"buildToolMode": "gradle", "offline": True})

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=gradle_config))
        # No spurious diagnostics: the test file resolves its test-only dependency under the test set's own classpath.
        status = json.loads(
            client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=gradle_config)).to_json()
        )
        assert status["ready"] is True, status["project_model"]["errors"]
        assert not any("cannot find symbol" in error for error in status["project_model"]["errors"]), status["project_model"]["errors"]

        # Rename the main symbol declared at Service.java line 3 ("value"); its only call site lives in the test set.
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Service.java", "line": 3, "column": 16, "newName": "amount"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    touched = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert touched == {"src/main/java/demo/Service.java", "src/test/java/demo/ServiceTest.java"}, touched


def test_sidecar_per_source_set_cross_set_override_rename_rewrites_subclass(sidecar_jar: Path, tmp_path: Path) -> None:
    """A test-source-set subclass overrides a main-source-set method. Renaming the main method must also rewrite the
    overriding declaration that lives in the OTHER source set's task. With per-source-set indexing the override relation
    is discovered per task and the override group is unioned across tasks by canonical key, so the secondary task's
    override declaration is still rewritten. (Uses explicit config so it runs without a build tool.)
    """
    main_src = tmp_path / "src/main/java/demo"
    test_src = tmp_path / "src/test/java/demo"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    (main_src / "Base.java").write_text("package demo;\npublic class Base {\n    public int value() { return 1; }\n}\n", encoding="utf-8")
    (test_src / "Sub.java").write_text(
        "package demo;\nclass Sub extends Base {\n    @Override public int value() { return 2; }\n}\n", encoding="utf-8"
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=CROSS_SOURCE_SET_CONFIG))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Base.java", "line": 3, "column": 16, "newName": "amount"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    touched = {edit["relativePath"] for edit in text_edits(result["workspaceEdit"])}
    assert touched == {"src/main/java/demo/Base.java", "src/test/java/demo/Sub.java"}, touched


def test_sidecar_cross_task_rename_refuses_same_arity_overload_in_secondary_owner(sidecar_jar: Path, tmp_path: Path) -> None:
    """G005: a main method is overridden by a TEST-source-set subclass that already declares a same-arity overload with
    the new name. The cross-task rename would rewrite the override declaration in the test set, forming an ambiguous
    overload there, so the conflict analysis must run against the secondary owner and refuse."""
    main_src = tmp_path / "src/main/java/demo"
    test_src = tmp_path / "src/test/java/demo"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    (main_src / "Base.java").write_text(
        "package demo;\npublic class Base {\n    public int value(int x) { return x; }\n}\n", encoding="utf-8"
    )
    (test_src / "Sub.java").write_text(
        "package demo;\nclass Sub extends Base {\n    @Override public int value(int x) { return x; }\n"
        "    public int amount(String s) { return 0; }\n}\n",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=CROSS_SOURCE_SET_CONFIG))
        # Rename Base.value -> amount; Sub already declares a same-arity (1-param) overload `amount(String)`.
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Base.java", "line": 3, "column": 16, "newName": "amount"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "name_conflict"
    assert "overload" in result["refusal"]["message"]


def test_sidecar_cross_task_rename_refuses_when_secondary_owner_inherits_conflicting_method(sidecar_jar: Path, tmp_path: Path) -> None:
    """G005: a main method is overridden in a TEST-source-set subclass whose owner INHERITS a conflicting same-signature
    method (from a test-set interface). The rename must run inherited-context conflict checks against the secondary
    declaration and refuse, even though the home owner has no such conflict."""
    main_src = tmp_path / "src/main/java/demo"
    test_src = tmp_path / "src/test/java/demo"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    (main_src / "Base.java").write_text(
        "package demo;\npublic class Base {\n    public int value(int x) { return x; }\n}\n", encoding="utf-8"
    )
    (test_src / "HasAmount.java").write_text(
        "package demo;\ninterface HasAmount {\n    default int amount(int x) { return 0; }\n}\n", encoding="utf-8"
    )
    (test_src / "Sub.java").write_text(
        "package demo;\nclass Sub extends Base implements HasAmount {\n    @Override public int value(int x) { return x; }\n}\n",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=CROSS_SOURCE_SET_CONFIG))
        # Base has no `amount`, so the home checks pass; the conflict exists only in Sub's inherited context.
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Base.java", "line": 3, "column": 16, "newName": "amount"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "name_conflict"
    assert "inherited" in result["refusal"]["message"]


def test_sidecar_cross_task_rename_applies_duplicate_member_check_to_secondary_declaration(sidecar_jar: Path, tmp_path: Path) -> None:
    """G005: the secondary-source-set override declaration receives the SAME duplicate-member check as a home
    declaration. Here the test subclass already declares a method with the new name and the same parameter types, so
    the cross-task rename would create a duplicate in the secondary owner and must be refused."""
    main_src = tmp_path / "src/main/java/demo"
    test_src = tmp_path / "src/test/java/demo"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    (main_src / "Base.java").write_text(
        "package demo;\npublic class Base {\n    public int value(int x) { return x; }\n}\n", encoding="utf-8"
    )
    (test_src / "Sub.java").write_text(
        "package demo;\nclass Sub extends Base {\n    @Override public int value(int x) { return x; }\n"
        "    public int amount(int y) { return y; }\n}\n",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=CROSS_SOURCE_SET_CONFIG))
        # Sub already declares amount(int) with the same parameter types as the renamed value(int).
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Base.java", "line": 3, "column": 16, "newName": "amount"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "name_conflict"
    assert "same parameter types" in result["refusal"]["message"]


def test_sidecar_rename_method_does_not_rewrite_field_receiver(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008(a): a field `foo` and a method `foo()` invoked as `this.foo()` and `foo.foo()`. Renaming the METHOD must
    # rewrite only the method-name token(s), never the field/receiver `foo`.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
class Main {
    Main foo;
    void foo() {}
    void run() {
        this.foo();
        foo.foo();
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        # Target the method at its declaration `void foo()` (line 4, col 10 -> the `f` of the method name).
        result = client.preview(
            "semanticRename", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": 10, "newName": "bar"}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    # declaration + this.foo() + foo.foo() method token = 3 edits; the field decl and receiver `foo.` are untouched.
    assert edit["stats"]["editCount"] == 3, edit
    text = source.read_text(encoding="utf-8")
    # The method-select tokens are the trailing `foo` after each `.`; the receiver `foo` before `.foo()` is the field.
    this_call = text.index("this.foo();") + len("this.")
    recv_call = text.index("foo.foo();") + len("foo.")
    decl = text.index("void foo()") + len("void ")
    offsets = sorted(item["startOffset"] for item in text_edits(edit))
    assert offsets == sorted([decl, this_call, recv_call]), text_edits(edit)
    # The receiver `foo` of `foo.foo()` (the field) must NOT be in the edit set.
    receiver_field = text.index("foo.foo();")
    assert all(item["startOffset"] != receiver_field for item in text_edits(edit)), text_edits(edit)
    assert {item["replacement"] for item in text_edits(edit)} == {"bar"}


def test_sidecar_rename_skips_comment_and_string_literal(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008(b): occurrences of the old name inside a comment and a string literal are NOT rewritten.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
class Main {
    void helper() {}
    void run() {
        // helper helper helper
        String s = "call helper now";
        helper();
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        # Target the method declaration `void helper()` (line 3, col 10).
        result = client.preview(
            "semanticRename", {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": 10, "newName": "renamed"}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    # Only the declaration token and the real call site; the comment and string occurrences are not edited.
    assert edit["stats"]["editCount"] == 2, edit
    text = source.read_text(encoding="utf-8")
    comment_start = text.index("// helper")
    string_start = text.index('"call helper now"')
    for item in text_edits(edit):
        start = item["startOffset"]
        assert not (comment_start <= start < text.index("\n", comment_start)), item
        assert not (string_start <= start < string_start + len('"call helper now"')), item
    assert {item["replacement"] for item in text_edits(edit)} == {"renamed"}


def test_sidecar_rename_non_bmp_identifier_uses_correct_offset(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008(c): a field named with a non-BMP Java letter has its span anchored on the correct UTF-16 char offset
    # (surrogate-pair aware), not on a code-point or byte offset.
    non_bmp = chr(0x1D52C)  # MATHEMATICAL FRAKTUR SMALL O, a non-BMP Java identifier letter (a surrogate pair).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    text = f"package demo;\nclass Main {{\n    int {non_bmp} = 0;\n    void run() {{\n        this.{non_bmp} = 1;\n    }}\n}}\n"
    source.write_text(text, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        # Target the field declaration; the name follows `    int ` (8 chars) -> col 9.
        result = client.scan_references("src/main/java/demo/Main.java", 3, 9, None)
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    spans = result["references"]
    # Declaration `int 𝔬` and the use `this.𝔬`; both spans must land on UTF-16-correct offsets and cover the name.
    decl_off = _utf16_offset(text, text.index(f"int {non_bmp}") + len("int "))
    use_off = _utf16_offset(text, text.index(f"this.{non_bmp}") + len("this."))
    found = sorted((span["startOffset"], span["endOffset"]) for span in spans)
    assert found == sorted([(decl_off, decl_off + 2), (use_off, use_off + 2)]), spans
    # The emitted `text` is the full non-BMP identifier; the span width is 2 UTF-16 code units (one surrogate pair).
    assert {span["text"] for span in spans} == {non_bmp}
    for span in spans:
        assert span["endOffset"] - span["startOffset"] == 2, span


def test_sidecar_rename_constructor_reference_rewrites_type_token(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008(d): `Type::new` rewrites the qualifier type's simple name (not the `new` keyword), and `Type::method`
    # rewrites the trailing method name.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    factory = src / "Widget.java"
    factory.write_text("package demo; public class Widget { public Widget() {} }\n", encoding="utf-8")
    use = src / "Use.java"
    use.write_text(
        """package demo;
import java.util.function.Supplier;
class Use {
    Supplier<Widget> make = Widget::new;
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        # Target the type at its declaration name `class Widget` (line 1, col 35 of the single-line source).
        decl_col = factory.read_text(encoding="utf-8").index("Widget {") + 1
        result = client.preview(
            "semanticRename", {"relativePath": "src/main/java/demo/Widget.java", "line": 1, "column": decl_col, "newName": "Gadget"}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    use_edits = [item for item in text_edits(result["workspaceEdit"]) if item["relativePath"].endswith("Use.java")]
    use_text = use.read_text(encoding="utf-8")
    # The `Widget` of `Widget::new` (the qualifier) is rewritten; the `new` keyword is not.
    ctor_ref = use_text.index("Widget::new")
    assert any(item["startOffset"] == ctor_ref and use_text[item["startOffset"] : item["endOffset"]] == "Widget" for item in use_edits), (
        use_edits
    )


def test_sidecar_rename_method_reference_and_call_target_method_token(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008(d): `Type::method` rewrites the trailing method-name token after `::`.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
import java.util.function.Supplier;
class Main {
    String produce() { return "x"; }
    void run() {
        Supplier<String> s = this::produce;
        produce();
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename", {"relativePath": "src/main/java/demo/Main.java", "line": 7, "column": 9, "newName": "build"}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    assert edit["stats"]["editCount"] == 3, edit
    text = source.read_text(encoding="utf-8")
    ref_token = text.index("this::produce") + len("this::")
    assert any(
        item["startOffset"] == ref_token and text[item["startOffset"] : item["endOffset"]] == "produce" for item in text_edits(edit)
    ), text_edits(edit)
    assert {item["replacement"] for item in text_edits(edit)} == {"build"}


def test_sidecar_rename_parameterized_type_rewrites_raw_type_only(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008(e): a generic reference `Foo<Bar>` rewrites only the raw type `Foo`, never the type argument `Bar`.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    box = src / "Box.java"
    box.write_text("package demo; public class Box<T> { }\n", encoding="utf-8")
    (src / "Bar.java").write_text("package demo; public class Bar { }\n", encoding="utf-8")
    use = src / "Use.java"
    use.write_text(
        """package demo;
class Use {
    Box<Bar> field;
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        decl_col = box.read_text(encoding="utf-8").index("Box<T>") + 1
        result = client.preview(
            "semanticRename", {"relativePath": "src/main/java/demo/Box.java", "line": 1, "column": decl_col, "newName": "Crate"}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    use_edits = [item for item in text_edits(result["workspaceEdit"]) if item["relativePath"].endswith("Use.java")]
    use_text = use.read_text(encoding="utf-8")
    raw_type = use_text.index("Box<Bar>")
    bar_arg = use_text.index("Bar>")
    assert any(item["startOffset"] == raw_type and use_text[item["startOffset"] : item["endOffset"]] == "Box" for item in use_edits), (
        use_edits
    )
    # The type argument `Bar` (a different type) is never part of this rename.
    assert all(item["startOffset"] != bar_arg for item in use_edits), use_edits
    assert all(use_text[item["startOffset"] : item["endOffset"]] != "Bar" for item in use_edits), use_edits


# --- G009: include_javadocs / include_comments, parameter-hierarchy, conflict, coverage ---


def test_sidecar_rename_includes_javadoc_link_and_see_only_when_enabled(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    /**
     * Calls {@link #helper()}.
     * @see #helper()
     */
    void run() { helper(); }
    void helper() {}
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        target = {"relativePath": "src/main/java/demo/Main.java", "line": 8, "column": 10, "newName": "renamed"}
        without = client.preview("semanticRename", dict(target))
        withdoc = client.preview("semanticRename", {**target, "includeJavadocs": True})
    finally:
        client.shutdown()

    assert without["accepted"] is True, without
    assert withdoc["accepted"] is True, withdoc
    src_text = (src / "Main.java").read_text(encoding="utf-8")
    link_off = src_text.index("helper", src_text.index("{@link"))
    see_off = src_text.index("helper", src_text.index("@see"))

    def offsets(result: dict) -> set[int]:
        return {e["startOffset"] for e in text_edits(result["workspaceEdit"])}

    # Default false: javadoc tokens are NOT edited.
    assert link_off not in offsets(without)
    assert see_off not in offsets(without)
    # include_javadocs=true: both {@link} and @see member tokens are rewritten.
    assert link_off in offsets(withdoc), withdoc
    assert see_off in offsets(withdoc), withdoc
    assert all(e["replacement"] == "renamed" for e in text_edits(withdoc["workspaceEdit"]))


def test_sidecar_rename_includes_param_javadoc_for_renameable_parameter(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    /**
     * @param count the number
     */
    private void run(int count) { System.out.println(count); }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        decl = (src / "Main.java").read_text(encoding="utf-8")
        col = decl.split("\n")[5].index("count") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Main.java", "line": 6, "column": col, "newName": "total", "includeJavadocs": True},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    src_text = (src / "Main.java").read_text(encoding="utf-8")
    param_off = src_text.index("count", src_text.index("@param"))
    offsets = {e["startOffset"] for e in text_edits(result["workspaceEdit"])}
    assert param_off in offsets, result


def test_sidecar_rename_includes_comments_and_strings_only_when_enabled_with_warning(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    void helper() {}
    void run() {
        helper();
        // call helper here
        String s = "helper";
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        target = {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": 10, "newName": "renamed"}
        without = client.preview("semanticRename", dict(target))
        withcomments = client.preview("semanticRename", {**target, "includeComments": True})
    finally:
        client.shutdown()

    assert without["accepted"] is True
    assert withcomments["accepted"] is True
    src_text = (src / "Main.java").read_text(encoding="utf-8")
    comment_off = src_text.index("helper", src_text.index("// call"))
    string_off = src_text.index("helper", src_text.index('"helper"'))

    def offsets(result: dict) -> set[int]:
        return {e["startOffset"] for e in text_edits(result["workspaceEdit"])}

    assert comment_off not in offsets(without)
    assert string_off not in offsets(without)
    assert without["workspaceEdit"]["warnings"] == []
    assert comment_off in offsets(withcomments), withcomments
    assert string_off in offsets(withcomments), withcomments
    assert any("heuristic" in w for w in withcomments["workspaceEdit"]["warnings"]), withcomments


def test_sidecar_rename_parameter_in_override_method_updates_hierarchy_and_private_method(sidecar_jar: Path, tmp_path: Path) -> None:
    # Hierarchy parameter rename updates corresponding project declarations/usages; non-hierarchy parameters still rename.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Base.java").write_text("package demo;\nclass Base { void m(int value) {} }\n", encoding="utf-8")
    sub_source = (
        "package demo;\nclass Sub extends Base {\n    @Override void m(int value) { int x = value; }\n"
        "    private void p(int value) { int y = value; }\n}\n"
    )
    (src / "Sub.java").write_text(sub_source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        override_col = sub_source.split("\n")[2].index("int value") + len("int ") + 1
        private_col = sub_source.split("\n")[3].index("int value") + len("int ") + 1
        override_rename = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Sub.java", "line": 3, "column": override_col, "newName": "renamed"},
        )
        private_rename = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Sub.java", "line": 4, "column": private_col, "newName": "renamed"},
        )
    finally:
        client.shutdown()

    assert override_rename["accepted"] is True, override_rename
    override_edits = text_edits(override_rename["workspaceEdit"])
    assert {e["relativePath"] for e in override_edits} == {"src/main/java/demo/Base.java", "src/main/java/demo/Sub.java"}
    assert {e["replacement"] for e in override_edits} == {"renamed"}

    assert private_rename["accepted"] is True, private_rename
    flat = text_edits(private_rename["workspaceEdit"])
    assert len(flat) == 2
    assert {e["relativePath"] for e in flat} == {"src/main/java/demo/Sub.java"}, private_rename
    assert {e["replacement"] for e in flat} == {"renamed"}


def test_sidecar_rename_parameter_in_interface_method_updates_implementation(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    iface = "package demo;\ninterface Service { void handle(int code); }\n"
    (src / "Service.java").write_text(iface, encoding="utf-8")
    impl = "package demo;\nclass Impl implements Service {\n    @Override public void handle(int code) { int y = code; }\n}\n"
    (src / "Impl.java").write_text(impl, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        col = iface.split("\n")[1].index("code") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Service.java", "line": 2, "column": col, "newName": "status"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edits = text_edits(result["workspaceEdit"])
    assert {e["relativePath"] for e in edits} == {"src/main/java/demo/Service.java", "src/main/java/demo/Impl.java"}
    assert {e["replacement"] for e in edits} == {"status"}


def test_sidecar_rename_parameter_in_abstract_method_updates_subclass(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    base = "package demo;\nabstract class Base {\n    abstract void run(int amount);\n}\n"
    (src / "Base.java").write_text(base, encoding="utf-8")
    sub = "package demo;\nclass Sub extends Base {\n    @Override void run(int amount) { int z = amount; }\n}\n"
    (src / "Sub.java").write_text(sub, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        col = base.split("\n")[2].index("int amount") + len("int ") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Base.java", "line": 3, "column": col, "newName": "count"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edits = text_edits(result["workspaceEdit"])
    assert {e["relativePath"] for e in edits} == {"src/main/java/demo/Base.java", "src/main/java/demo/Sub.java"}
    assert {e["replacement"] for e in edits} == {"count"}


def test_sidecar_rename_overriding_interface_method_parameter_updates_interface(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Service.java").write_text("package demo;\ninterface Service { void handle(int code); }\n", encoding="utf-8")
    impl = (
        "package demo;\nclass Impl implements Service {\n    @Override public void handle(int code) {\n"
        "        int a = code;\n        System.out.println(code);\n    }\n}\n"
    )
    (src / "Impl.java").write_text(impl, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        col = impl.split("\n")[2].index("int code") + len("int ") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Impl.java", "line": 3, "column": col, "newName": "value"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edits = text_edits(result["workspaceEdit"])
    assert {e["relativePath"] for e in edits} == {"src/main/java/demo/Service.java", "src/main/java/demo/Impl.java"}
    assert {e["replacement"] for e in edits} == {"value"}


def test_sidecar_rename_parameter_divergent_names_across_override_group_updates_all(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    base = "package demo;\nabstract class Base {\n    abstract void m(int alpha);\n}\n"
    (src / "Base.java").write_text(base, encoding="utf-8")
    sub = "package demo;\nclass Sub extends Base {\n    @Override void m(int beta) { int q = beta; }\n}\n"
    (src / "Sub.java").write_text(sub, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        sub_col = sub.split("\n")[2].index("int beta") + len("int ") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Sub.java", "line": 3, "column": sub_col, "newName": "gamma"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edits = text_edits(result["workspaceEdit"])
    assert {e["relativePath"] for e in edits} == {"src/main/java/demo/Base.java", "src/main/java/demo/Sub.java"}
    assert {e["replacement"] for e in edits} == {"gamma"}


def test_sidecar_rename_superclass_parameter_with_project_override_updates_subclass(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    base = "package demo;\nclass Base {\n    void m(int value) {}\n}\n"
    (src / "Base.java").write_text(base, encoding="utf-8")
    (src / "Sub.java").write_text(
        "package demo;\nclass Sub extends Base {\n    @Override void m(int value) { int x = value; }\n}\n",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        col = base.split("\n")[2].index("int value") + len("int ") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Base.java", "line": 3, "column": col, "newName": "renamed"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edits = text_edits(result["workspaceEdit"])
    assert {e["relativePath"] for e in edits} == {"src/main/java/demo/Base.java", "src/main/java/demo/Sub.java"}
    assert {e["replacement"] for e in edits} == {"renamed"}


def test_sidecar_rename_library_override_parameter_refused_when_parameterized(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\nclass Task implements java.util.function.Consumer<String> {\n"
        "    @Override public void accept(String item) { System.out.println(item); }\n}\n"
    )
    (src / "Task.java").write_text(source, encoding="utf-8")

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        col = source.split("\n")[2].index("String item") + len("String ") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Task.java", "line": 3, "column": col, "newName": "value"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "parameter_hierarchy_external_declaration"


def test_sidecar_rename_cross_source_set_override_parameter_updates_test_override(sidecar_jar: Path, tmp_path: Path) -> None:
    main_src = tmp_path / "src/main/java/demo"
    test_src = tmp_path / "src/test/java/demo"
    main_src.mkdir(parents=True)
    test_src.mkdir(parents=True)
    base = "package demo;\npublic class Base {\n    public int value(int count) { return count; }\n}\n"
    (main_src / "Base.java").write_text(base, encoding="utf-8")
    (test_src / "Sub.java").write_text(
        "package demo;\nclass Sub extends Base {\n    @Override public int value(int count) { return count + 1; }\n}\n",
        encoding="utf-8",
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=CROSS_SOURCE_SET_CONFIG))
        col = base.split("\n")[2].index("int count") + len("int ") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Base.java", "line": 3, "column": col, "newName": "amount"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edits = text_edits(result["workspaceEdit"])
    assert {e["relativePath"] for e in edits} == {"src/main/java/demo/Base.java", "src/test/java/demo/Sub.java"}
    assert {e["replacement"] for e in edits} == {"amount"}


def test_sidecar_rename_type_refuses_import_collision(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    other = tmp_path / "src/main/java/other"
    src.mkdir(parents=True)
    other.mkdir(parents=True)
    (src / "Foo.java").write_text("package demo; public class Foo {}\n", encoding="utf-8")
    (other / "Target.java").write_text("package other; public class Target {}\n", encoding="utf-8")
    (src / "User.java").write_text("package demo;\nimport other.Target;\nclass User { Foo f; Target t; }\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        col = (src / "Foo.java").read_text(encoding="utf-8").index("Foo", 8) + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Foo.java", "line": 1, "column": col, "newName": "Target"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "name_conflict", result
    assert "import" in result["refusal"]["message"].lower(), result


def test_sidecar_rename_refuses_edit_in_generated_source_root(sidecar_jar: Path, tmp_path: Path) -> None:
    # Real Gradle extraction: a srcDir whose path contains "/generated/" is reported as a generatedRoot (and is NOT
    # under build/, so the sidecar compiles it). A reference living there must block the rename as a non-editable edit.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for generated-source-root extraction")
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "sample"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\n'
        "java { sourceCompatibility = JavaVersion.VERSION_17 }\n"
        'sourceSets { main { java { srcDir("src/generated/java") } } }\n',
        encoding="utf-8",
    )
    main_src = tmp_path / "src/main/java/demo"
    gen = tmp_path / "src/generated/java/demo"
    main_src.mkdir(parents=True)
    gen.mkdir(parents=True)
    (main_src / "Api.java").write_text("package demo; public class Api { public void run() {} }\n", encoding="utf-8")
    (gen / "GenUser.java").write_text("package demo; public class GenUser { void use(Api a) { a.run(); } }\n", encoding="utf-8")
    configuration = json.dumps({"offline": True, "allowIncompleteAnalysis": True})
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=configuration))
        col = (main_src / "Api.java").read_text(encoding="utf-8").index("run") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Api.java", "line": 1, "column": col, "newName": "execute"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "non_editable_target", result


def test_sidecar_rename_plain_field_in_record_is_renameable(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Point.java").write_text(
        """package demo;
record Point(int x, int y) {
    static int counter = 0;
    void bump() { counter++; }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        col = (src / "Point.java").read_text(encoding="utf-8").split("\n")[2].index("counter") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Point.java", "line": 3, "column": col, "newName": "total"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    assert {e["replacement"] for e in text_edits(result["workspaceEdit"])} == {"total"}
    assert result["workspaceEdit"]["stats"]["editCount"] == 2, result


def test_sidecar_rename_annotation_type_updates_declaration_and_usage(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Marker.java").write_text("package demo;\n@interface Marker {}\n", encoding="utf-8")
    (src / "Used.java").write_text("package demo;\n@Marker class Used {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        col = (src / "Marker.java").read_text(encoding="utf-8").split("\n")[1].index("Marker") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Marker.java", "line": 2, "column": col, "newName": "Tag"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    touched = {e["relativePath"] for e in text_edits(result["workspaceEdit"])}
    assert "src/main/java/demo/Used.java" in touched, result
    assert file_ops(result["workspaceEdit"])[0]["newRelativePath"] == "src/main/java/demo/Tag.java", result


def test_sidecar_rename_static_import_field_rewrites_import_and_use(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Constants.java").write_text("package demo; public class Constants { public static final int LIMIT = 1; }\n", encoding="utf-8")
    (src / "User.java").write_text(
        "package demo;\nimport static demo.Constants.LIMIT;\nclass User { int v() { return LIMIT; } }\n",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        col = (src / "Constants.java").read_text(encoding="utf-8").index("LIMIT") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Constants.java", "line": 1, "column": col, "newName": "MAX"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    user_edits = [e for e in text_edits(result["workspaceEdit"]) if e["relativePath"].endswith("User.java")]
    user_text = (src / "User.java").read_text(encoding="utf-8")
    import_off = user_text.index("LIMIT", user_text.index("import static"))
    use_off = user_text.index("LIMIT", user_text.index("return"))
    offsets = {e["startOffset"] for e in user_edits}
    assert import_off in offsets, result
    assert use_off in offsets, result


# --- G016: full V1 acceptance matrix gap closure (plan section 15) ---------------------------------------------------
# These cases close the remaining plan-section-15 matrix entries that prior stories left uncovered: rename of a
# shadowing local, a parameter sharing a field's name, a static method, a superclass method, an enum/nested type,
# through a wildcard import, a class-qualified static field, a private method, a CRLF file; plus safe-delete refusal
# when the only reference is an annotation value or a method reference.


def test_sidecar_rename_local_variable_shadowing_renames_only_inner_scope(sidecar_jar: Path, tmp_path: Path) -> None:
    # A local `x` shadows a field `x`. Renaming the local must touch the local declaration and its use, never the
    # field declaration nor the `this.x` field access.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    int x = 100;\n    void run() {\n        int x = 1;\n        int y = x + this.x;\n    }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    local_col = source.split("\n")[4].index("x") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Main.java", "line": 5, "column": local_col, "newName": "z"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    # local declaration + the bare `x` use; the field decl and `this.x` access are untouched.
    assert edit["stats"]["editCount"] == 2, edit
    field_decl = source.index("int x = 100") + len("int ")
    this_access = source.index("this.x") + len("this.")
    touched_offsets = {e["startOffset"] for e in text_edits(edit)}
    assert field_decl not in touched_offsets, edit
    assert this_access not in touched_offsets, edit
    assert {e["replacement"] for e in text_edits(edit)} == {"z"}


def test_sidecar_rename_local_allows_name_used_only_in_disjoint_block(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a local rename into a name used only in a DISJOINT sibling block must be allowed — the two scopes do not
    # overlap, so there is no real conflict. The old whole-method name scan over-refused this valid V1 case.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "class Main {\n"
        "    void run() {\n"
        "        { int x = 1; System.out.println(x); }\n"
        "        { int y = 2; System.out.println(y); }\n"
        "    }\n"
        "}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    lines = source.split("\n")
    x_col = lines[3].index("int x") + len("int ") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": x_col, "newName": "y"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    # Only block 1's `x` declaration and its use are renamed; block 2's `y` is untouched.
    assert result["workspaceEdit"]["stats"]["editCount"] == 2, result["workspaceEdit"]


def test_sidecar_rename_local_refuses_name_in_same_scope(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010 (negative): a genuine same-scope conflict must still be refused — two locals with overlapping scope cannot
    # share a name.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "class Main {\n"
        "    void run() {\n"
        "        int x = 1;\n"
        "        int y = 2;\n"
        "        System.out.println(x + y);\n"
        "    }\n"
        "}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    x_col = source.split("\n")[3].index("int x") + len("int ") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": x_col, "newName": "y"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "name_conflict", result


def test_sidecar_rename_local_refuses_name_in_enclosing_scope(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010 (negative): renaming an inner-block local to a name declared in an ENCLOSING scope must be refused (the outer
    # variable is visible at the inner declaration, so the rename would illegally shadow it).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "class Main {\n"
        "    void run() {\n"
        "        int outer = 1;\n"
        "        { int inner = 2; System.out.println(outer + inner); }\n"
        "    }\n"
        "}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    inner_col = source.split("\n")[4].index("int inner") + len("int ") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Main.java", "line": 5, "column": inner_col, "newName": "outer"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "name_conflict", result


def test_sidecar_rename_parameter_sharing_field_name_renames_only_parameter(sidecar_jar: Path, tmp_path: Path) -> None:
    # A parameter `value` shares the field name `value`. Renaming the parameter must rewrite the parameter declaration
    # and its bare use (the right-hand `value`), never the field declaration nor the `this.value` field access.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    int value = 1;\n    void set(int value) {\n        this.value = value;\n    }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    param_col = source.split("\n")[3].index("int value") + len("int ") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": param_col, "newName": "amount"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    # parameter declaration + the bare-`value` read on the right side of the assignment.
    assert edit["stats"]["editCount"] == 2, edit
    field_decl = source.index("int value = 1") + len("int ")
    this_access = source.index("this.value") + len("this.")
    touched_offsets = {e["startOffset"] for e in text_edits(edit)}
    assert field_decl not in touched_offsets, edit
    assert this_access not in touched_offsets, edit
    assert {e["replacement"] for e in text_edits(edit)} == {"amount"}


def test_sidecar_rename_static_method_rewrites_unqualified_and_class_qualified_calls(sidecar_jar: Path, tmp_path: Path) -> None:
    # A static method called both unqualified and via the class name; rename rewrites the declaration and both calls.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    static int helper() { return 1; }\n    int run() { return helper() + Main.helper(); }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    decl_col = source.split("\n")[2].index("helper") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": decl_col, "newName": "compute"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    # declaration + unqualified call + class-qualified call.
    assert edit["stats"]["editCount"] == 3, edit
    assert {e["replacement"] for e in text_edits(edit)} == {"compute"}


def test_sidecar_rename_superclass_method_renames_override_group(sidecar_jar: Path, tmp_path: Path) -> None:
    # A class method overridden by a subclass (via `extends`, not an interface). Renaming the superclass declaration
    # must rewrite the subclass @Override declaration and the call site resolving to the supertype method.
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    base_line = "package ex; public class Base { public void run(){} }"
    (src / "Base.java").write_text(base_line + "\n", encoding="utf-8")
    (src / "Sub.java").write_text("package ex; public class Sub extends Base { @Override public void run(){} }\n", encoding="utf-8")
    (src / "Use.java").write_text("package ex; class Use { void t(Base b){ b.run(); } }\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/ex/Base.java", "line": 1, "column": base_line.index("run") + 1, "newName": "execute"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    # base declaration + subclass override declaration + call site.
    assert edit["stats"]["editCount"] == 3, edit
    touched = {e["relativePath"] for e in text_edits(edit)}
    assert touched == {"src/main/java/ex/Base.java", "src/main/java/ex/Sub.java", "src/main/java/ex/Use.java"}, edit


def test_sidecar_rename_enum_type_updates_references_and_renames_file(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    color = src / "Color.java"
    color.write_text("package ex; public enum Color { RED, GREEN }\n", encoding="utf-8")
    use = src / "Use.java"
    use.write_text("package ex; class Use { Color c = Color.RED; }\n", encoding="utf-8")
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        decl_col = "package ex; public enum Color { RED, GREEN }".index("Color") + 1
        result = manager.semantic_rename("src/main/java/ex/Color.java", 1, decl_col, "Hue", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True, result
    assert (src / "Hue.java").exists() and not color.exists()
    renamed = (src / "Hue.java").read_text(encoding="utf-8")
    assert "public enum Hue" in renamed
    updated_use = use.read_text(encoding="utf-8")
    assert "Hue c = Hue.RED;" in updated_use
    assert "Color" not in updated_use


def test_sidecar_rename_nested_class_updates_references_without_file_rename(sidecar_jar: Path, tmp_path: Path) -> None:
    # A nested type rename rewrites the declaration and its in-file references but performs NO file rename (the file is
    # named for the enclosing top-level type).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\npublic class Outer {\n    static class Inner {}\n    Inner make() { return new Inner(); }\n}\n"
    (src / "Outer.java").write_text(source, encoding="utf-8")
    decl_col = source.split("\n")[2].index("Inner") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Outer.java", "line": 3, "column": decl_col, "newName": "Core"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    # declaration + field-type reference + `new Inner()` construction; no file operation for a nested type.
    assert edit["stats"]["editCount"] == 3, edit
    assert file_ops(edit) == [], edit
    assert {e["replacement"] for e in text_edits(edit)} == {"Core"}


def test_sidecar_rename_type_through_wildcard_import_rewrites_usage(sidecar_jar: Path, tmp_path: Path) -> None:
    # The using file imports the package with a wildcard (`import lib.*;`), so the type's simple name is bound without
    # a single-type import. Renaming the type must rewrite the simple-name usage; the wildcard import stays valid.
    lib = tmp_path / "src/main/java/lib"
    app = tmp_path / "src/main/java/app"
    lib.mkdir(parents=True)
    app.mkdir(parents=True)
    (lib / "Widget.java").write_text("package lib; public class Widget {}\n", encoding="utf-8")
    use = app / "Use.java"
    use.write_text("package app;\nimport lib.*;\nclass Use { Widget w; }\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        decl_col = "package lib; public class Widget {}".index("Widget") + 1
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/lib/Widget.java", "line": 1, "column": decl_col, "newName": "Gadget"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    use_edits = [e for e in text_edits(result["workspaceEdit"]) if e["relativePath"].endswith("Use.java")]
    use_text = use.read_text(encoding="utf-8")
    usage_off = use_text.index("Widget w")
    assert any(e["startOffset"] == usage_off and e["replacement"] == "Gadget" for e in use_edits), use_edits
    # The wildcard import line must not be rewritten (it names the package, not the type).
    wildcard_off = use_text.index("lib.*")
    assert all(e["startOffset"] != wildcard_off for e in use_edits), use_edits


def test_sidecar_rename_field_via_class_qualifier_rewrites_static_accesses(sidecar_jar: Path, tmp_path: Path) -> None:
    # A static field accessed through the class name (`Main.total`). Renaming the field must rewrite the declaration
    # and both class-qualified accesses (write and read), touching only the trailing field token, not the qualifier.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    static int total = 0;\n    void run() { Main.total = 5; int x = Main.total; }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    decl_col = source.split("\n")[2].index("total") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": decl_col, "newName": "count"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = result["workspaceEdit"]
    # declaration + two class-qualified accesses; each edit replaces only the `total` token.
    assert edit["stats"]["editCount"] == 3, edit
    assert {e["replacement"] for e in text_edits(edit)} == {"count"}
    # No edit may start at a `Main` qualifier offset.
    qualifier_write = source.index("Main.total = 5")
    qualifier_read = source.index("Main.total;")
    starts = {e["startOffset"] for e in text_edits(edit)}
    assert qualifier_write not in starts and qualifier_read not in starts, edit


def test_sidecar_rename_private_method_rewrites_declaration_and_call(sidecar_jar: Path, tmp_path: Path) -> None:
    # A private method (no hierarchy) renamed via its declaration: declaration + sole call site.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    private int helper() { return 1; }\n    int run() { return helper(); }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    decl_col = source.split("\n")[2].index("helper") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "semanticRename",
            {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": decl_col, "newName": "compute"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    assert result["workspaceEdit"]["stats"]["editCount"] == 2, result
    assert {e["replacement"] for e in text_edits(result["workspaceEdit"])} == {"compute"}


def test_sidecar_rename_crlf_file_preserves_line_endings_on_apply(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # A CRLF-terminated source file: rename + top-level file rename must apply cleanly and preserve CRLF line endings.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    old_file = src / "OldName.java"
    source = "package demo;\r\npublic class OldName {\r\n    OldName self() { return new OldName(); }\r\n}\r\n"
    old_file.write_bytes(source.encode("utf-8"))
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        decl_col = "public class OldName {".index("OldName") + 1
        result = manager.semantic_rename("src/main/java/demo/OldName.java", 2, decl_col, "NewName", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True, result
    new_file = src / "NewName.java"
    assert new_file.exists() and not old_file.exists()
    new_bytes = new_file.read_bytes()
    assert b"public class NewName {" in new_bytes
    assert b"new NewName()" in new_bytes
    assert b"OldName" not in new_bytes
    # CRLF line endings are preserved (no lone-LF introduced by the edit application).
    assert b"\r\n" in new_bytes
    assert new_bytes.replace(b"\r\n", b"").find(b"\n") == -1, "a lone LF was introduced"


def test_sidecar_rename_residual_rolls_back_dropped_rebinding_reference(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # G004: if a rename leaves a reference unrewritten and that reference silently rebinds to an inherited field (so the
    # code still COMPILES), the old-key residual verification must detect the stale AST node by location and roll back.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Base.java").write_text("package demo;\nclass Base { int data; }\n", encoding="utf-8")
    sub = src / "Sub.java"
    sub_source = "package demo;\nclass Sub extends Base {\n    int data;\n    int use() { return data; }\n}\n"
    sub.write_text(sub_source, encoding="utf-8")

    # Simulate an incomplete rename: drop the highest-offset reference edit (the `return data;` use site), keeping the
    # declaration rewrite. The leftover `data` rebinds to Base.data and still compiles, defeating javac post-validation.
    original_apply = JavaRefactorClient.apply_refactor

    def dropping_apply(self: JavaRefactorClient, operation: str, params: dict | None = None) -> dict:
        result = original_apply(self, operation, params)
        if operation == "semanticRename" and result.get("accepted"):
            edits = text_edits(result["workspaceEdit"])
            if len(edits) >= 2:
                drop = max(edits, key=lambda edit: edit["startOffset"])
                # Drop the highest-offset edit from the V1 grouped changes[] to simulate an incomplete rename.
                for change in result["workspaceEdit"].get("changes", []):
                    if change.get("path") == drop["relativePath"]:
                        change["edits"] = [
                            e
                            for e in change["edits"]
                            if not (e["startOffset"] == drop["startOffset"] and e["endOffset"] == drop["endOffset"])
                        ]
        return result

    monkeypatch.setattr(JavaRefactorClient, "apply_refactor", dropping_apply)

    column = "    int data;".index("data") + 1
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.semantic_rename("src/main/java/demo/Sub.java", 3, column, "renamed", apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "rename_old_key_residual", result
    assert "Sub.java:4" in result["refusal"]["message"], result
    assert result.get("rolledBack") is True, result
    # Rollback must restore the original source — neither the declaration nor the use site is renamed on disk.
    assert sub.read_text(encoding="utf-8") == sub_source


def test_sidecar_rename_shadowing_field_completes_without_false_residual(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # G004 (no false positive): the SAME rebinding-prone shadowing layout renames cleanly when every reference is
    # rewritten; the coverage-based residual check must not fire.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Base.java").write_text("package demo;\nclass Base { int data; }\n", encoding="utf-8")
    sub = src / "Sub.java"
    sub.write_text("package demo;\nclass Sub extends Base {\n    int data;\n    int use() { return data; }\n}\n", encoding="utf-8")

    column = "    int data;".index("data") + 1
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.semantic_rename("src/main/java/demo/Sub.java", 3, column, "renamed", apply=True)
    finally:
        manager.shutdown()

    assert result.get("accepted") is True, result
    updated = sub.read_text(encoding="utf-8")
    assert "int renamed;" in updated, updated
    assert "return renamed;" in updated, updated


def test_sidecar_rename_refuses_classpath_only_binary_target(sidecar_jar: Path, tmp_path: Path) -> None:
    # G007: the centralized target-origin gate refuses renaming a target that resolves to a classpath-only binary
    # element (java.lang.String) which has no editable source declaration.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = 'package demo;\nclass Main {\n    String text = "x";\n}\n'
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("String") + 1
    result = _preview_rename(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 3, col, "Str2")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "non_editable_target", result
    assert "classpath" in result["refusal"]["message"], result


def test_sidecar_rename_sealed_permitted_subtype_updates_permits_clause(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010 (sealed classes): renaming a permitted subtype rewrites the `permits` clause reference in the sealed type and
    # renames the subtype's file — a sealed-hierarchy reference, not just a plain usage.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Shape.java").write_text("package demo;\npublic sealed interface Shape permits Circle {}\n", encoding="utf-8")
    circle = src / "Circle.java"
    circle.write_text("package demo;\npublic final class Circle implements Shape {}\n", encoding="utf-8")
    col = "public final class Circle implements Shape {}".index("Circle") + 1
    result = _preview_rename(sidecar_jar, tmp_path, "src/main/java/demo/Circle.java", 2, col, "Round")

    assert result.get("accepted") is True, result
    ops = file_ops(result["workspaceEdit"])
    assert any(op["kind"] == "rename" and op["newRelativePath"].endswith("Round.java") for op in ops), result
    # The `permits Circle` reference in Shape.java must be rewritten to `permits Round`.
    shape_edits = [e for e in text_edits(result["workspaceEdit"]) if e["relativePath"].endswith("Shape.java")]
    assert shape_edits, result
    assert all(e["replacement"] == "Round" for e in shape_edits), shape_edits


def test_sidecar_rename_preserves_unicode_and_uses_correct_offsets(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010 (Unicode): a method rename in a file containing multi-byte and supplementary (astral) characters before the
    # target must land on the correct characters and leave the Unicode content byte-for-byte intact.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "class Main {\n"
        '    String s = "café 日本語 \U0001f340";\n'
        "    private int target() { return 1; }\n"
        "    int run() { return target(); }\n"
        "}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = "    private int target() { return 1; }".index("target") + 1
    result = _preview_rename(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 4, col, "renamed")

    assert result.get("accepted") is True, result
    updated = source
    for edit in sorted(text_edits(result["workspaceEdit"]), key=lambda e: e["startOffset"], reverse=True):
        # Offsets are UTF-16 code-unit indices; convert to Python string indices for an exact apply check.
        units = source.encode("utf-16-le")
        start = len(units[: edit["startOffset"] * 2].decode("utf-16-le"))
        end = len(units[: edit["endOffset"] * 2].decode("utf-16-le"))
        updated = updated[:start] + edit["replacement"] + updated[end:]
    assert "private int renamed()" in updated, updated
    assert "return renamed();" in updated, updated
    assert "café 日本語 \U0001f340" in updated, updated


# --- G002: staged binding-change checks for local/parameter/field rename ----------------------------------------------
# The documented failure mode: renaming a variable INTO a name already referenced (by a field or an outer-scope
# variable) inside the renamed variable's scope must be refused, because that existing reference would silently rebind
# to the renamed variable while still compiling — javac post-validation cannot catch it, so it is a pre-edit conflict.


def test_sidecar_rename_local_to_field_name_refused_when_field_referenced(sidecar_jar: Path, tmp_path: Path) -> None:
    # The canonical case from the review: a field `value` is read unqualified inside local `x`'s scope. Renaming x->value
    # would rebind that `value` read from the field to the renamed local. Must be refused.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    int value = 1;\n    void run() {\n        int x = 2;\n        int y = value + x;\n    }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    x_col = source.split("\n")[4].index("int x") + len("int ") + 1
    result = _preview_rename(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 5, x_col, "value")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "name_conflict", result
    assert "rebind" in result["refusal"]["message"], result["refusal"]["message"]


def test_sidecar_rename_local_to_field_name_allowed_when_field_unreferenced(sidecar_jar: Path, tmp_path: Path) -> None:
    # No-false-positive: the field `value` exists but is never referenced unqualified in the local's scope, so renaming
    # x->value introduces no rebinding and is allowed (the field is merely shadowed for the rest of the scope).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    int value = 1;\n    void run() {\n        int x = 2;\n        int y = x + 3;\n    }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    x_col = source.split("\n")[4].index("int x") + len("int ") + 1
    result = _preview_rename(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 5, x_col, "value")

    assert result.get("accepted") is True, result


def test_sidecar_rename_parameter_to_field_name_refused_when_field_referenced(sidecar_jar: Path, tmp_path: Path) -> None:
    # Same rebinding check for a PARAMETER: renaming param `p` to `value` where the body reads the field `value`
    # unqualified would rebind that read to the parameter.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    int value = 1;\n    int run(int p) {\n        return value + p;\n    }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    p_col = source.split("\n")[3].index("int p") + len("int ") + 1
    result = _preview_rename(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 4, p_col, "value")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "name_conflict", result


def test_sidecar_rename_field_to_local_name_refused_when_local_in_scope(sidecar_jar: Path, tmp_path: Path) -> None:
    # Field-direction rebind: renaming field `data` to `value` while a method references the field unqualified AND
    # declares a local `value` in scope — the rewritten field reference would bind to the local instead of the field.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\nclass Main {\n    int data = 1;\n    int run() {\n        int value = 9;\n        return data + value;\n    }\n}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    field_col = source.split("\n")[2].index("int data") + len("int ") + 1
    result = _preview_rename(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 3, field_col, "value")

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "name_conflict", result


# --- G006: V1 rename requirement -> test traceability map -------------------------------------------------------------
# Every V1 semantic-rename requirement bullet maps here to the explicit test(s) that exercise it. The map is verified by
# `test_v1_rename_requirements_are_all_covered`, so it cannot silently rot: deleting or renaming a referenced test fails
# the suite. This is the dedicated, traceable coverage record the V1 acceptance review required.
V1_RENAME_REQUIREMENT_MAP: dict[str, list[str]] = {
    "override groups": [
        "test_sidecar_rename_method_renames_override_group",
        "test_sidecar_rename_superclass_method_renames_override_group",
    ],
    "static imports": [
        "test_sidecar_rename_static_import_field_rewrites_import_and_use",
    ],
    "constructors through type rename": [
        "test_sidecar_rename_constructor_through_type_rename_applies",
        "test_sidecar_rename_record_type_updates_constructions",
        "test_sidecar_rename_constructor_reference_rewrites_type_token",
    ],
    "method references": [
        "test_sidecar_rename_includes_method_reference_expression",
        "test_sidecar_rename_method_reference_and_call_target_method_token",
    ],
    "non-BMP offsets": [
        "test_sidecar_apply_rename_after_non_bmp_char_uses_utf16_offsets",
        "test_sidecar_rename_non_bmp_identifier_uses_correct_offset",
        "test_sidecar_rename_preserves_unicode_and_uses_correct_offsets",
    ],
    "comments/Javadocs flags": [
        "test_sidecar_rename_includes_javadoc_link_and_see_only_when_enabled",
        "test_sidecar_rename_includes_param_javadoc_for_renameable_parameter",
        "test_sidecar_rename_includes_comments_and_strings_only_when_enabled_with_warning",
        "test_sidecar_rename_skips_comment_and_string_literal",
    ],
    "shadowing": [
        "test_sidecar_rename_local_variable_shadowing_renames_only_inner_scope",
        "test_sidecar_rename_local_to_field_name_refused_when_field_referenced",
        "test_sidecar_rename_local_to_field_name_allowed_when_field_unreferenced",
        "test_sidecar_rename_parameter_to_field_name_refused_when_field_referenced",
        "test_sidecar_rename_field_to_local_name_refused_when_local_in_scope",
        "test_sidecar_rename_local_refuses_name_in_enclosing_scope",
        "test_sidecar_rename_local_refuses_name_in_same_scope",
    ],
    "overload ambiguity": [
        "test_sidecar_rename_refuses_same_arity_overload_ambiguity",
        "test_sidecar_rename_refuses_inherited_same_arity_overload",
        "test_sidecar_rename_allows_different_arity_same_name",
    ],
    "import conflicts": [
        "test_sidecar_rename_type_refuses_import_collision",
    ],
    "generated-source refusal": [
        "test_sidecar_rename_refuses_edit_in_generated_source_root",
    ],
    "old-key residual verification": [
        "test_sidecar_rename_residual_rolls_back_dropped_rebinding_reference",
        "test_sidecar_rename_shadowing_field_completes_without_false_residual",
    ],
}


def test_v1_rename_requirements_are_all_covered() -> None:
    # G006: each requirement maps to at least one explicit, existing test in this module; the local/field-shadowing
    # rebinding regression must be present among the shadowing cases.
    module = globals()
    missing: list[str] = []
    for requirement, test_names in V1_RENAME_REQUIREMENT_MAP.items():
        assert test_names, f"requirement {requirement!r} has no mapped tests"
        for test_name in test_names:
            if not callable(module.get(test_name)):
                missing.append(f"{requirement} -> {test_name}")
    assert not missing, f"requirement map references missing test(s): {missing}"
    # The review's hard-blocking regression (local renamed onto a referenced field name) must be present.
    assert "test_sidecar_rename_local_to_field_name_refused_when_field_referenced" in V1_RENAME_REQUIREMENT_MAP["shadowing"]
