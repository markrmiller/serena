import json

from serena.java_refactor.manager import JavaRefactorManager, target_hints_from_lsp_symbol
from serena.tools import (
    EditingToolWithDiagnostics,
    Tool,
    ToolMarkerBeta,
    ToolMarkerOptional,
    ToolMarkerSymbolicEdit,
    ToolMarkerSymbolicRead,
)


class _JavaRefactorToolBase(EditingToolWithDiagnostics, ToolMarkerSymbolicEdit, ToolMarkerOptional, ToolMarkerBeta):
    """Shared helpers for the mutating Java-specific refactoring tools.

    These derive from :class:`EditingToolWithDiagnostics` (so they participate in Serena's edit/diagnostics framework and
    are removed in read-only mode like the other editing tools), and are marked optional + beta because the
    compiler-backed engine is opt-in.
    """

    def _get_manager(self) -> JavaRefactorManager:
        return self.create_java_refactor_client()

    def _resolve_preview(self, preview: bool | None) -> bool:
        """Resolves the effective preview mode for a direct Java tool call.

        An explicit caller value always wins. When the caller omits ``preview``, the project's
        ``java_refactor.preview_default`` applies — the same knob the generic ``rename_symbol``/``safe_delete_symbol``
        routing honors — so direct and generic entry points share one default behavior per project. When the project
        configuration cannot be resolved, the safe default is preview mode (no mutation).
        """
        if preview is not None:
            return preview
        try:
            return bool(self.project.project_config.java_refactor.preview_default)
        except Exception:
            return True

    def _resolve_target(self, relative_path: str, name_path: str | None, line: int | None, column: int | None) -> tuple[int, int, dict]:
        """Resolves a one-based (line, column) plus target-identity hints for the sidecar from a Serena ``name_path``.

        Pass ``name_path`` to use Serena's symbol targeting (resolved to a source position via the language server);
        the resolved LSP symbol's name, kind, and signature arity travel along as ``nameHint``/``kindHint``/
        ``arityHint``, which the sidecar verifies against its own javac resolution BEFORE planning any edit — closing
        the identity gap of the lossy position round-trip (overloads, same-line siblings, enclosing declarations,
        parameter/field name collisions are refused with ``target_mismatch`` instead of silently refactoring the wrong
        element). As an advanced/internal escape hatch, pass one-based ``line`` and ``column`` directly instead; that
        path is position-only and carries no identity hints to verify.
        """
        if not relative_path:
            raise ValueError("relative_path is required.")
        if line is not None and column is not None:
            return line, column, {}
        if name_path is None:
            raise ValueError("Provide either name_path, or both line and column (one-based).")
        try:
            use_lsp_symbol_resolution = bool(self.project.project_config.java_refactor.use_lsp_symbol_resolution)
        except Exception:
            use_lsp_symbol_resolution = True
        if not use_lsp_symbol_resolution:
            raise ValueError(
                "java_refactor.use_lsp_symbol_resolution is false; pass explicit one-based line and column instead "
                "of name_path for Java refactor tools."
            )
        symbol = self.create_language_server_symbol_retriever().find_unique(
            name_path, substring_matching=False, within_relative_path=relative_path
        )
        if symbol.line is None or symbol.column is None:
            raise ValueError(f"Symbol '{name_path}' in {relative_path} has no resolvable source position.")
        # Language-server positions are zero-based; the sidecar expects one-based line/column.
        return symbol.line + 1, symbol.column + 1, target_hints_from_lsp_symbol(symbol)

    def _finalize_result(self, result: dict) -> str:
        """Serializes a refactor result, first re-syncing the language server with any applied on-disk edits.

        The Java refactoring sidecar writes its edits directly to disk, OUTSIDE the language-server editor path. If an
        affected file is currently open in the Java language server, it keeps a stale in-memory copy (no
        didChange/didClose was sent), so its document symbols, references, and diagnostics would be stale after an
        applied rename/move/inline/delete. On a REAL apply we therefore push the fresh disk content for every affected
        file (from the workspace edit's touched-file set, which includes both the old and new paths of renames/moves) to
        the language server. Previews touch no files and are left alone.

        This is best-effort and strictly defensive: a notification failure must never corrupt the successful apply
        result, so any error is annotated onto the result rather than raised.
        """
        if result.get("applied"):
            touched_files = ((result.get("preview") or {}).get("touchedFiles")) or []
            self._notify_language_server_of_disk_edits(result, touched_files)
        return json.dumps(result, ensure_ascii=False, indent=2)

    def _notify_language_server_of_disk_edits(self, result: dict, touched_files: list[str]) -> None:
        """Re-syncs every touched file with the active language server, annotating (never raising on) failures."""
        try:
            ls_manager = self.agent.get_language_server_manager()
        except Exception as error:
            result["languageServerSyncError"] = f"Could not access the language server manager: {error}"
            return
        if ls_manager is None:
            return
        for relative_path in touched_files:
            try:
                ls_manager.notify_open_file_changed_on_disk(relative_path)
            except Exception as error:
                # Keep the successful apply intact; surface the coherence gap as an annotation instead of crashing.
                result.setdefault("languageServerSyncWarnings", []).append(
                    f"Failed to re-sync {relative_path} with the language server after apply: {error}"
                )


