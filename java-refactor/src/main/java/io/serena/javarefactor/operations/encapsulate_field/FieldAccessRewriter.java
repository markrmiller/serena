package io.serena.javarefactor.operations.encapsulate_field;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans the reference rewrites for {@code encapsulateField} and applies the same-class direct-access (internal-usage)
 * policy (plan §3 {@code encapsulate_field/FieldAccessRewriter}).
 *
 * <p>It is the single place that owns:
 *
 * <ul>
 *   <li>invoking {@link SemanticIndex#fieldAccessorReferenceEdits} with the {@code refuseCompoundAssignments} policy
 *       and translating its low-level {@link IllegalStateException} signals into structured {@link Refused} reasons;</li>
 *   <li>the {@code rewriteInternalUsages} policy: when an internal rewrite is NOT requested, reference edits that fall
 *       inside the declaring type's own body (same-class direct accesses) are dropped so they are left as direct field
 *       access, while references elsewhere remain routed through the accessors.</li>
 * </ul>
 *
 * <p>External references (other files / outside the declaring type body) are always rewritten when
 * {@code updateReferences} is true regardless of the internal-usage policy; the planner decides whether to call this
 * unit at all based on {@code updateReferences}.
 */
public final class FieldAccessRewriter {

    private FieldAccessRewriter() {
    }

    /**
     * Plans the accessor-routing reference edits for {@code field}, then applies the same-class internal-usage policy.
     *
     * @param rewriteInternalUsages       when false, same-class direct accesses are left as direct field access
     * @param refuseCompoundAssignments   the {@code refuse_compound_assignments} policy (true refuses compound/increment
     *                                    usages; false rewrites them with an expression-preserving accessor transform)
     * @throws Refused with a structured code when a usage cannot be safely rewritten
     */
    public static List<PlannerSupport.TextEdit> plan(
            SemanticIndex index,
            SemanticIndex.SemanticField field,
            String getterName,
            String setterName,
            boolean generateSetter,
            boolean rewriteInternalUsages,
            boolean refuseCompoundAssignments,
            SemanticIndex.SourceRange declaringTypeBody) {
        List<PlannerSupport.TextEdit> referenceEdits;
        try {
            referenceEdits = index.fieldAccessorReferenceEdits(
                    field, getterName, setterName, generateSetter, refuseCompoundAssignments);
        } catch (IllegalStateException refusal) {
            throw translate(refusal);
        }
        if (!rewriteInternalUsages) {
            referenceEdits = withoutSameClassReferences(referenceEdits, field.file(), declaringTypeBody);
        }
        return referenceEdits;
    }

    /** Maps the SemanticIndex reference-scan signal to the planner's structured refusal code + message. */
    private static Refused translate(IllegalStateException refusal) {
        String signal = refusal.getMessage();
        if ("setter_required_for_writes".equals(signal)) {
            return new Refused("setter_required_for_writes",
                    "Encapsulate field cannot rewrite write references when setter=false.");
        }
        if ("unsafe_field_usage".equals(signal)) {
            return new Refused("unsafe_field_usage",
                    "V2 encapsulate field refuses field writes whose assignment value is used or whose source positions are unsafe.");
        }
        return new Refused("compound_field_usage",
                "V2 encapsulate field refuses compound assignments and increment/decrement usages.");
    }

    /**
     * Drops reference edits that fall inside the declaring type's own body (same-class direct accesses) so they are left
     * as direct field access, while references in other files / outside the declaring type body remain rewritten through
     * the accessors.
     */
    private static List<PlannerSupport.TextEdit> withoutSameClassReferences(
            List<PlannerSupport.TextEdit> edits, Path declaringFile, SemanticIndex.SourceRange typeBody) {
        Path normalized = declaringFile.toAbsolutePath().normalize();
        List<PlannerSupport.TextEdit> kept = new ArrayList<>();
        for (PlannerSupport.TextEdit edit : edits) {
            boolean sameClass = edit.file().toAbsolutePath().normalize().equals(normalized)
                    && edit.startOffset() >= typeBody.start()
                    && edit.endOffset() <= typeBody.end();
            if (!sameClass) {
                kept.add(edit);
            }
        }
        return kept;
    }

    /** A structured refusal raised while planning reference rewrites, carrying the planner-facing reason code. */
    public static final class Refused extends RuntimeException {
        private final String code;

        public Refused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
