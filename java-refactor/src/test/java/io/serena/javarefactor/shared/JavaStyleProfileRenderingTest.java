package io.serena.javarefactor.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G001 coverage: the generated-member renderers must apply the inferred {@link JavaStyleProfile} (brace style, line
 * endings, indentation unit, final-parameter style, annotation placement) instead of hard-coding K&R braces and LF.
 *
 * <p>Each test infers a profile from a representative source and asserts that {@code renderMethod} /
 * {@code renderInterfaceSource} / {@code renderAnnotatedMethod} honor it.
 */
class JavaStyleProfileRenderingTest {

    private static final String ALLMAN_SOURCE = ""
            + "package demo;\n"
            + "public class Sample\n"
            + "{\n"
            + "    int count;\n"
            + "    void run()\n"
            + "    {\n"
            + "        int x = 1;\n"
            + "    }\n"
            + "}\n";

    private static final String KR_SOURCE = ""
            + "package demo;\n"
            + "public class Sample {\n"
            + "    int count;\n"
            + "    void run() {\n"
            + "        int x = 1;\n"
            + "    }\n"
            + "}\n";

    @Test
    void renderMethodPutsBraceOnSameLineForKrStyle() {
        JavaStyleProfile style = JavaStyleProfile.infer(KR_SOURCE);
        assertTrue(style.openBraceSameLine());
        String method = style.renderMethod("private int addUp(int a, int b)", "return a + b;");
        assertTrue(method.contains("addUp(int a, int b) {"), method);
    }

    @Test
    void renderMethodPutsBraceOnOwnLineForAllmanStyle() {
        JavaStyleProfile style = JavaStyleProfile.infer(ALLMAN_SOURCE);
        assertFalse(style.openBraceSameLine());
        String method = style.renderMethod("private int addUp(int a, int b)", "return a + b;");
        // The opening brace is on its own line at member indentation, never appended to the header line.
        assertFalse(method.contains("addUp(int a, int b) {"), method);
        assertTrue(method.contains("addUp(int a, int b)\n    {"), method);
    }

    @Test
    void renderMethodPreservesCrlfLineEndings() {
        JavaStyleProfile style = JavaStyleProfile.infer(KR_SOURCE.replace("\n", "\r\n"));
        String method = style.renderMethod("private void f()", "g();");
        assertTrue(method.contains("\r\n"), "expected CRLF line endings");
        assertFalse(method.contains("\n\r"), "line endings must not be corrupted");
        // No bare LF that is not part of a CRLF pair.
        assertFalse(method.replace("\r\n", "").contains("\n"), method);
    }

    @Test
    void renderMethodUsesTabIndentation() {
        String tabSource = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "\tint count;\n"
                + "\tvoid run() {\n"
                + "\t\tint x = 1;\n"
                + "\t}\n"
                + "}\n";
        JavaStyleProfile style = JavaStyleProfile.infer(tabSource);
        String method = style.renderMethod("private void f()", "g();");
        assertTrue(method.contains("\tprivate void f()"), method);
        assertTrue(method.contains("\t\tg();"), method);
    }

    @Test
    void interfaceSourceHonorsAllmanBrace() {
        JavaStyleProfile style = JavaStyleProfile.infer(ALLMAN_SOURCE);
        String iface = style.renderInterfaceSource("demo", "Greeter", List.of(), List.of("    String greet();"));
        assertFalse(iface.contains("interface Greeter {"), iface);
        assertTrue(iface.contains("interface Greeter\n{"), iface);
    }

    @Test
    void annotatedMethodHonorsSameLineAnnotationPlacement() {
        String sameLineAnnotations = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    void run() { g(); }\n"
                + "    @Deprecated public void done() {}\n"
                + "}\n";
        JavaStyleProfile style = JavaStyleProfile.infer(sameLineAnnotations);
        assertFalse(style.annotationsOnOwnLine());
        String method = style.renderAnnotatedMethod(List.of("@Override"), "public String toString()", "return \"x\";");
        // Same-line placement keeps the annotation on the header line, not on a separate line above it.
        assertTrue(method.contains("@Override public String toString()"), method);
    }

    @Test
    void annotatedMethodHonorsOwnLineAnnotationPlacement() {
        JavaStyleProfile style = JavaStyleProfile.infer(KR_SOURCE);
        assertTrue(style.annotationsOnOwnLine());
        String method = style.renderAnnotatedMethod(List.of("@Override"), "public String toString()", "return \"x\";");
        assertTrue(method.contains("@Override\n"), method);
        assertFalse(method.contains("@Override public String toString()"), method);
    }
}
