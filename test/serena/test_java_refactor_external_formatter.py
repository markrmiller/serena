"""Tests for the V2 external-formatter contract (design §19; review hard blockers 4/12 and G003).

The external formatter runs only on APPLY, after the refactor edit is committed to disk but BEFORE the javac
post-validation pass, so its output is part of the validated transaction: the unit ``_run_external_formatter`` itself
only runs the formatter and records what it changed (it never rolls back on its own), while the surrounding apply
pipeline re-runs javac over the formatted state and rolls back the whole snapshot if the formatter introduced a
compiler error. The first group of tests drives ``_run_external_formatter`` directly against a real committed
``StagedEdit`` with ``subprocess.run`` stubbed; the G003 group drives the full apply pipeline with a fake sidecar to
prove the formatted output is javac-validated (or rolled back) before commit. All are hermetic and fast.
"""

import subprocess
from pathlib import Path
from types import SimpleNamespace

import pytest

from serena.config.serena_config import (
    JavaRefactorConfig,
    JavaRefactorV2Config,
    LanguageBackend,
    V2FormattingConfig,
)
from serena.java_refactor import manager as manager_module
from serena.java_refactor.manager import JavaRefactorManager
from serena.java_refactor.workspace_edit import (
    RefactorFileOperation,
    RefactorTextEdit,
    RefactorWorkspaceEdit,
    StagedEdit,
    TransactionalWorkspaceEditApplier,
    sha256_bytes,
)
from solidlsp.ls_config import Language


def _make_manager(tmp_path: Path, formatting: V2FormattingConfig) -> JavaRefactorManager:
    config = JavaRefactorConfig(v2=JavaRefactorV2Config(formatting=formatting))
    from serena.config.serena_config import LanguageBackend

    return JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.JETBRAINS,
        [Language.JAVA],
        java_refactor_config=config,
    )


def _commit_staged_edit(tmp_path: Path) -> tuple[TransactionalWorkspaceEditApplier, StagedEdit]:
    """Builds a real edit (one in-place change + one created file), commits it to disk, returns applier+staged."""
    existing = tmp_path / "Existing.java"
    existing.write_text("class Existing{}\n", encoding="utf-8")
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit("Existing.java", 0, 5, "klass", old_hash=sha256_bytes(existing.read_bytes()))],
        file_operations=[RefactorFileOperation(kind="create", relative_path="Created.java", content="class Created{}\n")],
    )
    applier = TransactionalWorkspaceEditApplier(tmp_path)
    staged = applier.stage(edit)
    applier.commit(staged)
    return applier, staged


