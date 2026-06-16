package io.serena.javarefactor.operations.extract_method;

import io.serena.javarefactor.compiler.SemanticIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit coverage for {@link DataFlowAnalyzer}'s output-strategy classification (pure, no compiler). */
class DataFlowAnalyzerTest {

    private static SemanticIndex.SemanticExtractVariable var(String type, String name) {
        return new SemanticIndex.SemanticExtractVariable(type, name, true);
    }

    @Test
    void noOutputsIsVoid() {
        assertEquals(DataFlowAnalyzer.Strategy.Kind.VOID, DataFlowAnalyzer.classify(List.of(), false).kind());
    }

    @Test
    void oneOutputIsSingle() {
        DataFlowAnalyzer.Strategy strategy = DataFlowAnalyzer.classify(List.of(var("int", "x")), false);
        assertEquals(DataFlowAnalyzer.Strategy.Kind.SINGLE_OUTPUT, strategy.kind());
        assertEquals("x", strategy.outputs().get(0).name());
    }

    @Test
    void multipleOutputsRefusedWhenPolicyDisabled() {
        DataFlowAnalyzer.Strategy strategy = DataFlowAnalyzer.classify(List.of(var("int", "x"), var("int", "y")), false);
        assertTrue(strategy.isRefused());
        assertEquals("multiple_outputs_unsupported", strategy.refusalCode());
        assertTrue(strategy.refusalMessage().contains("x, y"), strategy.refusalMessage());
    }

    @Test
    void multipleOutputsAllowedWhenPolicyEnabled() {
        DataFlowAnalyzer.Strategy strategy = DataFlowAnalyzer.classify(List.of(var("int", "x"), var("long", "y")), true);
        assertEquals(DataFlowAnalyzer.Strategy.Kind.MULTI_OUTPUT, strategy.kind());
        assertEquals(2, strategy.outputs().size());
    }
}
