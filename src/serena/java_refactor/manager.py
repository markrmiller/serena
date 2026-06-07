import json
import os
import re
from collections import Counter
from importlib import resources
from pathlib import Path
from typing import TYPE_CHECKING

from serena.config.serena_config import JavaRefactorConfig, LanguageBackend
from serena.java_refactor.client import JavaRefactorClient
from serena.java_refactor.models import JavaRefactorInitializeParams, JavaRefactorStatus
from serena.java_refactor.workspace_edit import (
    RefactorWorkspaceEdit,
    StagedEdit,
    TransactionalWorkspaceEditApplier,
    WorkspaceEditError,
    WorkspaceEditPreview,
)
from solidlsp.ls_config import Language
from solidlsp.ls_types import SymbolKind

if TYPE_CHECKING:
    from serena.project import Project

# javac diagnostics are formatted "<path>:<line>:<col>: <message>"; dropping the :line:col lets a pre-existing
# diagnostic still match its baseline counterpart after an edit shifts line numbers.
_DIAGNOSTIC_LOCATION = re.compile(r":\d+:\d+:")

# Empty overlay: validating against current on-disk sources, used to capture the pre-edit diagnostic baseline.
_EMPTY_OVERLAY: dict = {"changedFiles": {}, "deletedFiles": [], "renamedFiles": []}


# Coarse identity categories the sidecar verifies against javac ElementKinds (TargetHints.kindMatches). Only
# confidently-mappable LSP kinds are listed: a missing hint verifies nothing, while a WRONG hint would refuse valid
# requests, so anything ambiguous simply sends no kind hint.
_SYMBOL_KIND_TO_SIDECAR_KIND: dict[SymbolKind, str] = {
    SymbolKind.Class: "type",
    SymbolKind.Interface: "type",
    SymbolKind.Enum: "type",
    SymbolKind.Struct: "type",
    SymbolKind.Method: "method",
    SymbolKind.Function: "method",
    SymbolKind.Constructor: "constructor",
    SymbolKind.Field: "field",
    SymbolKind.Property: "field",
    SymbolKind.Constant: "field",
    SymbolKind.EnumMember: "field",
    SymbolKind.Variable: "variable",
    SymbolKind.TypeParameter: "type_parameter",
    SymbolKind.Package: "package",
}


def _signature_arity(symbol_name: str) -> int | None:
    """The parameter count encoded in an LSP symbol name like ``foo(String, int)``, or None when absent.

    JDTLS document-symbol names carry the parameter list for methods/constructors. Commas inside generic arguments or
    nested groups (``Map<String, Integer>``) are not parameter separators, so counting is depth-aware.
    """
    open_index = symbol_name.find("(")
    close_index = symbol_name.rfind(")")
    if open_index < 0 or close_index <= open_index:
        return None
    inner = symbol_name[open_index + 1 : close_index].strip()
    if not inner:
        return 0
    depth = 0
    count = 1
    for character in inner:
        if character in "<([":
            depth += 1
        elif character in ">)]":
            depth -= 1
        elif character == "," and depth == 0:
            count += 1
    return count


def target_hints_from_lsp_symbol(symbol: object) -> dict:
    """Target-identity hints (``nameHint``/``kindHint``/``arityHint``) derived from a resolved LSP symbol.

    Serena targets Java refactorings by ``name_path`` and resolves them through the language server to a line/column;
    the sidecar re-resolves that POSITION with javac. These hints carry the selected symbol's identity across that
    lossy round-trip so the sidecar can prove it planned against the same element (and refuse with ``target_mismatch``
    otherwise — overloads, same-line siblings, enclosing declarations, parameter/field name collisions). Every hint is
    optional and derived conservatively: an underivable property is omitted rather than guessed.
    """
    hints: dict = {}
    raw_name = str(getattr(symbol, "name", "") or "")
    simple_name = raw_name.split("(", 1)[0].strip()
    if simple_name:
        hints["nameHint"] = simple_name
    arity = _signature_arity(raw_name)
    if arity is not None:
        hints["arityHint"] = arity
    try:
        kind = SymbolKind(int(getattr(symbol, "symbol_kind")))
    except (AttributeError, TypeError, ValueError):
        kind = None
    sidecar_kind = _SYMBOL_KIND_TO_SIDECAR_KIND.get(kind) if kind is not None else None
    if sidecar_kind is not None:
        hints["kindHint"] = sidecar_kind
    return hints


def _normalize_diagnostic(diagnostic: str) -> str:
    """Strips the :line:col location so a diagnostic is comparable across edits that shift line numbers."""
    return _DIAGNOSTIC_LOCATION.sub(":", diagnostic, count=1).strip()


def _new_diagnostics(baseline: list[str], staged: list[str]) -> list[str]:
    """Staged diagnostics not accounted for by the baseline (multiset difference by normalized text).

    Pre-existing diagnostics (even if their line number shifted) are tolerated; only genuinely new ones are returned.
    """
    baseline_counts = Counter(_normalize_diagnostic(d) for d in baseline)
    new_items: list[str] = []
    for diagnostic in staged:
        key = _normalize_diagnostic(diagnostic)
        if baseline_counts.get(key, 0) > 0:
            baseline_counts[key] -= 1
        else:
            new_items.append(diagnostic)
    return new_items


class JavaRefactorRuntimeError(RuntimeError):
    """Runtime error raised when the Java refactoring sidecar cannot be used."""


class RenameBaselineError(Exception):
    """Raised when the rename reference-site baseline cannot be captured from a ``scanReferences`` response.

    Covers a scan that raises, is not accepted, or returns malformed/no reference spans. On the apply path this is a
    hard, fail-closed condition: without a trustworthy pre-edit baseline the old-key residual verification cannot run,
    so the apply must refuse before any mutation rather than proceed unverified.
    """


