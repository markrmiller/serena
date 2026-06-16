package io.serena.javarefactor.operations.extract_method;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.shared.JavaStyleProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the concrete extraction text — the call that replaces the selection, the extracted method
 * declaration, and (for multi-output extractions) the synthesized result-holder record — from the
 * strategy facts produced by {@link DataFlowAnalyzer} and {@link ControlFlowAnalyzer}.
 *
 * <p>The synthesizer is where the four supported shapes become source:
 *
 * <ul>
 *   <li><b>void</b> — {@code name(args);} replaces the selection; the helper is {@code void}.</li>
 *   <li><b>single output</b> — {@code Type v = name(args);} (or {@code v = name(args);} when {@code v}
 *       is declared before the selection); the helper returns the value.</li>
 *   <li><b>multi output</b> — the helper returns a synthesized nested {@code record} holding every
 *       output; the caller destructures it field-by-field. The record declaration is emitted as a
 *       separate inserted member.</li>
 *   <li><b>control-flow signal</b> — the helper returns a boolean (break/continue/void-return) or a
 *       {@code <Name>Signal} record (value-return); the caller guards on it: {@code if (name(args))
 *       break;} / {@code if (name(args)) return;} / {@code var s = name(args); if (s.returned()) return
 *       s.value();}. Inside the helper, each escaping jump is rewritten to the matching signal and a
 *       fall-through is appended.</li>
 * </ul>
 *
 * <p>Multi-output and control-flow exits are <em>not</em> combined: a selection that both writes
 * multiple later-read variables and escapes via a jump is genuinely ambiguous to thread through one
 * return value, so the planner refuses it upstream. Each shape is synthesized in isolation here.
 */
final class MethodBodySynthesizer {

    private MethodBodySynthesizer() {}

    /**
     * The rendered extraction.
     *
     * @param callText the text that replaces the selection
     * @param methodText the extracted method declaration (already separated from the previous member)
     * @param resultTypeText the synthesized result-holder record declaration, or {@code null} when none is
     *     needed (only multi-output extractions produce one)
     */
    record Synthesis(String callText, String methodText, String resultTypeText) {}

    /**
     * Inputs shared by every synthesis shape, gathered once by the planner.
     *
     * <p>{@code resultTypeName}/{@code resultHolderName} (multi-output) and {@code signalTypeName}/{@code
     * signalHolderName} (value-return control flow) are the scope-checked, guaranteed-unique names the planner computed
     * via {@link SemanticIndex} before synthesis (HB-8). The synthesizer renders exactly these names rather than
     * hard-coded literals, so the emitted holder local and nested record type never clash with an existing local,
     * parameter, member, or nested type.
     */
    record Context(
            JavaStyleProfile style,
            String name,
            String visibility,
            String staticModifier,
            String parameters,
            String arguments,
            String throwsClause,
            String selectedBody,
            String resultTypeName,
            String resultHolderName,
            String signalTypeName,
            String signalHolderName) {}

    // ── void ────────────────────────────────────────────────────────────────────────────────────────

    static Synthesis voidExtraction(Context ctx) {
        String header = ctx.visibility() + ctx.staticModifier() + " void " + ctx.name()
                + "(" + ctx.parameters() + ")" + ctx.throwsClause();
        String methodText = renderInsertedMember(ctx.style(), header, ctx.selectedBody());
        return new Synthesis(ctx.name() + "(" + ctx.arguments() + ");", methodText, null);
    }

    // ── single output ────────────────────────────────────────────────────────────────────────────────

    static Synthesis singleOutput(Context ctx, SemanticIndex.SemanticExtractVariable output) {
        String returnExpression = output.declaredInSelection()
                ? selectedOutputInitializer(ctx.selectedBody(), output)
                : null;
        String methodBody = returnExpression != null
                ? "return " + returnExpression + ";"
                : ctx.selectedBody() + "\nreturn " + output.name() + ";";
        String callPrefix = output.declaredInSelection()
                ? output.type() + " " + output.name() + " = "
                : output.name() + " = ";
        String header = ctx.visibility() + ctx.staticModifier() + " " + output.type() + " " + ctx.name()
                + "(" + ctx.parameters() + ")" + ctx.throwsClause();
        String methodText = renderInsertedMember(ctx.style(), header, methodBody);
        return new Synthesis(callPrefix + ctx.name() + "(" + ctx.arguments() + ");", methodText, null);
    }

    // ── multi output ─────────────────────────────────────────────────────────────────────────────────

