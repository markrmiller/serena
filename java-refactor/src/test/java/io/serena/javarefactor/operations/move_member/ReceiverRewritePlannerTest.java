package io.serena.javarefactor.operations.move_member;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the G008 receiver-rewrite primitives in {@link ReceiverRewritePlanner}, proving the AST/source-position
 * compound-receiver rewrite drops only genuine qualifier spans and REFUSES (never raw text-replaces) when the moved body
 * cannot be parsed in isolation.
 */
class ReceiverRewritePlannerTest {

    /** G008 req 2: a compound receiver qualifier is dropped by source position; comment/string occurrences are kept. */
    @Test
    void compoundReceiverRewriteDropsOnlyResolvedQualifier() throws MoveMemberPlanner.Refusal {
        String member = ""
                + "public int compute(int amount, Object raw) {\n"
                + "    // ((Target) raw).note kept\n"
                + "    String s = \"((Target) raw).inString\";\n"
                + "    return ((Target) raw).scale(amount);\n"
                + "}";

        String rewritten = ReceiverRewritePlanner.rewriteCompoundReceiverBody(member, "(Target) raw");

        assertTrue(rewritten.contains("return scale(amount);"), rewritten);
        assertTrue(rewritten.contains("((Target) raw).note kept"), rewritten);
        assertTrue(rewritten.contains("((Target) raw).inString"), rewritten);
    }

    /** G008 req 2: an unparseable body refuses with {@code body_rewrite_unparseable} rather than a corrupting text replace. */
    @Test
    void compoundReceiverRewriteRefusesWhenPositionsUnresolvable() {
        String notAMethod = "this is not a parseable Java method (Target) raw .scale(";

        MoveMemberPlanner.Refusal refusal = assertThrows(
                MoveMemberPlanner.Refusal.class,
                () -> ReceiverRewritePlanner.rewriteCompoundReceiverBody(notAMethod, "(Target) raw"));
        assertEquals("body_rewrite_unparseable", refusal.code());
    }

    /** A simple-chain argument is used verbatim; a compound argument is parenthesized for safe receiver binding. */
    @Test
    void asReceiverParenthesizesCompoundExpressions() {
        assertEquals("a.b.c", ReceiverRewritePlanner.asReceiver("a.b.c"));
        assertEquals("((Target) raw)", ReceiverRewritePlanner.asReceiver("(Target) raw"));
    }
}
