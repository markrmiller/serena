import os
from pathlib import Path

import pytest

from serena.java_refactor.workspace_edit import (
    RefactorFileOperation,
    RefactorTextEdit,
    RefactorWorkspaceEdit,
    TransactionalWorkspaceEditApplier,
    WorkspaceEditError,
    sha256_bytes,
)


def test_workspace_edit_preview_does_not_modify_files(tmp_path: Path) -> None:
    source = tmp_path / "a.txt"
    source.write_text("alpha\n", encoding="utf-8")
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit("a.txt", 0, 5, "beta", old_hash=sha256_bytes(source.read_bytes()))],
        warnings=["preview warning"],
        preconditions=["hash checked"],
        stats={"planned": 1},
    )

    preview = TransactionalWorkspaceEditApplier(tmp_path).preview(edit)

    assert source.read_text(encoding="utf-8") == "alpha\n"
    assert preview.touched_files == ["a.txt"]
    assert preview.edit_count == 1
    assert preview.file_operation_count == 0
    assert preview.warnings == ["preview warning"]
    assert preview.preconditions == ["hash checked"]
    assert preview.stats == {"planned": 1}


def test_workspace_edit_apply_character_offsets_descending_preserves_line_endings(tmp_path: Path) -> None:
    source = tmp_path / "a.txt"
    source.write_bytes(b"one\r\ntwo\r\nthree\r\n")
    old_hash = sha256_bytes(source.read_bytes())
    edit = RefactorWorkspaceEdit(
        text_edits=[
            RefactorTextEdit("a.txt", 0, 3, "ONE", old_hash=old_hash),
            RefactorTextEdit("a.txt", 10, 15, "THREE", old_hash=old_hash),
        ]
    )

    TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert source.read_bytes() == b"ONE\r\ntwo\r\nTHREE\r\n"


def test_workspace_edit_inserted_newlines_match_file_crlf(tmp_path: Path) -> None:
    # G008: planner insertions use "\n"; when applied to a CRLF file the inserted newlines must become "\r\n" so the
    # file's existing line-ending convention is preserved (e.g. a package/import insertion in a CRLF Java file).
    source = tmp_path / "a.txt"
    source.write_bytes(b"alpha\r\nbeta\r\n")
    edit = RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("a.txt", 0, 5, "first\nsecond", old_hash=sha256_bytes(source.read_bytes()))])

    TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert source.read_bytes() == b"first\r\nsecond\r\nbeta\r\n"


def test_workspace_edit_inserted_newlines_keep_lf_for_lf_file(tmp_path: Path) -> None:
    # An LF file keeps LF for inserted newlines (no spurious CRLF introduced).
    source = tmp_path / "a.txt"
    source.write_bytes(b"alpha\nbeta\n")
    edit = RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("a.txt", 0, 5, "first\nsecond", old_hash=sha256_bytes(source.read_bytes()))])

    TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert source.read_bytes() == b"first\nsecond\nbeta\n"


def test_workspace_edit_forced_line_ending_overrides_detection(tmp_path: Path) -> None:
    # An explicit project line_ending forces inserted newlines regardless of the file's own style.
    source = tmp_path / "a.txt"
    source.write_bytes(b"alpha\r\nbeta\r\n")
    edit = RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("a.txt", 0, 5, "first\nsecond", old_hash=sha256_bytes(source.read_bytes()))])

    TransactionalWorkspaceEditApplier(tmp_path, line_ending="\n").apply(edit)

    # Inserted text uses the forced LF; the existing CRLF spans outside the edit are untouched.
    assert source.read_bytes() == b"first\nsecond\r\nbeta\r\n"


def test_workspace_edit_apply_byte_offsets(tmp_path: Path) -> None:
    source = tmp_path / "unicode.txt"
    source.write_text("aéz", encoding="utf-8")
    start = len(b"a")
    end = len("aé".encode())
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit("unicode.txt", start, end, "E", offset_encoding="byte", old_hash=sha256_bytes(source.read_bytes()))]
    )

    TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert source.read_text(encoding="utf-8") == "aEz"


