package io.serena.javarefactor.v3.resources;

import java.util.List;

/**
 * The resource edits and file renames a single {@link ResourceReferenceProvider} plans for ONE resource file under a
 * {@link ResourceRenameRequest} (refactor-feature-plan-V3.md §15). {@link ResourcePlanner} aggregates the per-file plans
 * across the whole project.
 *
 * @param edits       in-place text rewrites for the file (possibly empty, never {@code null})
 * @param fileRenames file renames triggered by the file (e.g. a ServiceLoader interface registration, §15.2)
 */
public record ResourceEditPlan(List<ResourceEdit> edits, List<ResourceFileRename> fileRenames) {

    public static final ResourceEditPlan EMPTY = new ResourceEditPlan(List.of(), List.of());

    public ResourceEditPlan {
        edits = edits == null ? List.of() : List.copyOf(edits);
        fileRenames = fileRenames == null ? List.of() : List.copyOf(fileRenames);
    }

    public boolean isEmpty() {
        return edits.isEmpty() && fileRenames.isEmpty();
    }

    /** A plan with only edits and no file renames. */
    public static ResourceEditPlan ofEdits(List<ResourceEdit> edits) {
        return new ResourceEditPlan(edits, List.of());
    }
}