    static Synthesis multiOutput(Context ctx, List<SemanticIndex.SemanticExtractVariable> outputs) {
        String resultType = ctx.resultTypeName();
        // The helper returns a record carrying every output; the caller destructures it. The record is nested,
        // private, and static (an inner record is implicitly static, but we render `static` for clarity and to
        // keep it valid inside non-static inner classes). Each output's declared-in-selection flag drives whether
        // the caller redeclares the variable or merely reassigns it.
        String components = renderRecordComponents(outputs);
        String constructorArgs = renderArguments(outputs);
        String methodBody = ctx.selectedBody() + "\nreturn new " + resultType + "(" + constructorArgs + ");";
        String header = ctx.visibility() + ctx.staticModifier() + " " + resultType + " " + ctx.name()
                + "(" + ctx.parameters() + ")" + ctx.throwsClause();
        String methodText = renderInsertedMember(ctx.style(), header, methodBody);

        String resultVar = ctx.resultHolderName();
        StringBuilder call = new StringBuilder();
        call.append(resultType).append(" ").append(resultVar).append(" = ")
                .append(ctx.name()).append("(").append(ctx.arguments()).append(");");
        for (SemanticIndex.SemanticExtractVariable output : outputs) {
            call.append("\n");
            if (output.declaredInSelection()) {
                call.append(output.type()).append(" ").append(output.name());
            } else {
                call.append(output.name());
            }
            call.append(" = ").append(resultVar).append(".").append(output.name()).append("();");
        }

        String recordHeader = "private" + (ctx.staticModifier().isBlank() ? "" : ctx.staticModifier())
                + " record " + resultType + "(" + components + ")";
        // A record with no body renders as `... ) {}` via renderMethod's brace block; an empty body is correct.
        String resultTypeText = renderInsertedMember(ctx.style(), recordHeader, "");
        return new Synthesis(call.toString(), methodText, resultTypeText);
    }

    // ── control-flow signal ─────────────────────────────────────────────────────────────────────────

    /**
     * Synthesizes a control-flow extraction. The selected body's escaping jumps are rewritten to a boolean (or
     * {@code <Name>Signal}) return, and the caller guards on the result. {@code controlFlow} must be a supported
     * (non-{@code UNSUPPORTED}, non-{@code NONE}) classification. The selection must have no data-flow outputs
     * (enforced by the planner) so the only thing the helper communicates is the jump decision.
     */
    static Synthesis controlFlow(Context ctx, ControlFlowAnalyzer.ControlFlow controlFlow) {
        return switch (controlFlow.kind()) {
            case RETURN_VALUE -> valueReturnSignal(ctx, controlFlow.returnType());
            case RETURN_VOID -> booleanSignal(ctx, "return");
            case BREAK -> booleanSignal(ctx, "break");
            case CONTINUE -> booleanSignal(ctx, "continue");
            default -> throw new IllegalArgumentException("Unsupported control-flow kind: " + controlFlow.kind());
        };
    }

    /** boolean-signal shape for void-return / break / continue: {@code if (name(args)) <keyword>;}. */
    private static Synthesis booleanSignal(Context ctx, String callerKeyword) {
        String rewrittenBody = rewriteJumps(ctx.selectedBody(), "return true;");
        String methodBody = rewrittenBody + "\nreturn false;";
        String header = ctx.visibility() + ctx.staticModifier() + " boolean " + ctx.name()
                + "(" + ctx.parameters() + ")" + ctx.throwsClause();
        String methodText = renderInsertedMember(ctx.style(), header, methodBody);
        String callText = "if (" + ctx.name() + "(" + ctx.arguments() + ")) " + callerKeyword + ";";
        return new Synthesis(callText, methodText, null);
    }

    /** value-return shape: a {@code <Name>Signal(boolean returned, <Type> value)} the caller acts on. */
    private static Synthesis valueReturnSignal(Context ctx, String returnType) {
        String signalType = ctx.signalTypeName();
        // Rewrite each escaping `return <expr>;` to `return new Signal(true, <expr>);` and bare `return;`
        // cannot occur here (the classifier guarantees a uniform value-return). Fall-through returns "not taken".
        String rewrittenBody = rewriteValueReturns(ctx.selectedBody(), signalType);
        String methodBody = rewrittenBody + "\nreturn new " + signalType + "(false, " + defaultValue(returnType) + ");";
        String header = ctx.visibility() + ctx.staticModifier() + " " + signalType + " " + ctx.name()
                + "(" + ctx.parameters() + ")" + ctx.throwsClause();
        String methodText = renderInsertedMember(ctx.style(), header, methodBody);

        String signalVar = ctx.signalHolderName();
        StringBuilder call = new StringBuilder();
        call.append(signalType).append(" ").append(signalVar).append(" = ").append(ctx.name()).append("(").append(ctx.arguments()).append(");");
        call.append("\nif (").append(signalVar).append(".returned()) return ").append(signalVar).append(".value();");

        String recordHeader = "private" + (ctx.staticModifier().isBlank() ? "" : ctx.staticModifier())
                + " record " + signalType + "(boolean returned, " + returnType + " value)";
        String resultTypeText = renderInsertedMember(ctx.style(), recordHeader, "");
        return new Synthesis(call.toString(), methodText, resultTypeText);
    }

