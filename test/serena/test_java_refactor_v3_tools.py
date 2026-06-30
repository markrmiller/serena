from serena.tools.java_refactor_v3_tools import JavaPropagateSafeDeleteTool
"""Tool-layer forwarding contract tests for the V3 Java refactoring tools.

These are deliberately lightweight: they prove that each ``Java*Tool.apply()`` forwards to the matching
:class:`JavaRefactorManager` method with the Design-Y contract (project-relative path + line/column + native selectors),
WITHOUT standing up the live sidecar or the full Tool/agent framework. The transformation tools previously drifted from
the manager signatures (e.g. passing a non-existent ``occurrence`` kwarg) and nothing caught it because no test exercised
the tool layer; this file closes that gap so the schema can't silently re-diverge from the manager.

The strategy: ``apply`` is invoked as an unbound function against a tiny stub ``self`` that supplies the three base-class
hooks (``_get_manager`` -> a mock manager, ``_resolve_preview`` -> a recorded boolean, ``_finalize_result`` -> identity).
The module-level ``_parse_member_names`` parser runs for real. We then assert the exact (args, kwargs) the tool handed to
the manager.
"""

from typing import Any, cast

import inspect
from unittest.mock import MagicMock

from serena.java_refactor.manager import JavaRefactorManager
from serena.java_refactor_v3.reports import acceptance_matrix
from serena.tools.java_refactor_v3_tools import (
    JAVA_REFACTOR_V3_TOOL_CLASSES,
    V3_MATRIX_TOOL_BINDINGS,
    JavaAddWorkspaceOperationTool,
    JavaAddWorkspaceSessionTool,
    JavaApplyRefactorRecipeTool,
    JavaApplyTransformationWorkspaceTool,
    JavaCancelTransformationWorkspaceTool,
    JavaConvertAnonymousToLambdaTool,
    JavaConvertLambdaToMethodReferenceTool,
    JavaCreateTransformationWorkspaceTool,
    JavaDeepInlineMethodTool,
    JavaExtractClassTool,
    JavaExtractSuperclassTool,
    JavaFindDeadCodeTool,
    JavaFrameworkDetectTool,
    JavaImpactReportTool,
    JavaListTransformationWorkspacesTool,
    JavaPlanResourceEditsTool,
    JavaPreviewTransformationWorkspaceTool,
    JavaReplaceInheritanceWithDelegationTool,
    JavaResourceReferencesTool,
    JavaScanMigrationOpportunitiesTool,
    JavaTransformationGraphTool,
)


class _StubTool:
    """Minimal ``self`` for invoking a tool's ``apply`` without the Tool/agent framework.

    Records the resolved preview value it was asked for and returns the manager result verbatim from
    ``_finalize_result`` so a test can read back exactly what the tool forwarded.
    """

    def __init__(self, preview_returns: bool = True) -> None:
        self.manager = MagicMock(name="JavaRefactorManager")
        self._preview_returns = preview_returns

    def _get_manager(self):
        return self.manager

    def _resolve_preview(self, preview):
        # Mirror the real base: an explicit caller value wins; otherwise the recorded project default.
        return preview if preview is not None else self._preview_returns

    def _finalize_result(self, result):
        return result

    def _resolve_target(self, relative_path, name_path, line, column):
        # Mirror the real base's identity guard: record the call and return a placeholder. Tools use the call only to
        # verify the symbol resolves (it raises ValueError on a miss), not for its return value.
        self.resolved_target = (relative_path, name_path, line, column)
        return MagicMock(name="ResolvedSymbol")

    def _limit_length(self, result, max_answer_chars):
        # Mirror the real base's identity passthrough so tests can read back the forwarded result verbatim.
        return result


