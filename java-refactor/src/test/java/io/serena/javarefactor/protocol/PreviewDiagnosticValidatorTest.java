package io.serena.javarefactor.protocol;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The G016 "diagnostics delta matrix": proves the {@link PreviewDiagnosticValidator} delta contract against REAL
 * before/after javac diagnostics. Each test drives a real preview workspaceEdit through {@code validate(...)} and
 * asserts the authoritative javac-derived delta (never the planner-level {@code unvalidated()} placeholder):
 * <ul>
 *   <li>introduced compile error → refusal {@code new_compiler_errors}, delta.newErrors populated, no edit committed;</li>
 *   <li>resolved error (preview fixes a pre-existing error) → delta.resolvedErrors populated, accepted;</li>
 *   <li>unchanged error (pre-existing, neither fixed nor added) → reported unchanged, NOT counted as new;</li>
 *   <li>new/resolved warnings → reported in the delta, accepted (warnings never refuse);</li>
 *   <li>multi-file edit → delta computed across all changed files;</li>
 *   <li>generated root / source-set classpath → validator compiles against the correct model classpath;</li>
 *   <li>exact delta schema → the accepted result carries the real javac fields, not the empty placeholder.</li>
 * </ul>
 */
class PreviewDiagnosticValidatorTest {
    @TempDir
    private Path projectRoot;

    @Test
    void acceptedPreviewReplacesPlannerDiagnosticDeltaWithRealJavacDelta() throws Exception {
        Path source = writeSource("App.java", "class App {\n    int value() { return 1; }\n}\n");
        String preview = previewJson(rewrite(source, "return 1;", "return 2;"));

        String validated = new PreviewDiagnosticValidator().validate("changeSignature", preview, model(false, source));

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(true, result.get("accepted"));
        assertEquals(List.of(), result.get("diagnostics"));
        // HB-10: validate() flips the diagnosticDeltaValidated marker to true, so a downstream guard can prove the
        // accepted preview carries a real javac delta even when (as here) it introduced no new diagnostics.
        assertEquals(true, result.get("diagnosticDeltaValidated"));
        Map<?, ?> delta = (Map<?, ?>) result.get("diagnosticDelta");
        assertEquals(List.of(), delta.get("newWarnings"));
        assertEquals(List.of(), ((Map<?, ?>) delta.get("after")).get("warnings"));
    }

    @Test
    void acceptedPreviewReportsNewJavacWarningsWithoutRefusal() throws Exception {
        Path source = writeSource("App.java",
                "import java.util.List;\nclass App {\n    List<String> value(List list) { return List.of(); }\n}\n");
        String preview = previewJson(rewrite(source, "return List.of();", "return (List<String>) list;"));

        String validated = new PreviewDiagnosticValidator()
                .validate("changeSignature", preview, model(false, List.of("-Xlint:unchecked"), source));

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(true, result.get("accepted"));
        Map<?, ?> delta = (Map<?, ?>) result.get("diagnosticDelta");
        assertEquals(List.of(), delta.get("newErrors"));
        assertTrue(!((List<?>) delta.get("newWarnings")).isEmpty());
        assertTrue(!((List<?>) ((Map<?, ?>) delta.get("after")).get("warnings")).isEmpty());
    }

    @Test
    void acceptedPreviewReportsResolvedJavacWarningWithoutRefusal() throws Exception {
        // A pre-existing unchecked-conversion warning that the preview removes by generifying the parameter (raw
        // `List list` -> `List<String> list`) must be reported as a RESOLVED warning (present before, absent after),
        // introduce NO new warning, and must NOT block the accept.
        Path source = writeSource("App.java",
                "import java.util.List;\nclass App {\n    List<String> value(List list) { return list; }\n}\n");
        String preview = previewJson(rewrite(source, "List list", "List<String> list"));

        String validated = new PreviewDiagnosticValidator()
                .validate("changeSignature", preview, model(false, List.of("-Xlint:unchecked"), source));

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(true, result.get("accepted"));
        Map<?, ?> delta = (Map<?, ?>) result.get("diagnosticDelta");
        assertEquals(List.of(), delta.get("newErrors"));
        List<?> resolvedWarnings = (List<?>) delta.get("resolvedWarnings");
        assertFalse(resolvedWarnings.isEmpty());
        assertTrue(String.valueOf(resolvedWarnings.get(0)).contains("unchecked"));
        // The before-state captured the warning; the after-state no longer carries it, and no NEW warning replaced it.
        assertFalse(((List<?>) ((Map<?, ?>) delta.get("before")).get("warnings")).isEmpty());
        assertEquals(List.of(), ((Map<?, ?>) delta.get("after")).get("warnings"));
        assertEquals(List.of(), delta.get("newWarnings"));
    }

