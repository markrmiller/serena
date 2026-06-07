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
    _apply_edits_to_text,
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



def test_sidecar_inline_local_allowed_in_modelled_context(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010 (no false positive): inlining a local used in a modelled (binary) context succeeds.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "D.java").write_text(
        "package demo;\npublic class D {\n    int m() {\n        int v = 2;\n        return v + 1;\n    }\n}\n", encoding="utf-8"
    )

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 4, "column": 13})

    assert result.get("accepted") is True, result



def test_sidecar_inline_local_refuses_unsupported_context(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: a usage in a context the parenthesization model does not cover (a method-reference qualifier `s::trim`) is
    # refused instead of emitting a possibly-mis-parenthesized edit.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "D.java").write_text(
        "package demo;\nimport java.util.function.Supplier;\npublic class D {\n    Supplier<String> m() {\n"
        '        String s = "x";\n        return s::trim;\n    }\n}\n',
        encoding="utf-8",
    )

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 5, "column": 16})

    assert result.get("accepted") is False
    assert result["refusal"]["code"] == "unsupported_inline_context"



def test_sidecar_inline_private_constant_g010_removes_declaration(sidecar_jar: Path, tmp_path: Path) -> None:
    # G010: inlining a private compile-time constant replaces its usages and removes its declaration. A missed reference
    # would dangle after declaration removal and be caught by javac post-validation (the old-key safety guarantee).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "C.java").write_text(
        "package demo;\npublic class C {\n    private static final int N = 5;\n    int use() { return N + 1; }\n}\n",
        encoding="utf-8",
    )

    result = _preview_op(sidecar_jar, tmp_path, "inlineConstant", {"relativePath": "src/main/java/demo/C.java", "line": 3, "column": 30})

    assert result.get("accepted") is True, result
    # The declaration delete and the usage replacement are both planned.
    assert result["workspaceEdit"]["stats"]["editCount"] >= 2, result["workspaceEdit"]



