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


def test_java_move_top_level_type_manager_applies_package_file_and_import_rewrites(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    old_pkg = tmp_path / "src/main/java/demo/old"
    user_pkg = tmp_path / "src/main/java/demo/use"
    old_pkg.mkdir(parents=True)
    user_pkg.mkdir(parents=True)
    old_file = old_pkg / "Thing.java"
    old_file.write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    user_file = user_pkg / "Use.java"
    user_file.write_text(
        "package demo.use;\n"
        "import demo.old.Thing;\n"
        'class Use { demo.old.Thing field; String text = "demo.old.Thing"; /* demo.old.Thing */ Thing method() { return null; } }\n',
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type("src/main/java/demo/old/Thing.java", 2, 14, "demo.newpkg", apply=True)
    finally:
        manager.shutdown()

    new_file = tmp_path / "src/main/java/demo/newpkg/Thing.java"
    assert result["accepted"] is True
    assert not old_file.exists()
    assert new_file.exists()
    assert "package demo.newpkg;" in new_file.read_text(encoding="utf-8")
    updated_user = user_file.read_text(encoding="utf-8")
    assert "import demo.newpkg.Thing;" in updated_user
    assert "demo.newpkg.Thing field" in updated_user
    assert '"demo.old.Thing"' in updated_user
    assert "/* demo.old.Thing */" in updated_user


def test_sidecar_move_main_type_referenced_from_test_source_set(sidecar_jar: Path, tmp_path: Path) -> None:
    # G011: moving a main type that a TEST source set references must, under the directional source-set model (test
    # depends on main, from G002), discover and rewrite the cross-source-set reference in the test source set. Verified
    # at preview time so the planned edit spans both source sets.
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "sample"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\njava { sourceCompatibility = JavaVersion.VERSION_17 }\n', encoding="utf-8"
    )
    main_pkg = tmp_path / "src/main/java/app/core"
    test_pkg = tmp_path / "src/test/java/app"
    main_pkg.mkdir(parents=True)
    test_pkg.mkdir(parents=True)
    (main_pkg / "Widget.java").write_text("package app.core;\npublic class Widget {}\n", encoding="utf-8")
    (test_pkg / "WidgetTest.java").write_text(
        "package app;\nimport app.core.Widget;\nclass WidgetTest { Widget w = new Widget(); }\n", encoding="utf-8"
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=json.dumps({"offline": True})))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/app/core/Widget.java", "line": 2, "column": 14, "targetPackage": "app.moved"},
        )
    finally:
        client.shutdown()

    assert result.get("accepted") is True, result
    edits = text_edits(result["workspaceEdit"])
    test_edits = [edit for edit in edits if edit["relativePath"].replace("\\", "/") == "src/test/java/app/WidgetTest.java"]
    # The cross-source-set reference in the test set is discovered and rewritten to the new package import.
    assert any("app.moved" in edit["replacement"] for edit in test_edits), test_edits


def test_sidecar_move_within_modular_project(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # G011: moving a type between non-exported packages inside a real module validates under the modular compiler model
    # (G003), not just module-info.java detection.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java"
    (src / "demo/internal").mkdir(parents=True)
    (src / "module-info.java").write_text("module demo { }\n", encoding="utf-8")
    (src / "demo/internal/Helper.java").write_text("package demo.internal;\npublic class Helper {}\n", encoding="utf-8")
    (src / "demo").mkdir(exist_ok=True)
    (src / "demo/App.java").write_text(
        "package demo;\nimport demo.internal.Helper;\npublic class App { Helper h = new Helper(); }\n", encoding="utf-8"
    )

    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type("src/main/java/demo/internal/Helper.java", 2, 14, "demo.support", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True, result
    assert (tmp_path / "src/main/java/demo/support/Helper.java").exists()
    updated_app = (src / "demo/App.java").read_text(encoding="utf-8")
    assert "import demo.support.Helper;" in updated_app


def test_sidecar_move_top_level_type_refuses_target_collision_and_module_info(sidecar_jar: Path, tmp_path: Path) -> None:
    old_pkg = tmp_path / "src/main/java/demo/old"
    new_pkg = tmp_path / "src/main/java/demo/newpkg"
    old_pkg.mkdir(parents=True)
    new_pkg.mkdir(parents=True)
    (old_pkg / "Thing.java").write_text("package demo.old; class Thing {}\n", encoding="utf-8")
    (new_pkg / "Thing.java").write_text("package demo.newpkg; class Thing {}\n", encoding="utf-8")
    (tmp_path / "src/main/java/module-info.java").write_text("module demo.module {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=json.dumps({"allowIncompleteAnalysis": True}))
        )

        collision = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/demo/old/Thing.java", "line": 1, "column": 25, "targetPackage": "demo.newpkg"},
        )
        module_info = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/module-info.java", "line": 1, "column": 8, "targetPackage": "demo.newpkg"},
        )
    finally:
        client.shutdown()

    assert collision["accepted"] is False
    assert collision["refusal"]["code"] == "target_exists"
    assert module_info["accepted"] is False
    assert module_info["refusal"]["code"] == "unsupported_module_info"


def test_sidecar_move_detects_package_via_ast_ignoring_comment_and_string(sidecar_jar: Path, tmp_path: Path) -> None:
    # A leading block comment and a string literal both contain the text "package fake;"; the AST-based detection must
    # anchor on the real "package demo;" declaration, not the textual first match.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = '/* package fake; */\npackage demo;\npublic class Foo { String s = "package other;"; }\n'
    (src / "Foo.java").write_text(source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/demo/Foo.java", "line": 3, "column": 14, "targetPackage": "demo.sub"},
        )
    finally:
        client.shutdown()

    # The current package was correctly computed as demo (not refused as same_package), and the move proceeds.
    assert result["accepted"] is True, result
    # V1 transaction ordering: the moved file's package edit targets its CURRENT (old) path; the rename moves it.
    old_relative = "src/main/java/demo/Foo.java"
    package_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"] == old_relative and edit["replacement"].startswith("package ")
    ]
    assert len(package_edits) == 1, package_edits
    edit = package_edits[0]
    real_start = source.index("package demo;")
    assert edit["startOffset"] == real_start
    assert edit["endOffset"] == real_start + len("package demo;")
    assert edit["replacement"] == "package demo.sub;"


