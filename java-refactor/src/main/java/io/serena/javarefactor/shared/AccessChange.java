package io.serena.javarefactor.shared;

/**
 * A single structured visibility change computed by the plan-wide access analyzer
 * ({@link AccessAdjustmentPlanner#requiredAccessChanges}).
 *
 * <p>Each entry describes one source member or type whose access must change so a relocated or
 * extracted body remains source-valid from its destination, or a refusal when the required change is
 * not permitted (security-sensitive private widening, or widening not confirmed by the caller).
 *
 * @param memberName the simple name of the member whose access is analyzed
 * @param declaringType the qualified (or simple, when unavailable) name of the type that declares the member
 * @param fromVisibility the member's current visibility: {@code private}, {@code package-private}, {@code protected}, or {@code public}
 * @param toVisibility the minimal legal visibility after the change, or {@code null} when refused
 * @param reason a human-readable explanation of the change (or the refusal)
 * @param publicApiWidening {@code true} when the change widens the member to public API (callers should surface a warning)
 * @param refusal the structured refusal when the change is not permitted, otherwise {@code null}
 */
public record AccessChange(
        String memberName,
        String declaringType,
        String fromVisibility,
        String toVisibility,
        String reason,
        boolean publicApiWidening,
        StructuredRefusal refusal) {

    /** Whether this entry is a refusal rather than an allowed change. */
    public boolean refused() {
        return refusal != null;
    }

    /** Whether this allowed entry actually widens the member's visibility (vs. leaving it unchanged). */
    public boolean widens() {
        return refusal == null && toVisibility != null && !toVisibility.equals(fromVisibility);
    }
}
