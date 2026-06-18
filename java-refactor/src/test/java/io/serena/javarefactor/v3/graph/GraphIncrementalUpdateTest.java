package io.serena.javarefactor.v3.graph;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves R05 acceptance #1 + #3: the transformation-graph cache maintains the graph INCREMENTALLY across {@code .java}
 * edits (re-extracting only the touched files' contributions plus their 1-hop cross-file neighborhood) and that the
 * incrementally-maintained graph is byte-for-byte identical to a from-scratch rebuild for the same revision over every
 * edit class — modify a referenced type, add a file, delete a file, and edit an untouched file. The observable counters
 * ({@link GraphInvalidation#buildCount()} for full rebuilds, {@link GraphInvalidation#incrementalUpdateCount()} for
 * incremental updates) prove the incremental path was taken (full build count flat, incremental count advances).
 */
class GraphIncrementalUpdateTest {

    private final TransformationGraphBuilder builder = new TransformationGraphBuilder();

    /**
     * The correctness oracle: after every edit the cached (incrementally maintained) graph must equal a from-scratch
     * {@link TransformationGraphBuilder#build} of the SAME on-disk revision, and the incremental path must have been used
     * (no full rebuild) for the post-seed edits.
     */
    @Test
    void incrementalGraphEqualsFromScratchOverEveryEditClass(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/app"));
        Path a = write(src.resolve("A.java"), """
                package app;
                public class A {
                    public int value() { return new B().base() + 1; }
                }
                """);
        Path b = write(src.resolve("B.java"), """
                package app;
                public class B {
                    public int base() { return 41; }
                }
                """);
        Path untouched = write(src.resolve("Untouched.java"), """
                package app;
                public class Untouched {
                    public String name() { return "constant"; }
                }
                """);

        GraphInvalidation cache = GraphInvalidation.INSTANCE;
        cache.invalidate();
        List<Path> files = new ArrayList<>(List.of(a, b, untouched));

        // Seed: first request is necessarily a full rebuild (no prior snapshot).
        long fullAfterSeed;
        long incrAfterSeed;
        {
            long fullBefore = GraphInvalidation.buildCount();
            long incrBefore = GraphInvalidation.incrementalUpdateCount();
            assertGraphsEqual(cache, model(tmp, src, files));
            fullAfterSeed = GraphInvalidation.buildCount();
            incrAfterSeed = GraphInvalidation.incrementalUpdateCount();
            assertEquals(fullBefore + 1, fullAfterSeed, "seed must be a full rebuild");
            assertEquals(incrBefore, incrAfterSeed, "seed must not be an incremental update");
        }

        // Edit class 1: modify a referenced type (B's body) — A's B->base edge must still resolve correctly.
        write(b, """
                package app;
                public class B {
                    public int base() { return 100; }
                }
                """);
        long incrAfterModify = assertIncrementalEdit(cache, model(tmp, src, files), fullAfterSeed, incrAfterSeed);

        // Edit class 2: add a new file that references an existing type.
        Path c = write(src.resolve("C.java"), """
                package app;
                public class C {
                    public int viaA() { return new A().value(); }
                }
                """);
        files.add(c);
        long incrAfterAdd = assertIncrementalEdit(cache, model(tmp, src, files), fullAfterSeed, incrAfterModify);

        // Edit class 3: edit an untouched-by-relationships file (changes only its own member body).
        write(untouched, """
                package app;
                public class Untouched {
                    public String name() { return "changed"; }
                }
                """);
        long incrAfterUntouched =
                assertIncrementalEdit(cache, model(tmp, src, files), fullAfterSeed, incrAfterAdd);

        // Edit class 4: delete a file (C) — the graph must drop C's contributions.
        Files.delete(c);
        files.remove(c);
        assertIncrementalEdit(cache, model(tmp, src, files), fullAfterSeed, incrAfterUntouched);
    }

    /**
     * Requests the graph through the cache (which uses the incremental path) and asserts it equals a from-scratch build,
     * that NO full rebuild occurred, and that the incremental counter advanced by exactly one. Returns the new
     * incremental-update count.
     */
    private long assertIncrementalEdit(GraphInvalidation cache, JavaProjectModel model, long expectedFullCount,
            long priorIncrCount) throws IOException {
        String cached = cache.get(model, GraphCacheLimits.defaults()).toJson();
        String fromScratch = builder.build(model).toJson();
        assertEquals(fromScratch, cached, "incrementally maintained graph must equal a from-scratch rebuild");
        assertEquals(expectedFullCount, GraphInvalidation.buildCount(),
                "an incremental edit must NOT trigger a full whole-project rebuild");
        assertEquals(priorIncrCount + 1, GraphInvalidation.incrementalUpdateCount(),
                "an incremental edit must advance the incremental-update counter exactly once");
        return GraphInvalidation.incrementalUpdateCount();
    }

    private void assertGraphsEqual(GraphInvalidation cache, JavaProjectModel model) throws IOException {
        String cached = cache.get(model, GraphCacheLimits.defaults()).toJson();
        String fromScratch = builder.build(model).toJson();
        assertEquals(fromScratch, cached, "cached graph must equal a from-scratch rebuild");
    }

    /** A cross-file edge (A -> B) must be present in the from-scratch JSON so the equivalence test is meaningful. */
    @Test
    void fromScratchGraphContainsCrossFileCallEdge(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/app"));
        Path a = write(src.resolve("A.java"), """
                package app;
                public class A { public int value() { return new B().base(); } }
                """);
        Path b = write(src.resolve("B.java"), """
                package app;
                public class B { public int base() { return 41; } }
                """);
        String json = builder.build(model(tmp, src, List.of(a, b))).toJson();
        assertTrue(json.contains("app.A"), "graph must contain type A");
        assertTrue(json.contains("app.B"), "graph must contain type B");
        // A change to B's body changes the graph JSON (sanity for the modify-referenced-type oracle).
        write(b, "package app;\npublic class B { public int base() { return 99; } public int extra() { return 1; } }\n");
        String changed = builder.build(model(tmp, src, List.of(a, b))).toJson();
        assertNotEquals(json, changed, "adding a member to B must change the graph JSON");
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
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