class ValidationRefusedError(Exception):
    """Raised when a ``validateEdit`` response cannot be trusted: refused, malformed, or the request itself failed.

    The sidecar's ``validateEdit`` returns ``accepted: true`` with ``compilerErrors`` only on its happy path; a refusal
    (``not_initialized``, ``project_model_errors``, ``malformed_overlay``, ...) carries NO compiler errors at all.
    Extracting errors from such a response would therefore read as "no blocking errors" and silently skip the V1
    validation gate. Every validateEdit consumer must gate on acceptance and convert this exception into a structured
    blocking refusal (apply paths) or a not-ready report (preview validation).
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class JavaRefactorManager:
    """Lifecycle manager for Serena's optional Java refactoring sidecar."""

    ENV_JAR = "SERENA_JAVA_REFACTOR_JAR"
    REPO_JAR_GLOB = "java-refactor/build/libs/serena-java-refactor*.jar"
    BUNDLED_JAR_RESOURCE = "java-refactor/serena-java-refactor.jar"

    def __init__(
        self,
        project_root: str,
        language_backend: LanguageBackend,
        languages: list[Language],
        java_refactor_config: JavaRefactorConfig | None = None,
        repo_root: str | Path | None = None,
        jdtls_settings: dict | None = None,
        project_data_dir: str | Path | None = None,
        project_encoding: str | None = None,
        project_line_ending: str | None = None,
    ) -> None:
        """
        :param project_root: active Serena project root
        :param language_backend: fixed Serena session language backend
        :param languages: active Serena project languages
        :param repo_root: Serena's own checkout root, used to locate a locally built sidecar jar during development.
            Defaults to the Serena package root inferred from this module's location.
        :param jdtls_settings: Serena's Java language-server settings (the ``ls_specific_settings["java"]`` entry, e.g.
            ``maven_user_settings``/``gradle_user_home``/``gradle_java_home``/``gradle_wrapper_enabled``). When
            ``java_refactor.use_jdtls_settings`` is true these are forwarded to the sidecar's build-model discovery so
            extraction matches the language server. Defaults to an empty mapping.
        """
        self._project_root = Path(project_root)
        self._language_backend = language_backend
        self._languages = languages
        self._config = java_refactor_config or JavaRefactorConfig()
        # General Serena project encoding/line-ending, used as the source-of-truth for reading/writing edits when the
        # java_refactor subsection does not override them (transaction plan: preserve project encoding and line endings).
        self._project_encoding = project_encoding
        self._project_line_ending = project_line_ending
        self._jdtls_settings = jdtls_settings or {}
        self._project_data_dir = Path(project_data_dir) if project_data_dir is not None else None
        self._repo_root = Path(repo_root) if repo_root is not None else self._default_repo_root()
        self._client: JavaRefactorClient | None = None
        self._initialization_error: str | None = None

    @staticmethod
    def _default_repo_root() -> Path:
        """Infers Serena's checkout root from this module location (src/serena/java_refactor/manager.py)."""
        return Path(__file__).resolve().parents[3]

    def get_status(self, refresh: bool = False) -> JavaRefactorStatus:
        """Starts or refreshes the sidecar and returns readiness/errors."""
        try:
            self._validate_supported_project()
            client = self._get_or_start_client(refresh=refresh)
            if refresh:
                return client.status(refresh=True)
            return client.status(refresh=False)
        except Exception as e:
            self._initialization_error = str(e)
            resolved_jar = self._resolve_sidecar_jar_or_none()
            jar_path = str(resolved_jar) if resolved_jar is not None else None
            return JavaRefactorStatus.unavailable(str(e), jar_path=jar_path)

    def shutdown(self, timeout: float = 2.0) -> None:
        """Stops the sidecar if it has been started."""
        if self._client is not None:
            self._client.shutdown(timeout=timeout)
            self._client = None

    def semantic_rename(
        self,
        relative_path: str,
        line: int,
        column: int,
        new_name: str,
        apply: bool = False,
        validate: bool | None = None,
        include_javadocs: bool | None = None,
        include_comments: bool | None = None,
        target_hints: dict | None = None,
    ) -> dict:
        """Previews or applies a compiler-backed Java semantic rename.

        ``include_javadocs``/``include_comments`` opt the rename into updating Javadoc references and plain-comment/string
        occurrences of the old name. When ``None`` the project-config defaults (``include_javadocs``/``include_comments``)
        apply. ``target_hints`` carries the selected symbol's identity (``nameHint``/``kindHint``/``arityHint``, see
        :func:`target_hints_from_lsp_symbol`); the sidecar refuses to plan when the position resolves to a different
        element than the hints describe.
        """
        params: dict = {"relativePath": relative_path, "line": line, "column": column, "newName": new_name}
        params.update(self._hint_params(target_hints))
        params["includeJavadocs"] = self._config.include_javadocs if include_javadocs is None else include_javadocs
        params["includeComments"] = self._config.include_comments if include_comments is None else include_comments
        return self._preview_or_apply_refactor(
            operation="semanticRename",
            params=params,
            apply=apply,
            validate=validate,
        )

    def safe_delete(
        self,
        relative_path: str,
        line: int,
        column: int,
        allow_public_api_delete: bool = False,
        apply: bool = False,
        validate: bool | None = None,
        target_hints: dict | None = None,
    ) -> dict:
        """Previews or applies a strict compiler-backed Java safe delete."""
        params: dict = {"relativePath": relative_path, "line": line, "column": column, "allowPublicApi": allow_public_api_delete}
        params.update(self._hint_params(target_hints))
        return self._preview_or_apply_refactor(
            operation="safeDelete",
            params=params,
            apply=apply,
            validate=validate,
        )

    def move_top_level_type(
        self,
        relative_path: str,
        line: int,
        column: int,
        target_package: str | None = None,
        target_directory: str | None = None,
        apply: bool = False,
        validate: bool | None = None,
        target_hints: dict | None = None,
    ) -> dict:
        """Previews or applies a compiler-backed Java top-level type move.

        Exactly one of ``target_package`` or ``target_directory`` must be provided. ``target_directory`` is a path
        relative to the project root; its destination package is derived from the source root that contains it.
        """
        if (target_package is None) == (target_directory is None):
            return {
                "accepted": False,
                "applied": False,
                "operation": "moveTopLevelType",
                "mode": "apply" if apply else "preview",
                "refusal": {
                    "code": "ambiguous_move_target",
                    "message": "Move requires exactly one of target_package or target_directory.",
                },
            }
        params: dict = {"relativePath": relative_path, "line": line, "column": column}
        params.update(self._hint_params(target_hints))
        if target_package is not None:
            params["targetPackage"] = target_package
        else:
            params["targetDirectory"] = target_directory
        return self._preview_or_apply_refactor(
            operation="moveTopLevelType",
            params=params,
            apply=apply,
            validate=validate,
        )

    def inline_local_variable(
        self,
        relative_path: str,
        line: int,
        column: int,
        apply: bool = False,
        validate: bool | None = None,
        target_hints: dict | None = None,
    ) -> dict:
        """Previews or applies conservative Java local variable inline."""
        params: dict = {"relativePath": relative_path, "line": line, "column": column}
        params.update(self._hint_params(target_hints))
        return self._preview_or_apply_refactor(
            operation="inlineLocalVariable",
            params=params,
            apply=apply,
            validate=validate,
        )

    def inline_constant(
        self,
        relative_path: str,
        line: int,
        column: int,
        apply: bool = False,
        validate: bool | None = None,
        allow_public_api: bool = False,
        target_hints: dict | None = None,
    ) -> dict:
        """Previews or applies conservative Java constant inline.

        A private compile-time constant has its usages inlined and its declaration removed. A non-private constant
        previews usage replacements while keeping its declaration; applying those edits requires ``allow_public_api``
        because the declaration may be public API or a reflection target.
        """
        params: dict = {"relativePath": relative_path, "line": line, "column": column, "allowPublicApi": allow_public_api}
        params.update(self._hint_params(target_hints))
        return self._preview_or_apply_refactor(
            operation="inlineConstant",
            params=params,
            apply=apply,
            validate=validate,
        )

    @staticmethod
    def _hint_params(target_hints: dict | None) -> dict:
        """The protocol fields for caller-supplied target-identity hints (``nameHint``/``kindHint``/``arityHint``)."""
        if not target_hints:
            return {}
        return {key: value for key, value in target_hints.items() if key in ("nameHint", "kindHint", "arityHint") and value is not None}

    def _preview_or_apply_refactor(self, operation: str, params: dict, apply: bool, validate: bool | None = None) -> dict:
        """Previews or applies one Java refactoring workspace edit.

        :param validate: governs PREVIEW-time validation reporting only (whether a preview also runs the staged
            in-memory javac validation and surfaces it under ``previewValidation``). It has NO effect on the apply
            safety gate: an apply ALWAYS runs post-commit validation with rollback and (for rename) old-key residual
            verification, regardless of ``validate`` or any config knob (G001). Staged pre-commit javac validation
            runs on apply unless the project's ``validate_before_apply`` config disables it (post-commit validation
            still rolls a broken commit back in that case).
        """
        if not self._config.enabled:
            return {
                "accepted": False,
                "applied": False,
                "operation": operation,
                "mode": "apply" if apply else "preview",
                "refusal": {
                    "code": "java_refactor_disabled",
                    "message": "Java refactoring is disabled for this project. Set java_refactor.enabled: true in the "
                    "project configuration to enable compiler-backed Java refactoring tools.",
                },
            }
        self._validate_supported_project()
        client = self._get_or_start_client(refresh=False)

        if apply:
            # Apply-path safety gate (H3): a project model produced via conventional fallback (extraction failed) has no
            # resolved classpath, and a model whose compiler errors were suppressed by allow_incomplete_analysis was not
            # actually validated. Either way an apply could commit edits that do not compile against the true classpath,
            # so we refuse before mutating the workspace. Preview stays permissive (it writes nothing).
            degraded_refusal = self._degraded_model_apply_refusal(client, operation)
            if degraded_refusal is not None:
                return degraded_refusal

        # Old-key post-validation baseline (rename only): capture the source sites that reference the target BEFORE the
        # edit. After a complete rename every one of those sites references the NEW name, so re-scanning the declaration
        # must find the same number of references; a missed reference (the old key left referenced from source) shrinks
        # the post-edit set and forces a rollback. Captured while the project is still pristine.
        #
        # G001 (non-bypassable apply gate): the baseline is captured for EVERY rename apply — it is NOT gated by the
        # per-call ``validate`` flag or any config knob. The rename old-key residual
        # verification is part of the mandatory apply safety gate and must always be able to run. Failure to capture
        # the baseline (scan raises, is not accepted, or yields malformed/no spans) is therefore a hard refusal BEFORE
        # any mutation: an apply without a trustworthy baseline could commit a rename whose old key silently survives.
        rename_ref_baseline = None
        if apply and operation == "semanticRename":
            try:
                rename_ref_baseline = self._reference_site_summary(client, params)
            except RenameBaselineError as error:
                return {
                    "accepted": False,
                    "applied": False,
                    "operation": operation,
                    "mode": "apply",
                    "refusal": {
                        "code": "rename_baseline_unavailable",
                        "message": "Rename was refused before any edit was planned or applied because the pre-apply "
                        f"reference-site baseline for old-key verification could not be captured: {error}. No files "
                        "were written.",
                    },
                }

        result = client.apply_refactor(operation, params) if apply else client.preview(operation, params)
        if not result.get("accepted"):
            return result

        # Fail closed on a malformed sidecar response: parsing rejects (among others) any text-edit group or
        # destructive file operation that lacks its oldSha256 precondition, so a buggy or tampered payload can never
        # reach staging/apply with the optimistic-concurrency hash check silently skipped.
        try:
            workspace_edit = RefactorWorkspaceEdit.from_protocol_dict(result["workspaceEdit"])
        except (WorkspaceEditError, KeyError, TypeError, ValueError) as error:
            result["accepted"] = False
            result["applied"] = False
            result["refusal"] = {
                "code": "malformed_workspace_edit",
                "message": f"The sidecar returned a malformed workspace edit, so nothing was staged or applied: {error}",
            }
            return result
        applier = TransactionalWorkspaceEditApplier(
            self._project_root, encoding=self._source_encoding(), line_ending=self._project_line_ending
        )
        if not apply:
            # G003: preview runs the SAME in-memory staging/safety pipeline as apply, committing nothing. stage()
            # performs path-in-root checks, hash verification, overlap rejection, descending-order application, and
            # exact in-memory materialization of every text edit and file operation; an edit that cannot be staged
            # exactly (out-of-range offsets, missing target, rename/create conflicts, UTF-16 boundary errors, invalid
            # operation sequencing) makes preview REFUSE rather than display an edit that apply would reject.
            try:
                staged = applier.stage(workspace_edit)
            except WorkspaceEditError as error:
                result["accepted"] = False
                result["applied"] = False
                result["refusal"] = {
                    "code": "preview_unsafe_edit",
                    "message": f"Preview refused because the planned edit could not be staged exactly (no files were written): {error}",
                }
                return result
            self._attach_preview(result, staged.preview, applied=False)
            # G002: real validate_after_preview — run the staged overlay through javac IN MEMORY (no commit) and report
            # the outcome under `previewValidation`, kept distinct from apply-time `preValidation`/`postValidation`.
            validate_after_preview = self._config.validate_after_preview if validate is None else validate
            if validate_after_preview:
                result["previewValidation"] = self._staged_validation_report(client, staged)
            return result

        # Stage the edit fully in memory so we can validate the post-edit sources BEFORE committing anything to disk.
        # Staging enforces the workspace-edit preconditions (path-in-root, content-hash match, exact spans, no
        # overlaps, create/rename target conflicts, consistent offset encoding); a violation is a structured safety
        # refusal of the apply, never a leaked exception.
        try:
            staged = applier.stage(workspace_edit)
        except WorkspaceEditError as error:
            result["accepted"] = False
            result["applied"] = False
            result["editsAlreadyApplied"] = False
            result["refusal"] = {
                "code": "apply_unsafe_edit",
                "message": f"Apply was refused because the planned edit could not be staged exactly (no files were written): {error}",
            }
            return result

        # G001 (non-bypassable apply gate): post-commit validation/rollback ALWAYS runs on apply and does NOT derive
        # from the per-call ``validate`` flag or any config knob. The ``validate_before_apply`` config controls ONLY
        # the staged pre-commit javac validation step below: disabling it trades the early nothing-written refusal
        # for a commit that post-validation will still roll back if the edit breaks compilation. A caller can never
        # request ``apply=True, validate=False`` to commit an unvalidated, compiler-breaking, or semantically
        # incomplete refactor: the per-call ``validate`` flag and ``validate_after_preview`` config govern only
        # preview-time reporting (see the preview branch above).
        validate_before_apply = self._config.validate_before_apply

        # Pre-edit diagnostic baseline, captured before any commit so both the pre-commit and post-apply guards can
        # distinguish edit-introduced errors from problems that already existed (the basis of allow_incomplete_analysis).
        baseline_errors: list[str] = []
        if validate_before_apply or self._config.allow_incomplete_analysis:
            try:
                baseline_errors = self._compiler_errors(self._checked_validate_edit(client, _EMPTY_OVERLAY))
            except ValidationRefusedError as error:
                return self._validation_refused_apply_result(result, error, stage="baseline")

        if validate_before_apply:
            # Diff the staged overlay's `compilerErrors` (real, unsuppressed javac errors) against the baseline. Complete
            # mode rejects ALL staged errors; allow_incomplete_analysis (opt-in) tolerates pre-existing diagnostics and
            # rejects only the errors the edit newly introduced. Nothing is written when this refuses.
            try:
                staged_errors = self._compiler_errors(self._checked_validate_edit(client, staged.overlay()))
            except ValidationRefusedError as error:
                return self._validation_refused_apply_result(result, error, stage="staged pre-commit")
            new_errors = _new_diagnostics(baseline_errors, staged_errors)
            blocking = new_errors if self._config.allow_incomplete_analysis else staged_errors
            if blocking:
                new_set = Counter(_normalize_diagnostic(e) for e in new_errors)
                pre_existing = [e for e in staged_errors if new_set.get(_normalize_diagnostic(e), 0) == 0]
                result["accepted"] = False
                result["applied"] = False
                result["editsAlreadyApplied"] = False
                result["preValidation"] = {
                    "ready": False,
                    "errors": blocking,
                    "newErrors": new_errors,
                    "preExistingErrors": pre_existing,
                }
                detail = "newly introduced compiler errors" if self._config.allow_incomplete_analysis else "compiler errors"
                result["refusal"] = {
                    "code": "pre_apply_validation_failed",
                    "message": f"Edits were not applied because staged javac pre-validation found {detail} (no files "
                    "were written):\n" + "\n".join(blocking),
                }
                return result

        preview = staged.preview
        self._attach_preview(result, preview, applied=False)

        # Capture the pre-apply state so a semantically invalid edit (javac post-validation failure) can be rolled back.
        # A snapshot failure happens BEFORE any write, so it is a clean refusal; without it the semantic rollback
        # guarantee below could not be honored.
        try:
            snapshot = applier.snapshot(workspace_edit)
        except (WorkspaceEditError, OSError) as error:
            result["accepted"] = False
            result["applied"] = False
            result["editsAlreadyApplied"] = False
            result["refusal"] = {
                "code": "apply_snapshot_failed",
                "message": "Apply was refused because the pre-apply rollback snapshot could not be captured "
                f"(no files were written): {error}",
            }
            return result
        try:
            applier.commit(staged)
        except WorkspaceEditError as error:
            # The applier's commit is transactional: on a failed write it restores the captured backups before raising,
            # so the workspace is back at its pre-apply state.
            result["accepted"] = False
            result["applied"] = False
            result["editsAlreadyApplied"] = False
            result["rolledBack"] = True
            result["refusal"] = {
                "code": "apply_commit_failed",
                "message": f"Apply failed while committing the edit; the original file contents were restored: {error}",
            }
            return result
        except Exception as error:
            # The commit failed AND restoring the backups also failed (a WorkspaceEditError would have been raised had
            # the restore succeeded). Surface this as a structured result that is explicit about the workspace state
            # instead of leaking the exception.
            result["accepted"] = False
            result["applied"] = False
            result["editsAlreadyApplied"] = True
            result["rolledBack"] = False
            result["refusal"] = {
                "code": "apply_commit_failed",
                "message": "Apply failed while committing the edit and the original file contents could NOT be fully "
                f"restored; the workspace may contain partially applied edits: {error}",
            }
            return result
        self._attach_preview(result, preview, applied=True)

        # Post-commit validation with rollback is part of the non-bypassable apply safety gate: it runs on EVERY apply,
        # independent of the per-call ``validate`` flag and of ``validate_before_apply``.
        post_status = client.status(refresh=True)
        result["postValidation"] = {"ready": post_status.ready, "errors": post_status.errors}
        post_failure_errors: list[str] = list(post_status.errors) if not post_status.ready else []
        if self._config.allow_incomplete_analysis:
            # status.ready is permissive under incomplete analysis (errors surface as warnings), so re-validate the
            # now-committed on-disk sources and roll back only if the edit introduced errors beyond the baseline.
            # A refused/failed revalidation fails closed: the committed edit's validity is unknown, so it is treated
            # as a post-validation failure and rolled back rather than left in place unverified.
            try:
                post_validation = self._compiler_errors(self._checked_validate_edit(client, _EMPTY_OVERLAY))
                post_new_errors = _new_diagnostics(baseline_errors, post_validation)
            except ValidationRefusedError as error:
                post_new_errors = [f"post-apply javac revalidation was refused by the sidecar: [{error.code}] {error.message}"]
            result["postValidation"]["newErrors"] = post_new_errors
            post_failure_errors = post_new_errors
        if post_failure_errors:
            # The file I/O succeeded, but javac no longer validates the project. Roll the workspace back to its
            # pre-apply snapshot so apply is transactional for both I/O *and* semantic validity.
            applier.restore(snapshot)
            client.status(refresh=True)
            result["accepted"] = False
            result["applied"] = False
            result["editsAlreadyApplied"] = False
            result["rolledBack"] = True
            result["refusal"] = {
                "code": "post_validation_failed",
                "message": "Edits were rolled back because javac post-validation failed:\n" + "\n".join(post_failure_errors),
            }

        # Rename old-key post-validation: confirm no source reference to the old key survives. Runs for BOTH text-only
        # renames AND top-level type renames that move the declaration file — the latter are the highest-risk cases
        # (constructors, static imports, FQNs, cross-file type references), so the declaration position is remapped to
        # the renamed file rather than skipping the check. Only runs when the apply actually committed (not rolled back).
        if rename_ref_baseline is not None and not result.get("rolledBack"):
            requested_new_name = str(params.get("newName") or "")
            # Primary check: prove every AST node that resolved to the old key was rewritten EXACTLY — each baseline
            # reference span must be fully covered by an edit whose replacement is precisely the requested new name
            # (span overlap alone could leave part of the old identifier in place), and the staged post-edit content
            # at every remapped site must actually read the new name. Uncovered/stale sites are reported as concrete
            # locations (replaces the old count-only heuristic, which missed same-count rebindings).
            uncovered = self._uncovered_reference_sites(rename_ref_baseline, result.get("workspaceEdit") or {}, requested_new_name)
            if not uncovered:
                uncovered = self._stale_rewritten_sites(rename_ref_baseline, result.get("workspaceEdit") or {}, staged, requested_new_name)
            residual: str | None
            if uncovered:
                residual = self._format_uncovered_residual(rename_ref_baseline.get("name"), uncovered)
            else:
                # Secondary, fail-closed guard: re-resolve the renamed declaration and confirm it now carries the NEW
                # name and that its post-edit reference set did not shrink (catches a reference that rebinds to a
                # same-named symbol so its span shifted out of the new symbol's reference set), and that the
                # declaration still resolves at all.
                post_params = self._post_rename_scan_params(params, result)
                residual = self._rename_old_key_residual(client, post_params, rename_ref_baseline, requested_new_name)
            if residual is not None:
                applier.restore(snapshot)
                client.status(refresh=True)
                result["accepted"] = False
                result["applied"] = False
                result["editsAlreadyApplied"] = False
                result["rolledBack"] = True
                result["refusal"] = {"code": "rename_old_key_residual", "message": residual}
        return result

    @staticmethod
    def _post_rename_scan_params(params: dict, result: dict) -> dict:
        """Returns the params for re-resolving the renamed declaration AFTER apply.

        For a text-only rename the declaration stays put, so the original position is returned unchanged. For a top-level
        type rename the declaration file was moved by a rename file operation; the line/column of the type name are
        stable (only the name text and the file path change), so the relative path is remapped to the operation's new
        path while the line/column are preserved. This lets the old-key residual re-scan address the declaration in its
        new location instead of failing to find the (now-moved) old file.
        """
        new_params = dict(params)
        declaration_path = params.get("relativePath")
        for operation in (result.get("workspaceEdit") or {}).get("fileOperations") or []:
            if operation.get("kind") == "rename" and operation.get("oldPath") == declaration_path:
                new_path = operation.get("newPath")
                if new_path:
                    new_params["relativePath"] = new_path
                break
        return new_params

    @staticmethod
    def _reference_site_summary(client: JavaRefactorClient, params: dict) -> dict:
        """Per-symbol reference data for the rename target; raises :class:`RenameBaselineError` if it cannot be captured.

        Returns ``{"total": int, "byFile": Counter, "sites": list, "name": str}`` where ``sites`` carries each
        resolved reference's pre-edit character span (``relativePath``/``startOffset``/``endOffset``/``line``/
        ``column``/``text``). This is the pre-edit canonical-key data the old-key residual check consumes: after the
        rename every one of these AST nodes must have been rewritten, so any site NOT covered by a rewrite edit is a
        concrete location that still resolves to the old key.

        Fail-closed contract: a scan that raises, is not accepted, or yields malformed/no spans raises
        :class:`RenameBaselineError` instead of degrading to a partial summary. The real sidecar always reports every
        reference (including the declaration identifier) with a full span, so a resolvable rename target never
        legitimately produces an empty or span-less reference set.
        """
        try:
            # Forward the full target identity (name/kind/arity), not name alone, so the baseline scan's target
            # resolution is gated against the same semantic element the rename plans against. Resolving on name alone
            # would let the baseline bind to a same-name sibling (overload, same-name field/parameter), weakening the
            # old-key residual check below the operation it guards.
            scan = client.scan_references(
                params["relativePath"],
                params["line"],
                params["column"],
                name_hint=params.get("nameHint"),
                kind_hint=params.get("kindHint"),
                arity_hint=params.get("arityHint"),
            )
        except (JavaRefactorRuntimeError, RuntimeError, FileNotFoundError, AttributeError) as error:
            raise RenameBaselineError(f"the reference scan failed: {error}") from error
        if not isinstance(scan, dict):
            raise RenameBaselineError("the reference scan returned a malformed response")
        if not scan.get("accepted"):
            scan_refusal = scan.get("refusal")
            refusal = scan_refusal if isinstance(scan_refusal, dict) else {}
            detail = refusal.get("message") or refusal.get("code")
            raise RenameBaselineError("the reference scan was not accepted" + (f": {detail}" if detail else ""))
        references = scan.get("references")
        if not isinstance(references, list) or not references:
            raise RenameBaselineError("the reference scan returned no reference spans for the rename target")
        by_file: Counter = Counter()
        sites: list[dict] = []
        for reference in references:
            if not isinstance(reference, dict):
                raise RenameBaselineError("the reference scan returned a malformed reference entry")
            path = reference.get("relativePath") or reference.get("file") or ""
            start = reference.get("startOffset")
            end = reference.get("endOffset")
            if (
                not path
                or not isinstance(start, int)
                or not isinstance(end, int)
                or isinstance(start, bool)
                or isinstance(end, bool)
                or start < 0
                or end <= start
            ):
                raise RenameBaselineError(
                    f"the reference scan returned a reference without a usable span (path={path!r}, "
                    f"startOffset={start!r}, endOffset={end!r})"
                )
            by_file[path] += 1
            sites.append(
                {
                    "relativePath": path,
                    "startOffset": start,
                    "endOffset": end,
                    "line": reference.get("line"),
                    "column": reference.get("column"),
                    "text": reference.get("text"),
                }
            )
        target = scan.get("target") or {}
        # The real sidecar reports the resolved symbol under target.semanticKey (kind/owner/name/signature/canonical);
        # synthetic/older shapes carry a flat name or a "key" object. The name is qualified for types, simple otherwise.
        name = target.get("name") or (target.get("key") or {}).get("name") or (target.get("semanticKey") or {}).get("name")
        return {"total": sum(by_file.values()), "byFile": by_file, "sites": sites, "name": name}

    @staticmethod
    def _rename_edits_by_file(workspace_edit: dict) -> dict[str, list[tuple[int, int, str]]]:
        """The rename's text edits grouped per file as ``(startOffset, endOffset, newText)``, sorted by span."""
        edits_by_file: dict[str, list[tuple[int, int, str]]] = {}
        for change in workspace_edit.get("changes", []) or []:
            path = str(change.get("path") or "")
            for edit in change.get("edits", []) or []:
                start = edit.get("startOffset")
                end = edit.get("endOffset")
                if start is None or end is None:
                    continue
                edits_by_file.setdefault(path, []).append((start, end, str(edit.get("newText", ""))))
        for edits in edits_by_file.values():
            edits.sort(key=lambda item: (item[0], item[1]))
        return edits_by_file

    @classmethod
    def _uncovered_reference_sites(cls, baseline: dict, workspace_edit: dict, new_name: str) -> list[dict]:
        """Pre-edit reference sites NOT exactly rewritten to ``new_name`` by the rename's workspace edit.

        The rename planner rewrites every semantic reference (including the declaration identifier and constructor
        declarations of a renamed type) by replacing exactly the identifier span with the new name; the file rename
        operation of a top-level type rename carries no identifier text — the DECLARATION text edit does. A baseline
        site therefore counts as covered only when some text edit in the same file FULLY covers its span AND its
        replacement text is exactly the requested new name. Mere span overlap is NOT proof of replacement: an edit
        could intersect the identifier yet replace the wrong subrange or substitute different text, leaving an AST
        node that still resolves to the old key — a stale reference that, in the worst case, silently rebinds to an
        inherited/member/local symbol and still compiles. The V1 ``changes[].edits`` and the pre-edit reference
        ``sites`` are both expressed in pre-edit UTF-16 character offsets, so spans compare directly.

        The V1 edit model keys every edit (including those in a renamed declaration file) under the file's CURRENT path,
        which is exactly the path the pre-edit scan reports, so no old->new remap is needed for coverage matching.
        """
        edits_by_file = cls._rename_edits_by_file(workspace_edit)
        uncovered: list[dict] = []
        for site in baseline.get("sites", []) or []:
            start = site.get("startOffset")
            end = site.get("endOffset")
            if start is None or end is None:
                # Without a precise span this site's coverage cannot be computed, so defer it to the secondary
                # count-based check rather than guessing. The real sidecar always reports spans, so this only affects
                # synthetic inputs that supply reference counts without locations.
                continue
            site_path = str(site.get("relativePath") or "")
            spans = edits_by_file.get(site_path, [])
            covered = any(
                edit_start <= start and end <= edit_end and replacement == new_name for edit_start, edit_end, replacement in spans
            )
            if not covered:
                uncovered.append(site)
        return uncovered

    @classmethod
    def _stale_rewritten_sites(cls, baseline: dict, workspace_edit: dict, staged: "StagedEdit", new_name: str) -> list[dict]:
        """Covered baseline sites whose POST-EDIT text is not exactly the new name, verified against staged content.

        Coverage (:meth:`_uncovered_reference_sites`) proves an exact-replacement edit exists in the PLAN; this proves
        the staging pipeline actually MATERIALIZED the new name at every baseline site: each site's covering edit is
        remapped through the offset deltas of the file's preceding edits, and the staged post-edit content at the
        remapped span must read exactly ``new_name``. The staged bytes are byte-identical to what commit writes, so
        checking them is equivalent to re-reading the workspace after apply (and works for renamed declaration files,
        whose post-edit content lives under the NEW path). All offsets are UTF-16 code units — the sidecar's offset
        encoding and the applier's splice space — so spans are remapped and sliced in UTF-16 space.
        """
        edits_by_file = cls._rename_edits_by_file(workspace_edit)
        new_path_by_old = {entry["oldPath"]: entry["newPath"] for entry in staged.renamed_files}
        new_name_units = cls._utf16_length(new_name)
        stale: list[dict] = []
        for site in baseline.get("sites", []) or []:
            start = site.get("startOffset")
            end = site.get("endOffset")
            if start is None or end is None:
                continue
            site_path = str(site.get("relativePath") or "")
            edits = edits_by_file.get(site_path, [])
            covering = next(
                (edit for edit in edits if edit[0] <= start and end <= edit[1] and edit[2] == new_name),
                None,
            )
            if covering is None:
                continue  # already reported as uncovered by the coverage check
            content = staged.changed_files.get(new_path_by_old.get(site_path, site_path))
            if content is None:
                # The post-edit content for a file the plan rewrote is missing from staging: fail closed.
                stale.append(site)
                continue
            # Edits within a file never overlap (the applier rejects overlap), so the covering edit's post-edit start
            # is its pre-edit start shifted by the length deltas of every edit that ends at or before it.
            delta = sum(
                cls._utf16_length(text) - (edit_end - edit_start) for edit_start, edit_end, text in edits if edit_end <= covering[0]
            )
            new_start = covering[0] + delta
            units = content.encode("utf-16-le")
            segment = units[new_start * 2 : (new_start + new_name_units) * 2].decode("utf-16-le", errors="replace")
            if segment != new_name:
                stale.append(site)
        return stale

    @staticmethod
    def _utf16_length(text: str) -> int:
        """The length of a string in UTF-16 code units (the sidecar's character-offset encoding)."""
        return len(text.encode("utf-16-le")) // 2

    @staticmethod
    def _simple_symbol_name(name: str) -> str:
        """The simple identifier of a possibly qualified and/or signature-carrying semantic-key name."""
        base = name.split("(", 1)[0]
        for separator in (".", "#", "$"):
            base = base.rsplit(separator, 1)[-1]
        return base

    @staticmethod
    def _format_uncovered_residual(name: str | None, uncovered: list[dict]) -> str:
        """A refusal message naming the concrete source locations that still reference the old key after the rename."""
        locations = ", ".join(f"{site.get('relativePath')}:{site.get('line')}:{site.get('column')}" for site in uncovered[:10])
        more = "" if len(uncovered) <= 10 else f" (+{len(uncovered) - 10} more)"
        symbol = f" to '{name}'" if name else ""
        return (
            f"Rename left {len(uncovered)} reference(s){symbol} without an exact rewrite to the new name, so an AST "
            f"node still resolves to the old key (it may silently rebind to an inherited, member, or local symbol and "
            f"still compile). Stale location(s): {locations}{more}. The edit was rolled back."
        )

    def _rename_old_key_residual(self, client: JavaRefactorClient, params: dict, baseline: dict, new_name: str) -> str | None:
        """Returns a refusal message when re-resolving the renamed declaration does not prove the rename completed.

        After a complete rename, re-resolving the declaration position (remapped to the renamed file for a top-level
        type rename that moved its declaration file) yields the NEW symbol: its simple name must be the requested new
        name (anything else means the position re-resolved to a different — possibly the old — symbol), and its source
        references must be at least the sites that referenced the old symbol. A shortfall means a reference still
        carries the old key (it was not rewritten or silently rebound), so the rename is incomplete and must be rolled
        back. If the position can no longer be resolved at all, the check fails closed (rollback) rather than assuming
        success.
        """
        try:
            after = self._reference_site_summary(client, params)
        except RenameBaselineError as error:
            return (
                "Rename old-key verification could not re-scan references after applying; refusing to leave a possibly "
                f"incomplete rename in place: {error}"
            )
        after_name = after.get("name")
        if after_name is not None and self._simple_symbol_name(str(after_name)) != new_name:
            return (
                f"Rename old-key verification re-resolved the declaration position to symbol '{after_name}' instead of "
                f"the requested new name '{new_name}', so the rename did not take effect at the declaration (the old "
                "key may still be live). The edit was rolled back."
            )
        if after["total"] < baseline["total"]:
            return (
                f"Rename left the old symbol referenced from source: {baseline['total']} reference(s) existed before the "
                f"rename but only {after['total']} reference the new name afterwards. The unrewritten reference(s) still "
                "bind to the old key, so the edit was rolled back."
            )
        return None

    def _staged_validation_report(self, client: JavaRefactorClient, staged: "StagedEdit") -> dict:
        """Runs the staged (in-memory, uncommitted) javac validation for a preview and returns a distinct report.

        Mirrors the apply-path pre-commit validation (diff staged compiler errors against the pre-edit baseline; under
        ``allow_incomplete_analysis`` only newly introduced errors block) but writes nothing and is surfaced under the
        preview-specific ``previewValidation`` key so callers can tell preview validation apart from apply-time
        ``preValidation``/``postValidation``.

        A refused validateEdit (sidecar not initialized, project-model errors, malformed overlay, request failure)
        fails closed in the report: it is surfaced as ``ready: false`` with the sidecar refusal code/message, never as
        an empty (and therefore clean-looking) error list — the same acceptance gate the apply path enforces.
        """
        try:
            baseline_errors = self._compiler_errors(self._checked_validate_edit(client, _EMPTY_OVERLAY))
            staged_validation = self._checked_validate_edit(client, staged.overlay())
        except ValidationRefusedError as error:
            return {
                "ready": False,
                "errors": [f"javac validation was refused by the sidecar: [{error.code}] {error.message}"],
                "newErrors": [],
                "preExistingErrors": [],
                "warnings": [],
                "refusal": {"code": error.code, "message": error.message},
            }
        staged_errors = self._compiler_errors(staged_validation)
        new_errors = _new_diagnostics(baseline_errors, staged_errors)
        blocking = new_errors if self._config.allow_incomplete_analysis else staged_errors
        return {
            "ready": not blocking,
            "errors": blocking,
            "newErrors": new_errors,
            "preExistingErrors": [e for e in staged_errors if e not in new_errors],
            "warnings": list(staged_validation.get("warnings") or []),
        }

    @staticmethod
    def _checked_validate_edit(client: JavaRefactorClient, overlay: dict) -> dict:
        """Runs ``validateEdit`` and returns the response only when the sidecar ACCEPTED the validation request.

        Gate for every validateEdit consumer: a refused response carries no ``compilerErrors``, so reading errors out
        of it without checking ``accepted`` would masquerade as a clean validation and let an unvalidated edit through.
        Raises :class:`ValidationRefusedError` carrying the sidecar refusal code/message for a refused or malformed
        response, and for a validateEdit request that itself failed (sidecar crash, timeout, malformed JSON) — the
        validation outcome is equally unknown in all three cases, so they all fail closed.
        """
        try:
            validation = client.validate_edit(overlay)
        except (RuntimeError, ValueError, OSError) as error:
            # RuntimeError: client/protocol errors; ValueError: malformed response JSON; OSError includes TimeoutError.
            raise ValidationRefusedError("validation_unavailable", f"the validateEdit request failed: {error}") from error
        if not isinstance(validation, dict) or validation.get("accepted") is not True:
            sidecar_refusal = validation.get("refusal") if isinstance(validation, dict) else None
            refusal = sidecar_refusal if isinstance(sidecar_refusal, dict) else {}
            code = str(refusal.get("code") or "validation_refused")
            message = str(refusal.get("message") or "the sidecar did not accept the validateEdit request")
            raise ValidationRefusedError(code, message)
        return validation

    @staticmethod
    def _validation_refused_apply_result(result: dict, error: ValidationRefusedError, stage: str) -> dict:
        """Marks an apply result as refused because a mandatory javac validation pass could not run.

        Used only BEFORE any file is written (baseline and staged pre-commit validation), so the refusal can assert
        that nothing was applied. The sidecar's own refusal code/message is preserved both in the message and as
        ``sidecarRefusal`` so callers can distinguish e.g. a malformed overlay from project-model errors.
        """
        result["accepted"] = False
        result["applied"] = False
        result["editsAlreadyApplied"] = False
        result["refusal"] = {
            "code": "pre_apply_validation_refused",
            "message": f"Edits were not applied because the {stage} javac validation was refused by the sidecar "
            f"(no files were written): [{error.code}] {error.message}",
            "sidecarRefusal": {"code": error.code, "message": error.message},
        }
        return result

    @staticmethod
    def _compiler_errors(validation: dict) -> list[str]:
        """The real, unsuppressed javac errors from a validateEdit response.

        Prefers ``compilerErrors`` (always the true errors, even when allow_incomplete_analysis routes them into
        ``warnings`` for presentation) and falls back to ``errors`` for older sidecar responses.
        """
        if "compilerErrors" in validation:
            return list(validation.get("compilerErrors") or [])
        return list(validation.get("errors") or [])

    def _degraded_model_apply_refusal(self, client: JavaRefactorClient, operation: str) -> dict | None:
        """Returns an apply refusal when the active project model is degraded, or None when apply may proceed.

        A model is degraded when build-tool extraction failed and discovery fell back to a classpath-less conventional
        layout (``conventionalFallbackUsed``): there is no resolved classpath at all, so the edit cannot be validated
        against the real build and apply fails closed; preview is unaffected.

        Note: ``allow_incomplete_analysis`` is NOT treated as degraded here. It is an explicit opt-in to apply against a
        project with pre-existing unresolved diagnostics; the apply path instead gates it by diffing the staged/post-edit
        compiler errors against the pre-edit baseline (see ``_preview_or_apply_refactor``), tolerating pre-existing
        diagnostics while rejecting any newly introduced error.
        """
        status = client.status(refresh=False)
        model = status.project_model or {}
        reasons: list[str] = []
        if model.get("conventionalFallbackUsed"):
            reasons.append(
                "the project model was produced via conventional fallback because build-tool extraction failed, so it "
                "has no resolved classpath"
            )
        if not reasons:
            return None
        return {
            "accepted": False,
            "applied": False,
            "operation": operation,
            "mode": "apply",
            "refusal": {
                "code": "degraded_model_apply_refused",
                "message": "Apply refused on a degraded project model: "
                + "; ".join(reasons)
                + ". Fix the build-tool extraction (check that Maven or Gradle can be invoked from the project root) "
                "or provide explicit source_roots and classpath in the java_refactor project configuration, then retry. "
                "Preview remains available.",
            },
        }

    @staticmethod
    def _attach_preview(result: dict, preview: "WorkspaceEditPreview", applied: bool) -> None:
        """Attaches the workspace-edit preview summary and applied flag to a refactor result."""
        result["preview"] = {
            "touchedFiles": preview.touched_files,
            "editCount": preview.edit_count,
            "fileOperationCount": preview.file_operation_count,
            "warnings": preview.warnings,
            "preconditions": preview.preconditions,
            "stats": preview.stats,
        }
        result["applied"] = applied

    def _validate_supported_project(self) -> None:
        """Validates that Java is configured and LSP is available when configured symbol targeting needs it."""
        if self._config.use_lsp_symbol_resolution and not self._language_backend.is_lsp():
            raise JavaRefactorRuntimeError(
                "Java refactor sidecar requires Serena's LSP backend when java_refactor.use_lsp_symbol_resolution is true; "
                "set it false and pass explicit line/column to use sidecar-only targeting."
            )
        if Language.JAVA not in self._languages:
            raise JavaRefactorRuntimeError("Java refactor sidecar requires an active project configured with the java language.")

    def _get_or_start_client(self, refresh: bool) -> JavaRefactorClient:
        """Creates, starts, and initializes the sidecar client as needed."""
        if refresh and self._client is not None:
            self._client.shutdown()
            self._client = None

        if self._client is None or not self._client.is_running():
            jar_path = self._resolve_sidecar_jar()
            self._client = JavaRefactorClient(jar_path, java_command=self._java_command(), max_heap=self._java_max_heap())
            self._client.start()
            self._client.initialize(
                JavaRefactorInitializeParams(
                    project_root=str(self._project_root),
                    configuration=self._sidecar_configuration(),
                    config=self._sidecar_config_dict(),
                    encoding=self._source_encoding(),
                    java_home=(self._config.java_home or None),
                    ignored_patterns=list(self._config.ignored_patterns),
                    project_data_dir=str(self._project_data_dir) if self._project_data_dir is not None else None,
                )
            )

        return self._client

    def _java_command(self) -> str:
        """Returns the configured Java executable path."""
        if self._config.java_home:
            return str(Path(self._config.java_home).expanduser().resolve() / "bin" / "java")
        return "java"

    def _java_max_heap(self) -> str:
        """Returns a sanitized JVM max heap setting."""
        max_heap = self._config.max_heap.strip()
        if not re.fullmatch(r"\d+[kKmMgG]?", max_heap):
            raise JavaRefactorRuntimeError(f"Invalid java_refactor.max_heap value: {self._config.max_heap!r}")
        return max_heap

    def _source_encoding(self) -> str:
        """The project source encoding used for character-offset edits, matching the charset the sidecar reads with.

        Resolution order: the ``java_refactor.encoding`` override, then the general Serena project ``encoding``, then
        UTF-8. Honoring the general project encoding ensures edits are read/written with the same charset the rest of
        Serena uses, without requiring the java_refactor subsection to duplicate it.

        Java charset names (UTF-8, ISO-8859-1, US-ASCII, UTF-16, ...) are accepted directly by Python's codec registry.
        """
        return self._config.encoding or self._project_encoding or "utf-8"

    def _sidecar_configuration(self) -> str:
        """Serializes supported Java refactor settings for the sidecar discoverer as a JSON object string.

        Retained as the legacy flat ``configuration`` payload for backward compatibility with sidecars that predate the
        structured ``config`` object; it is the JSON serialization of :meth:`_sidecar_config_dict`.
        """
        return json.dumps(self._sidecar_config_dict())

    def _sidecar_config_dict(self) -> dict[str, object]:
        """Builds the structured Java refactor configuration object for the sidecar discoverer.

        Path-list values (source roots, classpath, module path) travel as JSON arrays so individual entries can
        contain ``;``, ``,``, or the OS path separator without being truncated or merged (the flat ``key=value``
        protocol could not represent those characters and collided with Windows' ``;`` path separator). An empty
        payload (``{}``) is treated by ``DiscoveryConfig`` as all-defaults.
        """
        payload: dict[str, object] = {}

        mode = (self._config.build_tool_mode or "auto").strip().lower()
        if mode and mode != "auto":
            payload["buildToolMode"] = mode

        for key, values in (
            ("sourceRoots", self._config.source_roots),
            ("classpath", self._config.classpath),
            ("modulePath", self._config.module_path),
        ):
            cleaned = [str(value).strip() for value in values if str(value).strip()]
            if cleaned:
                payload[key] = cleaned

        for key, value in (
            ("release", self._config.release),
            ("source", self._config.source),
            ("target", self._config.target),
            ("encoding", self._config.encoding),
        ):
            if value:
                payload[key] = str(value).strip()

        if self._config.allow_incomplete_analysis:
            payload["allowIncompleteAnalysis"] = True
        if self._config.allow_conventional_fallback:
            payload["allowConventionalFallback"] = True
        if self._config.offline:
            payload["offline"] = True
        payload["annotationProcessing"] = self._config.annotation_processing
        payload["maxFiles"] = self._config.max_files
        if self._config.use_jdtls_settings:
            self._add_jdtls_settings(payload)
        return payload

    def _add_jdtls_settings(self, payload: dict[str, object]) -> None:
        """Forwards Serena's Java LS build-tool settings (settings.xml / Gradle user home / Gradle JDK / wrapper flag) to
        the sidecar discovery payload so build-model extraction matches the language server (project-model plan section 3
        step 5). Absent settings are simply omitted; the sidecar treats every key as optional.
        """
        for source_key, payload_key in (
            ("maven_user_settings", "mavenUserSettings"),
            ("gradle_user_home", "gradleUserHome"),
            ("gradle_java_home", "gradleJavaHome"),
        ):
            value = self._jdtls_settings.get(source_key)
            if value is not None and str(value).strip():
                payload[payload_key] = str(value).strip()
        wrapper_enabled = self._jdtls_settings.get("gradle_wrapper_enabled")
        if wrapper_enabled is not None:
            payload["gradleWrapperEnabled"] = bool(wrapper_enabled)

    def _resolve_sidecar_jar(self) -> Path:
        """Resolves the sidecar jar according to Serena's development and wheel lookup order."""
        env_path = os.getenv(self.ENV_JAR)
        if env_path:
            path = Path(env_path).expanduser().resolve()
            if path.exists():
                return path
            raise FileNotFoundError(f"{self.ENV_JAR} points to a missing Java refactor sidecar jar: {path}")

        repo_matches = sorted(self._repo_root.glob(self.REPO_JAR_GLOB))
        if repo_matches:
            return repo_matches[-1]

        bundled = self._resolve_bundled_sidecar_jar()
        if bundled is not None and bundled.exists():
            return bundled

        raise FileNotFoundError(
            "Java refactor sidecar jar was not found. Set SERENA_JAVA_REFACTOR_JAR, "
            "build java-refactor/build/libs/serena-java-refactor*.jar, or install a wheel that bundles serena/resources/java-refactor/serena-java-refactor.jar."
        )

    def _resolve_sidecar_jar_or_none(self) -> Path | None:
        """Best-effort sidecar jar resolution for diagnostic status output."""
        try:
            return self._resolve_sidecar_jar()
        except Exception:
            return None

    @staticmethod
    def _resolve_bundled_sidecar_jar() -> Path | None:
        """Returns the bundled wheel resource path if importlib can resolve it."""
        try:
            return Path(str(resources.files("serena.resources") / "java-refactor" / "serena-java-refactor.jar"))
        except Exception:
            return None


