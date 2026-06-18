from typing import Any

from serena.tools.java_refactor_tools import (
    JAVA_REFACTOR_TOOL_CLASSES,
    JavaApplyRefactorSessionTool,
    JavaCancelRefactorSessionTool,
    JavaCreateRefactorSessionTool,
    JavaGetRefactorSessionEditTool,
    _JavaRefactorToolBase,
)


class JavaInlineMethodTool(_JavaRefactorToolBase):
    """Previews or applies V2 inline-method sessions."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        method_name: str = "",
        delete_method: bool = False,
        preview: bool | None = None,
        validate: bool = True,
        line: int | None = None,
        column: int | None = None,
        call_site_file: str | None = None,
        call_site_start_offset: int | None = None,
        call_site_end_offset: int | None = None,
        call_site_start_line: int | None = None,
        call_site_start_column: int | None = None,
        call_site_hint: str | None = None,
    ) -> str:
        """
        Inline a constrained Java method through the V2 preview-session protocol.

        :param method_name: Optional simple method name when line/column or name_path are not enough.
        :param delete_method: Whether the inlined declaration should be deleted after call-site rewrites.
        :param call_site_file: Project-relative path of the file containing the specific call site to inline.
            Omit to inline all call sites (default behavior).
        :param call_site_start_offset: Zero-based character offset of the invocation in the call-site file.
            Preferred over line/column when available.
        :param call_site_end_offset: Zero-based exclusive end offset of the invocation; combined with
            call_site_start_offset to pin the exact span.
        :param call_site_start_line: One-based line of the invocation; used when character offsets are unavailable.
        :param call_site_start_column: One-based column of the invocation; used together with call_site_start_line.
        :param call_site_hint: Short distinguishing text (e.g. a receiver or argument snippet) used to break ties
            when offset/line-column alone leave multiple candidates.
        """
        params: dict[str, Any] = {"methodName": method_name, "deleteMethod": delete_method}
        call_site_selection: dict[str, Any] = {}
        if call_site_file is not None:
            call_site_selection["file"] = call_site_file
        if call_site_start_offset is not None:
            call_site_selection["startOffset"] = call_site_start_offset
        if call_site_end_offset is not None:
            call_site_selection["endOffset"] = call_site_end_offset
        if call_site_start_line is not None:
            call_site_selection["startLine"] = call_site_start_line
        if call_site_start_column is not None:
            call_site_selection["startColumn"] = call_site_start_column
        if call_site_hint is not None:
            call_site_selection["hint"] = call_site_hint
        if call_site_selection:
            params["callSiteSelection"] = call_site_selection
        return self._session_refactor("inlineMethod", relative_path, name_path, line, column, preview, validate, params)


class JavaChangeSignatureTool(_JavaRefactorToolBase):
    """Previews or applies V2 Java change-signature sessions."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        new_name: str | None = None,
        new_return_type: str | None = None,
        parameters_json: str | None = None,
        default_values_json: str | None = None,
        line: int | None = None,
        column: int | None = None,
        preview: bool | None = None,
        validate: bool = True,
        remove_parameters_json: str | None = None,
        confirm_public_api: bool = False,
        return_conversion: str | None = None,
        body_return_conversion: str | None = None,
    ) -> str:
        """
        Change a Java method signature through the V2 preview-session protocol.

        :param name_path: Serena name path of the method/constructor target.
        :param relative_path: Java source path relative to the project root.
        :param new_name: Optional replacement method name.
        :param new_return_type: Optional replacement return type.
        :param parameters_json: JSON array describing the desired parameter list.
        :param default_values_json: JSON object mapping new parameter names to call-site default expressions.
        :param preview: Whether to only plan the refactor (``True``) or apply it (``False``). When omitted, the
            project's ``preview_default`` applies. ``preview=True`` returns a revision-guarded preview session whose
            planned edit can later be applied with ``java_apply_refactor_session`` using the returned ``sessionId``;
            ``preview=False`` applies the refactor in one shot through the manager's transactional
            create -> revalidate -> stage -> commit -> post-validate pipeline (rolled back on failure).
        :param validate: Preview/apply validation request flag.
        :param line: Advanced: one-based source line, used instead of ``name_path`` resolution.
        :param column: Advanced: one-based source column, used instead of ``name_path`` resolution.
        :param remove_parameters_json: JSON array of parameter names or indexes to remove; the sidecar refuses silent
            parameter removal and requires explicit declaration via this field.
        :param confirm_public_api: Hard gate: must be ``True`` to change the signature of a public method; the sidecar
            blocks public-API changes unless this flag is explicitly set.
        :param return_conversion: Template string (must contain ``$return``) used to rewrite call sites when the return
            type changes and call-site values are consumed; required whenever the planner detects value-consuming sites.
        :param body_return_conversion: Template string (must contain ``$return``) used to rewrite the selected method's
            own ``return <expr>;`` statements (and every concrete override's) into the new return type. Each value-return
            expression is spliced into the ``$return`` placeholder; bodiless interface/abstract declarations only receive
            the signature edit. The planner refuses a method-owned bare ``return;`` (nothing to convert) and the javac
            diagnostic delta rejects any template that does not actually produce the new type.
        """
        # normalize designed parameter/default inputs
        parameters = self._json_argument(parameters_json, "parameters_json", [])
        default_values = self._json_argument(default_values_json, "default_values_json", {})
        # G004: the V2 parameter contract is snake_case (old_index, default_value, removal policy fields), but the
        # sidecar planner reads the camelCase names. Normalize each parameter object so both spellings are accepted and
        # forwarded as the camelCase field the planner reads. snake_case never overrides an explicit camelCase value.
        for parameter in parameters:
            if not isinstance(parameter, dict):
                continue
            for snake, camel in (
                ("old_index", "oldIndex"),
                ("default_value", "defaultValue"),
                ("removal_policy", "removalPolicy"),
                ("on_removed", "onRemoved"),
            ):
                if snake in parameter and camel not in parameter:
                    parameter[camel] = parameter[snake]
            # The new-name alias travels under either `new` or `newName`; keep `new` as the canonical spelling the
            # planner already reads while honouring a snake/camel `newName` if that is all the caller supplied.
            if "newName" in parameter and "new" not in parameter:
                parameter["new"] = parameter["newName"]
            if isinstance(default_values, dict) and parameter.get("name") in default_values and "defaultValue" not in parameter:
                parameter["defaultValue"] = default_values[parameter["name"]]

        # forward the V2 contract fields
        params = {
            "newName": new_name,
            "newReturnType": new_return_type,
            "parameters": parameters,
            "defaultValues": default_values,
            "removeParameters": self._json_argument(remove_parameters_json, "remove_parameters_json", None),
            "confirmPublicApi": confirm_public_api,
            "returnConversion": return_conversion,
            "bodyReturnConversion": body_return_conversion,
        }
        return self._session_refactor("changeSignature", relative_path, name_path, line, column, preview, validate, params)


