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
 * Behavioural coverage for the V2 change-signature / introduce-parameter hardening goals G006-G011:
 *
 * <ul>
 *   <li>G006 explicit parameter removal: a supplied plan must map or explicitly remove every old parameter; silent
 *       omission is refused with {@code parameter_coverage_incomplete}.</li>
 *   <li>G007 javac-validated call-site defaults: an added parameter whose default cannot be proven a side-effect-free
 *       constant is refused ({@code DEFAULT_ARGUMENT_NOT_VERIFIABLE}) unless {@code allow_side_effects} is set.</li>
 *   <li>G008 qualified-aware type equivalence: two distinct fully-qualified types that share a simple name (e.g.
 *       {@code java.util.List} vs {@code other.List}) are not conflated, so a return-type change between them is treated
 *       as a real change.</li>
 *   <li>G010 selection-field aliases: {@code start_line}/{@code start_col}/{@code end_line}/{@code end_col} and the
 *       {@code selection_start_line} family both drive the introduce-parameter selection.</li>
 *   <li>G011 explicit side-effect opt-in: a non-pure selected expression is refused without opt-in and accepted with
 *       {@code allow_side_effects}.</li>
 * </ul>
 */
class ChangeSignaturePlannerV2GoalsTest {

    // --- G006 ----------------------------------------------------------------------------------------------------