def _invoke(tool_cls, stub, manager_method, **kwargs):
    """Calls ``tool_cls.apply`` against ``stub`` and returns the (args, kwargs) it forwarded to ``manager_method``.

    Crucially, the forwarded call is then bound against the REAL :class:`JavaRefactorManager` method signature, so a tool
    that hands the manager an argument it does not accept (the exact drift this file guards against) fails here with a
    ``TypeError`` instead of passing silently against the mock.
    """
    getattr(stub.manager, manager_method).return_value = {"ok": True}
    result = tool_cls.apply(stub, **kwargs)
    assert result == {"ok": True}
    call = getattr(stub.manager, manager_method).call_args
    # Bind against the unbound manager method (supplying a dummy ``self``) to prove the manager accepts this exact call.
    inspect.signature(getattr(JavaRefactorManager, manager_method)).bind(object(), *call.args, **call.kwargs)
    return call.args, call.kwargs


# --- conversions: project-relative path + line + optional column (no name_path/occurrence) -----------------------------


def test_convert_anonymous_to_lambda_forwards_path_line_column() -> None:
    stub = _StubTool(preview_returns=True)
    args, kwargs = _invoke(
        JavaConvertAnonymousToLambdaTool,
        stub,
        "convert_anonymous_to_lambda",
        relative_path="src/main/java/com/acme/app/Main.java",
        line=4,
        column=9,
        preview=False,
        validate=True,
    )
    assert args == ("src/main/java/com/acme/app/Main.java", 4)
    assert kwargs == {"column": 9, "allow_review_required": False, "apply": True, "validate": True}


def test_convert_lambda_to_method_reference_forwards_and_defaults_preview() -> None:
    # preview omitted -> _resolve_preview falls back to the project default (True here) -> apply=False.
    stub = _StubTool(preview_returns=True)
    args, kwargs = _invoke(
        JavaConvertLambdaToMethodReferenceTool,
        stub,
        "convert_lambda_to_method_reference",
        relative_path="src/main/java/com/acme/app/Main.java",
        line=7,
    )
    assert args == ("src/main/java/com/acme/app/Main.java", 7)
    assert kwargs == {"column": None, "allow_review_required": False, "apply": False, "validate": True}


# --- extract class: path + new class name + member selector + target package + leave_delegate_methods -----------------


def test_extract_class_forwards_members_and_options() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaExtractClassTool,
        stub,
        "extract_class",
        relative_path="src/main/java/com/acme/app/Cart.java",
        new_class_name="Totals",
        members='["total","addToTotal"]',
        target_package="com.acme.core",
        leave_delegate_methods=False,
        preview=True,
    )
    assert args == ("src/main/java/com/acme/app/Cart.java", "Totals", ["total", "addToTotal"])
    assert kwargs == {
        "target_package": "com.acme.core",
        "leave_delegate_methods": False,
        "update_usages": False,
        "confirm_public_api_change": False,
        "allow_review_required": False,
        "apply": False,
        "validate": True,
    }


def test_extract_class_forwards_name_path_guard_and_update_usages() -> None:
    # name_path is resolved as an identity guard (verifies the symbol exists) but NOT forwarded to the manager — the
    # sidecar resolves the file's primary type. update_usages IS forwarded so the external-rewrite path is reachable.
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaExtractClassTool,
        stub,
        "extract_class",
        name_path="Cart",
        relative_path="src/main/java/com/acme/app/Cart.java",
        new_class_name="Totals",
        members='["method:addToTotal(int)"]',
        leave_delegate_methods=False,
        update_usages=True,
        preview=True,
    )
    # The named symbol was resolved within the file as an identity guard.
    assert stub.resolved_target == ("src/main/java/com/acme/app/Cart.java", "Cart", None, None)
    assert args == ("src/main/java/com/acme/app/Cart.java", "Totals", ["method:addToTotal(int)"])
    assert kwargs == {
        "target_package": None,
        "leave_delegate_methods": False,
        "update_usages": True,
        "confirm_public_api_change": False,
        "allow_review_required": False,
        "apply": False,
        "validate": True,
    }


# --- extract superclass: backward-compatible multi-sibling class list + member selector --------------------------------