class JavaIntroduceParameterTool(_JavaRefactorToolBase):
    """Previews or applies V2 Java introduce-parameter sessions."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        parameter_name: str = "value",
        selected_expression: str | None = None,
        parameter_type: str | None = None,
        selection_start_line: int | None = None,
        selection_start_column: int | None = None,
        selection_end_line: int | None = None,
        selection_end_column: int | None = None,
        allow_side_effects: bool = False,
        preview: bool | None = None,
        validate: bool = True,
        line: int | None = None,
        column: int | None = None,
    ) -> str:
        """
        Introduce a Java method parameter by replacing a selected expression through the V2 preview-session protocol.

        :param name_path: Serena name path of the method target.
        :param relative_path: Java source path relative to the project root.
        :param parameter_name: Name of the new parameter.
        :param selected_expression: Compatibility fallback exact source expression; source ranges are preferred.
        :param parameter_type: Optional explicit type override; omitted values are inferred by the sidecar when possible.
        :param selection_start_line: One-based start line of the selected expression.
        :param selection_start_column: One-based start column of the selected expression.
        :param selection_end_line: One-based exclusive end line of the selected expression.
        :param selection_end_column: One-based exclusive end column of the selected expression.
        :param allow_side_effects: Hard gate: the planner refuses a selected expression that javac cannot prove
            reorder/duplication-safe (it is duplicated at every call site), unless this is ``True``. Set it only after
            confirming the duplicated evaluation order is acceptable. Forwarded to the sidecar as ``allowSideEffects``.
        :param preview: Whether to only plan the refactor (``True``) or apply it (``False``). When omitted, the
            project's ``preview_default`` applies. ``preview=True`` returns a revision-guarded preview session whose
            planned edit can later be applied with ``java_apply_refactor_session`` using the returned ``sessionId``;
            ``preview=False`` applies the refactor in one shot through the manager's transactional
            create -> revalidate -> stage -> commit -> post-validate pipeline (rolled back on failure).
        :param validate: Preview/apply validation request flag.
        :param line: Advanced: one-based source line, used instead of ``name_path`` resolution.
        :param column: Advanced: one-based source column, used instead of ``name_path`` resolution.
        """
        params: dict[str, Any] = {
            "selectedExpression": selected_expression,
            "parameterName": parameter_name,
            "parameterType": parameter_type,
            "allowSideEffects": allow_side_effects,
        }
        selection = {
            "startLine": selection_start_line,
            "startColumn": selection_start_column,
            "endLine": selection_end_line,
            "endColumn": selection_end_column,
        }
        if any(value is not None for value in selection.values()):
            params["selection"] = {
                "startLine": selection_start_line,
                "startColumn": selection_start_column,
                "endLine": selection_end_line,
                "endColumn": selection_end_column,
            }
        return self._session_refactor("introduceParameter", relative_path, name_path, line, column, preview, validate, params)


class JavaMoveStaticMemberTool(_JavaRefactorToolBase):
    """Previews or applies V2 static member move sessions."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        target_type: str = "",
        new_name: str | None = None,
        allow_access_widening: bool = False,
        allow_security_sensitive_private_widening: bool = False,
        preview: bool | None = None,
        validate: bool = True,
    ) -> str:
        """
        Move a static Java member to another type through the V2 preview-session protocol.

        :param target_type: Fully qualified or resolvable destination type name.
        :param new_name: Optional replacement member name after moving.
        :param allow_access_widening: Whether the planner may widen access to preserve moved references.
        :param allow_security_sensitive_private_widening: Secondary security gate: must be ``True`` to widen a
            security-sensitive private member even when ``allow_access_widening`` is set.
        """
        params = {
            "targetType": target_type,
            "newName": new_name,
            "allowAccessWidening": allow_access_widening,
            "allowSecuritySensitivePrivateWidening": allow_security_sensitive_private_widening,
        }
        return self._session_refactor("moveStaticMember", relative_path, name_path, None, None, preview, validate, params)


