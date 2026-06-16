package io.serena.javarefactor.operations.extract_method;

import io.serena.javarefactor.compiler.SemanticIndex;
import java.util.List;

/**
 * Decides the extract-method output strategy from the data-flow facts the semantic layer computed.
 *
 * <p>The {@code javac}-backed {@link SemanticIndex} already classifies a selection's variables into
 * {@code inputs} (read inside, declared outside → method parameters) and {@code outputs} (assigned or
 * declared inside and read after the selection → values the caller still needs). This unit turns those
 * raw lists into a single {@link Strategy} the {@link MethodBodySynthesizer} can render:
 *
 * <ul>
 *   <li>{@link Strategy.Kind#VOID} — no outputs; the helper is {@code void}.</li>
 *   <li>{@link Strategy.Kind#SINGLE_OUTPUT} — exactly one output; the helper returns that value.</li>
 *   <li>{@link Strategy.Kind#MULTI_OUTPUT} — two or more outputs; the helper would return a synthesized
 *       record holder the caller destructures. This is V3 scope: the V2 planner always passes
 *       {@code allowMultipleOutputs=false}, so this strategy is unreachable from V2.</li>
 *   <li>{@link Strategy.Kind#REFUSED} — more than one output; the only outcome for multi-output selections
 *       in V2.</li>
 * </ul>
 *
 * <p>The analyzer holds no javac state of its own: it is a pure function of the precomputed facts, which
 * keeps it trivially unit-testable.
 */
final class DataFlowAnalyzer {

    private DataFlowAnalyzer() {}

    /** The chosen output strategy plus the data needed to render it. */
    record Strategy(Kind kind, List<SemanticIndex.SemanticExtractVariable> outputs, String refusalCode, String refusalMessage) {

        enum Kind {
            VOID,
            SINGLE_OUTPUT,
            MULTI_OUTPUT,
            REFUSED
        }

        static Strategy ofVoid() {
            return new Strategy(Kind.VOID, List.of(), null, null);
        }

        static Strategy single(SemanticIndex.SemanticExtractVariable output) {
            return new Strategy(Kind.SINGLE_OUTPUT, List.of(output), null, null);
        }

        static Strategy multi(List<SemanticIndex.SemanticExtractVariable> outputs) {
            return new Strategy(Kind.MULTI_OUTPUT, List.copyOf(outputs), null, null);
        }

        static Strategy refused(String code, String message) {
            return new Strategy(Kind.REFUSED, List.of(), code, message);
        }

        boolean isRefused() {
            return kind == Kind.REFUSED;
        }
    }

    /**
     * Classifies the output strategy for {@code outputs}. When more than one output exists the strategy is
     * {@link Strategy.Kind#MULTI_OUTPUT} only if {@code allowMultipleOutputs} is set; otherwise it is
     * {@link Strategy.Kind#REFUSED} with a precise reason that names the escaping variables.
     */
    static Strategy classify(List<SemanticIndex.SemanticExtractVariable> outputs, boolean allowMultipleOutputs) {
        if (outputs.isEmpty()) {
            return Strategy.ofVoid();
        }
        if (outputs.size() == 1) {
            return Strategy.single(outputs.get(0));
        }
        if (!allowMultipleOutputs) {
            return Strategy.refused(
                    "multiple_outputs_unsupported",
                    "V2 extract method supports a single output variable; multi-output extraction is reserved for a "
                            + "future V3 plan and is not available in V2. Selected statements write values used later: "
                            + variableNames(outputs) + ". Reduce the selection so at most one value is needed afterwards.");
        }
        return Strategy.multi(outputs);
    }

    /** A comma-separated rendering of the variable names, used in refusal diagnostics. */
    static String variableNames(List<SemanticIndex.SemanticExtractVariable> variables) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < variables.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(variables.get(i).name());
        }
        return builder.toString();
    }
}
