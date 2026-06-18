package io.serena.javarefactor.v3.transformation;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;

import java.util.List;

/**
 * One operation's structured contribution to a transformation workspace (refactor-feature-plan-V3.md §3).
 *
 * <p>A step is the bridge between a V3 planner — which knows how to turn a request into a list of text edits and file
 * operations — and the {@link EditComposer}, which merges many steps into a single validated workspace edit. Carrying
 * the already-built {@link PlannerSupport.TextEdit}/{@link FileOperation} lists (rather than re-parsed JSON) keeps the
 * composition exact: offsets, ranges, and rename source/target paths survive untouched.
 *
 * @param operation the V3 operation name that produced this step (e.g. {@code renamePackage})
 * @param edits     the text edits this operation contributes (may be empty, e.g. moveSourceRoot)
 * @param fileOperations the file operations (renames/creates/deletes) this operation contributes
 * @param warnings  human-readable caveats surfaced to the caller for this operation
 * @param semanticTargetJson the operation's already-serialized {@code semanticTarget} JSON object
 */
public record TransformationStep(
        String operation,
        List<PlannerSupport.TextEdit> edits,
        List<FileOperation> fileOperations,
        List<String> warnings,
        String semanticTargetJson) {

    public TransformationStep {
        edits = List.copyOf(edits);
        fileOperations = List.copyOf(fileOperations);
        warnings = List.copyOf(warnings);
    }
}
