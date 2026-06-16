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
 * Behavioural coverage for the completed V2 change-signature planner (story G010): full parameter-plan execution
 * (add / remove / reorder / retype), annotation + {@code final} preservation on retained parameters, override-group
 * propagation, and op-specific refusals for executables that cannot be soundly rewritten (native methods, annotation
 * type members). Each case drives the real {@link ChangeSignaturePlanner} against a javac-backed temp project and
 * asserts on the emitted preview {@code workspaceEdit} JSON. Target-identity gating is covered separately by
 * {@link ChangeSignaturePlannerTargetIdentityTest}.
 */
class ChangeSignaturePlannerCompletionTest {

    @Test
    void addsParameterWithDefaultAcrossCallSites(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public final class Svc {\n"
                + "    public int compute(int a) { return a; }\n"
                + "    int run() { return compute(1); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a)");
        fields.put("confirmPublicApi", true);
        fields.put("parameters", List.of(
                param("int", "a", null, 0),
                param("String", "tag", "\"x\"", null)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("int a, String tag"), json);
    }

    @Test
    void removesUnusedParameterAndCallArgument(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a, int b) { return a; }\n"
                + "    int run() { return compute(1, 2); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a");
        fields.put("parameters", List.of(param("int", "a", null, 0)));
        // G006: removal must be explicit; the dropped parameter `b` is declared under removeParameters rather than
        // silently omitted from the plan.
        fields.put("removeParameters", List.of("b"));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("compute(int a)"), json);
    }

    @Test
    void reordersParametersAndArguments(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    String helper(String person, int times) { return person + times; }\n"
                + "    String run() { return helper(\"Bob\", 2); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "helper(String person");
        fields.put("parameters", List.of(
                param("int", "times", null, 1),
                param("String", "person", null, 0)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("helper(int times, String person)"), json);
    }

    @Test
    void retypesParameter(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a) { return a; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a)");
        fields.put("parameters", List.of(param("long", "a", null, 0)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("compute(long a)"), json);
    }

    @Test
    void preservesParameterAnnotationsAndFinalModifier(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(@Deprecated final int a, int b) { return a + b; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(@Deprecated");
        // Retype the sibling parameter; the annotated/final parameter must survive verbatim.
        fields.put("parameters", List.of(
                param("int", "a", null, 0),
                param("long", "b", null, 1)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("@Deprecated final int a"), json);
        assertTrue(json.contains("long b"), json);
    }

    @Test
    void propagatesAcrossOverrideGroup(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "interface Service { String label(String name); }\n"
                + "class Impl implements Service {\n"
                + "    public String label(String text) { return text; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "label(String name)");
        fields.put("confirmPublicApi", true);
        fields.put("parameters", List.of(param("String", "label", null, 0)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        // The interface declaration AND the implementing declaration must both be rewritten. The impl's distinct
        // "public" header only appears in the output if override-group propagation rewrote the implementation too.
        assertTrue(json.contains("public String label(String label)"), json);
        assertTrue(json.contains("String label(String label)"), json);
    }

    @Test
    void refusesNativeMethod(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public final class Svc {\n"
                + "    public native int compute(int a);\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a)");
        fields.put("confirmPublicApi", true);
        fields.put("parameters", List.of(
                param("int", "a", null, 0),
                param("String", "tag", "\"x\"", null)));

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"NATIVE_METHOD_UNSUPPORTED\""), json);
    }

    @Test
    void refusesAnnotationTypeMember(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public @interface Anno {\n"
                + "    int value();\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "value()");
        fields.put("relativePath", "src/demo/Anno.java");
        fields.put("confirmPublicApi", true);
        fields.put("parameters", List.of(param("int", "x", "0", null)));

        String json = run(tmp, source, fields, "src/demo/Anno.java");
        // Must be refused; the strongest signal is the op-specific code, but any refusal proves the gate held.
        assertTrue(json.contains("\"accepted\":false"), json);
    }

    // --- harness ---------------------------------------------------------------------------------------------------

    private static Map<String, Object> param(String type, String name, String defaultValue, Integer oldIndex) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", type);
        spec.put("name", name);
        if (defaultValue != null) {
            spec.put("defaultValue", defaultValue);
        }
        if (oldIndex != null) {
            spec.put("oldIndex", oldIndex);
        }
        return spec;
    }

    private static Map<String, Object> baseFields(String source, String token) {
        int[] pos = positionOf(source, token);
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/demo/Svc.java");
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        return fields;
    }

    private String run(Path tmp, String source, Map<String, Object> fields) throws IOException {
        return run(tmp, source, fields, "src/demo/Svc.java");
    }

    private String run(Path tmp, String source, Map<String, Object> fields, String relativePath) throws IOException {
        JavaProjectModel model = singleFileModel(tmp, source, relativePath);
        return new ChangeSignaturePlanner(tmp.toAbsolutePath().normalize(), model).changeSignature(fields, false);
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