def test_workspace_edit_rejects_hash_mismatch(tmp_path: Path) -> None:
    source = tmp_path / "a.txt"
    source.write_text("current", encoding="utf-8")
    edit = RefactorWorkspaceEdit(old_hashes={"a.txt": "0" * 64})

    with pytest.raises(WorkspaceEditError, match="Hash mismatch"):
        TransactionalWorkspaceEditApplier(tmp_path).preview(edit)


def test_workspace_edit_rejects_overlapping_edits(tmp_path: Path) -> None:
    (tmp_path / "a.txt").write_text("abcdef", encoding="utf-8")
    old_hash = sha256_bytes((tmp_path / "a.txt").read_bytes())
    edit = RefactorWorkspaceEdit(
        text_edits=[
            RefactorTextEdit("a.txt", 1, 4, "x", old_hash=old_hash),
            RefactorTextEdit("a.txt", 3, 5, "y", old_hash=old_hash),
        ]
    )

    with pytest.raises(WorkspaceEditError, match="Overlapping"):
        TransactionalWorkspaceEditApplier(tmp_path).preview(edit)


def test_workspace_edit_rejects_outside_root_paths(tmp_path: Path) -> None:
    edit = RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("../escape.txt", 0, 0, "x")])

    with pytest.raises(WorkspaceEditError, match="escapes project root"):
        TransactionalWorkspaceEditApplier(tmp_path).preview(edit)


def test_workspace_edit_rejects_malformed_file_operations(tmp_path: Path) -> None:
    applier = TransactionalWorkspaceEditApplier(tmp_path)

    with pytest.raises(WorkspaceEditError, match="Create operation requires content"):
        applier.preview(RefactorWorkspaceEdit(file_operations=[RefactorFileOperation("create", "new.txt")]))

    with pytest.raises(WorkspaceEditError, match="Rename operation requires new_relative_path"):
        applier.preview(RefactorWorkspaceEdit(file_operations=[RefactorFileOperation("rename", "old.txt")]))


def test_workspace_edit_file_operations_apply_atomically(tmp_path: Path) -> None:
    (tmp_path / "delete.txt").write_text("delete", encoding="utf-8")
    (tmp_path / "rename.txt").write_text("rename", encoding="utf-8")
    edit = RefactorWorkspaceEdit(
        file_operations=[
            RefactorFileOperation("create", "created.txt", content="created\n"),
            RefactorFileOperation("delete", "delete.txt", old_hash=sha256_bytes((tmp_path / "delete.txt").read_bytes())),
            RefactorFileOperation(
                "rename", "rename.txt", new_relative_path="renamed.txt", old_hash=sha256_bytes((tmp_path / "rename.txt").read_bytes())
            ),
        ]
    )

    preview = TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert preview.touched_files == ["created.txt", "delete.txt", "rename.txt", "renamed.txt"]
    assert (tmp_path / "created.txt").read_text(encoding="utf-8") == "created\n"
    assert not (tmp_path / "delete.txt").exists()
    assert not (tmp_path / "rename.txt").exists()
    assert (tmp_path / "renamed.txt").read_text(encoding="utf-8") == "rename"


def test_workspace_edit_renamed_and_edited_file_keeps_edited_content(tmp_path: Path) -> None:
    # Regression: a file that is BOTH renamed and content-edited (e.g. a cross-package move that rewrites its
    # `package` declaration) must land at its new path with the EDITED bytes, not the original ones. The edit is staged
    # under the source path and the rename carries it to the (possibly new) target directory.
    source = tmp_path / "rename.txt"
    source.write_text("rename-old", encoding="utf-8")
    original_hash = sha256_bytes(source.read_bytes())
    edit = RefactorWorkspaceEdit(
        text_edits=[
            # Replace "old" (offsets 7-10) with "new"; targets the rename SOURCE path per the staging contract.
            RefactorTextEdit("rename.txt", 7, 10, "new", old_hash=original_hash),
        ],
        file_operations=[
            RefactorFileOperation("rename", "rename.txt", new_relative_path="sub/renamed.txt", old_hash=original_hash),
        ],
    )

    TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert not source.exists()
    # Target is in a directory that did not exist before the move, and carries the edited content.
    assert (tmp_path / "sub" / "renamed.txt").read_text(encoding="utf-8") == "rename-new"


