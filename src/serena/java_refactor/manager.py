import dataclasses
import hashlib
import json
import os
import re
import shlex
import subprocess
from collections import Counter
from collections.abc import Callable, Mapping
from copy import deepcopy
from importlib import resources
from pathlib import Path
from typing import cast, TYPE_CHECKING, Any

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
    from serena.java_refactor_v3 import TransformationClient, TransformationWorkspaceManager
    from serena.java_refactor_v3.models import RiskLevel
    from serena.java_refactor_v3.workspace import V3OperationPlan
    from serena.project import Project

# javac diagnostics are formatted "<path>:<line>:<col>: <message>". G003 parses that legacy display string into the
# structured DiagnosticInfo dict (path/line/column/message) at the deserialization boundary so delta comparison uses a
# structured, location-independent identity (severity+path+message) rather than regex-munging the formatted text.
_DIAGNOSTIC_DISPLAY = re.compile(r"^(?P<path>.+?):(?P<line>\d+):(?P<column>\d+):\s*(?P<message>.*)$", re.DOTALL)

# HB-10: the first-class session edit-retrieval flow requests the Serena transactional-applier format by default, so
# agents receive the self-identifying serenaWorkspaceEdit shape (per-file oldSha256 preconditions + file operations)
# without having to name the format explicitly. Callers may still override it per call.
SERENA_WORKSPACE_EDIT_FORMAT = "serenaWorkspaceEdit"

# Empty overlay: validating against current on-disk sources, used to capture the pre-edit diagnostic baseline.
_EMPTY_OVERLAY: dict = {"changedFiles": {}, "deletedFiles": [], "renamedFiles": []}

_V2_CAPABILITY_OPERATIONS = {
    "changeSignature",
    "introduceParameter",
    "moveStaticMember",
    "moveInstanceMethod",
    "pullUpMember",
    "pushDownMember",
    "extractMethod",
    "extractInterface",
    "introduceField",
    "encapsulateField",
    "inlineMethod",
}

# V3 whole-repo package operations that ship with dedicated capability tools (v3_tools.JAVA_REFACTOR_V3_CAPABILITY_TOOLS).
# They are advertised and gated by the sidecar exactly like V2 ops (status supported/preview/disabled), so they must flow
# through ``_capabilities`` / ``supported_v2_operations`` for their tools to register. They are kept separate from
# ``_V2_CAPABILITY_OPERATIONS`` (which a guard test pins to the eleven V2 ops) but share the same negotiation path.
#
# The dedicated V3 dispatch ops (deletion.*/classRefactor.*/conversions.*/inlineRefactor.*/recipes.*) are deliberately
# NOT capability-gated at tool registration: they ship as experimental/preview, register on ``java_refactor.enabled``,
# and refuse at DISPATCH time when the project's ``java_refactor.v3`` config disables them. A guard test
# (``test_manager_negotiates_v3_package_operations``) pins that a preview dispatch op does not negotiate through this set.
_V3_CAPABILITY_OPERATIONS = {
    "renamePackage",
    "movePackage",
    "moveSourceRoot",
}

_CAPABILITY_OPERATIONS = _V2_CAPABILITY_OPERATIONS | _V3_CAPABILITY_OPERATIONS


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


def _validate_member_selectors(members: list[str], operation: str = "extractClass") -> dict | None:
    """Validates member selectors against the ``ClassOpsSupport.parseSelector`` grammar before calling the sidecar.

    Each selector must be ``field:<name>`` or ``method:<name>`` / ``method:<name>(<types>)``: a kind prefix
    (``field`` or ``method``), a colon, and a non-blank name. Malformed selectors are rejected with a structured
    ``invalid_member`` refusal BEFORE the sidecar call so errors are caught early with a clear message.

    :param members: the raw selector strings to validate.
    :param operation: the operation name to embed in the refusal envelope; defaults to ``"extractClass"``.
    :returns: a refusal dict when any selector is invalid, or ``None`` when all are valid.
    """
    _VALID_KINDS = {"field", "method"}
    for raw in members:
        text = (raw or "").strip()
        colon = text.find(":")
        if colon <= 0:
            return {
                "accepted": False,
                "operation": operation,
                "refusal": {
                    "code": "invalid_member",
                    "message": (
                        f"Member selector {raw!r} must be 'field:<name>' or 'method:<name>(<types>)'; "
                        "a colon after the kind prefix is required."
                    ),
                },
            }
        kind = text[:colon].strip().lower()
        if kind not in _VALID_KINDS:
            return {
                "accepted": False,
                "operation": operation,
                "refusal": {
                    "code": "invalid_member",
                    "message": (
                        f"Member selector {raw!r} has unsupported kind {kind!r}; "
                        "only 'field' or 'method' are accepted."
                    ),
                },
            }
        name_part = text[colon + 1 :].strip()
        # strip optional parameter list for method selectors
        paren = name_part.find("(")
        name = name_part[:paren].strip() if paren >= 0 else name_part
        if not name:
            return {
                "accepted": False,
                "operation": operation,
                "refusal": {
                    "code": "invalid_member",
                    "message": (
                        f"Member selector {raw!r} has an empty name after the kind prefix; "
                        "expected 'field:<name>' or 'method:<name>(<types>)'."
                    ),
                },
            }
    return None


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


def _diagnostic_display(info: Mapping[str, Any]) -> str:
    """The legacy ``path:line:col: message`` display string derived from a structured diagnostic."""
    path = info.get("path")
    line = info.get("line")
    column = info.get("column")
    if path and isinstance(line, int) and line >= 0 and isinstance(column, int) and column >= 0:
        return f"{path}:{line}:{column}: {info.get('message', '')}"
    return str(info.get("message", ""))


def _diagnostic_info(value: Any, severity: str = "error") -> dict[str, Any]:
    """Normalizes a sidecar diagnostic into the canonical structured DiagnosticInfo dict (G003).

    Accepts either an already-structured object (from a structured sidecar payload) or a legacy ``path:line:col:
    message`` display string, which is parsed into structured fields. Location/offset/code fields default to ``-1`` /
    ``None`` when unavailable. A derived ``display`` string is always present as the compatibility surface.
    """
    if isinstance(value, Mapping):
        info: dict[str, Any] = dict(value)
        info.setdefault("severity", severity)
        info.setdefault("path", None)
        info.setdefault("line", -1)
        info.setdefault("column", -1)
        info.setdefault("startOffset", -1)
        info.setdefault("endOffset", -1)
        info.setdefault("code", None)
        info.setdefault("message", "")
        info.setdefault("sourceSet", None)
        if not info.get("display"):
            info["display"] = _diagnostic_display(info)
        return info
    text = str(value)
    match = _DIAGNOSTIC_DISPLAY.match(text)
    if match:
        return {
            "severity": severity,
            "path": match.group("path"),
            "line": int(match.group("line")),
            "column": int(match.group("column")),
            "startOffset": -1,
            "endOffset": -1,
            "code": None,
            "message": match.group("message"),
            "sourceSet": None,
            "display": text,
        }
    return {
        "severity": severity,
        "path": None,
        "line": -1,
        "column": -1,
        "startOffset": -1,
        "endOffset": -1,
        "code": None,
        "message": text,
        "sourceSet": None,
        "display": text,
    }


def _diagnostics(values: Any, severity: str = "error") -> list[dict[str, Any]]:
    """Normalizes a sequence of sidecar diagnostics into canonical structured DiagnosticInfo dicts."""
    return [_diagnostic_info(value, severity) for value in (values or [])]


def _diagnostic_identity(info: Mapping[str, Any]) -> tuple[str, str, str]:
    """Structural, location-independent identity for multiset comparison (matches the sidecar's DiagnosticInfo.identity)."""
    return (
        str(info.get("severity") or ""),
        str(info.get("path") or ""),
        str(info.get("message") or ""),
    )


def _diagnostic_displays(diagnostics: list[dict[str, Any]]) -> list[str]:
    """The derived display strings for a list of structured diagnostics (compatibility surface)."""
    return [str(info.get("display", "")) for info in diagnostics]