def test_sidecar_move_rewrites_fqn_respecting_identifier_boundaries(sidecar_jar: Path, tmp_path: Path) -> None:
    # A referencing file uses both com.old.Foo (the moved type) and the longer sibling com.old.FooBar, plus a member
    # access com.old.Foo.VALUE. Only whole-qualified-name occurrences of com.old.Foo must be rewritten.
    old_pkg = tmp_path / "src/main/java/com/old"
    old_pkg.mkdir(parents=True)
    (old_pkg / "Foo.java").write_text("package com.old;\npublic class Foo { public static final int VALUE = 1; }\n", encoding="utf-8")
    (old_pkg / "FooBar.java").write_text("package com.old;\npublic class FooBar {}\n", encoding="utf-8")
    use_pkg = tmp_path / "src/main/java/com/use"
    use_pkg.mkdir(parents=True)
    use_file = use_pkg / "Use.java"
    use_file.write_text(
        "package com.use;\nclass Use {\n    com.old.Foo f;\n    com.old.FooBar g;\n    int v = com.old.Foo.VALUE;\n}\n",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/com/old/Foo.java", "line": 2, "column": 14, "targetPackage": "com.fresh"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    use_relative = "src/main/java/com/use/Use.java"
    source = use_file.read_text(encoding="utf-8")
    rewrites = sorted(
        (edit["startOffset"], edit["endOffset"], edit["replacement"])
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"] == use_relative and edit["replacement"] == "com.fresh.Foo"
    )
    # Exactly two com.old.Foo occurrences qualify: the field type and the member access. FooBar must be untouched.
    foo_field = source.index("com.old.Foo f;")
    foo_member = source.index("com.old.Foo.VALUE")
    assert rewrites == [
        (foo_field, foo_field + len("com.old.Foo"), "com.fresh.Foo"),
        (foo_member, foo_member + len("com.old.Foo"), "com.fresh.Foo"),
    ], rewrites
    # No edit may touch the FooBar occurrence.
    foobar_start = source.index("com.old.FooBar")
    foobar_end = foobar_start + len("com.old.FooBar")
    for edit in text_edits(result["workspaceEdit"]):
        if edit["relativePath"] != use_relative:
            continue
        assert not (edit["startOffset"] < foobar_end and edit["endOffset"] > foobar_start), edit


def test_sidecar_move_inserts_import_for_unqualified_same_package_reference(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo/old"
    src.mkdir(parents=True)
    (src / "Thing.java").write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    # A same-package sibling references Thing unqualified with no import; the move must add an import rather than refuse.
    sibling = src / "Sibling.java"
    sibling.write_text("package demo.old;\nclass Sibling { Thing field; }\n", encoding="utf-8")
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type("src/main/java/demo/old/Thing.java", 2, 14, "demo.newpkg", apply=True)
    finally:
        manager.shutdown()

    # apply succeeding (no rollback) proves the post-move project compiles.
    assert result["accepted"] is True and result["applied"] is True and not result.get("rolledBack"), result
    assert (tmp_path / "src/main/java/demo/newpkg/Thing.java").exists()
    updated_sibling = sibling.read_text(encoding="utf-8")
    assert "import demo.newpkg.Thing;" in updated_sibling
    assert "Thing field" in updated_sibling


def test_sidecar_move_adds_imports_for_former_same_package_siblings(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo/old"
    src.mkdir(parents=True)
    (src / "Helper.java").write_text("package demo.old;\npublic class Helper { public static int v(){ return 1; } }\n", encoding="utf-8")
    # The moved type uses a sibling unqualified; once it leaves the package it needs an import of that sibling.
    moved = src / "Thing.java"
    moved.write_text("package demo.old;\npublic class Thing { int x = Helper.v(); }\n", encoding="utf-8")
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type("src/main/java/demo/old/Thing.java", 2, 14, "demo.newpkg", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True and not result.get("rolledBack"), result
    moved_text = (tmp_path / "src/main/java/demo/newpkg/Thing.java").read_text(encoding="utf-8")
    assert "package demo.newpkg;" in moved_text
    assert "import demo.old.Helper;" in moved_text


def test_sidecar_move_refuses_when_module_info_exports_old_package(sidecar_jar: Path, tmp_path: Path) -> None:
    base = tmp_path / "src/main/java"
    (base / "a").mkdir(parents=True)
    (base / "module-info.java").write_text("module demo { exports a; }\n", encoding="utf-8")
    (base / "a" / "Foo.java").write_text("package a;\npublic class Foo {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/a/Foo.java", "line": 2, "column": 14, "targetPackage": "b"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "module_info_package_exported"


def test_java_move_top_level_type_target_directory_within_source_root(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    old_pkg = tmp_path / "src/main/java/demo/old"
    old_pkg.mkdir(parents=True)
    old_file = old_pkg / "Thing.java"
    old_file.write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type(
            "src/main/java/demo/old/Thing.java", 2, 14, target_directory="src/main/java/demo/newpkg", apply=True
        )
    finally:
        manager.shutdown()

    new_file = tmp_path / "src/main/java/demo/newpkg/Thing.java"
    assert result["accepted"] is True, result
    assert not old_file.exists()
    assert new_file.exists()
    # The destination package is derived from the source root containing the target directory.
    assert "package demo.newpkg;" in new_file.read_text(encoding="utf-8")
    assert result["preview"]["fileOperationCount"] == 1


def test_java_move_top_level_type_target_directory_across_source_roots(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    # Two explicit source roots; the move crosses from the first into a package under the second. Explicit mode keeps
    # discovery hermetic and bypasses build-tool extraction.
    first_root = tmp_path / "main/java/com/old"
    first_root.mkdir(parents=True)
    old_file = first_root / "Thing.java"
    old_file.write_text("package com.old;\npublic class Thing {}\n", encoding="utf-8")
    (tmp_path / "extra/java/com/fresh").mkdir(parents=True)
    manager = JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, build_tool_mode="explicit", source_roots=["main/java", "extra/java"]),
    )
    try:
        result = manager.move_top_level_type("main/java/com/old/Thing.java", 2, 14, target_directory="extra/java/com/fresh", apply=True)
    finally:
        manager.shutdown()

    new_file = tmp_path / "extra/java/com/fresh/Thing.java"
    assert result["accepted"] is True, result
    assert not old_file.exists()
    assert new_file.exists()
    # Package derived by relativizing the target directory against the second source root (extra/java).
    assert "package com.fresh;" in new_file.read_text(encoding="utf-8")


def test_java_move_top_level_type_requires_exactly_one_target(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo/old"
    src.mkdir(parents=True)
    (src / "Thing.java").write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        both = manager.move_top_level_type(
            "src/main/java/demo/old/Thing.java", 2, 14, target_package="demo.newpkg", target_directory="src/main/java/demo/newpkg"
        )
        neither = manager.move_top_level_type("src/main/java/demo/old/Thing.java", 2, 14)
    finally:
        manager.shutdown()

    assert both["accepted"] is False
    assert both["refusal"]["code"] == "ambiguous_move_target"
    assert neither["accepted"] is False
    assert neither["refusal"]["code"] == "ambiguous_move_target"


def test_sidecar_move_semantic_fqn_rewrite_leaves_longer_sibling_untouched(sidecar_jar: Path, tmp_path: Path) -> None:
    # G011 gap 1: FQN/reference rewrites are driven from the compiler's reference data (the MemberSelect AST span of the
    # moved type), not a substring scan. `new com.old.Foo()` and `com.old.Foo.VALUE` must be rewritten while the longer
    # sibling `com.old.FooBar` and the unrelated `com.old.FooBar.OTHER` stay untouched.
    old_pkg = tmp_path / "src/main/java/com/old"
    old_pkg.mkdir(parents=True)
    (old_pkg / "Foo.java").write_text("package com.old;\npublic class Foo { public static final int VALUE = 1; }\n", encoding="utf-8")
    (old_pkg / "FooBar.java").write_text("package com.old;\npublic class FooBar { public static final int OTHER = 2; }\n", encoding="utf-8")
    use_pkg = tmp_path / "src/main/java/com/use"
    use_pkg.mkdir(parents=True)
    use_file = use_pkg / "Use.java"
    use_file.write_text(
        "package com.use;\n"
        "class Use {\n"
        "    Object a = new com.old.Foo();\n"
        "    int b = com.old.Foo.VALUE;\n"
        "    com.old.FooBar c;\n"
        "    int d = com.old.FooBar.OTHER;\n"
        "}\n",
        encoding="utf-8",
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/com/old/Foo.java", "line": 2, "column": 14, "targetPackage": "com.fresh"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    use_relative = "src/main/java/com/use/Use.java"
    source = use_file.read_text(encoding="utf-8")
    rewrites = sorted(
        (edit["startOffset"], edit["endOffset"], edit["replacement"])
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"] == use_relative
    )
    new_foo = source.index("new com.old.Foo()") + len("new ")
    member_foo = source.index("com.old.Foo.VALUE")
    assert rewrites == [
        (new_foo, new_foo + len("com.old.Foo"), "com.fresh.Foo"),
        (member_foo, member_foo + len("com.old.Foo"), "com.fresh.Foo"),
    ], rewrites
    # No edit may overlap either FooBar occurrence.
    for needle in ("com.old.FooBar c;", "com.old.FooBar.OTHER"):
        fb_start = source.index(needle)
        fb_end = fb_start + len("com.old.FooBar")
        for edit in text_edits(result["workspaceEdit"]):
            if edit["relativePath"] != use_relative:
                continue
            assert not (edit["startOffset"] < fb_end and edit["endOffset"] > fb_start), edit


def test_sidecar_move_rewrites_import_and_removes_obsolete_import(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # G011 gap 3: a file that keeps a single-type import of the moved type from a third package must have its import
    # qualifier rewritten; a file that already lives in the NEW package no longer needs the import and must have the
    # whole import line removed (the type is then visible without an import).
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    old_pkg = tmp_path / "src/main/java/demo/old"
    new_pkg = tmp_path / "src/main/java/demo/newpkg"
    other_pkg = tmp_path / "src/main/java/demo/other"
    old_pkg.mkdir(parents=True)
    new_pkg.mkdir(parents=True)
    other_pkg.mkdir(parents=True)
    (old_pkg / "Thing.java").write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    # Already in the destination package: its import becomes obsolete and must be removed.
    in_new = new_pkg / "InNew.java"
    in_new.write_text("package demo.newpkg;\nimport demo.old.Thing;\nclass InNew { Thing field; }\n", encoding="utf-8")
    # In a third package: its import qualifier must be rewritten to the new package.
    other = other_pkg / "Other.java"
    other.write_text("package demo.other;\nimport demo.old.Thing;\nclass Other { Thing field; }\n", encoding="utf-8")
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type("src/main/java/demo/old/Thing.java", 2, 14, "demo.newpkg", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True and not result.get("rolledBack"), result
    updated_other = other.read_text(encoding="utf-8")
    assert "import demo.newpkg.Thing;" in updated_other
    assert "import demo.old.Thing;" not in updated_other
    updated_in_new = in_new.read_text(encoding="utf-8")
    # The obsolete import line is gone entirely (no demo.old/demo.newpkg single-type import survives) but the use stays.
    assert "import demo.old.Thing;" not in updated_in_new
    assert "import demo.newpkg.Thing;" not in updated_in_new
    assert "Thing field;" in updated_in_new


def test_sidecar_move_inserts_import_for_now_cross_package_simple_name(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # G011 gap 3: a former same-package sibling uses the moved type by simple name with no import. Once the type leaves
    # the package, that use becomes cross-package and a new single-type import of the moved type must be inserted.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo/old"
    src.mkdir(parents=True)
    (src / "Thing.java").write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    sibling = src / "Sibling.java"
    sibling.write_text("package demo.old;\nclass Sibling { Thing field; }\n", encoding="utf-8")
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type("src/main/java/demo/old/Thing.java", 2, 14, "demo.newpkg", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True and result["applied"] is True and not result.get("rolledBack"), result
    updated = sibling.read_text(encoding="utf-8")
    assert "import demo.newpkg.Thing;" in updated
    assert "Thing field;" in updated


def test_sidecar_move_refuses_duplicate_type_in_target_package_other_source_root(sidecar_jar: Path, tmp_path: Path) -> None:
    # G011 gap 2: a type with the same simple name already exists in the TARGET package, but in a DIFFERENT source root,
    # so the conventional target file does not exist. The move must still be refused via the project model.
    first_root = tmp_path / "main/java/com/old"
    first_root.mkdir(parents=True)
    (first_root / "Thing.java").write_text("package com.old;\npublic class Thing {}\n", encoding="utf-8")
    # The colliding Thing lives in the target package but under the SECOND source root.
    second_root = tmp_path / "extra/java/com/fresh"
    second_root.mkdir(parents=True)
    (second_root / "Thing.java").write_text("package com.fresh;\npublic class Thing {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(
                project_root=str(tmp_path),
                configuration=json.dumps({"buildToolMode": "explicit", "sourceRoots": ["main/java", "extra/java"]}),
            )
        )
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "main/java/com/old/Thing.java", "line": 2, "column": 14, "targetPackage": "com.fresh"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "target_type_exists", result


def test_sidecar_move_refuses_when_module_info_opens_old_package(sidecar_jar: Path, tmp_path: Path) -> None:
    # G011 gap 4: AST/model-based module-info detection must refuse a move out of an `opens` package (not only
    # `exports`), and must do so via the parsed ModuleTree directives, not a regex over source text.
    base = tmp_path / "src/main/java"
    (base / "a").mkdir(parents=True)
    (base / "module-info.java").write_text("module demo { opens a; }\n", encoding="utf-8")
    (base / "a" / "Foo.java").write_text("package a;\npublic class Foo {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/a/Foo.java", "line": 2, "column": 14, "targetPackage": "b"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "module_info_package_exported", result


def test_sidecar_move_succeeds_when_module_info_does_not_export_old_package(sidecar_jar: Path, tmp_path: Path) -> None:
    # G011 gap 4: the AST-based module check must NOT over-refuse. The module exports a DIFFERENT package (`b`), so a
    # type moving out of the non-exported package `a` is allowed (the regex-free check ignores the unrelated directive,
    # and a string literal containing "exports a;" must not trip detection either).
    base = tmp_path / "src/main/java"
    (base / "a").mkdir(parents=True)
    (base / "b").mkdir(parents=True)
    (base / "module-info.java").write_text("module demo { exports b; }\n", encoding="utf-8")
    (base / "b" / "Keep.java").write_text("package b;\npublic class Keep {}\n", encoding="utf-8")
    (base / "a" / "Foo.java").write_text('package a;\npublic class Foo { String s = "exports a;"; }\n', encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/a/Foo.java", "line": 2, "column": 14, "targetPackage": "c"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result
    # V1 transaction ordering: the moved file's package edit targets its CURRENT (old) path; the rename moves it.
    old_relative = "src/main/java/a/Foo.java"
    package_edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"] == old_relative and edit["replacement"].startswith("package ")
    ]
    assert len(package_edits) == 1 and package_edits[0]["replacement"] == "package c;", package_edits


def test_sidecar_move_refuses_when_module_info_uses_moved_type(sidecar_jar: Path, tmp_path: Path) -> None:
    # Reviewer blocker #2: `uses`/`provides` directives reference TYPES (FQNs), not packages. Moving the type named by a
    # `uses` directive would leave a stale FQN in the descriptor that v1 does not rewrite, so the move is refused.
    base = tmp_path / "src/main/java"
    (base / "a").mkdir(parents=True)
    (base / "module-info.java").write_text("module demo { uses a.Service; }\n", encoding="utf-8")
    (base / "a" / "Service.java").write_text("package a;\npublic interface Service {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/a/Service.java", "line": 2, "column": 18, "targetPackage": "b"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "module_info_type_referenced", result


def test_sidecar_move_refuses_when_module_info_provides_moved_service(sidecar_jar: Path, tmp_path: Path) -> None:
    # Reviewer blocker #2: moving the SERVICE type named in `provides <Service> with <Provider>` is refused, because the
    # service FQN in the descriptor would go stale.
    base = tmp_path / "src/main/java"
    (base / "a").mkdir(parents=True)
    (base / "a" / "impl").mkdir(parents=True)
    (base / "module-info.java").write_text(
        "module demo { provides a.Service with a.impl.Provider; }\n", encoding="utf-8"
    )
    (base / "a" / "Service.java").write_text("package a;\npublic interface Service {}\n", encoding="utf-8")
    (base / "a" / "impl" / "Provider.java").write_text(
        "package a.impl;\npublic class Provider implements a.Service {}\n", encoding="utf-8"
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/a/Service.java", "line": 2, "column": 18, "targetPackage": "b"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "module_info_type_referenced", result


def test_sidecar_move_refuses_when_module_info_provides_moved_provider(sidecar_jar: Path, tmp_path: Path) -> None:
    # Reviewer blocker #2: moving the PROVIDER implementation named in `provides ... with <Provider>` is refused, because
    # the provider FQN in the descriptor would go stale.
    base = tmp_path / "src/main/java"
    (base / "a").mkdir(parents=True)
    (base / "a" / "impl").mkdir(parents=True)
    (base / "module-info.java").write_text(
        "module demo { provides a.Service with a.impl.Provider; }\n", encoding="utf-8"
    )
    (base / "a" / "Service.java").write_text("package a;\npublic interface Service {}\n", encoding="utf-8")
    (base / "a" / "impl" / "Provider.java").write_text(
        "package a.impl;\npublic class Provider implements a.Service {}\n", encoding="utf-8"
    )
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/a/impl/Provider.java", "line": 2, "column": 14, "targetPackage": "b"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "module_info_type_referenced", result


def test_sidecar_move_succeeds_when_module_info_uses_unrelated_type(sidecar_jar: Path, tmp_path: Path) -> None:
    # Reviewer blocker #2 control: the type-reference check must NOT over-refuse. The module `uses`/`provides` an
    # UNRELATED type; moving a different type whose FQN does not match any directive must still succeed. A type merely
    # sharing the package prefix of the moved type must not trip detection.
    base = tmp_path / "src/main/java"
    (base / "a").mkdir(parents=True)
    (base / "a" / "impl").mkdir(parents=True)
    (base / "b").mkdir(parents=True)
    (base / "module-info.java").write_text(
        "module demo { uses a.Service; provides a.Service with a.impl.Provider; }\n", encoding="utf-8"
    )
    (base / "a" / "Service.java").write_text("package a;\npublic interface Service {}\n", encoding="utf-8")
    (base / "a" / "impl" / "Provider.java").write_text(
        "package a.impl;\npublic class Provider implements a.Service {}\n", encoding="utf-8"
    )
    # Foo lives in package `a` (shares the prefix of `a.Service`) but is not named by any directive.
    (base / "a" / "Foo.java").write_text("package a;\npublic class Foo {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/a/Foo.java", "line": 2, "column": 14, "targetPackage": "b"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is True, result


def test_sidecar_move_into_default_package_refuses_unqualified_sibling(sidecar_jar: Path, tmp_path: Path) -> None:
    """M6: moving a type INTO the default package is refused when it references a former same-package sibling.

    Default-package types cannot be imported, so the moved file's unqualified sibling reference cannot be repaired with
    an import; the previous behavior produced an accepted preview that always failed on apply.
    """
    src = tmp_path / "src/main/java/demo/old"
    src.mkdir(parents=True)
    (src / "Sibling.java").write_text("package demo.old; class Sibling {}\n", encoding="utf-8")
    (src / "Thing.java").write_text("package demo.old; class Thing { Sibling s; }\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration="default"))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/demo/old/Thing.java", "line": 1, "column": 25, "targetDirectory": "src/main/java"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "move_to_default_package_breaks_references", result


def _assert_refuses_inbound_default_package_move(result: dict, expected_blocking_file: str) -> None:
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "move_to_default_package_breaks_inbound_references", result
    references = result.get("references") or []
    assert references, result
    assert any(expected_blocking_file in (ref.get("relativePath") or "") for ref in references), references


def test_sidecar_move_into_default_package_refuses_inbound_import(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003 (inbound gate): a named-package file IMPORTING the moved type blocks a move into the default package —
    # default-package types cannot be imported, so the import would become unresolvable.
    src = tmp_path / "src/main/java"
    (src / "demo/old").mkdir(parents=True)
    (src / "demo/use").mkdir(parents=True)
    (src / "demo/old/Thing.java").write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    (src / "demo/use/Use.java").write_text("package demo.use;\nimport demo.old.Thing;\nclass Use { Thing t; }\n", encoding="utf-8")
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/old/Thing.java", "line": 2, "column": 14, "targetPackage": ""},
    )
    _assert_refuses_inbound_default_package_move(result, "Use.java")


def test_sidecar_move_into_default_package_refuses_inbound_static_import(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003 (inbound gate): a named-package file STATICALLY importing a member of the moved type blocks the move — the
    # static import names the type's qualified name, which a default-package type does not have.
    src = tmp_path / "src/main/java"
    (src / "demo/old").mkdir(parents=True)
    (src / "demo/use").mkdir(parents=True)
    (src / "demo/old/Thing.java").write_text(
        "package demo.old;\npublic class Thing { public static final int VALUE = 1; }\n", encoding="utf-8"
    )
    (src / "demo/use/Use.java").write_text(
        "package demo.use;\nimport static demo.old.Thing.VALUE;\nclass Use { int v = VALUE; }\n", encoding="utf-8"
    )
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/old/Thing.java", "line": 2, "column": 14, "targetPackage": ""},
    )
    _assert_refuses_inbound_default_package_move(result, "Use.java")


def test_sidecar_move_into_default_package_refuses_inbound_fqn(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003 (inbound gate): a named-package file referencing the moved type by FULLY QUALIFIED NAME blocks the move —
    # a default-package type has no qualified name expressible from a named package.
    src = tmp_path / "src/main/java"
    (src / "demo/old").mkdir(parents=True)
    (src / "demo/use").mkdir(parents=True)
    (src / "demo/old/Thing.java").write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    (src / "demo/use/Use.java").write_text("package demo.use;\nclass Use { demo.old.Thing t; }\n", encoding="utf-8")
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/old/Thing.java", "line": 2, "column": 14, "targetPackage": ""},
    )
    _assert_refuses_inbound_default_package_move(result, "Use.java")


def test_sidecar_move_into_default_package_refuses_same_package_simple_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003 (inbound gate): a file in the moved type's OLD (named) package referencing it by simple name blocks the
    # move — after the move that file stays in a named package and cannot import the now-default-package type.
    src = tmp_path / "src/main/java"
    (src / "demo/old").mkdir(parents=True)
    (src / "demo/old/Thing.java").write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    (src / "demo/old/Sibling.java").write_text("package demo.old;\nclass Sibling { Thing t; }\n", encoding="utf-8")
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/old/Thing.java", "line": 2, "column": 14, "targetPackage": ""},
    )
    _assert_refuses_inbound_default_package_move(result, "Sibling.java")


def test_sidecar_move_into_default_package_refuses_cross_source_set_reference(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003 (inbound gate): a named-package file in ANOTHER source set (test) referencing the moved main type blocks
    # the move into the default package; the gate must look across all source sets, not just the home one.
    (tmp_path / "settings.gradle.kts").write_text('rootProject.name = "sample"\n', encoding="utf-8")
    (tmp_path / "build.gradle.kts").write_text(
        'plugins { id("java") }\njava { sourceCompatibility = JavaVersion.VERSION_17 }\n', encoding="utf-8"
    )
    main_pkg = tmp_path / "src/main/java/app/core"
    test_pkg = tmp_path / "src/test/java/app"
    main_pkg.mkdir(parents=True)
    test_pkg.mkdir(parents=True)
    (main_pkg / "Widget.java").write_text("package app.core;\npublic class Widget {}\n", encoding="utf-8")
    (test_pkg / "WidgetTest.java").write_text(
        "package app;\nimport app.core.Widget;\nclass WidgetTest { Widget w = new Widget(); }\n", encoding="utf-8"
    )

    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=json.dumps({"offline": True})))
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/app/core/Widget.java", "line": 2, "column": 14, "targetPackage": ""},
        )
    finally:
        client.shutdown()

    _assert_refuses_inbound_default_package_move(result, "WidgetTest.java")


def test_sidecar_move_into_default_package_apply_refuses_without_mutation(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # G003 (negative/no-mutation): an APPLY of an inbound-blocked default-package move must refuse before planning any
    # edit, leaving every source file byte-for-byte untouched.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java"
    (src / "demo/old").mkdir(parents=True)
    (src / "demo/use").mkdir(parents=True)
    (src / "demo/old/Thing.java").write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    (src / "demo/use/Use.java").write_text("package demo.use;\nimport demo.old.Thing;\nclass Use { Thing t; }\n", encoding="utf-8")
    before = {str(path.relative_to(src)): path.read_bytes() for path in sorted(src.rglob("*.java"))}

    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type("src/main/java/demo/old/Thing.java", 2, 14, "", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is False, result
    assert result.get("applied") is not True, result
    assert result["refusal"]["code"] == "move_to_default_package_breaks_inbound_references", result
    after = {str(path.relative_to(src)): path.read_bytes() for path in sorted(src.rglob("*.java"))}
    assert after == before


def test_sidecar_move_into_default_package_allows_default_package_references(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003 (no over-refusal): a move into the default package is LEGAL when every inbound reference comes from a file
    # already in the default package — those files drop their now-obsolete import and use the simple name.
    src = tmp_path / "src/main/java"
    (src / "demo/old").mkdir(parents=True)
    (src / "demo/old/Thing.java").write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    (src / "Use.java").write_text("import demo.old.Thing;\nclass Use { Thing t; }\n", encoding="utf-8")
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/old/Thing.java", "line": 2, "column": 14, "targetPackage": ""},
    )
    assert result.get("accepted") is True, result
    ops = file_ops(result["workspaceEdit"])
    assert any(
        op["kind"] == "rename" and (op.get("newRelativePath") or "").replace("\\", "/") == "src/main/java/Thing.java" for op in ops
    ), ops
    # The default-package referencing file's import of the moved type is removed (a default-package type cannot be
    # imported; the simple name now resolves within the shared default package).
    use_edits = [e for e in text_edits(result["workspaceEdit"]) if e["relativePath"].replace("\\", "/") == "src/main/java/Use.java"]
    assert any(e["replacement"] == "" for e in use_edits), use_edits


def test_sidecar_move_refuses_target_in_generated_source_root(sidecar_jar: Path, tmp_path: Path) -> None:
    # G009: moving a top-level type whose file lives under a generated source root must be refused.
    if shutil.which("gradle") is None:
        pytest.skip("gradle is required for generated-source-root extraction")
    _write_generated_root_project(tmp_path, "package demo; public class Gen {}\n")
    configuration = json.dumps({"offline": True, "allowIncompleteAnalysis": True})
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=configuration))
        col = (tmp_path / "src/generated/java/demo/Gen.java").read_text().index("Gen {") + 1
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/generated/java/demo/Gen.java", "line": 1, "column": col, "targetPackage": "demo.moved"},
        )
    finally:
        client.shutdown()

    assert result["accepted"] is False, result
    assert result["refusal"]["code"] == "non_editable_target", result


def test_sidecar_move_extracts_public_type_leaving_package_private_companion(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # HB-1: a file with a package-private companion is no longer refused — the selected public type is EXTRACTED into a
    # new file in the destination package, while the companion stays behind in the original file.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    old_file = src / "Thing.java"
    old_file.write_text("package demo;\npublic class Thing {}\nclass Helper {}\n", encoding="utf-8")
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type("src/main/java/demo/Thing.java", 2, 14, "demo.newpkg", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True, result
    new_file = tmp_path / "src/main/java/demo/newpkg/Thing.java"
    assert new_file.exists()
    new_text = new_file.read_text(encoding="utf-8")
    assert "package demo.newpkg;" in new_text
    assert "public class Thing {}" in new_text
    assert "Helper" not in new_text
    # The original file remains (it still holds the companion) with only the extracted type removed.
    assert old_file.exists()
    old_text = old_file.read_text(encoding="utf-8")
    assert "class Helper {}" in old_text
    assert "class Thing" not in old_text
    assert "package demo;" in old_text


def test_sidecar_move_refuses_trailing_stray_semicolon(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: a stray top-level ';' after the type is non-type trivia that must keep the move from proceeding.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Thing.java").write_text("package demo;\npublic class Thing {};\n", encoding="utf-8")
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/Thing.java", "line": 2, "column": 14, "targetPackage": "demo.newpkg"},
    )
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "multiple_top_level_declarations", result


def test_sidecar_move_refuses_leading_stray_semicolon(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003: a stray ';' top-level declaration before the type is likewise refused.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Thing.java").write_text("package demo;\n;\npublic class Thing {}\n", encoding="utf-8")
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/Thing.java", "line": 3, "column": 14, "targetPackage": "demo.newpkg"},
    )
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "multiple_top_level_declarations", result


def test_sidecar_move_extracts_one_of_multiple_public_types(sidecar_jar: Path, tmp_path: Path) -> None:
    # HB-1: two public top-level types in one file (parseable only under allow_incomplete_analysis) no longer refuse —
    # the selected type is extracted into a new file (create + removal). Apply-time javac validation would still guard
    # the inherently-malformed remainder, but the planner now produces an extraction rather than refusing outright.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Thing.java").write_text("package demo;\npublic class Thing {}\npublic class Other {}\n", encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=json.dumps({"allowIncompleteAnalysis": True}))
        )
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/demo/Thing.java", "line": 2, "column": 14, "targetPackage": "demo.newpkg"},
        )
    finally:
        client.shutdown()
    assert result.get("accepted") is True, result
    ops = file_ops(result["workspaceEdit"])
    create_ops = [op for op in ops if op["kind"] == "create"]
    assert len(create_ops) == 1, ops
    assert create_ops[0]["relativePath"] == "src/main/java/demo/newpkg/Thing.java", ops
    assert "public class Thing {}" in create_ops[0]["content"]
    assert "Other" not in create_ops[0]["content"]
    # The original file is edited in place (declaration removed), not renamed.
    assert not any(op["kind"] == "rename" for op in ops), ops
    removal = [edit for edit in text_edits(result["workspaceEdit"]) if not edit["replacement"]]
    assert removal, result


def test_sidecar_move_extraction_splits_imports_and_carries_javadoc(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # HB-1: extraction carries the moved type's attached Javadoc and ONLY the imports the moved type uses (import
    # splitting); the sibling-only import stays behind. Because the remaining sibling references the moved type by
    # simple name, the original file gains an import of the moved type's new fully-qualified name.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    old_file = src / "Thing.java"
    old_file.write_text(
        "package demo;\n"
        "import java.util.List;\n"
        "import java.util.Map;\n"
        "/** Doc for Thing. */\n"
        "public class Thing {\n"
        "    List<String> items;\n"
        "}\n"
        "class Helper {\n"
        "    Map<String, String> lookup;\n"
        "    Thing make() { return new Thing(); }\n"
        "}\n",
        encoding="utf-8",
    )
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type("src/main/java/demo/Thing.java", 5, 14, "demo.newpkg", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is True, result
    new_text = (tmp_path / "src/main/java/demo/newpkg/Thing.java").read_text(encoding="utf-8")
    assert "package demo.newpkg;" in new_text
    assert "import java.util.List;" in new_text  # used by Thing -> carried over
    assert "import java.util.Map;" not in new_text  # used only by Helper -> NOT carried over
    assert "/** Doc for Thing. */" in new_text  # attached Javadoc carried over
    assert "class Helper" not in new_text

    old_text = old_file.read_text(encoding="utf-8")
    assert "import demo.newpkg.Thing;" in old_text  # sibling still references the moved type by simple name
    assert "import java.util.Map;" in old_text  # still used by Helper
    assert "class Helper" in old_text
    assert "class Thing" not in old_text


def test_sidecar_move_extraction_rolls_back_when_result_does_not_compile(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # HB-1 rollback: extracting a type that depends on a package-private sibling produces a new file in a different
    # package that cannot access that sibling, so apply-time javac validation fails and the whole edit is rolled back —
    # the new file is not created and the original file is left exactly as it was.
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    old_file = src / "Thing.java"
    original = "package demo;\npublic class Thing {\n    Helper h;\n}\nclass Helper {}\n"
    old_file.write_text(original, encoding="utf-8")
    manager = JavaRefactorManager(
        str(tmp_path), LanguageBackend.LSP, [Language.JAVA], java_refactor_config=JavaRefactorConfig(enabled=True)
    )
    try:
        result = manager.move_top_level_type("src/main/java/demo/Thing.java", 2, 14, "demo.newpkg", apply=True)
    finally:
        manager.shutdown()

    assert result["accepted"] is False, result
    # Rolled back: the extraction target was not created and the original source is untouched.
    assert not (tmp_path / "src/main/java/demo/newpkg/Thing.java").exists()
    assert old_file.read_text(encoding="utf-8") == original


def test_sidecar_move_allows_sole_top_level_type(sidecar_jar: Path, tmp_path: Path) -> None:
    # G003 (no false positive): a file whose only top-level declaration is the moved type is still accepted.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    (src / "Thing.java").write_text("package demo;\npublic class Thing {}\n", encoding="utf-8")
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/Thing.java", "line": 2, "column": 14, "targetPackage": "demo.newpkg"},
    )
    assert result.get("accepted") is True, result
    assert file_ops(result["workspaceEdit"])[0]["kind"] == "rename", result


def _move_import_golden(sidecar_jar: Path, tmp_path: Path, sibling_source: str) -> str:
    """G008 golden harness: move demo.old.Thing -> demo.newpkg; return Sibling.java after applying its import edits.

    Sibling lives in demo.old and references Thing by simple name, so the move must insert an import of the moved
    type. allowIncompleteAnalysis lets the sibling carry arbitrary (even unresolved) imports so the insertion point can
    be exercised for every grouping/ordering case without needing each import to resolve.
    """
    src = tmp_path / "src/main/java/demo/old"
    src.mkdir(parents=True)
    (src / "Thing.java").write_text("package demo.old;\npublic class Thing {}\n", encoding="utf-8")
    (src / "Sibling.java").write_text(sibling_source, encoding="utf-8")
    client = JavaRefactorClient(sidecar_jar)
    client.start()
    try:
        client.initialize(
            JavaRefactorInitializeParams(project_root=str(tmp_path), configuration=json.dumps({"allowIncompleteAnalysis": True}))
        )
        result = client.preview(
            "moveTopLevelType",
            {"relativePath": "src/main/java/demo/old/Thing.java", "line": 2, "column": 14, "targetPackage": "demo.newpkg"},
        )
    finally:
        client.shutdown()
    assert result.get("accepted") is True, result
    edits = [e for e in text_edits(result["workspaceEdit"]) if e["relativePath"] == "src/main/java/demo/old/Sibling.java"]
    updated = sibling_source
    for edit in sorted(edits, key=lambda e: e["startOffset"], reverse=True):
        updated = updated[: edit["startOffset"]] + edit["replacement"] + updated[edit["endOffset"] :]
    return updated


def test_sidecar_move_import_golden_no_imports(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008: a file with no imports gets the import after the package declaration, separated by a blank line.
    updated = _move_import_golden(sidecar_jar, tmp_path, "package demo.old;\nclass Sibling { Thing field; }\n")
    assert updated == "package demo.old;\n\nimport demo.newpkg.Thing;\nclass Sibling { Thing field; }\n", repr(updated)


def test_sidecar_move_import_golden_single_block_new_group(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008: inserting a third-party/project import after an existing java.* block keeps a blank line between groups.
    sibling = (
        "package demo.old;\n"
        "import java.util.List;\n"
        "import java.util.Map;\n"
        "class Sibling { List<String> a; Map<String,String> b; Thing field; }\n"
    )
    updated = _move_import_golden(sidecar_jar, tmp_path, sibling)
    assert "import java.util.Map;\n\nimport demo.newpkg.Thing;\nclass Sibling" in updated, repr(updated)


def test_sidecar_move_import_golden_sorted_within_group(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008: within the same (third-party/project) group the new import is placed in lexicographic order.
    sibling = "package demo.old;\nimport aaa.A;\nimport zzz.Z;\nclass Sibling { Thing field; }\n"
    updated = _move_import_golden(sidecar_jar, tmp_path, sibling)
    assert "import aaa.A;\nimport demo.newpkg.Thing;\nimport zzz.Z;\n" in updated, repr(updated)


def test_sidecar_move_import_golden_static_imports_untouched(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008: a new (non-static) import is placed among the regular imports, never interleaved with static imports.
    sibling = "package demo.old;\nimport static java.lang.Math.max;\nimport zzz.Z;\nclass Sibling { Thing field; }\n"
    updated = _move_import_golden(sidecar_jar, tmp_path, sibling)
    assert "import static java.lang.Math.max;\nimport demo.newpkg.Thing;\nimport zzz.Z;\n" in updated, repr(updated)


def test_sidecar_move_import_golden_wildcard_same_group(sidecar_jar: Path, tmp_path: Path) -> None:
    # G008: a wildcard import is parsed and ordered like any other name in its group.
    sibling = "package demo.old;\nimport demo.aaa.*;\nclass Sibling { Thing field; }\n"
    updated = _move_import_golden(sidecar_jar, tmp_path, sibling)
    assert "import demo.aaa.*;\nimport demo.newpkg.Thing;\n" in updated, repr(updated)


def test_sidecar_move_refuses_classpath_only_binary_target(sidecar_jar: Path, tmp_path: Path) -> None:
    # G007: the centralized target-origin gate refuses moving a target that resolves to a classpath-only binary type
    # (java.lang.String) with no editable source declaration.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    source = 'package demo;\nclass Main {\n    String text = "x";\n}\n'
    (src / "Main.java").write_text(source, encoding="utf-8")
    col = source.split("\n")[2].index("String") + 1
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/Main.java", "line": 3, "column": col, "targetPackage": "other"},
    )
    assert result.get("accepted") is False, result
    assert result["refusal"]["code"] == "non_editable_target", result


def _apply_edits_to(source: str, result: dict, relative_path: str) -> str:
    edits = [e for e in text_edits(result["workspaceEdit"]) if e["relativePath"].replace("\\", "/") == relative_path]
    updated = source
    for edit in sorted(edits, key=lambda e: (e["startOffset"], e["endOffset"]), reverse=True):
        updated = updated[: edit["startOffset"]] + edit["replacement"] + updated[edit["endOffset"] :]
    return updated


def test_sidecar_move_static_import_does_not_suppress_required_normal_import(sidecar_jar: Path, tmp_path: Path) -> None:
    # A static import (`import static old.Thing.VALUE;`) imports MEMBERS, not the type name, so it must not satisfy a
    # remaining simple-name TYPE use: the move must BOTH rewrite the static import's qualifier AND insert the normal
    # single-type import for the simple-name reference.
    src = tmp_path / "src/main/java/demo/old"
    src.mkdir(parents=True)
    (src / "Thing.java").write_text(
        "package demo.old;\npublic class Thing { public static final int VALUE = 1; }\n", encoding="utf-8"
    )
    use_source = (
        "package demo.old;\n"
        "import static demo.old.Thing.VALUE;\n"
        "class UseSite { Thing field; int v = VALUE; }\n"
    )
    (src / "UseSite.java").write_text(use_source, encoding="utf-8")
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/old/Thing.java", "line": 2, "column": 14, "targetPackage": "demo.newpkg"},
    )
    assert result.get("accepted") is True, result
    updated = _apply_edits_to(use_source, result, "src/main/java/demo/old/UseSite.java")
    assert "import static demo.newpkg.Thing.VALUE;" in updated, repr(updated)
    assert "import demo.newpkg.Thing;" in updated, repr(updated)
    assert "demo.old.Thing" not in updated, repr(updated)


def test_sidecar_move_wildcard_plus_static_import_gets_normal_import(sidecar_jar: Path, tmp_path: Path) -> None:
    # A file in a THIRD package that sees the type only through an old-package wildcard import and also carries a
    # static import: the wildcard no longer covers the moved type, so a normal single-type import must be inserted,
    # and the static import's qualifier rewritten.
    old_pkg = tmp_path / "src/main/java/demo/old"
    use_pkg = tmp_path / "src/main/java/demo/use"
    old_pkg.mkdir(parents=True)
    use_pkg.mkdir(parents=True)
    (old_pkg / "Thing.java").write_text(
        "package demo.old;\npublic class Thing { public static final int VALUE = 1; }\n", encoding="utf-8"
    )
    use_source = (
        "package demo.use;\n"
        "import demo.old.*;\n"
        "import static demo.old.Thing.VALUE;\n"
        "class UseSite { Thing field; int v = VALUE; }\n"
    )
    (use_pkg / "UseSite.java").write_text(use_source, encoding="utf-8")
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/old/Thing.java", "line": 2, "column": 14, "targetPackage": "demo.newpkg"},
    )
    assert result.get("accepted") is True, result
    updated = _apply_edits_to(use_source, result, "src/main/java/demo/use/UseSite.java")
    assert "import demo.newpkg.Thing;" in updated, repr(updated)
    assert "import static demo.newpkg.Thing.VALUE;" in updated, repr(updated)


def test_sidecar_move_file_in_target_package_gets_no_redundant_import(sidecar_jar: Path, tmp_path: Path) -> None:
    # A referencing file ALREADY in the move's target package sees the moved type by package visibility: no normal
    # single-type import may be inserted for its simple-name use, while its static import is still rewritten to the
    # new package.
    old_pkg = tmp_path / "src/main/java/demo/old"
    new_pkg = tmp_path / "src/main/java/demo/newpkg"
    old_pkg.mkdir(parents=True)
    new_pkg.mkdir(parents=True)
    (old_pkg / "Thing.java").write_text(
        "package demo.old;\npublic class Thing { public static final int VALUE = 1; }\n", encoding="utf-8"
    )
    use_source = (
        "package demo.newpkg;\n"
        "import demo.old.*;\n"
        "import static demo.old.Thing.VALUE;\n"
        "class UseSite { Thing field; int v = VALUE; }\n"
    )
    (new_pkg / "UseSite.java").write_text(use_source, encoding="utf-8")
    result = _preview_op(
        sidecar_jar,
        tmp_path,
        "moveTopLevelType",
        {"relativePath": "src/main/java/demo/old/Thing.java", "line": 2, "column": 14, "targetPackage": "demo.newpkg"},
    )
    assert result.get("accepted") is True, result
    updated = _apply_edits_to(use_source, result, "src/main/java/demo/newpkg/UseSite.java")
    assert "import demo.newpkg.Thing;" not in updated, repr(updated)
    assert "import static demo.newpkg.Thing.VALUE;" in updated, repr(updated)
    assert "Thing field;" in updated, repr(updated)
