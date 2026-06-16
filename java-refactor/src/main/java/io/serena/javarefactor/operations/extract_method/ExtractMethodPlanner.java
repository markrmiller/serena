package io.serena.javarefactor.operations.extract_method;

import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.shared.AccessAdjustmentPlanner;
import io.serena.javarefactor.shared.AccessPlan;
import io.serena.javarefactor.shared.JavaStyleProfile;
import io.serena.javarefactor.shared.ProjectPathResolver;
import io.serena.javarefactor.shared.SourceText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;

/**
 * V2 extract-method planner backed by javac selection normalization and data-flow facts.
 *
 * <p>The planner is the orchestrator: it normalizes the raw selection ({@link SelectionAnalyzer}), asks the
 * semantic layer for statement/expression selection and data-flow facts, classifies the output and
 * control-flow shapes ({@link DataFlowAnalyzer}, {@link ControlFlowAnalyzer}), renders the concrete
 * extraction ({@link MethodBodySynthesizer}), and assembles the workspace edits. The hard analyses live in
 * the four units; this class wires them together and owns the refusal taxonomy.
 *
 * <p>V2 scope: extract method supports only zero-output and one-output complete-statement selections plus
 * expression extraction. Two richer behaviors are NOT part of the V2 surface and are reserved for a future V3
 * plan — extracting a selection that writes more than one variable read afterwards (multi-output, which would need
 * a synthesized record holder), and extracting a selection that escapes via {@code return}/{@code break}/
 * {@code continue} (control-flow exits, which would need a signal the caller acts on). Both are hardwired off here:
 * the corresponding request/config keys ({@code allow_multiple_outputs} / {@code allow_control_flow_exits}) are
 * intentionally NOT honored, so a selection that needs either is refused with a precise structured reason rather
 * than silently extracted under a non-functional opt-in. Selections that are semantically impossible regardless
 * (lambda/nested-class boundaries, labeled jumps, mixed exit kinds) are likewise always refused.
 */
public final class ExtractMethodPlanner {
    private final Path projectRoot;
    private final JavaProjectModel model;
    private final AccessAdjustmentPlanner accessPlanner = new AccessAdjustmentPlanner();