    // ── jump rewriting (brace/literal aware, top-level only) ─────────────────────────────────────────

    /**
     * Replaces each <em>non-local</em> jump (return/break/continue that escapes the selection) in {@code body}
     * with {@code replacement}. Jumps captured by a loop/switch opened inside the body are left untouched. The
     * scan reuses {@link ControlFlowAnalyzer#nonLocalJumps} so the locality rules are identical to classification.
     */
    static String rewriteJumps(String body, String replacement) {
        List<ControlFlowAnalyzer.Jump> jumps = ControlFlowAnalyzer.nonLocalJumps(body);
        return applyReplacements(body, jumps, jump -> replacement);
    }

    /** Rewrites each non-local {@code return <expr>;} to {@code return new <signalType>(true, <expr>);}. */
    static String rewriteValueReturns(String body, String signalType) {
        List<ControlFlowAnalyzer.Jump> jumps = ControlFlowAnalyzer.nonLocalJumps(body);
        return applyReplacements(body, jumps, jump ->
                "return new " + signalType + "(true, " + jump.value() + ");");
    }

    private interface Replacement {
        String render(ControlFlowAnalyzer.Jump jump);
    }

    private static String applyReplacements(String body, List<ControlFlowAnalyzer.Jump> jumps, Replacement replacement) {
        if (jumps.isEmpty()) {
            return body;
        }
        StringBuilder rewritten = new StringBuilder();
        int cursor = 0;
        for (ControlFlowAnalyzer.Jump jump : jumps) {
            if (jump.kind() == ControlFlowAnalyzer.ExitKind.UNSUPPORTED) {
                continue;
            }
            rewritten.append(body, cursor, jump.start());
            rewritten.append(replacement.render(jump));
            cursor = jump.end();
        }
        rewritten.append(body, cursor, body.length());
        return rewritten.toString();
    }

    // ── shared rendering helpers ─────────────────────────────────────────────────────────────────────

    static String selectedOutputInitializer(String selectedBody, SemanticIndex.SemanticExtractVariable output) {
        String selected = selectedBody.strip();
        Pattern declaration = Pattern.compile(
                "\\A(?:final\\s+)?" + Pattern.quote(output.type()) + "\\s+" + Pattern.quote(output.name())
                        + "\\s*=\\s*(.+);\\z",
                Pattern.DOTALL);
        Matcher matcher = declaration.matcher(selected);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1).trim();
    }

    private static String renderRecordComponents(List<SemanticIndex.SemanticExtractVariable> outputs) {
        List<String> rendered = new ArrayList<>();
        for (SemanticIndex.SemanticExtractVariable output : outputs) {
            rendered.add(output.type() + " " + output.name());
        }
        return String.join(", ", rendered);
    }

    private static String renderArguments(List<SemanticIndex.SemanticExtractVariable> outputs) {
        List<String> rendered = new ArrayList<>();
        for (SemanticIndex.SemanticExtractVariable output : outputs) {
            rendered.add(output.name());
        }
        return String.join(", ", rendered);
    }

    /**
     * Renders a newly extracted member, separated from the previous member by the inferred blank lines.
     * {@link JavaStyleProfile#renderMethod} emits the brace block and one leading line ending (terminating the
     * previous member's closing-brace line); we prepend the inferred blank lines so spacing matches the type.
     */
    static String renderInsertedMember(JavaStyleProfile style, String header, String body) {
        String blankLines = style.lineEnding().repeat(Math.max(0, style.blankLinesBetweenMembers()));
        return blankLines + style.renderMethod(header, body);
    }

    /** A correct default value for {@code type} so the fall-through signal type-checks; the caller never reads it. */
    static String defaultValue(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "byte", "short", "int", "char" -> "0";
            case "long" -> "0L";
            case "float" -> "0.0f";
            case "double" -> "0.0";
            default -> "null";
        };
    }
}