def test_extract_superclass_forwards_class_list() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaExtractSuperclassTool,
        stub,
        "extract_superclass",
        classes="src/main/java/com/acme/Account.java, src/main/java/com/acme/Ledger.java",
        superclass_name="BaseAccount",
        members='["field:balance","method:deposit(int)"]',
        preview=False,
    )
    assert args == (
        ["src/main/java/com/acme/Account.java", "src/main/java/com/acme/Ledger.java"],
        "BaseAccount",
        ["field:balance", "method:deposit(int)"],
    )
    assert kwargs == {"target_package": None, "make_abstract": True, "allow_review_required": False, "apply": True, "validate": True}


# --- extract superclass: planned name_path + relative_path + make_abstract contract ------------------------------------


def test_extract_superclass_forwards_relative_path_and_make_abstract() -> None:
    # The §4.3 contract: name_path/relative_path identify the source type (relative_path becomes the first class entry,
    # any extra siblings append after it) and make_abstract threads through to the manager.
    stub = _StubTool()
    # name_path resolution is an identity guard against the language server; stub it out so the forwarding contract test
    # does not need the LSP.
    cast(Any, stub)._resolve_target = lambda relative_path, name_path, line, column: (1, 1, {})
    args, kwargs = _invoke(
        JavaExtractSuperclassTool,
        stub,
        "extract_superclass",
        name_path="OnlineOrderHandler",
        relative_path="src/main/java/com/acme/OnlineOrderHandler.java",
        superclass_name="AbstractOrderHandler",
        members='["method:handle()"]',
        classes="src/main/java/com/acme/StoreOrderHandler.java",
        make_abstract=True,
        preview=True,
    )
    assert args == (
        [
            "src/main/java/com/acme/OnlineOrderHandler.java",
            "src/main/java/com/acme/StoreOrderHandler.java",
        ],
        "AbstractOrderHandler",
        ["method:handle()"],
    )
    assert kwargs == {"target_package": None, "make_abstract": True, "allow_review_required": False, "apply": False, "validate": True}


# --- replace inheritance with delegation: path + optional member selector (empty -> None) + delegate field name -------


def test_replace_inheritance_with_delegation_empty_members_become_none() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaReplaceInheritanceWithDelegationTool,
        stub,
        "replace_inheritance_with_delegation",
        relative_path="src/main/java/com/acme/app/Dog.java",
        preview=True,
    )
    assert args == ("src/main/java/com/acme/app/Dog.java",)
    assert kwargs == {
        "members": None,
        "delegate_field_name": None,
        "superclass_fqn": None,
        "confirm_public_api_change": False,
        "allow_review_required": False,
        "apply": False,
        "validate": True,
    }


def test_replace_inheritance_with_delegation_forwards_member_selector() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaReplaceInheritanceWithDelegationTool,
        stub,
        "replace_inheritance_with_delegation",
        relative_path="src/main/java/com/acme/app/Dog.java",
        members="bark, sit",
        delegate_field_name="animal",
        preview=True,
    )
    assert args == ("src/main/java/com/acme/app/Dog.java",)
    assert kwargs == {
        "members": ["bark", "sit"],
        "delegate_field_name": "animal",
        "superclass_fqn": None,
        "confirm_public_api_change": False,
        "allow_review_required": False,
        "apply": False,
        "validate": True,
    }


def test_replace_inheritance_with_delegation_forwards_superclass_fqn() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaReplaceInheritanceWithDelegationTool,
        stub,
        "replace_inheritance_with_delegation",
        relative_path="src/main/java/com/acme/app/Dog.java",
        superclass_fqn="com.acme.app.Animal",
        preview=True,
    )
    assert args == ("src/main/java/com/acme/app/Dog.java",)
    assert kwargs == {
        "members": None,
        "delegate_field_name": None,
        "superclass_fqn": "com.acme.app.Animal",
        "confirm_public_api_change": False,
        "allow_review_required": False,
        "apply": False,
        "validate": True,
    }


# --- deep inline method: path + line + optional column/method_name + delete_method ------------------------------------


def test_deep_inline_method_forwards_locator_and_flags() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaDeepInlineMethodTool,
        stub,
        "deep_inline_method",
        relative_path="src/main/java/com/acme/app/Calc.java",
        line=12,
        method_name="square",
        delete_method=True,
        preview=False,
    )
    assert args == ("src/main/java/com/acme/app/Calc.java", 12)
    assert kwargs == {
        "column": None,
        "method_name": "square",
        "delete_method": True,
        "max_call_sites": None,
        "allow_review_required": False,
        "apply": True,
        "validate": True,
    }


