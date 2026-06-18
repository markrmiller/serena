package io.serena.javarefactor.v3.graph;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.v3.resources.ResourceConfidence;
import io.serena.javarefactor.v3.resources.ResourceReference;
import io.serena.javarefactor.v3.resources.ResourceReferenceScanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves R05 acceptance #2 + #3: both configurable limits are honored.
 *
 * <ul>
 *   <li><b>Graph-cache memory knob.</b> {@link GraphCacheLimits#maxGraphCacheEntries()} bounds how many distinct
 *       revisions {@link GraphInvalidation} retains; exceeding the budget evicts the least-recently-used revision (a
 *       subsequent request for the evicted revision is a miss again). A budget of 0 caches nothing.</li>
 *   <li><b>Resource max-file-size cap.</b> {@link GraphCacheLimits#maxResourceFileBytes()} bounds the resource files the
 *       scanner reads; an over-cap file is NOT silently skipped or truncated — it is surfaced via a LOW-confidence
 *       over-cap signal (and a warning on the diagnostic op) so a possible reference is never silently hidden.</li>
 * </ul>
 */
class GraphCacheLimitsTest {

    // ── config resolution ─────────────────────────────────────────────────────

    @Test
    void resolvesBothKnobsFromGraphConfigSnakeAndCamelCase() {
        GraphCacheLimits snake = GraphCacheLimits.fromGraphConfig(
                Map.of("max_graph_cache_entries", 5, "max_resource_file_bytes", 1234L));
        assertEquals(5, snake.maxGraphCacheEntries());
        assertEquals(1234L, snake.maxResourceFileBytes());

        GraphCacheLimits camel = GraphCacheLimits.fromGraphConfig(
                Map.of("maxGraphCacheEntries", "3", "maxResourceFileBytes", "999"));
        assertEquals(3, camel.maxGraphCacheEntries());
        assertEquals(999L, camel.maxResourceFileBytes());

        GraphCacheLimits defaults = GraphCacheLimits.fromGraphConfig(Map.of());
        assertEquals(GraphCacheLimits.DEFAULT_MAX_GRAPH_CACHE_ENTRIES, defaults.maxGraphCacheEntries());
        assertEquals(GraphCacheLimits.DEFAULT_MAX_RESOURCE_FILE_BYTES, defaults.maxResourceFileBytes());
    }

    // ── graph-cache memory knob (eviction) ─────────────────────────────────────

    @Test
    void evictsLeastRecentlyUsedRevisionBeyondTheEntryBudget(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/app"));
        Path a = write(src.resolve("A.java"), "package app;\npublic class A { int v() { return 1; } }\n");

        GraphInvalidation cache = GraphInvalidation.INSTANCE;
        cache.invalidate();
        GraphCacheLimits budgetOne = new GraphCacheLimits(1, 0L);

        // Revision r1.
        write(a, "package app;\npublic class A { int v() { return 1; } }\n");
        cache.get(model(tmp, src, List.of(a)), budgetOne);
        long fullAfterR1 = GraphInvalidation.buildCount();

        // Revision r2 (modifies A) — with a budget of 1 this evicts r1.
        write(a, "package app;\npublic class A { int v() { return 2; } }\n");
        cache.get(model(tmp, src, List.of(a)), budgetOne);
        long incrAfterR2 = GraphInvalidation.incrementalUpdateCount();

        // Re-request r1's content: r1 was evicted, so this is a cache miss again (served incrementally from r2's facts).
        write(a, "package app;\npublic class A { int v() { return 1; } }\n");
        long incrBeforeReR1 = GraphInvalidation.incrementalUpdateCount();
        cache.get(model(tmp, src, List.of(a)), budgetOne);
        assertEquals(incrBeforeReR1 + 1, GraphInvalidation.incrementalUpdateCount(),
                "re-requesting an EVICTED revision must be a miss (it must rebuild, not hit)");
        assertEquals(fullAfterR1, GraphInvalidation.buildCount(),
                "post-seed misses use the incremental path, not a full rebuild");
    }

    @Test
    void largerBudgetRetainsRevisionsSoARepeatRequestHits(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/app"));
        Path a = write(src.resolve("A.java"), "package app;\npublic class A { int v() { return 1; } }\n");

        GraphInvalidation cache = GraphInvalidation.INSTANCE;
        cache.invalidate();
        GraphCacheLimits budgetThree = new GraphCacheLimits(3, 0L);
        JavaProjectModel r1 = model(tmp, src, List.of(a));

        cache.get(r1, budgetThree);
        write(a, "package app;\npublic class A { int v() { return 2; } }\n");
        cache.get(model(tmp, src, List.of(a)), budgetThree);

        // Re-request r1's exact content while it is still retained -> HIT (no counter advances).
        write(a, "package app;\npublic class A { int v() { return 1; } }\n");
        long fullBefore = GraphInvalidation.buildCount();
        long incrBefore = GraphInvalidation.incrementalUpdateCount();
        cache.get(model(tmp, src, List.of(a)), budgetThree);
        assertEquals(fullBefore, GraphInvalidation.buildCount(), "a retained revision must HIT (no full rebuild)");
        assertEquals(incrBefore, GraphInvalidation.incrementalUpdateCount(),
                "a retained revision must HIT (no incremental update)");
    }

    // ── resource max-file-size cap ─────────────────────────────────────────────

    @Test
    void resourceScanHonorsCapAndSurfacesOverCapFileInsteadOfSkipping(@TempDir Path tmp) throws IOException {
        Path resources = Files.createDirectories(tmp.resolve("src/main/resources"));
        Path small = write(resources.resolve("small.properties"), "bean.class=app.Target\n");
        // A large file that also references the same target; well over the cap below.
        StringBuilder big = new StringBuilder("ref=app.Target\n");
        while (big.length() < 4096) {
            big.append("padding.line=").append(big.length()).append('\n');
        }
        Path large = write(resources.resolve("large.properties"), big.toString());

        JavaProjectModel model = model(tmp, tmp.resolve("src/main/java"), List.of());
        Set<String> targets = Set.of("app.Target");

        // No cap: both files are scanned, neither produces an over-cap signal, and the scan is COMPLETE.
        ResourceReferenceScanner uncapped = new ResourceReferenceScanner(tmp, model, ResourceReferenceScanner.NO_FILE_SIZE_CAP);
        ResourceReferenceScanner.ScanResult uncappedScan = uncapped.referencesFor(targets);
        List<ResourceReference> all = uncappedScan.references();
        assertTrue(all.stream().anyMatch(r -> r.file().equals(small)), "uncapped scan must find the small-file ref");
        assertTrue(all.stream().anyMatch(r -> r.file().equals(large)), "uncapped scan must find the large-file ref");
        assertFalse(all.stream().anyMatch(r -> r.target().equals(ResourceReferenceScanner.OVER_CAP_TARGET)),
                "uncapped scan must not emit an over-cap signal");
        assertTrue(uncappedScan.completeness().isComplete(), "an uncapped scan that read every file must be complete");

        // Cap at 1 KiB: the large file exceeds it and is SURFACED (not silently dropped), the small file still scans, and
        // the completeness gate records the over-cap file so consumers escalate risk / refuse auto-apply (story R06).
        ResourceReferenceScanner capped = new ResourceReferenceScanner(tmp, model, 1024L);
        ResourceReferenceScanner.ScanResult cappedScan = capped.referencesFor(targets);
        List<ResourceReference> capScan = cappedScan.references();
        assertTrue(capScan.stream().anyMatch(r -> r.file().equals(small) && r.target().equals("app.Target")),
                "capped scan must still scan the under-cap file");
        List<ResourceReference> overCap = capScan.stream()
                .filter(r -> r.target().equals(ResourceReferenceScanner.OVER_CAP_TARGET))
                .toList();
        assertEquals(1, overCap.size(), "the over-cap file must be surfaced exactly once, not silently skipped");
        assertEquals(large, overCap.get(0).file(), "the surfaced over-cap signal must point at the large file");
        assertEquals(ResourceConfidence.LOW, overCap.get(0).confidence(),
                "an over-cap signal must be LOW confidence (it was not actually scanned)");
        assertFalse(cappedScan.completeness().isComplete(),
                "an over-cap file must make the scan incomplete so consumers escalate risk");
        assertTrue(cappedScan.completeness().incompleteFiles().stream().anyMatch(p -> p.contains("large.properties")),
                "the over-cap file must be listed in the completeness gate's incomplete files");
    }

    @Test
    void diagnosticFindReferencesWarnsOnOverCapFile(@TempDir Path tmp) throws IOException {
        Path resources = Files.createDirectories(tmp.resolve("src/main/resources"));
        StringBuilder big = new StringBuilder();
        while (big.length() < 4096) {
            big.append("ref=app.Target\n");
        }
        write(resources.resolve("huge.properties"), big.toString());

        JavaProjectModel model = model(tmp, tmp.resolve("src/main/java"), List.of());
        ResourceReferenceScanner capped = new ResourceReferenceScanner(tmp, model, 1024L);
        String json = capped.findReferences(Map.of("target", "app.Target"));
        assertTrue(json.contains("\"accepted\":true"), "diagnostic op must succeed");
        assertTrue(json.contains("exceeds the configured max-file-size cap"),
                "an over-cap file must surface a warning, not be silently skipped: " + json);
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