    @Test
    void previewIntroducingJavacErrorIsRefusedWithRealDiagnosticDelta() throws Exception {
        Path source = writeSource("App.java", "class App {\n    int value() { return 1; }\n}\n");
        String preview = previewJson(rewrite(source, "return 1;", "return missing;"));

        String validated = new PreviewDiagnosticValidator().validate("changeSignature", preview, model(false, source));

        Map<String, Object> result = Json.parseObject(validated);
        // Refusal means NO accepted edit is handed back: the workspaceEdit is dropped, so nothing can be committed.
        assertEquals(false, result.get("accepted"));
        assertEquals(false, result.get("applied"));
        assertFalse(result.containsKey("workspaceEdit"));
        assertEquals("new_compiler_errors", ((Map<?, ?>) result.get("refusal")).get("code"));
        Map<?, ?> delta = (Map<?, ?>) result.get("diagnosticDelta");
        assertEquals(List.of(), ((Map<?, ?>) delta.get("before")).get("errors"));
        List<?> newErrors = (List<?>) delta.get("newErrors");
        assertFalse(newErrors.isEmpty());
        assertTrue(String.valueOf(newErrors.get(0)).contains("cannot find symbol"));
    }

    @Test
    void applyModeCompilerErrorRefusalReportsApplyModeNotPreview() throws Exception {
        // G002: when the validator refuses a plan that introduces compiler errors, the refusal must report the ACTUAL
        // requested mode. A direct apply=true that is refused must say mode:"apply", not a hard-coded "preview", while
        // still carrying applied:false and the real javac delta.
        Path source = writeSource("App.java", "class App {\n    int value() { return 1; }\n}\n");
        String preview = previewJson(rewrite(source, "return 1;", "return missing;"));

        String validated = new PreviewDiagnosticValidator().validate("changeSignature", preview, model(false, source), true);

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(false, result.get("accepted"));
        assertEquals(false, result.get("applied"));
        assertEquals("apply", result.get("mode"));
        assertEquals("new_compiler_errors", ((Map<?, ?>) result.get("refusal")).get("code"));
    }

    @Test
    void previewResolvingPreExistingErrorIsAcceptedWithResolvedDelta() throws Exception {
        // The project starts with a real compile error (`return missing;`). The preview fixes exactly that error. The
        // delta must report it as RESOLVED (present before, absent after), carry no new errors, and accept.
        Path source = writeSource("App.java", "class App {\n    int value() { return missing; }\n}\n");
        String preview = previewJson(rewrite(source, "return missing;", "return 1;"));

        String validated = new PreviewDiagnosticValidator().validate("changeSignature", preview, model(true, source));

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(true, result.get("accepted"));
        Map<?, ?> delta = (Map<?, ?>) result.get("diagnosticDelta");
        assertEquals(List.of(), delta.get("newErrors"));
        List<?> resolvedErrors = (List<?>) delta.get("resolvedErrors");
        assertFalse(resolvedErrors.isEmpty());
        assertTrue(String.valueOf(resolvedErrors.get(0)).contains("cannot find symbol"));
        // before carried the error; after is clean.
        assertFalse(((List<?>) ((Map<?, ?>) delta.get("before")).get("errors")).isEmpty());
        assertEquals(List.of(), ((Map<?, ?>) delta.get("after")).get("errors"));
    }

