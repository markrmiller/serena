package io.serena.javarefactor.v3.transformation;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One open transformation workspace (refactor-feature-plan-V3.md §1.1): a composed, preview-ready set of edits and file
 * operations produced by one or more V3 operations, plus the bookkeeping needed to preview, apply, or cancel it.
 *
 * <p>The workspace never writes files. It holds the authoritative composed edit (so {@code transformation.preview} and
 * {@code transformation.apply} are served from the same already-validated data) and the project revision captured at
 * creation time, which {@code transformation.apply} uses to enforce the non-bypassable clean-revision guard.
 */
public final class TransformationWorkspace {

    /** Lifecycle status of a workspace. Phase 1 only ever reaches {@link #PREVIEW_READY} or {@link #APPLIED}. */
    public enum Status {
        PREVIEW_READY("previewReady"),
        APPLIED("applied"),
        CANCELLED("cancelled");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** §1.1 stats describing the composed workspace edit. */
    public record Stats(
            int javaFilesMoved,
            int javaFilesEdited,
            int resourceFilesEdited,
            int buildFilesEdited,
            int textEdits,
            int fileOperations) {

        public String toJson() {
            return "{\"javaFilesMoved\":" + javaFilesMoved
                    + ",\"javaFilesEdited\":" + javaFilesEdited
                    + ",\"resourceFilesEdited\":" + resourceFilesEdited
                    + ",\"buildFilesEdited\":" + buildFilesEdited
                    + ",\"textEdits\":" + textEdits
                    + ",\"fileOperations\":" + fileOperations + "}";
        }
    }

    private final String workspaceId;
    private final String goal;
    private final Path projectRoot;
    private final EditComposer.ComposedEdit composed;
    private final String projectRevision;
    private final long createdAtMillis;
    private Status status;
    private String validatedAcceptedJson;

    public TransformationWorkspace(
            String workspaceId,
            String goal,
            Path projectRoot,
            EditComposer.ComposedEdit composed,
            String projectRevision,
            long createdAtMillis) {
        this.workspaceId = workspaceId;
        this.goal = goal;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.composed = composed;
        this.projectRevision = projectRevision;
        this.createdAtMillis = createdAtMillis;
        this.status = Status.PREVIEW_READY;
    }

    public String workspaceId() {
        return workspaceId;
    }

    public String goal() {
        return goal;
    }

    public EditComposer.ComposedEdit composed() {
        return composed;
    }

    public String projectRevision() {
        return projectRevision;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public Status status() {
        return status;
    }

    public void markApplied() {
        this.status = Status.APPLIED;
    }

    public void markCancelled() {
        this.status = Status.CANCELLED;
    }

    /** The validated accepted JSON produced once during creation; reused verbatim by preview/apply. */
    public String validatedAcceptedJson() {
        return validatedAcceptedJson;
    }

    public void setValidatedAcceptedJson(String json) {
        this.validatedAcceptedJson = json;
    }

    public List<String> warnings() {
        return composed.warnings();
    }

    /**
     * Computes the §1.1 stats from the composed edit. A {@code .java} rename file-op counts as a moved Java file; a
     * distinct {@code .java} file with at least one text edit counts as an edited Java file; a build file is a
     * {@code pom.xml} / {@code build.gradle*}; any other edited file is a resource file.
     */
    public Stats computeStats() {
        Set<String> javaFilesEdited = new LinkedHashSet<>();
        Set<String> resourceFilesEdited = new LinkedHashSet<>();
        Set<String> buildFilesEdited = new LinkedHashSet<>();
        for (PlannerSupport.TextEdit edit : composed.edits()) {
            String relative = PlannerSupport.relative(projectRoot, edit.file());
            if (isBuildFile(relative)) {
                buildFilesEdited.add(relative);
            } else if (isJava(relative)) {
                javaFilesEdited.add(relative);
            } else {
                resourceFilesEdited.add(relative);
            }
        }
        int javaFilesMoved = 0;
        for (FileOperation op : composed.fileOperations()) {
            if ("rename".equals(op.kind()) && op.oldPath() != null && isJava(op.oldPath())) {
                javaFilesMoved++;
            }
        }
        return new Stats(
                javaFilesMoved,
                javaFilesEdited.size(),
                resourceFilesEdited.size(),
                buildFilesEdited.size(),
                composed.edits().size(),
                composed.fileOperations().size());
    }

    private static boolean isJava(String relative) {
        return relative.endsWith(".java");
    }

    private static boolean isBuildFile(String relative) {
        String name = relative.substring(relative.replace('\\', '/').lastIndexOf('/') + 1);
        return name.equals("pom.xml") || name.startsWith("build.gradle");
    }

    /** A compact summary line for {@code transformation.list}. */
    public String toListJson() {
        return "{\"workspaceId\":" + io.serena.javarefactor.protocol.JsonUtil.quote(workspaceId)
                + ",\"goal\":" + io.serena.javarefactor.protocol.JsonUtil.quote(goal == null ? "" : goal)
                + ",\"status\":" + io.serena.javarefactor.protocol.JsonUtil.quote(status.wire())
                + ",\"stats\":" + computeStats().toJson() + "}";
    }
}