    public ExtractMethodPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot;
        this.model = model;
    }

    @SuppressWarnings("unchecked")
    public String extractMethod(Map<String, Object> fields, boolean apply) {
        try {
            Path file = sourceFile(fields);
            String source = SourceText.read(model, file);
            String name = stringField(fields, "newMethodName", "");
            if (name.isBlank()) {
                throw new Refusal("missing_new_method_name", "newMethodName is required.");
            }
            Object selectionRaw = fields.get("selection");
            if (!(selectionRaw instanceof Map<?, ?> selection)) {
                throw new Refusal("missing_selection", "selection is required.");
            }
            Map<String, Object> selectionFields = (Map<String, Object>) selection;
            int startLine = intField(selectionFields, "startLine");
            int startColumn = intField(selectionFields, "startColumn");
            int endLine = intField(selectionFields, "endLine");
            int endColumn = intField(selectionFields, "endColumn");
            int rawStart = SelectionAnalyzer.offset(source, startLine, startColumn);
            int rawEnd = SelectionAnalyzer.offset(source, endLine, endColumn);
            SelectionAnalyzer.NormalizedRange range = SelectionAnalyzer.normalize(source, rawStart, rawEnd);
            if (range.isEmpty()) {
                throw new Refusal("invalid_selection", "Selection range must be non-empty after whitespace normalization.");
            }
            int start = range.start();
            int end = range.end();

            // G003 (V2 hard scope): multi-output and arbitrary control-flow-preserving extraction are NOT part of the
            // V2 supported surface. These are hardwired off and the request/config opt-out keys (allow_multiple_outputs /
            // allow_control_flow_exits) are intentionally ignored, so the MULTI_OUTPUT and control-flow synthesizer
            // dispatch are unreachable from V2 regardless of caller config. V2 extractMethod accepts only zero-output and
            // one-output complete-statement selections plus expression extraction; everything else is a structured
            // refusal. (A later V3 plan may promote these behind its own acceptance matrix.)
            boolean allowMultipleOutputs = false;
            boolean allowControlFlowExits = false;

            try (SemanticIndex index = SemanticIndex.open(model, PlannerSupport.relative(projectRoot, file))) {
                if (index.methodNameExists(file, name)) {
                    throw new Refusal("target_method_exists", "Source type already declares a method named '" + name + "'.");
                }
                JavaStyleProfile style = JavaStyleProfile.infer(source);
                String visibility = stringField(fields, "visibility", "private");
                boolean makeStatic = boolField(fields, "makeStatic", boolField(fields, "make_static", false));
                AccessPlan accessPlan = accessPlanner.plan(visibility + " ", "", "", true, name, true);

                SemanticIndex.SemanticStatementSelection statementSelection = index.statementSelection(file, start, end);
                SemanticIndex.SemanticExpressionSelection expressionSelection = null;
                if (statementSelection != null && !statementSelection.completeStatements()) {
                    SemanticIndex.SemanticExpressionSelection statementExpressionSelection = index.selectedExpression(file, startLine, startColumn, endLine, endColumn);
                    if (statementExpressionSelection == null) {
                        return refusedJson(
                                apply,
                                "SELECTION_NOT_STATEMENT_ALIGNED",
                                "V2 extract method requires complete javac statement selections; suggested range: "
                                        + rangeDiagnostic(source, statementSelection.suggestedRange()),
                                suggestedRangesJson(source, statementSelection.suggestedRange()));
                    }
                }
                if (statementSelection != null && statementSelection.completeStatements()) {
                    return planStatement(index, file, source, statementSelection, name, visibility, makeStatic, style, accessPlan,
                            allowMultipleOutputs, allowControlFlowExits, apply);
                }

                if (expressionSelection == null) {
                    expressionSelection = index.selectedExpression(file, startLine, startColumn, endLine, endColumn);
                }
                if (expressionSelection == null) {
                    throw new Refusal("selection_not_extractable", "V2 extract method requires a complete expression or complete statement selection; suggested range: expand to the nearest AST expression or statement boundaries.");
                }
                if (expressionSelection.enclosingMethodRange() == null && !expressionSelection.initializerScope()) {
                    // The selection resolves to an expression that is neither inside a method/constructor body nor inside
                    // a recognized initializer scope (class/instance initializer block or field initializer) — for
                    // example, an annotation argument expression. There is no extraction scope to host the helper, so
                    // refuse explicitly rather than failing with an opaque error.
                    throw new Refusal("initializer_extraction_unsupported", "V2 extract method requires a selection inside a method, constructor, or a class/instance initializer block or field initializer; this expression has no extractable scope.");
                }
                if (expressionSelection.initializerScope() && expressionSelection.enclosingTypeBodyRange() == null) {
                    throw new Refusal("initializer_extraction_unsupported", "V2 extract method could not resolve the enclosing type body for the initializer selection.");
                }
                return planExpression(file, source, expressionSelection, name, visibility, makeStatic, style, accessPlan, apply);
            }
        } catch (Refusal refusal) {
            return refusedJson(apply, refusal.code, refusal.getMessage());
        } catch (SelectionAnalyzer.SelectionException selectionError) {
            return refusedJson(apply, selectionError.code(), selectionError.getMessage());
        } catch (Exception error) {
            return refusedJson(apply, "extract_method_failed", error.getMessage());
        }
    }

    // ── statement extraction ─────────────────────────────────────────────────────────────────────────

    private String planStatement(
            SemanticIndex index,
            Path file,
            String source,
            SemanticIndex.SemanticStatementSelection selection,
            String name,
            String visibility,
            boolean makeStatic,
            JavaStyleProfile style,
            AccessPlan accessPlan,
            boolean allowMultipleOutputs,
            boolean allowControlFlowExits,
            boolean apply) throws Refusal, java.io.IOException {
        if (selection.crossesLambdaOrClass()) {
            throw new Refusal("lambda_boundary_unsupported", "V2 extract method refuses selections that cross lambda or nested-class boundaries.");
        }
        if (makeStatic && (selection.usesThis() || selection.usesSuper())) {
            throw new Refusal("make_static_unsupported", "makeStatic cannot be used when the extracted selection depends on this or super.");
        }

        DataFlowAnalyzer.Strategy outputStrategy = DataFlowAnalyzer.classify(selection.outputs(), allowMultipleOutputs);

        String enclosingHeader = enclosingMethodHeader(source, selection.enclosingMethodRange(), selection.range().start());
        ControlFlowAnalyzer.ControlFlow controlFlow = ControlFlowAnalyzer.analyze(
                source.substring(selection.range().start(), selection.range().end()),
                selection.hasControlFlowExit(),
                enclosingHeader);

        if (controlFlow.kind() == ControlFlowAnalyzer.ExitKind.UNSUPPORTED) {
            throw new Refusal("control_flow_unsupported",
                    "V2 extract method cannot synthesize this control-flow exit (labeled jump or mixed return/break/continue); select a region whose escaping jumps are a single uniform kind.");
        }
        if (controlFlow.hasExit() && !allowControlFlowExits) {
            throw new Refusal("control_flow_unsupported",
                    "V2 extract method refuses selections with non-local return/break/continue; control-flow-exit extraction is reserved for a future V3 plan and is not available in V2. Suggested range: select whole statements without non-local control flow.");
        }
        if (controlFlow.hasExit() && outputStrategy.kind() != DataFlowAnalyzer.Strategy.Kind.VOID) {
            throw new Refusal("control_flow_with_outputs_unsupported",
                    "V2 extract method cannot combine a control-flow exit with data-flow outputs in one helper; extract a region that either escapes or produces outputs, not both.");
        }
        if (outputStrategy.isRefused()) {
            throw new Refusal(outputStrategy.refusalCode(), outputStrategy.refusalMessage());
        }

        String selectionIndent = SelectionAnalyzer.leadingWhitespace(source, selection.range().start());
        String memberIndent = style.outerIndentFor(selectionIndent);
        String staticModifier = (makeStatic || selection.enclosingMethodStatic()) && !selection.usesThis() && !selection.usesSuper() ? " static" : "";
        String selected = source.substring(selection.range().start(), selection.range().end());

        // HB-8: choose guaranteed-unique names for the synthesized holder local and nested record type BEFORE rendering,
        // using javac scope facts rather than post-emit diagnostics. The holder local lives at the call site (selection
        // start) inside the enclosing executable; the record type is a new member of the enclosing type. The names are
        // computed only for the shape that will actually be synthesized — a void/single-output extraction synthesizes no
        // holder, so it must not be refused for a phantom collision (and need not resolve an enclosing executable).
        int callSiteOffset = selection.range().start();
        Element enclosingExecutable = selection.enclosingExecutable();
        boolean multiOutput = !controlFlow.hasExit() && outputStrategy.kind() == DataFlowAnalyzer.Strategy.Kind.MULTI_OUTPUT;
        boolean valueReturn = controlFlow.hasExit() && controlFlow.kind() == ControlFlowAnalyzer.ExitKind.RETURN_VALUE;
        String resultTypeName = multiOutput ? uniqueTypeMemberName(index, enclosingExecutable, capitalize(name) + "Result") : null;
        String resultHolderName = multiOutput ? uniqueLocalName(index, file, callSiteOffset, enclosingExecutable, "result") : null;
        String signalTypeName = valueReturn ? uniqueTypeMemberName(index, enclosingExecutable, capitalize(name) + "Signal") : null;
        String signalHolderName = valueReturn ? uniqueLocalName(index, file, callSiteOffset, enclosingExecutable, "signal") : null;

        MethodBodySynthesizer.Context ctx = new MethodBodySynthesizer.Context(
                style,
                name,
                visibility,
                staticModifier,
                parameters(style, selection.inputs()),
                arguments(selection.inputs()),
                throwsClause(selection.checkedExceptions()),
                selected.stripIndent(),
                resultTypeName,
                resultHolderName,
                signalTypeName,
                signalHolderName);

        MethodBodySynthesizer.Synthesis synthesis = synthesize(ctx, outputStrategy, controlFlow);

        int afterMethod = selection.enclosingMethodRange().end();
        int insertionOffset = memberInsertionOffset(source, afterMethod, selection.enclosingTypeBodyRange());

        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        edits.add(new PlannerSupport.TextEdit(file, selection.range().start(), selection.range().end(), synthesis.callText(), "EXTRACT_METHOD_CALL"));
        edits.add(new PlannerSupport.TextEdit(file, insertionOffset, insertionOffset, synthesis.methodText(), "EXTRACT_METHOD_DECLARATION"));
        if (synthesis.resultTypeText() != null) {
            // The synthesized result/signal record is hosted at the end of the enclosing type body so it sits with
            // the other helpers and is in scope for both the helper and the call site.
            int recordOffset = selection.enclosingTypeBodyRange() != null
                    ? selection.enclosingTypeBodyRange().end() - 1
                    : insertionOffset;
            edits.add(new PlannerSupport.TextEdit(file, recordOffset, recordOffset, synthesis.resultTypeText(), "EXTRACT_METHOD_RESULT_TYPE"));
        }

        String targetJson = SemanticKey.from(selection.enclosingExecutable()).toJson();
        String accessPlansJson = accessPlanner.plansJson(List.of(accessPlan));
        return ResponseBuilder.acceptedResult(projectRoot, "extractMethod", apply, "{\"semanticKey\":" + targetJson + "}", edits, List.of(), List.of(statementSummary(outputStrategy, controlFlow)), List.of(), ResponseBuilder.DiagnosticDelta.unvalidated(), false, accessPlansJson);
    }

    private MethodBodySynthesizer.Synthesis synthesize(
            MethodBodySynthesizer.Context ctx,
            DataFlowAnalyzer.Strategy outputStrategy,
            ControlFlowAnalyzer.ControlFlow controlFlow) {
        if (controlFlow.hasExit()) {
            return MethodBodySynthesizer.controlFlow(ctx, controlFlow);
        }
        return switch (outputStrategy.kind()) {
            case VOID -> MethodBodySynthesizer.voidExtraction(ctx);
            case SINGLE_OUTPUT -> MethodBodySynthesizer.singleOutput(ctx, outputStrategy.outputs().get(0));
            case MULTI_OUTPUT -> MethodBodySynthesizer.multiOutput(ctx, outputStrategy.outputs());
            case REFUSED -> throw new IllegalStateException("refused strategy reached synthesis");
        };
    }

    // ── HB-8: scope-aware unique-name selection ─────────────────────────────────────────────────────

    /** Maximum numeric suffixes tried when resolving a name collision before refusing. */
    private static final int MAX_UNIQUE_NAME_ATTEMPTS = 100;

    /**
     * A guaranteed-unique simple name for the synthesized holder local at {@code callSiteOffset}, derived from
     * {@code base} via javac scope facts. Throws a structured {@code extract_name_collision} refusal when no free name
     * can be formed within {@link #MAX_UNIQUE_NAME_ATTEMPTS}, rather than emitting a clashing declaration.
     */
    private static String uniqueLocalName(SemanticIndex index, Path file, int callSiteOffset, Element enclosingExecutable, String base)
            throws Refusal {
        String unique = index.uniqueLocalName(file, callSiteOffset, enclosingExecutable, base, MAX_UNIQUE_NAME_ATTEMPTS);
        if (unique == null) {
            throw new Refusal("extract_name_collision",
                    "V2 extract method could not synthesize a unique local name for the result/signal holder (base '" + base
                            + "' and " + MAX_UNIQUE_NAME_ATTEMPTS + " numbered variants all clash with existing locals or parameters).");
        }
        return unique;
    }

    /**
     * A guaranteed-unique simple name for the synthesized nested record type, derived from {@code base} via the enclosing
     * type's declared members. Throws a structured {@code extract_name_collision} refusal when no free name can be formed
     * within {@link #MAX_UNIQUE_NAME_ATTEMPTS}, rather than emitting a clashing type declaration.
     */
    private static String uniqueTypeMemberName(SemanticIndex index, Element enclosingExecutable, String base) throws Refusal {
        String unique = index.uniqueTypeMemberName(enclosingExecutable, base, MAX_UNIQUE_NAME_ATTEMPTS);
        if (unique == null) {
            throw new Refusal("extract_name_collision",
                    "V2 extract method could not synthesize a unique name for the result/signal record type (base '" + base
                            + "' and " + MAX_UNIQUE_NAME_ATTEMPTS + " numbered variants all clash with existing members or nested types).");
        }
        return unique;
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private String statementSummary(DataFlowAnalyzer.Strategy outputStrategy, ControlFlowAnalyzer.ControlFlow controlFlow) {
        if (controlFlow.hasExit()) {
            return "V2 extractMethod normalized the selection with javac AST ranges and synthesized a control-flow signal ("
                    + controlFlow.kind() + ") the caller acts on, plus parameters/throws/static facts.";
        }
        if (outputStrategy.kind() == DataFlowAnalyzer.Strategy.Kind.MULTI_OUTPUT) {
            return "V2 extractMethod normalized the selection with javac AST ranges and synthesized a record result holder for "
                    + outputStrategy.outputs().size() + " outputs, threading parameters/throws/static facts and a destructuring call.";
        }
        return "V2 extractMethod normalized the selection with javac AST ranges, synthesized parameters/return/throws/static facts, and inserted the extracted method using the type's member-grouping style.";
    }

    // ── expression extraction ────────────────────────────────────────────────────────────────────────

    private String planExpression(
            Path file,
            String source,
            SemanticIndex.SemanticExpressionSelection selection,
            String name,
            String visibility,
            boolean makeStatic,
            JavaStyleProfile style,
            AccessPlan accessPlan,
            boolean apply) throws Refusal, java.io.IOException {
        if (selection.type() == null || selection.type().isBlank()) {
            throw new Refusal("expression_type_unknown", "V2 extract method could not infer the selected expression type.");
        }
        if (makeStatic && (selection.usesThis() || selection.usesSuper())) {
            throw new Refusal("make_static_unsupported", "makeStatic cannot be used when the extracted selection depends on this or super.");
        }
        String staticModifier = (makeStatic || selection.enclosingMethodStatic()) && !selection.usesThis() && !selection.usesSuper() ? " static" : "";
        String header = visibility + staticModifier + " " + selection.type() + " " + name + "(" + parameters(style, selection.inputs()) + ")" + throwsClause(selection.checkedExceptions());
        String methodText = MethodBodySynthesizer.renderInsertedMember(style, header, "return " + selection.text().strip() + ";");
        String callText = name + "(" + arguments(selection.inputs()) + ")";

        int expressionInsertionOffset = selection.initializerScope()
                ? selection.enclosingTypeBodyRange().end() - 1
                : selection.enclosingMethodRange().end();
        List<PlannerSupport.TextEdit> edits = List.of(
                new PlannerSupport.TextEdit(file, selection.range().start(), selection.range().end(), callText, "EXTRACT_METHOD_CALL"),
                new PlannerSupport.TextEdit(file, expressionInsertionOffset, expressionInsertionOffset, methodText, "EXTRACT_METHOD_DECLARATION"));
        // Blocker 4: every accepted expression preview must carry a stable session target. An executable-scoped
        // selection uses the enclosing method/constructor semantic key; an initializer-scope selection (field
        // initializer or class/instance initializer block) has no enclosing executable, so it uses the initializer
        // host's stable identity (the field's semantic key, or the enclosing type + initializer discriminator +
        // normalized range for a block). A selection that can produce neither is refused — never accepted as a preview
        // that cannot become a V2 session.
        String exprSemanticTargetJson;
        if (selection.enclosingExecutable() != null) {
            exprSemanticTargetJson = "{\"semanticKey\":" + SemanticKey.from(selection.enclosingExecutable()).toJson() + "}";
        } else if (selection.initializerScope()
                && selection.initializerTargetKey() != null
                && !selection.initializerTargetKey().isBlank()) {
            exprSemanticTargetJson = "{\"identity\":" + JsonUtil.quote(selection.initializerTargetKey()) + "}";
        } else {
            throw new Refusal(
                    "initializer_extraction_unsupported",
                    "V2 extract method could not derive a stable session target for this initializer-scope selection; "
                            + "refusing rather than producing a preview that cannot be applied as a session.");
        }
        String exprAccessPlansJson = accessPlanner.plansJson(List.of(accessPlan));
        return ResponseBuilder.acceptedResult(projectRoot, "extractMethod", apply, exprSemanticTargetJson, edits, List.of(), List.of("V2 extractMethod extracted a javac-resolved expression and synthesized parameters/return/throws/static facts."), List.of(), ResponseBuilder.DiagnosticDelta.unvalidated(), false, exprAccessPlansJson);
    }

    // ── insertion offset (G023) ──────────────────────────────────────────────────────────────────────

    /** An indented {@code private ... ( ... ) ... {} member header (a private method/constructor declaration). */
    private static final java.util.regex.Pattern PRIVATE_METHOD_HEADER =
            java.util.regex.Pattern.compile("(?m)^[ \\t]*private\\b[^=;\\n]*\\([^)]*\\)[^;{]*\\{");

    /**
     * Style-aware insertion offset for a newly extracted method (G023).
     *
     * <p>By default the extracted method is inserted immediately after the enclosing method
     * ({@code afterEnclosingMethod}). When the enclosing method is NOT the last member of its type AND the type groups
     * its private helpers below the public callers (a private method declaration appears after the enclosing method),
     * the new helper is instead appended at the end of the type body — joining the helper group at the bottom rather
     * than being wedged between two callers — so the surrounding member-ordering style is preserved.
     */
    static int memberInsertionOffset(String source, int afterEnclosingMethod, SemanticIndex.SourceRange typeBodyRange) {
        if (typeBodyRange == null) {
            return afterEnclosingMethod;
        }
        int closingBrace = typeBodyRange.end() - 1; // index of the type body's closing '}'
        if (closingBrace <= afterEnclosingMethod || closingBrace > source.length()) {
            return afterEnclosingMethod;
        }
        String trailing = source.substring(afterEnclosingMethod, closingBrace);
        if (trailing.isBlank()) {
            // The enclosing method is already the last member; after-method and end-of-body coincide.
            return afterEnclosingMethod;
        }
        if (!PRIVATE_METHOD_HEADER.matcher(trailing).find()) {
            // No private helper grouped below the enclosing method: keep the default after-method placement.
            return afterEnclosingMethod;
        }
        // Append after the last existing member, just before the type body's closing brace.
        int lastMember = closingBrace;
        while (lastMember > afterEnclosingMethod && Character.isWhitespace(source.charAt(lastMember - 1))) {
            lastMember--;
        }
        return lastMember;
    }

    // ── signature fragments ──────────────────────────────────────────────────────────────────────────

    private String parameters(JavaStyleProfile style, List<SemanticIndex.SemanticExtractVariable> inputs) {
        List<String> rendered = new ArrayList<>();
        for (SemanticIndex.SemanticExtractVariable input : inputs) {
            // Honor the inferred final-parameter style for the synthesized helper signature.
            rendered.add(style.parameter(input.type(), input.name()));
        }
        return String.join(", ", rendered);
    }

    private String arguments(List<SemanticIndex.SemanticExtractVariable> inputs) {
        List<String> rendered = new ArrayList<>();
        for (SemanticIndex.SemanticExtractVariable input : inputs) {
            rendered.add(input.name());
        }
        return String.join(", ", rendered);
    }

    private String throwsClause(Set<String> checkedExceptions) {
        if (checkedExceptions.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(checkedExceptions);
        sorted.sort(String::compareTo);
        return " throws " + String.join(", ", sorted);
    }

    /**
     * The enclosing method's header text — from the start of the method declaration up to the opening brace of
     * its body — used by {@link ControlFlowAnalyzer} to read the declared return type for value returns. Returns
     * {@code null} when no enclosing method range is known (initializer scopes have no method header / return type).
     */
    private static String enclosingMethodHeader(String source, SemanticIndex.SourceRange methodRange, int bodyHint) {
        if (methodRange == null) {
            return null;
        }
        int from = Math.max(0, Math.min(methodRange.start(), source.length()));
        int searchEnd = Math.max(from, Math.min(bodyHint, source.length()));
        int brace = source.indexOf('{', from);
        int to = brace >= from && brace <= searchEnd ? brace : searchEnd;
        return source.substring(from, to);
    }

    // ── diagnostics ──────────────────────────────────────────────────────────────────────────────────

    private String rangeDiagnostic(String source, SemanticIndex.SourceRange range) {
        if (range == null) {
            return "unavailable";
        }
        int[] start = SelectionAnalyzer.lineColumn(source, range.start());
        int[] end = SelectionAnalyzer.lineColumn(source, range.end());
        return "startLine=" + start[0] + ", startColumn=" + start[1] + ", endLine=" + end[0] + ", endColumn=" + end[1];
    }

    private String suggestedRangesJson(String source, SemanticIndex.SourceRange range) {
        if (range == null) {
            return "[]";
        }
        int[] start = SelectionAnalyzer.lineColumn(source, range.start());
        int[] end = SelectionAnalyzer.lineColumn(source, range.end());
        return "[{\"startLine\":"
                + start[0]
                + ",\"startColumn\":"
                + start[1]
                + ",\"endLine\":"
                + end[0]
                + ",\"endColumn\":"
                + end[1]
                + "}]";
    }

    // ── field parsing ────────────────────────────────────────────────────────────────────────────────

    private Path sourceFile(Map<String, Object> fields) throws Refusal {
        String relative = stringField(fields, "relativePath", "");
        if (relative.isBlank()) {
            throw new Refusal("missing_relative_path", "relativePath is required.");
        }
        try {
            return ProjectPathResolver.resolveProjectRelative(projectRoot, relative, "relativePath");
        } catch (ProjectPathResolver.Violation refusal) {
            throw new Refusal(refusal.code(), refusal.getMessage());
        }
    }

    private int intField(Map<String, Object> fields, String key) throws Refusal {
        Object value = fields.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new Refusal("missing_" + key, key + " is required.");
    }

    private static boolean boolField(Map<String, Object> fields, String key, boolean defaultValue) {
        Object value = fields.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String stringField(Map<String, Object> fields, String key, String defaultValue) {
        Object value = fields.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private String refusedJson(boolean apply, String code, String message) {
        return refusedJson(apply, code, message, "[]");
    }

    private String refusedJson(boolean apply, String code, String message, String suggestedRangesJson) {
        return "{\"accepted\":false,\"applied\":false,\"operation\":\"extractMethod\",\"mode\":"
                + JsonUtil.quote(apply ? "apply" : "preview")
                + ",\"refusal\":{\"code\":"
                + JsonUtil.quote(code)
                + ",\"message\":"
                + JsonUtil.quote(message)
                + "},\"suggestedRanges\":"
                + suggestedRangesJson
                + ",\"diagnostics\":[],\"warnings\":[],\"stats\":{\"editCount\":0,\"fileOperationCount\":0,\"touchedFileCount\":0},\"workspaceEdit\":{\"changes\":[],\"fileOperations\":[],\"warnings\":[],\"preconditions\":[],\"stats\":{\"editCount\":0,\"fileOperationCount\":0,\"touchedFileCount\":0}}}";
    }

    private static final class Refusal extends Exception {
        private final String code;
        private Refusal(String code, String message) { super(message); this.code = code; }
    }
}
