package io.serena.javarefactor.v3.transformation;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges the structured {@link TransformationStep}s of a transformation workspace into one composed edit
 * (refactor-feature-plan-V3.md §1.1/§3).
 *
 * <p>Composition is intentionally permissive about <em>co-located</em> edits and strict only about genuine conflicts:
 * <ul>
 *   <li><b>Text edits</b> from all steps are concatenated and sorted by {@code (file, startOffset)}. Two edits to the
 *       SAME file that do not overlap compose silently — this is the whole point of a workspace (e.g. one operation edits
 *       a package declaration at the top of a file while another rewrites a reference lower down). A composition is
 *       refused ONLY when two edits target the same file with truly overlapping half-open {@code [start, end)} ranges.</li>
 *   <li><b>File operations</b> compose unless they genuinely conflict: two renames of the same source path, two
 *       operations creating the same path, a create over a path another op already produces, or a rename whose source
 *       path is also edited by text edits keyed to that (now-moving) path under a different op — the classic
 *       rename-then-edit-old-path hazard.</li>
 * </ul>
 * A genuine conflict is reported by throwing {@link ComposeConflict} carrying the canonical {@code workspace_edit_conflict}
 * code; the workspace turns that into the canonical refusal JSON.
 *
 * <p>Note: this composes V3 TransformationSteps and is intentionally permissive about co-located, non-overlapping
 * same-file edits. The Python V2-session workspace composer ({@code serena/java_refactor_v3/workspace.py::_compose})
 * enforces a STRICTER file-level rule because V2 session edits are independently planned against the ORIGINAL file and
 * are not offset-composable; the two layers are intentionally separate and must NOT be unified.
 */
public final class EditComposer {

    /** The merged, conflict-checked contribution of every step in a workspace. */
    public record ComposedEdit(
            List<PlannerSupport.TextEdit> edits,
            List<FileOperation> fileOperations,
            List<String> warnings,
            String semanticTargetJson) {
    }

    /** Raised when two steps genuinely conflict; carries the canonical {@code workspace_edit_conflict} refusal code. */
    public static final class ComposeConflict extends RuntimeException {
        public ComposeConflict(String message) {
            super(message);
        }
    }

    private final Path projectRoot;

    public EditComposer(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /**
     * Composes {@code steps} into one workspace edit, detecting true overlap and conflicting file operations.
     *
     * @throws ComposeConflict if two steps genuinely conflict (refused with {@code workspace_edit_conflict})
     */
    public ComposedEdit compose(List<TransformationStep> steps) {
        List<PlannerSupport.TextEdit> allEdits = new ArrayList<>();
        List<FileOperation> allFileOps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> semanticTargets = new ArrayList<>();
        for (TransformationStep step : steps) {
            allEdits.addAll(step.edits());
            allFileOps.addAll(step.fileOperations());
            warnings.addAll(step.warnings());
            semanticTargets.add("{\"operation\":" + io.serena.javarefactor.protocol.JsonUtil.quote(step.operation())
                    + ",\"target\":" + step.semanticTargetJson() + "}");
        }

        // Sort by (absolute file path, startOffset) so overlap detection compares adjacent edits in the same file.
        allEdits.sort(Comparator
                .comparing((PlannerSupport.TextEdit e) -> e.file().toAbsolutePath().normalize().toString())
                .thenComparingLong(PlannerSupport.TextEdit::startOffset)
                .thenComparingLong(PlannerSupport.TextEdit::endOffset));
        detectTrueOverlap(allEdits);
        detectFileOperationConflicts(allFileOps);
        detectRenameThenEditOldPath(allEdits, allFileOps);

        String semanticTargetJson = "{\"kind\":\"transformation\",\"steps\":["
                + String.join(",", semanticTargets) + "]}";
        return new ComposedEdit(allEdits, allFileOps, warnings, semanticTargetJson);
    }

    /** Refuses ONLY when two edits to the same file have overlapping half-open {@code [start, end)} ranges. */
    private void detectTrueOverlap(List<PlannerSupport.TextEdit> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            PlannerSupport.TextEdit prev = sorted.get(i - 1);
            PlannerSupport.TextEdit cur = sorted.get(i);
            Path prevFile = prev.file().toAbsolutePath().normalize();
            Path curFile = cur.file().toAbsolutePath().normalize();
            if (!prevFile.equals(curFile)) {
                continue;
            }
            // Half-open ranges overlap iff cur.start < prev.end. A zero-width insertion (start == end) at the same offset
            // as another edit's boundary does not overlap (cur.start == prev.end), so adjacent edits still compose.
            if (cur.startOffset() < prev.endOffset()) {
                throw new ComposeConflict("workspace edit conflict: overlapping edits in '"
                        + PlannerSupport.relative(projectRoot, curFile) + "' — ranges ["
                        + prev.startOffset() + "," + prev.endOffset() + ") and ["
                        + cur.startOffset() + "," + cur.endOffset() + ") overlap.");
            }
        }
    }