def test_deep_inline_method_forwards_max_call_sites() -> None:
    # Review Gap 8: the public max_call_sites cap must reach the manager (and thence the sidecar planner) so a tight cap
    # can refuse a large inline blast radius. Default (omitted) forwards None; an explicit value forwards verbatim.
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaDeepInlineMethodTool,
        stub,
        "deep_inline_method",
        relative_path="src/main/java/com/acme/app/Calc.java",
        line=12,
        max_call_sites=1,
        preview=False,
    )
    assert args == ("src/main/java/com/acme/app/Calc.java", 12)
    assert kwargs == {
        "column": None,
        "method_name": None,
        "delete_method": False,
        "max_call_sites": 1,
        "allow_review_required": False,
        "apply": True,
        "validate": True,
    }


# --- recipes: mutually-exclusive recipe_name / recipe_document, "" coerced to None ------------------------------------


def test_scan_migration_opportunities_coerces_blank_to_none() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaScanMigrationOpportunitiesTool,
        stub,
        "scan_migration_opportunities",
        recipe_name="junit4-to-junit5-basic",
    )
    assert args == ()
    assert kwargs == {"recipe_name": "junit4-to-junit5-basic", "recipe_document": None, "scope": "project"}


def test_apply_refactor_recipe_forwards_document_and_apply() -> None:
    stub = _StubTool()
    document = '{"id":"foo-to-bar","rules":[]}'
    args, kwargs = _invoke(
        JavaApplyRefactorRecipeTool,
        stub,
        "apply_refactor_recipe",
        recipe_document=document,
        preview=False,
    )
    assert args == ()
    assert kwargs == {
        "recipe_name": None,
        "recipe_document": document,
        "allow_review_required": False,
        "apply": True,
        "validate": True,
        "scope": "project",
    }


def test_apply_refactor_recipe_accepts_recipe_json_alias() -> None:
    # Review Gap 8: recipe_json is a backward-compatible alias for the inline recipe document; it must feed the SAME
    # recipe_document selector the manager consumes (so a caller can pass either name).
    stub = _StubTool()
    document = '{"id":"foo-to-bar","rules":[]}'
    args, kwargs = _invoke(
        JavaApplyRefactorRecipeTool,
        stub,
        "apply_refactor_recipe",
        recipe_json=document,
        preview=False,
    )
    assert args == ()
    assert kwargs["recipe_document"] == document
    assert kwargs["recipe_name"] is None


def test_apply_refactor_recipe_forwards_allow_review_required() -> None:
    # Review Gap 8: the allow-review-required control must thread to the manager (and thence the sidecar as
    # apply_needs_review) so REVIEW_REQUIRED matches are applied only when explicitly opted in.
    stub = _StubTool()
    document = '{"id":"foo-to-bar","rules":[]}'
    args, kwargs = _invoke(
        JavaApplyRefactorRecipeTool,
        stub,
        "apply_refactor_recipe",
        recipe_document=document,
        allow_review_required=True,
        preview=False,
    )
    assert args == ()
    assert kwargs == {
        "recipe_name": None,
        "recipe_document": document,
        "allow_review_required": True,
        "apply": True,
        "validate": True,
        "scope": "project",
    }


# --- find dead code: read-only scan + max_answer_chars bound ----------------------------------------------------------


def test_find_dead_code_forwards_scan_options_and_bounds_output() -> None:
    # Review Gap 8: find_dead_code exposes max_answer_chars; the tool must forward the scan options to the manager and
    # route the result through the length bound (proven via the _limit_length passthrough recorded on the stub).
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaFindDeadCodeTool,
        stub,
        "find_dead_code",
        scope="com.acme.app",
        include_tests=True,
        public_api_policy="report",
        max_answer_chars=4096,
    )
    assert args == ()
    assert kwargs == {
        "min_confidence": None,
        "scope": "com.acme.app",
        "include_tests": True,
        "public_api_policy": "report",
    }