class JavaRefactorStatusTool(Tool, ToolMarkerSymbolicRead, ToolMarkerOptional, ToolMarkerBeta):
    """Reports readiness and diagnostics for Serena's optional Java-only refactoring sidecar."""

    def apply(self, refresh: bool = False) -> str:
        """
        Report whether the Java-only refactoring sidecar is ready for the active project.

        :param refresh: Whether to restart/reinitialize the sidecar before collecting status.
        """
        manager = self.create_java_refactor_client()

        return manager.get_status(refresh=refresh).to_json()


class JavaSemanticRenameTool(_JavaRefactorToolBase):
    """Previews or applies compiler-backed Java semantic rename edits."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        new_name: str = "",
        preview: bool | None = None,
        include_javadocs: bool | None = None,
        include_comments: bool | None = None,
        validate: bool = True,
        line: int | None = None,
        column: int | None = None,
    ) -> str:
        """
        Rename the Java symbol identified by ``name_path`` in ``relative_path`` using javac semantic resolution.

        :param name_path: Serena name path of the target symbol (e.g. ``MyClass/myMethod``). Resolved to a source
            position via the language server. For overloaded methods include the signature as Serena requires.
        :param relative_path: Java source path relative to the project root.
        :param new_name: New Java identifier.
        :param preview: When true only the planned workspace edit is returned; when false the edit is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent),
            matching the generic ``rename_symbol``/``safe_delete_symbol`` routing.
        :param include_javadocs: Also update Javadoc references (e.g. ``{@link Foo#bar}``) to the renamed symbol.
            When omitted, the project's ``java_refactor.include_javadocs_default`` decides (false if absent).
        :param include_comments: Also update plain-comment/string occurrences of the old name (use with care).
            When omitted, the project's ``java_refactor.include_comments_default`` decides (false if absent).
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged
            in-memory javac validation. This flag cannot weaken the apply safety gate: post-apply validation with
            rollback and rename old-key residual verification ALWAYS run on apply, and staged pre-commit javac
            validation runs unless the project disables ``java_refactor.validate_before_apply``.
        :param line: Advanced: one-based source line, used instead of ``name_path`` resolution.
        :param column: Advanced: one-based source column, used instead of ``name_path`` resolution.
        """
        resolved_line, resolved_column, target_hints = self._resolve_target(relative_path, name_path, line, column)
        result = self._get_manager().semantic_rename(
            relative_path,
            resolved_line,
            resolved_column,
            new_name,
            apply=not self._resolve_preview(preview),
            validate=validate,
            include_javadocs=include_javadocs,
            include_comments=include_comments,
            target_hints=target_hints,
        )
        return self._finalize_result(result)


class JavaSafeDeleteTool(_JavaRefactorToolBase):
    """Previews or applies compiler-backed Java safe delete edits."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        preview: bool | None = None,
        allow_public_api_delete: bool = False,
        validate: bool = True,
        line: int | None = None,
        column: int | None = None,
    ) -> str:
        """
        Safely delete the Java symbol identified by ``name_path`` after semantic reference and hierarchy checks.

        :param name_path: Serena name path of the target symbol. Resolved to a source position via the language server.
        :param relative_path: Java source path relative to the project root.
        :param preview: When true only the planned workspace edit is returned; when false the edit is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent),
            matching the generic ``rename_symbol``/``safe_delete_symbol`` routing.
        :param allow_public_api_delete: Whether public/protected API deletion is allowed.
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged
            in-memory javac validation. This flag cannot weaken the apply safety gate: post-apply validation with
            rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project disables
            ``java_refactor.validate_before_apply``.
        :param line: Advanced: one-based source line, used instead of ``name_path`` resolution.
        :param column: Advanced: one-based source column, used instead of ``name_path`` resolution.
        """
        resolved_line, resolved_column, target_hints = self._resolve_target(relative_path, name_path, line, column)
        result = self._get_manager().safe_delete(
            relative_path,
            resolved_line,
            resolved_column,
            allow_public_api_delete=allow_public_api_delete,
            apply=not self._resolve_preview(preview),
            validate=validate,
            target_hints=target_hints,
        )
        return self._finalize_result(result)


class JavaMoveTopLevelTypeTool(_JavaRefactorToolBase):
    """Previews or applies compiler-backed Java top-level type moves."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        target_package: str | None = None,
        target_directory: str | None = None,
        preview: bool | None = None,
        validate: bool = True,
        line: int | None = None,
        column: int | None = None,
    ) -> str:
        """
        Move a file-backed top-level Java type to another package and directory.

        Provide EXACTLY ONE of ``target_package`` or ``target_directory``.

        :param name_path: Serena name path of the top-level type. Resolved to a source position via the language server.
        :param relative_path: Java source path relative to the project root.
        :param target_package: Destination Java package, or empty string for the default package. The destination
            directory is derived from the source root containing the moved file. Mutually exclusive with
            ``target_directory``.
        :param target_directory: Destination directory path relative to the project root (e.g.
            ``src/main/java/com/new``). The destination package is derived by relativizing this directory against the
            source root that contains it; this supports moves across source roots. Mutually exclusive with
            ``target_package``.
        :param preview: When true only the planned workspace edit is returned; when false the edit is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent),
            matching the generic ``rename_symbol``/``safe_delete_symbol`` routing.
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged
            in-memory javac validation. This flag cannot weaken the apply safety gate: post-apply validation with
            rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project disables
            ``java_refactor.validate_before_apply``.
        :param line: Advanced: one-based source line, used instead of ``name_path`` resolution.
        :param column: Advanced: one-based source column, used instead of ``name_path`` resolution.
        """
        resolved_line, resolved_column, target_hints = self._resolve_target(relative_path, name_path, line, column)
        result = self._get_manager().move_top_level_type(
            relative_path,
            resolved_line,
            resolved_column,
            target_package=target_package,
            target_directory=target_directory,
            apply=not self._resolve_preview(preview),
            validate=validate,
            target_hints=target_hints,
        )
        return self._finalize_result(result)


class JavaInlineLocalVariableTool(_JavaRefactorToolBase):
    """Previews or applies conservative Java local variable inline edits."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        preview: bool | None = None,
        validate: bool = True,
        line: int | None = None,
        column: int | None = None,
    ) -> str:
        """
        Inline a pure/effectively-final Java local variable.

        :param name_path: Serena name path of the local variable. Resolved to a source position via the language server.
        :param relative_path: Java source path relative to the project root.
        :param preview: When true only the planned workspace edit is returned; when false the edit is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent),
            matching the generic ``rename_symbol``/``safe_delete_symbol`` routing.
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged
            in-memory javac validation. This flag cannot weaken the apply safety gate: post-apply validation with
            rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project disables
            ``java_refactor.validate_before_apply``.
        :param line: Advanced: one-based source line, used instead of ``name_path`` resolution.
        :param column: Advanced: one-based source column, used instead of ``name_path`` resolution.
        """
        resolved_line, resolved_column, target_hints = self._resolve_target(relative_path, name_path, line, column)
        result = self._get_manager().inline_local_variable(
            relative_path,
            resolved_line,
            resolved_column,
            apply=not self._resolve_preview(preview),
            validate=validate,
            target_hints=target_hints,
        )
        return self._finalize_result(result)