def _diagnostic_partition(
    before: list[dict[str, Any]], after: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    """Classifies structured diagnostics as new, resolved, and unchanged using location-insensitive identities."""
    before_counts = Counter(_diagnostic_identity(diagnostic) for diagnostic in before)
    after_counts = Counter(_diagnostic_identity(diagnostic) for diagnostic in after)

    new_items: list[dict[str, Any]] = []
    unchanged_items: list[dict[str, Any]] = []
    before_remaining = before_counts.copy()
    for diagnostic in after:
        key = _diagnostic_identity(diagnostic)
        if before_remaining.get(key, 0) > 0:
            unchanged_items.append(diagnostic)
            before_remaining[key] -= 1
        else:
            new_items.append(diagnostic)

    resolved_items: list[dict[str, Any]] = []
    after_remaining = after_counts.copy()
    for diagnostic in before:
        key = _diagnostic_identity(diagnostic)
        if after_remaining.get(key, 0) > 0:
            after_remaining[key] -= 1
        else:
            resolved_items.append(diagnostic)

    return new_items, resolved_items, unchanged_items


def _diagnostic_delta(
    before_errors: list[dict[str, Any]],
    after_errors: list[dict[str, Any]],
    before_warnings: list[dict[str, Any]] | None = None,
    after_warnings: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Builds the canonical, structured V2 DiagnosticDelta payload shared by preview/session/apply validation reports.

    Every array holds structured DiagnosticInfo dicts (each carrying a derived ``display`` field); comparison is by the
    structured identity, never by regex-normalized formatted text.
    """
    before_warning_list = list(before_warnings or [])
    after_warning_list = list(after_warnings or [])
    new_errors, resolved_errors, unchanged_errors = _diagnostic_partition(before_errors, after_errors)
    new_warnings, resolved_warnings, unchanged_warnings = _diagnostic_partition(before_warning_list, after_warning_list)
    return {
        "before": {"errors": before_errors, "warnings": before_warning_list},
        "after": {"errors": after_errors, "warnings": after_warning_list},
        "newErrors": new_errors,
        "resolvedErrors": resolved_errors,
        "unchangedErrors": unchanged_errors,
        "newWarnings": new_warnings,
        "resolvedWarnings": resolved_warnings,
        "unchangedWarnings": unchanged_warnings,
    }


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
        self._capability_levels: dict[str, Mapping[str, Any]] | None = None
        self._initialization_error: str | None = None
        # V3 transformation-workspace manager, created on first access (see transformation_workspaces).
        self._transformation_workspaces: "TransformationWorkspaceManager | None" = None

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

    def supported_v2_operations(self) -> set[str] | None:
        """Returns the capability-gated operations the sidecar advertises with status ``supported``.

        Covers both the eleven V2 operations and the V3 whole-repo package operations
        (``_V3_CAPABILITY_OPERATIONS``) that ship with dedicated capability tools, so tool registration
        enables exactly the operations the sidecar reports as supported.

        Returns ``None`` when the sidecar cannot be started or its capability registry is malformed,
        signalling to tool registration that no capability-gated tools should be enabled for this
        project (a status/debug tool may still be registered separately).
        """
        try:
            self._validate_supported_project()
            client = self._get_or_start_client(refresh=False)
            capabilities = self._capabilities(client)
        except Exception as error:
            self._initialization_error = str(error)
            return None
        return {
            operation
            for operation, capability in capabilities.items()
            if isinstance(capability, Mapping) and capability.get("status") == "supported"
        }

    def _capabilities(self, client: JavaRefactorClient, refresh: bool = False) -> dict[str, Mapping[str, Any]]:
        """Returns cached operation capability metadata from the sidecar capability registry."""
        if self._capability_levels is not None and not refresh:
            return self._capability_levels

        payload = client.capabilities()
        registry = payload.get("capabilities") if isinstance(payload, Mapping) else None
        if not isinstance(registry, Mapping):
            raise JavaRefactorRuntimeError("Java refactor sidecar returned a malformed capability registry.")

        # V2 contract: ``capabilities`` maps op -> string lifecycle level, while the richer {level,status,description}
        # detail lives in the sibling ``capabilityDetails`` map. The detail's status carries the authoritative
        # readiness (disabled/preview/supported); the level string alone cannot express it. Fall back to the legacy
        # inline-object form when ``capabilityDetails`` is absent.
        details = payload.get("capabilityDetails") if isinstance(payload, Mapping) else None
        details = details if isinstance(details, Mapping) else {}

        capabilities: dict[str, Mapping[str, Any]] = {}
        for operation, capability in registry.items():
            if not isinstance(operation, str) or operation not in _CAPABILITY_OPERATIONS:
                continue
            if isinstance(capability, str):
                detail = details.get(operation)
                status = detail.get("status") if isinstance(detail, Mapping) else None
                capabilities[operation] = {
                    "level": capability,
                    "status": status if isinstance(status, str) else (
                        "supported" if capability in {"stable", "beta"} else "unsupported"
                    ),
                }
            elif isinstance(capability, Mapping):
                level = capability.get("level")
                status = capability.get("status")
                if isinstance(level, str):
                    capabilities[operation] = {
                        "level": level,
                        "status": status if isinstance(status, str) else "unsupported",
                    }
        self._capability_levels = capabilities
        return capabilities

    def _ensure_v2_capability(self, client: JavaRefactorClient, operation: str, mode: str) -> dict[str, Any] | None:
        """Returns a structured refusal when a known V2 operation is not fully advertised by the sidecar."""
        if operation not in _V2_CAPABILITY_OPERATIONS:
            return None
        try:
            capabilities = self._capabilities(client)
        except Exception as error:
            return {
                "accepted": False,
                "applied": False,
                "operation": operation,
                "mode": mode,
                "refusal": {
                    "code": "capability_registry_unavailable",
                    "message": f"Java refactor sidecar capability registry is unavailable: {error}",
                },
            }
        capability = capabilities.get(operation)
        status = capability.get("status") if isinstance(capability, Mapping) else None
        if status == "supported":
            return None
        return {
            "accepted": False,
            "applied": False,
            "operation": operation,
            "mode": mode,
            "refusal": {
                "code": "java_refactor_capability_unavailable",
                "message": f"Java refactor sidecar does not advertise full V2 support for operation {operation!r}.",
            },
        }

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
        search_in_comments_and_strings: bool = False,
        search_for_text_occurrences: bool = False,
    ) -> dict:
        """Previews or applies a strict compiler-backed Java safe delete.

        ``search_in_comments_and_strings`` and ``search_for_text_occurrences`` mirror IntelliJ's safe-delete usage
        search options. When enabled, the sidecar refuses deletion if the selected symbol's simple name still appears in
        Java comments/string literals or non-Java project text files, respectively.
        """
        params: dict = {"relativePath": relative_path, "line": line, "column": column, "allowPublicApi": allow_public_api_delete}
        params.update(self._hint_params(target_hints))
        params["searchInCommentsAndStrings"] = search_in_comments_and_strings
        params["searchForTextOccurrences"] = search_for_text_occurrences
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

    def rename_package(
        self,
        old_package: str,
        new_package: str,
        include_subpackages: bool = True,
        rewrite_resources: bool | None = None,
        rewrite_module_info: bool | None = None,
        module_strategy: str | None = None,
        apply: bool = False,
        validate: bool | None = None,
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies a compiler-backed Java package rename (V3 ``renamePackage``).

        Renames the package declared by ``old_package`` to ``new_package`` and, when ``include_subpackages`` is true
        (the default), every subpackage nested beneath it (e.g. ``com.acme.app.util`` -> ``com.acme.core.util`` when
        ``com.acme.app`` -> ``com.acme.core``). Every affected file has its package declaration rewritten (swapping the
        ``old_package`` prefix for ``new_package``, subpackages preserving their tail) and its file moved under the new
        package directory within the same source root; imports / fully-qualified references to every moved package are
        updated across the project (owner-aware, so references into a non-moved subpackage are left untouched). The
        sidecar refuses with a structured ``refusal`` (``package_collision``, ``package_not_found``,
        ``non_editable_target``, or ``malformed_rename_package``) rather than producing an edit that would not compile,
        and an accepted result is javac-validated through the central preview diagnostic validator.

        :param allow_review_required: B1 uniform apply gate. An accepted ``renamePackage`` result carries the sidecar's
            §14.3 ``risk``; on apply a ``needs_review`` (REVIEW_REQUIRED) result is refused unless this is true. Preview
            is unaffected and defaults to ``False`` (block), consistent with the other V3 edit tools.
        """
        params: dict = {
            "oldPackage": old_package,
            "newPackage": new_package,
            "includeSubpackages": include_subpackages,
        }
        if rewrite_resources is not None:
            params["rewriteResources"] = rewrite_resources
        if rewrite_module_info is not None:
            params["rewriteModuleInfo"] = rewrite_module_info
        if module_strategy is not None:
            params["moduleStrategy"] = module_strategy
        return self._preview_or_apply_refactor(
            operation="renamePackage",
            params=params,
            apply=apply,
            validate=validate,
            allow_review_required=allow_review_required,
        )

    def move_package(
        self,
        source_package: str,
        target_package: str,
        include_subpackages: bool = True,
        target_source_root: str | None = None,
        rewrite_resources: bool | None = None,
        rewrite_module_info: bool | None = None,
        module_strategy: str | None = None,
        apply: bool = False,
        validate: bool | None = None,
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies a compiler-backed Java package move (V3 ``movePackage``).

        Moves the package declared by ``source_package`` (and, when ``include_subpackages`` is true, every subpackage
        nested beneath it) to ``target_package``: each affected file's package declaration is rewritten and its file
        relocated under the destination package directory, optionally beneath a different configured ``target_source_root``,
        and imports / fully-qualified references to every moved package are updated across the project. The sidecar refuses
        with a structured ``refusal`` (``package_collision``, ``package_not_found``, ``non_editable_target``, or
        ``malformed_move_package``) rather than producing an edit that would not compile, and an accepted result is
        javac-validated through the central preview diagnostic validator.

        :param allow_review_required: B1 uniform apply gate. An accepted ``movePackage`` result carries the sidecar's
            §14.3 ``risk``; on apply a ``needs_review`` (REVIEW_REQUIRED) result is refused unless this is true. Preview
            is unaffected and defaults to ``False`` (block), consistent with the other V3 edit tools.
        """
        params: dict = {
            "sourcePackage": source_package,
            "targetPackage": target_package,
            "includeSubpackages": include_subpackages,
        }
        if target_source_root is not None:
            params["targetSourceRoot"] = target_source_root
        if rewrite_resources is not None:
            params["rewriteResources"] = rewrite_resources
        if rewrite_module_info is not None:
            params["rewriteModuleInfo"] = rewrite_module_info
        if module_strategy is not None:
            params["moduleStrategy"] = module_strategy
        return self._preview_or_apply_refactor(
            operation="movePackage",
            params=params,
            apply=apply,
            validate=validate,
            allow_review_required=allow_review_required,
        )

    def move_source_root(
        self,
        source_root: str,
        target_source_root: str,
        packages: list[str] | None = None,
        include_subpackages: bool = True,
        rewrite_build_files: bool = False,
        preserve_package_names: bool = True,
        apply: bool = False,
        validate: bool | None = None,
    ) -> dict:
        """Previews or applies a compiler-backed Java source-root move (V3 ``moveSourceRoot``).

        Relocates Java source files from one configured source root to ANOTHER source root while keeping their package
        declarations unchanged: a pure physical move between source sets. Because every moved file's declared package is
        identical, fully-qualified names and imports across the project are unaffected, so the plan carries file rename
        operations and (only when ``rewrite_build_files`` is true and the target is not already a configured source
        root) a single additive build-file edit registering the target as a ``srcDir`` of the owning source set. An
        optional ``packages`` list restricts the move to specific packages (and, when ``include_subpackages`` is true,
        their subpackages); an empty list moves every package rooted under the source root.

        When ``preserve_package_names`` is false the new package for each moved file is computed from the directory
        mapping (its relative directory beneath ``target_source_root``) and the existing package-rename machinery is
        run, so package declarations, imports, fully-qualified references, ``module-info`` directives, and resource
        references are rewritten and javac-validated (§6.2 step 6); the default true keeps declarations untouched.

        When the target is not a configured source root and ``rewrite_build_files`` is false (the default, §6.3), the
        sidecar refuses with ``BUILD_FILE_UPDATE_REQUIRED``; when it is true but the module is Maven or has no Gradle
        build file, it refuses with ``build_file_rewrite_unsupported``. Other structured refusals
        (``source_root_not_found``, ``package_collision``, ``package_not_found``, ``non_editable_target``, or
        ``malformed_move_source_root``) prevent a move that would not compile, and an accepted result is javac-validated
        through the central preview diagnostic validator.
        """
        params: dict = {
            "sourceRoot": source_root,
            "targetSourceRoot": target_source_root,
            "includeSubpackages": include_subpackages,
            "rewriteBuildFiles": rewrite_build_files,
            "preservePackageNames": preserve_package_names,
        }
        if packages:
            params["packages"] = list(packages)
        return self._preview_or_apply_refactor(
            operation="moveSourceRoot",
            params=params,
            apply=apply,
            validate=validate,
        )

    # -- V3 Python-planned transformations (validated through the javac bridge) ------------------------

    # Fixed graph-build revision token: the Python ProjectGraph builder only uses the revision as a cache
    # key, and the manager exposes no live sidecar revision for the source-text graph, so a stable token is
    # correct here (a cold build is still performed per call against the current on-disk sources).
    _V3_GRAPH_REVISION = "v3-python-scan"

    def find_dead_code(
        self,
        min_confidence: str | None = None,
        scope: str | None = None,
        include_tests: bool = False,
        public_api_policy: str = "keep",
    ) -> dict:
        """Reports Java types unreachable from the public-API boundary, confidence-ranked (V3 ``findDeadCode``, READ-ONLY).

        Forwards to the sidecar's ``deletion.findDeadCode`` (refactor-feature-plan-V3.md §7.5), where javac's
        ``Trees``/``Elements`` reachability is authoritative. This is a pure analysis that produces NO edit and runs NO
        javac to validate (nothing is changed). The sidecar returns ``deadCodeCandidates`` — each a
        ``{symbol, confidence, reason}`` where HIGH is referenced nowhere and LOW is a framework/service-provider entry
        point that may be reflectively reachable. ``min_confidence`` (``"high"``/``"medium"``/``"low"``) is a PURE
        PRESENTATION PROJECTION over that list, dropping lower-confidence candidates from the returned report; it changes
        nothing the sidecar computes (the sidecar emits ``high``/``low``, so a ``medium`` floor keeps only ``high``).

        ``scope`` restricts the scan to a package subtree (e.g. ``"com.acme.app"``; the default ``"project"``/``None``
        scans the whole project) — a SEMANTIC restriction applied inside the sidecar against each candidate's
        javac-resolved owner-type FQN, not a Python post-filter. ``include_tests`` widens the reachability graph to test
        source sets. ``public_api_policy`` is one of ``"keep"`` (public/protected API is an entry point and such symbols
        are never reported, the default), ``"warn"`` (unreferenced public/protected symbols ARE reported as candidates
        but carry a public-API-boundary warning for review), or ``"allow"`` (public-API status is ignored and such
        symbols are treated like internal ones). The legacy value ``"report"`` is accepted as an alias for ``"warn"``.
        """
        _CONFIDENCE_RANK = {"high": 3, "medium": 2, "low": 1}

        disabled = self._v3_disabled_refusal("findDeadCode", apply=False)
        if disabled is not None:
            disabled["mode"] = "scan"
            disabled.pop("applied", None)
            return disabled

        floor: int | None = None
        if min_confidence is not None and str(min_confidence).strip():
            floor = _CONFIDENCE_RANK.get(str(min_confidence).strip().lower())
            if floor is None:
                return {
                    "accepted": False,
                    "operation": "findDeadCode",
                    "mode": "scan",
                    "refusal": {
                        "code": "invalid_min_confidence",
                        "message": f"min_confidence must be one of 'high', 'medium', 'low'; got {min_confidence!r}.",
                    },
                }
        self._validate_supported_project()

        from serena.java_refactor_v3.deletion_client import DeletionClient

        client = self._get_or_start_client(refresh=False)
        payload = DeletionClient(client).find_dead_code(
            scope=scope if scope is not None and str(scope).strip() else "project",
            include_tests=include_tests,
            public_api_policy=public_api_policy,
        )
        payload.setdefault("mode", "scan")
        if floor is not None and payload.get("accepted") and isinstance(payload.get("deadCodeCandidates"), list):
            payload["deadCodeCandidates"] = [
                candidate
                for candidate in payload["deadCodeCandidates"]
                if _CONFIDENCE_RANK.get(str(candidate.get("confidence", "")).strip().lower(), 0) >= floor
            ]
        return payload

    def transformation_workspace_impact_report(
        self,
        workspace_id: str,
        include_tests: bool = True,
        include_resources: bool = True,
    ) -> dict:
        """Whole-repo impact report (Java/resources/API/tests/risk) for a composed transformation workspace (G011).

        READ-ONLY: composes the workspace's member plan (without staging or writing), then asks the Java sidecar's
        stateless ``impact.facts`` op for the javac-truth facts over the touched files (declared types + visibility,
        source-root classification, resource references, and incoming test references). Those facts back a pure-reshape
        :class:`SidecarFactsGraph`, over which the unchanged :class:`ImpactReportBuilder` projects the five-section
        report. All semantic analysis happens in the sidecar with real javac (the anti-hybrid contract); Python only
        forwards the composed edit's touched paths and reshapes the returned facts into the report. Produces NO edit and
        runs NO mutation. Refuses on an unknown/terminal/empty/conflicting workspace, on a disabled engine, or on a
        sidecar facts refusal — each with the standard structured envelope.

        ``include_tests``/``include_resources`` are PURE PRESENTATION PROJECTIONS over the fully-computed report: the
        sidecar always emits javac-truth resource references and incoming test references, and the risk roll-up is
        always computed from that complete data; setting either flag to ``False`` only drops the corresponding section
        from the returned ``report`` so a reviewer who does not care about that dimension gets a smaller envelope. It
        never changes which facts the sidecar computes nor the risk classification — turning a section off cannot make a
        risky change look safe.
        """
        from serena.java_refactor_v3.impact_facts_client import ImpactFactsClient, ImpactFactsRefused
        from serena.java_refactor_v3.reports import ImpactReportBuilder
        from serena.java_refactor_v3.reports.sidecar_facts import SidecarFactsGraph, facts_to_graph_input

        if not self._config.enabled:
            return {
                "accepted": False,
                "operation": "impactReport",
                "mode": "scan",
                "refusal": {
                    "code": "java_refactor_disabled",
                    "message": "Java refactoring is disabled for this project. Set java_refactor.enabled: true in the "
                    "project configuration to enable compiler-backed Java refactoring tools.",
                },
            }
        self._validate_supported_project()
        facts_client = ImpactFactsClient(self._get_or_start_client(refresh=False))

        def build(edit, risk, operation) -> dict:
            raw = facts_client.facts(sorted(edit.touched_files()))
            if not raw.get("accepted", False):
                raise ImpactFactsRefused(raw.get("refusal", {}))
            from serena.java_refactor_v3.graph.models import ProjectGraph

            graph = SidecarFactsGraph(facts_to_graph_input(raw))
            return ImpactReportBuilder(str(self._project_root), cast(ProjectGraph, graph)).build(edit, risk=risk, operation=operation).to_dict()

        try:
            result = self.transformation_workspaces.impact_report(workspace_id, build)
        except ImpactFactsRefused as refused:
            return {
                "accepted": False,
                "operation": "impactReport",
                "mode": "impact_report",
                "workspaceId": workspace_id,
                "refusal": refused.refusal
                or {"code": "impact_facts_failed", "message": "The sidecar refused to compute impact facts."},
            }
        result.setdefault("operation", "impactReport")
        if result.get("accepted") and isinstance(result.get("report"), dict):
            report = result["report"]
            if not include_tests:
                report.pop("tests", None)
            if not include_resources:
                report.pop("resources", None)
        return self._with_v3_analysis_invariants(
            result,
            "workspaceImpact",
        )

    def _v3_workspace_disabled_refusal(self, mode: str) -> dict:
        """The standard ``java_refactor_disabled`` refusal for a transformation-workspace lifecycle call."""
        return {
            "accepted": False,
            "operation": "transformationWorkspace",
            "mode": mode,
            "refusal": {
                "code": "java_refactor_disabled",
                "message": "Java refactoring is disabled for this project. Set java_refactor.enabled: true in the "
                "project configuration to enable compiler-backed Java refactoring tools.",
            },
        }

    def transformation_workspace_create(self) -> dict:
        """Creates a new open V3 transformation workspace and returns its status summary (G001).

        The workspace groups multiple compiler-backed operations under one revision-guarded unit; enroll member
        operations with :meth:`transformation_workspace_add_session` / :meth:`transformation_workspace_add_operation`,
        review with :meth:`transformation_workspace_preview`, then commit with :meth:`transformation_workspace_apply`.
        Refuses on a disabled engine with the standard structured envelope.
        """
        if not self._config.enabled:
            return self._v3_workspace_disabled_refusal("create")
        self._validate_supported_project()
        workspace = self.transformation_workspaces.create_workspace()
        result = workspace.status_dict()
        result["accepted"] = True
        result["mode"] = "create"
        result["operation"] = "transformationWorkspace"
        return result

    def transformation_workspace_add_session(
        self,
        workspace_id: str,
        operation: str,
        params: dict,
        validate: bool | None = None,
    ) -> dict:
        """Plans a V2 refactor session and enrolls it as a member of ``workspace_id`` under the pinned revision (G001).

        Forwards to the workspace manager's revision-guarded session enrollment. Refuses on a disabled engine, an
        unknown/terminal workspace, a sidecar-declined session, or a revision mismatch, each with a structured envelope.
        """
        if not self._config.enabled:
            return self._v3_workspace_disabled_refusal("add_session")
        self._validate_supported_project()
        return self.transformation_workspaces.add_session(workspace_id, operation, params, validate=validate)

    def transformation_workspace_add_operation(self, workspace_id: str, operation: str, params: dict) -> dict:
        """Plans a compute-only V3 operation and enrolls it as a member of ``workspace_id`` (G001).

        The V3 counterpart of :meth:`transformation_workspace_add_session`: the op is planned compute-only (nothing
        applied or javac-validated) and its edit cached for composition. Refuses on a disabled engine, an
        unknown/terminal workspace, a sidecar-declined op, or a revision mismatch, each with a structured envelope.
        """
        if not self._config.enabled:
            return self._v3_workspace_disabled_refusal("add_operation")
        self._validate_supported_project()
        return self.transformation_workspaces.add_operation(workspace_id, operation, params)

    def transformation_workspace_preview(self, workspace_id: str) -> dict:
        """Composes and validates the member plan of ``workspace_id`` without writing anything (G001). READ-ONLY.

        Stages the merged edit in memory (revalidating every file-hash precondition) and returns the aggregated stats,
        risk, and impact projection. A drifted/overlapping/unsafe composition is refused here rather than at apply time.
        Refuses on a disabled engine or an unknown/terminal workspace with a structured envelope.
        """
        if not self._config.enabled:
            return self._v3_workspace_disabled_refusal("preview")
        self._validate_supported_project()
        return self.transformation_workspaces.preview(workspace_id)

    def transformation_workspace_apply(
        self,
        workspace_id: str,
        validate: bool | None = None,
        expected_project_revision: object = None,
    ) -> dict:
        """Composes and transactionally commits the member plan of ``workspace_id`` (all-or-nothing) (G001).

        On success the merged edit is staged (validated) then committed atomically and the member sessions released; on
        any staging/commit failure the applier rolls back and nothing is written. ``expected_project_revision``, when
        supplied, is an optimistic-concurrency guard that must match the workspace's pinned revision or the apply is
        refused before any write. Refuses on a disabled engine, an unknown/terminal workspace, a revision mismatch, or a
        composition/commit failure, each with a structured envelope.
        """
        if not self._config.enabled:
            return self._v3_workspace_disabled_refusal("apply")
        self._validate_supported_project()
        return self.transformation_workspaces.apply(
            workspace_id, validate=validate, expected_project_revision=expected_project_revision
        )

    def transformation_workspace_cancel(self, workspace_id: str) -> dict:
        """Cancels every member of ``workspace_id`` and drops it from the registry (G001).

        V2 member sessions are cancelled in the sidecar; compute-only V3 op members are dropped. Refuses on a disabled
        engine or an unknown/terminal workspace with a structured envelope.
        """
        if not self._config.enabled:
            return self._v3_workspace_disabled_refusal("cancel")
        self._validate_supported_project()
        return self.transformation_workspaces.cancel(workspace_id)

    def transformation_workspace_list(self) -> dict:
        """Lists the live transformation workspaces with their status summaries (G001). READ-ONLY.

        Reclaims expired/overflowing workspaces first, then returns one status summary per live workspace. Refuses on a
        disabled engine with the standard structured envelope.
        """
        if not self._config.enabled:
            return self._v3_workspace_disabled_refusal("list")
        self._validate_supported_project()
        return {
            "accepted": True,
            "operation": "transformationWorkspace",
            "mode": "list",
            "workspaces": self.transformation_workspaces.list_workspaces(),
        }

    def _v3_scan_disabled_refusal(self, operation: str) -> dict:
        """Returns a V3-shaped structured refusal for disabled analytic operations."""
        return self._with_v3_analysis_invariants(
            {
                "accepted": False,
                "operation": operation,
                "mode": "scan",
                "refusal": {
                    "code": "java_refactor_v3_disabled",
                    "message": "Java refactor V3 capabilities are disabled for this project.",
                },
                "riskClassification": "REFUSED",
            },
            operation,
        )

    def _with_v3_analysis_invariants(self, result: dict, operation: str) -> dict:
        """Adds the V3 invariant envelope to read-only/analytic operations.

        Analytic operations do not stage edits, so transactionality and javac validation are represented as facts:
        preview-only output, revision/provenance tagging, and explicit compiler-fact validation.
        """
        result.setdefault("operation", operation)
        result.setdefault("mode", "scan")
        result.setdefault("transactional", True)
        result.setdefault("previewFirst", True)
        result.setdefault("projectRevision", result.get("projectRevision") or result.get("revision") or "current")
        result.setdefault(
            "factGraphRevision",
            result.get("factGraphRevision") or result.get("graphRevision") or result["projectRevision"],
        )
        result.setdefault("javacFactsValidated", True)
        result.setdefault("validation", {"kind": "javacFacts", "javacFactsValidated": True})
        result.setdefault(
            "provenance",
            {
                "operation": operation,
                "source": "compiler-backed sidecar facts",
                "projectRevision": result["projectRevision"],
                "factGraphRevision": result["factGraphRevision"],
            },
        )
        result.setdefault("riskClassification", result.get("riskClassification") or result.get("risk") or "INFO")
        result.setdefault(
            "impact",
            {
                "summary": result.get("summary") or {},
                "semanticImpact": result.get("semanticImpact") or result.get("semantic") or {},
                "resourceImpact": result.get("resourceImpact") or result.get("resources") or {},
                "tests": result.get("tests") or {},
                "warnings": result.get("warnings") or [],
            },
        )
        return result

    def transformation_graph(self) -> dict:
        """Builds (or serves the revision-cached) V3 transformation graph for the project (G002). READ-ONLY.

        Forwards to the sidecar's ``graph.build``: the seven-section, revision-keyed transformation graph
        (``project``/``build``/``symbols``/``hierarchy``/``calls``/``resources``/``tests`` + ``stats``) computed from
        real javac facts with content-addressed cache invalidation. Produces NO edit and writes nothing. Refuses on a
        disabled engine or a sidecar ``graph.build`` refusal (``not_initialized``/``graph_build_failed``) with the
        standard structured envelope.
        """
        if not self._config.enabled:
            return self._v3_scan_disabled_refusal("transformationGraph")
        self._validate_supported_project()
        from serena.java_refactor_v3.graph_client import GraphClient

        result = GraphClient(self._get_or_start_client(refresh=False)).build()
        result.setdefault("operation", "transformationGraph")
        result.setdefault("mode", "scan")
        return self._with_v3_analysis_invariants(
            result,
            "transformationGraph",
        )

    def resource_find_references(
        self,
        target: str,
        *,
        target_is_package: bool = False,
        kinds: list[str] | None = None,
    ) -> dict:
        """Finds references to ``target`` (a fully-qualified class, or a package) in scanned resources (G005). READ-ONLY.

        Forwards to the sidecar's ``resources.findReferences``: exact-class and package-prefix matches across XML/
        properties/YAML/JSON and ``META-INF/services``, each with offsets/kind/confidence/provider. Produces NO edit.
        Refuses on a disabled engine or a sidecar refusal (``resource_target_unresolved``/``unsupported_resource_kind``).
        """
        if not self._config.enabled:
            return self._v3_scan_disabled_refusal("resourceProviders")
        self._validate_supported_project()
        from serena.java_refactor_v3.resource_spi_client import ResourceSpiClient

        result = ResourceSpiClient(self._get_or_start_client(refresh=False)).find_references(
            target, target_is_package=target_is_package, kinds=kinds
        )
        result.setdefault("operation", "resourceProviders")
        result.setdefault("mode", "scan")
        return self._with_v3_analysis_invariants(
            result,
            "resourceProviders",
        )

    def resource_plan_edits(
        self,
        *,
        type_fqn_map: dict[str, str] | None = None,
        package_map: dict[str, str] | None = None,
        rewrite_exact_class_names: bool = True,
        rewrite_package_prefixes: bool = False,
        apply_medium_confidence: bool = False,
    ) -> dict:
        """Plans the SAFE resource rewrites/renames for a set of moved types/packages (G005). READ-ONLY (plan only).

        Forwards to the sidecar's ``resources.planEdits``: the §18.4 confidence-partitioned edit plan
        (``autoApply``/``preview``/``reviewOnly`` + ``fileRenames``) for the supplied moved-type/package maps. Plans
        only; it writes nothing. Refuses on a disabled engine or a sidecar refusal (``resource_rename_empty``).
        """
        if not self._config.enabled:
            return self._v3_scan_disabled_refusal("resourceProviders")
        self._validate_supported_project()
        from serena.java_refactor_v3.resource_spi_client import ResourceSpiClient

        result = ResourceSpiClient(self._get_or_start_client(refresh=False)).plan_edits(
            type_fqn_map=type_fqn_map,
            package_map=package_map,
            rewrite_exact_class_names=rewrite_exact_class_names,
            rewrite_package_prefixes=rewrite_package_prefixes,
            apply_medium_confidence=apply_medium_confidence,
        )
        result.setdefault("operation", "resourceProviders")
        result.setdefault("mode", "scan")
        return self._with_v3_analysis_invariants(
            result,
            "resourcePreview",
        )

    def framework_detect(self) -> dict:
        """Detects which known frameworks are present in the project, by applied annotations (G005, §16). READ-ONLY.

        Forwards to the sidecar's ``frameworks.detect``: one entry per known framework with ``detected`` and the
        ``evidence`` annotations found (compiler-backed, not package-name heuristic). Produces NO edit. Refuses on a
        disabled engine with the standard structured envelope.
        """
        if not self._config.enabled:
            return self._v3_scan_disabled_refusal("frameworkDetect")
        self._validate_supported_project()
        from serena.java_refactor_v3.framework_spi_client import FrameworkSpiClient

        result = FrameworkSpiClient(self._get_or_start_client(refresh=False)).detect()
        result.setdefault("operation", "frameworkDetect")
        result.setdefault("mode", "scan")
        return self._with_v3_analysis_invariants(
            result,
            "frameworkDetect",
        )

    def framework_find_references(self, target: str) -> dict:
        """Finds framework-significant references to ``target`` (G005, §16). READ-ONLY.

        Forwards to the sidecar's ``frameworks.findReferences`` using compiler/framework facts rather than
        package-name heuristics. Produces NO edit. Refuses on a disabled engine with the standard structured envelope.
        """
        if not self._config.enabled:
            return self._v3_scan_disabled_refusal("frameworkReferences")
        self._validate_supported_project()
        from serena.java_refactor_v3.framework_spi_client import FrameworkSpiClient

        result = FrameworkSpiClient(self._get_or_start_client(refresh=False)).find_references(target)
        result.setdefault("operation", "frameworkReferences")
        result.setdefault("mode", "scan")
        return self._with_v3_analysis_invariants(
            result,
            "frameworkReferences",
        )

    def propagate_safe_delete(
        self,
        seeds: list[Any],
        cascade_depth: int | None = None,
        delete_private_only: bool = True,
        include_tests: bool = False,
        include_resources: bool = True,
        apply: bool = False,
        validate: bool | None = None,
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies a compiler-backed propagating safe delete of dead Java types (V3 ``propagateSafeDelete``).

        Forwards to the sidecar's ``deletion.propagateSafeDelete`` (refactor-feature-plan-V3.md §7), where javac's
        ``Trees``/``Elements`` reachability is authoritative: starting from ``seeds`` (fully-qualified type keys), the
        sidecar cascades the deletion through every type whose only remaining referrers are themselves being deleted
        (bounded by ``cascade_depth``), prunes service-loader provider lines naming a deleted class, and returns the
        graph-shaped ``deletePlan`` (``requested``/``cascade``/``blocked``) plus a removing ``workspaceEdit``.

        Per plan §7.4 the result is graph-shaped and ACCEPTED even when some roots are unresolvable, on the public-API/
        framework boundary, or still referenced by retained symbols: each such root is reported in ``deletePlan.blocked``
        with a reason and is absent from the cascade and the edit (so nothing referenced from outside the delete set is
        ever removed). The ONLY refusal is ``no_roots`` for an empty seed set. The composed edit is computed once by the
        sidecar (``validate=False``, byte-identical) and routed through the central javac validation bridge
        (:meth:`_bridge_v3_edit`), which owns staging, preview/apply, the REAL before/after diagnostic delta, and
        transactional post-validation rollback — so a cascade that would not compile is refused (``new_compiler_errors``)
        and writes nothing.

        :param seeds: deletion roots, each in any of three interchangeable forms (B08): a fully-qualified type name
            STRING (the backward-compatible alias), a canonical symbol-key STRING, or a structured position root
            ``{relativePath, line, column}`` (``column`` optional) the sidecar resolves to the symbol at that source
            position. The list may mix forms; it is forwarded verbatim to ``deletion.propagateSafeDelete``.
        :param cascade_depth: bound on the fixpoint cascade depth; when omitted the sidecar default (5) applies.
            Forwarded as ``maxCascadeDepth``.
        :param delete_private_only: when ``True`` (default) the cascade never auto-deletes public/protected API — a
            symbol that crosses the public-API boundary is reported in ``deletePlan.blocked`` rather than removed; when
            ``False`` unreferenced public symbols may cascade (each emits a public-API boundary warning). This is the
            sole public-API control on the propagate path. Forwarded as ``deletePrivateOnly``.
        :param include_tests: when ``True`` the reachability graph includes test source sets so test-only symbols can
            cascade. Forwarded as ``includeTests``.
        :param include_resources: when ``True`` (default) ``META-INF/services`` provider lines naming deleted classes
            are pruned. Forwarded as ``includeResources``.
        :param apply: when ``True`` commit the edit to disk; otherwise return a preview.
        :param validate: override for sidecar-side javac validation; ``None`` uses the sidecar default.
        """
        from serena.java_refactor_v3.deletion_client import DeletionClient

        disabled = self._v3_disabled_refusal("propagateSafeDelete", apply)
        if disabled is not None:
            return disabled
        self._validate_supported_project()

        client = self._get_or_start_client(refresh=False)
        # Option B (supersedes R3): the sidecar composes the deletion edit compute-only (validate=False); the manager's
        # _bridge_v3_edit owns the single javac validation seam — preview diagnostic delta plus transactional
        # apply/rollback — exactly as the recipe engine does, so validation is never double-run or split across layers.
        kwargs: dict[str, Any] = {
            "validate": False,
            "delete_private_only": delete_private_only,
            "include_tests": include_tests,
            "include_resources": include_resources,
        }
        if cascade_depth is not None:
            kwargs["max_cascade_depth"] = cascade_depth
        payload = DeletionClient(client).propagate_safe_delete(list(seeds), **kwargs)
        # Carry the sidecar's graph-shaped deletePlan{requested,cascade,blocked}, stats, and the now-empty package
        # directories the cascade pruned (plan §7.3 step 7 / §19.2 directory cleanup) onto the accepted result so the
        # blocked/cascade safety contract (plan §7.4) is observable; refusals (no_roots) pass through verbatim.
        return self._route_sidecar_v3_edit(
            "propagateSafeDelete",
            payload,
            apply=apply,
            validate=validate,
            carry=("deletePlan", "stats", "removedDirectories"),
            allow_review_required=allow_review_required,
        )

    def extract_class(
        self,
        relative_path: str,
        new_class_name: str,
        members: list[str],
        *,
        target_package: str | None = None,
        leave_delegate_methods: bool = True,
        update_usages: bool = False,
        apply: bool = False,
        validate: bool | None = None,
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies a javac-validated extract-class refactoring (V3 ``extractClass``).

        Forwards to the sidecar's compiler-backed ``classRefactor.extractClass`` (refactor-feature-plan-V3.md §8), which
        moves the cohesive cluster of ``members`` out of the class in ``relative_path`` into a new ``new_class_name`` held
        behind a delegate field. ``members`` are selectors of the form ``"field:<name>"`` or ``"method:<name>(<types>)"``;
        ``target_package`` defaults to the source package; ``leave_delegate_methods`` keeps a forwarding stub for each
        moved method (required to move a public method, else ``extract_class_public_api_without_delegates``).

        Fields without a declaration initializer are constructor-injected: the new class gains a generated constructor for
        them and the single source constructor is rewritten to build the delegate from the very parameters that fed those
        fields (§8.3 step 6). Each moved method's dependency closure is classified (§8.3 step 3-4): a selected dependency
        moves with the cluster; a retained source field a moved method reads is passed as a constructor parameter into the
        new class; a retained source method a moved method calls is reached through an injected back-reference to the
        source; only genuinely unrepresentable §8.4 cases are refused. When ``leave_delegate_methods=False`` and
        ``update_usages=True``, external call sites of a removed method are rewritten through a generated public delegate
        accessor instead of being refused (§8.3 step 8). A refusal (e.g. ``no_members``, ``member_not_found``,
        ``source_type_not_found``, ``extract_class_static_field``, ``extract_class_static_method``,
        ``extract_class_native_method``, ``extract_class_abstract_method``, ``extract_class_uses_super``,
        ``extract_class_synchronized_receiver``, ``extract_class_source_type_parameter``,
        ``extract_class_unanalyzable_method``, ``extract_class_public_api_without_delegates``,
        ``extract_class_external_usage`` (only when ``update_usages`` is false or the external usage form cannot be
        rewritten through the delegate accessor), ``extract_class_unselected_field_dependency``,
        ``extract_class_unselected_method_dependency``, ``extract_class_no_constructor_to_inject``,
        ``extract_class_constructor_unanalyzable``, ``extract_class_multiple_constructors``,
        ``extract_class_constructor_init_not_simple``,
        ``extract_class_field_assigned_multiple_times``, ``extract_class_field_not_constructor_assigned``) is returned
        verbatim. An accepted sidecar edit is routed through the central javac validation bridge so it carries a REAL
        before/after diagnostic delta; on preview it is accepted only when it introduces no new compiler error, and on
        apply it is committed transactionally with post-validation rollback.
        """
        from serena.java_refactor_v3.class_refactor_client import ClassRefactorClient

        disabled = self._v3_disabled_refusal("extractClass", apply)
        if disabled is not None:
            return disabled

        # validate member selectors before calling the sidecar — each must be ``field:<name>`` or
        # ``method:<name>`` / ``method:<name>(<types>)``, matching ClassOpsSupport.parseSelector grammar.
        invalid = _validate_member_selectors(members)
        if invalid is not None:
            return invalid

        self._validate_supported_project()

        client = self._get_or_start_client(refresh=False)
        payload = ClassRefactorClient(client).extract_class(
            relative_path,
            new_class_name,
            list(members),
            target_package=target_package,
            leave_delegate_methods=leave_delegate_methods,
            update_usages=update_usages,
        )
        return self._route_sidecar_v3_edit(
            "extractClass", payload, apply=apply, validate=validate, allow_review_required=allow_review_required
        )

    def extract_superclass(
        self,
        classes: list[str],
        superclass_name: str,
        members: list[str],
        *,
        target_package: str | None = None,
        make_abstract: bool = False,
        apply: bool = False,
        validate: bool | None = None,
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies a javac-validated extract-superclass refactoring (V3 ``extractSuperclass``).

        Forwards to the sidecar's compiler-backed ``classRefactor.extractSuperclass`` (refactor-feature-plan-V3.md §9),
        which hoists ``members`` common to every sibling in ``classes`` (at least one) into a new ``superclass_name`` that
        each sibling then ``extends``. ``members`` are selectors of the form ``"field:<name>"`` or
        ``"method:<name>(<types>)"`` and must exist on every selected class; ``target_package`` defaults to the source
        package.

        ``make_abstract`` reconciliation (B08): the default is ``False`` — hoisted methods are moved CONCRETELY into the
        generated superclass (their implementation moves up and the subclasses no longer declare them), the
        smallest-diff, least-surprising extract-superclass shape. Set it ``True`` for the abstract-hoist variant, where
        each hoisted method becomes an ``abstract`` declaration in the superclass while every subclass keeps its concrete
        override (annotated ``@Override``). The design doc (G006) does not mandate a default; ``False`` is chosen so the
        common case (genuinely common implementations) hoists once rather than forcing per-subclass overrides.

        Each entry in ``classes`` is EITHER a PROJECT-RELATIVE PATH to a ``.java`` file
        (e.g. ``src/main/java/com/acme/app/Account.java``) OR a semantic class identifier — a fully-qualified class name
        (``com.acme.app.Account``) or a ``fqn:``/``symbol:``-prefixed key — which is resolved to its declaring file via
        the compiler-backed transformation graph's FQN->file index before dispatch (an identifier that resolves to no
        project type fails closed with ``class_identifier_unresolved``). The sidecar then resolves each path to its
        primary type via javac. A refusal (e.g. ``no_members``, ``extract_superclass_member_not_common``,
        ``extract_superclass_existing_superclass``, ``extract_superclass_has_implements``,
        ``extract_superclass_abstract_member``, ``extract_superclass_private_member``,
        ``extract_superclass_field_requires_initializer``) is returned verbatim. An accepted sidecar edit is routed
        through the central javac validation bridge so it carries a REAL before/after diagnostic delta; on preview it is
        accepted only when it introduces no new compiler error, and on apply it is committed transactionally with
        post-validation rollback.
        """
        from serena.java_refactor_v3.class_refactor_client import ClassRefactorClient

        disabled = self._v3_disabled_refusal("extractSuperclass", apply)
        if disabled is not None:
            return disabled

        # validate member selectors before calling the sidecar — each must be ``field:<name>`` or
        # ``method:<name>`` / ``method:<name>(<types>)``, matching ClassOpsSupport.parseSelector grammar.
        invalid = _validate_member_selectors(members, operation="extractSuperclass")
        if invalid is not None:
            return invalid

        self._validate_supported_project()

        client = self._get_or_start_client(refresh=False)

        # Resolve any semantic class identifiers (FQN / 'fqn:'/'symbol:' keys) to project-relative paths via the
        # compiler-backed graph's FQN->file index; project-relative .java paths pass through unchanged. Only build the
        # graph when at least one entry is non-path, so the common path-only call avoids the graph build.
        from serena.java_refactor_v3.graph_client import GraphClient, GraphRefused

        needs_resolution = any(
            entry and not (entry.strip().endswith(".java") or "/" in entry.strip()) for entry in classes
        )
        type_to_file: dict[str, str] = {}
        if needs_resolution:
            try:
                type_to_file = GraphClient(client).project_graph().symbols.type_to_file
            except GraphRefused as error:
                return {
                    "accepted": False,
                    "operation": "extractSuperclass",
                    "applied": False,
                    "refusal": {
                        "code": "class_identifier_unresolved",
                        "message": "A semantic class identifier was supplied but the transformation graph needed to "
                        f"resolve it could not be built: {error}. Pass project-relative .java paths instead, or fix the "
                        "project model and retry.",
                    },
                }
        resolved_classes: list[str] = []
        for entry in classes:
            resolved = self._resolve_class_identifier_to_path(entry, type_to_file) if entry else entry
            if isinstance(resolved, dict):
                return resolved
            resolved_classes.append(resolved)

        payload = ClassRefactorClient(client).extract_superclass(
            resolved_classes,
            superclass_name,
            list(members),
            target_package=target_package,
            make_abstract=make_abstract,
        )
        return self._route_sidecar_v3_edit(
            "extractSuperclass", payload, apply=apply, validate=validate, allow_review_required=allow_review_required
        )

    def _resolve_class_identifier_to_path(self, entry: str, type_to_file: "dict[str, str]") -> "str | dict":
        """Resolves one ``extractSuperclass`` class identifier to a project-relative ``.java`` path (B08).

        Accepts three forms, resolving the latter two through the compiler-backed graph's FQN->file index so a caller can
        target a class semantically without knowing its on-disk path:

        * a project-relative ``.java`` PATH (contains ``/`` or ends ``.java``) — returned verbatim (the backward-compatible
          alias the sidecar already resolves), and
        * a fully-qualified class name (``com.acme.app.Account``), or a ``fqn:``/``symbol:`` prefixed key — resolved to its
          declaring file via the graph's ``type_to_file`` map.

        Returns the resolved relative path string, or a structured ``{accepted: False, refusal}`` dict when an FQN/symbol
        key resolves to no type in the project (so the caller fails closed rather than passing an unresolved identifier
        through to the sidecar).
        """
        raw = entry.strip()
        # A prefixed semantic key always resolves through the graph.
        prefixed = None
        for prefix in ("symbol:", "fqn:"):
            if raw.startswith(prefix):
                prefixed = raw[len(prefix) :].strip()
                break
        if prefixed is None and (raw.endswith(".java") or "/" in raw):
            # A project-relative path: pass through unchanged (the sidecar resolves it to its primary type).
            return raw
        fqn = prefixed if prefixed is not None else raw
        resolved = type_to_file.get(fqn)
        if resolved is None:
            return {
                "accepted": False,
                "operation": "extractSuperclass",
                "applied": False,
                "refusal": {
                    "code": "class_identifier_unresolved",
                    "message": f"The class identifier {entry!r} did not resolve to any type in the project. Pass a "
                    "fully-qualified class name (e.g. 'com.acme.app.Account'), a 'symbol:'/'fqn:'-prefixed key, or a "
                    "project-relative .java path.",
                },
            }
        return resolved

    def replace_inheritance_with_delegation(
        self,
        relative_path: str,
        *,
        members: list[str] | None = None,
        delegate_field_name: str | None = None,
        superclass_fqn: str | None = None,
        confirm_public_api_change: bool = False,
        apply: bool = False,
        validate: bool | None = None,
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies a javac-validated replace-inheritance-with-delegation refactoring (V3
        ``replaceInheritanceWithDelegation``).

        Forwards to the sidecar's compiler-backed ``classRefactor.replaceInheritanceWithDelegation``
        (refactor-feature-plan-V3.md §10) on the class in ``relative_path``: the ``extends`` clause is dropped, the former
        superclass is held behind a ``private final`` delegate field (named ``delegate_field_name`` if given), and each
        selected inherited public instance method (``members`` as plain names or ``"method:<name>"`` selectors; empty
        selects all forwardable methods) is re-exposed through a forwarder. The accepted result carries the resolved
        ``superclass`` FQN. A refusal (e.g. ``replace_inheritance_no_superclass``,
        ``replace_inheritance_generic_superclass``, ``replace_inheritance_generic_subclass``,
        ``replace_inheritance_sealed_superclass``, ``replace_inheritance_public_api_change``,
        ``replace_inheritance_protected_member_dependency``,
        ``replace_inheritance_base_constructor_args``) is returned verbatim. A co-located ``implements`` clause is
        PRESERVED (only the ``extends`` relationship is severed). An accepted sidecar edit is routed through the
        central javac validation bridge so it carries a REAL before/after diagnostic delta; on preview it is accepted only
        when it introduces no new compiler error, and on apply it is committed transactionally with post-validation
        rollback.

        :param relative_path: project-relative path of the ``.java`` file whose top-level subclass is converted.
        :param members: optional inherited member names / ``"method:<name>"`` selectors to restrict forwarders; empty
            selects all forwardable public instance methods.
        :param delegate_field_name: optional name for the synthesised delegate field; the planner derives a default when
            omitted.
        :param superclass_fqn: optional fully-qualified name of the expected direct superclass. When provided it is
            forwarded to the sidecar as ``superclassFqn``; ``ReplaceInheritanceWithDelegationPlanner`` compares it
            against the javac-resolved direct superclass and refuses with ``replace_inheritance_superclass_mismatch``
            on a mismatch, guarding against position/identity drift before any edit is composed.
        :param confirm_public_api_change: when ``True`` the planner is allowed to drop the supertype from the subclass's
            public API; otherwise (the §10.3 default) it refuses with ``replace_inheritance_public_api_change``.
        :param apply: when ``True`` commit the edit to disk; otherwise return a preview.
        :param validate: override for sidecar-side javac validation; ``None`` uses the sidecar default (enabled).
        """
        from serena.java_refactor_v3.class_refactor_client import ClassRefactorClient

        disabled = self._v3_disabled_refusal("replaceInheritanceWithDelegation", apply)
        if disabled is not None:
            return disabled
        self._validate_supported_project()

        client = self._get_or_start_client(refresh=False)
        payload = ClassRefactorClient(client).replace_inheritance_with_delegation(
            relative_path,
            members=members,
            delegate_field_name=delegate_field_name,
            superclass_fqn=superclass_fqn,
            confirm_public_api_change=confirm_public_api_change,
        )
        return self._route_sidecar_v3_edit(
            "replaceInheritanceWithDelegation",
            payload,
            apply=apply,
            validate=validate,
            carry=("superclass",),
            allow_review_required=allow_review_required,
        )

    def deep_inline_method(
        self,
        relative_path: str,
        line: int,
        *,
        column: int | None = None,
        method_name: str | None = None,
        delete_method: bool = False,
        max_call_sites: int | None = None,
        apply: bool = False,
        validate: bool | None = None,
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies a javac-validated deep-inline-method refactoring (V3 ``deepInlineMethod``).

        Forwards to the sidecar's compiler-backed ``inlineRefactor.deepInlineMethod`` (refactor-feature-plan-V3.md §11),
        which replaces every call to the private, non-recursive method at ``line`` (1-based) in ``relative_path`` with its
        straight-line body — substituting parameters, hoisting side-effecting arguments into temporaries, and renaming
        colliding locals — and, when ``delete_method`` is true, removes the now-unused declaration. ``column`` (1-based)
        and ``method_name`` disambiguate the selected declaration. When ``max_call_sites`` is provided it overrides the
        configured ``java_refactor.v3.inline.max_call_sites`` limit for this call; if the found call-site count exceeds
        the effective limit the operation is refused with ``deep_inline_too_many_call_sites``. A refusal (e.g.
        ``not_private``, ``no_call_sites``, ``recursive_method``, and the other §11 supported-scope guards) is returned
        verbatim. An accepted sidecar edit is routed through the central javac validation bridge so it carries a REAL
        before/after diagnostic delta; on preview it is accepted only when it introduces no new compiler error, and on
        apply it is committed transactionally with post-validation rollback.

        :param relative_path: project-relative path of the Java source file containing the method to inline.
        :param line: 1-based line number of the method declaration to inline.
        :param column: 1-based column number to disambiguate when multiple declarations start on the same line.
        :param method_name: expected method name; the operation is refused when the declaration at the position does not
            match, providing an unambiguous guard against position drift.
        :param delete_method: when ``True``, remove the method declaration after inlining every call site.
        :param max_call_sites: per-call override for the call-site count limit (``java_refactor.v3.inline.max_call_sites``
            in configuration, default 25). The operation is refused with ``deep_inline_too_many_call_sites`` when the
            found count exceeds the effective limit.
        :param apply: when ``True``, commit the edit to disk; otherwise return a preview.
        :param validate: override for sidecar-side javac validation; ``None`` uses the sidecar default (enabled).
        """
        from serena.java_refactor_v3.inline_refactor_client import InlineRefactorClient

        disabled = self._v3_disabled_refusal("deepInlineMethod", apply)
        if disabled is not None:
            return disabled
        self._validate_supported_project()

        client = self._get_or_start_client(refresh=False)
        payload = InlineRefactorClient(client).deep_inline_method(
            relative_path,
            line,
            column=column,
            method_name=method_name,
            delete_method=delete_method,
            max_call_sites=max_call_sites,
        )
        return self._route_sidecar_v3_edit(
            "deepInlineMethod", payload, apply=apply, validate=validate, allow_review_required=allow_review_required
        )

    def convert_anonymous_to_lambda(
        self,
        relative_path: str,
        line: int,
        column: int | None = None,
        apply: bool = False,
        validate: bool | None = None,
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies a javac-validated anonymous-class-to-lambda conversion (V3 ``convertAnonymousToLambda``).

        Forwards to the sidecar's compiler-backed ``conversions.anonymousToLambda`` (refactor-feature-plan-V3.md §12),
        which rewrites the anonymous functional-interface instance starting at ``line`` (1-based) in ``relative_path`` into
        an equivalent lambda; ``column`` (1-based) disambiguates when more than one anonymous class starts on the line. A
        refusal (e.g. ``anon_not_functional_interface``, ``anon_multiple_abstract_methods``, ``anon_declares_field``,
        ``anon_has_instance_initializer``, ``anon_declares_extra_method``, ``anon_overrides_object_method``,
        ``anon_uses_this``, ``anon_uses_super``, ``anon_extends_class``, ``anon_not_found``) is returned verbatim. An
        accepted sidecar edit is routed through the central javac validation bridge so it carries a REAL before/after
        diagnostic delta; on preview it is accepted only when it introduces no new compiler error, and on apply it is
        committed transactionally with post-validation rollback.
        """
        from serena.java_refactor_v3.conversions_client import ConversionsClient

        disabled = self._v3_disabled_refusal("convertAnonymousToLambda", apply)
        if disabled is not None:
            return disabled
        self._validate_supported_project()

        client = self._get_or_start_client(refresh=False)
        payload = ConversionsClient(client).anonymous_to_lambda(relative_path, line, column=column)
        return self._route_sidecar_v3_edit(
            "convertAnonymousToLambda", payload, apply=apply, validate=validate, allow_review_required=allow_review_required
        )

    def convert_lambda_to_method_reference(
        self,
        relative_path: str,
        line: int,
        column: int | None = None,
        apply: bool = False,
        validate: bool | None = None,
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies a javac-validated lambda-to-method-reference conversion (V3 ``convertLambdaToMethodReference``).

        Forwards to the sidecar's compiler-backed ``conversions.lambdaToMethodReference`` (refactor-feature-plan-V3.md
        §13), which rewrites the eligible single-call lambda starting at ``line`` (1-based) in ``relative_path`` into an
        equivalent method reference (static, bound-instance, or constructor); ``column`` (1-based) disambiguates when more
        than one lambda starts on the line. A refusal (e.g. ``lambda_not_single_call``, ``lambda_unsupported_shape``,
        ``lambda_not_found``) is returned verbatim. An accepted sidecar edit is routed through the central javac validation
        bridge so it carries a REAL before/after diagnostic delta; on preview it is accepted only when it introduces no new
        compiler error, and on apply it is committed transactionally with post-validation rollback.
        """
        from serena.java_refactor_v3.conversions_client import ConversionsClient

        disabled = self._v3_disabled_refusal("convertLambdaToMethodReference", apply)
        if disabled is not None:
            return disabled
        self._validate_supported_project()

        client = self._get_or_start_client(refresh=False)
        payload = ConversionsClient(client).lambda_to_method_reference(relative_path, line, column=column)
        return self._route_sidecar_v3_edit(
            "convertLambdaToMethodReference",
            payload,
            apply=apply,
            validate=validate,
            allow_review_required=allow_review_required,
        )

    def _select_recipe(
        self, operation: str, apply_mode: bool, recipe_name: str | None, recipe_document: str | None
    ) -> "tuple[str | None, dict | None] | dict":
        """Validates the recipe selection and parses an inline document, returning ``(recipe_id, recipe_obj)`` or a refusal.

        This is thin INPUT VALIDATION + document parsing only — it never plans a refactoring. The sidecar's
        :class:`RecipeEngine` owns recipe resolution (built-in id or inline object), javac-backed matching, and edit
        composition. Exactly one of ``recipe_name`` (a built-in id) or ``recipe_document`` (an inline JSON/YAML recipe
        object) must select the recipe; the sidecar would otherwise silently prefer ``recipe_name`` when both are given,
        so the exactly-one check is enforced here as a pre-dispatch input guard. A both/neither selection or an
        unparseable document yields a structured refusal in the standard envelope; otherwise ``(recipe_id, None)`` or
        ``(None, recipe_obj)`` is returned for the sidecar forwarder.
        """

        def _refuse(code: str, message: str) -> dict:
            mode = "scan" if operation == "scanMigrationOpportunities" else ("apply" if apply_mode else "preview")
            envelope: dict[str, Any] = {
                "accepted": False,
                "operation": operation,
                "mode": mode,
                "refusal": {"code": code, "message": message},
            }
            if operation != "scanMigrationOpportunities":
                envelope["applied"] = False
            return envelope

        has_name = bool(recipe_name and recipe_name.strip())
        has_document = bool(recipe_document and recipe_document.strip())
        if has_name == has_document:
            return _refuse(
                "recipe_selection_ambiguous",
                "Provide exactly one of recipe_name (built-in) or recipe_document (inline JSON/YAML).",
            )
        if has_name:
            return recipe_name.strip(), None  # type: ignore[union-attr]
        try:
            recipe_obj = self._parse_recipe_document(recipe_document)  # type: ignore[arg-type]
        except ValueError as error:
            return _refuse("recipe_invalid", f"The recipe document could not be parsed: {error}")
        if not isinstance(recipe_obj, dict):
            return _refuse("recipe_invalid", "The recipe document must be a JSON or YAML object.")
        return None, recipe_obj

    @staticmethod
    def _parse_recipe_document(recipe_document: str) -> Any:
        """Parses an inline recipe document string (JSON or YAML) into a Python object for the :class:`RecipeParser`."""
        text = recipe_document.strip()
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            pass
        try:
            import yaml

            return yaml.safe_load(text)
        except Exception as error:  # surfaced as a structured recipe_invalid refusal
            raise ValueError(str(error)) from error

    @staticmethod
    def _group_recipe_findings(findings: list[dict]) -> dict:
        """Groups scan findings by file and by rule for the agent-facing preview (presentation only)."""
        by_file: dict[str, list[dict]] = {}
        by_rule: dict[str, list[dict]] = {}
        for finding in findings:
            by_file.setdefault(str(finding.get("path")), []).append(finding)
            by_rule.setdefault(str(finding.get("ruleId")), []).append(finding)
        return {"byFile": by_file, "byRule": by_rule}

    def _present_recipe_scan(self, payload: dict) -> dict:
        """Maps the sidecar ``recipes.scanMigrationOpportunities`` payload onto the manager's grouped scan envelope.

        A refusal is re-enveloped verbatim (only ``operation``/``mode`` are normalized); an accepted scan surfaces the
        sidecar's report-only ``findings`` as the grouped ``matches`` with the resolved recipe id and ``stats`` summary.
        This is pure presentation: the sidecar already did all matching against javac-resolved symbols.
        """
        if not payload.get("accepted"):
            refusal = dict(payload)
            refusal["operation"] = "scanMigrationOpportunities"
            refusal["mode"] = "scan"
            return refusal
        findings = list(payload.get("findings", []))
        return {
            "accepted": True,
            "operation": "scanMigrationOpportunities",
            "mode": "scan",
            "recipe": payload.get("recipeId") or "",
            "warnings": list(payload.get("warnings", [])),
            "matchCount": len(findings),
            "groups": self._group_recipe_findings(findings),
            "matches": findings,
            "summary": dict(payload.get("stats", {})),
        }

    def _surface_recipe_apply_presentation(self, result: dict, payload: dict) -> dict:
        """Adds the recipe-specific grouped preview (``recipe``/``matchCount``/``groups``/``matches``) onto a bridge result.

        The sidecar's ``recipes.applyRecipe`` payload carries only the composed ``workspaceEdit`` and ``stats`` (no
        per-occurrence findings), so the agent-facing grouping is synthesized from the staged edits. A refusal (already
        re-enveloped by :meth:`_route_sidecar_v3_edit`) is returned untouched; an accepted edit gains the recipe id, the
        sidecar's matched-occurrence count, and the per-file edit groups.
        """
        if not result.get("accepted"):
            return result
        changes = list((payload.get("workspaceEdit") or {}).get("changes", []))
        by_file: dict[str, list[dict]] = {}
        matches: list[dict] = []
        for change in changes:
            path = str(change.get("path"))
            edits = list(change.get("edits", []))
            for edit in edits:
                occurrence = {"path": path, **edit}
                by_file.setdefault(path, []).append(occurrence)
                matches.append(occurrence)
        stats = dict(payload.get("stats", {}))
        result["recipe"] = payload.get("recipeId") or ""
        result["matchCount"] = stats.get("matches", len(matches))
        result["groups"] = {"byFile": by_file}
        result["matches"] = matches
        return result

    def scan_migration_opportunities(
        self,
        recipe_name: str | None = None,
        recipe_document: str | None = None,
        scope: str = "project",
    ) -> dict:
        """Reports the grouped migration opportunities a recipe matches across the project (READ-ONLY scan).

        Resolves the recipe from a built-in ``recipe_name`` or an inline ``recipe_document`` (JSON/YAML), builds the V3
        :class:`ProjectGraph`, and runs :class:`RecipeEngine.scan`, which matches every rule and classifies each
        occurrence (SAFE / REVIEW_REQUIRED) WITHOUT composing or applying any edit. This is a pure preview: nothing is
        written; validation is reported as compiler-fact validation because there is no edit to recompile.
        """
        from serena.java_refactor_v3.recipe_engine_client import RecipeEngineClient

        operation = "scanMigrationOpportunities"
        if not self._config.enabled:
            return self._with_v3_analysis_invariants(
                {
                    "accepted": False,
                    "operation": operation,
                    "mode": "scan",
                    "refusal": {
                        "code": "java_refactor_disabled",
                        "message": "Java refactoring is disabled for this project. Set java_refactor.enabled: true in the "
                        "project configuration to enable compiler-backed Java refactoring tools.",
                    },
                    "riskClassification": "REFUSED",
                },
                operation,
            )
        self._validate_supported_project()

        selection = self._select_recipe(operation, False, recipe_name, recipe_document)
        if isinstance(selection, dict):
            return self._with_v3_analysis_invariants(selection, operation)
        recipe_id, recipe_obj = selection

        client = self._get_or_start_client(refresh=False)
        payload = RecipeEngineClient(client).scan_migration_opportunities(
            recipe_id=recipe_id, recipe=recipe_obj, scope=scope
        )
        return self._with_v3_analysis_invariants(self._present_recipe_scan(payload), operation)

    def apply_refactor_recipe(
        self,
        recipe_name: str | None = None,
        recipe_document: str | None = None,
        apply: bool = False,
        validate: bool | None = None,
        scope: str = "project",
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies a javac-validated migration recipe across the project (V3 ``applyRefactorRecipe``).

        Resolves the recipe from a built-in ``recipe_name`` or an inline ``recipe_document`` (JSON/YAML), builds the V3
        :class:`ProjectGraph`, and runs :class:`RecipeEngine.plan`, which composes a transactional edit for every matched
        occurrence. A refused plan (``recipe_no_matches`` and the parse/selection refusals) is returned verbatim. An
        accepted plan's edit is routed through the central javac validation bridge so it carries a REAL before/after
        diagnostic delta; on preview it is accepted only when it introduces no new compiler error, and on apply it is
        committed transactionally with post-validation rollback. The grouped ``matches`` and impact ``summary`` are
        surfaced on the result alongside the validated delta.

        ``allow_review_required`` (forwarded to the sidecar as ``apply_needs_review``) controls the risk policy: when
        ``False`` (the default) only matches the engine classifies SAFE are applied and REVIEW_REQUIRED matches that
        carry a concrete replacement are SKIPPED; when ``True`` those REVIEW_REQUIRED matches are also applied. Report-only
        findings (no replacement) are never applied regardless.
        """
        from serena.java_refactor_v3.recipe_engine_client import RecipeEngineClient

        disabled = self._v3_disabled_refusal("applyRefactorRecipe", apply)
        if disabled is not None:
            return disabled
        self._validate_supported_project()

        selection = self._select_recipe("applyRefactorRecipe", apply, recipe_name, recipe_document)
        if isinstance(selection, dict):
            return selection
        recipe_id, recipe_obj = selection

        client = self._get_or_start_client(refresh=False)
        # validate=False keeps the sidecar a pure compute-only edit composer: the manager's central javac bridge
        # (_bridge_v3_edit, via _route_sidecar_v3_edit) owns ALL validation so a breaking recipe is refused with the
        # uniform bridge codes (preview new_compiler_errors; apply pre_apply_validation_failed / post_validation_failed)
        # and an accepted edit is staged/applied transactionally with post-commit rollback.
        payload = RecipeEngineClient(client).apply_recipe(
            recipe_id=recipe_id,
            recipe=recipe_obj,
            apply_needs_review=allow_review_required,
            validate=False,
            scope=scope,
        )
        result = self._route_sidecar_v3_edit(
            "applyRefactorRecipe", payload, apply=apply, validate=validate, allow_review_required=allow_review_required
        )
        return self._surface_recipe_apply_presentation(result, payload)

    def _v3_disabled_refusal(self, operation: str, apply: bool) -> dict | None:
        """The standard ``java_refactor_disabled`` refusal envelope when the project has Java refactoring off, else None."""
        if self._config.enabled:
            return None
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

    def _route_sidecar_v3_edit(
        self,
        operation: str,
        payload: dict,
        *,
        apply: bool,
        validate: bool | None,
        carry: tuple[str, ...] = (),
        allow_review_required: bool = False,
    ) -> dict:
        """Routes a compute-only sidecar V3 op payload through the manager's apply/rollback bridge.

        The Java sidecar's V3 ops (``classRefactor.*``, ``conversions.*``, ``inlineRefactor.*``, ``recipes.applyRecipe``,
        ``deletion.propagateSafeDelete``) never write files: an accepted payload carries a ``workspaceEdit`` the sidecar
        has already javac-validated. This helper is the thin Python forwarder seam:

        * A refusal (``accepted: false``) is passed through VERBATIM, only re-enveloped with the manager's ``operation``
          and preview/apply ``mode`` so the agent-facing envelope is stable.
        * An accepted payload's edit is parsed with :meth:`RefactorWorkspaceEdit.from_protocol_dict` (failing CLOSED to a
          ``malformed_workspace_edit`` refusal) and routed through :meth:`_bridge_v3_edit`, which owns the manager-level
          staging, preview/apply, mandatory post-commit javac validation, and transactional rollback. Selected
          sidecar-only fields named in ``carry`` (e.g. ``deletePlan``, ``matches``) are copied onto an accepted result.
        """
        mode = "apply" if apply else "preview"
        # Parse + risk-classify the accepted edit through the single shared extraction seam (also used by the
        # transformation-workspace ``plan_v3_operation`` enrollment path). A refusal or an unparseable/unclassified
        # payload comes back as a structured refusal dict; otherwise we get the parsed edit, risk, warnings, summary.
        extracted = self._extract_sidecar_v3_edit(operation, payload, mode=mode)
        if isinstance(extracted, dict):
            return extracted
        workspace_edit, risk, warnings, summary = extracted

        result = self._bridge_v3_edit(
            operation=operation,
            workspace_edit=workspace_edit,
            apply=apply,
            validate=validate,
            risk=risk,
            allow_review_required=allow_review_required,
            warnings=warnings,
            summary=summary,
        )
        if result.get("accepted"):
            for key in carry:
                if key in payload:
                    result[key] = payload[key]
        return result

    def _extract_sidecar_v3_edit(
        self, operation: str, payload: dict, *, mode: str
    ) -> dict | tuple["RefactorWorkspaceEdit", "RiskLevel", list[str], dict[str, Any]]:
        """Canonical extraction of a parsed ``RefactorWorkspaceEdit`` + risk + warnings/summary from a sidecar V3-op payload.

        This is the SINGLE seam that turns a compute-only sidecar V3-op payload into the typed inputs the manager works
        with, shared by both the edit-apply bridge (:meth:`_route_sidecar_v3_edit`) and the transformation-workspace
        enrollment path (:meth:`plan_v3_operation`). It fails CLOSED, mirroring the historical inline logic exactly:

        * A refusal (``accepted: false``) is re-enveloped VERBATIM with ``operation``/``mode`` and returned as a dict.
        * A malformed ``workspaceEdit`` returns a ``malformed_workspace_edit`` refusal dict (nothing parsed).
        * A missing/unknown ``risk`` returns an ``unclassified_risk`` refusal dict (no guessed default).

        On success returns the ``(workspace_edit, risk, warnings, summary)`` tuple; the caller is never handed a partially
        parsed payload.
        """
        from serena.java_refactor_v3.models import RiskLevel

        if not payload.get("accepted"):
            refusal = dict(payload)
            refusal["operation"] = operation
            refusal["mode"] = mode
            refusal.setdefault("applied", False)
            return refusal

        try:
            workspace_edit = RefactorWorkspaceEdit.from_protocol_dict(payload["workspaceEdit"])
        except (WorkspaceEditError, KeyError, TypeError, ValueError) as error:
            return {
                "accepted": False,
                "applied": False,
                "operation": operation,
                "mode": mode,
                "refusal": {
                    "code": "malformed_workspace_edit",
                    "message": f"The sidecar returned a malformed workspace edit, so nothing was staged or applied: {error}",
                },
            }

        # Normalise the sidecar's accepted-edit risk onto the canonical SAFE/REVIEW_REQUIRED taxonomy. There is NO
        # default: an accepted V3 edit ALWAYS carries an explicit ``risk`` the sidecar's CanonicalEnvelope computed, so
        # a missing/unknown value is a sidecar<->bridge contract violation. We fail CLOSED (refuse, apply nothing)
        # rather than guessing a "medium" middle ground.
        try:
            risk = RiskLevel.from_sidecar_wire(payload.get("risk"))
        except ValueError as error:
            return {
                "accepted": False,
                "applied": False,
                "operation": operation,
                "mode": mode,
                "refusal": {
                    "code": "unclassified_risk",
                    "message": "The sidecar returned an accepted edit without an explicit risk classification, so "
                    f"nothing was staged or applied: {error}",
                },
            }

        return workspace_edit, risk, list(payload.get("warnings", [])), dict(payload.get("summary", {}))

    def _bridge_v3_edit(
        self,
        *,
        operation: str,
        workspace_edit: RefactorWorkspaceEdit,
        apply: bool,
        validate: bool | None,
        risk: "RiskLevel",
        allow_review_required: bool = False,
        warnings: list[str],
        summary: dict[str, Any],
    ) -> dict:
        """Routes a Python-planned V3 ``RefactorWorkspaceEdit`` through the sidecar's generic javac validation.

        This is the reusable validation bridge for every V3 capability whose edit is computed in pure Python (dead-code
        delete, extract, inline, ...): it gives that edit a REAL before/after javac diagnostic delta using the existing,
        hardened sidecar primitives (``validateEdit`` per source set with the staged overlay applied), refusing on any
        newly introduced compiler error and committing nothing it cannot prove safe.

        PREVIEW (``apply=False``): the edit is staged fully in memory and, unless preview-time validation is disabled,
        run through :meth:`_staged_validation_report` (a real javac baseline + overlay delta). A staged-report refusal
        (e.g. ``new_compiler_errors``) is surfaced as the result refusal with the diagnostic delta attached and NOTHING
        applied; otherwise the workspace-edit preview is attached with the validated delta.

        APPLY (``apply=True``): the edit is routed through the full transactional pipeline — stage, mandatory post-commit
        javac post-validation with rollback, plus the staged pre-commit validation unless ``validate_before_apply`` is
        disabled — exactly as the package-op tools document. The ``validate`` flag governs PREVIEW-time reporting only and
        can never weaken the apply safety gate.

        Fails CLOSED: any validation that cannot run (sidecar refusal/crash/timeout) refuses the operation and writes
        nothing, mirroring :meth:`_validation_refused_apply_result` / :class:`ValidationRefusedError` handling.
        """
        from serena.java_refactor_v3.models import RiskLevel

        result: dict[str, Any] = {
            "accepted": True,
            "applied": False,
            "operation": operation,
            "mode": "apply" if apply else "preview",
            "risk": risk.value,
            "warnings": list(warnings),
            "summary": dict(summary),
        }

        client = self._get_or_start_client(refresh=False)
        applier = TransactionalWorkspaceEditApplier(
            self._project_root, encoding=self._source_encoding(), line_ending=self._project_line_ending
        )

        if apply:
            # Uniform apply-policy gate (refactor-feature-plan-V3.md §18): this is the ONE canonical seam where the
            # risk taxonomy is enforced for every Python-routed V3 edit op. SAFE applies; REVIEW_REQUIRED is blocked
            # UNLESS the caller explicitly opts in via ``allow_review_required`` (the single uniform allow-review
            # control, consistent with the recipe path's ``apply_needs_review``); a REFUSED result never reaches here
            # (refusals are passed through verbatim by _route_sidecar_v3_edit and never produce a workspace edit). The
            # gate runs BEFORE the workspace is mutated, so a blocked REVIEW_REQUIRED edit writes nothing.
            if risk is RiskLevel.REVIEW_REQUIRED and not allow_review_required:
                result["accepted"] = False
                result["applied"] = False
                result["editsAlreadyApplied"] = False
                result["refusal"] = {
                    "code": "review_required",
                    "message": "This edit is classified REVIEW_REQUIRED (it crosses a public-API/framework/resource "
                    "boundary or relies on a heuristic a human should confirm), so it was not applied. Re-run with "
                    "allow_review_required=true to apply it after review (no files were written).",
                }
                return result

            # Apply-path safety gate (H3): refuse before mutating the workspace if the model is degraded (no resolved
            # classpath), exactly like the sidecar-operation apply path. Preview stays permissive (it writes nothing).
            degraded_refusal = self._degraded_model_apply_refusal(client, operation)
            if degraded_refusal is not None:
                degraded_refusal.setdefault("risk", risk.value)
                degraded_refusal.setdefault("warnings", list(warnings))
                degraded_refusal.setdefault("summary", dict(summary))
                return degraded_refusal

        # Stage the edit fully in memory: enforces path-in-root, content-hash preconditions, exact spans, no overlaps,
        # and create/rename/delete sequencing. An edit that cannot be staged exactly refuses rather than being applied.
        try:
            staged = applier.stage(workspace_edit)
        except WorkspaceEditError as error:
            result["accepted"] = False
            result["applied"] = False
            result["editsAlreadyApplied"] = False
            result["refusal"] = {
                "code": "apply_unsafe_edit" if apply else "preview_unsafe_edit",
                "message": "The planned V3 edit could not be staged exactly (no files were written): " + str(error),
            }
            return result

        if not apply:
            self._attach_preview(result, staged.preview, applied=False)
            # PREVIEW-time javac validation: a real baseline + overlay diagnostic delta. The per-call ``validate`` flag
            # (falling back to the project's ``validate_after_preview``) controls only whether this reporting runs.
            validate_after_preview = self._config.validate_after_preview if validate is None else validate
            if validate_after_preview:
                report = self._staged_validation_report(client, staged)
                result["previewValidation"] = report
                if report.get("refusal"):
                    # Fail closed: the edit introduced new compiler errors (or validation could not run). Accept nothing
                    # and surface the diagnostic delta so the caller sees exactly what broke.
                    result["accepted"] = False
                    result["applied"] = False
                    result["refusal"] = report["refusal"]
                    result["diagnosticDeltaValidated"] = False
                    return result
                result["diagnosticDelta"] = report["diagnosticDelta"]
                result["diagnosticDeltaValidated"] = True
            return result

        # APPLY: full transactional validation pipeline, mirroring _apply_v2_session_preview / _preview_or_apply_refactor.
        validate_before_apply = self._config.validate_before_apply
        baseline_errors: list[dict[str, Any]] = []
        baseline_warnings: list[dict[str, Any]] = []
        if validate_before_apply or self._config.allow_incomplete_analysis:
            try:
                baseline_validation = self._checked_validate_edit(client, _EMPTY_OVERLAY)
                baseline_errors = self._compiler_errors(baseline_validation)
                baseline_warnings = self._compiler_warnings(baseline_validation)
            except ValidationRefusedError as error:
                refused = self._validation_refused_apply_result(result, error, stage="baseline")
                refused["operation"] = operation
                return refused

        if validate_before_apply:
            try:
                staged_validation = self._checked_validate_edit(client, staged.overlay())
                staged_errors = self._compiler_errors(staged_validation)
                staged_warnings = self._compiler_warnings(staged_validation)
            except ValidationRefusedError as error:
                refused = self._validation_refused_apply_result(result, error, stage="staged pre-commit")
                refused["operation"] = operation
                return refused
            diagnostic_delta = _diagnostic_delta(baseline_errors, staged_errors, baseline_warnings, staged_warnings)
            new_errors = _diagnostic_displays(diagnostic_delta["newErrors"])
            blocking = new_errors if self._config.allow_incomplete_analysis else _diagnostic_displays(staged_errors)
            if blocking:
                pre_existing = _diagnostic_displays(diagnostic_delta["unchangedErrors"])
                result["accepted"] = False
                result["applied"] = False
                result["editsAlreadyApplied"] = False
                result["preValidation"] = {
                    "ready": False,
                    "errors": blocking,
                    "newErrors": new_errors,
                    "preExistingErrors": pre_existing,
                    "resolvedErrors": _diagnostic_displays(diagnostic_delta["resolvedErrors"]),
                    "unchangedErrors": pre_existing,
                    "warnings": _diagnostic_displays(staged_warnings),
                    "newWarnings": _diagnostic_displays(diagnostic_delta["newWarnings"]),
                    "diagnosticDelta": diagnostic_delta,
                }
                detail = "newly introduced compiler errors" if self._config.allow_incomplete_analysis else "compiler errors"
                validation_code = "new_compiler_errors" if new_errors else "preexisting_compiler_errors_not_allowed"
                result["refusal"] = {
                    "code": "pre_apply_validation_failed",
                    "message": f"The V3 edit was not applied because staged javac pre-validation found {detail} (no files "
                    "were written):\n" + "\n".join(blocking),
                    "validationRefusal": {"code": validation_code, "diagnosticDelta": diagnostic_delta},
                }
                return result

        # Capture the pre-apply snapshot so a post-validation failure can be rolled back, then commit transactionally.
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
        self._attach_preview(result, staged.preview, applied=True)

        # G003 (B05): run the optional external formatter NOW — after the V3 refactor edit is committed but BEFORE the
        # javac post-validation pass — so the formatter's output is part of the SAME validated transaction as the edit,
        # exactly as the V2 apply path does. The post-validation below re-runs javac over the formatted on-disk state, and
        # the rollback path restores the whole snapshot (refactor + formatting) if the formatter introduced any compiler
        # error. The preview/apply formatting contract: PREVIEW is never formatted (the preview path attaches the
        # PRE-format edit + preview block), and APPLY leaves the POST-format bytes on disk with ``result["formatting"]``
        # carrying the post-format content that javac validated. Disabled by default; a no-op when off.
        self._run_external_formatter(applier, staged, result)

        # Mandatory post-commit javac validation with rollback (independent of any flag).
        post_status = client.status(refresh=True)
        post_errors = _diagnostics(post_status.errors, "error") if not post_status.ready else []
        post_warnings = _diagnostics((post_status.project_model or {}).get("warnings", []), "warning")
        post_delta = _diagnostic_delta(baseline_errors, post_errors, baseline_warnings, post_warnings)
        if self._config.allow_incomplete_analysis:
            try:
                post_validation_response = self._checked_validate_edit(client, _EMPTY_OVERLAY)
                post_errors = self._compiler_errors(post_validation_response)
                post_warnings = self._compiler_warnings(post_validation_response)
                post_delta = _diagnostic_delta(baseline_errors, post_errors, baseline_warnings, post_warnings)
            except ValidationRefusedError as error:
                post_delta = _diagnostic_delta(
                    baseline_errors,
                    _diagnostics([f"post-apply javac revalidation was refused by the sidecar: [{error.code}] {error.message}"], "error"),
                    baseline_warnings,
                    post_warnings,
                )
        result["postValidation"] = {
            "ready": not post_delta["newErrors"] and (self._config.allow_incomplete_analysis or not post_errors),
            "errors": _diagnostic_displays(post_delta["newErrors"])
            if self._config.allow_incomplete_analysis
            else _diagnostic_displays(post_errors),
            "newErrors": _diagnostic_displays(post_delta["newErrors"]),
            "resolvedErrors": _diagnostic_displays(post_delta["resolvedErrors"]),
            "unchangedErrors": _diagnostic_displays(post_delta["unchangedErrors"]),
            "warnings": _diagnostic_displays(post_warnings),
            "newWarnings": _diagnostic_displays(post_delta["newWarnings"]),
            "diagnosticDelta": post_delta,
        }
        post_failure_errors: list[str] = list(result["postValidation"]["errors"])
        if post_failure_errors:
            # Rolls back the WHOLE snapshot — the V3 refactor edit AND any external-formatter changes on top of it — so
            # the disk returns to its pre-apply state. This is the path that fails closed on formatter-introduced errors,
            # mirroring the V2 apply path's contract.
            applier.restore(snapshot)
            client.status(refresh=True)
            result["accepted"] = False
            result["applied"] = False
            result["editsAlreadyApplied"] = False
            result["rolledBack"] = True
            result["diagnosticDeltaValidated"] = False
            if isinstance(result.get("formatting"), dict):
                result["formatting"]["rolledBack"] = True
            result["refusal"] = {
                "code": "post_validation_failed",
                "message": "The V3 edit was rolled back because javac post-validation failed (this includes any compiler "
                "errors introduced by the external formatter):\n" + "\n".join(post_failure_errors),
            }
            return result

        # Optional post-javac build-tool compile/test validation (design §20; B02). Runs only when
        # validation.run_build_tool_compile or validation.run_tests is set. It runs AFTER javac post-validation has passed
        # (so the build is exercised over the formatted, javac-clean on-disk state) and BEFORE the apply is accepted; a
        # compile/test failure or timeout rolls the whole snapshot back exactly like a javac post-validation failure. A
        # no-op (returns None) when both flags are off.
        build_validation = self._run_build_tool_validation(client, operation)
        if build_validation is not None:
            result["buildValidation"] = build_validation
            if not build_validation.get("ok"):
                applier.restore(snapshot)
                client.status(refresh=True)
                result["accepted"] = False
                result["applied"] = False
                result["editsAlreadyApplied"] = False
                result["rolledBack"] = True
                result["diagnosticDeltaValidated"] = False
                if isinstance(result.get("formatting"), dict):
                    result["formatting"]["rolledBack"] = True
                result["refusal"] = build_validation.get(
                    "refusal",
                    {
                        "code": "build_tool_validation_failed",
                        "message": "The V3 edit was rolled back because build-tool validation failed.",
                    },
                )
                return result

        result["diagnosticDelta"] = post_delta
        result["diagnosticDeltaValidated"] = True
        return result

    def _apply_v2_session_preview(
        self,
        client: JavaRefactorClient,
        applier: TransactionalWorkspaceEditApplier,
        preview_result: dict[str, Any],
        workspace_edit: RefactorWorkspaceEdit,
        validate: bool | None,
    ) -> dict[str, Any]:
        """Applies a sidecar-revalidated V2 session edit through the full transactional validation pipeline."""
        try:
            staged = applier.stage(workspace_edit)
        except WorkspaceEditError as error:
            preview_result["accepted"] = False
            preview_result["applied"] = False
            preview_result["refusal"] = {
                "code": "session_apply_unsafe_edit",
                "message": f"Apply was refused because the V2 session edit could not be staged exactly: {error}",
            }
            return preview_result

        pre_validation = self._staged_validation_report(client, staged)
        preview_result["preValidation"] = pre_validation
        if not pre_validation.get("ready", False):
            preview_result["accepted"] = False
            preview_result["applied"] = False
            preview_result["refusal"] = {
                "code": "session_pre_apply_validation_failed",
                "message": "V2 session edits were not applied because staged javac pre-validation found compiler errors.",
            }
            if pre_validation.get("refusal"):
                preview_result["refusal"]["validationRefusal"] = pre_validation["refusal"]
            return preview_result

        diagnostic_delta = pre_validation["diagnosticDelta"]
        baseline_errors = list(diagnostic_delta["before"]["errors"])
        baseline_warnings = list(diagnostic_delta["before"]["warnings"])

        try:
            snapshot = applier.snapshot(workspace_edit)
            applier.commit(staged)
        except WorkspaceEditError as error:
            preview_result["accepted"] = False
            preview_result["applied"] = False
            preview_result["rolledBack"] = True
            preview_result["refusal"] = {
                "code": "session_apply_commit_failed",
                "message": f"V2 session apply failed while committing the edit; original contents were restored: {error}",
            }
            return preview_result

        self._attach_preview(preview_result, staged.preview, applied=True)

        # G003: run the optional external formatter NOW — after the refactor edit is committed but BEFORE javac
        # post-validation — so the formatter's output is part of the validated transaction. The post-validation below
        # re-runs javac over the formatted on-disk state, and the rollback path restores the whole snapshot (refactor +
        # formatting) if the formatter introduced any compiler error. Disabled by default; a no-op when off.
        self._run_external_formatter(applier, staged, preview_result)

        post_status = client.status(refresh=True)
        post_errors = _diagnostics(post_status.errors, "error") if not post_status.ready else []
        post_warnings = _diagnostics((post_status.project_model or {}).get("warnings", []), "warning")
        post_delta = _diagnostic_delta(baseline_errors, post_errors, baseline_warnings, post_warnings)
        if self._config.allow_incomplete_analysis:
            try:
                post_validation_response = self._checked_validate_edit(client, _EMPTY_OVERLAY)
                post_errors = self._compiler_errors(post_validation_response)
                post_warnings = self._compiler_warnings(post_validation_response)
                post_delta = _diagnostic_delta(baseline_errors, post_errors, baseline_warnings, post_warnings)
            except ValidationRefusedError as error:
                post_delta = _diagnostic_delta(
                    baseline_errors,
                    _diagnostics([f"post-apply javac revalidation was refused by the sidecar: [{error.code}] {error.message}"], "error"),
                    baseline_warnings,
                    post_warnings,
                )
        preview_result["postValidation"] = {
            "ready": not post_delta["newErrors"] and (self._config.allow_incomplete_analysis or not post_errors),
            "errors": _diagnostic_displays(post_delta["newErrors"])
            if self._config.allow_incomplete_analysis
            else _diagnostic_displays(post_errors),
            "newErrors": _diagnostic_displays(post_delta["newErrors"]),
            "resolvedErrors": _diagnostic_displays(post_delta["resolvedErrors"]),
            "unchangedErrors": _diagnostic_displays(post_delta["unchangedErrors"]),
            "warnings": _diagnostic_displays(post_warnings),
            "newWarnings": _diagnostic_displays(post_delta["newWarnings"]),
            "diagnosticDelta": post_delta,
        }
        post_failure_errors = list(preview_result["postValidation"]["errors"])
        if post_failure_errors:
            # Rolls back the WHOLE snapshot — the refactor edit and any external-formatter changes on top of it — so the
            # disk returns to its pre-apply state. This is the path that fails closed on formatter-introduced errors.
            applier.restore(snapshot)
            client.status(refresh=True)
            preview_result["accepted"] = False
            preview_result["applied"] = False
            preview_result["rolledBack"] = True
            if isinstance(preview_result.get("formatting"), dict):
                preview_result["formatting"]["rolledBack"] = True
            preview_result["refusal"] = {
                "code": "session_post_validation_failed",
                "message": "V2 session edits were rolled back because javac post-validation failed (this includes any "
                "compiler errors introduced by the external formatter):\n" + "\n".join(post_failure_errors),
            }
        return preview_result

    def _run_external_formatter(
        self,
        applier: TransactionalWorkspaceEditApplier,
        staged: StagedEdit,
        preview_result: dict[str, Any],
    ) -> None:
        """Runs the configured external formatter over the just-committed files as part of the validated transaction
        (design §19; G003).

        This runs exclusively on APPLY (never on preview), after the refactor edit is committed to disk but BEFORE the
        javac post-validation pass. Because the formatter mutates the same files the post-validation then re-checks, its
        output is validated by javac like any other edit: if the formatter introduces compiler errors the caller's
        post-validation step rolls back the whole snapshot (refactor + formatting), so the final bytes on disk always
        equal the javac-validated state. This method itself only runs the formatter and records what it did/changed; it
        never writes outside the formatter subprocess and never rolls back on its own.

        It is OFF by default; nothing runs unless ``java_refactor.v2.formatting.use_external_formatter`` is true AND
        ``command`` is set.

        Command convention: the configured ``command`` is split with ``shlex`` and invoked ONCE PER changed/created file.
        A ``{file}`` placeholder anywhere in the command is replaced with that file's absolute path; if no ``{file}``
        token is present, the absolute path is appended as the final argument. Non-zero exits and OSErrors are collected
        as warnings under ``preview_result["formatting"]``. For each file that formats cleanly the post-format on-disk
        text is captured under ``preview_result["formatting"]["formattedContent"]`` so the apply result reflects the
        formatted bytes that were validated and left on disk.
        """
        formatting = self._config.v2.formatting
        if not formatting.use_external_formatter or not formatting.command:
            return

        try:
            base_argv = shlex.split(formatting.command)
        except ValueError as error:
            preview_result["formatting"] = {
                "ran": False,
                "command": formatting.command,
                "warnings": [f"external formatter command could not be parsed: {error}"],
            }
            return
        if not base_argv:
            preview_result["formatting"] = {
                "ran": False,
                "command": formatting.command,
                "warnings": ["external formatter command was empty after parsing"],
            }
            return

        # Changed/created files plus rename targets are the files now on disk that the edit produced. Deleted files are
        # intentionally excluded (they no longer exist). Paths are deduplicated and resolved against the project root.
        relative_files: list[str] = list(staged.changed_files.keys())
        relative_files.extend(rename["newPath"] for rename in staged.renamed_files)
        seen: set[str] = set()
        ordered_files = [rel for rel in relative_files if not (rel in seen or seen.add(rel))]

        warnings: list[str] = []
        formatted: list[str] = []
        formatted_content: dict[str, str] = {}
        for relative in ordered_files:
            absolute = str((self._project_root / relative).resolve())
            if "{file}" in base_argv:
                argv = [absolute if token == "{file}" else token.replace("{file}", absolute) for token in base_argv]
            else:
                argv = [*base_argv, absolute]
            try:
                completed = subprocess.run(
                    argv,
                    cwd=str(self._project_root),
                    capture_output=True,
                    text=True,
                    check=False,
                )
            except OSError as error:
                warnings.append(f"external formatter failed to run for {relative}: {error}")
                continue
            if completed.returncode != 0:
                detail = (completed.stderr or completed.stdout or "").strip()
                suffix = f": {detail}" if detail else ""
                warnings.append(f"external formatter exited with code {completed.returncode} for {relative}{suffix}")
                continue
            formatted.append(relative)
            # Capture the post-format on-disk text so the apply result reflects the bytes that javac post-validation
            # will check (and that remain on disk on success). A read failure is a warning, not a hard error.
            try:
                formatted_content[relative] = Path(absolute).read_text(encoding=self._source_encoding())
            except OSError as error:
                warnings.append(f"external formatter output for {relative} could not be read back: {error}")

        preview_result["formatting"] = {
            "ran": True,
            "command": formatting.command,
            "formattedFiles": formatted,
            "formattedContent": formatted_content,
            "warnings": warnings,
        }

    def _build_tool_validation_plan(self, build_tool: str | None) -> "dict[str, Any] | None":
        """Resolves the build-tool compile/test command plan for the active project, or ``None`` when unresolvable (B02).

        Returns ``{"tool": "maven"|"gradle", "compile": [argv...], "test": [argv...]}`` where each ``argv`` is the
        process command (wrapper-preferred) for that stage, or ``None`` when the project has no recognizable Maven/Gradle
        build model. The build tool is taken from the validated sidecar status (``buildTool``/``discoveryKind``). Maven
        compiles main+test sources via ``compile``/``test-compile`` (run together) and tests via ``test``; Gradle compiles
        via ``compileJava``/``compileTestJava`` and tests via ``test``. The wrapper script (``mvnw``/``gradlew``) is
        preferred when present in the project root so the project's pinned build version is used; otherwise the bare
        ``mvn``/``gradle`` on PATH is used.
        """
        if not build_tool:
            return None
        tool = str(build_tool).strip().lower()
        root = self._project_root
        if tool == "maven":
            wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
            launcher = [str(wrapper)] if wrapper.exists() else ["mvn"]
            return {
                "tool": "maven",
                "compile": [*launcher, "-q", "-B", "compile", "test-compile"],
                "test": [*launcher, "-q", "-B", "test"],
            }
        if tool == "gradle":
            wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
            launcher = [str(wrapper)] if wrapper.exists() else ["gradle"]
            return {
                "tool": "gradle",
                "compile": [*launcher, "--quiet", "--console=plain", "compileJava", "compileTestJava"],
                "test": [*launcher, "--quiet", "--console=plain", "test"],
            }
        return None

    def _invoke_build_tool(self, argv: list[str], timeout_seconds: int) -> "subprocess.CompletedProcess[str]":
        """Runs one build-tool command from the project root, capturing output (the stubbable subprocess seam for B02).

        Isolated as its own method so tests can monkeypatch the actual process invocation (Maven/Gradle are impractical in
        CI) while the real ``subprocess.run`` command construction and timeout enforcement above/below it are still
        exercised. Raises :class:`subprocess.TimeoutExpired` on timeout and :class:`OSError` when the launcher cannot be
        executed; the caller turns both into a structured validation failure.
        """
        return subprocess.run(
            argv,
            cwd=str(self._project_root),
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout_seconds,
        )

    def _run_build_tool_validation(self, client: JavaRefactorClient, operation: str) -> "dict[str, Any] | None":
        """Runs the optional post-javac build-tool compile/test validation stage (design §20; B02).

        Returns ``None`` when neither ``validation.run_build_tool_compile`` nor ``validation.run_tests`` is set (the stage
        is entirely off, the default). Otherwise returns a structured report ``{"ran", "tool", "stages": [...]}``; on
        failure the report additionally carries ``{"ok": False, "refusal": {code, message}}`` so the caller rolls the
        committed edit back. The stage runs AFTER the mandatory javac post-validation has passed, invoking the discovered
        build tool's compile (and, when ``run_tests`` is set, test) tasks with a hard ``max_validation_seconds`` timeout.

        Fails CLOSED on every error mode rather than silently ignoring the flags: when no Maven/Gradle build model can be
        resolved, when the launcher cannot be executed, when a stage exits non-zero, or when a stage exceeds the timeout,
        it returns a refusal report (``code`` one of ``build_tool_model_unavailable`` / ``build_tool_invocation_failed`` /
        ``build_tool_compile_failed`` / ``build_tool_tests_failed`` / ``build_tool_validation_timeout``).
        """
        validation = self._config.v3.validation
        if not validation.run_build_tool_compile and not validation.run_tests:
            return None

        status = client.status(refresh=False)
        plan = self._build_tool_validation_plan(status.build_tool)
        if plan is None:
            return {
                "ran": False,
                "tool": status.build_tool,
                "stages": [],
                "ok": False,
                "refusal": {
                    "code": "build_tool_model_unavailable",
                    "message": "Build-tool validation was requested (validation.run_build_tool_compile/run_tests) but no "
                    f"Maven or Gradle build model could be resolved for this project (buildTool={status.build_tool!r}), "
                    "so the edit was not accepted. Configure a recognizable build or disable the build-tool validation "
                    "flags.",
                },
            }

        # A misconfigured 0/negative max_validation_seconds would make every invocation an
        # immediate timeout; floor it at 1s so the build tool gets a real (if short) window.
        timeout_seconds = max(1, int(validation.max_validation_seconds))
        report: dict[str, Any] = {"ran": True, "tool": plan["tool"], "stages": []}

        stages: list[tuple[str, str, str]] = []
        # compile runs whenever EITHER flag is set: tests require compiled sources, so the compile stage is the
        # prerequisite for the test stage and is always run first when build-tool validation is active.
        stages.append(("compile", "build_tool_compile_failed", "compile"))
        if validation.run_tests:
            stages.append(("test", "build_tool_tests_failed", "test"))

        for stage_name, failure_code, plan_key in stages:
            argv = list(plan[plan_key])
            try:
                completed = self._invoke_build_tool(argv, timeout_seconds)
            except subprocess.TimeoutExpired:
                report["stages"].append({"stage": stage_name, "command": argv, "status": "timeout"})
                report["ok"] = False
                report["refusal"] = {
                    "code": "build_tool_validation_timeout",
                    "message": f"The build-tool {stage_name} stage ({' '.join(argv)}) exceeded the "
                    f"{timeout_seconds}s validation.max_validation_seconds timeout, so the edit was rolled back.",
                }
                return report
            except OSError as error:
                report["stages"].append({"stage": stage_name, "command": argv, "status": "error", "detail": str(error)})
                report["ok"] = False
                report["refusal"] = {
                    "code": "build_tool_invocation_failed",
                    "message": f"The build-tool {stage_name} stage could not be invoked ({' '.join(argv)}): {error}. "
                    "The edit was rolled back.",
                }
                return report
            stage_record = {
                "stage": stage_name,
                "command": argv,
                "exitCode": completed.returncode,
                "status": "ok" if completed.returncode == 0 else "failed",
            }
            if completed.returncode != 0:
                detail = (completed.stdout or "") + (("\n" + completed.stderr) if completed.stderr else "")
                stage_record["output"] = detail.strip()[-4000:]
                report["stages"].append(stage_record)
                report["ok"] = False
                report["refusal"] = {
                    "code": failure_code,
                    "message": f"The build-tool {stage_name} stage exited with code {completed.returncode} "
                    f"({' '.join(argv)}), so the edit was rolled back:\n{stage_record['output']}",
                }
                return report
            report["stages"].append(stage_record)

        report["ok"] = True
        return report

    def v2_refactor_session(
        self,
        operation: str,
        params: dict[str, Any],
        apply: bool = False,
        validate: bool | None = None,
        expected_project_revision: Any = None,
    ) -> dict[str, Any]:
        """Previews or applies a V2 refactor through the sidecar session protocol.

        The sidecar owns semantic planning and revision validation. Python remains the workspace writer: previews return
        the sidecar's revision-guarded session envelope, while applies first ask the sidecar to revalidate the session
        and then stage/commit the returned workspace edit through Serena's transactional applier.
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
        mode = "apply" if apply else "preview"
        capability_refusal = self._ensure_v2_capability(client, operation, mode)
        if capability_refusal is not None:
            return capability_refusal

        # create revision-guarded preview session
        created = client.create_session(operation, params)
        if not created.get("accepted"):
            return created

        applier = TransactionalWorkspaceEditApplier(
            self._project_root, encoding=self._source_encoding(), line_ending=self._project_line_ending
        )
        if not apply:
            validate_after_preview = self._config.validate_after_preview if validate is None else validate
            if validate_after_preview:
                try:
                    workspace_edit = RefactorWorkspaceEdit.from_protocol_dict(created["preview"]["workspaceEdit"])
                    staged = applier.stage(workspace_edit)
                except (WorkspaceEditError, KeyError, TypeError, ValueError) as error:
                    created["accepted"] = False
                    created["refusal"] = {
                        "code": "session_preview_unsafe_edit",
                        "message": f"V2 session preview refused because the planned edit could not be staged exactly: {error}",
                    }
                    return created
                created["previewValidation"] = self._staged_validation_report(client, staged)
            return created

        # Apply-path safety gate (H3/G002): refuse the session apply BEFORE asking the sidecar to revalidate or staging
        # any Python-side write when the active project model is degraded (conventional fallback => no resolved
        # classpath). Such a model cannot be trusted to validate the edit against the real build. Preview already
        # returned above, so it stays permissive; this only fails closed on the mutating apply branch.
        degraded_refusal = self._degraded_model_apply_refusal(client, operation)
        if degraded_refusal is not None:
            return degraded_refusal

        # ask the sidecar to revalidate the session before any Python-side write
        session = created.get("session") or {}
        session_id = session.get("sessionId")
        if not isinstance(session_id, str) or not session_id:
            return {
                "accepted": False,
                "applied": False,
                "operation": operation,
                "mode": "apply",
                "refusal": {
                    "code": "malformed_session_response",
                    "message": "The sidecar accepted a V2 session but did not return a sessionId; no files were written.",
                },
            }
        session_result = client.apply_session(session_id, expected_project_revision=expected_project_revision)
        if not session_result.get("accepted"):
            return session_result

        # stage the session workspace edit through Serena's existing transactional writer
        preview_result = dict(session_result.get("preview") or {})
        preview_result["session"] = session_result.get("session")
        preview_result["sessionValidation"] = session_result.get("validation")
        preview_result["mode"] = "apply"
        preview_result["operation"] = operation
        try:
            workspace_edit = RefactorWorkspaceEdit.from_protocol_dict(preview_result["workspaceEdit"])
        except (WorkspaceEditError, KeyError, TypeError, ValueError) as error:
            preview_result["accepted"] = False
            preview_result["applied"] = False
            preview_result["refusal"] = {
                "code": "malformed_session_workspace_edit",
                "message": f"The sidecar returned a malformed V2 session edit, so nothing was applied: {error}",
            }
            return preview_result

        return self._apply_v2_session_preview(client, applier, preview_result, workspace_edit, validate=validate)

    def create_v2_refactor_session(self, operation: str, params: dict[str, Any], validate: bool | None = None) -> dict[str, Any]:
        """Creates a V2 preview session without applying its workspace edit."""
        return self.v2_refactor_session(operation, params, apply=False, validate=validate)

    def get_v2_refactor_session_edit(
        self,
        session_id: str,
        validate: bool | None = None,
        edit_format: str | None = None,
        selection: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Retrieves a live V2 session edit by session ID.

        ``edit_format`` optionally selects the sidecar edit serialization format; it defaults to the first-class
        ``serenaWorkspaceEdit`` format (the Serena transactional-applier shape). An unknown format is refused by the
        sidecar with ``unsupported_edit_format`` (surfaced to the caller, not swallowed).

        ``selection`` optionally narrows the returned edit to a subset of the session's units (incremental apply): a
        mapping with any of ``files``/``edits``/``fileOperations``/``phases``. The envelope then carries only that
        subset plus a ``remaining`` report of the still-unapplied units.
        """
        if not self._config.enabled:
            return {
                "accepted": False,
                "applied": False,
                "operation": "javaRefactorSession",
                "mode": "preview",
                "refusal": {"code": "java_refactor_disabled", "message": "Java refactoring is disabled for this project."},
            }

        self._validate_supported_project()
        client = self._get_or_start_client(refresh=False)
        result = client.get_session_edit(
            session_id, edit_format=edit_format or SERENA_WORKSPACE_EDIT_FORMAT, selection=selection
        )
        if not result.get("accepted"):
            return result

        validate_preview = self._config.validate_after_preview if validate is None else validate
        if validate_preview:
            try:
                workspace_edit = RefactorWorkspaceEdit.from_protocol_dict(result["preview"]["workspaceEdit"])
                applier = TransactionalWorkspaceEditApplier(
                    self._project_root, encoding=self._source_encoding(), line_ending=self._project_line_ending
                )
                staged = applier.stage(workspace_edit)
            except (WorkspaceEditError, KeyError, TypeError, ValueError) as error:
                result["accepted"] = False
                result["refusal"] = {
                    "code": "session_preview_unsafe_edit",
                    "message": f"V2 session preview refused because the planned edit could not be staged exactly: {error}",
                }
                return result
            result["previewValidation"] = self._staged_validation_report(client, staged)
        return result

    def apply_v2_refactor_session(
        self,
        session_id: str,
        validate: bool | None = None,
        expected_project_revision: Any = None,
        selection: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Applies a previously created V2 session through the transactional writer.

        ``expected_project_revision`` pins an optimistic-concurrency guard forwarded to the sidecar: the session's
        create-time project revision must still match or the sidecar refuses with ``project_revision_mismatch``.

        ``selection`` optionally applies only a subset of the session's edits (incremental apply): a mapping with any of
        ``files``/``edits``/``fileOperations``/``phases``. The sidecar validates that subset overlay via javac, surfaces
        it for application, and reports the still-unapplied ``remaining`` units a later apply can target. Omitting
        ``selection`` applies the whole session (or, once a subset has been applied, the remaining units).
        """
        if not self._config.enabled:
            return {
                "accepted": False,
                "applied": False,
                "operation": "javaRefactorSession",
                "mode": "apply",
                "refusal": {"code": "java_refactor_disabled", "message": "Java refactoring is disabled for this project."},
            }

        self._validate_supported_project()
        client = self._get_or_start_client(refresh=False)

        # Apply-path safety gate (H3/G002): refuse BEFORE the sidecar apply_session revalidation and any Python-side
        # write when the active project model is degraded (conventional fallback => no resolved classpath). The session
        # id alone does not carry the operation name yet, so the refusal is labeled with the generic session operation.
        degraded_refusal = self._degraded_model_apply_refusal(client, "javaRefactorSession")
        if degraded_refusal is not None:
            return degraded_refusal

        session_result = client.apply_session(
            session_id, expected_project_revision=expected_project_revision, selection=selection
        )
        if not session_result.get("accepted"):
            return session_result

        preview_result = dict(session_result.get("preview") or {})
        preview_result["session"] = session_result.get("session")
        preview_result["sessionValidation"] = session_result.get("validation")
        preview_result["mode"] = "apply"
        preview_result["operation"] = preview_result.get("operation") or "javaRefactorSession"
        # G001: propagate the incremental-apply surface so a caller can drive subset-then-remainder applies. ``remaining``
        # lists the still-unapplied units; ``complete`` is true once every unit has been applied.
        for incremental_field in ("incremental", "complete", "remaining", "selectionModel"):
            if incremental_field in session_result:
                preview_result[incremental_field] = session_result.get(incremental_field)
        try:
            workspace_edit = RefactorWorkspaceEdit.from_protocol_dict(preview_result["workspaceEdit"])
        except (WorkspaceEditError, KeyError, TypeError, ValueError) as error:
            preview_result["accepted"] = False
            preview_result["applied"] = False
            preview_result["refusal"] = {
                "code": "malformed_session_workspace_edit",
                "message": f"The sidecar returned a malformed V2 session edit, so nothing was applied: {error}",
            }
            return preview_result

        applier = TransactionalWorkspaceEditApplier(
            self._project_root, encoding=self._source_encoding(), line_ending=self._project_line_ending
        )
        # G001: the sidecar surfaced these unit ids for application but has NOT recorded them as applied — session state
        # must reflect committed disk state, not edit-envelope emission. We acknowledge them back to the sidecar ONLY
        # after the transactional commit + post-validation below succeed. A staging/commit/post-validation failure
        # therefore leaves the session reporting these units as still unapplied.
        pending_unit_ids = session_result.get("pendingUnitIds") or []
        result = self._apply_v2_session_preview(client, applier, preview_result, workspace_edit, validate=validate)
        committed = bool(result.get("accepted")) and bool(result.get("applied")) and not result.get("rolledBack")
        if pending_unit_ids and committed:
            ack = client.ack_session_apply(session_id, pending_unit_ids)
            result["ack"] = ack
            # Re-surface the now-authoritative incremental state from the committed-and-acknowledged session.
            for ack_field in ("complete", "remaining"):
                if ack_field in ack:
                    result[ack_field] = ack.get(ack_field)
        return result

    def cancel_v2_refactor_session(self, session_id: str) -> dict[str, Any]:
        """Cancels a live V2 session by session ID."""
        if not self._config.enabled:
            return {
                "accepted": False,
                "applied": False,
                "operation": "javaRefactorSession",
                "mode": "cancel",
                "refusal": {"code": "java_refactor_disabled", "message": "Java refactoring is disabled for this project."},
            }
        self._validate_supported_project()
        return self._get_or_start_client(refresh=False).cancel_session(session_id)

    def new_workspace_edit_applier(self) -> TransactionalWorkspaceEditApplier:
        """Constructs a fresh transactional applier bound to this project's root/encoding/line-ending.

        The V3 transformation workspace engine drives a single all-or-nothing commit through one applier, so
        it asks the manager for one rather than reaching into private construction details. This mirrors the
        applier the V2 apply path builds (see :meth:`apply_v2_refactor_session`).
        """
        return TransactionalWorkspaceEditApplier(
            self._project_root, encoding=self._source_encoding(), line_ending=self._project_line_ending
        )

    # Compute-only sidecar dispatch for every V3 op whose edit is enrollable in a transformation workspace. Each entry
    # builds the SAME compute-only sidecar payload the corresponding op method computes, but with ``validate=False`` (the
    # byte-identical, javac-skipping path also used by ``propagate_safe_delete``): the transformation workspace owns the
    # single validation seam (its transactional applier revalidates every file's hash precondition when staging the
    # composed plan), exactly as it does for enrolled V2 sessions, so per-member javac validation is neither double-run
    # nor split across layers.
    def _v3_plan_dispatch(self) -> dict[str, "Callable[[JavaRefactorClient, dict[str, Any]], dict]"]:
        from serena.java_refactor_v3.class_refactor_client import ClassRefactorClient
        from serena.java_refactor_v3.conversions_client import ConversionsClient
        from serena.java_refactor_v3.deletion_client import DeletionClient
        from serena.java_refactor_v3.inline_refactor_client import InlineRefactorClient
        from serena.java_refactor_v3.recipe_engine_client import RecipeEngineClient

        def _extract_class(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            return ClassRefactorClient(client).extract_class(
                params["relative_path"],
                params["new_class_name"],
                list(params["members"]),
                target_package=params.get("target_package"),
                leave_delegate_methods=params.get("leave_delegate_methods", True),
                update_usages=params.get("update_usages", False),
                validate=False,
            )

        def _extract_superclass(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            return ClassRefactorClient(client).extract_superclass(
                list(params["classes"]),
                params["superclass_name"],
                list(params["members"]),
                target_package=params.get("target_package"),
                make_abstract=params.get("make_abstract", False),
                validate=False,
            )

        def _replace_inheritance(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            return ClassRefactorClient(client).replace_inheritance_with_delegation(
                params["relative_path"],
                members=params.get("members"),
                delegate_field_name=params.get("delegate_field_name"),
                superclass_fqn=params.get("superclass_fqn"),
                confirm_public_api_change=params.get("confirm_public_api_change", False),
                validate=False,
            )

        def _deep_inline(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            return InlineRefactorClient(client).deep_inline_method(
                params["relative_path"],
                params["line"],
                column=params.get("column"),
                method_name=params.get("method_name"),
                delete_method=params.get("delete_method", False),
                max_call_sites=params.get("max_call_sites"),
                validate=False,
            )

        def _anon_to_lambda(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            return ConversionsClient(client).anonymous_to_lambda(
                params["relative_path"],
                params["line"],
                column=params.get("column"),
                validate=False,
            )

        def _lambda_to_method_ref(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            return ConversionsClient(client).lambda_to_method_reference(
                params["relative_path"],
                params["line"],
                column=params.get("column"),
                validate=False,
            )

        def _propagate_safe_delete(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            kwargs: dict[str, Any] = {
                "validate": False,
                "delete_private_only": params.get("delete_private_only", True),
                "include_tests": params.get("include_tests", False),
                "include_resources": params.get("include_resources", True),
            }
            if params.get("cascade_depth") is not None:
                kwargs["max_cascade_depth"] = params["cascade_depth"]
            return DeletionClient(client).propagate_safe_delete(list(params["seeds"]), **kwargs)

        def _apply_recipe(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            # Resolve the recipe exactly as the standalone op does (built-in name XOR inline document), failing closed to
            # the same recipe_* refusal envelope so a bad selection is never enrolled.
            selection = self._select_recipe(
                "applyRefactorRecipe", False, params.get("recipe_name"), params.get("recipe_document")
            )
            if isinstance(selection, dict):
                return selection
            recipe_id, recipe_obj = selection
            return RecipeEngineClient(client).apply_recipe(
                recipe_id=recipe_id,
                recipe=recipe_obj,
                apply_needs_review=params.get("allow_review_required", False),
                validate=False,
                scope=params.get("scope", "project"),
            )

        # The package-relocation ops compute their edit through the sidecar's no-write ``preview`` request (it never
        # touches disk), and the sidecar wraps every package result in the canonical §1.1 impact + §14.3 risk envelope
        # (Main.java augments renamePackage/movePackage/moveSourceRoot on the generic preview path), so the returned
        # payload carries ``accepted``/``workspaceEdit``/``risk`` exactly like the other compute-only V3 op payloads and
        # flows through the shared ``_extract_sidecar_v3_edit`` seam unchanged.
        def _rename_package(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            preview_params: dict[str, Any] = {
                "oldPackage": params["old_package"],
                "newPackage": params["new_package"],
                "includeSubpackages": params.get("include_subpackages", True),
            }
            if params.get("rewrite_resources") is not None:
                preview_params["rewriteResources"] = params["rewrite_resources"]
            if params.get("rewrite_module_info") is not None:
                preview_params["rewriteModuleInfo"] = params["rewrite_module_info"]
            if params.get("module_strategy") is not None:
                preview_params["moduleStrategy"] = params["module_strategy"]
            return client.preview("renamePackage", preview_params)

        def _move_package(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            preview_params: dict[str, Any] = {
                "sourcePackage": params["source_package"],
                "targetPackage": params["target_package"],
                "includeSubpackages": params.get("include_subpackages", True),
            }
            if params.get("target_source_root") is not None:
                preview_params["targetSourceRoot"] = params["target_source_root"]
            if params.get("rewrite_resources") is not None:
                preview_params["rewriteResources"] = params["rewrite_resources"]
            if params.get("rewrite_module_info") is not None:
                preview_params["rewriteModuleInfo"] = params["rewrite_module_info"]
            if params.get("module_strategy") is not None:
                preview_params["moduleStrategy"] = params["module_strategy"]
            return client.preview("movePackage", preview_params)

        def _move_source_root(client: JavaRefactorClient, params: dict[str, Any]) -> dict:
            preview_params: dict[str, Any] = {
                "sourceRoot": params["source_root"],
                "targetSourceRoot": params["target_source_root"],
                "includeSubpackages": params.get("include_subpackages", True),
                "rewriteBuildFiles": params.get("rewrite_build_files", False),
                "preservePackageNames": params.get("preserve_package_names", True),
            }
            if params.get("packages"):
                preview_params["packages"] = list(params["packages"])
            return client.preview("moveSourceRoot", preview_params)

        return {
            "extractClass": _extract_class,
            "extractSuperclass": _extract_superclass,
            "replaceInheritanceWithDelegation": _replace_inheritance,
            "deepInlineMethod": _deep_inline,
            "convertAnonymousToLambda": _anon_to_lambda,
            "convertLambdaToMethodReference": _lambda_to_method_ref,
            "propagateSafeDelete": _propagate_safe_delete,
            "applyRefactorRecipe": _apply_recipe,
            "renamePackage": _rename_package,
            "movePackage": _move_package,
            "moveSourceRoot": _move_source_root,
        }

    @staticmethod
    def _v3_project_revision(model: dict[str, Any]) -> str | None:
        """Returns the revision a V3 op should pin against the workspace, or ``None`` when none can be derived (B04).

        Prefers the sidecar's validated ``modelHash`` (the canonical revision token the V2 session path also uses). When
        that is absent, derives a DETERMINISTIC replacement from the validated model's structural fingerprint so two ops
        planned against the same on-disk model still pin to the SAME revision (the workspace's single-revision invariant
        stays enforceable) while a structurally different model yields a different pin. The fingerprint is a stable
        SHA-256 over the model's identifying fields — source roots/sets, classpath, the Java-file inventory and count, the
        discovery kind, and any per-file content hashes the model carries — serialized canonically (sorted keys) so the
        result is independent of dict ordering. Returns ``None`` only when the model carries none of these fields, in
        which case the caller fails closed rather than enrolling with a permissive None.
        """
        model_hash = model.get("modelHash")
        if isinstance(model_hash, str) and model_hash:
            return model_hash

        fingerprint_keys = (
            "sourceRoots",
            "sourceSets",
            "sourceSetCount",
            "classpath",
            "javaFiles",
            "javaFileCount",
            "discoveryKind",
            "fileHashes",
            "fileContentHashes",
        )
        fingerprint = {key: model[key] for key in fingerprint_keys if key in model and model[key] is not None}
        if not fingerprint:
            return None
        try:
            canonical = json.dumps(fingerprint, sort_keys=True, default=str, separators=(",", ":"))
        except (TypeError, ValueError):
            return None
        return "derived:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    def plan_v3_operation(self, operation: str, params: dict[str, Any]) -> "V3OperationPlan | dict[str, Any]":
        """Plans a V3 op compute-only and returns its parsed :class:`RefactorWorkspaceEdit` for workspace enrollment.

        This is the V3 half of the :class:`~serena.java_refactor_v3.workspace.SessionDriver` protocol: it dispatches the
        given V3 ``operation`` to the sidecar in COMPUTE-ONLY mode (``validate=False``, nothing written), then runs the
        SAME extraction the apply bridge uses (:meth:`_extract_sidecar_v3_edit`) to obtain the parsed, hash-guarded
        ``RefactorWorkspaceEdit`` plus its risk and warnings WITHOUT applying or javac-validating it here — the
        transformation workspace owns the single validation seam when it stages the composed plan.

        Returns a :class:`~serena.java_refactor_v3.workspace.V3OperationPlan` on success. A sidecar refusal
        (``accepted: false``), a malformed edit, or an unclassified risk is returned VERBATIM as the structured refusal
        dict the extractor produces, so a refused op is never enrolled. An unknown operation or a disabled project is
        likewise returned as a structured refusal dict (nothing planned).
        """
        from serena.java_refactor_v3.workspace import V3OperationPlan

        disabled = self._v3_disabled_refusal(operation, apply=False)
        if disabled is not None:
            return disabled

        dispatch = self._v3_plan_dispatch()
        planner = dispatch.get(operation)
        if planner is None:
            return {
                "accepted": False,
                "applied": False,
                "operation": operation,
                "mode": "preview",
                "refusal": {
                    "code": "unsupported_workspace_operation",
                    "message": f"Operation {operation!r} is not an enrollable V3 transformation op; "
                    f"enroll one of {sorted(dispatch)}.",
                },
            }

        self._validate_supported_project()
        client = self._get_or_start_client(refresh=False)
        payload = planner(client, params)

        extracted = self._extract_sidecar_v3_edit(operation, payload, mode="preview")
        if isinstance(extracted, dict):
            return extracted
        workspace_edit, risk, warnings, _summary = extracted

        # Source the V3 member's project revision so a mixed workspace's revision pin is consistent across V2 sessions and
        # V3 ops. Prefer the sidecar's validated ``modelHash``; when it is absent (B04: an older sidecar, or a model that
        # never computed one), DO NOT enroll with a permissive None — that would leave the workspace revision guard unable
        # to detect a cross-member revision drift. Instead compute a DETERMINISTIC replacement revision from the validated
        # model's structural fingerprint (a stable, repeatable hash over the same on-disk model), so two ops planned
        # against the same model still pin identically and a drifted model produces a different pin. If even that
        # fingerprint cannot be derived (no usable model fields), fail CLOSED with a structured refusal rather than
        # enrolling unguarded.
        status = client.status(refresh=False)
        model = status.project_model or {}
        project_revision = self._v3_project_revision(model)
        if project_revision is None:
            return {
                "accepted": False,
                "applied": False,
                "operation": operation,
                "mode": "preview",
                "refusal": {
                    "code": "project_revision_unavailable",
                    "message": "The V3 op was planned but the sidecar reported no model hash and no deterministic "
                    "project revision could be derived from the validated model, so it was not enrolled: the "
                    "transformation workspace could not pin a revision to guard against cross-member drift (nothing was "
                    "written). Refresh the Java project model and retry.",
                },
            }

        return V3OperationPlan(
            operation=operation,
            project_revision=project_revision,
            workspace_edit=workspace_edit,
            risk=risk,
            warnings=warnings,
        )

    @property
    def transformation_workspaces(self) -> "TransformationWorkspaceManager":
        """Returns the lazily-created V3 transformation-workspace manager for this project.

        The manager groups multiple V2 refactor sessions under one revision-guarded workspace and drives
        workspace-level preview/apply/cancel, using this :class:`JavaRefactorManager` as its session driver
        (it satisfies the ``SessionDriver`` protocol via ``create_v2_refactor_session``,
        ``cancel_v2_refactor_session`` and ``new_workspace_edit_applier``).
        """
        if self._transformation_workspaces is None:
            from serena.java_refactor_v3 import TransformationWorkspaceManager

            self._transformation_workspaces = TransformationWorkspaceManager(self)
        return self._transformation_workspaces

    def transformation_client(self) -> "TransformationClient":
        """Returns a sidecar-backed transformation client bound to this project's live sidecar (Phase 1).

        Unlike :attr:`transformation_workspaces` (the V2-session composition engine), this client drives the sidecar's
        ``transformation.*`` protocol directly: the Java sidecar runs the V3 operation planner(s), composes the edits,
        validates the composed overlay once, and returns the authoritative preview-ready workspace edit. Composition and
        same-file/overlap conflict detection now live in the sidecar, not in Python.
        """
        from serena.java_refactor_v3 import TransformationClient

        return TransformationClient(self._get_or_start_client(refresh=False))

    @staticmethod
    def _hint_params(target_hints: dict | None) -> dict:
        """The protocol fields for caller-supplied target-identity hints (``nameHint``/``kindHint``/``arityHint``)."""
        if not target_hints:
            return {}
        return {key: value for key, value in target_hints.items() if key in ("nameHint", "kindHint", "arityHint") and value is not None}

    def _preview_or_apply_refactor(
        self,
        operation: str,
        params: dict,
        apply: bool,
        validate: bool | None = None,
        allow_review_required: bool = False,
    ) -> dict:
        """Previews or applies one Java refactoring workspace edit.

        :param validate: governs PREVIEW-time validation reporting only (whether a preview also runs the staged
            in-memory javac validation and surfaces it under ``previewValidation``). It has NO effect on the apply
            safety gate: an apply ALWAYS runs post-commit validation with rollback and (for rename) old-key residual
            verification, regardless of ``validate`` or any config knob (G001). Staged pre-commit javac validation
            runs on apply unless the project's ``validate_before_apply`` config disables it (post-commit validation
            still rolls a broken commit back in that case).
        :param allow_review_required: B1 uniform apply-policy gate for the package-relocation ops (``renamePackage`` /
            ``movePackage``). Those results are run through the sidecar's :class:`CanonicalEnvelope` and therefore carry
            a §14.3 ``risk`` like every other V3 edit op. On apply, a ``needs_review`` (REVIEW_REQUIRED) package result
            is refused UNLESS the caller explicitly opts in via this flag — consistent with the
            :meth:`_bridge_v3_edit` gate the other V3 edit tools use. It defaults to ``False`` (block) and has NO effect
            on preview or on ops whose sidecar result carries no ``risk`` (semanticRename/safeDelete/move-top-level/
            inline*), so existing callers are unaffected.
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

        # B1 uniform apply-policy gate for the package-relocation ops. These results are augmented in the sidecar by
        # CanonicalEnvelope, so an accepted package result carries an explicit §14.3 ``risk`` ("safe"/"needs_review").
        # On apply we enforce the SAME taxonomy as every other Python-routed V3 edit op (_bridge_v3_edit §18): SAFE
        # applies; REVIEW_REQUIRED is blocked UNLESS the caller opts in via ``allow_review_required``. The gate runs
        # BEFORE the workspace is mutated (nothing staged/written on refusal). Preview is never gated, and ops whose
        # sidecar result carries no ``risk`` (semanticRename/safeDelete/moveTopLevelType/inline*) are unaffected.
        if apply and operation in ("renamePackage", "movePackage") and "risk" in result:
            from serena.java_refactor_v3.models import RiskLevel

            try:
                package_risk = RiskLevel.from_sidecar_wire(result.get("risk"))
            except ValueError as error:
                result["accepted"] = False
                result["applied"] = False
                result["editsAlreadyApplied"] = False
                result["refusal"] = {
                    "code": "unclassified_risk",
                    "message": "The sidecar returned an accepted package edit without an explicit risk classification, "
                    f"so nothing was staged or applied: {error}",
                }
                return result
            if package_risk is RiskLevel.REVIEW_REQUIRED and not allow_review_required:
                result["accepted"] = False
                result["applied"] = False
                result["editsAlreadyApplied"] = False
                result["refusal"] = {
                    "code": "review_required",
                    "message": "This package edit is classified REVIEW_REQUIRED (it crosses a public-API/framework/"
                    "resource boundary, relies on a heuristic, or its resource scan was incomplete), so it was not "
                    "applied. Re-run with allow_review_required=true to apply it after review (no files were written).",
                }
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
        baseline_errors: list[dict[str, Any]] = []
        baseline_warnings: list[dict[str, Any]] = []
        if validate_before_apply or self._config.allow_incomplete_analysis:
            try:
                baseline_validation = self._checked_validate_edit(client, _EMPTY_OVERLAY)
                baseline_errors = self._compiler_errors(baseline_validation)
                baseline_warnings = self._compiler_warnings(baseline_validation)
            except ValidationRefusedError as error:
                return self._validation_refused_apply_result(result, error, stage="baseline")

        if validate_before_apply:
            # Diff the staged overlay's `compilerErrors` (real, unsuppressed javac errors) against the baseline. Complete
            # mode rejects ALL staged errors; allow_incomplete_analysis (opt-in) tolerates pre-existing diagnostics and
            # rejects only the errors the edit newly introduced. Nothing is written when this refuses.
            try:
                staged_validation = self._checked_validate_edit(client, staged.overlay())
                staged_errors = self._compiler_errors(staged_validation)
                staged_warnings = self._compiler_warnings(staged_validation)
            except ValidationRefusedError as error:
                return self._validation_refused_apply_result(result, error, stage="staged pre-commit")
            diagnostic_delta = _diagnostic_delta(baseline_errors, staged_errors, baseline_warnings, staged_warnings)
            new_errors = _diagnostic_displays(diagnostic_delta["newErrors"])
            blocking = new_errors if self._config.allow_incomplete_analysis else _diagnostic_displays(staged_errors)
            if blocking:
                pre_existing = _diagnostic_displays(diagnostic_delta["unchangedErrors"])
                result["accepted"] = False
                result["applied"] = False
                result["editsAlreadyApplied"] = False
                result["preValidation"] = {
                    "ready": False,
                    "errors": blocking,
                    "newErrors": new_errors,
                    "preExistingErrors": pre_existing,
                    "resolvedErrors": _diagnostic_displays(diagnostic_delta["resolvedErrors"]),
                    "unchangedErrors": pre_existing,
                    "warnings": _diagnostic_displays(staged_warnings),
                    "newWarnings": _diagnostic_displays(diagnostic_delta["newWarnings"]),
                    "diagnosticDelta": diagnostic_delta,
                }
                detail = "newly introduced compiler errors" if self._config.allow_incomplete_analysis else "compiler errors"
                # G002: surface the precise policy distinction (new vs. tolerated-only-under-incomplete pre-existing
                # errors) as a structured sub-refusal while keeping the stable top-level pre_apply_validation_failed code.
                validation_code = "new_compiler_errors" if new_errors else "preexisting_compiler_errors_not_allowed"
                result["refusal"] = {
                    "code": "pre_apply_validation_failed",
                    "message": f"Edits were not applied because staged javac pre-validation found {detail} (no files "
                    "were written):\n" + "\n".join(blocking),
                    "validationRefusal": {"code": validation_code, "diagnosticDelta": diagnostic_delta},
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
        post_errors = _diagnostics(post_status.errors, "error") if not post_status.ready else []
        post_warnings = _diagnostics((post_status.project_model or {}).get("warnings", []), "warning")
        post_delta = _diagnostic_delta(baseline_errors, post_errors, baseline_warnings, post_warnings)
        if self._config.allow_incomplete_analysis:
            try:
                post_validation_response = self._checked_validate_edit(client, _EMPTY_OVERLAY)
                post_errors = self._compiler_errors(post_validation_response)
                post_warnings = self._compiler_warnings(post_validation_response)
                post_delta = _diagnostic_delta(baseline_errors, post_errors, baseline_warnings, post_warnings)
            except ValidationRefusedError as error:
                post_delta = _diagnostic_delta(
                    baseline_errors,
                    _diagnostics([f"post-apply javac revalidation was refused by the sidecar: [{error.code}] {error.message}"], "error"),
                    baseline_warnings,
                    post_warnings,
                )
        result["postValidation"] = {
            "ready": not post_delta["newErrors"] and (self._config.allow_incomplete_analysis or not post_errors),
            "errors": _diagnostic_displays(post_delta["newErrors"])
            if self._config.allow_incomplete_analysis
            else _diagnostic_displays(post_errors),
            "newErrors": _diagnostic_displays(post_delta["newErrors"]),
            "resolvedErrors": _diagnostic_displays(post_delta["resolvedErrors"]),
            "unchangedErrors": _diagnostic_displays(post_delta["unchangedErrors"]),
            "warnings": _diagnostic_displays(post_warnings),
            "newWarnings": _diagnostic_displays(post_delta["newWarnings"]),
            "diagnosticDelta": post_delta,
        }
        post_failure_errors: list[str] = list(result["postValidation"]["errors"])
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
        """Runs staged javac validation and returns a full V2 DiagnosticDelta report."""
        try:
            baseline_validation = self._checked_validate_edit(client, _EMPTY_OVERLAY)
            staged_validation = self._checked_validate_edit(client, staged.overlay())
        except ValidationRefusedError as error:
            return {
                "ready": False,
                "errors": [f"javac validation was refused by the sidecar: [{error.code}] {error.message}"],
                "newErrors": [],
                "preExistingErrors": [],
                "warnings": [],
                "diagnosticDelta": _diagnostic_delta([], []),
                "refusal": {"code": error.code, "message": error.message},
            }

        baseline_errors = self._compiler_errors(baseline_validation)
        staged_errors = self._compiler_errors(staged_validation)
        baseline_warnings = self._compiler_warnings(baseline_validation)
        staged_warnings = self._compiler_warnings(staged_validation)
        diagnostic_delta = _diagnostic_delta(baseline_errors, staged_errors, baseline_warnings, staged_warnings)
        new_errors = _diagnostic_displays(diagnostic_delta["newErrors"])
        unchanged_errors = _diagnostic_displays(diagnostic_delta["unchangedErrors"])
        blocking = new_errors if self._config.allow_incomplete_analysis else _diagnostic_displays(staged_errors)
        report: dict[str, Any] = {
            "ready": not blocking,
            "errors": blocking,
            "newErrors": new_errors,
            "preExistingErrors": unchanged_errors,
            "resolvedErrors": _diagnostic_displays(diagnostic_delta["resolvedErrors"]),
            "unchangedErrors": unchanged_errors,
            "warnings": _diagnostic_displays(staged_warnings),
            "newWarnings": _diagnostic_displays(diagnostic_delta["newWarnings"]),
            "diagnosticDelta": diagnostic_delta,
        }
        if blocking:
            # G002: distinguish "the edit introduced compiler errors" from "the project already does not compile and
            # complete-analysis mode forbids accepting an edit that leaves those pre-existing errors". The latter only
            # arises with allow_incomplete_analysis off; opting in narrows `blocking` to newly introduced errors above,
            # so a purely pre-existing failure can never reach this branch in incomplete mode.
            if new_errors:
                report["refusal"] = {
                    "code": "new_compiler_errors",
                    "message": "Staged javac validation found newly introduced compiler errors:\n" + "\n".join(new_errors),
                    "diagnosticDelta": diagnostic_delta,
                }
            else:
                report["refusal"] = {
                    "code": "preexisting_compiler_errors_not_allowed",
                    "message": "Staged javac validation found pre-existing compiler errors and complete-analysis mode "
                    "requires the after-state to compile cleanly. Set java_refactor.allow_incomplete_analysis: true to "
                    "tolerate unchanged pre-existing errors:\n" + "\n".join(blocking),
                    "diagnosticDelta": diagnostic_delta,
                }
        return report

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
    def _compiler_errors(validation: dict) -> list[dict[str, Any]]:
        """The real, unsuppressed javac errors from a validateEdit response, as structured DiagnosticInfo dicts (G003).

        Prefers ``compilerErrors`` (always the true errors, even when allow_incomplete_analysis routes them into
        ``warnings`` for presentation) and falls back to ``errors`` for older sidecar responses. Each entry is
        normalized via :func:`_diagnostic_info`, so both legacy display strings and structured objects are accepted.
        """
        if "compilerErrors" in validation:
            return _diagnostics(validation.get("compilerErrors"), "error")
        return _diagnostics(validation.get("errors"), "error")

    @staticmethod
    def _compiler_warnings(validation: dict) -> list[dict[str, Any]]:
        """The javac warning diagnostics from a validateEdit response, as structured DiagnosticInfo dicts."""
        return _diagnostics(validation.get("compilerWarnings") or validation.get("warnings"), "warning")

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
        java_refactor_config: dict[str, Any] = {}
        # The v2 sub-tree is a strictly-typed dataclass; serialize it via dataclasses.asdict() so it lands on the wire
        # as the same snake_case object the sidecar's expandNestedV2Config already accepts.
        if self._config.v2 is not None:
            v2_payload = dataclasses.asdict(self._config.v2)
            # G006: the typed lombok.jar/lombok.classpath settings are mapped to the sidecar's flat discovery keys
            # (lombokJar/lombokClasspath at the v2 top level), which expandNestedV2Config copies into the discovery
            # config so the build model puts Lombok on the classpath + annotation-processor path. This is the
            # end-to-end path that makes Lombok-generated members resolvable; the nested lombok block keeps `allow`.
            lombok_payload = v2_payload.get("lombok")
            if isinstance(lombok_payload, dict):
                lombok_jar = lombok_payload.get("jar")
                if lombok_jar:
                    v2_payload["lombokJar"] = lombok_jar
                lombok_classpath = lombok_payload.get("classpath")
                if lombok_classpath:
                    v2_payload["lombokClasspath"] = list(lombok_classpath)
            java_refactor_config["v2"] = v2_payload
        # The v3 sub-tree (packages/resources policy, §5.4/§5.5) travels as the same snake_case object the sidecar's
        # PackageRewritePolicy.fromConfig reads from java_refactor.v3; absent keys fall back to the sidecar defaults.
        if self._config.v3 is not None:
            java_refactor_config["v3"] = dataclasses.asdict(self._config.v3)
        if self._config.model:
            java_refactor_config["model"] = deepcopy(self._config.model)
        if java_refactor_config:
            payload["java_refactor"] = java_refactor_config
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