    @Test
    void preExistingErrorStaysUnchangedAndDoesNotBlockBenignEdit() throws Exception {
        // `broken()` has a pre-existing unresolved symbol; the edit only touches the unrelated `value()` body. The
        // pre-existing error must be classified "unchanged" (present before AND after) and must NOT count as a NEW
        // error, so the diagnostic validator accepts. (Apply against an already-broken project is separately gated by
        // Main.modelGateRefusal under allow_incomplete_analysis; that is the config layer, not this delta layer.)
        Path source = writeSource("App.java",
                "class App {\n    int value() { return 1; }\n    int broken() { return missing; }\n}\n");
        String preview = previewJson(rewrite(source, "return 1;", "return 2;"));

        String validated = new PreviewDiagnosticValidator().validate("changeSignature", preview, model(true, source));

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(true, result.get("accepted"));
        Map<?, ?> delta = (Map<?, ?>) result.get("diagnosticDelta");
        assertEquals(List.of(), delta.get("newErrors"));
        List<?> unchangedErrors = (List<?>) delta.get("unchangedErrors");
        assertFalse(unchangedErrors.isEmpty());
        assertTrue(String.valueOf(unchangedErrors.get(0)).contains("cannot find symbol"));
    }

    @Test
    void completeModeRefusesBenignEditThatLeavesPreExistingError() throws Exception {
        // G002: the SAME benign edit as preExistingErrorStaysUnchangedAndDoesNotBlockBenignEdit, but under
        // complete-analysis mode (allowIncompleteAnalysis=false). Complete mode requires the after-state to compile
        // cleanly, so an unchanged pre-existing error blocks the edit with a refusal DISTINCT from new_compiler_errors,
        // and the refusal still carries the diagnostic delta (the unchanged error, with no newly introduced error).
        Path source = writeSource("App.java",
                "class App {\n    int value() { return 1; }\n    int broken() { return missing; }\n}\n");
        String preview = previewJson(rewrite(source, "return 1;", "return 2;"));

        String validated = new PreviewDiagnosticValidator().validate("changeSignature", preview, model(false, source));

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(false, result.get("accepted"));
        assertEquals("preexisting_compiler_errors_not_allowed", ((Map<?, ?>) result.get("refusal")).get("code"));
        Map<?, ?> delta = (Map<?, ?>) result.get("diagnosticDelta");
        assertEquals(List.of(), delta.get("newErrors"));
        List<?> unchangedErrors = (List<?>) delta.get("unchangedErrors");
        assertFalse(unchangedErrors.isEmpty());
        assertTrue(String.valueOf(unchangedErrors.get(0)).contains("cannot find symbol"));
    }

    @Test
    void previewIntroducingParseErrorIsRefused() throws Exception {
        Path source = writeSource("App.java", "class App {\n    int value() { return 1; }\n}\n");
        // Drop the terminating semicolon to introduce a syntax (parse) error.
        String preview = previewJson(rewrite(source, "return 1;", "return 1"));

        String validated = new PreviewDiagnosticValidator().validate("changeSignature", preview, model(false, source));

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(false, result.get("accepted"));
        assertEquals("new_compiler_errors", ((Map<?, ?>) result.get("refusal")).get("code"));
        List<?> newErrors = (List<?>) ((Map<?, ?>) result.get("diagnosticDelta")).get("newErrors");
        assertFalse(newErrors.isEmpty());
    }

    @Test
    void multiFileEditComputesDeltaAcrossEveryChangedFile() throws Exception {
        // A preview that edits two files at once. The benign file stays clean; the second file's edit introduces a new
        // error. The delta must be computed across BOTH files (so the error in the second file is caught), proving the
        // overlay/compile spans the whole changed set rather than a single file.
        Path apiSource = writeSource("Api.java", "class Api {\n    int value() { return 1; }\n}\n");
        Path implSource = writeSource("Impl.java",
                "class Impl {\n    int call(Api api) { return api.value(); }\n}\n");

        String preview = previewJson(
                rewrite(apiSource, "return 1;", "return 2;"),
                rewrite(implSource, "return api.value();", "return api.gone();"));

        String validated = new PreviewDiagnosticValidator()
                .validate("changeSignature", preview, model(false, apiSource, implSource));

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(false, result.get("accepted"));
        assertEquals("new_compiler_errors", ((Map<?, ?>) result.get("refusal")).get("code"));
        List<?> newErrors = (List<?>) ((Map<?, ?>) result.get("diagnosticDelta")).get("newErrors");
        assertFalse(newErrors.isEmpty());
        // The new error is attributed to the SECOND file, proving the compile spanned both edited files.
        assertTrue(newErrors.stream().anyMatch(e -> String.valueOf(e).contains("Impl.java")));
    }