def test_external_formatter_off_by_default_does_not_invoke_command(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    applier, staged = _commit_staged_edit(tmp_path)
    manager = _make_manager(tmp_path, V2FormattingConfig())  # default: use_external_formatter=False, command=None

    calls: list[list[str]] = []

    def _record(argv, **_kwargs):
        calls.append(argv)
        return subprocess.CompletedProcess(argv, 0, "", "")

    monkeypatch.setattr(manager_module.subprocess, "run", _record)

    preview_result: dict = {}
    manager._run_external_formatter(applier, staged, preview_result)

    assert calls == []
    assert "formatting" not in preview_result


def test_external_formatter_off_with_command_set_but_flag_false_does_not_invoke(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    applier, staged = _commit_staged_edit(tmp_path)
    manager = _make_manager(tmp_path, V2FormattingConfig(use_external_formatter=False, command="true"))

    calls: list[list[str]] = []
    monkeypatch.setattr(
        manager_module.subprocess,
        "run",
        lambda argv, **_kw: calls.append(argv) or subprocess.CompletedProcess(argv, 0, "", ""),
    )

    preview_result: dict = {}
    manager._run_external_formatter(applier, staged, preview_result)

    assert calls == []
    assert "formatting" not in preview_result


def test_external_formatter_runs_once_per_changed_file_appending_path(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    applier, staged = _commit_staged_edit(tmp_path)
    manager = _make_manager(tmp_path, V2FormattingConfig(use_external_formatter=True, command="my-formatter --write"))

    calls: list[list[str]] = []

    def _record(argv, **_kwargs):
        calls.append(argv)
        return subprocess.CompletedProcess(argv, 0, "", "")

    monkeypatch.setattr(manager_module.subprocess, "run", _record)

    preview_result: dict = {}
    manager._run_external_formatter(applier, staged, preview_result)

    # One invocation per changed/created file; the absolute path is appended as the final arg (no {file} placeholder).
    expected_changed = str((tmp_path / "Existing.java").resolve())
    expected_created = str((tmp_path / "Created.java").resolve())
    assert len(calls) == 2
    assert all(argv[:2] == ["my-formatter", "--write"] for argv in calls)
    appended = sorted(argv[-1] for argv in calls)
    assert appended == sorted([expected_changed, expected_created])

    formatting = preview_result["formatting"]
    assert formatting["ran"] is True
    assert formatting["command"] == "my-formatter --write"
    assert formatting["warnings"] == []
    assert sorted(formatting["formattedFiles"]) == ["Created.java", "Existing.java"]


def test_external_formatter_substitutes_file_placeholder(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    applier, staged = _commit_staged_edit(tmp_path)
    manager = _make_manager(tmp_path, V2FormattingConfig(use_external_formatter=True, command="fmt {file} --check"))

    calls: list[list[str]] = []
    monkeypatch.setattr(
        manager_module.subprocess,
        "run",
        lambda argv, **_kw: calls.append(argv) or subprocess.CompletedProcess(argv, 0, "", ""),
    )

    preview_result: dict = {}
    manager._run_external_formatter(applier, staged, preview_result)

    expected_changed = str((tmp_path / "Existing.java").resolve())
    expected_created = str((tmp_path / "Created.java").resolve())
    # {file} is replaced in place (not appended); the trailing arg stays --check.
    assert len(calls) == 2
    assert all(argv[0] == "fmt" and argv[-1] == "--check" for argv in calls)
    substituted = sorted(argv[1] for argv in calls)
    assert substituted == sorted([expected_changed, expected_created])


def test_external_formatter_failure_surfaces_warning_without_rolling_back(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    applier, staged = _commit_staged_edit(tmp_path)
    manager = _make_manager(tmp_path, V2FormattingConfig(use_external_formatter=True, command="failing-formatter"))

    def _fail(argv, **_kwargs):
        return subprocess.CompletedProcess(argv, 3, "", "boom: syntax error")

    monkeypatch.setattr(manager_module.subprocess, "run", _fail)

    preview_result: dict = {"applied": True, "accepted": True}
    manager._run_external_formatter(applier, staged, preview_result)

    # The applied edit stays on disk: a formatter failure is a post-pass warning, never a rollback.
    assert (tmp_path / "Existing.java").read_text(encoding="utf-8") == "klass Existing{}\n"
    assert (tmp_path / "Created.java").read_text(encoding="utf-8") == "class Created{}\n"
    assert preview_result["applied"] is True
    assert preview_result["accepted"] is True
    assert "rolledBack" not in preview_result

    formatting = preview_result["formatting"]
    assert formatting["ran"] is True
    assert formatting["formattedFiles"] == []
    assert len(formatting["warnings"]) == 2
    assert all("exited with code 3" in w and "boom: syntax error" in w for w in formatting["warnings"])


def test_external_formatter_oserror_surfaces_warning_without_rolling_back(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    applier, staged = _commit_staged_edit(tmp_path)
    manager = _make_manager(tmp_path, V2FormattingConfig(use_external_formatter=True, command="missing-binary"))

    def _raise(argv, **_kwargs):
        raise FileNotFoundError("No such file or directory: 'missing-binary'")

    monkeypatch.setattr(manager_module.subprocess, "run", _raise)

    preview_result: dict = {"applied": True}
    manager._run_external_formatter(applier, staged, preview_result)

    assert (tmp_path / "Existing.java").read_text(encoding="utf-8") == "klass Existing{}\n"
    assert preview_result["applied"] is True
    formatting = preview_result["formatting"]
    assert formatting["ran"] is True
    assert formatting["formattedFiles"] == []
    assert len(formatting["warnings"]) == 2
    assert all("failed to run" in w for w in formatting["warnings"])


def test_external_formatter_empty_command_after_parse_warns_without_running(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    applier, staged = _commit_staged_edit(tmp_path)
    manager = _make_manager(tmp_path, V2FormattingConfig(use_external_formatter=True, command="   "))

    calls: list = []
    monkeypatch.setattr(manager_module.subprocess, "run", lambda *a, **k: calls.append(a) or subprocess.CompletedProcess([], 0, "", ""))

    preview_result: dict = {}
    manager._run_external_formatter(applier, staged, preview_result)

    assert calls == []
    assert preview_result["formatting"]["ran"] is False
    assert preview_result["formatting"]["warnings"] == ["external formatter command was empty after parsing"]


# --- G003: the external formatter is part of the javac-validated transaction (full apply pipeline) -------------------


_REFACTORED = "class Demo { int total = 1; }\n"


def _v2_workspace_edit(source: Path, new_text: str) -> dict[str, object]:
    """A one-file REPLACE edit (`value` -> ``new_text``) in the Serena workspace-edit shape the sidecar returns."""
    return {
        "changes": [
            {
                "path": "Demo.java",
                "oldSha256": sha256_bytes(source.read_bytes()),
                "edits": [{"startOffset": 17, "endOffset": 22, "newText": new_text, "kind": "REPLACE"}],
            }
        ],
        "fileOperations": [],
        "warnings": [],
        "preconditions": [],
        "stats": {"editCount": 1, "fileOperationCount": 0},
    }


def _formatting_manager(tmp_path: Path) -> JavaRefactorManager:
    return JavaRefactorManager(
        str(tmp_path),
        LanguageBackend.LSP,
        [Language.JAVA],
        java_refactor_config=JavaRefactorConfig(
            enabled=True,
            v2=JavaRefactorV2Config(formatting=V2FormattingConfig(use_external_formatter=True, command="fmt")),
        ),
    )


def test_external_formatter_output_is_javac_validated_then_committed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """G003 happy path: with the formatter enabled, the refactor is committed, the formatter reformats the file, javac
    post-validation re-runs over the FORMATTED on-disk state, and (clean) the formatted bytes are what stays on disk and
    what the apply result reports. Proves the formatter output is inside the validated transaction, not an unvalidated
    post-pass.
    """
    source = tmp_path / "Demo.java"
    source.write_text("class Demo { int value = 1; }\n", encoding="utf-8")

    # The formatter reformats the committed refactor into a multi-line, still-valid layout.
    formatted_text = "class Demo {\n    int total = 1;\n}\n"

    validated_disk_states: list[str] = []

    def _format(argv, **_kwargs):
        target = Path(argv[-1])
        assert target.read_text(encoding="utf-8") == _REFACTORED  # formatter sees the committed refactor
        target.write_text(formatted_text, encoding="utf-8")
        return subprocess.CompletedProcess(argv, 0, "", "")

    monkeypatch.setattr(manager_module.subprocess, "run", _format)

    class FakeClient:
        def capabilities(self) -> dict[str, object]:
            return {"capabilities": {"refactorSessions": {"level": "beta", "status": "supported"}}}

        def apply_session(self, session_id: str, expected_project_revision: object = None, selection: object = None) -> dict[str, object]:
            return {
                "accepted": True,
                "session": {"sessionId": session_id},
                "validation": {"accepted": True},
                "preview": {"accepted": True, "workspaceEdit": _v2_workspace_edit(source, "total")},
            }

        def validate_edit(self, overlay: dict) -> dict[str, object]:
            return {"accepted": True, "ready": True, "compilerErrors": [], "compilerWarnings": []}

        def status(self, refresh: bool = False) -> SimpleNamespace:
            # javac post-validation observes the on-disk state, which is the FORMATTED text by the time it runs.
            validated_disk_states.append(source.read_text(encoding="utf-8"))
            return SimpleNamespace(ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []})

    manager = _formatting_manager(tmp_path)
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: FakeClient())

    result = manager.apply_v2_refactor_session("S")

    assert result["applied"] is True, result
    assert result.get("refusal") is None, result
    # javac post-validation ran on the formatted bytes (not the pre-format refactor).
    assert formatted_text in validated_disk_states
    # Final on-disk bytes equal the validated, formatted state.
    assert source.read_text(encoding="utf-8") == formatted_text
    # The apply result reflects the formatted output.
    assert result["formatting"]["formattedContent"]["Demo.java"] == formatted_text
    assert result["formatting"]["formattedFiles"] == ["Demo.java"]


def test_external_formatter_introduced_error_rolls_back_whole_transaction(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """G003 failure path: a formatter that introduces a compiler error is caught by javac post-validation, and the WHOLE
    snapshot (refactor + formatting) is rolled back so the file returns byte-for-byte to its pre-apply state.
    """
    source = tmp_path / "Demo.java"
    original = "class Demo { int value = 1; }\n"
    source.write_text(original, encoding="utf-8")

    broken_text = "class Demo { int total = ; }\n"  # formatter corrupts the file

    def _format(argv, **_kwargs):
        Path(argv[-1]).write_text(broken_text, encoding="utf-8")
        return subprocess.CompletedProcess(argv, 0, "", "")

    monkeypatch.setattr(manager_module.subprocess, "run", _format)

    class FakeClient:
        def capabilities(self) -> dict[str, object]:
            return {"capabilities": {"refactorSessions": {"level": "beta", "status": "supported"}}}

        def apply_session(self, session_id: str, expected_project_revision: object = None, selection: object = None) -> dict[str, object]:
            return {
                "accepted": True,
                "session": {"sessionId": session_id},
                "validation": {"accepted": True},
                "preview": {"accepted": True, "workspaceEdit": _v2_workspace_edit(source, "total")},
            }

        def validate_edit(self, overlay: dict) -> dict[str, object]:
            return {"accepted": True, "ready": True, "compilerErrors": [], "compilerWarnings": []}

        def status(self, refresh: bool = False) -> SimpleNamespace:
            # javac sees whatever is on disk: a syntax error after the formatter ran => not ready.
            disk = source.read_text(encoding="utf-8")
            if "= ;" in disk:
                return SimpleNamespace(
                    ready=False,
                    errors=["Demo.java:1:24: illegal start of expression"],
                    project_model={"conventionalFallbackUsed": False, "warnings": []},
                )
            return SimpleNamespace(ready=True, errors=[], project_model={"conventionalFallbackUsed": False, "warnings": []})

    manager = _formatting_manager(tmp_path)
    monkeypatch.setattr(manager, "_validate_supported_project", lambda: None)
    monkeypatch.setattr(manager, "_get_or_start_client", lambda refresh=False: FakeClient())

    result = manager.apply_v2_refactor_session("S")

    assert result["accepted"] is False, result
    assert result["applied"] is False, result
    assert result["rolledBack"] is True, result
    assert result["refusal"]["code"] == "session_post_validation_failed", result
    assert result["formatting"]["rolledBack"] is True, result
    # The whole transaction was rolled back: the file is byte-for-byte the pre-apply original.
    assert source.read_text(encoding="utf-8") == original