class JavaMoveInstanceMethodTool(_JavaRefactorToolBase):
    """Previews or applies V2 constrained instance-method move sessions."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        target_parameter_name: str | None = None,
        target_field_name: str | None = None,
        target_receiver: str | None = None,
        receiver_selection_json: str | None = None,
        target_type: str | None = None,
        new_name: str | None = None,
        rewrite_call_sites: bool = True,
        leave_delegate: bool = True,
        allow_access_widening: bool = False,
        allow_security_sensitive_private_widening: bool = False,
        preview: bool | None = None,
        validate: bool = True,
    ) -> str:
        """
        Move an instance method to a target parameter/receiver through the V2 preview-session protocol.

        :param target_parameter_name: Parameter name whose type will receive the method.
        :param target_field_name: Source field name whose type will receive the method.
        :param target_receiver: Explicit (detached-text) receiver expression to strip from the moved method body; only a
            simple identifier or dotted field navigation is accepted on this path.
        :param receiver_selection_json: AST-backed receiver selection range as a JSON object with ``startLine``,
            ``startColumn``, ``endLine``, ``endColumn`` (one-based). The sidecar resolves the range to a javac TreePath
            and proves it reorder-safe, admitting pure non-simple receivers (a cast, a parenthesized navigation) and
            refusing side-effecting/order-sensitive/unknown receivers with located evidence.
        :param target_type: Explicit receiver type when parameter/selection inference is not enough.
        :param new_name: Optional replacement method name after moving.
        :param rewrite_call_sites: Whether call sites should be updated to the new receiver.
        :param leave_delegate: Whether a forwarding method should be left in the source type.
        :param allow_access_widening: Hard gate: must be ``True`` to allow the planner to widen access visibility on
            the moved method when needed.
        :param allow_security_sensitive_private_widening: Secondary security gate: must be ``True`` to widen a
            security-sensitive private member even when ``allow_access_widening`` is set.
        """
        params: dict[str, Any] = {
            "targetParameter": target_parameter_name,
            "newName": new_name,
            "rewriteCallSites": rewrite_call_sites,
            "leaveDelegate": leave_delegate,
            "keepDelegate": leave_delegate,
            "allowAccessWidening": allow_access_widening,
            "allowSecuritySensitivePrivateWidening": allow_security_sensitive_private_widening,
        }
        if target_field_name is not None:
            params["targetField"] = target_field_name
        if target_receiver is not None:
            params["targetReceiver"] = target_receiver
        if receiver_selection_json is not None:
            params["receiverSelection"] = self._json_argument(receiver_selection_json, "receiver_selection_json", {})
        if target_type is not None:
            params["targetType"] = target_type
        return self._session_refactor("moveInstanceMethod", relative_path, name_path, None, None, preview, validate, params)


class JavaPullUpMemberTool(_JavaRefactorToolBase):
    """Previews or applies V2 pull-up member sessions."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        target_supertype: str = "",
        make_abstract: bool = False,
        leave_delegate: bool = False,
        allow_access_widening: bool = False,
        allow_security_sensitive_private_widening: bool = False,
        confirm_serialization_impact: bool = False,
        preview: bool | None = None,
        validate: bool = True,
    ) -> str:
        """
        Pull a Java member to a superclass or interface through the V2 preview-session protocol.

        :param target_supertype: Fully qualified or resolvable supertype receiving the member.
        :param make_abstract: Whether to request abstract declaration extraction where legal.
        :param leave_delegate: Whether a forwarding override should remain in the source type.
        :param allow_access_widening: Hard gate: must be ``True`` to allow the planner to widen access visibility when
            pulling the member up.
        :param allow_security_sensitive_private_widening: Secondary security gate: must be ``True`` to widen a
            security-sensitive private member even when ``allow_access_widening`` is set.
        :param confirm_serialization_impact: Hard gate: must be ``True`` to pull up a field whose move would alter
            ``serialVersionUID`` impact on the class hierarchy.
        """
        params = {
            "targetSupertype": target_supertype,
            "targetType": target_supertype,
            "makeAbstract": make_abstract,
            "leaveDelegate": leave_delegate,
            "allowAccessWidening": allow_access_widening,
            "allowSecuritySensitivePrivateWidening": allow_security_sensitive_private_widening,
            "confirmSerializationImpact": confirm_serialization_impact,
        }
        return self._session_refactor("pullUpMember", relative_path, name_path, None, None, preview, validate, params)


