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
 * G006 broad behavioural coverage for change-signature across the categories named in the hard blocker: constructors,
 * override-group propagation, generics/type-parameters, varargs, annotation carry-through, semantic call-site defaults,
 * removed side-effecting arguments (config-gated), return-type conversions, and every public-API confirmation gate.
 * Each case asserts either an accepted preview's edits or a structured refusal code.
 */
class ChangeSignatureCoverageTest {

    // --- constructors ------------------------------------------------------------------------------------------------

    @Test
    void rewritesConstructorAndCallSites(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Box {\n"
                + "    Box(int a, int b) { }\n"
                + "    static Box make() { return new Box(1, 2); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "Box(int a, int b)");
        // Reorder the two constructor parameters; the constructor name cannot change.
        fields.put("parameters", List.of(
                param("int", "b", null, 1),
                param("int", "a", null, 0)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("Box(int b, int a)"), json);
        // Call site argument list reorders to follow the parameter remap (a<->b); only the (args) range is rewritten.
        assertTrue(json.contains("\"newText\":\"(2, 1)\""), json);
    }

    @Test
    void refusesConstructorRename(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Box {\n"
                + "    Box(int a) { }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "Box(int a)");
        fields.put("newName", "Crate");
        fields.put("parameters", List.of(param("int", "a", null, 0)));

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"constructor_rename_unsupported\""), json);
    }

    // --- override propagation ----------------------------------------------------------------------------------------

    @Test
    void propagatesAcrossOverrideGroup(@TempDir Path tmp) throws IOException {
        String base = ""
                + "package demo;\n"
                + "abstract class Base {\n"
                + "    abstract int handle(int a);\n"
                + "}\n";
        String child = ""
                + "package demo;\n"
                + "final class Child extends Base {\n"
                + "    @Override int handle(int a) { return a; }\n"
                + "}\n";
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/demo/Base.java");
        int[] pos = positionOf(base, "int handle(int a)");
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        fields.put("confirmPublicApi", true);
        fields.put("parameters", List.of(
                param("int", "a", null, 0),
                param("int", "extra", "0", null)));

        String json = runMulti(tmp, fields,
                file("src/demo/Base.java", base),
                file("src/demo/Child.java", child));
        assertAccepted(json);
        // Both the abstract declaration and the override get the new parameter.
        assertTrue(json.contains("int handle(int a, int extra)"), json);
        assertTrue(json.contains("src/demo/Base.java"), json);
        assertTrue(json.contains("src/demo/Child.java"), json);
    }

    @Test
    void overrideGroupIsUpdatedTogetherEvenWithLegacyUpdateOverridesField(@TempDir Path tmp) throws IOException {
        // G002: update_overrides was removed from the V2 contract; the override group is always rewritten together.
        // A stray legacy update_overrides=false field must be ignored, never resurrect a partial-update refusal.
        String base = ""
                + "package demo;\n"
                + "abstract class Base {\n"
                + "    abstract int handle(int a);\n"
                + "}\n";
        String child = ""
                + "package demo;\n"
                + "final class Child extends Base {\n"
                + "    @Override int handle(int a) { return a; }\n"
                + "}\n";
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/demo/Base.java");
        int[] pos = positionOf(base, "int handle(int a)");
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        fields.put("confirmPublicApi", true);
        fields.put("update_overrides", false);
        fields.put("parameters", List.of(param("int", "a", null, 0), param("int", "extra", "0", null)));

        String json = runMulti(tmp, fields,
                file("src/demo/Base.java", base),
                file("src/demo/Child.java", child));
        assertAccepted(json);
        assertFalse(json.contains("OVERRIDE_GROUP_UPDATE_REQUIRED"), json);
        // Both the abstract declaration and the override are rewritten despite the ignored legacy field.
        assertTrue(json.contains("int handle(int a, int extra)"), json);
        assertTrue(json.contains("src/demo/Base.java"), json);
        assertTrue(json.contains("src/demo/Child.java"), json);
    }

    // --- generics ----------------------------------------------------------------------------------------------------

    @Test
    void preservesGenericParameterType(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.List;\n"
                + "final class Svc {\n"
                + "    void take(List<String> items) { }\n"
                + "    void run() { take(null); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "void take(List<String> items)");
        // The added parameter's generic type must be preserved verbatim; its default is a compile-time constant
        // (null literal) so it clears the G004 detached-default purity gate.
        fields.put("parameters", List.of(
                param("List<String>", "items", null, 0),
                param("List<Integer>", "counts", "null", null)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("List<Integer> counts"), json);
    }

    // --- varargs -----------------------------------------------------------------------------------------------------

    @Test
    void refusesVarargsRewrite(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int sum(int a) { return a; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "int sum(int a)");
        fields.put("parameters", List.of(
                param("int", "a", null, 0),
                param("int...", "rest", "new int[0]", null)));

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"varargs_unsupported\""), json);
    }

    // --- annotations -------------------------------------------------------------------------------------------------

