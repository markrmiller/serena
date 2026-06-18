package io.serena.javarefactor.v3.graph;

import java.util.Map;

/**
 * Configurable limits for the V3 transformation-graph cache and the resource scan it drives
 * (refactor-feature-plan-V3.md §1.2/§3 F-GRAPH, story R05 acceptance #2). Two independent knobs:
 *
 * <ul>
 *   <li><b>{@code maxGraphCacheEntries}</b> — the graph-cache memory budget, expressed as the maximum number of distinct
 *       project revisions {@link GraphInvalidation} retains at once. {@link GraphInvalidation} keeps an LRU of fully
 *       materialized {@link TransformationGraph}s; when a new revision would exceed this budget the least-recently-used
 *       revision is evicted. A budget of {@code 0} disables caching entirely (every request rebuilds).</li>
 *   <li><b>{@code maxResourceFileBytes}</b> — the resource-scanning max-file-size cap. A resource file larger than this
 *       cap is NOT silently dropped or truncated: it is surfaced through the refusal/confidence machinery so a reference
 *       it might contain is never silently hidden. {@code 0} means "no cap".</li>
 * </ul>
 *
 * <p>Both knobs are read from the {@code java_refactor.v3.graph} config section (snake_case or camelCase spellings):
 * {@code max_graph_cache_entries}/{@code maxGraphCacheEntries} and {@code max_resource_file_bytes}/
 * {@code maxResourceFileBytes}. Absent/foreign config yields {@link #defaults()}.
 */
public record GraphCacheLimits(int maxGraphCacheEntries, long maxResourceFileBytes) {

    /** The default entry budget: one revision retained (the legacy single-graph behavior). */
    public static final int DEFAULT_MAX_GRAPH_CACHE_ENTRIES = 1;

    /** The default resource-file cap: 8 MiB. Files above this are surfaced via the over-cap signal, never silently cut. */
    public static final long DEFAULT_MAX_RESOURCE_FILE_BYTES = 8L * 1024 * 1024;

    public GraphCacheLimits {
        if (maxGraphCacheEntries < 0) {
            throw new IllegalArgumentException("maxGraphCacheEntries must be >= 0");
        }
        if (maxResourceFileBytes < 0) {
            throw new IllegalArgumentException("maxResourceFileBytes must be >= 0");
        }
    }

    /** The default limits (single-revision cache, 8 MiB resource cap). */
    public static GraphCacheLimits defaults() {
        return new GraphCacheLimits(DEFAULT_MAX_GRAPH_CACHE_ENTRIES, DEFAULT_MAX_RESOURCE_FILE_BYTES);
    }

    /** Whether the resource cap is active (a positive byte budget). */
    public boolean hasResourceFileCap() {
        return maxResourceFileBytes > 0;
    }

    /**
     * Resolves the limits from the effective {@code java_refactor.v3.graph} config block. Any unset knob falls back to
     * its default, so a partial block (e.g. only the resource cap) still yields a complete, valid value.
     */
    public static GraphCacheLimits fromGraphConfig(Map<?, ?> graphConfig) {
        if (graphConfig == null || graphConfig.isEmpty()) {
            return defaults();
        }
        int entries = (int) longValue(graphConfig, DEFAULT_MAX_GRAPH_CACHE_ENTRIES,
                "max_graph_cache_entries", "maxGraphCacheEntries");
        long bytes = longValue(graphConfig, DEFAULT_MAX_RESOURCE_FILE_BYTES,
                "max_resource_file_bytes", "maxResourceFileBytes");
        return new GraphCacheLimits(Math.max(0, entries), Math.max(0L, bytes));
    }

    private static long longValue(Map<?, ?> config, long fallback, String... keys) {
        for (String key : keys) {
            Object value = config.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the next spelling / the default
                }
            }
        }
        return fallback;
    }
}