def test_find_dead_code_max_answer_chars_bounds_the_report() -> None:
    # Review Gap 8 (behavior): max_answer_chars is a REAL bound, not a silently-accepted no-op. Drive the tool's apply
    # through the REAL _finalize_result + _limit_length against a manager that returns an over-large candidate report and
    # assert that a too-small cap replaces the body with the "too long" guidance, while a generous cap returns it intact.
    from serena.tools.java_refactor_tools import _JavaRefactorToolBase
    from serena.tools.tools_base import Tool

    big_payload = {"accepted": True, "mode": "scan", "deadCodeCandidates": [{"symbol": f"com.acme.Dead{i}"} for i in range(200)]}

    class _RealLimitStub:
        def __init__(self) -> None:
            self.manager = MagicMock(name="JavaRefactorManager")
            self.manager.find_dead_code.return_value = big_payload

        def _get_manager(self):
            return self.manager

        _finalize_result = _JavaRefactorToolBase._finalize_result
        _limit_length = Tool._limit_length
        _notify_language_server_of_disk_edits = _JavaRefactorToolBase._notify_language_server_of_disk_edits

    full = JavaFindDeadCodeTool.apply(cast(Any, _RealLimitStub()), max_answer_chars=10**9)
    assert "com.acme.Dead199" in full  # generous cap: full report returned intact

    bounded = JavaFindDeadCodeTool.apply(cast(Any, _RealLimitStub()), max_answer_chars=64)
    assert "com.acme.Dead199" not in bounded  # too-small cap: body dropped
    assert "too long" in bounded.lower()  # replaced with the documented guidance


# -- R13g / R13r / R01: the graph + resource + framework built-ins are reachable as registered V3 tools --------------


def test_transformation_graph_tool_forwards_to_manager() -> None:
    # R13g / R01: the V3 transformation graph (graph.build, G002) is reachable through a REGISTERED tool that forwards to
    # the manager's transformation_graph bridge (binding proven against the real manager signature by _invoke).
    stub = _StubTool()
    args, kwargs = _invoke(JavaTransformationGraphTool, stub, "transformation_graph")
    assert args == ()
    assert kwargs == {}


def test_resource_references_tool_forwards_target_and_scan_options() -> None:
    # R13r: resources.findReferences is reachable through a registered tool that forwards target + scan options.
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaResourceReferencesTool,
        stub,
        "resource_find_references",
        target="com.acme.app.Service",
        target_is_package=True,
        kinds=["xml", "service_loader"],
    )
    assert args == ("com.acme.app.Service",)
    assert kwargs == {"target_is_package": True, "kinds": ["xml", "service_loader"]}


def test_plan_resource_edits_tool_forwards_maps_and_confidence_options() -> None:
    # R13r: resources.planEdits is reachable through a registered tool that forwards the moved-type/package maps.
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaPlanResourceEditsTool,
        stub,
        "resource_plan_edits",
        type_fqn_map={"com.acme.Old": "com.acme.New"},
        package_map={"com.acme.old": "com.acme.new"},
        rewrite_package_prefixes=True,
        apply_medium_confidence=True,
    )
    assert args == ()
    assert kwargs == {
        "type_fqn_map": {"com.acme.Old": "com.acme.New"},
        "package_map": {"com.acme.old": "com.acme.new"},
        "rewrite_exact_class_names": True,
        "rewrite_package_prefixes": True,
        "apply_medium_confidence": True,
    }


def test_framework_detect_tool_forwards_to_manager() -> None:
    # R13r: frameworks.detect (G005) is reachable through a registered tool that forwards to framework_detect.
    stub = _StubTool()
    args, kwargs = _invoke(JavaFrameworkDetectTool, stub, "framework_detect")
    assert args == ()
    assert kwargs == {}


