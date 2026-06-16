package io.serena.javarefactor.operations.inline_method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.shared.ExpressionPurityAnalyzer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the extracted {@link EvaluationOrderGuard} (G014): the conservative reorder-safety fallback (no
 * resolved context reachable) and standalone-identifier counting that drives the duplicate-evaluation gate.
 */
class EvaluationOrderGuardTest {

    private static final ExpressionPurityAnalyzer PURITY = new ExpressionPurityAnalyzer();

    private static EvaluationOrderGuard guard() {
        // No range is supplied in these tests, so the model is never dereferenced (it is only used to read a call-site
        // file for attribution, which the conservative fallback path skips).
        JavaProjectModel model = new JavaProjectModel(
                Path.of("."), "test", List.of(), List.of(), List.of(), List.of(), false, false, List.of());
        return new EvaluationOrderGuard(model, PURITY);
    }

    @Test
    void conservativeFallbackTreatsAPureExpressionAsReorderSafe() {
        // With no resolved context (null range), a structurally pure expression is the most that can be greenlit.
        assertTrue(guard().reorderSafe(Path.of("Sample.java"), null, "a + b"));
    }

    @Test
    void conservativeFallbackRefusesAnEffectfulExpression() {
        // A side-effecting expression is not structurally pure, so the conservative fallback withholds the green-light.
        assertFalse(guard().reorderSafe(Path.of("Sample.java"), null, "next()"));
        assertFalse(guard().reorderSafe(Path.of("Sample.java"), null, "++n"));
    }

    @Test
    void parameterUseCountsCountsStandaloneIdentifierReferencesOnly() {
        // `value` appears twice as a standalone reference; the `obj.value` member-select occurrence is NOT a parameter
        // reference and must not be counted.
        Map<String, Integer> counts =
                guard().parameterUseCounts("value + obj.value + value", List.of("value"));
        assertEquals(2, counts.get("value"));
    }

    @Test
    void parameterUseCountsIgnoresOccurrencesInStringLiteralsAndComments() {
        Map<String, Integer> counts = guard().parameterUseCounts(
                "value /* value */ + \"value\" + value", List.of("value"));
        assertEquals(2, counts.get("value"));
    }
}
