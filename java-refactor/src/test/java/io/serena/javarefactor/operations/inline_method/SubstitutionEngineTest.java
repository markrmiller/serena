package io.serena.javarefactor.operations.inline_method;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the extracted {@link SubstitutionEngine} (G014): precedence-correct argument/receiver rendering and
 * AST-backed parameter substitution (scopes, captures, member-selects, target context). These exercise the substitution
 * unit in isolation from the integrated planner.
 */
class SubstitutionEngineTest {

    // ── Precedence-correct rendering ────────────────────────────────────────────────────────────────────────────────

    @Test
    void nonAtomicArgumentIsParenthesizedSoItKeepsItsMeaningInATighterOperatorContext() {
        // `a + b` inlined into the body template `value * 2` must become `(a + b) * 2`, not `a + b * 2`.
        String rendered = SubstitutionEngine.renderArgumentSubstitution("a + b");
        assertEquals("(a + b)", rendered);
        assertEquals("(a + b) * 2", SubstitutionEngine.replaceIdentifiers("value * 2", Map.of("value", rendered)));
    }

    @Test
    void atomicArgumentIsSplicedWithoutRedundantParentheses() {
        // An identifier already binds tighter than any operator, so no parentheses are added.
        String rendered = SubstitutionEngine.renderArgumentSubstitution("x");
        assertEquals("x", rendered);
        assertEquals("x * 2", SubstitutionEngine.replaceIdentifiers("value * 2", Map.of("value", rendered)));
    }

    @Test
    void alreadyParenthesizedArgumentIsNotDoubleWrapped() {
        assertEquals("(a + b)", SubstitutionEngine.renderArgumentSubstitution("(a + b)"));
    }

    @Test
    void thisReceiverSubstitutionRewritesThisMemberAccessToTheCallSiteReceiver() {
        // The body template `this.value + 1` substituted with an explicit receiver `other` yields `other.value + 1`,
        // and a non-atomic receiver is parenthesized before substitution.
        assertEquals("other", SubstitutionEngine.renderReceiverSubstitution("other"));
        assertEquals(
                "other.value + 1",
                SubstitutionEngine.replaceIdentifiers(
                        "this.value + 1", Map.of("this", SubstitutionEngine.renderReceiverSubstitution("other"))));
    }

    // ── AST-backed substitution ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void astSubstitutionReplacesEveryParameterReference() {
        assertEquals(Optional.of("4 + 4"),
                SubstitutionEngine.substituteByAst("value + value", Map.of("value", "4")));
    }

    @Test
    void astSubstitutionLeavesAMemberSelectMemberNameUntouched() {
        // `obj.value` reads a member of `obj`; only the standalone `value` reference is the inlined parameter.
        assertEquals(Optional.of("obj.value + 4"),
                SubstitutionEngine.substituteByAst("obj.value + value", Map.of("value", "4")));
    }

    @Test
    void astSubstitutionRewritesAParameterUsedAsACallArgument() {
        // The void-statement shape `sink(value)` must substitute the argument identifier while leaving the call target.
        assertEquals(Optional.of("sink(\"hi\")"),
                SubstitutionEngine.substituteByAst("sink(value)", Map.of("value", "\"hi\"")));
    }

    @Test
    void astSubstitutionDoesNotTouchAnIdentifierShadowedByALambdaParameter() {
        // The outer `value` is the inlined parameter; the lambda declares its own `value`, so the captured occurrence
        // keeps its own binding and must not be substituted.
        assertEquals(
                Optional.of("4 + stream.map(value -> value + 1)"),
                SubstitutionEngine.substituteByAst("value + stream.map(value -> value + 1)", Map.of("value", "4")));
    }

    @Test
    void astSubstitutionDefersToFallbackWhenBodyDeclaresANestedClass() {
        // A nested/anonymous class introduces member shadowing this parser cannot resolve, so substitution returns empty
        // and the caller falls back to the conservative text scanner rather than emit a possibly-wrong edit.
        assertEquals(
                Optional.empty(),
                SubstitutionEngine.substituteByAst("compute(new Object() { int v = value; })", Map.of("value", "4")));
    }

    @Test
    void astSubstitutionWithNoReplacementsReturnsTheBodyUnchanged() {
        assertEquals(Optional.of("a + b"), SubstitutionEngine.substituteByAst("a + b", Map.of()));
    }
}
