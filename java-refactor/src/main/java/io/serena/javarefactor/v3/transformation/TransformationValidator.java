package io.serena.javarefactor.v3.transformation;

/**
 * Runs the sidecar's authoritative before/after javac validation over a fully composed workspace preview
 * (refactor-feature-plan-V3.md §3).
 *
 * <p>The real implementation lives in {@code protocol} (it needs the package-private {@code PreviewDiagnosticValidator}
 * and the discovered {@code JavaProjectModel}), so the transformation package depends only on this single-method
 * interface. Given the unvalidated composed preview JSON — already shaped exactly like a planner's accepted result, with
 * {@code workspaceEdit.changes} and {@code fileOperations} — it returns either the validated accepted JSON (carrying a
 * real {@code diagnosticDelta} and {@code diagnosticDeltaValidated:true}) or a canonical refusal JSON when the after-state
 * fails to compile. The composition runs the validator ONCE over the merged overlay, not per step.
 */
@FunctionalInterface
public interface TransformationValidator {

    /**
     * Validates the composed preview.
     *
     * @param operation    the synthetic operation name carried on the composed result (e.g. {@code transformation})
     * @param previewJson  the unvalidated composed accepted JSON
     * @return validated accepted JSON, or a canonical refusal JSON if the after-state does not compile
     */
    String validate(String operation, String previewJson);
}