def test_java_inline_local_variable_manager_applies_parenthesized_replacement(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
class Main {
    int run() {
        int value = 1 + 2;
        return value * value;
    }
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.inline_local_variable("src/main/java/demo/Main.java", 4, 13, apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True
    updated = source.read_text(encoding="utf-8")
    assert "int value =" not in updated
    assert "return (1 + 2) * (1 + 2);" in updated



def test_sidecar_inline_constant_preview_and_unsafe_refusal(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    private static final int LIMIT = 4;
    int ok() { return LIMIT; }
    int bad() {
        int value = call();
        return value;
    }
    int call() { return 1; }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        constant = client.preview("inlineConstant", {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": 30})
        unsafe = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 6, "column": 13})
    finally:
        client.shutdown()

    assert constant["accepted"] is True
    assert constant["workspaceEdit"]["stats"]["editCount"] == 2
    assert unsafe["accepted"] is False
    assert unsafe["refusal"]["code"] == "unsafe_initializer"



def test_sidecar_inline_constant_zero_reference_private_emits_delete_only(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-4: a private compile-time constant with no remaining references is not refused — the design's "optionally delete
    # the private constant once no references remain" branch is already satisfied, so the plan is delete-only (the
    # declaration is removed, with no usage replacements).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = """package demo;
class Main {
    private static final int UNUSED = 4;
    int run() { return 1; }
}
"""
    (src / "Main.java").write_text(source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineConstant", {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": 30})
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edits = text_edits(result["workspaceEdit"])
    # Delete-only: the single edit removes the declaration and produces no replacement text.
    assert all(not edit["replacement"] for edit in edits), edits
    edited = _apply_edits_to_text(source, edits)
    assert "UNUSED" not in edited
    assert "int run() { return 1; }" in edited



def test_sidecar_inline_local_refuses_reassigned_variable(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    int run() {
        int value = 1 + 2;
        value = 5;
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

        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": 13})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "not_effectively_final"



def test_sidecar_inline_constant_refuses_non_static_final_field(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    final int LIMIT = 4;
    int ok() { return LIMIT; }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))

        result = client.preview("inlineConstant", {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": 15})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "not_constant"



def test_sidecar_inline_private_compile_time_constant_applies_with_parentheses(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
class Main {
    private static final int K = 1 + 2;
    int u() { return K * 3; }
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.inline_constant("src/main/java/demo/Main.java", 3, 30, apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True and not result.get("rolledBack"), result
    updated = source.read_text(encoding="utf-8")
    # The binary initializer must be parenthesized to preserve precedence at the usage site.
    assert "return (1 + 2) * 3;" in updated
    assert "K = 1 + 2" not in updated



def test_sidecar_inline_public_constant_preview_accepted_apply_refused_by_default(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
public class Main {
    public static final int K = 5;
    int u() { return K; }
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        preview = manager.inline_constant("src/main/java/demo/Main.java", 3, 29, apply=False)
        applied = manager.inline_constant("src/main/java/demo/Main.java", 3, 29, apply=True)
    finally:
        manager.shutdown()

    assert preview["accepted"] is True, preview
    edit = preview["workspaceEdit"]
    assert any("binary compatibility" in warning for warning in edit["warnings"]), edit
    assert not any(e["kind"] == "DECLARATION" for e in text_edits(edit)), edit
    assert {e["replacement"] for e in text_edits(edit)} == {"5"}

    assert applied["accepted"] is False, applied
    assert applied["refusal"]["code"] == "public_api_apply_requires_opt_in"
    assert "public static final int K = 5;" in source.read_text(encoding="utf-8")



def test_sidecar_inline_public_constant_apply_with_allow_public_api_rewrites_usage_and_keeps_declaration(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
public class Main {
    public static final int K = 5;
    int u() { return K; }
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.inline_constant("src/main/java/demo/Main.java", 3, 29, apply=True, allow_public_api=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True, result
    updated = source.read_text(encoding="utf-8")
    assert "public static final int K = 5;" in updated
    assert "return 5;" in updated



def test_sidecar_inline_protected_constant_preview_accepted_apply_refused_by_default(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
public class Main {
    protected static final int K = 5;
    int u() { return K; }
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        preview = manager.inline_constant("src/main/java/demo/Main.java", 3, 32, apply=False)
        result = manager.inline_constant("src/main/java/demo/Main.java", 3, 32, apply=True)
    finally:
        manager.shutdown()

    assert preview["accepted"] is True, preview
    assert not any(e["kind"] == "DECLARATION" for e in text_edits(preview["workspaceEdit"])), preview
    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "public_api_apply_requires_opt_in"
    assert "protected static final int K = 5;" in source.read_text(encoding="utf-8")


def test_sidecar_inline_package_private_constant_preview_accepted_apply_refused_by_default(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
class Main {
    static final int K = 5;
    int u() { return K; }
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        preview = manager.inline_constant("src/main/java/demo/Main.java", 3, 22, apply=False)
        result = manager.inline_constant("src/main/java/demo/Main.java", 3, 22, apply=True)
    finally:
        manager.shutdown()

    assert preview["accepted"] is True, preview
    assert not any(e["kind"] == "DECLARATION" for e in text_edits(preview["workspaceEdit"])), preview
    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "public_api_apply_requires_opt_in"
    assert "static final int K = 5;" in source.read_text(encoding="utf-8")


def test_sidecar_inline_private_constant_removes_declaration(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = src / "Main.java"
    source.write_text(
        """package demo;
class Main {
    private static final int K = 5;
    int u() { return K; }
}
""",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.inline_constant("src/main/java/demo/Main.java", 3, 30, apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True and not result.get("rolledBack"), result
    updated = source.read_text(encoding="utf-8")
    # The usage is inlined and the private declaration is removed.
    assert "return 5;" in updated
    assert "K = 5" not in updated



def test_sidecar_inline_constant_refuses_non_compile_time_constant(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    private static final Object K = new Object();
    Object u() { return K; }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineConstant", {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": 33})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "not_compile_time_constant"



def test_sidecar_inline_local_refuses_resource_variable(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
import java.io.Closeable;
import java.io.IOException;
class Main {
    void run() throws IOException {
        try (Closeable r = open()) {
            use(r);
        }
    }
    Closeable open() { return null; }
    void use(Closeable c) {}
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 6, "column": 24})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "unsupported_resource_variable"



def test_sidecar_inline_local_refuses_for_init_variable(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    int run(int n) {
        int total = 0;
        for (int i = 0; i < n; i++) {
            total += i;
        }
        return total;
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 5, "column": 18})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "non_standalone_local"



def test_sidecar_inline_local_refuses_enhanced_for_variable(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
import java.util.List;
class Main {
    int run(List<String> list) {
        int total = 0;
        for (String s : list) {
            total += s.length();
        }
        return total;
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 6, "column": 21})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "non_standalone_local"



def test_sidecar_inline_local_accepts_multi_statement_line(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-3: a local that shares its line with sibling statements is a valid inline target. The declaration is removed by
    # its exact AST-backed span (not whole-line removal), so the neighbouring `noop();` and `return` statements survive,
    # and the single usage is replaced by the initializer.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = """package demo;
class Main {
    int run() {
        noop(); int x = 1; return x;
    }
    void noop() {}
}
"""
    (src / "Main.java").write_text(source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": 21})
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    edited = _apply_edits_to_text(source, text_edits(result["workspaceEdit"]))
    # The usage is inlined and the declaration is gone, while the sibling statements on the same line are untouched.
    assert "int x" not in edited
    assert "noop();" in edited
    assert "return 1;" in edited



def test_sidecar_inline_local_accepts_standalone_own_line_local(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    int run(int a, int b) {
        int x = a + b;
        return x * 2;
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": 13})
    finally:
        client.shutdown()

    assert result["accepted"] is True
    assert result["workspaceEdit"]["stats"]["editCount"] == 2
    replacements = {edit["replacement"] for edit in text_edits(result["workspaceEdit"]) if edit["replacement"]}
    assert "(a + b)" in replacements



def test_sidecar_inline_local_per_usage_context_aware_parenthesization(sidecar_jar: Path, tmp_path: Path) -> None:
    # One declaration, three usages in three different contexts: a tighter-binding multiplication (needs parens), a
    # method-invocation argument (no parens), and a return statement (no parens). Each usage must get its OWN
    # replacement text computed from that usage's surrounding context, not a single shared replacement.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    int run(int a, int b) {
        int x = a + b;
        int p = x * 2;
        int q = foo(x);
        return x;
    }
    int foo(int v) { return v; }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": 13})
    finally:
        client.shutdown()

    assert result["accepted"] is True
    source = (src / "Main.java").read_text(encoding="utf-8")
    usage_edits = [edit for edit in text_edits(result["workspaceEdit"]) if edit["replacement"]]
    # Three usage sites, each with its own replacement keyed by the source text immediately surrounding it.
    by_context = {}
    for edit in usage_edits:
        start = edit["startOffset"]
        line_start = source.rfind("\n", 0, start) + 1
        by_context[source[line_start:start].strip()] = edit["replacement"]
    assert by_context["int p ="] == "(a + b)"  # x * 2 -> (a + b) * 2
    assert by_context["int q = foo("] == "a + b"  # foo(x) -> foo(a + b)
    assert by_context["return"] == "a + b"  # return x; -> return a + b;



def test_sidecar_inline_local_additive_right_operand_of_subtraction_parenthesizes(sidecar_jar: Path, tmp_path: Path) -> None:
    # `y - x` where x = a + b: left-associative subtraction at the SAME precedence as the additive initializer requires
    # parentheses on the right operand, so the result is `y - (a + b)`, not `y - a + b`.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    int run(int a, int b, int y) {
        int x = a + b;
        return y - x;
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": 13})
    finally:
        client.shutdown()

    assert result["accepted"] is True
    replacements = {edit["replacement"] for edit in text_edits(result["workspaceEdit"]) if edit["replacement"]}
    assert replacements == {"(a + b)"}



def test_sidecar_inline_local_receiver_selector_parenthesizes(sidecar_jar: Path, tmp_path: Path) -> None:
    # A String-concatenation initializer used as the receiver of a member-select / method-invocation (`x.length()`)
    # must be parenthesized: `(a + b).length()`.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
class Main {
    int run(String a, String b) {
        String x = a + b;
        return x.length();
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 4, "column": 16})
    finally:
        client.shutdown()

    assert result["accepted"] is True
    replacements = {edit["replacement"] for edit in text_edits(result["workspaceEdit"]) if edit["replacement"]}
    assert replacements == {"(a + b)"}



def test_sidecar_inline_local_refuses_capture_in_lambda(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
import java.util.function.IntSupplier;
class Main {
    IntSupplier run(int a, int b) {
        int x = a + b;
        return () -> x;
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 5, "column": 13})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "captured_in_nested_scope"



def test_sidecar_inline_local_refuses_capture_in_anonymous_class(sidecar_jar: Path, tmp_path: Path) -> None:
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Main.java").write_text(
        """package demo;
import java.util.function.IntSupplier;
class Main {
    IntSupplier run(int a, int b) {
        int x = a + b;
        return new IntSupplier() {
            public int getAsInt() {
                return x;
            }
        };
    }
}
""",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview("inlineLocalVariable", {"relativePath": "src/main/java/demo/Main.java", "line": 5, "column": 13})
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "captured_in_nested_scope"



def test_sidecar_inline_refuses_target_in_generated_source_root(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009: inline must also refuse when the declaration lives under a generated source root.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for generated-source-root extraction")
    _write_generated_root_project(tmp_path, "package demo; class Gen { private static final int X = 1; int y = X + X; }\n")
    configuration = json.dumps({"offline": True, "allowIncompleteAnalysis": True})
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=configuration))
        source = (tmp_path / "src/generated/java/demo/Gen.java").read_text()
        col = source.index("X = 1") + 1
        result = client.preview(
            "inlineConstant", {"relativePath": "src/generated/java/demo/Gen.java", "line": 1, "column": col}
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "non_editable_target", result



def _declaration_edit(result: dict) -> dict:
    return next(edit for edit in text_edits(result["workspaceEdit"]) if edit["kind"] == "DECLARATION")


def test_sidecar_inline_local_preserves_preceding_line_comment(sidecar_jar: Path, tmp_path: Path) -> None:
    # Inline's declaration removal must not absorb an ordinary // comment above the declaration: it is a user comment
    # the V1 deletion-span rules (plan section 9) do not authorize editing. All-ASCII source, so UTF-16 offsets equal
    # Python string indices.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "public class D {\n"
        "    int m() {\n"
        "        // keep me: documents the computation below\n"
        "        int v = 2;\n"
        "        return v + 1;\n"
        "    }\n"
        "}\n"
    )
    (src / "D.java").write_text(source, encoding="utf-8")

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 5, "column": 13})

    assert result.get("accepted") is True, result
    declaration = _declaration_edit(result)
    assert source[declaration["startOffset"] : declaration["endOffset"]] == "        int v = 2;\n"


def test_sidecar_inline_local_preserves_preceding_block_comment(sidecar_jar: Path, tmp_path: Path) -> None:
    # Same contract for a /* ... */ block comment (and its * continuation lines) above the declaration.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "public class D {\n"
        "    int m() {\n"
        "        /* keep me:\n"
        "         * multi-line note */\n"
        "        int v = 2;\n"
        "        return v + 1;\n"
        "    }\n"
        "}\n"
    )
    (src / "D.java").write_text(source, encoding="utf-8")

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 6, "column": 13})

    assert result.get("accepted") is True, result
    declaration = _declaration_edit(result)
    assert source[declaration["startOffset"] : declaration["endOffset"]] == "        int v = 2;\n"


def test_sidecar_inline_local_removes_own_annotation_line(sidecar_jar: Path, tmp_path: Path) -> None:
    # An annotation is part of the declaration being removed, so inline deletes it WITH the declaration (explicit,
    # tested semantics rather than incidental comment absorption).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "public class D {\n"
        "    int m() {\n"
        '        @SuppressWarnings("unused")\n'
        "        int v = 2;\n"
        "        return v + 1;\n"
        "    }\n"
        "}\n"
    )
    (src / "D.java").write_text(source, encoding="utf-8")

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 5, "column": 13})

    assert result.get("accepted") is True, result
    declaration = _declaration_edit(result)
    assert source[declaration["startOffset"] : declaration["endOffset"]] == (
        '        @SuppressWarnings("unused")\n        int v = 2;\n'
    )


def test_sidecar_inline_constant_removes_attached_javadoc_and_annotation(sidecar_jar: Path, tmp_path: Path) -> None:
    # A directly-attached Javadoc and the declaration's own annotations document/decorate the constant being deleted,
    # so inline removes them together with the declaration (matching safe delete's deletion-span rules).
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "public class C {\n"
        "    /** The count used below. */\n"
        "    @Deprecated\n"
        "    private static final int N = 5;\n"
        "    int use() { return N + 1; }\n"
        "}\n"
    )
    (src / "C.java").write_text(source, encoding="utf-8")

    result = _preview_op(sidecar_jar, tmp_path, "inlineConstant", {"relativePath": "src/main/java/demo/C.java", "line": 5, "column": 30})

    assert result.get("accepted") is True, result
    declaration = _declaration_edit(result)
    assert source[declaration["startOffset"] : declaration["endOffset"]] == (
        "    /** The count used below. */\n    @Deprecated\n    private static final int N = 5;\n"
    )


def test_sidecar_inline_constant_preserves_preceding_line_comment(sidecar_jar: Path, tmp_path: Path) -> None:
    # A plain // comment above a constant is NOT attached documentation; inlining the constant must leave it in place.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "public class C {\n"
        "    // TODO revisit this constant\n"
        "    private static final int N = 5;\n"
        "    int use() { return N + 1; }\n"
        "}\n"
    )
    (src / "C.java").write_text(source, encoding="utf-8")

    result = _preview_op(sidecar_jar, tmp_path, "inlineConstant", {"relativePath": "src/main/java/demo/C.java", "line": 4, "column": 30})

    assert result.get("accepted") is True, result
    declaration = _declaration_edit(result)
    assert source[declaration["startOffset"] : declaration["endOffset"]] == "    private static final int N = 5;\n"


def test_sidecar_inline_constant_preserves_blank_line_separated_comment(sidecar_jar: Path, tmp_path: Path) -> None:
    # A comment separated from the declaration by a blank line is clearly not attached; it must be preserved.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = (
        "package demo;\n"
        "public class C {\n"
        "    // standalone note about the class\n"
        "\n"
        "    private static final int N = 5;\n"
        "    int use() { return N + 1; }\n"
        "}\n"
    )
    (src / "C.java").write_text(source, encoding="utf-8")

    result = _preview_op(sidecar_jar, tmp_path, "inlineConstant", {"relativePath": "src/main/java/demo/C.java", "line": 5, "column": 30})

    assert result.get("accepted") is True, result
    declaration = _declaration_edit(result)
    assert source[declaration["startOffset"] : declaration["endOffset"]] == "    private static final int N = 5;\n"


def test_sidecar_inline_local_refuses_method_call_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    # Plan section 15 inline case "method-call initializer refusal": duplicating a call at each usage could change
    # behavior (side effects, evaluation count), so inline refuses it.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "D.java").write_text(
        "package demo;\n"
        "public class D {\n"
        "    int compute() { return 3; }\n"
        "    int m() {\n"
        "        int v = compute();\n"
        "        return v + v;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 5, "column": 13})

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unsafe_initializer", result


def test_sidecar_inline_local_refuses_multi_declarator(sidecar_jar: Path, tmp_path: Path) -> None:
    # Plan section 15 inline case "multi-declarator refusal": removing one declarator from `int a = 1, b = 2;` cannot
    # be done with the line-oriented declaration removal, so inline refuses rather than corrupting the sibling.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "D.java").write_text(
        "package demo;\n"
        "public class D {\n"
        "    int m() {\n"
        "        int a = 1, b = 2;\n"
        "        return a + b;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 4, "column": 13})

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "ambiguous_multi_declarator", result


def test_sidecar_inline_local_refuses_dependency_reassigned_after_declaration(sidecar_jar: Path, tmp_path: Path) -> None:
    # Soundness: `int a = 1; int x = a; a = 2; return x;` must refuse. The initializer is pure and x is effectively
    # final, but `a` changes between the declaration and the use, so inlining would compile yet change the result.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "D.java").write_text(
        "package demo;\n"
        "public class D {\n"
        "    int m() {\n"
        "        int a = 1;\n"
        "        int x = a;\n"
        "        a = 2;\n"
        "        return x;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 5, "column": 13})

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unstable_initializer_dependency", result


def test_sidecar_inline_local_refuses_field_dependency_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    # `int x = this.a;` must refuse: a non-constant field read can change before a usage site (here `this.a = 2`),
    # and javac validation cannot catch the behavior change because the inlined result still compiles.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "D.java").write_text(
        "package demo;\n"
        "public class D {\n"
        "    int a = 1;\n"
        "    int m() {\n"
        "        int x = this.a;\n"
        "        this.a = 2;\n"
        "        return x;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 5, "column": 13})

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unstable_initializer_dependency", result


def test_sidecar_inline_local_refuses_array_element_dependency_initializer(sidecar_jar: Path, tmp_path: Path) -> None:
    # `int x = arr[i];` must refuse: an array element is never provably stable between declaration and use.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "D.java").write_text(
        "package demo;\n"
        "public class D {\n"
        "    int m(int[] arr, int i) {\n"
        "        int x = arr[i];\n"
        "        arr[i] = 2;\n"
        "        return x;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 4, "column": 13})

    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "unstable_initializer_dependency", result


def test_sidecar_inline_local_accepts_stable_final_local_dependency(sidecar_jar: Path, tmp_path: Path) -> None:
    # `final int a = 1; int x = a + 1; return x;` may accept: every value the initializer reads is proven stable
    # (a final, never-reassigned local), so re-evaluation at the usage site observes the same value.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "D.java").write_text(
        "package demo;\n"
        "public class D {\n"
        "    int m() {\n"
        "        final int a = 1;\n"
        "        int x = a + 1;\n"
        "        return x;\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )

    result = _preview_op(sidecar_jar, tmp_path, "inlineLocalVariable", {"relativePath": "src/main/java/demo/D.java", "line": 5, "column": 13})

    assert result.get("accepted") is True, result
    replacements = {edit["replacement"] for edit in text_edits(result["workspaceEdit"]) if edit["replacement"]}
    assert "a + 1" in replacements, replacements
