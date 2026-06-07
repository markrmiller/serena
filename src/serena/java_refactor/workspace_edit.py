import hashlib
import os
import shutil
import tempfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Literal


class WorkspaceEditError(ValueError):
    """Structured refusal raised when a workspace edit is unsafe or malformed."""


@dataclass(frozen=True)
class RefactorTextEdit:
    """Single text replacement planned by the Java refactoring sidecar."""

    relative_path: str
    start_offset: int
    end_offset: int
    replacement: str
    offset_encoding: Literal["character", "byte"] = "character"
    old_hash: str | None = None


@dataclass(frozen=True)
class RefactorFileOperation:
    """Single file operation planned by the Java refactoring sidecar."""

    kind: Literal["create", "delete", "rename"]
    relative_path: str
    new_relative_path: str | None = None
    content: str | None = None
    old_hash: str | None = None


@dataclass
class RefactorWorkspaceEdit:
    """Safe preview/apply model for Java refactoring edits."""

    text_edits: list[RefactorTextEdit] = field(default_factory=list)
    file_operations: list[RefactorFileOperation] = field(default_factory=list)
    old_hashes: dict[str, str] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)
    preconditions: list[str] = field(default_factory=list)
    stats: dict[str, int] = field(default_factory=dict)

    def touched_files(self) -> list[str]:
        """Returns sorted relative paths touched by this edit."""
        touched = set(self.old_hashes)
        for edit in self.text_edits:
            touched.add(edit.relative_path)
        for operation in self.file_operations:
            touched.add(operation.relative_path)
            if operation.new_relative_path is not None:
                touched.add(operation.new_relative_path)
        return sorted(touched)

    def to_dict(self) -> dict[str, Any]:
        """Serializes the workspace edit for preview output."""
        result = asdict(self)
        result["touched_files"] = self.touched_files()
        return result

    @classmethod
    def from_protocol_dict(cls, payload: dict[str, Any]) -> "RefactorWorkspaceEdit":
        """Creates a workspace edit from the sidecar's V1 Serena-specific edit model.

        The wire shape is grouped by file: ``changes[]`` carries ``path``, ``oldSha256``, and ``edits[]`` of
        ``{startOffset, endOffset, newText, kind}``; ``fileOperations[]`` carries ``oldPath``/``newPath`` for a rename
        and ``path`` (plus ``content`` for create) otherwise. Each change's ``oldSha256`` is recorded both per file (in
        :attr:`old_hashes`, the hash precondition the applier verifies) and on every flattened edit so the existing
        in-memory staging/commit pipeline applies unchanged.
        """
        text_edits: list[RefactorTextEdit] = []
        old_hashes: dict[str, str] = {}
        for change in payload.get("changes", []) or []:
            path = str(change["path"])
            old_sha256 = change.get("oldSha256")
            if not old_sha256:
                # Fail closed: every text-edit group targets an existing file, so the sidecar must supply the
                # optimistic-concurrency hash precondition. A malformed response without it must never reach
                # staging/apply with the hash check silently skipped.
                raise WorkspaceEditError(f"Malformed workspace edit: change group for {path} is missing oldSha256")
            old_hashes[path] = old_sha256
            for item in change.get("edits", []) or []:
                text_edits.append(
                    RefactorTextEdit(
                        relative_path=path,
                        start_offset=int(item["startOffset"]),
                        end_offset=int(item["endOffset"]),
                        replacement=str(item["newText"]),
                        offset_encoding="character",
                        old_hash=old_sha256,
                    )
                )
        file_operations: list[RefactorFileOperation] = []
        for item in payload.get("fileOperations", []) or []:
            kind = item["kind"]
            old_hash = item.get("oldSha256")
            if kind in ("rename", "delete") and not old_hash:
                # Fail closed: destructive file operations must carry the pre-edit hash of the file they remove/move.
                # A whole-file safe delete has NO text-edit group, so this is its only concurrent-modification guard.
                path_label = item.get("oldPath") if kind == "rename" else item.get("path")
                raise WorkspaceEditError(f"Malformed workspace edit: {kind} file operation for {path_label} is missing oldSha256")
            if kind == "rename":
                file_operations.append(
                    RefactorFileOperation(
                        kind="rename", relative_path=str(item["oldPath"]), new_relative_path=str(item["newPath"]), old_hash=old_hash
                    )
                )
            else:
                file_operations.append(
                    RefactorFileOperation(kind=kind, relative_path=str(item["path"]), content=item.get("content"), old_hash=old_hash)
                )
        return cls(
            text_edits=text_edits,
            file_operations=file_operations,
            old_hashes=old_hashes,
            warnings=list(payload.get("warnings", [])),
            preconditions=list(payload.get("preconditions", [])),
            stats=dict(payload.get("stats", {})),
        )


