package io.serena.javarefactor.v3.frameworks;

import io.serena.javarefactor.edits.PlannerSupport;

/**
 * A TYPED, structured framework-owned resource edit contributed by a {@link FrameworkPlugin} through
 * {@link FrameworkParticipation} (refactor-feature-plan-V3.md §16, shared-contract-1 {@code frameworkBoundaryChanges}).
 *
 * <p>Blocker B5: framework-owned resource changes (a class name in a Spring bean-definition XML, a JPA {@code orm.xml}
 * entity mapping, a DI descriptor) used to be surfaced as human-readable STRINGS folded into the plan's {@code warnings}
 * list, so they were indistinguishable from advisory text and could never be applied or reasoned about as real edits.
 * This record makes a framework-owned change a first-class, structured fact: it always names the
 * {@link #targetResource() target resource file} (project-relative when known, otherwise a descriptor label such as
 * {@code "Spring bean-definition XML"}) and the {@link #kind() edit kind}, and it carries EITHER a concrete
 * {@link #textEdit() TextEdit} the framework could prove, OR — when the SPI cannot produce a compiler/parse-verified
 * edit — a {@link #manualReviewRequired() manual-review-required} marker with the framework's
 * {@link #description() human-readable description}.
 *
 * <p>A framework-owned edit is, by shared contract 1, a {@code frameworkBoundaryChange}: it is carried into the
 * planner's structured result so {@code CanonicalEnvelope.classifyRisk} escalates the op to {@code needs_review} and the
 * Python apply gate blocks SAFE auto-apply. A manual-review-required edit is NEVER silently auto-applied.
 *
 * @param targetResource the resource file (project-relative) or descriptor label the framework owns; never blank
 * @param kind           why the framework treats this as a resource change (e.g. exact class-name rewrite, mapping)
 * @param description    the framework's human-readable explanation of the expected change; never blank
 * @param textEdit       a concrete, framework-proven edit, or {@code null} when none could be produced
 */
public record FrameworkResourceEdit(
        String targetResource,
        Kind kind,
        String description,
        PlannerSupport.TextEdit textEdit) {

    /** Why a framework treats a span/file as a resource change it owns (refactor-feature-plan-V3.md §16). */
    public enum Kind {
        /** An exact fully-qualified class-name token in a framework descriptor (Spring {@code <bean class=…>} etc.). */
        EXACT_CLASS_NAME,
        /** A package-prefix reference (Spring {@code @ComponentScan} base package, etc.). */
        PACKAGE_PREFIX,
        /** A framework metadata/mapping entry (JPA {@code persistence.xml}/{@code orm.xml} {@code <class>} mapping). */
        METADATA_MAPPING
    }

    public FrameworkResourceEdit {
        if (targetResource == null || targetResource.isBlank()) {
            throw new IllegalArgumentException("FrameworkResourceEdit requires a non-blank targetResource");
        }
        if (kind == null) {
            throw new IllegalArgumentException("FrameworkResourceEdit requires a kind");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("FrameworkResourceEdit requires a non-blank description");
        }
    }

    /**
     * A framework-owned edit the SPI could NOT turn into a concrete, parse-verified {@link PlannerSupport.TextEdit}: it
     * carries only the target + description, so it is a manual-review-required {@code frameworkBoundaryChange} that must
     * never be auto-applied.
     */
    public static FrameworkResourceEdit manualReview(String targetResource, Kind kind, String description) {
        return new FrameworkResourceEdit(targetResource, kind, description, null);
    }

    /** Whether this edit has no concrete {@link PlannerSupport.TextEdit} and so requires manual review before applying. */
    public boolean manualReviewRequired() {
        return textEdit == null;
    }
}