def _project_line_ending_value(project_config: object) -> str | None:
    r"""Returns the explicit newline string ("\n"/"\r\n") for an LF/CRLF project setting, else None.

    None means "no fixed project convention" — the applier then preserves each file's own existing line ending (and
    falls back to the platform default for a file that has none), which matches NATIVE/unset behavior.
    """
    line_ending = getattr(project_config, "line_ending", None)
    newline_str = getattr(line_ending, "newline_str", None)
    return newline_str


def get_or_create_java_refactor_manager(project: "Project", language_backend: LanguageBackend) -> JavaRefactorManager:
    """Returns the Project-owned Java refactor manager, creating it when needed."""
    manager = getattr(project, "java_refactor_manager", None)
    if manager is None:
        ls_specific_settings = getattr(project.project_config, "ls_specific_settings", None) or {}
        jdtls_settings = ls_specific_settings.get(Language.JAVA.value, {}) or {}
        project_data_dir: str | None = None
        path_to_data_folder = getattr(project, "path_to_serena_data_folder", None)
        if callable(path_to_data_folder):
            project_data_dir = str(path_to_data_folder())
        manager = JavaRefactorManager(
            project_root=project.project_root,
            language_backend=language_backend,
            languages=list(project.project_config.languages),
            java_refactor_config=project.project_config.java_refactor,
            jdtls_settings=jdtls_settings,
            project_data_dir=project_data_dir,
            project_encoding=getattr(project.project_config, "encoding", None),
            project_line_ending=_project_line_ending_value(project.project_config),
        )
        project.java_refactor_manager = manager
    return manager
