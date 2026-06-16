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
 * G006 hard-blocker coverage for method-reference call sites under a change-signature. The V2 standard rewrites a method
 * reference that remains valid under the new signature (its target functional-interface SAM still conforms) and refuses
 * ONLY the genuinely unsafe cases with a structured reason, using javac/SemanticIndex facts rather than a textual arity
 * heuristic. These tests prove both the accepted-rewrite and structured-refusal directions.
 */
class ChangeSignatureMethodReferenceTest {

    /** Renaming a static method used as a {@code Type::method} reference rewrites the reference name token. */
    @Test
    void renamesStaticMethodReferenceWhenSignatureOtherwiseUnchanged(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "final class Svc {\n"
                + "    static String convert(String input) { return input.trim(); }\n"
                + "    Function<String, String> ref() { return Svc::convert; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "String convert(String input)");
        fields.put("newName", "transform");
        fields.put("confirmPublicApi", true);
        fields.put("parameters", List.of(param("String", "input", null, 0)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        // Declaration renamed and the method-reference name token rewritten to match (preview edits, not applied text).
        assertTrue(json.contains("String transform(String input)"), json);
        assertTrue(json.contains("\"newText\":\"transform\",\"kind\":\"CHANGE_SIGNATURE_METHOD_REFERENCE\""), json);
    }

    /** Widening a parameter type that still conforms to the SAM (the SAM still passes a String into a CharSequence) is safe. */
    @Test
    void allowsCompatibleParameterWideningUnderMethodReference(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "final class Svc {\n"
                + "    static int len(String input) { return input.length(); }\n"
                + "    Function<String, Integer> ref() { return Svc::len; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "int len(String input)");
        fields.put("confirmPublicApi", true);
        // CharSequence is a supertype of the SAM's String argument, so the reference still type-checks.
        fields.put("parameters", List.of(param("CharSequence", "input", null, 0)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("int len(CharSequence input)"), json);
    }

    /** Adding a parameter changes the arity the SAM expects: the reference can no longer bind and is refused. */
    @Test
    void refusesArityChangeUnderMethodReference(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "final class Svc {\n"
                + "    static String convert(String input) { return input.trim(); }\n"
                + "    Function<String, String> ref() { return Svc::convert; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "String convert(String input)");
        fields.put("confirmPublicApi", true);
        fields.put("parameters", List.of(
                param("String", "input", null, 0),
                param("int", "flags", "0", null)));

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"METHOD_REFERENCE_ARITY_CHANGE\""), json);
    }

    /** Narrowing a parameter to a type the SAM cannot supply (String -> Integer) breaks the contract and is refused. */
    @Test
    void refusesIncompatibleParameterTypeUnderMethodReference(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "final class Svc {\n"
                + "    static int len(String input) { return input.length(); }\n"
                + "    Function<String, Integer> ref() { return Svc::len; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "int len(String input)");
        fields.put("confirmPublicApi", true);
        // The SAM would pass a String, which is not assignable to the new Integer parameter.
        fields.put("parameters", List.of(param("Integer", "input", null, 0)));

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"METHOD_REFERENCE_PARAMETER_INCOMPATIBLE\""), json);
    }

    /** Changing the return type to one not assignable to the SAM's expected result (Integer) is refused. */
    @Test
    void refusesIncompatibleReturnTypeUnderMethodReference(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "final class Svc {\n"
                + "    static Integer count(String input) { return input.length(); }\n"
                + "    Function<String, Integer> ref() { return Svc::count; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "Integer count(String input)");
        fields.put("confirmPublicApi", true);
        fields.put("newReturnType", "String");
        fields.put("returnConversion", "String.valueOf($return)");
        fields.put("parameters", List.of(param("String", "input", null, 0)));

        String json = run(tmp, source, fields);
        // A String result is not assignable to the SAM's Integer result, so the method reference is refused even though
        // ordinary value-using call sites could be adapted by a returnConversion.
        assertTrue(json.contains("\"code\":\"METHOD_REFERENCE_RETURN_INCOMPATIBLE\""), json);
    }

    /** A bound instance reference {@code obj::method} renamed: the SAM binds 1:1 to the method params, rename is safe. */
    @Test
    void renamesBoundInstanceMethodReference(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.Supplier;\n"
                + "final class Svc {\n"
                + "    String value() { return \"v\"; }\n"
                + "    Supplier<String> ref() { Svc s = new Svc(); return s::value; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "String value()");
        fields.put("newName", "compute");
        fields.put("confirmPublicApi", true);

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("String compute()"), json);
        assertTrue(json.contains("\"newText\":\"compute\",\"kind\":\"CHANGE_SIGNATURE_METHOD_REFERENCE\""), json);
    }

    // --- harness -----------------------------------------------------------------------------------------------------

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
        JavaProjectModel model = singleFileModel(tmp, source, "src/demo/Svc.java");
        return new ChangeSignaturePlanner(tmp.toAbsolutePath().normalize(), model).changeSignature(fields, false);
    }

    private static void assertAccepted(String json) {
        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("\"accepted\":false"), json);
    }

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
