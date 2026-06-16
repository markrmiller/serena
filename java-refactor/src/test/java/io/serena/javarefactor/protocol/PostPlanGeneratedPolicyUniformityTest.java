package io.serena.javarefactor.protocol;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G015 (hard blocker 16): the operation-level refusal matrix proving the uniform post-plan generated/Lombok gate
 * ({@link Main#postPlanPolicyRefusalJson}) is applied to EVERY V2 edit target, not just the {@code relativePath}/
 * {@code targetRelativePath} request fields the pre-dispatch gate inspects.
 *
 * <p>Each (operation × flavor) cell builds a synthetic <em>accepted</em> preview whose {@code changedFiles} /
 * {@code workspaceEdit.fileOperations} name a generated/Lombok edit target the way a real multi-file plan would — a move's
 * source file, a pull-up/push-down's supertype+subtype files, an extract-interface's newly created interface file and its
 * cross-file usage sites — and asserts:
 * <ul>
 *   <li><b>default refusal</b>: with {@code allowGenerated=false}/{@code allowLombok=false} the gate refuses with the
 *       established reason code for that flavor; and</li>
 *   <li><b>explicit opt-in</b>: with the matching allow flag the gate passes (returns {@code null}).</li>
 * </ul>
 *
 * <p>The four flavors are: (a) a target under a build-model generated source root (authority), (b) an {@code @Generated}
 * source, (c) a path-heuristic generated path, and (d) a Lombok-managed source. Testing at the gate level (rather than
 * driving a full javac plan per cell) is deliberate: it is exactly where the uniform gate lives on both the request and
 * session preview paths, and it lets a created/multi-file target be exercised without a real planner producing one.
 */
class PostPlanGeneratedPolicyUniformityTest {

    /** Every V2 operation that flows through the post-plan gate (mirrors Main.V2_OPERATIONS). */
    private static final List<String> V2_OPERATIONS = List.of(
            "changeSignature", "introduceParameter", "moveStaticMember", "moveInstanceMethod",
            "pullUpMember", "pushDownMember", "extractMethod", "extractInterface",
            "introduceField", "encapsulateField", "inlineMethod");

    private enum Flavor {
        /** (a) file under a build-model generated source root: authoritative non-editable target. */
        GENERATED_ROOT("non_editable_target"),
        /** (b) @Generated-annotated source text. */
        GENERATED_ANNOTATION("generated_source_refused"),
        /** (c) conventional generated path layout caught by the path heuristic. */
        GENERATED_PATH_HEURISTIC("generated_source_refused"),
        /** (d) Lombok-managed source text. */
        LOMBOK("lombok_managed_source_refused");

        final String expectedCode;

        Flavor(String expectedCode) {
            this.expectedCode = expectedCode;
        }

        boolean isLombok() {
            return this == LOMBOK;
        }
    }

    private static Stream<Arguments> matrix() {
        List<Arguments> cells = new ArrayList<>();
        for (String operation : V2_OPERATIONS) {
            for (Flavor flavor : Flavor.values()) {
                cells.add(Arguments.of(operation, flavor));
            }
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0} × {1} is refused by default and allowed on opt-in")
    @MethodSource("matrix")
    void everyV2OperationGatesEveryGeneratedFlavor(String operation, Flavor flavor, @TempDir Path root)
            throws IOException {
        Path genRoot = root.resolve("build/apout"); // deliberately NOT containing "/generated/": only the build model knows.
        JavaProjectModel model = modelWithGeneratedRoot(root, genRoot);

        // The edit target for this flavor, expressed as a multi-file plan's NON-primary target (a created file or a
        // secondary changed file), i.e. exactly the targets the pre-dispatch relativePath/targetRelativePath gate misses.
        String relativePath;
        String createdContent = null;
        switch (flavor) {
            case GENERATED_ROOT -> relativePath = "build/apout/demo/gen/Gen.java";
            case GENERATED_PATH_HEURISTIC -> relativePath = "src/generated/java/demo/Usage.java";
            case GENERATED_ANNOTATION -> {
                // A newly CREATED file carrying @Generated (e.g. an extract-interface output) — not yet on disk, so the
                // gate must inspect the planned create content, not the (absent) file.
                relativePath = "src/main/java/demo/NewType.java";
                createdContent = "package demo;\nimport javax.annotation.Generated;\n@Generated public interface NewType {}\n";
            }
            case LOMBOK -> {
                relativePath = "src/main/java/demo/LombokTarget.java";
                createdContent = "package demo;\nimport lombok.Data;\n@Data public class LombokTarget { int x; }\n";
            }
            default -> throw new IllegalStateException();
        }

        String preview = syntheticAcceptedPreview(operation, relativePath, createdContent);

        // ── default refusal ──────────────────────────────────────────────────────
        String refusal = Main.postPlanPolicyRefusalJson(
                model, root, operation, /*allowGenerated=*/false, /*allowLombok=*/false, preview);
        assertNotNull(refusal,
                operation + " × " + flavor + ": post-plan gate must refuse the generated/Lombok edit target by default");
        assertEquals(flavor.expectedCode, refusalCode(refusal),
                operation + " × " + flavor + ": refusal must carry the established reason code");
        assertFalse(accepted(refusal), operation + " × " + flavor + ": a refusal is never accepted");

        // ── explicit opt-in ──────────────────────────────────────────────────────
        boolean allowGenerated = !flavor.isLombok();
        boolean allowLombok = flavor.isLombok();
        String allowed = Main.postPlanPolicyRefusalJson(
                model, root, operation, allowGenerated, allowLombok, preview);
        assertNull(allowed,
                operation + " × " + flavor + ": the matching allow flag must let the edit target pass the gate");
    }

    /**
     * Proves the gate also reads an EXISTING (on-disk) edit target's source text — not only created-file content — so a
     * move/pull-up whose secondary changed file is an already-present @Generated or Lombok source is still refused.
     */
    @ParameterizedTest(name = "{0}: existing on-disk @Generated/Lombok changed file is refused")
    @MethodSource("existingDiskOps")
    void gateReadsExistingChangedFileSourceText(String operation, @TempDir Path root) throws IOException {
        JavaProjectModel model = modelWithGeneratedRoot(root, root.resolve("build/apout"));

        Path generatedDecl = root.resolve("src/main/java/demo/Existing.java");
        Files.createDirectories(generatedDecl.getParent());
        Files.writeString(generatedDecl,
                "package demo;\nimport javax.annotation.Generated;\n@Generated public class Existing { int v; }\n",
                StandardCharsets.UTF_8);

        String preview = syntheticAcceptedPreview(operation, "src/main/java/demo/Existing.java", null);

        String refusal = Main.postPlanPolicyRefusalJson(model, root, operation, false, false, preview);
        assertNotNull(refusal, operation + ": an existing @Generated changed file must be refused");
        assertEquals("generated_source_refused", refusalCode(refusal));

        assertNull(Main.postPlanPolicyRefusalJson(model, root, operation, true, false, preview),
                operation + ": allowGenerated must let the existing @Generated changed file pass");
    }

    private static Stream<Arguments> existingDiskOps() {
        return Stream.of(Arguments.of("moveStaticMember"), Arguments.of("pullUpMember"), Arguments.of("extractInterface"));
    }

    /** An ordinary multi-file plan (no generated/Lombok target) must NOT be refused — the gate only fires on policy hits. */
    @org.junit.jupiter.api.Test
    void ordinaryEditTargetsArePassedThrough(@TempDir Path root) throws IOException {
        JavaProjectModel model = modelWithGeneratedRoot(root, root.resolve("build/apout"));
        String preview = "{\"accepted\":true,\"operation\":\"moveStaticMember\","
                + "\"changedFiles\":[\"src/main/java/demo/A.java\",\"src/main/java/demo/B.java\"],"
                + "\"workspaceEdit\":{\"changes\":[],\"fileOperations\":["
                + "{\"kind\":\"create\",\"path\":\"src/main/java/demo/C.java\",\"content\":\"package demo;\\npublic class C {}\\n\"}"
                + "]}}";
        assertNull(Main.postPlanPolicyRefusalJson(model, root, "moveStaticMember", false, false, preview),
                "an ordinary multi-file plan with no generated/Lombok target must pass the gate");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * A synthetic accepted preview whose write target is {@code relativePath}. When {@code createdContent} is non-null the
     * target is modeled as a {@code create} file operation (carrying planned text, as an extract-interface output would);
     * otherwise it is modeled as a secondary changed file with an empty text-edit change entry.
     */
    private static String syntheticAcceptedPreview(String operation, String relativePath, String createdContent) {
        String fileOperations;
        if (createdContent != null) {
            fileOperations = "[{\"kind\":\"create\",\"path\":" + quote(relativePath)
                    + ",\"content\":" + quote(createdContent) + "}]";
        } else {
            fileOperations = "[]";
        }
        // Always list the target in changedFiles (ResponseBuilder derives created paths into changedFiles too); for a
        // create target it appears in both, which the gate de-duplicates.
        return "{\"accepted\":true,\"operation\":" + quote(operation)
                + ",\"mode\":\"preview\",\"applied\":false"
                + ",\"changedFiles\":[" + quote(relativePath) + "]"
                + ",\"workspaceEdit\":{\"changes\":[],\"fileOperations\":" + fileOperations + "}}";
    }

    private static String refusalCode(String json) {
        Object refusal = io.serena.javarefactor.protocol.Json.parseObject(json).get("refusal");
        if (refusal instanceof java.util.Map<?, ?> map && map.get("code") instanceof String code) {
            return code;
        }
        throw new AssertionError("no refusal.code in: " + json);
    }

    private static boolean accepted(String json) {
        Object value = io.serena.javarefactor.protocol.Json.parseObject(json).get("accepted");
        return value instanceof Boolean b && b;
    }

    private static String quote(String value) {
        return io.serena.javarefactor.protocol.JsonUtil.quote(value);
    }

    private static JavaProjectModel modelWithGeneratedRoot(Path root, Path genRoot) {
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(root.resolve("src")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(genRoot),
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
        return new JavaProjectModel(root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }
}