def test_workspace_edit_restores_backups_and_removes_created_files_on_commit_failure(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # Content now lands via an atomic os.replace of a fully-written temp file, so inject the commit-phase failure there:
    # the replace into "fail.txt" fails after "source.txt" has already been committed. The journal must restore the
    # edited original byte-for-byte and remove the (never-replaced) created file.
    source = tmp_path / "source.txt"
    source.write_text("before", encoding="utf-8")
    real_replace = os.replace

    def failing_replace(src: object, dst: object, *args: object, **kwargs: object) -> None:
        if str(dst).endswith("fail.txt"):
            raise OSError("injected write failure")
        return real_replace(src, dst, *args, **kwargs)

    monkeypatch.setattr(os, "replace", failing_replace)
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit("source.txt", 0, 6, "after", old_hash=sha256_bytes(source.read_bytes()))],
        file_operations=[RefactorFileOperation("create", "fail.txt", content="new")],
    )

    with pytest.raises(WorkspaceEditError, match="backups were restored"):
        TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert source.read_text(encoding="utf-8") == "before"
    assert not (tmp_path / "fail.txt").exists()
    assert not list(tmp_path.glob(".*.tmp"))


def test_workspace_edit_snapshot_and_restore_round_trip(tmp_path: Path) -> None:
    edited = tmp_path / "edited.txt"
    edited.write_text("original\n", encoding="utf-8")
    created = tmp_path / "created.txt"
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit("edited.txt", 0, 8, "changed", old_hash=sha256_bytes(edited.read_bytes()))],
        file_operations=[RefactorFileOperation(kind="create", relative_path="created.txt", content="new\n")],
    )
    applier = TransactionalWorkspaceEditApplier(tmp_path)

    snapshot = applier.snapshot(edit)
    applier.apply(edit)
    assert edited.read_text(encoding="utf-8") == "changed\n"
    assert created.read_text(encoding="utf-8") == "new\n"

    applier.restore(snapshot)

    assert edited.read_text(encoding="utf-8") == "original\n"
    assert not created.exists()

def test_workspace_edit_rejects_text_edit_without_hash_precondition(tmp_path: Path) -> None:
    # Fail closed: a text edit on an existing file with neither a per-edit old_hash nor a change-group entry in
    # old_hashes must refuse — never silently skip the optimistic-concurrency check.
    (tmp_path / "a.txt").write_text("alpha\n", encoding="utf-8")
    edit = RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("a.txt", 0, 5, "beta")])

    with pytest.raises(WorkspaceEditError, match="missing its oldSha256 hash precondition"):
        TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert (tmp_path / "a.txt").read_text(encoding="utf-8") == "alpha\n"


def test_workspace_edit_group_hash_satisfies_text_edit_hash_requirement(tmp_path: Path) -> None:
    # The change-group hash (old_hashes) is an equally valid precondition carrier for a per-file edit group.
    source = tmp_path / "a.txt"
    source.write_text("alpha\n", encoding="utf-8")
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit("a.txt", 0, 5, "beta")],
        old_hashes={"a.txt": sha256_bytes(source.read_bytes())},
    )

    TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert source.read_text(encoding="utf-8") == "beta\n"


@pytest.mark.parametrize("kind", ["delete", "rename"])
def test_workspace_edit_rejects_destructive_file_operation_without_hash(tmp_path: Path, kind: str) -> None:
    # Fail closed: delete/rename file operations must carry the pre-edit hash of the file they remove/move.
    (tmp_path / "target.txt").write_text("data", encoding="utf-8")
    operation = (
        RefactorFileOperation("delete", "target.txt")
        if kind == "delete"
        else RefactorFileOperation("rename", "target.txt", new_relative_path="renamed.txt")
    )

    with pytest.raises(WorkspaceEditError, match="missing its oldSha256 hash precondition"):
        TransactionalWorkspaceEditApplier(tmp_path).apply(RefactorWorkspaceEdit(file_operations=[operation]))

    assert (tmp_path / "target.txt").read_text(encoding="utf-8") == "data"