@dataclass
class WorkspaceEditPreview:
    """Preview summary for a workspace edit that has not modified files."""

    touched_files: list[str]
    edit_count: int
    file_operation_count: int
    warnings: list[str]
    preconditions: list[str]
    stats: dict[str, int]


@dataclass
class StagedEdit:
    """A fully staged-but-uncommitted workspace edit.

    ``preview`` summarizes the edit. ``changed_files`` maps project-relative paths to their would-be post-edit text
    (decoded with the project encoding), ``deleted_files`` lists project-relative paths the edit removes, and
    ``renamed_files`` pairs old/new project-relative paths. These three feed the sidecar's in-memory overlay validation.
    The byte-level ``_staged_bytes``/``_deleted_paths``/``_renamed_paths``/``_original_bytes`` are retained so
    :meth:`TransactionalWorkspaceEditApplier.commit` can write the edit transactionally (with rollback on failure).
    """

    preview: WorkspaceEditPreview
    changed_files: dict[str, str]
    deleted_files: list[str]
    renamed_files: list[dict[str, str]]
    _staged_bytes: dict[Path, bytes | None]
    _deleted_paths: set[Path]
    _renamed_paths: dict[Path, Path]
    _original_bytes: dict[Path, bytes | None]

    def overlay(self) -> dict[str, Any]:
        """The sidecar overlay payload (``changedFiles``/``deletedFiles``/``renamedFiles``) for pre-commit validation."""
        return {
            "changedFiles": self.changed_files,
            "deletedFiles": self.deleted_files,
            "renamedFiles": self.renamed_files,
        }


