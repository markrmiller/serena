package io.serena.javarefactor.v3.graph;

import io.serena.javarefactor.compiler.ReachabilityGraphCache;
import io.serena.javarefactor.compiler.TransformationGraphFacts;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide, revision-keyed cache and INCREMENTAL invalidation policy for the unified {@link TransformationGraph}
 * (refactor-feature-plan-V3.md §1.2/§3 F-GRAPH, story R05).
 *
 * <p><b>Incremental maintenance (R05 acceptance #1).</b> On a new project revision the cache does NOT rebuild the whole
 * transformation graph from scratch on every {@code .java} edit. It keeps, per cached revision, a snapshot of each
 * file's content hash plus the {@link TransformationGraphFacts} that revision produced. When the next revision arrives it
 * diffs the per-file hashes to compute the TOUCHED set (added/modified/removed files), expands it to the 1-hop cross-file
 * neighborhood in the prior graph (every file that references — or is referenced by — a touched file, which is exactly
 * the set whose facts can change), re-extracts facts for only that AFFECTED set, carries the untouched files' facts
 * forward, and re-stitches a new immutable graph (see {@link TransformationGraphBuilder#buildIncremental}). The result is
 * semantically identical to a from-scratch {@link TransformationGraphBuilder#buildArtifacts} for the same revision (the
 * equivalence is asserted by the R05 tests). Only a project-LAYOUT change (source roots / compiler options / classpath,
 * i.e. a changed {@link JavaProjectModel#revisionDigest()}), or the absence of a prior snapshot, falls back to a full
 * rebuild — the one documented full-rebuild class.
 *
 * <p><b>Memory knob (R05 acceptance #2).</b> The cache retains up to {@link GraphCacheLimits#maxGraphCacheEntries()}
 * distinct revisions in LRU order; a new revision beyond the budget evicts the least-recently-used one. A budget of
 * {@code 0} disables caching (every request rebuilds). The default budget is one revision (the legacy single-graph
 * behavior).
 *
 * <p><b>Observability.</b> {@link #buildCount()} counts FULL materializations (cache misses that rebuilt from scratch);
 * {@link #incrementalUpdateCount()} counts incremental updates (a new revision served by re-extracting only the affected
 * files). A test proves the incremental path was taken by asserting the full build count did NOT advance while the
 * incremental count did. {@link #invalidate()} clears everything and is wired into the sidecar shutdown path.
 */
public final class GraphInvalidation {

    /** The single process-wide transformation-graph cache, consulted by every {@code graph.*} consumer. */
    public static final GraphInvalidation INSTANCE = new GraphInvalidation();

    private static final AtomicLong BUILD_COUNT = new AtomicLong();
    private static final AtomicLong INCREMENTAL_UPDATE_COUNT = new AtomicLong();

    private final TransformationGraphBuilder builder = new TransformationGraphBuilder();

    /** One cached revision: its full project key, its layout digest, its per-file hashes, the facts, and the graph. */
    private record Entry(String projectKey, String layoutDigest, Map<String, String> fileHashes,
            TransformationGraphFacts facts, TransformationGraph graph) {
    }

    // Access-ordered LRU keyed by the whole-project key. The eldest entry is evicted when the budget is exceeded.
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, 0.75f, true);

    private GraphInvalidation() {
    }

    /** Returns the cached transformation graph for {@code model}'s current revision with the default limits. */
    public synchronized TransformationGraph get(JavaProjectModel model) throws IOException {
        return get(model, GraphCacheLimits.defaults());
    }

    /**
     * Returns the cached transformation graph for {@code model}'s current revision, honoring {@code limits}. On a cache
     * miss it performs an INCREMENTAL update when possible (re-extracting only the affected files' facts) or a full
     * rebuild otherwise, then caches the result subject to the memory budget. A HIT advances no counter; an incremental
     * update advances {@link #incrementalUpdateCount()}; a full rebuild advances {@link #buildCount()}.
     *
     * @throws IOException if the project cannot be opened or its resources cannot be walked
     */
    public synchronized TransformationGraph get(JavaProjectModel model, GraphCacheLimits limits) throws IOException {
        String key = ReachabilityGraphCache.projectKey(model);
        Entry hit = cache.get(key);
        if (hit != null) {
            return hit.graph();
        }

        String layoutDigest = model.revisionDigest();
        Map<String, String> fileHashes = fileHashes(model);

        // Pick the most-recently-used prior snapshot with the SAME layout and known facts as the incremental base.
        Entry base = incrementalBase(layoutDigest);

        TransformationGraphBuilder.BuildArtifacts artifacts;
        if (base != null && base.facts() != null) {
            Set<String> affected = affectedFiles(base, fileHashes);
            Set<String> surviving = fileHashes.keySet();
            artifacts = builder.buildIncremental(model, base.facts(), affected, surviving, limits);
            INCREMENTAL_UPDATE_COUNT.incrementAndGet();
        } else {
            // Full-rebuild fallback class: no prior snapshot, or a project-layout change.
            artifacts = builder.buildArtifacts(model, limits);
            BUILD_COUNT.incrementAndGet();
        }

        store(new Entry(key, layoutDigest, fileHashes, artifacts.facts(), artifacts.graph()), limits);
        return artifacts.graph();
    }

    /** The most-recently-used cached entry whose layout matches {@code layoutDigest}, or {@code null}. */
    private Entry incrementalBase(String layoutDigest) {
        Entry best = null;
        for (Entry entry : cache.values()) {
            if (entry.layoutDigest().equals(layoutDigest)) {
                best = entry; // access order: the last matching value is the most-recently-used.
            }
        }
        return best;
    }

    /**
     * Stores {@code entry} and evicts least-recently-used entries until the cache holds at most
     * {@code limits.maxGraphCacheEntries()}. A budget of 0 caches nothing.
     */
    private void store(Entry entry, GraphCacheLimits limits) {
        int budget = limits.maxGraphCacheEntries();
        if (budget <= 0) {
            cache.clear();
            return;
        }
        cache.put(entry.projectKey(), entry);
        while (cache.size() > budget) {
            String eldest = cache.keySet().iterator().next();
            cache.remove(eldest);
        }
    }

    /**
     * The AFFECTED file set for an incremental update: the touched files (added/modified/removed) expanded by their
     * 1-hop cross-file neighborhood in the prior graph. A member's facts can change only when its own source changes
     * (its declaration/edges) or when a file it references / is referenced by changes (edge-target resolution), so this
     * neighborhood is exactly the set whose facts may differ; untouched files outside it are carried forward unchanged.
     */
    private static Set<String> affectedFiles(Entry base, Map<String, String> newHashes) {
        Map<String, String> oldHashes = base.fileHashes();
        Set<String> touched = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : newHashes.entrySet()) {
            String old = oldHashes.get(e.getKey());
            if (old == null || !old.equals(e.getValue())) {
                touched.add(e.getKey()); // added or modified
            }
        }
        for (String oldFile : oldHashes.keySet()) {
            if (!newHashes.containsKey(oldFile)) {
                touched.add(oldFile); // removed
            }
        }
        if (touched.isEmpty()) {
            // No content change but a new whole-project key (e.g. a resource file changed): re-extract nothing on the
            // Java side; the merge carries everything forward and the resource scan is recomputed by the builder.
            return Set.of();
        }

        // Build the undirected cross-file adjacency from the prior facts: caller-file <-> target-file for every edge,
        // and child-file <-> parent-file for every supertype edge.
        Map<String, String> keyToFile = base.facts().keyToRelativePath();
        Map<String, String> typeFqnToFile = base.facts().typeFqnToRelativePath();
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        addEdgeAdjacency(adjacency, base.facts().callEdges(), keyToFile);
        addEdgeAdjacency(adjacency, base.facts().constructorEdges(), keyToFile);
        addEdgeAdjacency(adjacency, base.facts().methodReferenceEdges(), keyToFile);
        addEdgeAdjacency(adjacency, base.facts().overrideGroups(), keyToFile);
        // Supertype edges: child FQN -> parent FQN; map both to their declaring files.
        for (Map.Entry<String, Set<String>> e : base.facts().supertypeFqns().entrySet()) {
            String childFile = typeFqnToFile.get(e.getKey());
            for (String parentFqn : e.getValue()) {
                String parentFile = typeFqnToFile.get(parentFqn);
                link(adjacency, childFile, parentFile);
            }
        }

        Set<String> affected = new LinkedHashSet<>(touched);
        for (String file : touched) {
            affected.addAll(adjacency.getOrDefault(file, Set.of()));
        }
        // Only re-extract files that still exist (removed files contribute nothing to extract; the merge prunes them).
        affected.retainAll(newHashes.keySet());
        return affected;
    }

    private static void addEdgeAdjacency(Map<String, Set<String>> adjacency, Map<String, Set<String>> edges,
            Map<String, String> keyToFile) {
        for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
            String fromFile = keyToFile.get(e.getKey());
            for (String toKey : e.getValue()) {
                link(adjacency, fromFile, keyToFile.get(toKey));
            }
        }
    }

    private static void link(Map<String, Set<String>> adjacency, String a, String b) {
        if (a == null || b == null || a.equals(b)) {
            return;
        }
        adjacency.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
        adjacency.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
    }

    /** Project-relative path -> SHA-256 of content, for every {@code .java} source file (sorted, deterministic). */
    private static Map<String, String> fileHashes(JavaProjectModel model) throws IOException {
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();
        TreeMap<String, String> hashes = new TreeMap<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path javaFile : sourceSet.javaFiles()) {
                Path normalized = javaFile.toAbsolutePath().normalize();
                String rel = PlannerSupport.relative(projectRoot, normalized);
                if (Files.isRegularFile(normalized)) {
                    hashes.put(rel, PlannerSupport.sha256(normalized));
                } else {
                    hashes.put(rel, "<missing>");
                }
            }
        }
        return hashes;
    }

    /** Clears the cached graphs; wired into the sidecar shutdown path to avoid cross-project leakage. */
    public synchronized void invalidate() {
        cache.clear();
    }

    /** The number of times a transformation graph was FULLY materialized (a from-scratch rebuild). Process-wide. */
    public static long buildCount() {
        return BUILD_COUNT.get();
    }

    /** The number of times a new revision was served by an INCREMENTAL update (affected-files re-extraction). */
    public static long incrementalUpdateCount() {
        return INCREMENTAL_UPDATE_COUNT.get();
    }
}