def test_workspace_edit_delete_refuses_when_file_changed_after_planning(tmp_path: Path) -> None:
    # Race-condition guard for a whole-file delete (e.g. safe delete of a top-level type): the file changing between
    # planning and apply must refuse the apply, since a whole-file delete has no text edits carrying a hash.
    target = tmp_path / "Gone.java"
    target.write_text("class Gone {}\n", encoding="utf-8")
    edit = RefactorWorkspaceEdit(
        file_operations=[RefactorFileOperation("delete", "Gone.java", old_hash=sha256_bytes(target.read_bytes()))]
    )
    target.write_text("class Gone { int newlyAdded; }\n", encoding="utf-8")  # concurrent modification

    with pytest.raises(WorkspaceEditError, match="Hash mismatch"):
        TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert target.read_text(encoding="utf-8") == "class Gone { int newlyAdded; }\n"


def test_workspace_edit_rename_refuses_when_file_changed_after_planning(tmp_path: Path) -> None:
    # Race-condition guard for a file rename (top-level type rename / move): a concurrent modification of the rename
    # SOURCE between planning and apply must refuse the apply.
    target = tmp_path / "Old.java"
    target.write_text("class Old {}\n", encoding="utf-8")
    edit = RefactorWorkspaceEdit(
        file_operations=[
            RefactorFileOperation("rename", "Old.java", new_relative_path="New.java", old_hash=sha256_bytes(target.read_bytes()))
        ]
    )
    target.write_text("class Old { int newlyAdded; }\n", encoding="utf-8")  # concurrent modification

    with pytest.raises(WorkspaceEditError, match="Hash mismatch"):
        TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert target.read_text(encoding="utf-8") == "class Old { int newlyAdded; }\n"
    assert not (tmp_path / "New.java").exists()


def test_from_protocol_dict_rejects_change_group_without_hash() -> None:
    payload = {
        "changes": [{"path": "A.java", "edits": [{"startOffset": 0, "endOffset": 1, "newText": "x", "kind": "REPLACE"}]}],
        "fileOperations": [],
    }

    with pytest.raises(WorkspaceEditError, match="change group for A.java is missing oldSha256"):
        RefactorWorkspaceEdit.from_protocol_dict(payload)


@pytest.mark.parametrize(
    ("operation", "label"),
    [
        ({"kind": "delete", "path": "A.java"}, "A.java"),
        ({"kind": "rename", "oldPath": "Old.java", "newPath": "New.java"}, "Old.java"),
    ],
)
def test_from_protocol_dict_rejects_destructive_file_operation_without_hash(operation: dict, label: str) -> None:
    payload = {"changes": [], "fileOperations": [operation]}

    with pytest.raises(WorkspaceEditError, match=f"file operation for {label} is missing oldSha256"):
        RefactorWorkspaceEdit.from_protocol_dict(payload)


def test_from_protocol_dict_parses_file_operation_hashes() -> None:
    payload = {
        "changes": [],
        "fileOperations": [
            {"kind": "delete", "path": "Gone.java", "oldSha256": "a" * 64},
            {"kind": "rename", "oldPath": "Old.java", "newPath": "New.java", "oldSha256": "b" * 64},
            {"kind": "create", "path": "Created.java", "content": "class Created {}\n"},
        ],
    }

    edit = RefactorWorkspaceEdit.from_protocol_dict(payload)

    assert [operation.old_hash for operation in edit.file_operations] == ["a" * 64, "b" * 64, None]


def test_workspace_edit_empty_string_hash_counts_as_missing(tmp_path: Path) -> None:
    # The parse layer rejects empty oldSha256 values; the applier layer must agree that "" means missing (refusing with
    # the missing-precondition message, not a confusing hash mismatch).
    (tmp_path / "a.txt").write_text("alpha\n", encoding="utf-8")
    applier = TransactionalWorkspaceEditApplier(tmp_path)

    with pytest.raises(WorkspaceEditError, match="missing its oldSha256 hash precondition"):
        applier.apply(RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("a.txt", 0, 5, "beta", old_hash="")]))

    with pytest.raises(WorkspaceEditError, match="missing its oldSha256 hash precondition"):
        applier.apply(RefactorWorkspaceEdit(file_operations=[RefactorFileOperation("delete", "a.txt", old_hash="")]))


