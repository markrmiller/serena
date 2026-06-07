"""G007: real JDTLS post-edit integration — a sidecar refactor's edits stay coherent with the Java language server.

The design keeps JDTLS as the companion for navigation/diagnostics/symbols while the compiler-backed sidecar owns
refactor planning + application. This test proves the seam end to end with a REAL JDTLS session (not stubs): start
JDTLS on a project, apply a sidecar semantic rename to disk, then confirm JDTLS document symbols, diagnostics, and
cross-file references reflect the renamed code once the changed files are re-opened (Serena's didOpen-based change
notification). It does not mutate any committed fixture repo — the project is created under a temp directory.
"""

import pytest

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.manager import JavaRefactorManager
from solidlsp.ls_config import Language
from test.conftest import find_identifier_position, language_tests_enabled, start_ls_context
from test.serena._java_refactor_sidecar_helpers import sidecar_jar  # noqa: F401

pytestmark = [pytest.mark.java, pytest.mark.skipif(not language_tests_enabled(Language.JAVA), reason="Java tests disabled")]

_UTILS = "src/main/java/demo/Utils.java"
_MAIN = "src/main/java/demo/Main.java"


def _write_project(root) -> None:
    (root / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>demo</groupId><artifactId>jdtls-integration</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release>"
        "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>",
        encoding="utf-8",
    )
    pkg = root / "src/main/java/demo"
    pkg.mkdir(parents=True)
    (pkg / "Utils.java").write_text(
        "package demo;\n\npublic class Utils {\n    public static void printHello() {\n"
        '        System.out.println("Hello");\n    }\n}\n',
        encoding="utf-8",
    )
    (pkg / "Main.java").write_text(
        "package demo;\n\npublic class Main {\n    public static void main(String[] args) {\n"
        "        Utils.printHello();\n    }\n}\n",
        encoding="utf-8",
    )


def _document_symbol_names(language_server, relative_path) -> set[str]:
    with language_server.open_file(relative_path):  # didOpen pushes current disk content to JDTLS
        symbols = language_server.request_document_symbols(relative_path).get_all_symbols_and_roots()
    return _collect_symbol_names(symbols)


def _collect_symbol_names(symbol_groups) -> set[str]:
    names: set[str] = set()
    for group in symbol_groups:
        for symbol in group:
            if isinstance(symbol, dict) and symbol.get("name"):
                names.add(symbol["name"])
    return names


def test_jdtls_stays_coherent_after_sidecar_rename(sidecar_jar, tmp_path, monkeypatch) -> None:  # noqa: F811
    project = tmp_path / "proj"
    project.mkdir()
    _write_project(project)
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))

    with start_ls_context(Language.JAVA, repo_path=str(project)) as language_server:
        # --- Baseline: JDTLS sees the original declaration and the project compiles cleanly. ---
        baseline_symbols = _document_symbol_names(language_server, _UTILS)
        assert "printHello" in baseline_symbols, baseline_symbols
        assert language_server.request_text_document_diagnostics(_UTILS, min_severity=1) == []
        assert language_server.request_text_document_diagnostics(_MAIN, min_severity=1) == []

        # --- Apply a real sidecar semantic rename to disk (explicit source roots => no Maven extraction needed). ---
        pos = find_identifier_position(project / _UTILS, "printHello")
        assert pos is not None
        manager = JavaRefactorManager(
            str(project),
            LanguageBackend.LSP,
            [Language.JAVA],
            java_refactor_config=JavaRefactorConfig(
                enabled=True, build_tool_mode="explicit", source_roots=["src/main/java"]
            ),
        )
        try:
            result = manager.semantic_rename(_UTILS, pos[0] + 1, pos[1] + 1, "printGreeting", apply=True)
        finally:
            manager.shutdown()

        assert result["accepted"] is True, result
        assert result["applied"] is True, result
        # The sidecar rewrote both the declaration and the cross-file call site on disk.
        assert "printGreeting" in (project / _UTILS).read_text(encoding="utf-8")
        assert "Utils.printGreeting();" in (project / _MAIN).read_text(encoding="utf-8")

        # --- Post-edit: JDTLS reflects the renamed declaration once the changed files are re-opened. ---
        renamed_symbols = _document_symbol_names(language_server, _UTILS)
        assert "printGreeting" in renamed_symbols, renamed_symbols
        assert "printHello" not in renamed_symbols, renamed_symbols

        # Diagnostics/caches stay coherent: the renamed project still compiles per JDTLS (no error diagnostics).
        with language_server.open_file(_MAIN):
            assert language_server.request_text_document_diagnostics(_MAIN, min_severity=1) == []
        assert language_server.request_text_document_diagnostics(_UTILS, min_severity=1) == []

        # Cross-file symbol operations stay coherent: references of the renamed method resolve to the updated call site.
        new_pos = find_identifier_position(project / _UTILS, "printGreeting")
        assert new_pos is not None
        with language_server.open_file(_MAIN):
            references = language_server.request_references(_UTILS, new_pos[0], new_pos[1])
        assert any("Main.java" in reference.get("relativePath", "") for reference in references), references