class TransactionalWorkspaceEditApplier:
    """Transactional Python-side applier for Java refactoring workspace edits."""

    def __init__(self, project_root: str | Path, encoding: str = "utf-8", line_ending: str | None = None) -> None:
        r"""
        :param project_root: root directory within which all edit paths must remain
        :param encoding: text encoding for character-offset edits and created text content
        :param line_ending: forced newline convention ("\n" or "\r\n") for inserted text; when None, each file's own
            existing line ending is preserved (falling back to "\n" for a file that has none). This keeps planner-emitted
            insertions (which use "\n") consistent with the target file's CRLF/LF style.
        """
        self._project_root = Path(project_root).resolve()
        self._encoding = encoding
        self._line_ending = line_ending

    def preview(self, workspace_edit: RefactorWorkspaceEdit) -> WorkspaceEditPreview:
        """Validates an edit and returns a no-write preview summary."""
        self._validate_workspace_edit(workspace_edit)
        return WorkspaceEditPreview(
            touched_files=workspace_edit.touched_files(),
            edit_count=len(workspace_edit.text_edits),
            file_operation_count=len(workspace_edit.file_operations),
            warnings=list(workspace_edit.warnings),
            preconditions=list(workspace_edit.preconditions),
            stats=dict(workspace_edit.stats),
        )

    def snapshot(self, workspace_edit: RefactorWorkspaceEdit) -> dict[Path, bytes | None]:
        """Captures the on-disk bytes of every file the edit may touch, for later semantic rollback."""
        return self._read_original_bytes(workspace_edit)

    def restore(self, snapshot: dict[Path, bytes | None]) -> None:
        """Restores files to a previously captured snapshot (used to roll back semantically invalid edits)."""
        self._restore_backups(snapshot)

    def stage(self, workspace_edit: RefactorWorkspaceEdit) -> StagedEdit:
        """Stages an edit fully in memory WITHOUT writing to disk.

        Returns a :class:`StagedEdit` exposing the would-be post-edit content (for sidecar overlay validation) alongside
        the byte-level staging needed by :meth:`commit`. No file is modified until ``commit`` runs.
        """
        preview = self.preview(workspace_edit)
        original_bytes = self._read_original_bytes(workspace_edit)
        staged_bytes = dict(original_bytes)
        deleted_paths: set[Path] = set()
        renamed_paths: dict[Path, Path] = {}

        # V1 transaction ordering: stage all changed file CONTENTS in memory first, then apply file creates/renames/
        # deletes. A renamed/moved file is therefore edited in place under its old path, and the rename operation carries
        # the already-edited content to the new path.
        self._stage_text_edits(workspace_edit, staged_bytes)
        self._stage_file_operations(workspace_edit, staged_bytes, deleted_paths, renamed_paths)

        rename_sources = set(renamed_paths)
        changed_files: dict[str, str] = {}
        for path, content in staged_bytes.items():
            if content is None:
                continue
            # A path's post-edit content is overlay-relevant when it is new (created/renamed-in) or its bytes changed.
            if original_bytes.get(path) == content and path not in renamed_paths.values():
                continue
            try:
                changed_files[self._relative_str(path)] = content.decode(self._encoding)
            except (UnicodeError, LookupError) as error:
                # E.g. a rename of a file whose bytes were never valid in the project encoding: refuse with a
                # structured error instead of leaking a UnicodeDecodeError out of the staging pipeline.
                raise WorkspaceEditError(
                    f"Staged content for {self._relative_str(path)} cannot be decoded with project encoding {self._encoding!r}: {error}"
                ) from error
        deleted_files = sorted(self._relative_str(path) for path in deleted_paths if path not in rename_sources)
        renamed_files = [
            {"oldPath": self._relative_str(source), "newPath": self._relative_str(target)} for source, target in renamed_paths.items()
        ]

        return StagedEdit(
            preview=preview,
            changed_files=changed_files,
            deleted_files=deleted_files,
            renamed_files=renamed_files,
            _staged_bytes=staged_bytes,
            _deleted_paths=deleted_paths,
            _renamed_paths=renamed_paths,
            _original_bytes=original_bytes,
        )

    def commit(self, staged: StagedEdit) -> WorkspaceEditPreview:
        """Commits a previously :meth:`stage`-d edit transactionally (restoring originals if any write fails)."""
        self._commit(staged._staged_bytes, staged._deleted_paths, staged._renamed_paths, staged._original_bytes)
        return staged.preview

    def apply(self, workspace_edit: RefactorWorkspaceEdit) -> WorkspaceEditPreview:
        """Applies an edit atomically from Serena's perspective (stage then commit)."""
        return self.commit(self.stage(workspace_edit))

    def _relative_str(self, path: Path) -> str:
        """Returns the project-relative POSIX-style path string for an absolute path under the project root."""
        return path.relative_to(self._project_root).as_posix()

    def _validate_workspace_edit(self, workspace_edit: RefactorWorkspaceEdit) -> None:
        """Validates paths, hashes, operation shape, and edit overlap."""
        for relative_path, old_hash in workspace_edit.old_hashes.items():
            self._verify_hash(self._resolve_relative_path(relative_path), old_hash)

        # Under V1 transaction ordering a renamed/moved file is edited in place under its OLD path and the rename
        # operation carries the edited content to the new path, so a text edit on a rename SOURCE is expected and
        # allowed. A text edit on a delete or create target is contradictory (the file is being removed, or does not yet
        # exist) and is rejected.
        delete_or_create_paths = {
            operation.relative_path for operation in workspace_edit.file_operations if operation.kind in {"delete", "create"}
        }
        for edit in workspace_edit.text_edits:
            self._validate_text_edit(edit)
            if edit.relative_path in delete_or_create_paths:
                raise WorkspaceEditError(f"Text edits cannot target a delete/create file-operation path: {edit.relative_path}")
            # Fail closed: every text edit targets an existing file, so an oldSha256 precondition is mandatory (either
            # per edit or via the file's change-group entry in old_hashes). Without it, a concurrent modification
            # between planning and apply would be silently overwritten. An empty string counts as missing (matching
            # the parse-layer check) so the refusal names the absent precondition instead of a confusing hash mismatch.
            if not edit.old_hash and edit.relative_path not in workspace_edit.old_hashes:
                raise WorkspaceEditError(f"Text edit for {edit.relative_path} is missing its oldSha256 hash precondition")
            if edit.old_hash is not None:
                self._verify_hash(self._resolve_relative_path(edit.relative_path), edit.old_hash)

        for operation in workspace_edit.file_operations:
            self._validate_file_operation(operation)
            # Fail closed: destructive file operations (delete/rename) must carry the pre-edit hash of the file they
            # remove/move. A whole-file delete has no text edits, so this is its only concurrent-modification guard.
            # An empty string counts as missing, matching the parse-layer check.
            if operation.kind in {"delete", "rename"} and not operation.old_hash:
                raise WorkspaceEditError(
                    f"{operation.kind.capitalize()} file operation for {operation.relative_path} is missing its oldSha256 hash precondition"
                )
            if operation.old_hash is not None:
                self._verify_hash(self._resolve_relative_path(operation.relative_path), operation.old_hash)

        self._reject_overlapping_text_edits(workspace_edit.text_edits)

    def _validate_text_edit(self, edit: RefactorTextEdit) -> None:
        """Validates one text edit."""
        self._resolve_relative_path(edit.relative_path)
        if edit.start_offset < 0 or edit.end_offset < edit.start_offset:
            raise WorkspaceEditError(f"Malformed text edit offsets for {edit.relative_path}: {edit.start_offset}-{edit.end_offset}")
        if edit.offset_encoding not in {"character", "byte"}:
            raise WorkspaceEditError(f"Unsupported offset encoding for {edit.relative_path}: {edit.offset_encoding}")

    def _validate_file_operation(self, operation: RefactorFileOperation) -> None:
        """Validates one file operation."""
        self._resolve_relative_path(operation.relative_path)
        if operation.kind not in {"create", "delete", "rename"}:
            raise WorkspaceEditError(f"Malformed file operation kind: {operation.kind}")
        if operation.kind == "create" and operation.content is None:
            raise WorkspaceEditError(f"Create operation requires content: {operation.relative_path}")
        if operation.kind == "rename" and operation.new_relative_path is None:
            raise WorkspaceEditError(f"Rename operation requires new_relative_path: {operation.relative_path}")
        if operation.new_relative_path is not None:
            self._resolve_relative_path(operation.new_relative_path)

    def _reject_overlapping_text_edits(self, edits: list[RefactorTextEdit]) -> None:
        """Rejects overlapping edits within each file and offset encoding."""
        grouped: dict[tuple[str, str], list[RefactorTextEdit]] = {}
        for edit in edits:
            grouped.setdefault((edit.relative_path, edit.offset_encoding), []).append(edit)

        for (relative_path, _encoding), file_edits in grouped.items():
            sorted_edits = sorted(file_edits, key=lambda edit: (edit.start_offset, edit.end_offset))
            previous_end = -1
            for edit in sorted_edits:
                if edit.start_offset < previous_end:
                    raise WorkspaceEditError(f"Overlapping text edits for {relative_path}")
                previous_end = edit.end_offset

    def _read_original_bytes(self, workspace_edit: RefactorWorkspaceEdit) -> dict[Path, bytes | None]:
        """Reads all touched source bytes before staging."""
        originals: dict[Path, bytes | None] = {}
        for relative_path in workspace_edit.touched_files():
            path = self._resolve_relative_path(relative_path)
            originals[path] = path.read_bytes() if path.exists() else None
        return originals

    def _stage_file_operations(
        self,
        workspace_edit: RefactorWorkspaceEdit,
        staged_bytes: dict[Path, bytes | None],
        deleted_paths: set[Path],
        renamed_paths: dict[Path, Path],
    ) -> None:
        """Stages create/delete/rename operations in memory."""
        for operation in workspace_edit.file_operations:
            path = self._resolve_relative_path(operation.relative_path)
            if operation.kind == "create":
                if staged_bytes.get(path) is not None or path.exists():
                    raise WorkspaceEditError(f"Create operation target already exists: {operation.relative_path}")
                try:
                    staged_bytes[path] = (operation.content or "").encode(self._encoding)
                except (UnicodeError, LookupError) as error:
                    raise WorkspaceEditError(
                        f"Create operation content for {operation.relative_path} cannot be encoded "
                        f"with project encoding {self._encoding!r}: {error}"
                    ) from error
            elif operation.kind == "delete":
                if staged_bytes.get(path) is None and not path.exists():
                    raise WorkspaceEditError(f"Delete operation target does not exist: {operation.relative_path}")
                staged_bytes[path] = None
                deleted_paths.add(path)
            elif operation.kind == "rename":
                assert operation.new_relative_path is not None
                new_path = self._resolve_relative_path(operation.new_relative_path)
                source_bytes = staged_bytes.get(path, path.read_bytes() if path.exists() else None)
                if source_bytes is None:
                    raise WorkspaceEditError(f"Rename operation source does not exist: {operation.relative_path}")
                if staged_bytes.get(new_path) is not None or new_path.exists():
                    raise WorkspaceEditError(f"Rename operation target already exists: {operation.new_relative_path}")
                staged_bytes[path] = None
                staged_bytes[new_path] = source_bytes
                deleted_paths.add(path)
                renamed_paths[path] = new_path

    def _stage_text_edits(self, workspace_edit: RefactorWorkspaceEdit, staged_bytes: dict[Path, bytes | None]) -> None:
        """Stages text edits in descending offset order."""
        edits_by_path: dict[Path, list[RefactorTextEdit]] = {}
        for edit in workspace_edit.text_edits:
            path = self._resolve_relative_path(edit.relative_path)
            edits_by_path.setdefault(path, []).append(edit)

        for path, edits in edits_by_path.items():
            current = staged_bytes.get(path, path.read_bytes() if path.exists() else None)
            if current is None:
                raise WorkspaceEditError(f"Text edit target does not exist: {path.relative_to(self._project_root)}")

            byte_edits = [edit for edit in edits if edit.offset_encoding == "byte"]
            character_edits = [edit for edit in edits if edit.offset_encoding == "character"]
            if byte_edits and character_edits:
                raise WorkspaceEditError(f"Cannot mix byte and character offsets for {path.relative_to(self._project_root)}")
            # Preserve the target file's line-ending convention for any inserted newlines: use the configured project
            # line ending if forced, otherwise the file's own existing style.
            eol = self._line_ending or self._detect_eol(current)
            staged_bytes[path] = (
                self._apply_byte_edits(current, byte_edits, eol)
                if byte_edits
                else self._apply_character_edits(current, character_edits, eol)
            )

    def _detect_eol(self, content: bytes) -> str:
        """Detects a file's dominant newline style, defaulting to LF for a file without newlines."""
        try:
            text = content.decode(self._encoding)
        except (UnicodeDecodeError, LookupError):
            return "\n"
        return "\r\n" if "\r\n" in text else "\n"

    @staticmethod
    def _normalize_newlines(text: str, eol: str) -> str:
        """Rewrites every newline in inserted text to ``eol`` so insertions match the target file's convention."""
        if "\r" not in text and "\n" not in text:
            return text
        normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        return normalized if eol == "\n" else normalized.replace("\n", eol)

    def _apply_byte_edits(self, content: bytes, edits: list[RefactorTextEdit], eol: str = "\n") -> bytes:
        """Applies byte-offset edits to content."""
        for edit in sorted(edits, key=lambda item: item.start_offset, reverse=True):
            if edit.end_offset > len(content):
                raise WorkspaceEditError(f"Byte edit range exceeds file length: {edit.relative_path}")
            try:
                replacement = self._normalize_newlines(edit.replacement, eol).encode(self._encoding)
            except (UnicodeError, LookupError) as error:
                raise WorkspaceEditError(
                    f"Byte edit replacement for {edit.relative_path} cannot be encoded with project encoding {self._encoding!r}: {error}"
                ) from error
            content = content[: edit.start_offset] + replacement + content[edit.end_offset :]
        return content

    def _apply_character_edits(self, content: bytes, edits: list[RefactorTextEdit], eol: str = "\n") -> bytes:
        """Applies character-offset edits to decoded content.

        The javac sidecar emits offsets as UTF-16 code-unit indices (a Java ``char`` is a UTF-16 code unit), so a
        non-BMP character (e.g. a supplementary-plane identifier letter) counts as two. Python ``str`` is indexed by
        Unicode code points, so slicing the decoded string by these offsets would be off by one for every preceding
        non-BMP character and corrupt the edit. Slice in UTF-16 space (little-endian, two bytes per code unit) so the
        offsets align exactly regardless of supplementary-plane content.

        Every failure mode is a structured :class:`WorkspaceEditError` refusal naming the file and range — never a
        leaked ``UnicodeError``/``LookupError``: an undecodable file or unknown/mismatched project encoding, an offset
        that is out of bounds or falls INSIDE a surrogate pair (a stale or malformed sidecar edit would otherwise
        corrupt the adjacent character), replacement text that is not valid Unicode (e.g. a lone surrogate), and edited
        content the project encoding cannot represent.
        """
        relative_path = edits[0].relative_path
        try:
            text = content.decode(self._encoding)
        except (UnicodeError, LookupError) as error:
            raise WorkspaceEditError(
                f"Cannot decode {relative_path} with project encoding {self._encoding!r} for character edits: {error}"
            ) from error
        units = text.encode("utf-16-le")
        unit_length = len(units) // 2
        for edit in sorted(edits, key=lambda item: item.start_offset, reverse=True):
            if edit.end_offset > unit_length:
                raise WorkspaceEditError(f"Character edit range exceeds file length: {edit.relative_path}")
            for offset in (edit.start_offset, edit.end_offset):
                if self._splits_surrogate_pair(units, offset):
                    raise WorkspaceEditError(
                        f"Character edit range {edit.start_offset}-{edit.end_offset} for {edit.relative_path} splits a "
                        f"UTF-16 surrogate pair at offset {offset}, so it does not align with character boundaries"
                    )
            try:
                replacement_units = self._normalize_newlines(edit.replacement, eol).encode("utf-16-le")
            except UnicodeError as error:
                raise WorkspaceEditError(
                    f"Character edit replacement for {edit.relative_path} "
                    f"(range {edit.start_offset}-{edit.end_offset}) is not valid Unicode text: {error}"
                ) from error
            units = units[: edit.start_offset * 2] + replacement_units + units[edit.end_offset * 2 :]
        try:
            return units.decode("utf-16-le").encode(self._encoding)
        except (UnicodeError, LookupError) as error:
            raise WorkspaceEditError(
                f"Edited content for {relative_path} cannot be encoded with project encoding {self._encoding!r}: {error}"
            ) from error

    @staticmethod
    def _splits_surrogate_pair(units: bytes, offset: int) -> bool:
        """Whether a UTF-16 code-unit offset falls between the high and low halves of a surrogate pair."""
        if offset <= 0 or offset * 2 >= len(units):
            return False
        previous = int.from_bytes(units[offset * 2 - 2 : offset * 2], "little")
        current = int.from_bytes(units[offset * 2 : offset * 2 + 2], "little")
        return 0xD800 <= previous <= 0xDBFF and 0xDC00 <= current <= 0xDFFF

    def _commit(
        self,
        staged_bytes: dict[Path, bytes | None],
        deleted_paths: set[Path],
        renamed_paths: dict[Path, Path],
        original_bytes: dict[Path, bytes | None],
    ) -> None:
        """Commits a staged edit transactionally via a preflight + temp-file-staging + atomic-replace + journal design.

        Phases:

        1. **Preflight (no mutation):** every controllable failure mode (unwritable destination/parent, missing parent
           that cannot be created, rename source absent / destination colliding, delete target absent) is validated
           BEFORE any irreversible change. A failure here raises ``WorkspaceEditError`` with nothing written, so the
           common permission/feasibility refusals never reach the partial-apply fallback in ``manager.py``.
        2. **Staging (reversible):** all new/modified content is written to temporary files in the SAME directory as the
           destination, so the later :func:`os.replace` is atomic on the same filesystem. Originals are untouched.
        3. **Commit (atomic-as-possible):** :func:`os.replace` moves each temp file into place; renames and deletes are
           performed. Every completed step is recorded in a journal carrying the backup bytes needed to reverse it.
        4. **Rollback:** if any commit step fails, the journal is reversed (replaced files restored from backups, renames
           undone, deletes recreated). Because content lands via atomic replace of a fully-written temp file, no
           half-written file can exist.

        Temp files are cleaned up on every exit path. Cross-filesystem temp/destination pairs are preflighted and fall
        back to a (non-atomic but still journalled) replace; on POSIX same-filesystem the replace is atomic.
        """
        backups = dict(original_bytes)
        rename_sources = set(renamed_paths)
        # Paths whose content is (re)written: any staged path that is not a rename source and is not a pure delete.
        # A renamed-and-edited file (e.g. a cross-package move that rewrites its `package` declaration) is staged under
        # its TARGET path; the rename step only relocates the source's ORIGINAL bytes, so the target's edited content
        # must still be written here. Because the commit performs renames before content replaces, that write lands on
        # the moved file and overwrites the stale bytes. Rename SOURCES are excluded: the source is moved away by the
        # rename and never written in place.
        content_writes: dict[Path, bytes] = {
            path: content
            for path, content in staged_bytes.items()
            if content is not None and path not in rename_sources
        }
        # Pure deletes (a path staged to None that is not a rename source — rename sources are removed by the rename).
        pure_deletes = {path for path in deleted_paths if path not in rename_sources}

        self._preflight_commit(content_writes, renamed_paths, pure_deletes)

        temp_files: list[Path] = []
        # Journal of completed, reversible commit steps in execution order; reversed on failure.
        journal: list[tuple[str, tuple[Any, ...]]] = []
        try:
            # Staging phase: write every new/modified content to a sibling temp file (same dir => atomic os.replace).
            staged_temps: dict[Path, Path] = {}
            for path, content in content_writes.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                temp = self._write_temp_sibling(path, content)
                temp_files.append(temp)
                staged_temps[path] = temp

            # Commit phase: renames first (matches the in-memory staging order), then content replaces, then deletes.
            for source, target in renamed_paths.items():
                target.parent.mkdir(parents=True, exist_ok=True)
                source_backup = source.read_bytes() if source.exists() else None
                os.replace(str(source), str(target))
                journal.append(("rename", (source, target, source_backup)))

            for path, temp in staged_temps.items():
                backup = backups.get(path)
                if backup is None and path.exists():
                    backup = path.read_bytes()
                os.replace(str(temp), str(path))
                temp_files.remove(temp)
                journal.append(("replace", (path, backup)))

            for path in pure_deletes:
                if path.exists():
                    backup = backups.get(path)
                    if backup is None:
                        backup = path.read_bytes()
                    path.unlink()
                    journal.append(("delete", (path, backup)))
        except Exception as e:
            self._rollback_journal(journal)
            raise WorkspaceEditError(f"Workspace edit failed and backups were restored: {e}") from e
        finally:
            for temp in temp_files:
                try:
                    temp.unlink()
                except OSError:
                    pass

    def _preflight_commit(
        self,
        content_writes: dict[Path, bytes],
        renamed_paths: dict[Path, Path],
        pure_deletes: set[Path],
    ) -> None:
        """Validates every controllable commit precondition WITHOUT mutating the workspace.

        Raises :class:`WorkspaceEditError` (nothing written) for the failure modes the applier can detect ahead of time:
        a destination or parent directory that is not writable / cannot be created, a rename source that is missing or a
        rename/create destination that unexpectedly collides, and a delete target that no longer exists. This is what
        turns the common permission/feasibility failures into clean refusals so the partial-apply fallback is never hit.
        """
        for path in content_writes:
            self._preflight_writable_destination(path)
        for source, target in renamed_paths.items():
            if not source.exists():
                raise WorkspaceEditError(f"Rename source does not exist at commit time: {self._relative_str(source)}")
            # A rename destination must not pre-exist (staging already rejected on-disk collisions); a destination that
            # is itself another rename's source is fine, because that source is moved away in the same commit.
            if target.exists() and target not in renamed_paths:
                raise WorkspaceEditError(f"Rename destination already exists at commit time: {self._relative_str(target)}")
            self._preflight_writable_destination(target)
            self._preflight_writable_parent(source)
        for path in pure_deletes:
            if not path.exists():
                raise WorkspaceEditError(f"Delete target does not exist at commit time: {self._relative_str(path)}")
            self._preflight_writable_parent(path)

    def _preflight_writable_destination(self, path: Path) -> None:
        """Ensures ``path`` can be created/overwritten: an existing target must be writable, else its parent must be."""
        if path.exists():
            if not os.access(path, os.W_OK):
                raise WorkspaceEditError(f"Destination is not writable: {self._relative_str(path)}")
            self._preflight_writable_parent(path)
        else:
            self._preflight_writable_parent(path)

    def _preflight_writable_parent(self, path: Path) -> None:
        """Ensures ``path``'s nearest existing ancestor is a writable directory (so the path can be created/replaced)."""
        parent = path.parent
        ancestor = parent
        while not ancestor.exists():
            if ancestor == ancestor.parent:
                break
            ancestor = ancestor.parent
        if not ancestor.is_dir():
            raise WorkspaceEditError(f"Parent path is not a directory: {self._relative_str(ancestor)}")
        if not os.access(ancestor, os.W_OK | os.X_OK):
            raise WorkspaceEditError(f"Parent directory is not writable: {self._relative_str(ancestor)}")

    def _write_temp_sibling(self, destination: Path, content: bytes) -> Path:
        """Writes ``content`` to a fully-flushed temp file in ``destination``'s directory and returns its path.

        Co-locating the temp file with the destination keeps the later :func:`os.replace` on the same filesystem (atomic
        on POSIX). The content is flushed and fsync'd so a fully-written file is what lands.
        """
        fd, temp_name = tempfile.mkstemp(dir=str(destination.parent), prefix=f".{destination.name}.", suffix=".tmp")
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(content)
                handle.flush()
                os.fsync(handle.fileno())
        except Exception:
            try:
                os.unlink(temp_name)
            except OSError:
                pass
            raise
        return Path(temp_name)

    def _rollback_journal(self, journal: list[tuple[str, tuple[Any, ...]]]) -> None:
        """Reverses completed commit steps in LIFO order, restoring the workspace to its pre-commit state."""
        for kind, payload in reversed(journal):
            if kind == "replace":
                path, backup = payload
                if backup is None:
                    if path.exists():
                        path.unlink()
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(backup)
            elif kind == "rename":
                source, target, source_backup = payload
                if target.exists():
                    os.replace(str(target), str(source))
                elif source_backup is not None:
                    source.parent.mkdir(parents=True, exist_ok=True)
                    source.write_bytes(source_backup)
            elif kind == "delete":
                path, backup = payload
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(backup)

    def _restore_backups(self, backups: dict[Path, bytes | None]) -> None:
        """Restores original file bytes after a failed commit."""
        for path, content in backups.items():
            if content is None:
                if path.is_file() or path.is_symlink():
                    path.unlink()
                elif path.exists():
                    shutil.rmtree(path)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)

    def _verify_hash(self, path: Path, expected_hash: str) -> None:
        """Verifies a SHA-256 precondition for an existing file."""
        if not path.exists():
            raise WorkspaceEditError(f"Hash precondition target does not exist: {path.relative_to(self._project_root)}")
        actual_hash = sha256_bytes(path.read_bytes())
        if actual_hash != expected_hash:
            raise WorkspaceEditError(
                f"Hash mismatch for {path.relative_to(self._project_root)}: expected {expected_hash}, got {actual_hash}"
            )

    def _resolve_relative_path(self, relative_path: str) -> Path:
        """Resolves and validates a path under the project root."""
        if Path(relative_path).is_absolute():
            raise WorkspaceEditError(f"Absolute paths are not allowed: {relative_path}")
        path = (self._project_root / relative_path).resolve()
        if os.path.commonpath([str(self._project_root), str(path)]) != str(self._project_root):
            raise WorkspaceEditError(f"Path escapes project root: {relative_path}")
        return path


def sha256_bytes(content: bytes) -> str:
    """Returns the SHA-256 digest for bytes."""
    return hashlib.sha256(content).hexdigest()


# Backwards-compatible aliases used by the first sidecar scaffold story.
JavaTextEdit = RefactorTextEdit
JavaWorkspaceEdit = RefactorWorkspaceEdit
