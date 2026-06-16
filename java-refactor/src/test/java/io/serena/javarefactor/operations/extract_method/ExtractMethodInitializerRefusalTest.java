package io.serena.javarefactor.operations.extract_method;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the V2 extract-method planner's handling of selections in class-initializer / field-initializer scopes (G012).
 *
 * <p>The design supports extraction of a selection within a single method, constructor, OR initializer. This suite proves
 * the initializer forms: a COMPLETE STATEMENT or a self-contained EXPRESSION inside a STATIC initializer block, inside an
 * INSTANCE initializer block, and inside a FIELD initializer. For each form the synthesized helper carries the correct
 * static fact (static block/field -> static helper; instance block/field -> instance helper), its data-flow inputs become
 * parameters, and it is inserted into the enclosing TYPE body (initializer scopes have no enclosing method to host the
 * helper). Forms that genuinely cannot be transformed are refused with a precise structured code after the initializer
 * form is analyzed -- never a blanket {@code initializer_extraction_unsupported} stand-in for "not yet implemented".
 */
class ExtractMethodInitializerRefusalTest {

    // ---------------------------------------------------------------------------------------------------------------
    // Static initializer block
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void extractsStatementFromStaticInitializerBlockAsStaticHelper(@TempDir Path tmp) throws IOException {
        // A COMPLETE statement selected inside a STATIC initializer block is extractable: the helper is static, inserted
        // into the enclosing TYPE body, and the statement is replaced with a call. Inputs/outputs are empty here.
        Path source = write(tmp, "InitSample.java", ""
                + "public class InitSample {\n"
                + "    static int value;\n"
                + "    static {\n"
                + "        value = compute(1, 2);\n"
                + "    }\n"
                + "    static int compute(int a, int b) { return a + b; }\n"
                + "}\n");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        String json = extract(tmp, source, "computeValue", selectionFor(text, "value = compute(1, 2);"));

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("initializer_extraction_unsupported"), json);
        assertTrue(json.contains("private static void computeValue("), json);
        assertTrue(json.contains("computeValue();"), json);
    }

    @Test
    void extractsStaticInitializerStatementWithLocalInputAsStaticHelper(@TempDir Path tmp) throws IOException {
        // A local declared earlier in the static block and read by the selection becomes a parameter (input) of the
        // extracted static helper, threaded as an argument at the call.
        Path source = write(tmp, "InitInputs.java", ""
                + "public class InitInputs {\n"
                + "    static int value;\n"
                + "    static {\n"
                + "        int base = 10;\n"
                + "        value = base + helper(base);\n"
                + "    }\n"
                + "    static int helper(int n) { return n; }\n"
                + "}\n");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        String json = extract(tmp, source, "assignValue", selectionFor(text, "value = base + helper(base);"));

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("private static void assignValue(int base)"), json);
        assertTrue(json.contains("assignValue(base);"), json);
    }

    @Test
    void extractsExpressionFromStaticInitializerBlockAsStaticHelper(@TempDir Path tmp) throws IOException {
        // A self-contained EXPRESSION sub-selection inside a static initializer is now extractable: it resolves to a
        // method-call AST node, so the helper returns its value and the call replaces the expression in place.
        Path source = write(tmp, "InitSample.java", ""
                + "public class InitSample {\n"
                + "    static int value;\n"
                + "    static {\n"
                + "        value = compute(1, 2);\n"
                + "    }\n"
                + "    static int compute(int a, int b) { return a + b; }\n"
                + "}\n");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        String json = extract(tmp, source, "computeValue", selectionFor(text, "compute(1, 2)"));

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("initializer_extraction_unsupported"), json);
        assertTrue(json.contains("private static int computeValue("), json);
        assertTrue(json.contains("return compute(1, 2);"), json);
        assertTrue(json.contains("computeValue()"), json);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Instance initializer block
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void extractsStatementFromInstanceInitializerBlockAsInstanceHelper(@TempDir Path tmp) throws IOException {
        // A COMPLETE statement selected inside an INSTANCE initializer block extracts to an INSTANCE helper (no static
        // modifier), inserted into the enclosing type body; the statement is replaced with a call. Replacing whole
        // statements with a call preserves the instance-init evaluation order.
        Path source = write(tmp, "InstanceInit.java", ""
                + "public class InstanceInit {\n"
                + "    int value;\n"
                + "    {\n"
                + "        value = compute(1, 2);\n"
                + "    }\n"
                + "    int compute(int a, int b) { return a + b; }\n"
                + "}\n");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        String json = extract(tmp, source, "assignValue", selectionFor(text, "value = compute(1, 2);"));

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("initializer_extraction_unsupported"), json);
        assertTrue(json.contains("private void assignValue("), json);
        assertFalse(json.contains("private static void assignValue("), json);
        assertTrue(json.contains("assignValue();"), json);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Field initializer
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void extractsStaticFieldInitializerExpressionAsStaticHelper(@TempDir Path tmp) throws IOException {
        // A self-contained expression in a STATIC field initializer extracts to a static helper inserted into the type
        // body; the field initializer is rewritten to call it.
        Path source = write(tmp, "FieldInit.java", ""
                + "public class FieldInit {\n"
                + "    static int value = compute(1, 2);\n"
                + "    static int compute(int a, int b) { return a + b; }\n"
                + "}\n");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        String json = extract(tmp, source, "computeValue", selectionFor(text, "compute(1, 2)"));

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("initializer_extraction_unsupported"), json);
        assertTrue(json.contains("private static int computeValue("), json);
        assertTrue(json.contains("return compute(1, 2);"), json);
        assertTrue(json.contains("computeValue()"), json);
    }

    @Test
    void extractsInstanceFieldInitializerExpressionAsInstanceHelper(@TempDir Path tmp) throws IOException {
        // A self-contained expression in an INSTANCE field initializer extracts to an INSTANCE helper (no static
        // modifier) inserted into the type body; the field initializer is rewritten to call it.
        Path source = write(tmp, "FieldInit.java", ""
                + "public class FieldInit {\n"
                + "    int value = compute(1, 2);\n"
                + "    int compute(int a, int b) { return a + b; }\n"
                + "}\n");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        String json = extract(tmp, source, "computeValue", selectionFor(text, "compute(1, 2)"));

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("initializer_extraction_unsupported"), json);
        assertTrue(json.contains("private int computeValue("), json);
        assertFalse(json.contains("private static int computeValue("), json);
        assertTrue(json.contains("return compute(1, 2);"), json);
        assertTrue(json.contains("computeValue()"), json);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Genuinely-impossible cases: precise refusals (never green-lit, never a blanket "unsupported" placeholder)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void refusesPartialExpressionSelectionInsideFieldInitializer(@TempDir Path tmp) throws IOException {
        // A selection that cuts the initializer expression mid-operand does not resolve to a complete AST node, so it is
        // refused as not extractable -- a precise reason, not a blanket initializer placeholder.
        Path source = write(tmp, "PartialField.java", ""
                + "public class PartialField {\n"
                + "    int value = 1 + 2 + 3;\n"
                + "}\n");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        // Select "+ 2 +" which is not a self-contained expression node.
        String json = extract(tmp, source, "computeValue", selectionFor(text, "+ 2 +"));

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"selection_not_extractable\""), json);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private static String extract(Path tmp, Path source, String newMethodName, Map<String, Object> selection)
            throws IOException {
        Path sourceRoot = source.getParent();
        JavaProjectModel model = model(tmp, sourceRoot, List.of(source));
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", tmp.relativize(source).toString().replace('\\', '/'));
        fields.put("newMethodName", newMethodName);
        fields.put("selection", selection);
        return new ExtractMethodPlanner(tmp.toAbsolutePath().normalize(), model).extractMethod(fields, false);
    }

    private static Path write(Path tmp, String name, String text) throws IOException {
        Path sourceRoot = tmp.resolve("src");
        Files.createDirectories(sourceRoot);
        Path source = sourceRoot.resolve(name);
        Files.writeString(source, text, StandardCharsets.UTF_8);
        return source;
    }

    private static Map<String, Object> selectionFor(String source, String snippet) {
        int index = source.indexOf(snippet);
        int[] start = lineColumn(source, index);
        int[] end = lineColumn(source, index + snippet.length());
        Map<String, Object> selection = new HashMap<>();
        selection.put("startLine", start[0]);
        selection.put("startColumn", start[1]);
        selection.put("endLine", end[0]);
        selection.put("endColumn", end[1]);
        return selection;
    }

    private static int[] lineColumn(String source, int offset) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new int[] {line, column};
    }

    private static JavaProjectModel model(Path root, Path sourceRoot, List<Path> javaFiles) {
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                new ArrayList<>(javaFiles),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "17",
                null,
                null,
                "UTF-8",
                false,
                "none",
                List.of(),
                false,
                List.of("-source", "17", "-target", "17", "-encoding", "UTF-8"),
                List.of(),
                List.of());
        return new JavaProjectModel(
                root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }
}