    /** Detects conflicting file operations: duplicate renames of a source, duplicate/colliding creates. */
    private void detectFileOperationConflicts(List<FileOperation> fileOps) {
        Set<String> renamedSources = new LinkedHashSet<>();
        Set<String> producedPaths = new LinkedHashSet<>();
        for (FileOperation op : fileOps) {
            switch (op.kind()) {
                case "rename" -> {
                    if (!renamedSources.add(op.oldPath())) {
                        throw new ComposeConflict("workspace edit conflict: '" + op.oldPath()
                                + "' is renamed by more than one operation.");
                    }
                    if (!producedPaths.add(op.newPath())) {
                        throw new ComposeConflict("workspace edit conflict: two operations produce the file '"
                                + op.newPath() + "'.");
                    }
                }
                case "create" -> {
                    if (!producedPaths.add(op.path())) {
                        throw new ComposeConflict("workspace edit conflict: two operations produce the file '"
                                + op.path() + "'.");
                    }
                }
                case "delete" -> {
                    // A delete that races a rename of the same source is caught by renamedSources below.
                    if (renamedSources.contains(op.path())) {
                        throw new ComposeConflict("workspace edit conflict: '" + op.path()
                                + "' is both renamed and deleted.");
                    }
                }
                default -> {
                    // Unknown kinds are passed through; the Python applier validates them.
                }
            }
        }
    }

    /**
     * Detects the rename-then-edit-old-path hazard: a file is renamed by one operation while another operation's text
     * edits are keyed to that file's OLD path under a *different* logical owner. Text edits keyed to a renamed file's old
     * path are legal (the applier applies edits to the source file BEFORE the rename moves it), so this only refuses when
     * the SAME old path is BOTH a rename source AND a create target — i.e. the path is being recreated under it, which the
     * applier cannot order.
     */
    private void detectRenameThenEditOldPath(List<PlannerSupport.TextEdit> edits, List<FileOperation> fileOps) {
        Set<String> renameSources = new LinkedHashSet<>();
        Set<String> createTargets = new LinkedHashSet<>();
        for (FileOperation op : fileOps) {
            if ("rename".equals(op.kind())) {
                renameSources.add(op.oldPath());
            } else if ("create".equals(op.kind())) {
                createTargets.add(op.path());
            }
        }
        for (String source : renameSources) {
            if (createTargets.contains(source)) {
                throw new ComposeConflict("workspace edit conflict: '" + source
                        + "' is renamed away and re-created by another operation in the same workspace.");
            }
        }
    }

    /** Groups edits by their absolute file path in first-appearance order (used by stats / preview helpers). */
    public static Map<Path, List<PlannerSupport.TextEdit>> groupByFile(List<PlannerSupport.TextEdit> edits) {
        Map<Path, List<PlannerSupport.TextEdit>> byFile = new LinkedHashMap<>();
        for (PlannerSupport.TextEdit edit : edits) {
            byFile.computeIfAbsent(edit.file().toAbsolutePath().normalize(), key -> new ArrayList<>()).add(edit);
        }
        return byFile;
    }
}