def test_from_protocol_dict_ignores_stray_hash_on_create() -> None:
    # A create has no pre-edit file, so a stray oldSha256 is carried but never enforced (creates are guarded by the
    # target-must-not-exist staging check instead).
    payload = {
        "changes": [],
        "fileOperations": [{"kind": "create", "path": "Created.java", "content": "class Created {}\n", "oldSha256": "c" * 64}],
    }

    edit = RefactorWorkspaceEdit.from_protocol_dict(payload)

    assert edit.file_operations[0].old_hash == "c" * 64


@pytest.mark.parametrize(
    ("start", "end", "label"),
    [
        (2, 3, "start splits the pair"),
        (1, 2, "end splits the pair"),
    ],
)
def test_workspace_edit_refuses_edit_splitting_surrogate_pair(tmp_path: Path, start: int, end: int, label: str) -> None:
    # javac offsets are UTF-16 code units, so a non-BMP character occupies two units. A stale or malformed sidecar edit
    # whose boundary falls INSIDE such a pair must be a structured refusal — splicing there would corrupt the character
    # and otherwise leak a raw UnicodeDecodeError out of staging.
    source = tmp_path / "a.txt"
    source.write_text("a\N{GRINNING FACE}b", encoding="utf-8")  # UTF-16 units: 'a', high, low, 'b'
    edit = RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("a.txt", start, end, "x", old_hash=sha256_bytes(source.read_bytes()))])
    applier = TransactionalWorkspaceEditApplier(tmp_path)

    with pytest.raises(WorkspaceEditError, match="splits a UTF-16 surrogate pair"):
        applier.stage(edit)
    assert source.read_text(encoding="utf-8") == "a\N{GRINNING FACE}b", label


def test_workspace_edit_refuses_out_of_bounds_utf16_offset(tmp_path: Path) -> None:
    # "abc" is 3 UTF-16 code units; an edit ending beyond that is a refusal, not an IndexError or silent truncation.
    source = tmp_path / "a.txt"
    source.write_text("abc", encoding="utf-8")
    edit = RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("a.txt", 0, 10, "x", old_hash=sha256_bytes(source.read_bytes()))])

    with pytest.raises(WorkspaceEditError, match="exceeds file length"):
        TransactionalWorkspaceEditApplier(tmp_path).stage(edit)
    assert source.read_text(encoding="utf-8") == "abc"


def test_workspace_edit_refuses_unknown_project_encoding(tmp_path: Path) -> None:
    # An unknown codec name in the project configuration must surface as a structured refusal naming the encoding,
    # not as a leaked LookupError from codecs.
    source = tmp_path / "a.txt"
    source.write_text("abc", encoding="utf-8")
    edit = RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("a.txt", 0, 1, "x", old_hash=sha256_bytes(source.read_bytes()))])

    with pytest.raises(WorkspaceEditError, match="project encoding 'no-such-codec'"):
        TransactionalWorkspaceEditApplier(tmp_path, encoding="no-such-codec").stage(edit)


def test_workspace_edit_refuses_undecodable_file_content(tmp_path: Path) -> None:
    # A file whose bytes are not valid in the project encoding (e.g. mis-declared encoding) cannot be edited by
    # character offsets; the mismatch is a structured refusal, not a UnicodeDecodeError.
    source = tmp_path / "a.txt"
    source.write_bytes(b"caf\xe9")  # latin-1 bytes, NOT valid UTF-8
    edit = RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("a.txt", 0, 1, "x", old_hash=sha256_bytes(source.read_bytes()))])

    with pytest.raises(WorkspaceEditError, match="Cannot decode a.txt with project encoding"):
        TransactionalWorkspaceEditApplier(tmp_path, encoding="utf-8").stage(edit)
    assert source.read_bytes() == b"caf\xe9"