    @Test
    void multiFileEditAcceptedWhenBothFilesStayCleanReportsNoNewErrors() throws Exception {
        // The complementary clean multi-file case: both edits compile, so the cross-file delta is empty and accepted.
        Path apiSource = writeSource("Api.java", "class Api {\n    int value() { return 1; }\n}\n");
        Path implSource = writeSource("Impl.java",
                "class Impl {\n    int call(Api api) { return api.value(); }\n}\n");

        String preview = previewJson(
                rewrite(apiSource, "return 1;", "return 3;"),
                rewrite(implSource, "return api.value();", "return api.value() + 1;"));

        String validated = new PreviewDiagnosticValidator()
                .validate("changeSignature", preview, model(false, apiSource, implSource));

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(true, result.get("accepted"));
        Map<?, ?> delta = (Map<?, ?>) result.get("diagnosticDelta");
        assertEquals(List.of(), delta.get("newErrors"));
    }

    @Test
    void validatesAgainstGeneratedRootSoCrossRootSymbolsResolve() throws Exception {
        // The edited file references a symbol declared ONLY in a generated source root. If the validator compiled the
        // edited file in isolation (without the generated root on the source set), the reference would be a spurious
        // "cannot find symbol" error. Because the source set carries BOTH roots, the BEFORE state is clean and a benign
        // edit is accepted — proving the validator compiles against the full model classpath / source roots.
        Path mainRoot = projectRoot.resolve("src/main/java");
        Path genRoot = projectRoot.resolve("build/generated/java");
        Files.createDirectories(mainRoot);
        Files.createDirectories(genRoot);
        Path generated = genRoot.resolve("Generated.java");
        Files.writeString(generated, "class Generated {\n    static int seed() { return 7; }\n}\n", StandardCharsets.UTF_8);
        Path consumer = mainRoot.resolve("Consumer.java");
        Files.writeString(consumer,
                "class Consumer {\n    int use() { return Generated.seed(); }\n}\n", StandardCharsets.UTF_8);

        SourceSet sourceSet = sourceSet("main", List.of(mainRoot, genRoot), List.of(consumer, generated),
                List.of(genRoot), false, List.of(), List.of());
        JavaProjectModel projectModel = projectModel(false, List.of(sourceSet), List.of(consumer, generated));

        String preview = previewJson(rewrite(consumer, "return Generated.seed();", "return Generated.seed() + 1;"));

        String validated = new PreviewDiagnosticValidator().validate("changeSignature", preview, projectModel);

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(true, result.get("accepted"));
        Map<?, ?> delta = (Map<?, ?>) result.get("diagnosticDelta");
        // No spurious "cannot find symbol Generated" before or after: the generated root was on the classpath.
        assertEquals(List.of(), ((Map<?, ?>) delta.get("before")).get("errors"));
        assertEquals(List.of(), delta.get("newErrors"));
    }

