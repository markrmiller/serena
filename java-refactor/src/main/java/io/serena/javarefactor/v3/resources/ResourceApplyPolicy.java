package io.serena.javarefactor.v3.resources;

/**
 * The single, project-wide confidence-to-apply policy for resource rewrites (refactor-feature-plan-V3.md §18.4).
 *
 * <p>Every non-Java-source rewrite carries a {@link ResourceConfidence}; this class is the one place that maps a
 * confidence to whether the rewrite may be applied automatically, must be previewed for human confirmation, or is
 * review-only. The §18.4 default policy is:
 *
 * <pre>
 * apply HIGH
 * preview MEDIUM unless configured
 * never auto-apply LOW
 * </pre>
 *
 * Wiring this rule through one helper — instead of letting each call site re-derive "is this safe to write" — keeps the
 * resource edit-planning ({@link ResourceEditPlanner}) and the package rename/move apply path ({@code ResourceRewriter})
 * uniform: a HIGH edit auto-applies everywhere, a MEDIUM edit auto-applies only when the caller opts in via
 * {@code applyMediumConfidence}, and a LOW match is never auto-edited (it stays a review-only finding).
 */
public final class ResourceApplyPolicy {

    private ResourceApplyPolicy() {
    }

    /**
     * What may be done with a rewrite of the given {@link ResourceConfidence}, per §18.4.
     *
     * <ul>
     *   <li>{@link #AUTO_APPLY} — safe to write without confirmation.</li>
     *   <li>{@link #PREVIEW} — surfaced for human confirmation, not auto-written unless the caller opts in.</li>
     *   <li>{@link #REVIEW_ONLY} — never auto-written; a warned finding for human review.</li>
     * </ul>
     */
    public enum Disposition {
        AUTO_APPLY,
        PREVIEW,
        REVIEW_ONLY
    }

    /**
     * Classifies a rewrite of {@code confidence} into its {@link Disposition} under the §18.4 policy. HIGH is always
     * {@link Disposition#AUTO_APPLY}; MEDIUM is {@link Disposition#AUTO_APPLY} only when {@code applyMediumConfidence} is
     * set (otherwise {@link Disposition#PREVIEW}); LOW is always {@link Disposition#REVIEW_ONLY}.
     */
    public static Disposition dispositionFor(ResourceConfidence confidence, boolean applyMediumConfidence) {
        return switch (confidence) {
            case HIGH -> Disposition.AUTO_APPLY;
            case MEDIUM -> applyMediumConfidence ? Disposition.AUTO_APPLY : Disposition.PREVIEW;
            case LOW -> Disposition.REVIEW_ONLY;
        };
    }

    /** Whether a rewrite of {@code confidence} may be written without human confirmation under the §18.4 policy. */
    public static boolean autoApplies(ResourceConfidence confidence, boolean applyMediumConfidence) {
        return dispositionFor(confidence, applyMediumConfidence) == Disposition.AUTO_APPLY;
    }
}
