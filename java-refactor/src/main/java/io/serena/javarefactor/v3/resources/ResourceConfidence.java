package io.serena.javarefactor.v3.resources;

/**
 * Confidence that a {@link ResourceReference} truly denotes the target Java type (refactor-feature-plan-V3.md §15).
 * Confidence is deliberately conservative: only structurally-unambiguous occurrences (service-loader provider lines,
 * exact dotted class tokens in structured config) are {@link #HIGH}; free-text/heuristic matches are {@link #LOW} and
 * are surfaced for human review rather than auto-edited.
 */
public enum ResourceConfidence {
    HIGH,
    MEDIUM,
    LOW
}