class JavaInlineConstantTool(_JavaRefactorToolBase):
    """Previews or applies conservative Java constant inline edits."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        preview: bool | None = None,
        validate: bool = True,
        allow_public_api: bool = False,
        line: int | None = None,
        column: int | None = None,
    ) -> str:
        """
        Inline a Java compile-time constant.

        A private constant has its usages inlined and its declaration removed. A non-private constant previews usage
        replacements while keeping its declaration; applying those edits requires ``allow_public_api`` because it may be
        part of the public API or a reflection target.

        :param name_path: Serena name path of the constant field. Resolved to a source position via the language server.
        :param relative_path: Java source path relative to the project root.
        :param preview: When true only the planned workspace edit is returned; when false the edit is applied. When
            omitted, the project's ``java_refactor.preview_default`` decides (preview mode if that config is absent),
            matching the generic ``rename_symbol``/``safe_delete_symbol`` routing.
        :param validate: Preview-time reporting only. When true (default) a preview also runs and reports staged
            in-memory javac validation. This flag cannot weaken the apply safety gate: post-apply validation with
            rollback ALWAYS runs on apply, and staged pre-commit javac validation runs unless the project disables
            ``java_refactor.validate_before_apply``.
        :param allow_public_api: Opt in to inlining a non-private constant's usages; the declaration is kept since it
            may be public API or a reflection target.
        :param line: Advanced: one-based source line, used instead of ``name_path`` resolution.
        :param column: Advanced: one-based source column, used instead of ``name_path`` resolution.
        """
        resolved_line, resolved_column, target_hints = self._resolve_target(relative_path, name_path, line, column)
        result = self._get_manager().inline_constant(
            relative_path,
            resolved_line,
            resolved_column,
            apply=not self._resolve_preview(preview),
            validate=validate,
            allow_public_api=allow_public_api,
            target_hints=target_hints,
        )
        return self._finalize_result(result)


class JavaRefactorSymbolTool(_JavaRefactorToolBase):
    def apply(
        self,
        operation: str,
        name_path: str | None = None,
        relative_path: str = "",
        new_name: str = "",
        target_package: str = "",
        preview: bool | None = None,
        include_javadocs: bool | None = None,
        include_comments: bool | None = None,
        validate: bool = True,
        allow_public_api_delete: bool = False,
        allow_public_api: bool = False,
        fallback_to_lsp: bool = True,
        line: int | None = None,
        column: int | None = None,
    ) -> str:
        """
        Dispatch a Java refactor operation through the compiler-backed sidecar using one target contract.

        :param operation: One of ``semantic_rename``/``rename``, ``safe_delete``/``delete``, ``move_top_level_type``,
            ``inline_local_variable``, or ``inline_constant``.
        :param name_path: Serena name path of the target symbol. Honored only when
            ``java_refactor.use_lsp_symbol_resolution`` is true.
        :param relative_path: Java source path relative to the project root.
        :param new_name: New Java identifier for rename operations.
        :param target_package: Destination package for ``move_top_level_type``.
        :param preview: When true only the planned workspace edit is returned; when false the edit is applied. When
            omitted, ``java_refactor.preview_default`` decides.
        :param include_javadocs: Rename-only opt-in for Javadoc reference edits.
        :param include_comments: Rename-only opt-in for plain comment/string textual edits.
        :param validate: Preview-time reporting flag; apply safety validation cannot be weakened by this flag.
        :param allow_public_api_delete: Safe-delete opt-in for public/protected API deletion.
        :param allow_public_api: Inline-constant opt-in allowing non-private constant apply after preview safety gates.
        :param fallback_to_lsp: For rename, fall back to the existing LSP rename if the sidecar is unavailable and a
            ``name_path`` target was supplied. Hard semantic refusals never fall back.
        :param line: Advanced: one-based source line, used instead of ``name_path`` resolution.
        :param column: Advanced: one-based source column, used instead of ``name_path`` resolution.
        """
        normalized = operation.strip().lower().replace("-", "_")
        resolved_line, resolved_column, target_hints = self._resolve_target(relative_path, name_path, line, column)
        apply_edit = not self._resolve_preview(preview)
        try:
            manager = self._get_manager()
            if normalized in {"semantic_rename", "rename"}:
                result = manager.semantic_rename(
                    relative_path,
                    resolved_line,
                    resolved_column,
                    new_name,
                    apply=apply_edit,
                    validate=validate,
                    include_javadocs=include_javadocs,
                    include_comments=include_comments,
                    target_hints=target_hints,
                )
            elif normalized in {"safe_delete", "delete"}:
                result = manager.safe_delete(
                    relative_path,
                    resolved_line,
                    resolved_column,
                    apply=apply_edit,
                    validate=validate,
                    allow_public_api_delete=allow_public_api_delete,
                    target_hints=target_hints,
                )
            elif normalized == "move_top_level_type":
                result = manager.move_top_level_type(
                    relative_path,
                    resolved_line,
                    resolved_column,
                    target_package,
                    apply=apply_edit,
                    validate=validate,
                    target_hints=target_hints,
                )
            elif normalized == "inline_local_variable":
                result = manager.inline_local_variable(
                    relative_path,
                    resolved_line,
                    resolved_column,
                    apply=apply_edit,
                    validate=validate,
                    target_hints=target_hints,
                )
            elif normalized == "inline_constant":
                result = manager.inline_constant(
                    relative_path,
                    resolved_line,
                    resolved_column,
                    apply=apply_edit,
                    validate=validate,
                    allow_public_api=allow_public_api,
                    target_hints=target_hints,
                )
            else:
                result = {
                    "accepted": False,
                    "refusal": {
                        "code": "unknown_java_refactor_operation",
                        "message": "Unknown Java refactor operation: " + operation,
                    },
                }
        except Exception:
            if fallback_to_lsp and normalized in {"semantic_rename", "rename"} and name_path is not None:
                status_message = self.create_ls_code_editor().rename_symbol(
                    name_path,
                    relative_path=relative_path,
                    new_name=new_name,
                )
                return status_message
            raise
        return self._finalize_result(result)


# The complete set of optional/beta Java compiler-backed refactoring tools. Tool visibility is gated on the active
# project's ``java_refactor.enabled`` flag at discovery time (see SerenaAgent tool-set computation), so these tools are
# exposed only for projects that opt in, rather than always-present and refusing at execution time.
JAVA_REFACTOR_TOOL_CLASSES = (
    JavaRefactorStatusTool,
    JavaRefactorSymbolTool,
    JavaSemanticRenameTool,
    JavaSafeDeleteTool,
    JavaMoveTopLevelTypeTool,
    JavaInlineLocalVariableTool,
    JavaInlineConstantTool,
)


def java_refactor_tool_names() -> list[str]:
    """Names of the optional Java refactoring tools, gated by ``java_refactor.enabled``."""
    return [cls.get_name_from_cls() for cls in JAVA_REFACTOR_TOOL_CLASSES]