def test_workspace_edit_refuses_lone_surrogate_replacement(tmp_path: Path) -> None:
    # Replacement text containing a lone surrogate (possible via JSON "\ud800" escapes) is not valid Unicode and must
    # be refused before any splice, not crash the UTF-16 re-encode.
    source = tmp_path / "a.txt"
    source.write_text("abc", encoding="utf-8")
    edit = RefactorWorkspaceEdit(text_edits=[RefactorTextEdit("a.txt", 0, 1, "\ud800", old_hash=sha256_bytes(source.read_bytes()))])

    with pytest.raises(WorkspaceEditError, match="not valid Unicode text"):
        TransactionalWorkspaceEditApplier(tmp_path).stage(edit)
    assert source.read_text(encoding="utf-8") == "abc"


def test_workspace_edit_refuses_replacement_unrepresentable_in_project_encoding(tmp_path: Path) -> None:
    # A replacement character the project encoding cannot represent (here: an emoji into a latin-1 project) must be a
    # structured refusal at staging time, not a UnicodeEncodeError when the edited content is re-encoded.
    source = tmp_path / "a.txt"
    source.write_text("abc", encoding="iso-8859-1")
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit("a.txt", 0, 1, "\N{GRINNING FACE}", old_hash=sha256_bytes(source.read_bytes()))]
    )

    with pytest.raises(WorkspaceEditError, match="cannot be encoded with project encoding"):
        TransactionalWorkspaceEditApplier(tmp_path, encoding="iso-8859-1").stage(edit)
    assert source.read_bytes() == b"abc"


def _snapshot_tree(root: Path) -> dict[str, bytes]:
    """Returns a byte-for-byte snapshot of every regular file under ``root`` (skipping applier temp files)."""
    snapshot: dict[str, bytes] = {}
    for path in sorted(root.rglob("*")):
        if path.is_file():
            snapshot[path.relative_to(root).as_posix()] = path.read_bytes()
    return snapshot


def test_workspace_edit_preflight_refuses_unwritable_dir_without_mutation(tmp_path: Path) -> None:
    # An unwritable destination directory is a controllable feasibility failure: the preflight must refuse BEFORE any
    # file is written, so the workspace is byte-for-byte unchanged and the manager never reaches the partial-apply path.
    sub = tmp_path / "locked"
    sub.mkdir()
    source = sub / "a.txt"
    source.write_text("alpha\n", encoding="utf-8")
    untouched = tmp_path / "other.txt"
    untouched.write_text("keep\n", encoding="utf-8")
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit("locked/a.txt", 0, 5, "beta", old_hash=sha256_bytes(source.read_bytes()))]
    )
    before = _snapshot_tree(tmp_path)
    os.chmod(sub, 0o500)  # read+execute, NOT writable
    try:
        with pytest.raises(WorkspaceEditError, match="not writable"):
            TransactionalWorkspaceEditApplier(tmp_path).apply(edit)
    finally:
        os.chmod(sub, 0o700)
    assert _snapshot_tree(tmp_path) == before


def test_workspace_edit_preflight_hash_mismatch_does_not_mutate(tmp_path: Path) -> None:
    # A hash-precondition mismatch is caught during staging/validation, before any commit-phase write.
    source = tmp_path / "a.txt"
    source.write_text("current\n", encoding="utf-8")
    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit("a.txt", 0, 7, "beta", old_hash="0" * 64)]
    )
    before = _snapshot_tree(tmp_path)
    with pytest.raises(WorkspaceEditError, match="Hash mismatch"):
        TransactionalWorkspaceEditApplier(tmp_path).apply(edit)
    assert _snapshot_tree(tmp_path) == before


def test_workspace_edit_preflight_refuses_blocked_rename_destination(tmp_path: Path) -> None:
    # A rename whose destination already exists on disk is a feasibility refusal with no mutation. (Staging already
    # rejects a pre-existing destination; this asserts the workspace is left byte-for-byte unchanged.)
    source = tmp_path / "Old.java"
    source.write_text("class Old {}\n", encoding="utf-8")
    blocker = tmp_path / "New.java"
    blocker.write_text("class New {}\n", encoding="utf-8")
    edit = RefactorWorkspaceEdit(
        file_operations=[
            RefactorFileOperation("rename", "Old.java", new_relative_path="New.java", old_hash=sha256_bytes(source.read_bytes()))
        ]
    )
    before = _snapshot_tree(tmp_path)
    with pytest.raises(WorkspaceEditError, match="already exists"):
        TransactionalWorkspaceEditApplier(tmp_path).apply(edit)
    assert _snapshot_tree(tmp_path) == before