class JavaPushDownMemberTool(_JavaRefactorToolBase):
    """Previews or applies V2 push-down member sessions."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        target_subtypes_json: str = "[]",
        remove_from_source: bool = False,
        allow_access_widening: bool = False,
        allow_security_sensitive_private_widening: bool = False,
        confirm_serialization_impact: bool = False,
        include_indirect_subtypes: bool = False,
        preview: bool | None = None,
        validate: bool = True,
    ) -> str:
        """
        Push a Java member to selected subtypes through the V2 preview-session protocol.

        :param target_subtypes_json: JSON array of fully qualified or resolvable subtype names.
        :param remove_from_source: Whether the source member should be removed when the move validates.
        :param allow_access_widening: Hard gate: must be ``True`` to allow the planner to widen access visibility when
            pushing the member down.
        :param allow_security_sensitive_private_widening: Secondary security gate: must be ``True`` to widen a
            security-sensitive private member even when ``allow_access_widening`` is set.
        :param confirm_serialization_impact: Hard gate: must be ``True`` to push down a field whose move would alter
            ``serialVersionUID`` impact on the class hierarchy.
        :param include_indirect_subtypes: Selection widener: when ``True`` the planner also pushes to indirect
            (transitive) subtypes; without this flag only direct subtypes receive the member.
        """
        subtypes = self._json_argument(target_subtypes_json, "target_subtypes_json", [])
        params = {
            "targetSubtypes": subtypes,
            "targetTypes": subtypes,
            "removeFromSource": remove_from_source,
            "allowAccessWidening": allow_access_widening,
            "allowSecuritySensitivePrivateWidening": allow_security_sensitive_private_widening,
            "confirmSerializationImpact": confirm_serialization_impact,
            "includeIndirectSubtypes": include_indirect_subtypes,
        }
        return self._session_refactor("pushDownMember", relative_path, name_path, None, None, preview, validate, params)


class JavaExtractMethodTool(_JavaRefactorToolBase):
    """Previews or applies V2 conservative extract-method sessions."""

    def apply(
        self,
        relative_path: str = "",
        start_line: int = 0,
        start_col: int = 0,
        end_line: int = 0,
        end_col: int = 0,
        new_method_name: str = "",
        visibility: str | None = None,
        make_static: bool | None = None,
        preview: bool | None = None,
        validate: bool = True,
    ) -> str:
        """
        Extract complete Java statements into a new method through the V2 preview-session protocol.

        :param start_line: One-based start line of the statement selection.
        :param start_col: One-based start column of the statement selection.
        :param end_line: One-based exclusive end line of the statement selection.
        :param end_col: One-based exclusive end column of the statement selection.
        :param new_method_name: Name of the synthesized method.
        :param visibility: Optional requested visibility, such as ``private`` or ``protected``.
        :param make_static: Optional explicit static-method request; omitted values let the sidecar decide.
        """
        params = {
            "newMethodName": new_method_name,
            "selection": {
                "startLine": start_line,
                "startColumn": start_col,
                "endLine": end_line,
                "endColumn": end_col,
            },
            "visibility": visibility,
            "makeStatic": make_static,
        }
        return self._session_refactor("extractMethod", relative_path, None, None, None, preview, validate, params)


class JavaExtractInterfaceTool(_JavaRefactorToolBase):
    """Previews or applies V2 extract-interface sessions."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        interface_name: str = "",
        target_package: str | None = None,
        members_json: str | None = None,
        replace_usages: bool = False,
        confirm_public_api_change: bool = False,
        preview: bool | None = None,
        validate: bool = True,
    ) -> str:
        """
        Extract a Java interface from a selected type through the V2 preview-session protocol.

        :param interface_name: Name of the generated interface.
        :param target_package: Optional package for the generated interface.
        :param members_json: JSON array describing selected methods/constants.
        :param replace_usages: Whether safe usage narrowing should be requested.
        :param confirm_public_api_change: Hard gate: must be ``True`` when ``replace_usages=True`` would narrow a type
            at API-visible call sites; the sidecar blocks the operation unless this flag is explicitly set.
        """
        params = {
            "interfaceName": interface_name,
            "targetPackage": target_package,
            "members": self._json_argument(members_json, "members_json", []),
            "replaceUsages": replace_usages,
            "confirmPublicApiChange": confirm_public_api_change,
        }
        return self._session_refactor("extractInterface", relative_path, name_path, None, None, preview, validate, params)


