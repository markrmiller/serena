package io.serena.javarefactor.v3.deletion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.serena.javarefactor.compiler.ReachabilityGraphCache;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.protocol.Json;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Story R08: a symbol ADMITTED to the delete set whose source file cannot be read at edit-emission time must never be
 * silently counted as deleted while no declaration edit is emitted. The planner refuses the whole delete with the coded
 * {@code delete_source_unreadable} refusal, so the plan stats (zero) trivially match the emitted edit (nothing), and
 * nothing is written.
 *
 * <p>Construction: the model is built from a real on-disk project so the reachability graph resolves the requested
 * symbol and admits it to {@code deleted} (the graph build and {@link ReachabilityGraphCache#projectKey} both read the
 * file successfully). The unreadability is injected ONLY at the single edit-time read seam
 * ({@link PropagatingSafeDeletePlanner#readDeclarationSource}) via a test subclass, exercising the real
 * {@code compute}/{@code computeEdit}/{@code plan} logic and asserting the real refusal JSON — not a mocked result. This
 * is deterministic on every platform (no {@code chmod} / root-dependent behavior), so the test always RUNS.
 */
final class PropagatingSafeDeleteUnreadableSourceTest {

    /**
     * A project where {@code Holder.java} declares two top-level types: {@code Holder} (kept live by {@code Main}) and
     * {@code Doomed} (unreferenced, so deletable). Deleting {@code Doomed} does NOT remove its whole file (the live
     * {@code Holder} sibling keeps the file), so the delete flows through the per-declaration edit path that calls
     * {@link PropagatingSafeDeletePlanner#readDeclarationSource} — the exact R08-critical read.
     */
    private static JavaProjectModel seedProject(Path root) throws IOException {
        Path src = root.resolve("src/main/java/app");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Main.java"),
                "package app;\npublic class Main {\n  Holder h = new Holder();\n}\n", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("Holder.java"),
                "package app;\nclass Holder {}\nclass Doomed {}\n", StandardCharsets.UTF_8);
        Path sourceRoot = root.resolve("src/main/java");
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                List.of(src.resolve("Main.java"), src.resolve("Holder.java")),
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

    @Test
    void admittedDeletionWithUnreadableSourceRefusesWholeDelete(@TempDir Path root) throws IOException {
        ReachabilityGraphCache.INSTANCE.invalidate();
        JavaProjectModel model = seedProject(root);
        Path holder = root.resolve("src/main/java/app/Holder.java");
        List<PropagatingSafeDeletePlanner.RootSpec> roots =
                List.of(PropagatingSafeDeletePlanner.RootSpec.ofSymbol("app.Doomed"));

        // Sanity: the REAL planner resolves and admits app.Doomed and emits a declaration edit for it (the node travels
        // the per-declaration edit path that reads the source). This proves the refusal path below is genuinely the
        // admitted-deletion read, not a resolution failure.
        String accepted = new PropagatingSafeDeletePlanner().plan(model, roots, options());
        Map<String, Object> acceptedResult = Json.parseObject(accepted);
        assertEquals(Boolean.TRUE, acceptedResult.get("accepted"),
                "the readable-source baseline must accept and delete app.Doomed: " + accepted);
        Map<?, ?> stats = (Map<?, ?>) acceptedResult.get("stats");
        assertEquals(1, ((Number) stats.get("deleted")).intValue(),
                "exactly app.Doomed is deleted in the baseline: " + accepted);
        List<?> baselineChanges = (List<?>) ((Map<?, ?>) acceptedResult.get("workspaceEdit")).get("changes");
        assertFalse(baselineChanges.isEmpty(), "the baseline must emit a declaration edit: " + accepted);

        // Now inject unreadability at the single edit-time read seam for Holder.java. The graph still builds (the model
        // build and projectKey read the file from disk), app.Doomed is still admitted to `deleted`, but computeEdit's
        // readDeclarationSource returns null -> the whole delete is refused rather than claim an unperformed deletion.
        ReachabilityGraphCache.INSTANCE.invalidate();
        PropagatingSafeDeletePlanner planner = new PropagatingSafeDeletePlanner() {
            @Override
            String readDeclarationSource(Path file, Charset charset) {
                if (file.toAbsolutePath().normalize().equals(holder.toAbsolutePath().normalize())) {
                    return null; // simulate an unreadable source exactly at edit-emission time
                }
                return super.readDeclarationSource(file, charset);
            }
        };
        String json = planner.plan(model, roots, options());
        Map<String, Object> result = Json.parseObject(json);

        assertEquals(Boolean.FALSE, result.get("accepted"),
                "an unreadable admitted-deletion source must refuse the whole delete: " + json);
        Map<?, ?> refusal = (Map<?, ?>) result.get("refusal");
        assertEquals("delete_source_unreadable", refusal.get("code"),
                "the refusal must carry the R08 coded refusal: " + json);
        // The refusal emits NOTHING: the canonical refusal envelope carries an EMPTY workspaceEdit (no changes, no file
        // operations) and zero stats, so the plan claims no deletion it cannot perform.
        Map<?, ?> workspaceEdit = (Map<?, ?>) result.get("workspaceEdit");
        assertTrue(((List<?>) workspaceEdit.get("changes")).isEmpty(),
                "a refusal must emit no workspace changes: " + json);
        assertTrue(((List<?>) workspaceEdit.get("fileOperations")).isEmpty(),
                "a refusal must emit no file operations: " + json);
        Map<?, ?> refusedStats = (Map<?, ?>) result.get("stats");
        assertEquals(0, ((Number) refusedStats.get("editCount")).intValue(),
                "a refusal must claim zero edits: " + json);
        assertEquals(0, ((Number) refusedStats.get("fileOperationCount")).intValue(),
                "a refusal must claim zero file operations: " + json);
        // The on-disk source is untouched (plan() never writes; this is preview composition only).
        assertTrue(Files.readString(holder, StandardCharsets.UTF_8).contains("class Doomed {}"),
                "the target file content must be unchanged: " + holder);
    }

    private static PropagatingSafeDeletePlanner.Options options() {
        return PropagatingSafeDeletePlanner.Options.defaults();
    }
}
