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
from test.serena._java_refactor_sidecar_helpers import (  # noqa: F401
    CROSS_SOURCE_SET_CONFIG,
    _apply_edits_to_text,
    _build_processor_jar,
    _build_vendored_jar,
    _crafted_apply,
    _plain_project,
    _preview_op,
    _preview_rename,
    _preview_safe_delete,
    _utf16_offset,
    _write_cross_source_set_project,
    _write_demo_main,
    _write_divergent_gradle_project,
    _write_generated_root_project,
    _write_gradle_java_project,
    _write_source_level_divergent_project,
    _write_two_module_project,
    file_ops,
    maven_offline_config,
    maven_offline_repo,
    run_status,
    sidecar_jar,
    text_edits,
    write_maven_offline_project,
)


def test_sidecar_safe_delete_refuses_abstract_method(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009: an abstract method is refused even with allow_public_api, because deleting only its declaration breaks the
    # contract implementors satisfy and whole-override-group delete is unsupported in v1.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "A.java").write_text("package demo;\npublic abstract class A {\n    abstract void task();\n}\n", encoding="utf-8")

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/A.java", 3, 19, allow_public_api=True)

    assert result.get("accepted") is False
    assert result["refusal"]["code"] == "method_hierarchy_relationship"
    assert "abstract or interface" in result["refusal"]["message"]


def test_sidecar_safe_delete_refuses_interface_method(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009: an interface method is refused (with allow_public_api so the check reaches the hierarchy guard).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "I.java").write_text("package demo;\npublic interface I {\n    void op();\n}\n", encoding="utf-8")

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/I.java", 3, 10, allow_public_api=True)

    assert result.get("accepted") is False
    assert result["refusal"]["code"] == "method_hierarchy_relationship"


def test_sidecar_safe_delete_accepts_shared_line_local(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-2: an unused local that shares its line with other code is safely deletable via exact-span removal. Only the
    # `int a = 1;` declaration is removed; the sibling `bar();` call on the same line survives.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\npublic class M {\n    void m() { int a = 1; bar(); }\n    void bar() {}\n}\n"
    (src / "M.java").write_text(source, encoding="utf-8")

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/M.java", 3, 20)

    assert result.get("accepted") is True, result
    edited = _apply_edits_to_text(source, text_edits(result["workspaceEdit"]))
    assert "int a" not in edited
    assert "bar();" in edited


def test_sidecar_safe_delete_multi_declarator_local_removes_first(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-2: deleting the FIRST declarator of a multi-declarator local keeps the shared type and the sibling declarator
    # via exact comma surgery: `int a = 1, b = 2;` -> `int b = 2;`.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\npublic class M {\n    int m() { int a = 1, b = 2; return b; }\n}\n"
    (src / "M.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("a = 1") + 1

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/M.java", 3, col)

    assert result.get("accepted") is True, result
    edited = _apply_edits_to_text(source, text_edits(result["workspaceEdit"]))
    assert "int b = 2;" in edited
    assert "a = 1" not in edited


def test_sidecar_safe_delete_multi_declarator_local_removes_last(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-2: deleting the LAST declarator of a multi-declarator local removes it (and its preceding comma) while keeping
    # the surviving declarator intact: `int a = 1, b = 2;` -> `int a = 1;`.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\npublic class M {\n    int m() { int a = 1, b = 2; return a; }\n}\n"
    (src / "M.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("b = 2") + 1

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/M.java", 3, col)

    assert result.get("accepted") is True, result
    edited = _apply_edits_to_text(source, text_edits(result["workspaceEdit"]))
    assert "int a = 1;" in edited
    assert "b = 2" not in edited


def test_sidecar_safe_delete_refuses_for_init_variable(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-2: a for-loop initializer variable is refused with a construct-specific reason (not a generic "standalone only").
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\npublic class M {\n    boolean cond = true;\n    void m() { for (int i = 0; cond; ) {} }\n}\n"
    (src / "M.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[3].index("i = 0") + 1

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/M.java", 4, col)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_local_variable_delete"
    assert "for-loop initializer" in result["refusal"]["message"]


def test_sidecar_safe_delete_refuses_enhanced_for_variable(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-2: an enhanced-for (for-each) loop variable is refused with a construct-specific reason.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\npublic class M {\n"
        '    void m() { for (String s : java.util.List.of("x")) {} }\n}\n'
    )
    (src / "M.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("s :") + 1

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/M.java", 3, col)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_local_variable_delete"
    assert "enhanced-for" in result["refusal"]["message"]


def test_sidecar_safe_delete_refuses_catch_parameter(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-2: a catch clause's exception parameter is refused with a construct-specific reason.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\npublic class M {\n    void m() { try {} catch (Exception e) {} }\n}\n"
    (src / "M.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("e)") + 1

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/M.java", 3, col)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_local_variable_delete"
    assert "exception parameter" in result["refusal"]["message"]


def test_sidecar_safe_delete_refuses_resource_variable(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-2: a try-with-resources resource variable is refused with a construct-specific reason (it owns a closeable).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\npublic class M {\n"
        "    void m() throws Exception { try (var r = new java.io.ByteArrayInputStream(new byte[0])) {} }\n}\n"
    )
    (src / "M.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("r =") + 1

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/M.java", 3, col)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_local_variable_delete"
    assert "resource variable" in result["refusal"]["message"]


def test_sidecar_safe_delete_allows_own_line_unused_local(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009 (no false positive): an unused local on its own line is still safely deletable.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "N.java").write_text("package demo;\npublic class N {\n    void m() {\n        int a = 1;\n    }\n}\n", encoding="utf-8")

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/N.java", 4, 13)

    assert result.get("accepted") is True, result


def test_sidecar_safe_delete_parameter_conservative_when_body_absent(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009: parameter-use analysis fails closed when the method body cannot be located (here a bodyless native method),
    # so the parameter is treated as used and the delete is refused.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Native.java").write_text(
        "package demo;\npublic class Native {\n    private static native int compute(int x);\n}\n", encoding="utf-8"
    )

    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/Native.java", 3, 43)

    assert result.get("accepted") is False
    assert result["refusal"]["code"] in {"parameter_in_use", "unsupported_parameter_delete"}


def test_sidecar_safe_delete_refuses_cross_source_set_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    _write_cross_source_set_project(tmp_path)
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=CROSS_SOURCE_SET_CONFIG))

        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Service.java", "line": 3, "column": 9})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "semantic_references_exist"
    refused_paths = {reference["relativePath"] for reference in result["references"]}
    assert "src/test/java/demo/ServiceTest.java" in refused_paths


def test_sidecar_safe_delete_refuses_live_references(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text("package demo;\nclass Main { private void helper() {} void run() { helper(); } }\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 2, "column": 30})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "semantic_references_exist"
    assert result["references"][0]["text"] == "helper"


def test_java_safe_delete_manager_applies_private_method_delete(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
class Main {
    // safe comment
    @Deprecated
    private void unused() {}
    void run() {}
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.safe_delete("src/main/java/demo/Main.java", 5, 18, apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True
    updated = source.read_text(encoding="utf-8")
    assert "unused" not in updated
    assert "@Deprecated" not in updated
    # G010: the tightened deletion range no longer absorbs a preceding // line comment.
    assert "safe comment" in updated
    assert "void run() {}" in updated


def test_sidecar_safe_delete_refuses_public_api_and_multi_declarator(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text("package demo;\npublic class Main { public void api() {} private int a, b; }\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        public_result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 2, "column": 33})
        field_result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 2, "column": 57})
    finally:
        client.shutdown()

    assert public_result["accepted"] is False
    assert public_result["refusal"]["code"] == "public_api"
    assert field_result["accepted"] is False
    assert field_result["refusal"]["code"] == "ambiguous_multi_declarator"


def test_sidecar_safe_delete_public_api_override_allows_unreferenced_field_and_method(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\npublic class Main {\n    public int field = 1;\n"
        "    protected void api() {}\n}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        field_col = source.split("\n")[2].index("field") + 1
        method_col = source.split("\n")[3].index("api") + 1
        default_field = client.preview(
            "safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": field_col}
        )
        default_method = client.preview(
            "safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": method_col}
        )
        allowed_field = client.preview(
            "safeDelete",
            {
                "relativePath": "src/main/java/demo/Main.java",
                "line": 3,
                "column": field_col,
                "allowPublicApi": True,
            },
        )
        allowed_method = client.preview(
            "safeDelete",
            {
                "relativePath": "src/main/java/demo/Main.java",
                "line": 4,
                "column": method_col,
                "allowPublicApi": True,
            },
        )
    finally:
        client.shutdown()

    assert default_field["accepted"] is False, default_field
    assert default_field["refusal"]["code"] == "public_api"
    assert default_method["accepted"] is False, default_method
    assert default_method["refusal"]["code"] == "public_api"
    assert allowed_field["accepted"] is True, allowed_field
    assert allowed_method["accepted"] is True, allowed_method


def test_sidecar_safe_delete_public_api_override_still_refuses_live_reference(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\npublic class Main {\n    public int field = 1;\n    int use() { return field; }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        field_col = source.split("\n")[2].index("field") + 1
        result = client.preview(
            "safeDelete",
            {
                "relativePath": "src/main/java/demo/Main.java",
                "line": 3,
                "column": field_col,
                "allowPublicApi": True,
            },
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "semantic_references_exist"


def test_sidecar_safe_delete_refusal_envelope_has_can_delete_reason_and_references(
    sidecar_jar: Path, tmp_path: Path
) -> None:
    # G004: a safe-delete refusal conforms to the V1 result shape — canDelete:false and a reason field alongside the
    # blocking references[] — while preserving the shared accepted:false + refusal:{code,message} envelope.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        "package demo;\nclass Main { private void helper() {} void run() { helper(); } }\n", encoding="utf-8"
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        # Live reference -> refusal carrying the rich references payload.
        with_refs = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 2, "column": 30})
        # target_not_found -> refusal with an empty references[]; still carries canDelete/reason.
        no_target = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 2, "column": 1})
    finally:
        client.shutdown()

    assert with_refs["accepted"] is False
    assert with_refs["canDelete"] is False
    # The V1 reason mirrors the refusal message.
    assert with_refs["reason"] == with_refs["refusal"]["message"]
    assert with_refs["refusal"]["code"] == "semantic_references_exist"
    assert any(reference["text"] == "helper" for reference in with_refs["references"])

    assert no_target["accepted"] is False
    assert no_target["canDelete"] is False
    assert no_target["reason"] == no_target["refusal"]["message"]
    assert no_target["references"] == []


def test_sidecar_safe_delete_plans_top_level_type_file_delete(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Gone.java").write_text("package demo; class Gone {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Gone.java", "line": 1, "column": 21})
    finally:
        client.shutdown()

    assert result["accepted"] is True
    assert file_ops(result["workspaceEdit"])[0]["kind"] == "delete"
    assert file_ops(result["workspaceEdit"])[0]["relativePath"] == "src/main/java/demo/Gone.java"


def test_sidecar_safe_delete_sole_top_level_type_deletes_file(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Solo.java").write_text("package demo;\nclass Solo {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Solo.java", "line": 2, "column": 7})
    finally:
        client.shutdown()

    assert result["accepted"] is True
    assert file_ops(result["workspaceEdit"])[0]["kind"] == "delete"
    assert file_ops(result["workspaceEdit"])[0]["relativePath"] == "src/main/java/demo/Solo.java"
    assert text_edits(result["workspaceEdit"]) == []


def test_sidecar_safe_delete_multi_type_file_deletes_only_declaration(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Foo.java").write_text("package demo;\nclass Foo {}\nclass Helper {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Foo.java", "line": 2, "column": 7})
    finally:
        client.shutdown()

    assert result["accepted"] is True
    assert file_ops(result["workspaceEdit"]) == []
    assert result["workspaceEdit"]["stats"]["editCount"] == 1
    assert len(text_edits(result["workspaceEdit"])) == 1
    assert text_edits(result["workspaceEdit"])[0]["relativePath"] == "src/main/java/demo/Foo.java"


def test_sidecar_safe_delete_refuses_parameter_of_non_private_method(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a parameter of a non-private (package-private) method is still refused, because removing it would require
    # editing an API that may be called outside the compilation unit.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text("package demo;\nclass Main { void m(int x, int y) {} }\n", encoding="utf-8")
    line = "class Main { void m(int x, int y) {} }"
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview(
            "safeDelete",
            {"relativePath": "src/main/java/demo/Main.java", "line": 2, "column": line.index("x") + 1},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "unsupported_parameter_delete", result


def test_sidecar_safe_delete_removes_unused_private_method_parameter(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a parameter of a private, non-hierarchy method with no body uses is deleted: the declaration param is
    # removed AND the positional argument at every call site is removed.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "class Main {\n"
        "    private int helper(int keep, int drop) { return keep; }\n"
        "    int run() { return helper(1, 2); }\n"
        "}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    drop_col = source.split("\n")[2].index("int drop") + len("int ") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "safeDelete",
            {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": drop_col},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edits = sorted(text_edits(result["workspaceEdit"]), key=lambda e: e["startOffset"])
    assert len(edits) == 2, result
    assert all(e["replacement"] == "" for e in edits)
    # Apply the (empty-text) removals manually to verify the resulting source is correct.
    updated = source
    for edit in sorted(edits, key=lambda e: e["startOffset"], reverse=True):
        updated = updated[: edit["startOffset"]] + edit["replacement"] + updated[edit["endOffset"] :]
    assert "private int helper(int keep)" in updated, updated
    assert "helper(1)" in updated, updated
    assert "drop" not in updated, updated


def test_sidecar_safe_delete_refuses_parameter_of_override_method(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a parameter of an @Override / interface-bound method is refused even when the method is private-looking,
    # because parameter names participate in the override hierarchy.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Service.java").write_text("package demo;\ninterface Service { void handle(int code); }\n", encoding="utf-8")
    (src / "Impl.java").write_text(
        "package demo;\nclass Impl implements Service { @Override public void handle(int code) {} }\n",
        encoding="utf-8",
    )
    impl_line = "class Impl implements Service { @Override public void handle(int code) {} }"
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "safeDelete",
            {"relativePath": "src/main/java/demo/Impl.java", "line": 2, "column": impl_line.index("int code") + len("int ") + 1},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "unsupported_parameter_delete", result


def test_sidecar_safe_delete_refuses_used_private_method_parameter(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a private-method parameter that IS used in the body is refused (cannot drop a live argument).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\nclass Main {\n    private int helper(int a, int b) { return a + b; }\n    int run() { return helper(1, 2); }\n}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    b_col = source.split("\n")[2].index("int b") + len("int ") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "safeDelete",
            {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": b_col},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "parameter_in_use", result


def test_sidecar_safe_delete_allows_standalone_local_variable(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a plain standalone block-statement local with no uses is deletable.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = 'package demo;\nclass Main {\n    void run() {\n        int unused = 7;\n        System.out.println("x");\n    }\n}\n'
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[3].index("unused") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": col})
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    assert result["workspaceEdit"]["stats"]["editCount"] == 1, result


def test_sidecar_safe_delete_refuses_try_with_resources_local(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a try-with-resources resource variable is NOT a standalone block-statement local -> refused.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "import java.io.StringReader;\n"
        "class Main {\n"
        "    void run() throws Exception {\n"
        '        try (StringReader r = new StringReader("x")) {\n'
        "        }\n"
        "    }\n"
        "}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[4].index("StringReader r") + len("StringReader ") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 5, "column": col})
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "unsupported_local_variable_delete", result


def test_sidecar_safe_delete_refuses_enhanced_for_local(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: an enhanced-for loop variable is not a standalone block-statement local -> refused.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    void run(int[] xs) {\n        for (int item : xs) {}\n    }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[3].index("int item") + len("int ") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": col})
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "unsupported_local_variable_delete", result


def test_sidecar_safe_delete_removes_attached_javadoc_not_preceding_line_comment(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: deletion span absorbs a directly-attached /** */ Javadoc, but NOT a preceding // line comment.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "class Main {\n"
        "    // keep this comment\n"
        "    /** doc for unused */\n"
        "    private void unused() {}\n"
        "    void run() {}\n"
        "}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[4].index("unused") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 5, "column": col})
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edit = text_edits(result["workspaceEdit"])[0]
    removed = source[edit["startOffset"] : edit["endOffset"]]
    assert "doc for unused" in removed, removed
    assert "keep this comment" not in removed, removed
    # Applying the edit leaves the preceding line comment intact.
    updated = source[: edit["startOffset"]] + edit["replacement"] + source[edit["endOffset"] :]
    assert "// keep this comment" in updated, updated
    assert "unused" not in updated, updated


def test_sidecar_safe_delete_refusal_payload_has_containing_symbol_and_snippet(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: rich refusal payload includes containingSymbol (enclosing method/type name-path) and snippet (source line).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    private int value() { return 1; }\n    int run() { return value(); }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("value") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": col})
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "semantic_references_exist", result
    ref = result["references"][0]
    assert ref["containingSymbol"] == "Main/run", ref
    assert "return value();" in ref["snippet"], ref
    assert ref["path"].endswith("Main.java"), ref


def test_java_safe_delete_manager_removes_entire_multiline_declaration(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
class Main {
    private int helper(int a,
                       int b) {
        int sum = a + b;
        return sum;
    }
    void run() {}
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.safe_delete("src/main/java/demo/Main.java", 3, 17, apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True
    updated = source.read_text(encoding="utf-8")
    # The whole multi-line method body must be removed, not just its first line.
    assert "helper" not in updated
    assert "sum" not in updated
    assert "int b" not in updated
    assert "void run() {}" in updated


def test_sidecar_safe_delete_refuses_overridden_method(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    base_line = "package ex; class Base { void run(){} }"
    (src / "Base.java").write_text(base_line + "\n", encoding="utf-8")
    # A subtype overrides run(); deleting the base declaration would orphan the @Override even with no callers.
    (src / "Sub.java").write_text("package ex; class Sub extends Base { @Override void run(){} }\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "safeDelete", {"relativePath": "src/main/java/ex/Base.java", "line": 1, "column": base_line.index("run") + 1}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "method_hierarchy_relationship"


def test_sidecar_safe_delete_refuses_library_override(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    source_line = 'package ex; class O { @Override public String toString(){ return "x"; } }'
    (src / "O.java").write_text(source_line + "\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "safeDelete",
            {"relativePath": "src/main/java/ex/O.java", "line": 1, "column": source_line.index("toString") + 1, "allowPublicApi": True},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "method_hierarchy_relationship"


def test_sidecar_safe_delete_allows_standalone_unreferenced_method(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/ex"
    src.mkdir(parents=True)
    source_line = "package ex; class C { private void helper(){} void run(){} }"
    (src / "C.java").write_text(source_line + "\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "safeDelete", {"relativePath": "src/main/java/ex/C.java", "line": 1, "column": source_line.index("helper") + 1}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    assert result["workspaceEdit"]["stats"]["editCount"] == 1


def test_sidecar_safe_delete_second_type_with_syntax_error_keeps_file(sidecar_jar: Path, tmp_path: Path) -> None:
    """M7: with a second top-level type that has a syntax error, safe delete must NOT delete the whole file.

    Under the incomplete parse javac's top-level-type count can drop the broken second type, which would mislead the
    sole-type heuristic into deleting a file that still holds another type. The model reports a diagnostic for the file,
    so safe delete falls back to a declaration-only delete (a text edit) instead of a whole-file delete operation.
    """
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    # `Broken` has a syntax error (missing closing brace on the method). allow_incomplete_analysis routes the resulting
    # javac diagnostic into warnings so the model is still "ready" and the planner runs.
    (src / "Foo.java").write_text("package demo;\nclass Foo {}\nclass Broken { void m() { int x = ; } }\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=json.dumps({"allowIncompleteAnalysis": True}))
        )
        # Target the FIRST type `Foo` (line 2). It is file-named and would normally trigger a whole-file delete.
        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Foo.java", "line": 2, "column": 7})
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    # Declaration-only delete: a text edit and NO whole-file delete operation (which would lose `Broken`).
    assert file_ops(result["workspaceEdit"]) == [], result["workspaceEdit"]
    assert len(text_edits(result["workspaceEdit"])) == 1, result["workspaceEdit"]


def test_sidecar_safe_delete_refuses_target_in_generated_source_root(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009: the generated/dependency-source safety gate applies to safe delete too — deleting a declaration that lives
    # under a generated source root must be refused, not silently performed.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for generated-source-root extraction")
    _write_generated_root_project(tmp_path, "package demo; class Gen { private void unused() {} }\n")
    configuration = json.dumps({"offline": True, "allowIncompleteAnalysis": True})
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=configuration))
        col = (tmp_path / "src/generated/java/demo/Gen.java").read_text().index("unused") + 1
        result = client.preview("safeDelete", {"relativePath": "src/generated/java/demo/Gen.java", "line": 1, "column": col})
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "non_editable_target", result


def test_sidecar_safe_delete_refuses_field_used_in_annotation_value(sidecar_jar: Path, tmp_path: Path) -> None:
    # A private constant referenced only inside an annotation argument is still a live semantic reference; safe delete
    # must refuse rather than orphan the annotation value.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = 'package demo;\nclass Main {\n    private static final String NAME = "x";\n    @SuppressWarnings(NAME)\n    void run() {}\n}\n'
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("NAME") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": col})
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "semantic_references_exist", result


def test_sidecar_safe_delete_refuses_method_referenced_by_method_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    # A private method whose only reference is a `this::make` method reference must block safe delete.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "import java.util.function.Supplier;\n"
        "class Main {\n"
        '    private String make() { return "x"; }\n'
        "    Supplier<String> s = this::make;\n"
        "}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[3].index("make") + 1
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": col})
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "semantic_references_exist", result
    refs = {(r["relativePath"], r["text"]) for r in result["references"]}
    assert ("src/main/java/demo/Main.java", "make") in refs, result


def test_sidecar_safe_delete_refuses_static_field_with_side_effecting_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    # G001: a static final field whose initializer calls a method has an observable side effect; deleting the
    # declaration would drop that effect, so safe delete refuses even though the field has no references.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\nclass Main {\n    private static final Object X = init();\n    static Object init() { return new Object(); }\n}\n"
    )
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("X =") + 1
    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 3, col)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "side_effecting_initializer", result


def test_sidecar_safe_delete_refuses_instance_field_with_array_creation_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    # G001: array creation is an observable side effect (allocation); an unused instance field initialized with
    # `new int[]{...}` is refused.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    private int[] data = new int[]{1, 2, 3};\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("data") + 1
    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 3, col)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "side_effecting_initializer", result


def test_sidecar_safe_delete_refuses_local_with_method_call_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    # G001: an unused standalone local whose initializer invokes a method is refused — inlining-style side-effect
    # detection now also gates safe delete.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    int make() { return 1; }\n    void run() {\n        int x = make();\n    }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[4].index("x =") + 1
    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 5, col)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "side_effecting_initializer", result


def test_sidecar_safe_delete_refuses_local_with_constructor_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    # G001: constructor invocation (`new Object()`) is a side effect; an unused local holding it is refused.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    void run() {\n        Object o = new Object();\n    }\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[3].index("o =") + 1
    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 4, col)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "side_effecting_initializer", result


def test_sidecar_safe_delete_allows_field_with_pure_literal_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    # G001 (no false positive): a private field with a pure literal initializer and no references is still deletable.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = "package demo;\nclass Main {\n    private final int answer = 42;\n    void run() {}\n}\n"
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("answer") + 1
    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 3, col)

    assert result.get("accepted") is True, result
    assert result["workspaceEdit"]["stats"]["editCount"] == 1, result


def _safe_delete_drop_param(sidecar_jar: Path, tmp_path: Path, source: str, decl_line_index: int) -> dict:
    """Write `source` as Main.java and preview deleting the `drop` parameter declared on `decl_line_index` (0-based)."""
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(source, encoding="utf-8")
    decl_line = source.split("\n")[decl_line_index]
    col = decl_line.index("drop") + 1
    return _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", decl_line_index + 1, col)


def test_sidecar_safe_delete_param_refuses_method_invocation_argument(sidecar_jar: Path, tmp_path: Path) -> None:
    # G002: a call site passing `audit()` for the removed parameter is not provably pure -> refuse.
    source = (
        "package demo;\n"
        "class Main {\n"
        "    private int helper(int keep, int drop) { return keep; }\n"
        "    int audit() { return 0; }\n"
        "    int run() { return helper(1, audit()); }\n"
        "}\n"
    )
    result = _safe_delete_drop_param(sidecar_jar, tmp_path, source, 2)
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_parameter_delete", result


def test_sidecar_safe_delete_param_refuses_constructor_argument(sidecar_jar: Path, tmp_path: Path) -> None:
    # G002: `new Object()` argument is impure -> refuse.
    source = (
        "package demo;\n"
        "class Main {\n"
        "    private int helper(int keep, Object drop) { return keep; }\n"
        "    int run() { return helper(1, new Object()); }\n"
        "}\n"
    )
    result = _safe_delete_drop_param(sidecar_jar, tmp_path, source, 2)
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_parameter_delete", result


def test_sidecar_safe_delete_param_refuses_assignment_argument(sidecar_jar: Path, tmp_path: Path) -> None:
    # G002: an assignment expression argument (`x = 5`) is impure -> refuse.
    source = (
        "package demo;\n"
        "class Main {\n"
        "    private int helper(int keep, int drop) { return keep; }\n"
        "    int x;\n"
        "    int run() { return helper(1, x = 5); }\n"
        "}\n"
    )
    result = _safe_delete_drop_param(sidecar_jar, tmp_path, source, 2)
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_parameter_delete", result


def test_sidecar_safe_delete_param_refuses_increment_argument(sidecar_jar: Path, tmp_path: Path) -> None:
    # G002: a post-increment argument (`i++`) mutates state -> refuse.
    source = (
        "package demo;\n"
        "class Main {\n"
        "    private int helper(int keep, int drop) { return keep; }\n"
        "    int run() { int i = 0; return helper(1, i++); }\n"
        "}\n"
    )
    result = _safe_delete_drop_param(sidecar_jar, tmp_path, source, 2)
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_parameter_delete", result


def test_sidecar_safe_delete_param_refuses_array_creation_argument(sidecar_jar: Path, tmp_path: Path) -> None:
    # G002: an array-creation argument (`new int[]{1}`) is impure -> refuse.
    source = (
        "package demo;\n"
        "class Main {\n"
        "    private int helper(int keep, int[] drop) { return keep; }\n"
        "    int run() { return helper(1, new int[]{1}); }\n"
        "}\n"
    )
    result = _safe_delete_drop_param(sidecar_jar, tmp_path, source, 2)
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_parameter_delete", result


def test_sidecar_safe_delete_param_refuses_lambda_argument(sidecar_jar: Path, tmp_path: Path) -> None:
    # G002: a lambda argument carries deferred behavior -> refuse.
    source = (
        "package demo;\n"
        "class Main {\n"
        "    private int helper(int keep, Runnable drop) { return keep; }\n"
        "    int run() { return helper(1, () -> {}); }\n"
        "}\n"
    )
    result = _safe_delete_drop_param(sidecar_jar, tmp_path, source, 2)
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_parameter_delete", result


def test_sidecar_safe_delete_param_refuses_ternary_argument(sidecar_jar: Path, tmp_path: Path) -> None:
    # G002: a ternary argument is not provably pure (conservative V1) -> refuse.
    source = (
        "package demo;\n"
        "class Main {\n"
        "    private int helper(int keep, int drop) { return keep; }\n"
        "    int run() { boolean c = true; return helper(1, c ? 1 : 2); }\n"
        "}\n"
    )
    result = _safe_delete_drop_param(sidecar_jar, tmp_path, source, 2)
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsupported_parameter_delete", result


def test_sidecar_safe_delete_param_allows_identifier_argument(sidecar_jar: Path, tmp_path: Path) -> None:
    # G002 (no false positive): a plain identifier argument is provably pure -> the parameter delete is accepted and
    # the positional argument is removed at the call site.
    source = (
        "package demo;\n"
        "class Main {\n"
        "    private int helper(int keep, int drop) { return keep; }\n"
        "    int run() { int v = 3; return helper(1, v); }\n"
        "}\n"
    )
    result = _safe_delete_drop_param(sidecar_jar, tmp_path, source, 2)
    assert result.get("accepted") is True, result
    updated = source
    for edit in sorted(text_edits(result["workspaceEdit"]), key=lambda e: e["startOffset"], reverse=True):
        updated = updated[: edit["startOffset"]] + edit["replacement"] + updated[edit["endOffset"] :]
    assert "private int helper(int keep)" in updated, updated
    assert "helper(1)" in updated, updated


def test_sidecar_safe_delete_refuses_classpath_only_binary_target(sidecar_jar: Path, tmp_path: Path) -> None:
    # G007: the centralized target-origin gate refuses a target that resolves to a classpath-only binary element (the
    # JDK type java.lang.String) which has no editable source declaration.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = 'package demo;\nclass Main {\n    String text = "x";\n}\n'
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("String") + 1
    result = _preview_safe_delete(sidecar_jar, tmp_path, "src/main/java/demo/Main.java", 3, col)

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "non_editable_target", result
    assert "classpath" in result["refusal"]["message"], result

def test_sidecar_safe_delete_file_delete_carries_old_sha256(sidecar_jar: Path, tmp_path: Path) -> None:
    # A whole-file delete has NO text edits, so the delete file operation itself must carry the pre-edit hash; this is
    # the only optimistic-concurrency precondition protecting the file between planning and apply.
    from serena.java_refactor.workspace_edit import sha256_bytes

    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    target = src / "Gone.java"
    target.write_text("package demo;\nclass Gone {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview("safeDelete", {"relativePath": "src/main/java/demo/Gone.java", "line": 2, "column": 7})
    finally:
        client.shutdown()

    assert result["accepted"] is True
    operation = result["workspaceEdit"]["fileOperations"][0]
    assert operation["kind"] == "delete"
    assert operation["oldSha256"] == sha256_bytes(target.read_bytes())


def test_sidecar_rename_file_operation_carries_old_sha256(sidecar_jar: Path, tmp_path: Path) -> None:
    # A top-level type rename moves the declaration file; the rename file operation must carry the source file's
    # pre-edit hash in addition to the text-edit group hash (defense in depth for the destructive operation).
    from serena.java_refactor.workspace_edit import sha256_bytes

    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    target = src / "Solo.java"
    target.write_text("package demo;\npublic class Solo {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview(
            "semanticRename", {"relativePath": "src/main/java/demo/Solo.java", "line": 2, "column": 14, "newName": "Renamed"}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    operation = result["workspaceEdit"]["fileOperations"][0]
    assert operation["kind"] == "rename"
    assert operation["oldSha256"] == sha256_bytes(target.read_bytes())


def test_safe_delete_apply_refuses_when_file_changed_after_planning(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # End-to-end race-condition guard: the target file changes between the sidecar's planning and the Python applier's
    # commit. The whole-file delete must refuse (hash mismatch) and leave the modified file untouched.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    target = src / "Gone.java"
    target.write_text("package demo;\nclass Gone {}\n", encoding="utf-8")
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        client = manager._get_or_start_client(refresh=False)
        original_apply = client.apply_refactor

        def racing_apply(operation, params):
            result = original_apply(operation, params)
            # Concurrent modification AFTER the sidecar planned the delete but BEFORE the Python applier stages it.
            target.write_text("package demo;\nclass Gone { int newlyAdded; }\n", encoding="utf-8")
            return result

        client.apply_refactor = racing_apply  # type: ignore[method-assign]
        result = manager.safe_delete("src/main/java/demo/Gone.java", 2, 7, apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "apply_unsafe_edit"
    assert "Hash mismatch" in result["refusal"]["message"]
    # The concurrently modified file is untouched.
    assert target.read_text(encoding="utf-8") == "package demo;\nclass Gone { int newlyAdded; }\n"