class JavaIntroduceFieldTool(_JavaRefactorToolBase):
    """Previews or applies V2 introduce-field sessions."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        field_name: str = "",
        field_type: str = "",
        initializer: str | None = None,
        selection_json: str | None = None,
        constant: bool = False,
        initialize_in_constructor: bool = False,
        constructor_strategy: str = "",
        preview: bool | None = None,
        validate: bool = True,
        line: int | None = None,
        column: int | None = None,
    ) -> str:
        """
        Introduce a Java field through the V2 preview-session protocol.

        :param field_name: Name of the new field.
        :param field_type: Declared field type.
        :param initializer: Optional literal/simple initializer.
        :param selection_json: Optional JSON range replacing an expression with the new field.
        :param constant: Whether to create a private static final compile-time constant.
        :param initialize_in_constructor: Whether to initialize a private final field in a constructor.
        :param constructor_strategy: Explicit multi-constructor strategy; use "allTerminal" to assign every terminal constructor.
        """
        params = {
            "fieldName": field_name,
            "fieldType": field_type,
            "initializer": initializer,
            "selection": self._json_argument(selection_json, "selection_json", None),
            "constant": constant,
            "initializeInConstructor": initialize_in_constructor,
            "constructorStrategy": constructor_strategy,
        }
        return self._session_refactor("introduceField", relative_path, name_path, line, column, preview, validate, params)


class JavaEncapsulateFieldTool(_JavaRefactorToolBase):
    """Previews or applies V2 encapsulate-field sessions."""

    def apply(
        self,
        name_path: str | None = None,
        relative_path: str = "",
        getter_name: str | None = None,
        setter_name: str | None = None,
        setter: bool = True,
        update_usages: bool = True,
        preview: bool | None = None,
        validate: bool = True,
    ) -> str:
        """
        Encapsulate a Java field through the V2 preview-session protocol.

        :param getter_name: Optional explicit getter name.
        :param setter_name: Optional explicit setter name.
        :param setter: Whether a setter should be generated and write references may be rewritten.
        :param update_usages: Whether direct reads/writes should be rewritten through accessors.
        """
        params = {
            "getterName": getter_name,
            "setterName": setter_name,
            "setter": setter,
            "updateUsages": update_usages,
            "updateReferences": update_usages,
        }
        return self._session_refactor("encapsulateField", relative_path, name_path, None, None, preview, validate, params)


# The complete set of V2 operation classes, including session management tools imported from the V1 module.
# V2 operation classes (below) are the authoritative definitions; session tools (Create/Get/Apply/Cancel)
# remain owned by java_refactor_tools.py and are re-exported here for convenience.
JAVA_REFACTOR_V2_TOOL_CLASSES = (
    JavaCreateRefactorSessionTool,
    JavaGetRefactorSessionEditTool,
    JavaApplyRefactorSessionTool,
    JavaCancelRefactorSessionTool,
    JavaChangeSignatureTool,
    JavaIntroduceParameterTool,
    JavaMoveStaticMemberTool,
    JavaMoveInstanceMethodTool,
    JavaPullUpMemberTool,
    JavaPushDownMemberTool,
    JavaInlineMethodTool,
    JavaExtractMethodTool,
    JavaExtractInterfaceTool,
    JavaIntroduceFieldTool,
    JavaEncapsulateFieldTool,
)


# V2 operation tools whose registration is negotiated against the sidecar capability registry:
# each is enabled only when the sidecar advertises its operation with status "supported".
JAVA_REFACTOR_V2_CAPABILITY_TOOLS: dict[type, str] = {
    JavaChangeSignatureTool: "changeSignature",
    JavaIntroduceParameterTool: "introduceParameter",
    JavaMoveStaticMemberTool: "moveStaticMember",
    JavaMoveInstanceMethodTool: "moveInstanceMethod",
    JavaPullUpMemberTool: "pullUpMember",
    JavaPushDownMemberTool: "pushDownMember",
    JavaInlineMethodTool: "inlineMethod",
    JavaExtractMethodTool: "extractMethod",
    JavaExtractInterfaceTool: "extractInterface",
    JavaIntroduceFieldTool: "introduceField",
    JavaEncapsulateFieldTool: "encapsulateField",
}


def java_refactor_v2_capability_tool_operations() -> dict[str, str]:
    """Maps each capability-gated tool name to its sidecar operation identifier (V2 plus the V3 engine tools).

    V3 transformation-engine tools (e.g. ``renamePackage``) are negotiated against the same sidecar capability
    registry as the V2 operation tools, so they are merged into one capability map the agent consults at discovery
    time.
    """
    from serena.tools.java_refactor_v3_tools import java_refactor_v3_capability_tool_operations

    operations = {cls.get_name_from_cls(): operation for cls, operation in JAVA_REFACTOR_V2_CAPABILITY_TOOLS.items()}
    operations.update(java_refactor_v3_capability_tool_operations())
    return operations


def java_refactor_always_on_tool_names() -> list[str]:
    """Names of Java refactoring tools that are available without capability negotiation.

    This is the V1 set (status/debug, session lifecycle, V1 operations) PLUS the Python-planned V3 tools that are not
    sidecar operations (e.g. propagate-safe-delete and the dead-code scan): all of these depend only on
    ``java_refactor.enabled``, not on a specific advertised sidecar operation capability.
    """
    from serena.tools.java_refactor_tools import java_refactor_tool_names as _v1_tool_names
    from serena.tools.java_refactor_v3_tools import java_refactor_v3_non_capability_tool_names

    seen: set[str] = set()
    names: list[str] = []
    for name in list(_v1_tool_names()) + java_refactor_v3_non_capability_tool_names():
        if name not in seen:
            seen.add(name)
            names.append(name)
    return names


def java_refactor_tool_names() -> list[str]:
    """Names of ALL Java refactoring tools (V1 + V2 + V3), gated by ``java_refactor.enabled``."""
    from serena.tools.java_refactor_v3_tools import JAVA_REFACTOR_V3_TOOL_CLASSES

    all_classes = JAVA_REFACTOR_TOOL_CLASSES + JAVA_REFACTOR_V2_TOOL_CLASSES + JAVA_REFACTOR_V3_TOOL_CLASSES
    seen: set[str] = set()
    result = []
    for cls in all_classes:
        name = cls.get_name_from_cls()
        if name not in seen:
            seen.add(name)
            result.append(name)
    return result


__all__ = [
    "JAVA_REFACTOR_V2_CAPABILITY_TOOLS",
    "JAVA_REFACTOR_V2_TOOL_CLASSES",
    "JavaApplyRefactorSessionTool",
    "JavaCancelRefactorSessionTool",
    "JavaChangeSignatureTool",
    "JavaCreateRefactorSessionTool",
    "JavaEncapsulateFieldTool",
    "JavaExtractInterfaceTool",
    "JavaExtractMethodTool",
    "JavaGetRefactorSessionEditTool",
    "JavaInlineMethodTool",
    "JavaIntroduceFieldTool",
    "JavaIntroduceParameterTool",
    "JavaMoveInstanceMethodTool",
    "JavaMoveStaticMemberTool",
    "JavaPullUpMemberTool",
    "JavaPushDownMemberTool",
    "java_refactor_always_on_tool_names",
    "java_refactor_tool_names",
    "java_refactor_v2_capability_tool_operations",
]