def test_new_v3_tools_are_registered() -> None:
    # The graph/resource/framework tools are not sidecar-internal: they are first-class registered V3 tools, so the
    # G002/G005 acceptance rows bind to something actually exposed at the Serena tool layer.
    for cls in (
        JavaTransformationGraphTool,
        JavaResourceReferencesTool,
        JavaPlanResourceEditsTool,
        JavaFrameworkDetectTool,
    ):
        assert cls in JAVA_REFACTOR_V3_TOOL_CLASSES


def test_acceptance_matrix_operations_are_reachable_through_registered_tools() -> None:
    # R01 / R02 / R13g / R13r: EVERY acceptance-matrix operation — now including the workspace-lifecycle row (R02) —
    # names an operation reached by a REGISTERED V3 tool. No matrix row points at a phantom surface, and the binding map
    # covers the whole matrix exactly (no extra bindings, no uncovered rows).
    matrix_ops = {row["tool"] for row in acceptance_matrix()}
    bound_ops = set(V3_MATRIX_TOOL_BINDINGS)
    assert bound_ops == matrix_ops, matrix_ops ^ bound_ops
    # and every bound tool is actually registered (not a dangling reference).
    for operation, tool_classes in V3_MATRIX_TOOL_BINDINGS.items():
        assert tool_classes, operation
        for cls in tool_classes:
            assert cls in JAVA_REFACTOR_V3_TOOL_CLASSES, (operation, cls)


def test_graph_and_resource_and_framework_rows_bind_to_the_new_tools() -> None:
    # R01: the G002 graph row binds to the new graph tool; the G005 rows bind to the resource + framework tools.
    assert V3_MATRIX_TOOL_BINDINGS["transformationGraph"] == (JavaTransformationGraphTool,)
    assert V3_MATRIX_TOOL_BINDINGS["resourceProviders"] == (JavaResourceReferencesTool, JavaPlanResourceEditsTool)
    assert V3_MATRIX_TOOL_BINDINGS["frameworkDetect"] == (JavaFrameworkDetectTool,)
    assert V3_MATRIX_TOOL_BINDINGS["impactReport"] == (JavaImpactReportTool,)


# --- R02: transformation-workspace lifecycle tools forward to the local manager bridges --------------------------------


def test_create_transformation_workspace_tool_forwards_to_manager() -> None:
    # R02: workspace creation is reachable through a registered tool that forwards to the manager's create bridge.
    stub = _StubTool()
    args, kwargs = _invoke(JavaCreateTransformationWorkspaceTool, stub, "transformation_workspace_create")
    assert args == ()
    assert kwargs == {}


def test_add_workspace_session_tool_forwards_operation_and_params() -> None:
    # R02: enrolling a V2 session forwards (workspace_id, operation, params) + validate to the manager bridge.
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaAddWorkspaceSessionTool,
        stub,
        "transformation_workspace_add_session",
        workspace_id="ws-1",
        operation="renameSymbol",
        params={"namePath": "Foo/bar", "newName": "baz"},
        validate=False,
    )
    assert args == ("ws-1", "renameSymbol", {"namePath": "Foo/bar", "newName": "baz"})
    assert kwargs == {"validate": False}


def test_add_workspace_session_tool_defaults_empty_params() -> None:
    # R02: an omitted params object is forwarded as an empty dict (never None) so the bridge signature is satisfied.
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaAddWorkspaceSessionTool,
        stub,
        "transformation_workspace_add_session",
        workspace_id="ws-1",
        operation="safeDelete",
    )
    assert args == ("ws-1", "safeDelete", {})
    assert kwargs == {"validate": None}


def test_add_workspace_operation_tool_forwards_operation_and_params() -> None:
    # R02: enrolling a compute-only V3 op forwards (workspace_id, operation, params) to the manager bridge.
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaAddWorkspaceOperationTool,
        stub,
        "transformation_workspace_add_operation",
        workspace_id="ws-2",
        operation="renamePackage",
        params={"oldPackage": "com.a", "newPackage": "com.b"},
    )
    assert args == ("ws-2", "renamePackage", {"oldPackage": "com.a", "newPackage": "com.b"})
    assert kwargs == {}


