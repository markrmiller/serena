"""G002 + G003: preview runs the same in-memory staging/safety pipeline as apply and reports real preview validation.

G003 pins that a preview refuses any edit that cannot be staged EXACTLY (out-of-range offsets, missing files,
rename+edit conflicts, UTF-16 boundary errors, invalid file-operation sequencing) — the same safety checks apply runs —
without writing to disk. G002 pins that ``validate_after_preview`` runs the staged in-memory javac validation and reports
the outcome distinctly under ``previewValidation`` (never as apply-time ``preValidation``/``postValidation``).
"""

from pathlib import Path

import pytest

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.manager import JavaRefactorManager
from serena.java_refactor.workspace_edit import sha256_bytes
from solidlsp.ls_config import Language
from test.serena._java_refactor_sidecar_helpers import (  # noqa: F401
    _crafted_apply,
    _utf16_offset,
    _write_demo_main,
    sidecar_jar,
)


def _manager(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, sidecar_jar: Path, **config: bool) -> JavaRefactorManager:
    monkeypatch.setenv(JavaRefactorManager.ENV_JAR, str(sidecar_jar.resolve()))
    return JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(enabled=True, **config),
    )


def _stub_preview(client, workspace_edit: dict) -> None:
    """Forces the sidecar client's preview to return a crafted workspace edit (the real planner is bypassed)."""
    client.preview = lambda operation, params: {"accepted": True, "workspaceEdit": workspace_edit}  # type: ignore[method-assign]


def _text_edit_payload(project_root: Path, relative_path: str, start: int, end: int, replacement: str) -> dict:
    # The V1 wire contract requires an oldSha256 per change group (parsing fails closed without one). A target that
    # does not exist on disk gets a placeholder hash so the payload still parses and the STAGING-level check refuses.
    target = project_root / relative_path
    old_hash = sha256_bytes(target.read_bytes()) if target.is_file() else "0" * 64
    return {
        "changes": [
            {
                "path": relative_path,
                "oldSha256": old_hash,
                "edits": [{"startOffset": start, "endOffset": end, "newText": replacement, "kind": "REPLACE"}],
            }
        ],
        "fileOperations": [],
        "warnings": [],
        "preconditions": [],
        "stats": {"editCount": 1, "fileOperationCount": 0},
    }


# --- G002: real preview validation -----------------------------------------------------------------------------------


def test_preview_validation_reports_ready_for_compiling_edit(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    source = _write_demo_main(tmp_path)
    original = source.read_text(encoding="utf-8")
    manager = _manager(tmp_path, monkeypatch, sidecar_jar)
    try:
        client = manager._get_or_start_client(refresh=False)
        client.preview = _crafted_apply(client, "package demo;\nclass Main {\n    int renamed = 1;\n}\n", source)  # type: ignore[method-assign]
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=False)
    finally:
        manager.shutdown()

    assert result["accepted"] is True
    assert result["applied"] is False
    # G002: preview validation runs and is reported under its OWN key, never as apply-time validation.
    assert result["previewValidation"]["ready"] is True, result["previewValidation"]
    assert result["previewValidation"]["errors"] == []
    assert "preValidation" not in result and "postValidation" not in result
    # Preview writes nothing: the file is byte-identical to its pre-preview state.
    assert source.read_text(encoding="utf-8") == original


def test_preview_validation_reports_errors_without_writing(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    source = _write_demo_main(tmp_path)
    original = source.read_text(encoding="utf-8")
    manager = _manager(tmp_path, monkeypatch, sidecar_jar)
    try:
        client = manager._get_or_start_client(refresh=False)
        client.preview = _crafted_apply(client, "package demo;\nclass Main { this is not java }\n", source)  # type: ignore[method-assign]
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=False)
    finally:
        manager.shutdown()

    # The preview itself is accepted (it is non-mutating), but its validation reports the staged edit will not compile.
    assert result["applied"] is False
    assert result["previewValidation"]["ready"] is False
    assert result["previewValidation"]["errors"]
    # Still nothing written to disk.
    assert source.read_text(encoding="utf-8") == original


def test_preview_validation_skipped_when_disabled(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    source = _write_demo_main(tmp_path)
    manager = _manager(tmp_path, monkeypatch, sidecar_jar, validate_after_preview=False)
    try:
        client = manager._get_or_start_client(refresh=False)
        client.preview = _crafted_apply(client, "package demo;\nclass Main { this is not java }\n", source)  # type: ignore[method-assign]
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=False)
    finally:
        manager.shutdown()

    assert result["accepted"] is True
    assert "previewValidation" not in result


# --- G003: preview refuses edits that cannot be staged exactly -------------------------------------------------------


def test_preview_refuses_out_of_range_offset(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    source = _write_demo_main(tmp_path)
    original = source.read_text(encoding="utf-8")
    manager = _manager(tmp_path, monkeypatch, sidecar_jar)
    try:
        client = manager._get_or_start_client(refresh=False)
        _stub_preview(client, _text_edit_payload(tmp_path, "src/main/java/demo/Main.java", 0, 999999, "x"))
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=False)
    finally:
        manager.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "preview_unsafe_edit"
    assert source.read_text(encoding="utf-8") == original


def test_preview_refuses_missing_text_edit_file(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _write_demo_main(tmp_path)
    manager = _manager(tmp_path, monkeypatch, sidecar_jar)
    try:
        client = manager._get_or_start_client(refresh=False)
        _stub_preview(client, _text_edit_payload(tmp_path, "src/main/java/demo/DoesNotExist.java", 0, 0, "x"))
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=False)
    finally:
        manager.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "preview_unsafe_edit"