    @Test
    void refusesSilentlyOmittedParameter(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a, int b) { return a; }\n"
                + "    int run() { return compute(1, 2); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a");
        // `b` is silently dropped with no removeParameters declaration: must be refused, not treated as a removal.
        fields.put("parameters", List.of(param("int", "a", null, 0)));

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"parameter_coverage_incomplete\""), json);
        assertTrue(json.contains("b"), json);
    }

    @Test
    void acceptsExplicitRemovalByIndex(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a, int b) { return a; }\n"
                + "    int run() { return compute(1, 2); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a");
        fields.put("parameters", List.of(param("int", "a", null, 0)));
        fields.put("removeParameters", List.of(1));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("compute(int a)"), json);
    }

    // --- G007 ----------------------------------------------------------------------------------------------------

    @Test
    void refusesSideEffectingCallSiteDefault(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a) { return a; }\n"
                + "    int run() { return compute(1); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a)");
        // The added parameter's default is a method call (UNKNOWN purity): not provably safe to duplicate at callers.
        fields.put("parameters", List.of(
                param("int", "a", null, 0),
                param("int", "tag", "next()", null)));

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"DEFAULT_ARGUMENT_NOT_VERIFIABLE\""), json);
    }

    @Test
    void refusesSideEffectingCallSiteDefaultEvenWhenOptedIn(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a) { return a; }\n"
                + "    int run() { return compute(1); }\n"
                + "    int next() { return 7; }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a)");
        fields.put("parameters", List.of(
                param("int", "a", null, 0),
                param("int", "tag", "next()", null)));
        fields.put("allow_side_effects", true);

        String json = run(tmp, source, fields);
        // G004: a change-signature default is detached text with no AST proof, so a method-call default cannot be
        // admitted; allow_side_effects must NOT bypass this contract (only introduce-parameter, which has a real
        // TreePath selection, may opt a side-effecting expression in).
        assertTrue(json.contains("\"code\":\"DEFAULT_ARGUMENT_NOT_VERIFIABLE\""), json);
    }

    @Test
    void acceptsConstantCallSiteDefault(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a) { return a; }\n"
                + "    int run() { return compute(1); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a)");
        fields.put("parameters", List.of(
                param("int", "a", null, 0),
                param("int", "tag", "42", null)));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("compute(1, 42)"), json);
    }

    // --- G008 ----------------------------------------------------------------------------------------------------

    @Test
    void doesNotConflateDistinctlyQualifiedReturnTypes(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.List;\n"
                + "final class Svc {\n"
                + "    List<String> make() { return null; }\n"
                + "    Object run() { return make(); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "make()");
        // The resolved return type is java.util.List<...>; a same-simple-name but differently-qualified target type
        // must be treated as a genuine return-type change (a value-using call site then needs a returnConversion).
        fields.put("newReturnType", "other.List<String>");

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"RETURN_INCOMPATIBILITY\""), json);
    }

    @Test
    void acceptsUnchangedResolvedReturnType(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.List;\n"
                + "final class Svc {\n"
                + "    List<String> make() { return null; }\n"
                + "    Object run() { return make(); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "make()");
        // Supplying the resolved fully-qualified return type is the same type: no change, no refusal.
        fields.put("newReturnType", "java.util.List<java.lang.String>");

        String json = run(tmp, source, fields);
        assertAccepted(json);
    }

    // --- G010 ----------------------------------------------------------------------------------------------------

    @Test
    void introduceParameterAcceptsShortSelectionAliases(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    String greet() { return helper(\"Bob\"); }\n"
                + "    String helper(String name) { return \"hi \" + name; }\n"
                + "}\n";
        Map<String, Object> fields = introduceFields(source, "String helper(String name)");
        putSelection(fields, source, "\"hi \"", "start_line", "start_col", "end_line", "end_col");
        fields.put("parameterName", "prefix");
        fields.put("parameterType", "String");

        String json = run(tmp, source, fields, true);
        assertAccepted(json);
        assertTrue(json.contains("String helper(String name, String prefix)"), json);
    }

    @Test
    void introduceParameterAcceptsCanonicalSelectionFields(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    String greet() { return helper(\"Bob\"); }\n"
                + "    String helper(String name) { return \"hi \" + name; }\n"
                + "}\n";
        Map<String, Object> fields = introduceFields(source, "String helper(String name)");
        putSelection(fields, source, "\"hi \"",
                "selection_start_line", "selection_start_column", "selection_end_line", "selection_end_column");
        fields.put("parameterName", "prefix");
        fields.put("parameterType", "String");

        String json = run(tmp, source, fields, true);
        assertAccepted(json);
        assertTrue(json.contains("String helper(String name, String prefix)"), json);
    }

    // --- G011 ----------------------------------------------------------------------------------------------------

    @Test
    void introduceParameterAcceptsNonPureExpressionWhenOptedIn(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    double greet() { return helper(\"Bob\"); }\n"
                + "    double helper(String name) { return name.length() + Math.random(); }\n"
                + "}\n";
        Map<String, Object> fields = introduceFields(source, "double helper(String name)");
        fields.put("selectedExpression", "Math.random()");
        fields.put("parameterName", "noise");
        fields.put("parameterType", "double");
        fields.put("allow_side_effects", true);

        String json = run(tmp, source, fields, true);
        assertAccepted(json);
        assertTrue(json.contains("double helper(String name, double noise)"), json);
        assertTrue(json.contains("helper(\\\"Bob\\\", Math.random())"), json);
    }

    @Test
    void introduceParameterRefusesNonPureExpressionWithoutOptIn(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    double greet() { return helper(\"Bob\"); }\n"
                + "    double helper(String name) { return name.length() + Math.random(); }\n"
                + "}\n";
        Map<String, Object> fields = introduceFields(source, "double helper(String name)");
        fields.put("selectedExpression", "Math.random()");
        fields.put("parameterName", "noise");
        fields.put("parameterType", "double");

        String json = run(tmp, source, fields, true);
        assertTrue(json.contains("\"code\":\"SELECTED_EXPRESSION_HAS_SIDE_EFFECTS\""), json);
    }

    // --- G019 (B3): change signature can remove all parameters -------------------------------------------------------

    @Test
    void removesTheOnlyParameter(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    void log(int a) { }\n"
                + "    void run() { log(1); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "log(int a)");
        // Empty plan + explicit removal of the only parameter: `void log(int a)` becomes `void log()`.
        fields.put("parameters", List.of());
        fields.put("removeParameters", List.of(0));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("log()"), json);
    }

    @Test
    void removesAllParametersFromMultiParameterMethod(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a, int b) { return 0; }\n"
                + "    int run() { return compute(1, 2); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a");
        // Every existing parameter is explicitly removed by name, so the empty plan is accepted and all call sites drop
        // their (side-effect-free literal) arguments.
        fields.put("parameters", List.of());
        fields.put("removeParameters", List.of("a", "b"));

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("compute()"), json);
    }

    @Test
    void refusesEmptyPlanWithoutExplicitRemoval(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int compute(int a, int b) { return 0; }\n"
                + "    int run() { return compute(1, 2); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "compute(int a");
        // An empty plan must not silently drop parameters: without removeParameters this is parameter_coverage_incomplete.
        fields.put("parameters", List.of());

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"parameter_coverage_incomplete\""), json);
    }

    @Test
    void refusesRemovingAllWhenDroppedArgumentHasSideEffects(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int seq() { return 7; }\n"
                + "    void log(int a) { }\n"
                + "    void run() { log(seq()); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "log(int a)");
        // Removing the only parameter would drop the side-effecting `seq()` argument: refused without an explicit opt-in.
        fields.put("parameters", List.of());
        fields.put("removeParameters", List.of(0));

        String json = run(tmp, source, fields);
        assertTrue(json.contains("\"code\":\"CALL_SITE_ARGUMENT_HAS_SIDE_EFFECTS\""), json);
    }

    @Test
    void removesAllWithSideEffectingArgumentWhenOptedIn(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "final class Svc {\n"
                + "    int seq() { return 7; }\n"
                + "    void log(int a) { }\n"
                + "    void run() { log(seq()); }\n"
                + "}\n";
        Map<String, Object> fields = baseFields(source, "log(int a)");
        fields.put("parameters", List.of());
        fields.put("removeParameters", List.of(0));
        fields.put("allow_removed_side_effecting_arguments", true);

        String json = run(tmp, source, fields);
        assertAccepted(json);
        assertTrue(json.contains("log()"), json);
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

    private static Map<String, Object> introduceFields(String source, String token) {
        return baseFields(source, token);
    }

    /** Writes the four flat selection fields for {@code token} under the supplied alias key names. */
    private static void putSelection(Map<String, Object> fields, String source, String token,
            String startLineKey, String startColumnKey, String endLineKey, String endColumnKey) {
        int[] pos = positionOf(source, token);
        fields.put(startLineKey, pos[0]);
        fields.put(startColumnKey, pos[1]);
        fields.put(endLineKey, pos[0]);
        fields.put(endColumnKey, pos[1] + token.length());
    }

    private String run(Path tmp, String source, Map<String, Object> fields) throws IOException {
        return run(tmp, source, fields, false);
    }

    private String run(Path tmp, String source, Map<String, Object> fields, boolean introduce) throws IOException {
        JavaProjectModel model = singleFileModel(tmp, source, "src/demo/Svc.java");
        ChangeSignaturePlanner planner = new ChangeSignaturePlanner(tmp.toAbsolutePath().normalize(), model);
        return introduce ? planner.introduceParameter(fields, false) : planner.changeSignature(fields, false);
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