def test_preview_transformation_workspace_tool_forwards_id() -> None:
    # R02: composing/validating the member plan without writing is reachable through the preview tool.
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaPreviewTransformationWorkspaceTool,
        stub,
        "transformation_workspace_preview",
        workspace_id="ws-3",
    )
    assert args == ("ws-3",)
    assert kwargs == {}


def test_apply_transformation_workspace_tool_forwards_validate_and_revision_guard() -> None:
    # R02: the transactional commit forwards the revision guard + validate toggle to the manager bridge.
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaApplyTransformationWorkspaceTool,
        stub,
        "transformation_workspace_apply",
        workspace_id="ws-4",
        validate=True,
        expected_project_revision="rev-7",
    )
    assert args == ("ws-4",)
    assert kwargs == {"validate": True, "expected_project_revision": "rev-7", "allow_review_required": False}


def test_cancel_transformation_workspace_tool_forwards_id() -> None:
    # R02: cancellation is reachable through a registered tool that forwards to the manager's cancel bridge.
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaCancelTransformationWorkspaceTool,
        stub,
        "transformation_workspace_cancel",
        workspace_id="ws-5",
    )
    assert args == ("ws-5",)
    assert kwargs == {}


def test_list_transformation_workspaces_tool_forwards_to_manager() -> None:
    # R02: listing live workspaces is reachable through a registered tool that forwards to the list bridge.
    stub = _StubTool()
    args, kwargs = _invoke(JavaListTransformationWorkspacesTool, stub, "transformation_workspace_list")
    assert args == ()
    assert kwargs == {}


def test_workspace_lifecycle_tools_are_registered_and_bound() -> None:
    # R02: the G001 transformationWorkspace row binds to the full create/add/preview/apply/cancel/list lifecycle, and
    # every one of those tools is actually registered (not a dangling reference).
    lifecycle = (
        JavaCreateTransformationWorkspaceTool,
        JavaAddWorkspaceSessionTool,
        JavaAddWorkspaceOperationTool,
        JavaPreviewTransformationWorkspaceTool,
        JavaApplyTransformationWorkspaceTool,
        JavaCancelTransformationWorkspaceTool,
        JavaListTransformationWorkspacesTool,
    )
    assert V3_MATRIX_TOOL_BINDINGS["transformationWorkspace"] == lifecycle
    for cls in lifecycle:
        assert cls in JAVA_REFACTOR_V3_TOOL_CLASSES


def test_v3_planned_public_python_facade_imports() -> None:
    from serena.java_refactor_v3 import (
        ImpactReportBuilder,
        MigrationOpportunity,
        RecipeDocument,
        RecipeOperation,
        RecipeResult,
        ResourcePreviewClient,
        V3OperationPlan,
    )
    from serena.java_refactor_v3 import impact_report, recipe_models, resource_preview, transformation_models

    assert transformation_models.V3OperationPlan is V3OperationPlan
    assert recipe_models.RecipeDocument is RecipeDocument
    assert recipe_models.RecipeOperation is RecipeOperation
    assert recipe_models.RecipeResult is RecipeResult
    assert recipe_models.MigrationOpportunity is MigrationOpportunity
    assert impact_report.ImpactReportBuilder is ImpactReportBuilder
    assert resource_preview.ResourcePreviewClient is ResourcePreviewClient


def test_impact_report_public_model_uses_v3_plan_sections() -> None:
    from serena.java_refactor_v3.models import ImpactReport

    report = ImpactReport(java={"filesChanged": ["src/A.java"]}, resources={}, api={}, tests={}, risk={"level": "INFO"})
    payload = report.to_dict()

    assert list(payload) == ["summary", "semanticImpact", "resourceImpact", "tests", "warnings"]
    assert payload["summary"]["filesChanged"] == ["src/A.java"]
    assert isinstance(payload["semanticImpact"], dict)
    assert isinstance(payload["resourceImpact"], dict)
    assert isinstance(payload["tests"], dict)
    assert isinstance(payload["warnings"], list)