    @Test
    void carriesParameterAnnotationThroughReorder(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.lang.annotation.*;\n"
                + "final class Svc {\n"
                + "    @Target(ElementType.PARAMETER) @Retention(RetentionPolicy.RUNTIME) @interface NonNull { }\n"
                + "    void take(@NonNull String first, int second) { }\n"
                + "    void run() { take(\"x\", 1); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "void take(@NonNull String first, int second)");
        // Reorder; the @NonNull prefix must travel with `first`.
        fields.put("parameters", List.of(
                param("int", "second", null, 1),
                param("String", "first", null, 0)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("@NonNull String first"), json);
        assertTrue(json.contains("take(1, \\\"x\\\")"), json);
    }

    // --- semantic call-site defaults ---------------------------------------------------------------------------------

    @Test
    void refusesQualifiedFactoryDefault(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    void take(int a) { }\n"
                + "    void run() { take(1); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "void take(int a)");
        fields.put("parameters", List.of(
                param("int", "a", null, 0),
                param("java.util.List<String>", "items", "java.util.Collections.emptyList()", null)));

        String json = run(tmp, source, fields);
        // G004: a fully-qualified factory call is a method invocation, not a provable compile-time constant. As
        // detached default text it has no AST proof of reorder/duplication safety, so it is refused outright.
        assertTrue(json.contains("\"code\":\"DEFAULT_ARGUMENT_NOT_VERIFIABLE\""), json);
    }

    // --- removed side-effecting arguments (gated) --------------------------------------------------------------------

    @Test
    void refusesDroppingSideEffectingArgumentWithoutOptIn(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a, int b) { return a; }\n"
                + "    int tick() { return 1; }\n"
                + "    int run() { return compute(1, tick()); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "int compute(int a, int b)");
        fields.put("parameters", List.of(param("int", "a", null, 0)));
        fields.put("removeParameters", List.of(1));

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"CALL_SITE_ARGUMENT_HAS_SIDE_EFFECTS\""), json);
    }

    @Test
    void dropsSideEffectingArgumentWhenOptedIn(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a, int b) { return a; }\n"
                + "    int tick() { return 1; }\n"
                + "    int run() { return compute(1, tick()); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "int compute(int a, int b)");
        fields.put("parameters", List.of(param("int", "a", null, 0)));
        fields.put("removeParameters", List.of(1));
        fields.put("allow_removed_side_effecting_arguments", true);

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("compute(1)"), json);
    }

    // --- return-type conversions -------------------------------------------------------------------------------------

    @Test
    void appliesReturnConversionAtValueUsingCallSite(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    long size() { return 1; }\n"
                + "    long run() { return size(); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "long size()");
        // The body `return 1;` (an int literal) is assignable to the new int return type, so only call sites need
        // adaptation: a value-using caller is wrapped by the returnConversion to convert the new int back to a long.
        fields.put("newReturnType", "int");
        fields.put("returnConversion", "(long) $return");

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("int size()"), json);
        assertTrue(json.contains("(long) size()"), json);
    }

    @Test
    void returnConversionWrapsEntireQualifiedInvocationIncludingReceiver(@TempDir Path tmp) throws IOException {
        // HB-05: the conversion must wrap the WHOLE invocation expression including the receiver/qualifier, so
        // `this.size()` becomes `(long) this.size()`, never `this.(long) size()`.
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    long size() { return 1; }\n"
                + "    long run() { return this.size(); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "long size()");
        fields.put("newReturnType", "int");
        fields.put("returnConversion", "(long) $return");

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("(long) this.size()"), json);
        assertFalse(json.contains("this.(long)"), json);
    }

    // --- public-API gates --------------------------------------------------------------------------------------------

    @Test
    void refusesPublicApiChangeWithoutConfirmation(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public final class Svc {\n"
                + "    public int compute(int a) { return a; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "public int compute(int a)");
        fields.put("parameters", List.of(param("int", "a", null, 0), param("int", "b", "0", null)));

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"PUBLIC_API_CONFIRMATION_REQUIRED\""), json);
    }

    @Test
    void acceptsPublicApiChangeWithConfirmation(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public final class Svc {\n"
                + "    public int compute(int a) { return a; }\n"
                + "    int run() { return compute(1); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "public int compute(int a)");
        fields.put("confirmPublicApi", true);
        fields.put("parameters", List.of(param("int", "a", null, 0), param("int", "b", "0", null)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("compute(int a, int b)"), json);
        assertTrue(json.contains("compute(1, 0)"), json);
    }

    // --- harness -----------------------------------------------------------------------------------------------------

    private record FileSpec(String relativePath, String source) {
    }

    private static FileSpec file(String relativePath, String source) {
        return new FileSpec(relativePath, source);
    }

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
        JavaProjectModel model = model(tmp, file("src/demo/Svc.java", source));
        return new ChangeSignaturePlanner(tmp.toAbsolutePath().normalize(), model).changeSignature(fields, false);
    }

    private String runMulti(Path tmp, Map<String, Object> fields, FileSpec... files) throws IOException {
        JavaProjectModel model = model(tmp, files);
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

    private static JavaProjectModel model(Path root, FileSpec... files) throws IOException {
        Path sourceRoot = root.resolve("src");
        List<Path> sourceFiles = new ArrayList<>();
        for (FileSpec spec : files) {
            Path javaFile = root.resolve(spec.relativePath());
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, spec.source(), StandardCharsets.UTF_8);
            sourceFiles.add(javaFile);
        }
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