    @Test
    void validatesCrossSourceSetClasspathSoTestResolvesMainSymbols() throws Exception {
        // A `test` source set that dependsOn `main` references a main symbol. The validator must compile the test set
        // with main on its -sourcepath (cross-source-set classpath), so a benign edit in the test file is accepted with
        // no spurious unresolved-symbol error. An edit that calls a non-existent main method introduces a real new error.
        Path mainRoot = projectRoot.resolve("src/main/java");
        Path testRoot = projectRoot.resolve("src/test/java");
        Files.createDirectories(mainRoot);
        Files.createDirectories(testRoot);
        Path mainFile = mainRoot.resolve("Service.java");
        Files.writeString(mainFile, "class Service {\n    int compute() { return 5; }\n}\n", StandardCharsets.UTF_8);
        Path testFile = testRoot.resolve("ServiceTest.java");
        Files.writeString(testFile,
                "class ServiceTest {\n    int run() { return new Service().compute(); }\n}\n", StandardCharsets.UTF_8);

        SourceSet mainSet = sourceSet("main", List.of(mainRoot), List.of(mainFile), List.of(), false, List.of(), List.of());
        SourceSet testSet = sourceSet("test", List.of(testRoot), List.of(testFile), List.of(), false,
                List.of(mainRoot), List.of("main"));
        JavaProjectModel projectModel = projectModel(false, List.of(mainSet, testSet), List.of(mainFile, testFile));

        String benign = previewJson(rewrite(testFile, "return new Service().compute();",
                "return new Service().compute() + 1;"));
        String benignResult = new PreviewDiagnosticValidator().validate("changeSignature", benign, projectModel);
        Map<String, Object> accepted = Json.parseObject(benignResult);
        assertEquals(true, accepted.get("accepted"));
        Map<?, ?> benignDelta = (Map<?, ?>) accepted.get("diagnosticDelta");
        assertEquals(List.of(), ((Map<?, ?>) benignDelta.get("before")).get("errors"));
        assertEquals(List.of(), benignDelta.get("newErrors"));

        String broken = previewJson(rewrite(testFile, "return new Service().compute();",
                "return new Service().missing();"));
        String brokenResult = new PreviewDiagnosticValidator().validate("changeSignature", broken, projectModel);
        Map<String, Object> refused = Json.parseObject(brokenResult);
        assertEquals(false, refused.get("accepted"));
        assertEquals("new_compiler_errors", ((Map<?, ?>) refused.get("refusal")).get("code"));
    }