def test_jdtls_open_document_resynced_after_sidecar_rename(sidecar_jar, tmp_path, monkeypatch) -> None:  # noqa: F811
    """A file OPEN in JDTLS across a sidecar disk edit is re-synced via notify_open_file_changed_on_disk.

    The sidecar writes its rename directly to disk, OUTSIDE the language-server editor path. While a document is held
    open in JDTLS (Serena keeps it in ``open_file_buffers`` with a live ``didOpen``), JDTLS keeps its stale in-memory
    copy because no ``didChange``/``didClose`` was sent — so its document symbols still report the OLD name. This pins
    the coherence fix: without re-opening or closing the held-open document, ``notify_open_file_changed_on_disk`` (the
    mechanism the apply tool drives over every touched file) pushes the fresh disk content and JDTLS reflects the rename.

    Pre-fix (no didChange after the disk edit) the post-apply assertion that JDTLS reports the new name fails, because
    the held-open document still resolves against JDTLS's stale copy.
    """
    project = tmp_path / "proj"
    project.mkdir()
    _write_project(project)
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))

    with start_ls_context(Language.JAVA, repo_path=str(project)) as language_server:
        # Hold the file OPEN in JDTLS for the whole refactor: this is the stale-copy scenario the fix addresses.
        with language_server.open_file(_UTILS):
            baseline = _collect_symbol_names(language_server.request_document_symbols(_UTILS).get_all_symbols_and_roots())
            assert "printHello" in baseline, baseline

            # Apply a real sidecar semantic rename to disk while the document stays open in JDTLS.
            pos = find_identifier_position(project / _UTILS, "printHello")
            assert pos is not None
            manager = JavaRefactorManager(
                str(project),
                LanguageBackend.LSP,
                [Language.JAVA],
                java_refactor_config=JavaRefactorConfig(
                    enabled=True, build_tool_mode="explicit", source_roots=["src/main/java"]
                ),
            )
            try:
                result = manager.semantic_rename(_UTILS, pos[0] + 1, pos[1] + 1, "printGreeting", apply=True)
            finally:
                manager.shutdown()
            assert result["applied"] is True, result
            assert "printGreeting" in (project / _UTILS).read_text(encoding="utf-8")

            # Re-sync the still-open document with disk (what the apply tool does for every touched file). This must
            # invalidate any cached symbols and push fresh content to JDTLS so the held-open document reports the rename.
            resynced = language_server.notify_open_file_changed_on_disk(_UTILS)
            assert resynced is True, "expected the held-open document to be re-synced with disk"

            renamed = _collect_symbol_names(language_server.request_document_symbols(_UTILS).get_all_symbols_and_roots())
            assert "printGreeting" in renamed, renamed
            assert "printHello" not in renamed, renamed

    # A file that is not open in the language server is a no-op (nothing to re-sync), reported as False.
    with start_ls_context(Language.JAVA, repo_path=str(project)) as language_server:
        assert language_server.notify_open_file_changed_on_disk(_UTILS) is False
