package io.serena.javarefactor.operations.extract_method;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.shared.JavaStyleProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link MethodBodySynthesizer}: the four rendered shapes (void, single-output, multi-output
 * record holder, control-flow signal) and the brace-aware jump rewriting that drives the control-flow body. Pure
 * string synthesis — no compiler.
 */
class MethodBodySynthesizerTest {

    private static final JavaStyleProfile STYLE = new JavaStyleProfile("\n", "    ", "    ", false, 1, false, true, true);

    private static MethodBodySynthesizer.Context ctx(String body) {
        // The planner now supplies the scope-checked unique holder/type names; this pure synthesizer test pins them to
        // the historical defaults so the rendered output is unchanged when no collision exists.
        return new MethodBodySynthesizer.Context(
                STYLE, "helper", "private", "", "int a", "a", "", body,
                "HelperResult", "result", "HelperSignal", "signal");
    }

    private static SemanticIndex.SemanticExtractVariable out(String type, String name, boolean declaredInside) {
        return new SemanticIndex.SemanticExtractVariable(type, name, declaredInside);
    }

    @Test
    void voidExtractionHasNoResultTypeAndCallsTheHelper() {
        MethodBodySynthesizer.Synthesis s = MethodBodySynthesizer.voidExtraction(ctx("sink(a);"));
        assertEquals("helper(a);", s.callText());
        assertTrue(s.methodText().contains("private void helper(int a) {"), s.methodText());
        assertNull(s.resultTypeText());
    }

    @Test
    void singleOutputDeclaredInsideReturnsInitializerExpression() {
        MethodBodySynthesizer.Synthesis s = MethodBodySynthesizer.singleOutput(ctx("int r = a + 1;"), out("int", "r", true));
        assertEquals("int r = helper(a);", s.callText());
        assertTrue(s.methodText().contains("private int helper(int a) {"), s.methodText());
        assertTrue(s.methodText().contains("return a + 1;"), s.methodText());
    }

    @Test
    void singleOutputPreDeclaredReassignsAndAppendsReturn() {
        MethodBodySynthesizer.Synthesis s = MethodBodySynthesizer.singleOutput(ctx("r = a + 1;"), out("int", "r", false));
        assertEquals("r = helper(a);", s.callText());
        assertTrue(s.methodText().contains("r = a + 1;"), s.methodText());
        assertTrue(s.methodText().contains("return r;"), s.methodText());
    }

    @Test
    void multiOutputReturnsRecordHolderAndDestructuresAtCall() {
        MethodBodySynthesizer.Synthesis s = MethodBodySynthesizer.multiOutput(
                ctx("int x = a;\nlong y = a * 2L;"), List.of(out("int", "x", true), out("long", "y", true)));
        assertTrue(s.methodText().contains("private HelperResult helper(int a) {"), s.methodText());
        assertTrue(s.methodText().contains("return new HelperResult(x, y);"), s.methodText());
        assertNotNull(s.resultTypeText());
        assertTrue(s.resultTypeText().contains("private record HelperResult(int x, long y)"), s.resultTypeText());
        assertTrue(s.callText().contains("HelperResult result = helper(a);"), s.callText());
        assertTrue(s.callText().contains("int x = result.x();"), s.callText());
        assertTrue(s.callText().contains("long y = result.y();"), s.callText());
    }

    @Test
    void controlFlowVoidReturnUsesBooleanSignalAndGuardsReturn() {
        ControlFlowAnalyzer.ControlFlow flow = new ControlFlowAnalyzer.ControlFlow(ControlFlowAnalyzer.ExitKind.RETURN_VOID, null);
        MethodBodySynthesizer.Synthesis s = MethodBodySynthesizer.controlFlow(ctx("if (a < 0) {\n    return;\n}"), flow);
        assertEquals("if (helper(a)) return;", s.callText());
        assertTrue(s.methodText().contains("private boolean helper(int a) {"), s.methodText());
        assertTrue(s.methodText().contains("return true;"), s.methodText());
        assertTrue(s.methodText().contains("return false;"), s.methodText());
        assertNull(s.resultTypeText());
    }

    @Test
    void controlFlowBreakAndContinueGuardTheMatchingKeyword() {
        MethodBodySynthesizer.Synthesis brk = MethodBodySynthesizer.controlFlow(
                ctx("if (a < 0) {\n    break;\n}"),
                new ControlFlowAnalyzer.ControlFlow(ControlFlowAnalyzer.ExitKind.BREAK, null));
        assertEquals("if (helper(a)) break;", brk.callText());

        MethodBodySynthesizer.Synthesis cont = MethodBodySynthesizer.controlFlow(
                ctx("if (a < 0) {\n    continue;\n}"),
                new ControlFlowAnalyzer.ControlFlow(ControlFlowAnalyzer.ExitKind.CONTINUE, null));
        assertEquals("if (helper(a)) continue;", cont.callText());
    }

    @Test
    void controlFlowValueReturnUsesSignalRecord() {
        ControlFlowAnalyzer.ControlFlow flow = new ControlFlowAnalyzer.ControlFlow(ControlFlowAnalyzer.ExitKind.RETURN_VALUE, "Money");
        MethodBodySynthesizer.Synthesis s = MethodBodySynthesizer.controlFlow(ctx("if (a < 0) {\n    return total;\n}"), flow);
        assertTrue(s.methodText().contains("private HelperSignal helper(int a) {"), s.methodText());
        assertTrue(s.methodText().contains("return new HelperSignal(true, total);"), s.methodText());
        assertTrue(s.methodText().contains("return new HelperSignal(false, null);"), s.methodText());
        assertNotNull(s.resultTypeText());
        assertTrue(s.resultTypeText().contains("private record HelperSignal(boolean returned, Money value)"), s.resultTypeText());
        assertTrue(s.callText().contains("HelperSignal signal = helper(a);"), s.callText());
        assertTrue(s.callText().contains("if (signal.returned()) return signal.value();"), s.callText());
    }

    @Test
    void valueReturnDefaultMatchesPrimitiveType() {
        ControlFlowAnalyzer.ControlFlow flow = new ControlFlowAnalyzer.ControlFlow(ControlFlowAnalyzer.ExitKind.RETURN_VALUE, "int");
        MethodBodySynthesizer.Synthesis s = MethodBodySynthesizer.controlFlow(ctx("if (a < 0) {\n    return 7;\n}"), flow);
        assertTrue(s.methodText().contains("return new HelperSignal(false, 0);"), s.methodText());
    }

    @Test
    void rewriteJumpsLeavesNestedLoopBreakUntouched() {
        // The inner break targets the wholly-contained for-loop and must NOT be rewritten; only the trailing
        // non-local break is converted.
        String body = "for (int i = 0; i < n; i++) {\n    if (a[i] == k) {\n        break;\n    }\n}\nif (miss) {\n    break;\n}";
        String rewritten = MethodBodySynthesizer.rewriteJumps(body, "return true;");
        assertTrue(rewritten.contains("if (a[i] == k) {\n        break;\n    }"), rewritten);
        assertTrue(rewritten.contains("if (miss) {\n    return true;\n}"), rewritten);
        assertFalse(rewritten.contains("if (miss) {\n    break;\n}"), rewritten);
    }
}