def test_v3_analysis_invariant_envelope_for_read_only_tools() -> None:
    from serena.java_refactor.manager import JavaRefactorManager

    manager = object.__new__(JavaRefactorManager)
    payload = manager._with_v3_analysis_invariants({"accepted": True, "warnings": ["review resources"]}, "frameworkReferences")

    assert payload["previewFirst"] is True
    assert payload["transactional"] is True
    assert payload["projectRevision"] == "current"
    assert payload["factGraphRevision"] == "current"
    assert payload["javacFactsValidated"] is True
    assert payload["validation"] == {"kind": "javacFacts", "javacFactsValidated": True}
    assert payload["provenance"]["operation"] == "frameworkReferences"
    assert payload["riskClassification"] == "INFO"
    assert list(payload["impact"]) == ["summary", "semanticImpact", "resourceImpact", "tests", "warnings"]


def test_v3_disabled_scan_refusal_carries_analysis_invariants() -> None:
    from serena.java_refactor.manager import JavaRefactorManager

    manager = object.__new__(JavaRefactorManager)
    payload = manager._v3_scan_disabled_refusal("transformationGraph")

    assert payload["accepted"] is False
    assert payload["refusal"]["code"] == "java_refactor_v3_disabled"
    assert payload["riskClassification"] == "REFUSED"
    assert payload["previewFirst"] is True
    assert payload["javacFactsValidated"] is True
    assert payload["impact"]["warnings"] == []


def test_extract_class_public_api_review_knobs_are_forwarded() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaExtractClassTool,
        stub,
        "extract_class",
        relative_path="src/main/java/com/acme/app/Cart.java",
        new_class_name="Totals",
        members='["addToTotal"]',
        leave_delegate_methods=False,
        update_usages=True,
        confirm_public_api_change=True,
        allow_review_required=True,
        preview=False,
    )
    assert args == ("src/main/java/com/acme/app/Cart.java", "Totals", ["addToTotal"])
    assert kwargs["leave_delegate_methods"] is False
    assert kwargs["update_usages"] is True
    assert kwargs["confirm_public_api_change"] is True
    assert kwargs["allow_review_required"] is True
    assert kwargs["apply"] is True


def test_extract_superclass_relative_path_alias_keeps_public_api_defaults() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaExtractSuperclassTool,
        stub,
        "extract_superclass",
        relative_path="src/main/java/com/acme/app/Account.java",
        superclass_name="AbstractAccount",
        members='["method:save()"]',
    )
    assert args == (["src/main/java/com/acme/app/Account.java"], "AbstractAccount", ["method:save()"])
    assert kwargs["make_abstract"] is True
    assert kwargs["allow_review_required"] is False
    assert kwargs["apply"] is False


def test_extract_superclass_classes_alias_accepts_json_list() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaExtractSuperclassTool,
        stub,
        "extract_superclass",
        classes='["src/main/java/com/acme/A.java", "src/main/java/com/acme/B.java"]',
        superclass_name="Base",
        members='["method:save()"]',
        preview=True,
    )
    assert args == (
        ["src/main/java/com/acme/A.java", "src/main/java/com/acme/B.java"],
        "Base",
        ["method:save()"],
    )
    assert kwargs["make_abstract"] is True
    assert kwargs["allow_review_required"] is False


def test_propagate_safe_delete_accepts_planned_roots_alias() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaPropagateSafeDeleteTool,
        stub,
        "propagate_safe_delete",
        roots='["com.acme.Legacy", {"relativePath": "src/main/java/com/acme/Old.java", "line": 4, "column": 8}]',
    )
    assert args[0] == [
        "com.acme.Legacy",
        {"relativePath": "src/main/java/com/acme/Old.java", "line": 4, "column": 8},
    ]


def test_deep_inline_accepts_name_path_alias_for_method_name() -> None:
    stub = _StubTool()
    args, kwargs = _invoke(
        JavaDeepInlineMethodTool,
        stub,
        "deep_inline_method",
        relative_path="src/main/java/com/acme/Worker.java",
        line=12,
        name_path="Worker/helper[0]",
    )
    assert args == ("src/main/java/com/acme/Worker.java", 12)
    assert kwargs["method_name"] == "helper"
