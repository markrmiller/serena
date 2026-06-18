"""Live-sidecar coverage for AST-driven package reference rewriting (refactor-feature-plan-V3.md §5; blocker B3).

``renamePackage`` previously located references with a raw ``source.indexOf(oldPackage, ...)`` scan over the WHOLE file
text, so a textual occurrence of the package name inside a string/char literal, a line/block comment, or a Javadoc
comment was rewritten too — corrupting the file while the operation advertised itself as javac-validated. Detection is
now driven by the javac parse tree (an offset not covered by a real identifier/member-select node is not a reference),
so those non-code occurrences are left untouched while real imports and fully-qualified references ARE rewritten.

This boots the real sidecar against a project where the SAME fully-qualified package name appears both as real code
(an import and a qualified static reference) and inside a string literal, a line comment, and a Javadoc comment, and
proves the edit set covers exactly the code occurrences and never the string/comment/Javadoc ones.
"""

from __future__ import annotations

from pathlib import Path

from test.serena._java_refactor_sidecar_helpers import (
    _preview_op,
    file_ops,
    text_edits,
)

pytest_plugins = ("test.serena._java_refactor_sidecar_helpers",)

_OLD = "com.acme.app"
_NEW = "com.acme.core"


def _write(root: Path, rel: str, content: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _occurrence(source: str, marker: str) -> int:
    """The offset of ``com.acme.app`` within the substring that begins at ``marker`` (an unambiguous anchor)."""
    anchor = source.index(marker)
    return source.index(_OLD, anchor)


def test_rename_package_rewrites_code_refs_but_not_strings_or_comments(sidecar_jar: Path, tmp_path: Path) -> None:
    project_root = tmp_path / "ast_safety"
    _write(
        project_root,
        "src/main/java/com/acme/app/Service.java",
        "package com.acme.app;\n"
        "public class Service {\n"
        "    public static final String NAME = \"svc\";\n"
        "}\n",
    )
    # Client references com.acme.app.Service as real code in TWO places (an import and a fully-qualified static read),
    # and mentions the SAME FQN inside a Javadoc comment, a line comment, and a string literal — none of which may be
    # rewritten. Every non-code occurrence is a full ``com.acme.app.Service`` so the OLD raw-text scan WOULD have
    # rewritten it (proving the AST gate, not an unrelated boundary check, is what protects it).
    client_source = (
        "package com.acme.client;\n"
        "\n"
        "import com.acme.app.Service;\n"
        "\n"
        "/** See com.acme.app.Service for the canonical name. */\n"
        "public class Client {\n"
        "    // legacy fqn was com.acme.app.Service before the move\n"
        "    private final String doc = \"refer to com.acme.app.Service\";\n"
        "    private final Service service = new Service();\n"
        "    private final String name = com.acme.app.Service.NAME;\n"
        "}\n"
    )
    _write(project_root, "src/main/java/com/acme/client/Client.java", client_source)

    result = _preview_op(sidecar_jar, project_root, "renamePackage", {"oldPackage": _OLD, "newPackage": _NEW})

    assert result.get("accepted") is True, result
    # The after-state was javac-validated (the rewrite compiles), proving the code refs were rewritten correctly.
    assert result.get("diagnosticDeltaValidated") is True, result

    # Service.java still moves and its package decl is rewritten (the rename itself is unaffected by the AST gate).
    renames = [op for op in file_ops(result["workspaceEdit"]) if op["kind"] == "rename"]
    assert any(
        op["relativePath"].replace("\\", "/") == "src/main/java/com/acme/app/Service.java" for op in renames
    ), renames

    client_relative = "src/main/java/com/acme/client/Client.java"
    client_edits = [
        edit for edit in text_edits(result["workspaceEdit"])
        if edit["relativePath"].replace("\\", "/") == client_relative
    ]
    edit_offsets = {edit["startOffset"] for edit in client_edits}

    # Every Client edit rewrites the old package qualifier to the new one and nothing else.
    assert client_edits, result
    assert all(edit["replacement"] == _NEW for edit in client_edits), client_edits

    # The two REAL code references are rewritten: the import qualifier and the fully-qualified static read.
    import_offset = _occurrence(client_source, "import com.acme.app.Service;")
    fqn_offset = _occurrence(client_source, "name = com.acme.app.Service.NAME")
    assert import_offset in edit_offsets, (import_offset, client_edits)
    assert fqn_offset in edit_offsets, (fqn_offset, client_edits)

    # The Javadoc, line-comment, and string-literal occurrences are NEVER rewritten.
    javadoc_offset = _occurrence(client_source, "/** See com.acme.app.Service")
    comment_offset = _occurrence(client_source, "// legacy fqn was com.acme.app.Service")
    string_offset = _occurrence(client_source, "\"refer to com.acme.app.Service\"")
    for forbidden in (javadoc_offset, comment_offset, string_offset):
        assert forbidden not in edit_offsets, (forbidden, client_edits)

    # Exactly the two code references — no extra edits leaked in from the non-code occurrences.
    assert edit_offsets == {import_offset, fqn_offset}, client_edits
