package io.serena.javarefactor.operations.change_signature;

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
 * Behavioural coverage for the V2 introduce-parameter wrapper (story G011). Introduce parameter is a thin front end
 * over the completed change-signature planner (story G010): it resolves and types the selected expression, validates
 * that the expression is a sound call-site default, then appends a new parameter (whose {@code defaultValue} is the
 * selected expression text) and delegates the declaration / call-site / import rewrites to the same
 * {@link ChangeSignaturePlanner#changeSignature} machinery, finally replacing the in-body expression with the new
 * parameter name.
 *
 * <p>The tests below prove the delegation produces the change-signature declaration + call-site edits for a portable
 * default, and that the planner refuses both side-effecting expressions and otherwise-pure expressions that capture
 * enclosing-method state (locals, parameters, {@code this}/{@code super}) which would be invalid when re-emitted at a
 * caller. Sidecar/session-level coverage lives in {@code test_java_refactor_sidecar_sessions.py}.
 */
class IntroduceParameterPlannerTest {

    @Test
    void pureLiteralExpressionIsIntroducedAndDelegatedToChangeSignature(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    String greet() { return helper(\"Bob\"); }\n"
                + "    String helper(String name) { return \"hi \" + name; }\n"
                + "}\n";
        Map<String, Object> fields = introduceFields(source, "String helper(String name)");
        fields.put("selectedExpression", "\"hi \"");
        fields.put("parameterName", "prefix");
        fields.put("parameterType", "String");

        String json = run(tmp, source, fields);
        assertAccepted(json);
        // Declaration rewrite came from the delegated change-signature path (new trailing parameter).
        assertTrue(json.contains("String helper(String name, String prefix)"), json);
        // Call-site default applied: the selected expression text is threaded as the new argument at the caller.
        assertTrue(json.contains("helper(\\\"Bob\\\", \\\"hi \\\")"), json);
    }

    @Test
    void infersExpressionTypeWhenHintOmitted(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    String greet() { return helper(\"Bob\"); }\n"
                + "    String helper(String name) { return \"hi \" + name; }\n"
                + "}\n";
        Map<String, Object> fields = introduceFields(source, "String helper(String name)");
        fields.put("selectedExpression", "\"hi \"");
        fields.put("parameterName", "prefix");
        // No parameterType: javac must infer String from the literal selection.

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("String helper(String name, String prefix)"), json);
    }

    @Test
    void refusesSideEffectingSelectedExpression(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    String helper(String name) { return prefix() + name; }\n"
                + "    String prefix() { return \"hi \"; }\n"
                + "}\n";
        Map<String, Object> fields = introduceFields(source, "String helper(String name)");
        fields.put("selectedExpression", "prefix()");
        fields.put("parameterName", "prefix");
        fields.put("parameterType", "String");

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"SELECTED_EXPRESSION_HAS_SIDE_EFFECTS\""), json);
    }

    @Test
    void refusesExpressionCapturingEnclosingLocalAsCallSiteDefault(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    String greet() { return helper(\"Bob\"); }\n"
                + "    String helper(String name) { return name + \"!\"; }\n"
                + "}\n";
        Map<String, Object> fields = introduceFields(source, "String helper(String name)");
        // Pure (passes the purity gate) but reads the parameter `name`, which is not in scope at the caller.
        fields.put("selectedExpression", "name + \"!\"");
        fields.put("parameterName", "suffix");
        fields.put("parameterType", "String");

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"CALL_SITE_DEFAULT_NOT_PORTABLE\""), json);
    }

    @Test
    void refusesExpressionCapturingInstanceStateAsCallSiteDefault(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    String prefix = \"hi \";\n"
                + "    String greet() { return helper(\"Bob\"); }\n"
                + "    String helper(String name) { return prefix + name; }\n"
                + "}\n";
        Map<String, Object> fields = introduceFields(source, "String helper(String name)");
        // Pure field read, but `this.prefix` binds to a different receiver at the caller (or none in a static caller).
        fields.put("selectedExpression", "prefix");
        fields.put("parameterName", "leading");
        fields.put("parameterType", "String");

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"CALL_SITE_DEFAULT_NOT_PORTABLE\""), json);
    }

    // --- harness ---------------------------------------------------------------------------------------------------

    private static Map<String, Object> introduceFields(String source, String token) {
        int[] pos = positionOf(source, token);
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/demo/Svc.java");
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        return fields;
    }

    private String run(Path tmp, String source, Map<String, Object> fields) throws IOException {
        JavaProjectModel model = singleFileModel(tmp, source, "src/demo/Svc.java");
        return new ChangeSignaturePlanner(tmp.toAbsolutePath().normalize(), model).introduceParameter(fields, false);
    }

    private static void assertAccepted(String json) {
        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("\"accepted\":false"), json);
    }

    /** One-based {line, column} of the first occurrence of {@code token} in {@code source}. */
    private static int[] positionOf(String source, String token) {
        int from = source.indexOf(token);
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < from; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, from - lineStart + 1};
    }

    private static JavaProjectModel singleFileModel(Path root, String source, String relativePath) throws IOException {
        Path sourceRoot = root.resolve("src");
        Path javaFile = root.resolve(relativePath);
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, source, StandardCharsets.UTF_8);

        List<Path> sourceFiles = new ArrayList<>();
        sourceFiles.add(javaFile);
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                sourceFiles,
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