    @Test
    void acceptedDeltaCarriesAuthoritativeSchemaNotThePlaceholder() throws Exception {
        // Exact-schema assertion: the accepted result's diagnosticDelta must carry the real javac fields, NOT the
        // all-empty unvalidated placeholder that the planner attaches before validation. We prove this against a real
        // change that resolves a pre-existing warning: the placeholder would have an empty before/after, but the
        // authoritative delta records the warning in before and its removal in resolvedWarnings.
        Path source = writeSource("App.java",
                "import java.util.List;\nclass App {\n    List<String> value(List list) { return list; }\n}\n");
        String preview = previewJson(rewrite(source, "List list", "List<String> list"));

        String validated = new PreviewDiagnosticValidator()
                .validate("changeSignature", preview, model(false, List.of("-Xlint:unchecked"), source));

        Map<String, Object> result = Json.parseObject(validated);
        assertEquals(true, result.get("accepted"));
        Map<?, ?> delta = (Map<?, ?>) result.get("diagnosticDelta");
        // Every authoritative schema field is present.
        for (String field : List.of("before", "after", "newErrors", "resolvedErrors", "unchangedErrors",
                "newWarnings", "resolvedWarnings", "unchangedWarnings")) {
            assertTrue(delta.containsKey(field), "delta missing field " + field);
        }
        assertTrue(((Map<?, ?>) delta.get("before")).containsKey("errors"));
        assertTrue(((Map<?, ?>) delta.get("before")).containsKey("warnings"));
        assertTrue(((Map<?, ?>) delta.get("after")).containsKey("errors"));
        assertTrue(((Map<?, ?>) delta.get("after")).containsKey("warnings"));
        // The decisive proof the placeholder was replaced: the before-state is NON-empty (a real warning existed) and
        // resolvedWarnings is populated. The unvalidated() placeholder is all-empty, so this could only be the real delta.
        assertFalse(((List<?>) ((Map<?, ?>) delta.get("before")).get("warnings")).isEmpty());
        assertFalse(((List<?>) delta.get("resolvedWarnings")).isEmpty());
        // The top-level diagnostics array reflects the after-state (no warnings remain after the cast).
        assertEquals(List.of(), result.get("diagnostics"));
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private Path writeSource(String name, String source) throws Exception {
        Path file = projectRoot.resolve(name);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private JavaProjectModel model(boolean allowIncompleteAnalysis, Path... sources) {
        return model(allowIncompleteAnalysis, List.of(), sources);
    }

    private JavaProjectModel model(boolean allowIncompleteAnalysis, List<String> javacOptions, Path... sources) {
        List<Path> files = List.of(sources);
        SourceSet sourceSet = sourceSet("main", List.of(projectRoot), files, List.of(), allowIncompleteAnalysis,
                List.of(), List.of(), javacOptions);
        return projectModel(allowIncompleteAnalysis, List.of(sourceSet), files);
    }

    private SourceSet sourceSet(String name, List<Path> sourceRoots, List<Path> javaFiles, List<Path> generatedRoots,
            boolean allowIncompleteAnalysis, List<Path> crossSourceRoots, List<String> dependsOn) {
        return sourceSet(name, sourceRoots, javaFiles, generatedRoots, allowIncompleteAnalysis, crossSourceRoots,
                dependsOn, List.of());
    }

    private SourceSet sourceSet(String name, List<Path> sourceRoots, List<Path> javaFiles, List<Path> generatedRoots,
            boolean allowIncompleteAnalysis, List<Path> crossSourceRoots, List<String> dependsOn,
            List<String> javacOptions) {
        return new SourceSet(
                name,
                sourceRoots,
                javaFiles,
                List.of(projectRoot.resolve("out").resolve(name)),
                List.of(),
                List.of(),
                generatedRoots,
                null,
                null,
                null,
                "UTF-8",
                false,
                "none",
                List.of(),
                allowIncompleteAnalysis,
                javacOptions,
                javaFiles,
                dependsOn);
    }

    private JavaProjectModel projectModel(boolean allowIncompleteAnalysis, List<SourceSet> sourceSets,
            List<Path> allFiles) {
        return new JavaProjectModel(
                projectRoot,
                "test",
                sourceSets,
                List.of(),
                List.of(),
                allFiles,
                allowIncompleteAnalysis,
                false,
                List.of());
    }

    /** A single file-edit descriptor: the file plus an (oldText -> newText) replacement applied to its on-disk text. */
    private record FileEdit(Path file, String oldText, String newText) {}

    private FileEdit rewrite(Path file, String oldText, String newText) {
        return new FileEdit(file, oldText, newText);
    }

    private String previewJson(FileEdit... edits) throws Exception {
        List<String> changeObjects = new ArrayList<>();
        for (FileEdit edit : edits) {
            String content = Files.readString(edit.file(), StandardCharsets.UTF_8);
            int start = content.indexOf(edit.oldText());
            if (start < 0) {
                throw new IllegalArgumentException("oldText not found in " + edit.file() + ": " + edit.oldText());
            }
            int end = start + edit.oldText().length();
            String relativePath = projectRoot.relativize(edit.file()).toString().replace('\\', '/');
            changeObjects.add("{\"path\":" + JsonUtil.quote(relativePath)
                    + ",\"oldSha256\":\"ignored\",\"edits\":[{"
                    + "\"startOffset\":" + start + ","
                    + "\"endOffset\":" + end + ","
                    + "\"newText\":" + JsonUtil.quote(edit.newText()) + ","
                    + "\"kind\":\"REPLACE\""
                    + "}]}");
        }
        String changes = "[" + String.join(",", changeObjects) + "]";
        // The planner-level placeholder delta the validator is expected to OVERWRITE with the real javac delta. It is
        // intentionally inconsistent with the real edit (claims a "planner warning") so a test that reads the real
        // after-state cannot accidentally pass by echoing the placeholder.
        return "{"
                + "\"accepted\":true,"
                + "\"applied\":false,"
                + "\"operation\":\"changeSignature\","
                + "\"mode\":\"preview\","
                + "\"diagnostics\":[\"planner warning\"],"
                + "\"diagnosticDelta\":{"
                + "\"before\":{\"errors\":[],\"warnings\":[]},"
                + "\"after\":{\"errors\":[],\"warnings\":[\"planner warning\"]},"
                + "\"newErrors\":[],\"resolvedErrors\":[],\"unchangedErrors\":[],"
                + "\"newWarnings\":[\"planner warning\"],\"resolvedWarnings\":[],\"unchangedWarnings\":[]"
                + "},"
                + "\"workspaceEdit\":{\"changes\":" + changes
                + ",\"fileOperations\":[],\"warnings\":[],\"preconditions\":[],"
                + "\"stats\":{\"editCount\":" + edits.length + ",\"fileOperationCount\":0}}"
                + "}";
    }
}