def test_workspace_edit_commit_phase_failure_rolls_back_via_journal(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # Inject a failure DURING the commit phase (after preflight passes): the first content replace succeeds, the second
    # raises. The journal must reverse the completed replace so EVERY original is restored byte-for-byte and the created
    # file is removed -- no partial application survives.
    first = tmp_path / "first.txt"
    first.write_text("first-before\n", encoding="utf-8")
    second = tmp_path / "second.txt"
    second.write_text("second-before\n", encoding="utf-8")
    created = tmp_path / "created.txt"

    edit = RefactorWorkspaceEdit(
        text_edits=[
            RefactorTextEdit("first.txt", 0, 12, "first-after", old_hash=sha256_bytes(first.read_bytes())),
            RefactorTextEdit("second.txt", 0, 13, "second-after", old_hash=sha256_bytes(second.read_bytes())),
        ],
        file_operations=[RefactorFileOperation("create", "created.txt", content="created\n")],
    )

    real_replace = os.replace
    calls = {"n": 0}

    def flaky_replace(src: object, dst: object, *args: object, **kwargs: object) -> None:
        # Let temp-file replaces for the FIRST committed content through, then fail the next one to hit the journal.
        if str(dst).endswith(".txt") and not str(dst).endswith(".tmp"):
            calls["n"] += 1
            if calls["n"] == 2:
                raise OSError("injected commit-phase failure")
        return real_replace(src, dst, *args, **kwargs)

    monkeypatch.setattr(os, "replace", flaky_replace)
    before = _snapshot_tree(tmp_path)

    with pytest.raises(WorkspaceEditError, match="backups were restored"):
        TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert _snapshot_tree(tmp_path) == before
    assert not created.exists()
    # No applier temp files were left behind.
    assert not list(tmp_path.glob(".*.tmp"))


def test_workspace_edit_commit_failure_rolls_back_rename_and_delete(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # A commit that performs a rename and a delete, then fails on a content replace, must undo the rename (restore the
    # source, remove the destination) and recreate the deleted file -- the full workspace returns to its pre-commit state.
    renamed = tmp_path / "Old.java"
    renamed.write_text("class Old {}\n", encoding="utf-8")
    deleted = tmp_path / "Gone.java"
    deleted.write_text("class Gone {}\n", encoding="utf-8")
    edited = tmp_path / "Edit.java"
    edited.write_text("class Edit {}\n", encoding="utf-8")

    edit = RefactorWorkspaceEdit(
        text_edits=[RefactorTextEdit("Edit.java", 0, 13, "class Edited {}", old_hash=sha256_bytes(edited.read_bytes()))],
        file_operations=[
            RefactorFileOperation("rename", "Old.java", new_relative_path="New.java", old_hash=sha256_bytes(renamed.read_bytes())),
            RefactorFileOperation("delete", "Gone.java", old_hash=sha256_bytes(deleted.read_bytes())),
        ],
    )

    real_replace = os.replace

    def fail_on_edit_replace(src: object, dst: object, *args: object, **kwargs: object) -> None:
        if str(dst).endswith("Edit.java"):
            raise OSError("injected content-replace failure")
        return real_replace(src, dst, *args, **kwargs)

    monkeypatch.setattr(os, "replace", fail_on_edit_replace)
    before = _snapshot_tree(tmp_path)

    with pytest.raises(WorkspaceEditError, match="backups were restored"):
        TransactionalWorkspaceEditApplier(tmp_path).apply(edit)

    assert _snapshot_tree(tmp_path) == before
    assert not (tmp_path / "New.java").exists()
    assert (tmp_path / "Old.java").read_text(encoding="utf-8") == "class Old {}\n"
    assert (tmp_path / "Gone.java").read_text(encoding="utf-8") == "class Gone {}\n"
    assert not list(tmp_path.glob(".*.tmp"))
