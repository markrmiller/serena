package io.serena.javarefactor.operations.change_signature;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.ast.IdentifierSpan;
import io.serena.javarefactor.ast.ResolvedTarget;
import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.shared.SemanticTargetGate;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.shared.ExpressionPurityAnalyzer;
import io.serena.javarefactor.shared.ImportConflictResolvers;
import io.serena.javarefactor.shared.ImportManager;
import io.serena.javarefactor.shared.ProjectPathResolver;
import io.serena.javarefactor.shared.SourceText;
import java.io.IOException;
import java.nio.file.Path;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** V2 change-signature and introduce-parameter planner backed by javac Trees, Elements, TypeMirror, and source positions. */
public final class ChangeSignaturePlanner {
    private final Path projectRoot;
    private final JavaProjectModel model;

    public ChangeSignaturePlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot;
        this.model = model;
    }

    public String changeSignature(Map<String, Object> fields, boolean apply) {
        try {
            Path file = sourceFile(fields);
            String relativePath = PlannerSupport.relative(projectRoot, file);
            String source = SourceText.read(model, file);
            try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
                ResolvedTarget verified = SemanticTargetGate.require(index, relativePath, fields);
                MethodMatch declaration = selectedDeclaration(index, file, source, intField(fields, "line"), verified);
                SemanticTargetGate.confirmSelection(verified, declaration.semantic().element());
                List<ParameterSpec> desired = fields.containsKey("parameters") ? desiredParameters(fields, declaration) : declaration.parameters();
                ChangePlan plan = planSignature(index, source, file, declaration, fields, desired);
                return acceptedJson(apply, "changeSignature", declaration, plan.edits(), plan.warnings());
            }
        } catch (SignatureRefusal refusal) {
            return refusedJson("changeSignature", apply, refusal.code(), refusal.getMessage());
        } catch (SemanticTargetGate.Refused refused) {
            return refusedJson("changeSignature", apply, refused.code(), refused.getMessage());
        } catch (Exception error) {
            return refusedJson("changeSignature", apply, "change_signature_failed", error.getMessage());
        }
    }

    /**
     * Structured result of planning a {@code changeMethodSignature} recipe rule (F13): the declaration / override-group /
     * call-site / javadoc {@link PlannerSupport.TextEdit}s and warnings, or a structured refusal (code + message). The
     * operation's own refusal is returned as data — never thrown across the package boundary — so the recipe engine can
     * surface it honestly.
     */
    public record RecipeSignaturePlan(boolean refused, String refusalCode, String refusalMessage,
                                      List<PlannerSupport.TextEdit> edits, List<String> warnings) {
        public RecipeSignaturePlan {
            edits = edits == null ? List.of() : List.copyOf(edits);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * F13: plans a {@code changeMethodSignature} recipe rule against an already-open whole-project {@code index}, producing
     * the exact declaration / override-group / call-site / javadoc edits the standalone change-signature operation emits —
     * so the recipe engine's {@code changeMethodSignature} kind is a real compiler-backed signature change, not a
     * documented refusal. {@code fields} carries {@code line} (1-based, of the resolved declaration; supplied by the
     * recipe engine from the rule's owner/name/paramTypes) plus the desired-signature fields
     * ({@code parameters}/{@code newName}/{@code newReturnType}/{@code confirmPublicApi}/…). The caller's
     * PreviewDiagnosticValidator javac-validates the merged workspaceEdit, so this reuses the open index for a single
     * compiler pass instead of opening its own.
     */
    public RecipeSignaturePlan planRecipeSignatureChange(SemanticIndex index, Path file, String relativePath, Map<String, Object> fields) {
        try {
            String source = SourceText.read(model, file);
            ResolvedTarget verified = SemanticTargetGate.require(index, relativePath, fields);
            MethodMatch declaration = selectedDeclaration(index, file, source, intField(fields, "line"), verified);
            SemanticTargetGate.confirmSelection(verified, declaration.semantic().element());
            List<ParameterSpec> desired = fields.containsKey("parameters") ? desiredParameters(fields, declaration) : declaration.parameters();
            ChangePlan plan = planSignature(index, source, file, declaration, fields, desired);
            return new RecipeSignaturePlan(false, null, null, plan.edits(), plan.warnings());
        } catch (SignatureRefusal refusal) {
            return new RecipeSignaturePlan(true, refusal.code(), refusal.getMessage(), List.of(), List.of());
        } catch (SemanticTargetGate.Refused refused) {
            return new RecipeSignaturePlan(true, refused.code(), refused.getMessage(), List.of(), List.of());
        } catch (Exception error) {
            return new RecipeSignaturePlan(true, "change_signature_failed", error.getMessage(), List.of(), List.of());
        }
    }

    public String introduceParameter(Map<String, Object> fields, boolean apply) {
        try {
            Path file = sourceFile(fields);
            String relativePath = PlannerSupport.relative(projectRoot, file);
            String source = SourceText.read(model, file);
            try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
                ResolvedTarget verified = SemanticTargetGate.require(index, relativePath, fields);
                MethodMatch declaration = selectedDeclaration(index, file, source, intField(fields, "line"), verified);
                SemanticTargetGate.confirmSelection(verified, declaration.semantic().element());
                SemanticIndex.SemanticExpressionSelection selection = selectedExpressionSelection(index, file, fields, declaration);
                if (selection == null) {
                    throw new SignatureRefusal("missing_selected_expression", "Introduce parameter requires a selected expression range.");
                }
                if (selection.enclosingExecutable() != null && !selection.enclosingExecutable().equals(declaration.semantic().element())) {
                    throw new SignatureRefusal("expression_not_in_method", "Selected expression must be inside the target method body.");
                }
                String selectedExpression = selection.text();
                String requestedExpression = stringField(fields, "selectedExpression", "").trim();
                if (!requestedExpression.isBlank() && !requestedExpression.equals(selectedExpression.trim())) {
                    throw new SignatureRefusal("selected_expression_mismatch", "selectedExpression must match the AST expression selected by range.");
                }
                // Prove call-site default validity instead of blindly reusing the expression text: the selected
                // expression is delegated to change-signature as the new parameter's defaultValue, which is re-emitted
                // verbatim at every caller. That is only sound when the expression captures no enclosing-method state.
                // Locals/parameters it reads (selection.inputs()) are not in scope at callers, and this/super bind to a
                // different (or absent) receiver outside the declaring body, so any such default would fail to compile.
                // This portability gate runs first so an expression that captures enclosing state is reported precisely as
                // non-portable rather than as a side effect.
                if (!selection.inputs().isEmpty() || selection.usesThis() || selection.usesSuper()) {
                    throw new SignatureRefusal(
                            "CALL_SITE_DEFAULT_NOT_PORTABLE",
                            "Introduce parameter cannot reuse the selected expression as a call-site default because it "
                                    + "captures enclosing-method state (local variables, parameters, this, or super) that is "
                                    + "not in scope at callers.");
                }
                // G011/G004: the selected expression is duplicated verbatim at every call site, so it may only be admitted
                // without opt-in when the canonical javac TreePath verdict proves it reorder/duplication safe (side-effect
                // free, reads only stable final state). The coarse classify() purity is never used as the green-light here;
                // the bridge resolves the selection range back to its real AST node. A SIDE_EFFECTING/UNKNOWN/non-final
                // expression is refused with a structured side-effect payload unless the caller opts in via
                // allow_side_effects. Because the portability gate above already ran, the opt-in only ever admits a
                // self-contained side-effecting expression (e.g. a static counter call).
                boolean reorderSafeWithoutOptIn = index.isExpressionReorderSafe(file, selection.range());
                if (!reorderSafeWithoutOptIn && !allowSideEffects(fields)) {
                    throw new SignatureRefusal(
                            "SELECTED_EXPRESSION_HAS_SIDE_EFFECTS",
                            "Introduce parameter refuses a selected expression (purity=" + selection.purity()
                                    + ") that javac cannot prove reorder-safe because it is duplicated at every call site; "
                                    + "set allow_side_effects to opt in once you have confirmed the duplicated evaluation "
                                    + "order is acceptable.");
                }
                String parameterName = stringField(fields, "parameterName", "value");
                if (!parameterName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                    throw new SignatureRefusal("invalid_parameter_name", "parameterName must be a Java identifier.");
                }
                if (MethodSignatureModel.parameterIndex(declaration.parameters(), parameterName) >= 0) {
                    throw new SignatureRefusal("parameter_already_exists", "parameterName already exists on the target method.");
                }
                String parameterType = inferParameterType(fields, selection);
                List<ParameterSpec> desired = new ArrayList<>(declaration.parameters());
                // The default originates from a real TreePath selection that the portability gate above proved
                // reorder-safe (or the caller explicitly opted into via allow_side_effects). That AST-backed proof is
                // stronger than the detached-text constant gate, so mark it so validateCallSiteDefault does not
                // re-refuse a legitimately side-effecting introduce-parameter selection.
                desired.add(new ParameterSpec(parameterType, parameterName, selectedExpression, null, "", true));
                ChangePlan plan = planSignature(index, source, file, declaration, fields, desired);
                List<PlannerSupport.TextEdit> edits = new ArrayList<>(plan.edits());
                edits.add(new PlannerSupport.TextEdit(file, selection.range().start(), selection.range().end(), parameterName, "INTRODUCE_PARAMETER_BODY"));
                return acceptedJson(apply, "introduceParameter", declaration, edits, plan.warnings());
            }
        } catch (SignatureRefusal refusal) {
            return refusedJson("introduceParameter", apply, refusal.code(), refusal.getMessage());
        } catch (SemanticTargetGate.Refused refused) {
            return refusedJson("introduceParameter", apply, refused.code(), refused.getMessage());
        } catch (Exception error) {
            return refusedJson("introduceParameter", apply, "introduce_parameter_failed", error.getMessage());
        }
    }

    private SemanticIndex.SemanticExpressionSelection selectedExpressionSelection(SemanticIndex index, Path file, Map<String, Object> fields, MethodMatch declaration) throws SignatureRefusal {
        Object raw = fields.get("selection");
        if (raw instanceof Map<?, ?> rawSelection) {
            @SuppressWarnings("unchecked")
            Map<String, Object> selection = (Map<String, Object>) rawSelection;
            return selectedExpressionAt(
                    index,
                    file,
                    intField(selection, "startLine"),
                    intField(selection, "startColumn"),
                    intField(selection, "endLine"),
                    intField(selection, "endColumn"));
        }

        // G010: accept a flat selection range supplied directly on the fields, honouring both the canonical
        // selection_start_line/selection_start_column/selection_end_line/selection_end_column names and the shorter
        // start_line/start_col/end_line/end_col aliases. Both spellings resolve identically.
        int[] flat = flatSelectionRange(fields);
        if (flat != null) {
            return selectedExpressionAt(index, file, flat[0], flat[1], flat[2], flat[3]);
        }

        String requestedExpression = stringField(fields, "selectedExpression", stringField(fields, "selected_expression", "")).trim();
        if (requestedExpression.isBlank()) {
            return null;
        }

        SourceRange range = selectedExpressionRange(file, requestedExpression, declaration);
        if (range == null) {
            return null;
        }
        try {
            return index.selectedExpression(file, range.startLine(), range.startColumn(), range.endLine(), range.endColumn());
        } catch (IllegalArgumentException error) {
            throw new SignatureRefusal("invalid_selection_range", error.getMessage());
        }
    }

    private SemanticIndex.SemanticExpressionSelection selectedExpressionAt(
            SemanticIndex index, Path file, int startLine, int startColumn, int endLine, int endColumn) throws SignatureRefusal {
        try {
            return index.selectedExpression(file, startLine, startColumn, endLine, endColumn);
        } catch (IllegalArgumentException error) {
            throw new SignatureRefusal("invalid_selection_range", error.getMessage());
        }
    }

    /**
     * Resolves a flat selection range from the top-level fields, accepting the canonical {@code selection_start_line}
     * family and the shorter {@code start_line}/{@code start_col}/{@code end_line}/{@code end_col} aliases (G010).
     * Returns {@code null} when no flat selection field is present, or refuses when only some of the four are supplied.
     */
    private int[] flatSelectionRange(Map<String, Object> fields) throws SignatureRefusal {
        Integer startLine = aliasInt(fields, "selection_start_line", "start_line");
        Integer startColumn = aliasInt(fields, "selection_start_column", "start_col");
        Integer endLine = aliasInt(fields, "selection_end_line", "end_line");
        Integer endColumn = aliasInt(fields, "selection_end_column", "end_col");
        if (startLine == null && startColumn == null && endLine == null && endColumn == null) {
            return null;
        }
        if (startLine == null || startColumn == null || endLine == null || endColumn == null) {
            throw new SignatureRefusal(
                    "incomplete_selection_range",
                    "A flat selection requires start and end line and column "
                            + "(selection_start_line/selection_start_column/selection_end_line/selection_end_column "
                            + "or the start_line/start_col/end_line/end_col aliases).");
        }
        return new int[] {startLine, startColumn, endLine, endColumn};
    }

    private static Integer aliasInt(Map<String, Object> fields, String primary, String alias) {
        Object value = fields.get(primary);
        if (value == null) {
            value = fields.get(alias);
        }
        return value instanceof Number number ? number.intValue() : null;
    }

    /** Whether the caller has explicitly opted in to duplicating a non-pure expression (G011). */
    private boolean allowSideEffects(Map<String, Object> fields) {
        return boolField(fields, "allow_side_effects", false) || boolField(fields, "allowSideEffects", false);
    }

    /**
     * G001: whether the caller has explicitly opted in to dropping call-site arguments whose evaluation has observable
     * side effects. Sourced from the {@code change_signature.allow_removed_side_effecting_arguments} config default
     * (wired in by {@code Main.applyConfiguredDefaults}) or a per-request override. Default false.
     */
    private boolean allowRemovedSideEffectingArguments(Map<String, Object> fields) {
        return boolField(fields, "allowRemovedSideEffectingArguments", false)
                || boolField(fields, "allow_removed_side_effecting_arguments", false);
    }

    private SourceRange selectedExpressionRange(Path file, String expression, MethodMatch declaration) throws SignatureRefusal {
        String source;
        try {
            source = SourceText.read(model, file);
        } catch (IOException error) {
            throw new SignatureRefusal("source_read_failed", error.getMessage());
        }

        List<SourceRange> matches = new ArrayList<>();
        int searchLimit = declaration.bodyEnd(source);
        int fromIndex = declaration.start();
        while (fromIndex <= searchLimit) {
            int start = source.indexOf(expression, fromIndex);
            if (start < 0 || start >= searchLimit) {
                break;
            }
            matches.add(sourceRange(source, start, start + expression.length()));
            fromIndex = start + Math.max(1, expression.length());
        }

        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new SignatureRefusal("ambiguous_selected_expression", "Selected expression text occurs more than once; provide an explicit selection range.");
        }
        return matches.get(0);
    }

    private SourceRange sourceRange(String source, int startOffset, int endOffset) {
        int startLine = 1;
        int startColumn = 1;
        for (int index = 0; index < startOffset; index++) {
            if (source.charAt(index) == '\n') {
                startLine++;
                startColumn = 1;
            } else {
                startColumn++;
            }
        }

        int endLine = startLine;
        int endColumn = startColumn;
        for (int index = startOffset; index < endOffset; index++) {
            if (source.charAt(index) == '\n') {
                endLine++;
                endColumn = 1;
            } else {
                endColumn++;
            }
        }
        return new SourceRange(startLine, startColumn, endLine, endColumn);
    }

    private record SourceRange(int startLine, int startColumn, int endLine, int endColumn) {
    }

    private int selectedExpressionOffset(String source, MethodMatch declaration, String selectedExpression) throws SignatureRefusal {
        int bodyEnd = declaration.bodyEnd(source);
        int expressionOffset = source.indexOf(selectedExpression, declaration.headerEnd());
        if (expressionOffset < 0 || expressionOffset > bodyEnd) {
            throw new SignatureRefusal("expression_not_in_method", "Selected expression was not found inside the target method body.");
        }
        int secondOffset = source.indexOf(selectedExpression, expressionOffset + selectedExpression.length());
        if (secondOffset >= 0 && secondOffset <= bodyEnd) {
            throw new SignatureRefusal("ambiguous_selected_expression", "Selected expression appears multiple times inside the target method body.");
        }
        return expressionOffset;
    }

    private String inferParameterType(Map<String, Object> fields, SemanticIndex.SemanticExpressionSelection selection) throws SignatureRefusal {
        String explicit = stringField(fields, "parameterType", "").trim();
        if (!explicit.isBlank()) {
            return explicit;
        }
        String inferred = selection.type();
        if (inferred != null && !inferred.isBlank()) {
            return inferred;
        }
        throw new SignatureRefusal("unresolved_expression_type", "parameterType is required when the selected expression type cannot be inferred by javac.");
    }

    private String localVariableType(String source, MethodMatch declaration, String selectedExpression, int expressionOffset) {
        if (!selectedExpression.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return "";
        }
        String beforeExpression = source.substring(declaration.headerEnd(), expressionOffset);
        Matcher matcher = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$.<>\\[\\]]*)\\s+" + Pattern.quote(selectedExpression) + "\\b").matcher(beforeExpression);
        String result = "";
        while (matcher.find()) {
            result = matcher.group(1);
        }
        return result;
    }

    private ChangePlan planSignature(SemanticIndex index, String source, Path file, MethodMatch declaration, Map<String, Object> fields, List<ParameterSpec> desired)
            throws IOException, SignatureRefusal {
        String requestedName = stringField(fields, "newName", declaration.name());
        if (declaration.constructor() && fields.containsKey("newName") && !requestedName.equals(declaration.name())) {
            throw new SignatureRefusal("constructor_rename_unsupported", "Change signature cannot rename constructors; rename the declaring type instead.");
        }
        String newName = declaration.constructor() ? declaration.name() : requestedName;
        OverrideSignatureUpdater overrides = new OverrideSignatureUpdater(index);
        List<MethodMatch> declarations = overrides.overrideDeclarations(declaration);
        // The override/interface group is always expanded and rewritten together: skipping any member would leave the
        // group's declarations with mismatched signatures and break compilation, so partial updates are never safe.
        // A solitary declaration is simply a group of one. There is intentionally no update_overrides option in the
        // V2 contract (see refactor-feature-plan-V2.md §5.3 "Apply consistently across override group").
        overrides.validateSupportedTargets(declarations);
        String returnType = declaration.constructor() ? "" : stringField(fields, "newReturnType", declaration.returnType());

        if (overrides.publicApi(declarations)
                && !boolField(fields, "confirmPublicApi", false)
                && !boolField(fields, "confirmPublicApiChange", false)) {
            throw new SignatureRefusal("PUBLIC_API_CONFIRMATION_REQUIRED", "Changing a public or protected executable signature requires explicit public API confirmation.");
        }

        for (MethodMatch target : declarations) {
            if (index.hasOverloadSibling(target.semantic()) || index.hasSameArityMethod(target.semantic(), newName, desired.size())) {
                throw new SignatureRefusal("AMBIGUOUS_OVERLOAD_AFTER_CHANGE", "V2 change signature refuses overload groups whose post-change target could become ambiguous.");
            }
            overrides.validateRemovedParameters(target, desired);
        }
        String returnConversion = stringField(fields, "returnConversion", null);
        String bodyReturnConversion = stringField(fields, "bodyReturnConversion", stringField(fields, "body_return_conversion", null));
        validateMethodReferences(index, declarations, newName, returnType, desired);
        validateReturnCompatibility(index, declarations, returnType, returnConversion, bodyReturnConversion);

        for (ParameterSpec parameter : desired) {
            if (parameter.type() != null && parameter.type().contains("...")) {
                throw new SignatureRefusal("varargs_unsupported", "V2 change signature refuses varargs rewrites.");
            }
        }

        // Every added parameter's defaultValue is re-emitted verbatim at each call site, so validate it before planning
        // instead of splicing raw text. Only a provable compile-time constant / class literal / type-qualified
        // enum-or-static constant is safe to duplicate; anything else is refused (DEFAULT_ARGUMENT_NOT_VERIFIABLE).
        for (int desiredIndex = 0; desiredIndex < desired.size(); desiredIndex++) {
            ParameterSpec parameter = desired.get(desiredIndex);
            if (oldIndex(parameter, declaration, desiredIndex, desired) < 0) {
                validateCallSiteDefault(parameter);
            }
        }

        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        for (MethodMatch target : declarations) {
            CharSequence declarationSource = index.sourceText(target.semantic().file());
            if (declarationSource == null) {
                throw new SignatureRefusal("OVERRIDE_GROUP_INCOMPLETE", "Cannot resolve source text for declaration in " + target.semantic().file() + ".");
            }
            ImportRewrite imports = importRewrite(index, declarationSource.toString(), target.semantic().file(), returnType, desired);
            String signature = MethodSignatureModel.renderSignature(target, newName, returnType, desired, imports, declarationSource.toString());
            edits.addAll(overrides.declarationEdits(target, declarationSource.toString(), signature, desired));
            edits.addAll(imports.importEdits());
            edits.addAll(bodyReturnConversionEdits(index, target, declarationSource, returnType, bodyReturnConversion));
        }
        CallSiteRewriter callSiteRewriter = new CallSiteRewriter(index, allowRemovedSideEffectingArguments(fields));
        edits.addAll(callSiteRewriter.callSiteEdits(declarations, newName, returnType, desired, returnConversion));

        List<String> warnings = PlannerSupport.modelSafetyWarnings(model);
        warnings.add("V2 change signature resolves the target ExecutableElement, expands override/interface groups, rewrites declaration and call-site ranges from javac Trees/Elements/source positions, and applies imports per declaring file.");
        return new ChangePlan(edits, warnings);
    }

    private MethodMatch selectedDeclaration(SemanticIndex index, Path file, String source, int oneBasedLine, ResolvedTarget verified) throws SignatureRefusal {
        // Select with the gate-verified simple name so an overloaded declaration resolves to the proven semantic target.
        String nameHint = verified == null ? "" : verified.element().getSimpleName().toString();
        SemanticIndex.SemanticMethod method = index.selectedMethod(file, oneBasedLine, nameHint);
        if (method == null) {
            throw new SignatureRefusal("target_not_method", "Selected line does not contain a javac-resolved method or constructor declaration.");
        }
        return MethodMatchFactory.from(index, method);
    }

    private int oldIndex(ParameterSpec parameter, MethodMatch declaration, int desiredIndex, List<ParameterSpec> desired) {
        return MethodSignatureModel.oldIndex(parameter, declaration, desiredIndex, desired);
    }

    private boolean typeEquivalent(String left, String right) {
        return MethodSignatureModel.typeEquivalent(left, right);
    }

    /** The selected declaration's return type, preferring the resolved javac TypeMirror (fully qualified) over source text. */
    private String resolvedReturnType(MethodMatch declaration) {
        return MethodSignatureModel.resolvedReturnType(declaration);
    }

    /**
     * G006: pre-validate every method-reference call site against the proposed signature using the javac-backed
     * functional-interface SAM verdict. A reference that remains valid under the new signature is allowed (the actual
     * name-token rewrite, if any, is emitted by {@link CallSiteRewriter}); an INCOMPATIBLE or UNRESOLVED verdict is
     * refused here with the verdict's structured reason so the refusal is reported before any edits are assembled. This
     * replaces the old rule that refused every arity/return/type change outright.
     */
    private void validateMethodReferences(SemanticIndex index, List<MethodMatch> declarations, String newName, String returnType, List<ParameterSpec> desired) throws SignatureRefusal {
        List<String> desiredParameterTypes = MethodSignatureModel.desiredParameterTypes(desired);
        for (MethodMatch declaration : declarations) {
            String referenceName = declaration.constructor() ? declaration.name() : newName;
            for (SemanticIndex.SemanticCallSite site : index.methodCallSites(declaration.semantic())) {
                if (!site.methodReference()) {
                    continue;
                }
                SemanticIndex.MethodReferenceVerdict verdict =
                        index.methodReferenceVerdict(site, desiredParameterTypes, returnType, referenceName);
                if (verdict.incompatible() || verdict.unresolved()) {
                    throw new SignatureRefusal(verdict.code(), verdict.message());
                }
            }
        }
    }

    private void validateReturnCompatibility(SemanticIndex index, List<MethodMatch> declarations, String returnType, String returnConversion, String bodyReturnConversion) throws SignatureRefusal {
        if (returnConversion != null && !returnConversion.isBlank()) {
            validateReturnConversionTemplate(returnConversion);
        }
        boolean bodyConversionRequested = bodyReturnConversion != null && !bodyReturnConversion.isBlank();
        if (bodyConversionRequested) {
            // G001: the body conversion template is spliced around each return EXPRESSION, so it carries the same
            // single-expression / single-placeholder safety contract as the call-site template.
            validateReturnConversionTemplate(bodyReturnConversion);
            boolean anyChanged = false;
            for (MethodMatch declaration : declarations) {
                if (!declaration.constructor() && !typeEquivalent(resolvedReturnType(declaration), returnType)) {
                    anyChanged = true;
                    break;
                }
            }
            if (!anyChanged) {
                throw new SignatureRefusal(
                        "BODY_RETURN_CONVERSION_NO_CHANGE",
                        "bodyReturnConversion was supplied but the resolved return type is unchanged, so there is no body "
                                + "return value to convert. Drop bodyReturnConversion or specify a genuinely different newReturnType.");
            }
        }
        for (MethodMatch declaration : declarations) {
            // G008/G009: compare against the resolved (fully qualified) declared return type so a.Foo and b.Foo are not
            // conflated. When the resolved type is unchanged the bodies already return a compatible expression by
            // construction; only a genuine change needs ignored call sites or an explicit returnConversion.
            if (declaration.constructor() || typeEquivalent(resolvedReturnType(declaration), returnType)) {
                continue;
            }
            // G005/G006: the method body's own return expressions must be independently assignable to the new return
            // type. returnConversion only adapts CALL SITES — it wraps the invocation to convert the new return type back
            // to what each caller expects (new -> caller-expected direction) — and is never applied to the body, so it
            // cannot rescue a body that still returns an incompatible old-typed value. We therefore inspect the body
            // UNCONDITIONALLY (regardless of whether a returnConversion is supplied): a return expression javac proves is
            // not assignable to the new type would produce uncompilable code (e.g. `return "x";` under a new `int` return
            // type, even with a returnConversion that only rewrites call sites), so we refuse with a located
            // RETURN_TYPE_INCOMPATIBLE. A compatible widening/boxing (int -> long, int -> Number, String -> CharSequence)
            // remains assignable and passes this check unchanged.
            // G001: when bodyReturnConversion is supplied the caller is explicitly converting the body's returned value
            // into the new type, so a body that currently returns the old type is no longer a refusal — the spliced
            // template makes it assignable and the javac diagnostic delta on apply rejects any template that does not.
            if (!bodyConversionRequested) {
                String incompatibleLocation = index.returnBodyIncompatibility(declaration.semantic(), returnType);
                if (incompatibleLocation != null) {
                    throw new SignatureRefusal(
                            "RETURN_TYPE_INCOMPATIBLE",
                            "A return expression at " + incompatibleLocation + " is not assignable to the new return type '"
                                    + returnType + "'. Supply bodyReturnConversion to convert the body's returned value, or "
                                    + "change the method body to return a value of the new type; returnConversion only adapts "
                                    + "call sites and cannot convert the body's returned value.");
                }
            }
            for (SemanticIndex.SemanticCallSite site : index.methodCallSites(declaration.semantic())) {
                // Method-reference sites cannot be wrapped by a call-site returnConversion; their post-change validity is
                // decided by the javac SAM verdict in validateMethodReferences, so they never force a returnConversion.
                if (site.methodReference()) {
                    continue;
                }
                if (!site.statementExpression() && (returnConversion == null || returnConversion.isBlank())) {
                    throw new SignatureRefusal("RETURN_INCOMPATIBILITY", "Return type changes require ignored call sites or an explicit returnConversion.");
                }
            }
        }
    }

    /**
     * G001: rewrite each method-owned {@code return <expr>;} of {@code target} into {@code return <template[$return:=expr]>;}
     * when a body conversion was requested and this declaration's return type actually changed. Abstract/interface
     * declarations (no body) contribute no spans, so an override group is updated consistently: every concrete body is
     * converted while the bodiless declarations only receive the signature edit. A method-owned bare {@code return;}
     * cannot be wrapped and is refused with a located {@code BODY_RETURN_CONVERSION_UNSUPPORTED}.
     */
    private List<PlannerSupport.TextEdit> bodyReturnConversionEdits(
            SemanticIndex index,
            MethodMatch target,
            CharSequence declarationSource,
            String returnType,
            String bodyReturnConversion) throws SignatureRefusal {
        if (bodyReturnConversion == null || bodyReturnConversion.isBlank()
                || target.constructor()
                || typeEquivalent(resolvedReturnType(target), returnType)) {
            return List.of();
        }
        SemanticIndex.ReturnBodyRewrite rewrite = index.bodyReturnRewrite(target.semantic());
        if (rewrite.unsupportedLocation() != null) {
            throw new SignatureRefusal(
                    "BODY_RETURN_CONVERSION_UNSUPPORTED",
                    "A return statement at " + rewrite.unsupportedLocation() + " has no value expression and cannot be "
                            + "converted to the new return type '" + returnType + "'. bodyReturnConversion only rewrites "
                            + "value-returning statements.");
        }
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        for (int[] span : rewrite.spans()) {
            String original = declarationSource.subSequence(span[0], span[1]).toString();
            String replacement = bodyReturnConversion.replace("$return", "(" + original + ")");
            edits.add(new PlannerSupport.TextEdit(target.semantic().file(), span[0], span[1], replacement, "CHANGE_SIGNATURE_BODY_RETURN"));
        }
        return edits;
    }

    private void validateReturnConversionTemplate(String returnConversion) throws SignatureRefusal {
        int placeholder = returnConversion.indexOf("$return");
        if (placeholder < 0) {
            throw new SignatureRefusal("RETURN_CONVERSION_PLACEHOLDER_MISSING", "returnConversion must contain the $return placeholder.");
        }
        String templateWithoutPlaceholder = returnConversion.replace("$return", "");
        if (returnConversion.indexOf("$return", placeholder + "$return".length()) >= 0
                || returnConversion.contains(";")
                || returnConversion.contains("{")
                || returnConversion.contains("}")
                || returnConversion.contains("\n")
                || returnConversion.contains("\r")
                || returnConversion.contains("//")
                || returnConversion.contains("/*")
                || templateWithoutPlaceholder.matches("(?s).*\\breturn\\b.*")) {
            throw new SignatureRefusal("RETURN_CONVERSION_UNSAFE_TEMPLATE", "returnConversion must be a single-expression template with exactly one $return placeholder.");
        }
    }

    private boolean boolField(Map<String, Object> fields, String key, boolean defaultValue) {
        Object value = fields.get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }

    private int lineStart(String source, int offset) {
        int start = Math.min(offset, source.length());
        while (start > 0 && source.charAt(start - 1) != '\n') {
            start--;
        }
        return start;
    }

    /**
     * G004: resolves a parameter entry's {@code oldIndex}, accepting both the camelCase spelling and the snake_case
     * {@code old_index} from the V2 contract. Returns {@code null} when neither is present so the index falls back to
     * name/position matching.
     */
    private Integer oldIndexField(Map<String, Object> typed) throws SignatureRefusal {
        if (typed.containsKey("oldIndex")) {
            return intField(typed, "oldIndex");
        }
        if (typed.containsKey("old_index")) {
            return intField(typed, "old_index");
        }
        return null;
    }

    /** G004: resolves a parameter entry's default expression, accepting {@code defaultValue} or snake_case {@code default_value}. */
    private String defaultValueField(Map<String, Object> typed) {
        if (typed.containsKey("defaultValue")) {
            return stringField(typed, "defaultValue", null);
        }
        return stringField(typed, "default_value", null);
    }

    private List<ParameterSpec> desiredParameters(Map<String, Object> fields, MethodMatch declaration) throws SignatureRefusal {
        Object raw = fields.get("parameters");
        if (!(raw instanceof List<?> list)) {
            throw new SignatureRefusal("PARAMETER_PLAN_EMPTY", "Change signature requires a parameters list when parameters is supplied.");
        }
        // G019 (B3): an empty parameters plan is permitted so a method can drop to zero parameters. Correctness is not
        // weakened: enforceParameterCoverage below still requires every existing parameter to be explicitly listed under
        // removeParameters (an empty plan with any uncovered parameter is refused parameter_coverage_incomplete), and the
        // CallSiteRewriter still refuses dropping a side-effecting argument unless allow_removed_side_effecting_arguments
        // is set. So `void m(int a)` becomes `void m()` only when `a` is explicitly removed and its call-site arguments
        // are proven safe to drop.
        List<ParameterSpec> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                if (!typed.containsKey("type") || String.valueOf(typed.get("type")).isBlank()) {
                    throw new SignatureRefusal("PARAMETER_TYPE_REQUIRED", "Every parameter plan entry must provide an explicit type.");
                }
                if (!typed.containsKey("name") || String.valueOf(typed.get("name")).isBlank()) {
                    throw new SignatureRefusal("PARAMETER_NAME_REQUIRED", "Every parameter plan entry must provide an explicit name.");
                }
                // G004: read both camelCase and snake_case parameter fields so a direct sidecar caller using the
                // snake_case V2 contract (old_index/default_value) is honoured even when the Python normalizer is bypassed.
                Integer oldIndex = oldIndexField(typed);
                result.add(new ParameterSpec(
                        stringField(typed, "type", null),
                        stringField(typed, "name", null),
                        defaultValueField(typed),
                        oldIndex));
            } else {
                result.addAll(parseParameters(String.valueOf(item)));
            }
        }
        validateParameterPlan(declaration, result);
        enforceParameterCoverage(declaration, result, fields);
        return result;
    }

    /**
     * G006: a supplied parameter plan must account for every existing parameter, either by mapping it (an entry with a
     * matching {@code oldIndex} or name) or by listing it under an explicit {@code removeParameters} declaration. Silent
     * omission must never be interpreted as removal, so any old parameter that is neither retained nor explicitly removed
     * causes a structured {@code parameter_coverage_incomplete} refusal naming the uncovered parameters.
     */
    private void enforceParameterCoverage(MethodMatch declaration, List<ParameterSpec> desired, Map<String, Object> fields) throws SignatureRefusal {
        Set<Integer> retained = new LinkedHashSet<>();
        for (int desiredIndex = 0; desiredIndex < desired.size(); desiredIndex++) {
            int oldIndex = oldIndex(desired.get(desiredIndex), declaration, desiredIndex, desired);
            if (oldIndex >= 0 && oldIndex < declaration.parameters().size()) {
                retained.add(oldIndex);
            }
        }
        Set<Integer> removed = explicitRemovals(declaration, fields);
        List<String> uncovered = new ArrayList<>();
        for (int parameterIndex = 0; parameterIndex < declaration.parameters().size(); parameterIndex++) {
            if (!retained.contains(parameterIndex) && !removed.contains(parameterIndex)) {
                uncovered.add(declaration.parameters().get(parameterIndex).name());
            }
        }
        if (!uncovered.isEmpty()) {
            throw new SignatureRefusal(
                    "parameter_coverage_incomplete",
                    "A parameter plan must cover every existing parameter by mapping or explicit removal; uncovered: "
                            + String.join(", ", uncovered)
                            + ". Add a matching entry (oldIndex or name) for each, or list the parameter under removeParameters.");
        }
    }

    /** Parses an explicit removeParameters declaration into the set of 0-based old-parameter indexes it targets. */
    private Set<Integer> explicitRemovals(MethodMatch declaration, Map<String, Object> fields) throws SignatureRefusal {
        Set<Integer> removed = new LinkedHashSet<>();
        Object raw = firstPresent(fields, "removeParameters", "remove_parameters", "removeParams", "remove");
        if (raw == null) {
            return removed;
        }
        if (!(raw instanceof List<?> list)) {
            throw new SignatureRefusal("invalid_remove_declaration", "removeParameters must be a list of parameter names or 0-based indexes.");
        }
        for (Object item : list) {
            if (item instanceof Number number) {
                int index = number.intValue();
                if (index < 0 || index >= declaration.parameters().size()) {
                    throw new SignatureRefusal("remove_parameter_not_found", "removeParameters index is outside the current parameter list: " + index);
                }
                removed.add(index);
            } else {
                String name = String.valueOf(item).trim();
                int index = MethodSignatureModel.parameterIndex(declaration.parameters(), name);
                if (index < 0) {
                    throw new SignatureRefusal("remove_parameter_not_found", "removeParameters names a parameter that does not exist: " + name);
                }
                removed.add(index);
            }
        }
        return removed;
    }

    private static Object firstPresent(Map<String, Object> fields, String... keys) {
        for (String key : keys) {
            Object value = fields.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }


    private void validateParameterPlan(MethodMatch declaration, List<ParameterSpec> desired) throws SignatureRefusal {
        Set<String> names = new LinkedHashSet<>();
        Set<Integer> oldIndexes = new LinkedHashSet<>();
        for (ParameterSpec parameter : desired) {
            if (parameter.type() == null || parameter.type().isBlank()) {
                throw new SignatureRefusal("PARAMETER_TYPE_REQUIRED", "Every parameter plan entry must provide an explicit type.");
            }
            if (parameter.name() == null || parameter.name().isBlank()) {
                throw new SignatureRefusal("PARAMETER_NAME_REQUIRED", "Every parameter plan entry must provide an explicit name.");
            }
            if (!isJavaIdentifier(parameter.name())) {
                throw new SignatureRefusal("INVALID_PARAMETER_NAME", "Parameter name is not a valid Java identifier: " + parameter.name());
            }
            if (!names.add(parameter.name())) {
                throw new SignatureRefusal("DUPLICATE_PARAMETER_NAME", "Parameter plan contains a duplicate parameter name: " + parameter.name());
            }
            if (parameter.oldIndex() != null) {
                if (parameter.oldIndex() < 0 || parameter.oldIndex() >= declaration.parameters().size()) {
                    throw new SignatureRefusal("PARAMETER_OLD_INDEX_OUT_OF_RANGE", "Parameter oldIndex is outside the current parameter list.");
                }
                if (!oldIndexes.add(parameter.oldIndex())) {
                    throw new SignatureRefusal("DUPLICATE_PARAMETER_OLD_INDEX", "Parameter plan maps the same old parameter more than once.");
                }
            }
        }
    }

    private boolean isJavaIdentifier(String name) {
        if (name == null || name.isBlank() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            if (!Character.isJavaIdentifierPart(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private List<ParameterSpec> parseParameters(String text) {
        List<ParameterSpec> result = new ArrayList<>();
        for (String parameter : splitCsv(text)) {
            String trimmed = parameter.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int split = trimmed.lastIndexOf(' ');
            if (split < 0) {
                result.add(new ParameterSpec(trimmed, "value", null));
            } else {
                result.add(new ParameterSpec(trimmed.substring(0, split).trim(), trimmed.substring(split + 1).trim(), null));
            }
        }
        return result;
    }

    private List<String> splitCsv(String text) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '<' || ch == '(' || ch == '[') {
                depth++;
            } else if (ch == '>' || ch == ')' || ch == ']') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                result.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    private ImportRewrite importRewrite(SemanticIndex index, String source, Path file, String returnType, List<ParameterSpec> parameters) {
        ImportManager planner = new ImportManager(source)
                .withConflictResolver(ImportConflictResolvers.samePackageAndProject(index, file, index.packageNameOf(file)));
        Map<String, String> renderedTypes = new LinkedHashMap<>();
        List<PlannerSupport.TextEdit> importEdits = new ArrayList<>();
        if (returnType != null && !returnType.isBlank()) {
            // Deep planning imports nested/generic/array/varargs/wildcard/annotation components, not just the outer type.
            ImportManager.TypeUse typeUse = planner.planTypeUsageDeep(file, returnType, "CHANGE_SIGNATURE_RETURN_IMPORT");
            renderedTypes.put(returnType, typeUse.renderedType());
            addDeduplicated(importEdits, typeUse.importEdits());
        }
        for (ParameterSpec parameter : parameters) {
            ImportManager.TypeUse typeUse = planner.planTypeUsageDeep(file, parameter.type(), "CHANGE_SIGNATURE_PARAMETER_IMPORT");
            renderedTypes.put(parameter.type(), typeUse.renderedType());
            addDeduplicated(importEdits, typeUse.importEdits());
        }
        return new ImportRewrite(renderedTypes, Map.of(), importEdits);
    }

    /**
     * Appends every edit in {@code additions} to {@code target} that is not already present, so a
     * type imported once (the deep planner shares import state across the return type, parameters,
     * and any earlier-planned type) is never emitted twice. Edits compare structurally by
     * file/range/text/kind via {@link PlannerSupport.TextEdit}'s record equality.
     */
    private static void addDeduplicated(List<PlannerSupport.TextEdit> target, List<PlannerSupport.TextEdit> additions) {
        for (PlannerSupport.TextEdit edit : additions) {
            if (!target.contains(edit)) {
                target.add(edit);
            }
        }
    }

    /**
     * Validates an added parameter's call-site default. The default is re-emitted verbatim at every caller, so it must
     * be provably safe to duplicate without changing program behavior. A change-signature default is <em>detached
     * text</em> — there is no {@link com.sun.source.util.TreePath} to resolve symbols against — so the only admissible
     * defaults are those whose reorder/duplication safety is decidable from syntax alone: literal compile-time
     * constants, class literals, and type-qualified enum/static constants, as proven by
     * {@link ExpressionPurityAnalyzer#provesDetachedDefaultSafe(String)}.
     *
     * <p>A blank default is left to the per-call-site coverage check (DEFAULT_ARGUMENT_UNRESOLVED). Everything that is
     * not a provable constant — method invocations ({@code System.nanoTime()}, {@code UUID.randomUUID()}, factory
     * calls), allocation, instance/member access through a value receiver ({@code customer.name}), bare identifiers —
     * is refused with DEFAULT_ARGUMENT_NOT_VERIFIABLE. There is intentionally no {@code allow_side_effects} bypass: a
     * detached default cannot be backed by an AST {@code isReorderSafe} proof, so admitting a side-effecting default
     * would silently break evaluation-order/duplication semantics at every call site. (Side-effecting expressions are
     * supported only by introduce-parameter, where a real {@link com.sun.source.util.TreePath} selection is available.)
     *
     * <p>Scope note: per-call-site lexical resolution of the default's referenced types is enforced separately by
     * {@link CallSiteRewriter} via {@code SemanticIndex#defaultExpressionResolutionFailure}, and the whole edited
     * workspace is finally re-validated by the javac-backed PreviewDiagnosticValidator before a preview is accepted.
     */
    private void validateCallSiteDefault(ParameterSpec parameter) throws SignatureRefusal {
        String defaultValue = parameter.defaultValue();
        if (defaultValue == null || defaultValue.isBlank()) {
            return;
        }
        // Introduce-parameter supplies a default that javac already proved reorder-safe from a real TreePath selection
        // (or the caller explicitly opted in). That AST-backed proof supersedes the detached-text constant gate below,
        // which exists only because a change-signature default is context-free text with no resolvable AST.
        if (parameter.astVerifiedDefault()) {
            return;
        }
        if (new ExpressionPurityAnalyzer().provesDetachedDefaultSafe(defaultValue)) {
            return;
        }
        throw new SignatureRefusal(
                "DEFAULT_ARGUMENT_NOT_VERIFIABLE",
                "Added parameter '" + parameter.name() + "' default could not be proven a compile-time-constant "
                        + "expression (literal, class literal, or type-qualified enum/static constant) that is safe to "
                        + "duplicate verbatim at every call site: " + defaultValue + ". A change-signature default is "
                        + "detached text with no resolvable context, so method calls, allocation, and value-receiver "
                        + "member access are refused; allow_side_effects does not bypass this contract. Use "
                        + "introduce-parameter for an AST-selected side-effecting expression.");
    }

    /**
     * G002d: emit the one canonical accepted-result JSON through {@link ResponseBuilder#acceptedResult} instead of
     * hand-rolling it, so stats/changedFiles/touchedFiles are derived from the actual edits and the envelope shape
     * (top-level {@code workspaceEdit{changes,fileOperations,warnings,preconditions,stats}}, {@code changedFiles}) stays
     * consistent with every other planner. Change-signature/introduce-parameter never add file operations, and the
     * preview delta is the non-authoritative {@link ResponseBuilder.DiagnosticDelta#unvalidated()} placeholder (the
     * authoritative delta is produced later by the PreviewDiagnosticValidator), so validation is not required here. The
     * semantic target is carried under {@code target.semanticKey} — the canonical axis the session layer re-resolves on
     * apply.
     */
    private String acceptedJson(boolean apply, String operation, MethodMatch declaration, List<PlannerSupport.TextEdit> edits, List<String> warnings) throws IOException {
        String semanticTargetJson = "{\"semanticKey\":" + SemanticKey.from(declaration.semantic().element()).toJson() + "}";
        return ResponseBuilder.acceptedResult(
                projectRoot,
                operation,
                apply,
                semanticTargetJson,
                edits,
                java.util.List.of(),
                warnings,
                java.util.List.of(),
                ResponseBuilder.DiagnosticDelta.unvalidated(),
                false);
    }

    private String refusedJson(String operation, boolean apply, String code, String message) {
        return "{\"accepted\":false,\"applied\":false,\"operation\":" + JsonUtil.quote(operation)
                + ",\"mode\":" + JsonUtil.quote(apply ? "apply" : "preview")
                + ",\"refusal\":{\"code\":" + JsonUtil.quote(code) + ",\"message\":" + JsonUtil.quote(message)
                + "},\"diagnostics\":[],\"warnings\":[],\"stats\":{\"editCount\":0,\"fileOperationCount\":0,\"touchedFileCount\":0},"
                + "\"workspaceEdit\":{\"changes\":[],\"fileOperations\":[],\"warnings\":[],\"preconditions\":[],"
                + "\"stats\":{\"editCount\":0,\"fileOperationCount\":0,\"touchedFileCount\":0}}}";
    }

    private Path sourceFile(Map<String, Object> fields) throws SignatureRefusal {
        String relative = stringField(fields, "relativePath", "");
        if (relative.isBlank()) {
            throw new SignatureRefusal("missing_relative_path", "relativePath is required.");
        }
        try {
            return ProjectPathResolver.resolveProjectRelative(projectRoot, relative, "relativePath");
        } catch (ProjectPathResolver.Violation refusal) {
            throw new SignatureRefusal(refusal.code(), refusal.getMessage());
        }
    }

    private int intField(Map<String, Object> fields, String key) throws SignatureRefusal {
        Object value = fields.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new SignatureRefusal("missing_" + key, key + " is required.");
    }

    private static String stringField(Map<String, Object> fields, String key, String defaultValue) {
        Object value = fields.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private int lineStartOffset(String source, int oneBasedLine) throws SignatureRefusal {
        if (oneBasedLine <= 0) {
            throw new SignatureRefusal("invalid_line", "line must be one-based.");
        }
        if (oneBasedLine == 1) {
            return 0;
        }

        int line = 1;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                line++;
                if (line == oneBasedLine) {
                    return index + 1;
                }
            }
        }
        throw new SignatureRefusal("line_out_of_range", "line is outside the source file.");
    }

    private record ChangePlan(List<PlannerSupport.TextEdit> edits, List<String> warnings) {}

    private record ImportRewrite(
            Map<String, String> renderedTypes,
            Map<String, String> renderedDefaults,
            List<PlannerSupport.TextEdit> importEdits) implements MethodSignatureModel.TypeRenderer {
        @Override
        public String simpleType(String type) {
            return renderedTypes.getOrDefault(type, type == null ? "void" : type);
        }
    }
}
