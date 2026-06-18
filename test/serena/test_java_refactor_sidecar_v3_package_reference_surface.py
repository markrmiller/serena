"""Sidecar-backed tests for the FULL package-reference rewrite surface (refactor-feature-plan-V3.md §5.4, finding F3).

Renaming or moving a package must rewrite every kind of *real* reference into the moved tree while never touching an
occurrence of the package name that is not a reference (a string literal or a plain comment). The earlier
implementation only rewrote single-type/static imports and fully-qualified code references, and ``movePackage`` did so
with a raw text scan (no parse-tree mask) that could corrupt strings/comments. These tests drive the LIVE sidecar to
prove the consolidated, parse-tree-driven rewriter now handles:

* on-demand **wildcard imports** (``import old.pkg.*;``) — the owner-detection accepts a ``.*`` tail, and
* **Javadoc references** (``{@link old.pkg.Type}`` / ``@see old.pkg.Type``) — collected from the javac DocTree, while
* **string literals** and **comments** that merely contain the package name are left untouched, and
* ``movePackage`` has the SAME parse-tree safety as ``renamePackage`` (no string/comment corruption) — F3 parity.
"""

from pathlib import Path

from test.serena._java_refactor_sidecar_helpers import (
    _apply_edits_to_text,
    _preview_op,
    sidecar_jar,
    text_edits,
)


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


# A consumer in a DIFFERENT package that references the moved package every way that matters: an on-demand wildcard
# import, two Javadoc references, plus a string literal and a comment that contain the package name but are NOT
# references (those must survive verbatim).
_CONSUMER = (
    "package com.acme.client;\n"
    "\n"
    "import com.acme.app.api.*;\n"
    "\n"
    "/**\n"
    " * Builds things via {@link com.acme.app.api.Api}.\n"
    " *\n"
    " * @see com.acme.app.api.Widget\n"
    " */\n"
    "public class Consumer {\n"
    "    // reflective name com.acme.app.api lives in this comment and must not move\n"
    '    private final String fqcn = "com.acme.app.api.Api";\n'
    "    Api makeApi() { return new Api(); }\n"
    "    Widget makeWidget() { return new Widget(); }\n"
    "    String name() { return fqcn; }\n"
    "}\n"
)


def _write_reference_surface_project(project_root: Path) -> None:
    _write(
        project_root,
        "src/main/java/com/acme/app/api/Api.java",
        "package com.acme.app.api;\npublic class Api {\n    public int value() { return 1; }\n}\n",
    )
    _write(
        project_root,
        "src/main/java/com/acme/app/api/Widget.java",
        "package com.acme.app.api;\npublic class Widget {\n    public int size() { return 2; }\n}\n",
    )
    _write(project_root, "src/main/java/com/acme/client/Consumer.java", _CONSUMER)


def _assert_consumer_rewritten(result: dict, project_root: Path) -> None:
    assert result.get("accepted") is True, result
    assert result.get("diagnosticDeltaValidated") is True, result

    consumer_rel = "src/main/java/com/acme/client/Consumer.java"
    edits = [
        edit
        for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == consumer_rel
    ]
    # The Javadoc references go through the dedicated DocTree path (kind JAVADOC_REFERENCE), proving the doc-comment scan
    # fired rather than the code-name mask incidentally covering them.
    assert any(edit["kind"] == "JAVADOC_REFERENCE" for edit in edits), edits
    # The wildcard import is rewritten through the code-name path (kind PACKAGE_REFERENCE).
    assert any(edit["kind"] == "PACKAGE_REFERENCE" for edit in edits), edits

    rewritten = _apply_edits_to_text(_CONSUMER, edits)
    # (a) on-demand wildcard import rewritten to the new package.
    assert "import com.acme.app.core.*;" in rewritten, rewritten
    # (b) both Javadoc references rewritten (the #-less type tail is preserved).
    assert "{@link com.acme.app.core.Api}" in rewritten, rewritten
    assert "@see com.acme.app.core.Widget" in rewritten, rewritten
    # (c) the string literal naming the old package is NOT rewritten.
    assert '"com.acme.app.api.Api"' in rewritten, rewritten
    # (d) the comment naming the old package is NOT rewritten.
    assert "reflective name com.acme.app.api lives in this comment" in rewritten, rewritten
    # And the stale new-package name never leaks into the string/comment (no over-rewrite).
    assert rewritten.count("com.acme.app.api") == 2, rewritten  # exactly the string literal + the comment


def test_sidecar_rename_package_rewrites_wildcard_import_and_javadoc(sidecar_jar: Path, tmp_path: Path) -> None:
    # F3 for renamePackage: com.acme.app.api -> com.acme.app.core rewrites the consumer's wildcard import and both
    # Javadoc references, while the string literal and comment that merely contain the package name are left verbatim.
    project_root = tmp_path / "rename_reference_surface"
    _write_reference_surface_project(project_root)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "renamePackage",
        {"oldPackage": "com.acme.app.api", "newPackage": "com.acme.app.core"},
    )

    _assert_consumer_rewritten(result, project_root)


def test_sidecar_move_package_rewrites_wildcard_import_and_javadoc(sidecar_jar: Path, tmp_path: Path) -> None:
    # F3 parity for movePackage: the SAME consumer fixture moved com.acme.app.api -> com.acme.app.core must rewrite the
    # wildcard import and Javadoc references AND leave the string/comment untouched. The old move planner used a raw
    # text scan with no parse-tree mask, so it would have corrupted the string literal; this proves it now shares the
    # fail-closed, AST-driven rewriter with rename.
    project_root = tmp_path / "move_reference_surface"
    _write_reference_surface_project(project_root)

    result = _preview_op(
        sidecar_jar,
        project_root,
        "movePackage",
        {"sourcePackage": "com.acme.app.api", "targetPackage": "com.acme.app.core"},
    )

    _assert_consumer_rewritten(result, project_root)