def test_preview_accepts_rename_plus_edit_on_source_path_writes_nothing(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # V1 transaction ordering: a top-level type rename edits the declaration file in place under its CURRENT (old) path
    # and the rename file operation moves the already-edited content. So a text edit on a rename SOURCE path is the
    # normal, valid shape — preview must accept it and stage it without writing anything to disk.
    source = _write_demo_main(tmp_path)
    original = source.read_text(encoding="utf-8")
    manager = _manager(tmp_path, monkeypatch, sidecar_jar)
    workspace_edit = {
        "changes": [
            {
                "path": "src/main/java/demo/Main.java",
                "oldSha256": sha256_bytes(source.read_bytes()),
                "edits": [{"startOffset": 0, "endOffset": 0, "newText": "x", "kind": "REPLACE"}],
            }
        ],
        "fileOperations": [
            {
                "kind": "rename",
                "oldPath": "src/main/java/demo/Main.java",
                "newPath": "src/main/java/demo/Renamed.java",
                "oldSha256": sha256_bytes(source.read_bytes()),
            }
        ],
        "warnings": [],
        "preconditions": [],
        "stats": {"editCount": 1, "fileOperationCount": 1},
    }
    try:
        client = manager._get_or_start_client(refresh=False)
        _stub_preview(client, workspace_edit)
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=False)
    finally:
        manager.shutdown()

    assert result["accepted"] is True, result
    # Preview writes nothing: the original file is untouched and the rename target does not exist on disk.
    assert source.read_text(encoding="utf-8") == original
    assert not (tmp_path / "src/main/java/demo/Renamed.java").exists()


def test_preview_refuses_utf16_boundary_overflow(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # A file containing a non-BMP (supplementary-plane) character: its UTF-16 length exceeds its code-point length, and an
    # edit whose endOffset overruns the UTF-16 unit length must be refused in UTF-16 space, not silently corrupt the file.
    src = tmp_path / "src/main/java/demo"
    src.mkdir(parents=True)
    text = 'package demo;\nclass Main {\n    String s = "\U0001F600x";\n}\n'  # emoji => 2 UTF-16 units
    source = src / "Main.java"
    source.write_text(text, encoding="utf-8")
    original = source.read_text(encoding="utf-8")
    utf16_len = len(text.encode("utf-16-le")) // 2
    manager = _manager(tmp_path, monkeypatch, sidecar_jar)
    try:
        client = manager._get_or_start_client(refresh=False)
        _stub_preview(client, _text_edit_payload(tmp_path, "src/main/java/demo/Main.java", 0, utf16_len + 5, "x"))
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=False)
    finally:
        manager.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "preview_unsafe_edit"
    assert source.read_text(encoding="utf-8") == original


def test_preview_refuses_create_over_existing_file(sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # Invalid file-operation sequencing: a create whose target already exists must be refused at staging time.
    source = _write_demo_main(tmp_path)
    manager = _manager(tmp_path, monkeypatch, sidecar_jar)
    workspace_edit = {
        "changes": [],
        "fileOperations": [
            {"kind": "create", "path": "src/main/java/demo/Main.java", "content": "package demo; class Dup {}\n"}
        ],
        "warnings": [],
        "preconditions": [],
        "stats": {"editCount": 0, "fileOperationCount": 1},
    }
    try:
        client = manager._get_or_start_client(refresh=False)
        _stub_preview(client, workspace_edit)
        result = manager.semantic_rename("src/main/java/demo/Main.java", 3, 9, "renamed", apply=False)
    finally:
        manager.shutdown()

    assert result["accepted"] is False
    assert result["refusal"]["code"] == "preview_unsafe_edit"
    # The existing file content is untouched (create did not overwrite it).
    assert source.read_text(encoding="utf-8").startswith("package demo;\nclass Main")

# --- V1 incomplete-analysis contract, end to end through the manager -------------------------------------------------


def test_incomplete_analysis_default_config_preview_allowed_apply_refused(
    sidecar_jar: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Plan contract (§Incomplete project behavior), default configuration: a project with unresolved compiler
    # diagnostics still gets a warning-only PREVIEW, while APPLY is refused with a structured opt-in hint. Nothing is
    # ever written.
    (tmp_path / "Broken.java").write_text("import missing.Type; class Broken { Type value; }\n", encoding="utf-8")
    app = "class App { void run() { int amount = 1; System.out.println(amount); } }"
    source = tmp_path / "App.java"
    source.write_text(app + "\n", encoding="utf-8")
    column = app.index("amount = 1") + 1
    manager = _manager(tmp_path, monkeypatch, sidecar_jar)
    try:
        preview = manager.semantic_rename("App.java", 1, column, "total", apply=False)
        applied = manager.semantic_rename("App.java", 1, column, "total", apply=True)
    finally:
        manager.shutdown()

    assert preview["accepted"] is True, preview
    assert preview["applied"] is False
    assert any("incomplete analysis" in warning.lower() for warning in preview["preview"]["warnings"]), preview["preview"]

    assert applied["accepted"] is False, applied
    assert applied["refusal"]["code"] == "incomplete_analysis_apply_refused"
    assert "allow_incomplete_analysis" in applied["refusal"]["message"]
    # Nothing was written by either call.
    assert source.read_text(encoding="utf-8") == app + "\n"
