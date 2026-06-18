package io.serena.javarefactor.compiler;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Process-wide memoization of {@link ReachabilityGraph} instances keyed by a WHOLE-PROJECT revision (G-CACHE,
 * refactor-feature-plan-V3.md §3 F-GRAPH). The graph is stateless and was previously rebuilt on every request at the
 * three call sites ({@code ImpactFactsAnalyzer}, {@code DeadCodeAnalyzer}, {@code PropagatingSafeDeletePlanner}); this
 * cache lets repeated requests over an unchanged project reuse a single walk over the compiler tasks. It is a pure
 * performance win: a {@link ReachabilityGraph} holds only materialized values (canonical key strings, file paths, and
 * string-keyed edge sets — never live javac {@code Element}/{@code TypeMirror} references), so a cached graph stays
 * valid after the {@link SemanticIndex} it was built from is closed.
 *
 * <p><b>The keying contract (the whole point of the cache).</b> The key is a project-wide revision, NOT
 * {@code ProjectRevision.stableToken()}. That token is scoped to a workspace's TOUCHED files only — correct for the
 * apply-time drift guard, but WRONG here: a change to an UNtouched file would not move a touched-file-scoped token yet
 * DOES change the graph, which would silently serve a stale graph and corrupt impact facts. Instead the key combines
 * {@link JavaProjectModel#revisionDigest()} (source-set layout, compiler options, classpath, ... — every
 * inventory-independent model field) with a content digest over EVERY {@code .java} source file across EVERY source
 * root (path + SHA-256 of bytes, in sorted order). Any edit to any source file — touched or not — yields a new key, a
 * cache miss, and a rebuild. Caching is therefore content-addressed: invalidation is automatic on key change.
 *
 * <p>Memory is bounded: only the most-recently-seen key is retained, with at most one entry per {@code includeTests}
 * flavor (true/false). A new key evicts the previous key's entries. {@link #invalidate()} clears everything and is
 * wired into the sidecar's {@code shutdown} path to prevent cross-project leakage.
 */
public final class ReachabilityGraphCache {

    /** The single process-wide cache instance, consulted by every graph-build call site. */
    public static final ReachabilityGraphCache INSTANCE = new ReachabilityGraphCache();

    private String currentKey;
    private final Map<Boolean, ReachabilityGraph> graphs = new LinkedHashMap<>();

    private ReachabilityGraphCache() {
    }

    /**
     * Returns the cached graph for {@code (projectKey, includeTests)} when present, otherwise builds one via
     * {@code builder}, stores it, and returns it. A {@code projectKey} that differs from the currently-cached key
     * evicts the previous key's entries first (content-addressed invalidation).
     */
    public synchronized ReachabilityGraph get(String projectKey, boolean includeTests,
            Supplier<ReachabilityGraph> builder) {
        if (currentKey == null || !currentKey.equals(projectKey)) {
            graphs.clear();
            currentKey = projectKey;
        }
        ReachabilityGraph cached = graphs.get(includeTests);
        if (cached != null) {
            return cached;
        }
        ReachabilityGraph built = builder.get();
        graphs.put(includeTests, built);
        return built;
    }

    /** Clears every cached graph; wired into the sidecar shutdown path to avoid cross-project leakage. */
    public synchronized void invalidate() {
        currentKey = null;
        graphs.clear();
    }

    /**
     * Computes the WHOLE-PROJECT cache key: {@link JavaProjectModel#revisionDigest()} folded with a content digest over
     * every {@code .java} source file across every source root (sorted {@code absolutePath -> sha256(content)}). This is
     * deliberately broader than any touched-file-scoped revision so that a change to an UNtouched source file still
     * produces a new key and forces a rebuild.
     */
    public static String projectKey(JavaProjectModel model) throws IOException {
        TreeMap<String, String> inventory = new TreeMap<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path javaFile : sourceSet.javaFiles()) {
                Path normalized = javaFile.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized)) {
                    inventory.put(normalized.toString(), PlannerSupport.sha256(normalized));
                } else {
                    inventory.put(normalized.toString(), "<missing>");
                }
            }
        }
        StringBuilder builder = new StringBuilder();
        builder.append("model=").append(model.revisionDigest()).append('\n');
        for (Map.Entry<String, String> entry : inventory.entrySet()) {
            builder.append("src ").append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
        return sha256(builder.toString());
    }

    private static String sha256(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }
}
