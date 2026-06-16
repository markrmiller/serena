package io.serena.javarefactor.operations.extract_method;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the planner-local {@link ControlFlowAnalyzer}: it must classify the uniform kind of
 * non-local exit a selection contains, treat jumps captured by an in-selection loop/switch as local (not an
 * exit), read the enclosing return type for value returns, and report {@code UNSUPPORTED} for the cases the
 * synthesizer cannot handle soundly (labeled jumps, mixed kinds). These tests need no compiler — the analyzer
 * works purely on selected source text plus the collapsed javac boolean.
 */
class ControlFlowAnalyzerTest {

    @Test
    void shortcutsToNoneWhenJavacReportsNoExit() {
        ControlFlowAnalyzer.ControlFlow flow = ControlFlowAnalyzer.analyze("int x = 1;\nuse(x);", false, "void m()");
        assertEquals(ControlFlowAnalyzer.ExitKind.NONE, flow.kind());
    }

    @Test
    void classifiesBareReturnAsVoidReturn() {
        ControlFlowAnalyzer.ControlFlow flow = ControlFlowAnalyzer.analyze("if (x) {\n    return;\n}", true, "void m()");
        assertEquals(ControlFlowAnalyzer.ExitKind.RETURN_VOID, flow.kind());
    }

    @Test
    void classifiesValueReturnAndReadsEnclosingReturnType() {
        ControlFlowAnalyzer.ControlFlow flow = ControlFlowAnalyzer.analyze(
                "if (x < 0) {\n    return total;\n}", true, "    private Money compute(Order o) throws X ");
        assertEquals(ControlFlowAnalyzer.ExitKind.RETURN_VALUE, flow.kind());
        assertEquals("Money", flow.returnType());
    }

    @Test
    void classifiesBreakAndContinue() {
        assertEquals(ControlFlowAnalyzer.ExitKind.BREAK,
                ControlFlowAnalyzer.analyze("if (x) {\n    break;\n}", true, "void m()").kind());
        assertEquals(ControlFlowAnalyzer.ExitKind.CONTINUE,
                ControlFlowAnalyzer.analyze("if (x) {\n    continue;\n}", true, "void m()").kind());
    }

    @Test
    void treatsBreakInsideSelectedLoopAsLocalNotAnExit() {
        // The break targets the for-loop that is wholly inside the selection: it is local, so there is no exit.
        String selected = "for (int i = 0; i < n; i++) {\n    if (a[i] == k) {\n        break;\n    }\n}";
        ControlFlowAnalyzer.ControlFlow flow = ControlFlowAnalyzer.analyze(selected, true, "void m()");
        assertEquals(ControlFlowAnalyzer.ExitKind.NONE, flow.kind());
        assertTrue(ControlFlowAnalyzer.nonLocalJumps(selected).isEmpty(), "break captured by in-selection loop is local");
    }

    @Test
    void treatsContinueInsideSelectedLoopAsLocalNotAnExit() {
        String selected = "while (cond) {\n    if (skip) {\n        continue;\n    }\n    work();\n}";
        assertEquals(ControlFlowAnalyzer.ExitKind.NONE, ControlFlowAnalyzer.analyze(selected, true, "void m()").kind());
    }

    @Test
    void breakInsideSelectedSwitchIsLocalButContinueStillEscapesTheLoop() {
        // A break inside a wholly-selected switch is local; but a continue in the same switch escapes to the
        // (out-of-selection) loop, so the selection has a non-local continue exit.
        String selected = "switch (kind) {\n    case 1:\n        break;\n    default:\n        continue;\n}";
        ControlFlowAnalyzer.ControlFlow flow = ControlFlowAnalyzer.analyze(selected, true, "void m()");
        assertEquals(ControlFlowAnalyzer.ExitKind.CONTINUE, flow.kind());
    }

    @Test
    void refusesLabeledJump() {
        ControlFlowAnalyzer.ControlFlow flow = ControlFlowAnalyzer.analyze("if (x) {\n    break outer;\n}", true, "void m()");
        assertEquals(ControlFlowAnalyzer.ExitKind.UNSUPPORTED, flow.kind());
    }

    @Test
    void refusesMixedExitKinds() {
        String selected = "if (a) {\n    return;\n}\nif (b) {\n    break;\n}";
        assertEquals(ControlFlowAnalyzer.ExitKind.UNSUPPORTED, ControlFlowAnalyzer.analyze(selected, true, "void m()").kind());
    }

    @Test
    void ignoresKeywordsInsideStringLiteralsAndComments() {
        String selected = "log(\"return now\"); // continue here\nint x = 1;";
        assertEquals(ControlFlowAnalyzer.ExitKind.NONE, ControlFlowAnalyzer.analyze(selected, true, "void m()").kind());
    }

    @Test
    void readsGenericReturnTypeAtTopLevel() {
        assertEquals("Map<String, Integer>",
                ControlFlowAnalyzer.returnTypeOf("    private Map<String, Integer> build(int n) "));
    }

    @Test
    void nonLocalJumpsExposeOffsetsAndValuesForRewriting() {
        String selected = "if (x < 0) {\n    return total;\n}";
        List<ControlFlowAnalyzer.Jump> jumps = ControlFlowAnalyzer.nonLocalJumps(selected);
        assertEquals(1, jumps.size());
        assertEquals(ControlFlowAnalyzer.ExitKind.RETURN_VALUE, jumps.get(0).kind());
        assertEquals("total", jumps.get(0).value());
        assertEquals("return total;", selected.substring(jumps.get(0).start(), jumps.get(0).end()));
    }
}
