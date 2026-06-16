package io.serena.javarefactor.operations.extract_method;

import io.serena.javarefactor.shared.JavaStyleProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link MethodBodySynthesizer#renderInsertedMember} places an extracted method as a
 * proper class member: it honors the inferred blank-line spacing between members and the file's
 * line ending (LF vs CRLF), rather than appending the new method directly behind the previous
 * member's closing brace.
 */
class ExtractMethodInsertionStyleTest {

    private static final String HEADER = "private int calculateTotal(int base)";
    private static final String BODY = "return base + 1;";

    @Test
    void insertsOneBlankLineBetweenMembersForLfSource() {
        JavaStyleProfile style = JavaStyleProfile.infer(
                "class Sample {\n    void a() {\n    }\n\n    void b() {\n    }\n}\n");

        String rendered = render(style);

        // One inferred blank line (separator) + renderMethod's own leading line ending => two LFs
        // before the indented member header.
        assertTrue(rendered.startsWith("\n\n    " + HEADER + " {"),
                "expected a blank-line-separated member, got: " + debug(rendered));
        assertTrue(rendered.contains("\n        " + BODY), "body is indented one level deeper: " + debug(rendered));
    }

    @Test
    void preservesCrlfAndTabStyleForCrlfSource() {
        JavaStyleProfile style = JavaStyleProfile.infer(
                "class Sample {\r\n\tvoid a() {\r\n\t}\r\n\r\n\tvoid b() {\r\n\t}\r\n}\r\n");

        String rendered = render(style);

        assertTrue(rendered.startsWith("\r\n\r\n\t" + HEADER + " {"),
                "expected CRLF blank-line-separated tab-indented member, got: " + debug(rendered));
        assertTrue(rendered.contains("\r\n\t\t" + BODY), "body uses CRLF + double tab: " + debug(rendered));
        assertFalse(rendered.contains("\n\n\n"), "no stray bare-LF runs in CRLF output: " + debug(rendered));
    }

    @Test
    void honorsZeroBlankLineProfile() {
        // Directly constructed profile with no blank line between members: the helper must emit a
        // single line ending (renderMethod's own), not an extra blank line.
        JavaStyleProfile style = new JavaStyleProfile("\n", "    ", "    ", false, 0, false, true, true);

        String rendered = render(style);

        assertTrue(rendered.startsWith("\n    " + HEADER + " {"), "single separator line: " + debug(rendered));
        assertFalse(rendered.startsWith("\n\n"), "must not add a blank line when none are inferred: " + debug(rendered));
    }

    private static String render(JavaStyleProfile style) {
        return MethodBodySynthesizer.renderInsertedMember(style, HEADER, BODY);
    }

    private static String debug(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
