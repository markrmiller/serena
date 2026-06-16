package io.serena.javarefactor.operations.extract_method;

import io.serena.javarefactor.compiler.SemanticIndex;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the style-aware member-insertion offset (G023): {@link ExtractMethodPlanner#memberInsertionOffset} inserts a
 * newly extracted method immediately after the enclosing method by default, but appends it at the end of the type body
 * when the type groups its private helpers below the public callers (so the extracted helper joins the helper group at
 * the bottom rather than being wedged between two callers).
 */
class ExtractMethodInsertionOffsetTest {

    @Test
    void defaultsToAfterEnclosingMethodWhenItIsTheLastMember() {
        String source = "class C {\n    void a() {\n    }\n}\n";
        int afterA = source.indexOf('}', source.indexOf("void a()")) + 1;

        assertEquals(afterA, ExtractMethodPlanner.memberInsertionOffset(source, afterA, typeBody(source)));
    }

    @Test
    void keepsAfterEnclosingMethodWhenTrailingMembersAreNotPrivateHelpers() {
        // The only trailing member is a public method => not a helpers-below-callers grouping => keep default.
        String source = "class C {\n    void a() {\n    }\n\n    public void b() {\n    }\n}\n";
        int afterA = source.indexOf('}', source.indexOf("void a()")) + 1;

        assertEquals(afterA, ExtractMethodPlanner.memberInsertionOffset(source, afterA, typeBody(source)));
    }

    @Test
    void appendsAtEndOfBodyWhenAPrivateHelperIsGroupedBelowTheEnclosingMethod() {
        String source = "class C {\n    void a() {\n    }\n\n    private void helper() {\n    }\n}\n";
        int afterA = source.indexOf('}', source.indexOf("void a()")) + 1;

        int offset = ExtractMethodPlanner.memberInsertionOffset(source, afterA, typeBody(source));

        // Diverted past the trailing helper: the only thing left after the insertion point is the type's closing brace.
        assertTrue(offset > afterA, "expected end-of-body placement, not after-method; offset=" + offset);
        assertEquals("}", source.substring(offset).strip(), "insertion point must be just before the type's closing brace");
    }

    @Test
    void toleratesNullTypeBodyRange() {
        String source = "class C {\n    void a() {\n    }\n}\n";
        int afterA = source.indexOf('}', source.indexOf("void a()")) + 1;

        assertEquals(afterA, ExtractMethodPlanner.memberInsertionOffset(source, afterA, null));
    }

    private static SemanticIndex.SourceRange typeBody(String source) {
        return new SemanticIndex.SourceRange(Path.of("C.java"), source.indexOf('{'), source.lastIndexOf('}') + 1);
    }
}
