package io.serena.javarefactor.v3.transformation;

import io.serena.javarefactor.protocol.JsonUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-process registry of open transformation workspaces (refactor-feature-plan-V3.md §1.1/§3).
 *
 * <p>Drives the {@code transformation.*} protocol: it runs the requested V3 operation planner(s) to build structured
 * {@link TransformationStep}s, composes them with {@link EditComposer}, validates the composed overlay ONCE via the
 * injected {@link TransformationValidator}, and registers a preview-ready {@link TransformationWorkspace}. The manager
 * never writes files — {@code apply} returns the authoritative validated edit for the Python applier (mirroring V2's
 * applySession), after enforcing the non-bypassable clean-revision guard.
 *
 * <p>All sidecar-internal concerns (model discovery, the package-private diagnostic validator, project-revision capture)
 * are injected as small callbacks so this class depends only on the {@code v3.transformation} package and {@code JsonUtil}.
 * Workspace ids are deterministic ({@code jwt-<seq>}) so tests are reproducible.
 */
public final class TransformationWorkspaceManager {

    /** Builds one operation's structured contribution. Returns a refusal JSON (never null) on a planner refusal. */
    @FunctionalInterface
    public interface StepPlanner {
        /** @return a {@link StepResult} carrying either a step or the canonical refusal JSON the planner produced. */
        StepResult plan(String operation, Map<String, Object> arguments);
    }

    /** Either a successfully planned step, or the canonical refusal JSON a planner emitted. */
    public record StepResult(TransformationStep step, String refusalJson) {
        public static StepResult of(TransformationStep step) {
            return new StepResult(step, null);
        }

        public static StepResult refused(String refusalJson) {
            return new StepResult(null, refusalJson);
        }

        public boolean isRefused() {
            return refusalJson != null;
        }
    }

    /** Builds the unvalidated composed preview JSON (shaped like a planner's accepted result) for the validator. */
    @FunctionalInterface
    public interface PreviewBuilder {
        String build(String semanticTargetJson,
                     List<io.serena.javarefactor.edits.PlannerSupport.TextEdit> edits,
                     List<io.serena.javarefactor.edits.ResponseBuilder.FileOperation> fileOperations,
                     List<String> warnings,
                     String riskFactsJson);
    }

    /** Captures an opaque project-revision token over the workspace's touched files (clean-revision guard input). */
    @FunctionalInterface
    public interface RevisionCapturer {
        String capture(List<String> touchedRelativePaths);
    }

    /**
     * Discovers the {@link io.serena.javarefactor.project.JavaProjectModel} for the current project, used by the
     * impact-report path ({@link #report}) to run the {@code impact.facts} analyzer over the workspace's touched files.
     * Returns {@code null} when no model can be discovered (no Java sources / unanalyzable project), in which case the
     * report returns a structured refusal rather than a skeleton.
     */
    @FunctionalInterface
    public interface ModelSupplier {
        io.serena.javarefactor.project.JavaProjectModel get();
    }

    private final Path projectRoot;
    private final int maxOpenWorkspaces;
    private final long ttlMinutes;
    private final StepPlanner stepPlanner;
    private final PreviewBuilder previewBuilder;
    private final TransformationValidator validator;
    private final RevisionCapturer revisionCapturer;
    private final ModelSupplier modelSupplier;
    private final EditComposer composer;

    private final Map<String, TransformationWorkspace> workspaces = new LinkedHashMap<>();
    private final Map<String, List<OperationRequest>> workspaceOperations = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    public TransformationWorkspaceManager(
            Path projectRoot,
            int maxOpenWorkspaces,
            long ttlMinutes,
            StepPlanner stepPlanner,
            PreviewBuilder previewBuilder,
            TransformationValidator validator,
            RevisionCapturer revisionCapturer,
            ModelSupplier modelSupplier) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.maxOpenWorkspaces = maxOpenWorkspaces;
        this.ttlMinutes = ttlMinutes;
        this.stepPlanner = stepPlanner;
        this.previewBuilder = previewBuilder;
        this.validator = validator;
        this.revisionCapturer = revisionCapturer;
        this.modelSupplier = modelSupplier;
        this.composer = new EditComposer(projectRoot);
    }

    // ── createWorkspace ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the requested operation(s), composes + validates them, and registers a preview-ready workspace.
     *
     * @param goal       optional human-readable goal
     * @param operations the operation(s) to compose; Phase 1 callers pass a single {operation, arguments}
     * @param nowMillis  the wall clock used for TTL bookkeeping (injected for deterministic tests)
     * @return the canonical {@code createWorkspace} response JSON (accepted with workspaceId, or a refusal JSON)
     */
    public String createWorkspace(String goal, List<OperationRequest> operations, long nowMillis) {
        evictExpired(nowMillis);
        if (operations.isEmpty()) {
            return refusal("transformation_no_operations", "createWorkspace requires at least one operation.");
        }
        if (workspaces.size() >= maxOpenWorkspaces) {
            return refusal("too_many_open_workspaces",
                    "The maximum of " + maxOpenWorkspaces + " open transformation workspaces is reached; cancel one first.");
        }

        List<TransformationStep> steps = new ArrayList<>();
        for (OperationRequest request : operations) {
            StepResult result = stepPlanner.plan(request.operation(), request.arguments());
            if (result.isRefused()) {
                // Propagate the planner's canonical refusal verbatim (the contract requires surfacing it unchanged).
                return result.refusalJson();
            }
            steps.add(result.step());
        }

        EditComposer.ComposedEdit composed;
        try {
            composed = composer.compose(steps);
        } catch (EditComposer.ComposeConflict conflict) {
            return refusal("workspace_edit_conflict", conflict.getMessage());
        }

        String previewJson = previewBuilder.build(
                composed.semanticTargetJson(), composed.edits(), composed.fileOperations(), composed.warnings(), composed.riskFactsJson());
        String validatedJson = validator.validate("transformation", previewJson);
        if (!isAccepted(validatedJson)) {
            // Validation refused (the composed after-state did not compile): surface that refusal verbatim.
            return validatedJson;
        }

        List<String> touched = touchedRelativePaths(composed);
        String revision = revisionCapturer.capture(touched);
        if (revision == null || revision.isBlank()) {
            // A failed/unavailable revision must refuse rather than store a null token that would weaken the
            // apply-time guard (design §20: fail-closed).
            return refusal("revision_capture_unavailable",
                    "Project revision could not be captured; createWorkspace refused to ensure apply-time drift detection remains reliable.");
        }

        String workspaceId = "jwt-" + sequence.incrementAndGet();
        TransformationWorkspace workspace = new TransformationWorkspace(
                workspaceId, goal, projectRoot, composed, revision, nowMillis);
        workspace.setValidatedAcceptedJson(validatedJson);
        workspaces.put(workspaceId, workspace);
        workspaceOperations.put(workspaceId, List.copyOf(operations));

        TransformationWorkspace.Stats stats = workspace.computeStats();
        String summary = summaryFor(goal, stats);
        return "{\"accepted\":true,\"workspaceId\":" + JsonUtil.quote(workspaceId)
                + ",\"summary\":" + JsonUtil.quote(summary)
                + ",\"status\":\"previewReady\""
                + ",\"stats\":" + stats.toJson()
                + ",\"warnings\":" + JsonUtil.array(composed.warnings()) + "}";
    }

    // ── preview ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Returns the composed, validated workspace edit plus stats (the authoritative accepted JSON, augmented). */
    public String addOperation(String workspaceId, OperationRequest operation, long nowMillis) {
        evictExpired(nowMillis);
        TransformationWorkspace existing = workspaces.get(workspaceId);
        if (existing == null) {
            return refusal("workspace_not_found", "transformation.addOperation requires an open workspaceId.");
        }
        if (existing.status() != TransformationWorkspace.Status.PREVIEW_READY) {
            return refusal("workspace_not_open", "transformation.addOperation requires a preview-ready workspace.");
        }
        List<OperationRequest> operations = new ArrayList<>(workspaceOperations.getOrDefault(workspaceId, List.of()));
        operations.add(operation);

        List<TransformationStep> steps = new ArrayList<>();
        for (OperationRequest request : operations) {
            StepResult result = stepPlanner.plan(request.operation(), request.arguments());
            if (result.isRefused()) {
                return result.refusalJson();
            }
            steps.add(result.step());
        }

        EditComposer.ComposedEdit composed;
        try {
            composed = composer.compose(steps);
        } catch (EditComposer.ComposeConflict conflict) {
            return refusal("workspace_edit_conflict", conflict.getMessage());
        }

        String acceptedJson = previewBuilder.build(
                composed.semanticTargetJson(), composed.edits(), composed.fileOperations(), composed.warnings(), composed.riskFactsJson());
        String validatedJson = validator.validate("transformation", acceptedJson);
        if (!isAccepted(validatedJson)) {
            return validatedJson;
        }
        String revision = revisionCapturer.capture(touchedRelativePaths(composed));
        if (revision == null || revision.isBlank()) {
            return refusal("project_revision_unavailable", "Project revision capture failed for transformation workspace.");
        }

        TransformationWorkspace workspace = new TransformationWorkspace(
                workspaceId, existing.goal(), projectRoot, composed, revision, existing.createdAtMillis());
        workspace.setValidatedAcceptedJson(validatedJson);
        workspaces.put(workspaceId, workspace);
        workspaceOperations.put(workspaceId, List.copyOf(operations));
        return augmentWithWorkspaceMeta(workspace, workspace.validatedAcceptedJson());
    }

    public String preview(String workspaceId, long nowMillis) {
        evictExpired(nowMillis);
        TransformationWorkspace workspace = workspaces.get(workspaceId);
        if (workspace == null) {
            return refusal("workspace_not_found", "No open transformation workspace '" + workspaceId + "'.");
        }
        return augmentWithWorkspaceMeta(workspace, workspace.validatedAcceptedJson());
    }

    // ── apply ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Enforces the non-bypassable clean-revision guard, then returns the authoritative validated edit for the Python
     * applier. The sidecar never writes files and therefore never terminalizes the workspace as applied; the Python
     * transactional applier owns the disk commit and retry semantics.
     *
     * @param expectedProjectRevision optional caller-pinned revision token to match the captured one
     */
    public String apply(String workspaceId, String expectedProjectRevision, long nowMillis) {
        evictExpired(nowMillis);
        TransformationWorkspace workspace = workspaces.get(workspaceId);
        if (workspace == null) {
            return refusal("workspace_not_found", "No open transformation workspace '" + workspaceId + "'.");
        }
        if (workspace.status() == TransformationWorkspace.Status.APPLIED) {
            return refusal("workspace_already_applied",
                    "Transformation workspace '" + workspaceId + "' was already applied.");
        }
        // Clean-revision guard (config require_clean_revision_on_apply is non-bypassable, design §20): the project must
        // not have drifted since the preview was composed. A pinned expectedProjectRevision is additionally enforced.
        // Fail-closed: a null/blank current revision means capture is unavailable, which must refuse — never skip the guard.
        String current = revisionCapturer.capture(touchedRelativePaths(workspace.composed()));
        if (current == null || current.isBlank()) {
            return refusal("revision_capture_unavailable",
                    "Project revision could not be captured at apply time; apply refused to ensure drift detection remains reliable.");
        }
        if (!current.equals(workspace.projectRevision())) {
            return refusal("stale_project_revision",
                    "The project changed since this workspace was previewed; re-create the workspace before applying.");
        }
        if (expectedProjectRevision != null && !expectedProjectRevision.isBlank()
                && !expectedProjectRevision.equals(workspace.projectRevision())) {
            return refusal("project_revision_mismatch",
                    "expectedProjectRevision does not match the revision captured when the workspace was created.");
        }

        return augmentWithWorkspaceMeta(workspace, workspace.validatedAcceptedJson());
    }

    // ── cancel ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Marks a prepared workspace applied after the external transactional disk commit has succeeded. */
    public String ackApplied(String workspaceId, long nowMillis) {
        evictExpired(nowMillis);
        TransformationWorkspace workspace = workspaces.get(workspaceId);
        if (workspace == null) {
            return refusal("workspace_not_found", "No open transformation workspace '" + workspaceId + "' to acknowledge.");
        }
        if (workspace.status() == TransformationWorkspace.Status.CANCELLED) {
            return refusal("workspace_cancelled", "Transformation workspace '" + workspaceId + "' was cancelled.");
        }
        if (workspace.status() != TransformationWorkspace.Status.APPLIED) {
            workspace.markApplied();
        }
        return "{\"accepted\":true,\"workspaceId\":" + JsonUtil.quote(workspaceId) + ",\"status\":\"applied\"}";
    }

    /** Evicts a workspace. Idempotent: a missing workspace yields a terminal refusal rather than an error. */
    public String cancel(String workspaceId, long nowMillis) {
        evictExpired(nowMillis);
        TransformationWorkspace removed = workspaces.remove(workspaceId);
        workspaceOperations.remove(workspaceId);
        if (removed == null) {
            return refusal("workspace_not_found",
                    "No open transformation workspace '" + workspaceId + "' to cancel.");
        }
        removed.markCancelled();
        return "{\"accepted\":true,\"workspaceId\":" + JsonUtil.quote(workspaceId) + ",\"status\":\"cancelled\"}";
    }

    // ── list ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Lists open workspaces (id, goal, status, stats). */
    public String list(long nowMillis) {
        evictExpired(nowMillis);
        String entries = workspaces.values().stream()
                .map(TransformationWorkspace::toListJson)
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"accepted\":true,\"workspaces\":" + entries + "}";
    }

    // ── report ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The authoritative whole-repo five-section impact report (refactor-feature-plan-V3.md §17) for an open workspace.
     *
     * <p>Every section is genuinely computed — there are NO {@code computed:false} placeholders. The injected
     * {@link ModelSupplier} discovers the project's {@link JavaProjectModel}; the {@code impact.facts} analyzer
     * ({@link io.serena.javarefactor.compiler.ImpactFactsAnalyzer}) is run over the workspace's composed touched paths to
     * produce provider-backed resource references, framework metadata, per-kind resource subtype counts, the exact
     * HIGH-confidence changed entries, the review-only reflection candidates (never auto-edited), and the per-build-model
     * suggested test commands. {@link TransformationImpactReport} maps those facts plus the composed stats and the
     * already-compiled javac before/after delta (source of {@code summary.newCompileErrors}) into the §17 shape. When no
     * model can be discovered (no Java sources / unanalyzable project), a structured refusal is returned rather than a
     * skeleton.
     */
    public String report(String workspaceId, long nowMillis) {
        evictExpired(nowMillis);
        TransformationWorkspace workspace = workspaces.get(workspaceId);
        if (workspace == null) {
            return refusal("workspace_not_found", "No open transformation workspace '" + workspaceId + "'.");
        }
        io.serena.javarefactor.project.JavaProjectModel model = modelSupplier == null ? null : modelSupplier.get();
        if (model == null) {
            return refusal("impact_report_model_unavailable",
                    "The impact report could not be computed because no Java project model could be discovered for '"
                            + workspaceId + "' (no Java sources or the project is not analyzable).");
        }
        List<String> touched = touchedRelativePaths(workspace.composed());
        String factsJson;
        try {
            factsJson = new io.serena.javarefactor.compiler.ImpactFactsAnalyzer().analyze(model, touched);
        } catch (Exception e) {
            return refusal("impact_report_failed",
                    "Computing the impact report for '" + workspaceId + "' failed: " + e.getMessage());
        }
        if (!isAccepted(factsJson)) {
            // The analyzer refused (e.g. no Java files): surface that refusal verbatim rather than a partial report.
            return factsJson;
        }
        // Consume the unified, cached transformation graph (refactor-feature-plan-V3.md §1.2): its javac-resolved
        // TestGraph supplies the authoritative likely-affected tests for every touched type, which the report merges
        // into its tests section. Building the graph routes through the shared ReachabilityGraphCache the facts analyzer
        // already populated, so this reuses — never duplicates — the project walk.
        Set<String> graphLikelyAffectedTests;
        try {
            graphLikelyAffectedTests = graphLikelyAffectedTests(model, touched);
        } catch (IllegalStateException ex) {
            return refusal("impact_report_graph_unavailable", ex.getMessage());
        }
        String operation = workspace.goal() == null || workspace.goal().isBlank()
                ? "transformation" : workspace.goal();
        String reportJson = TransformationImpactReport.build(
                factsJson, workspace.computeStats(), workspace.validatedAcceptedJson(), operation, workspace.warnings(),
                graphLikelyAffectedTests);
        return "{\"accepted\":true,\"workspaceId\":" + JsonUtil.quote(workspaceId)
                + ",\"report\":" + reportJson + "}";
    }

    /**
     * The unified transformation graph's likely-affected test types for the workspace's touched files: the union, over
     * every top-level type declared in a touched file, of the test types the graph's {@link
     * io.serena.javarefactor.v3.graph.TestGraph} records as referencing it. Returns an empty set (never fails the
     * report) when the graph cannot be built.
     */
    private static Set<String> graphLikelyAffectedTests(
            io.serena.javarefactor.project.JavaProjectModel model, List<String> touched) {
        Set<String> result = new LinkedHashSet<>();
        try {
            io.serena.javarefactor.v3.graph.TransformationGraph graph =
                    io.serena.javarefactor.v3.graph.GraphInvalidation.INSTANCE.get(model);
            Set<String> touchedPaths = new LinkedHashSet<>(touched);
            for (Map.Entry<String, String> entry : graph.symbols().typeToFile().entrySet()) {
                if (!touchedPaths.contains(entry.getValue())) {
                    continue;
                }
                for (io.serena.javarefactor.v3.graph.TestGraph.TestNode test
                        : graph.tests().testsReferencing(entry.getKey())) {
                    result.add(test.testFqn());
                }
            }
        } catch (Exception ignored) {
            // V3 impact reports are complete-or-refused: graph-backed test impact must not silently degrade to zero.
            throw new IllegalStateException("graph_unavailable");
        }
        return result;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** An operation request inside a createWorkspace call. */
    public record OperationRequest(String operation, Map<String, Object> arguments) {
        public OperationRequest {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    public int openCount() {
        return workspaces.size();
    }

    private void evictExpired(long nowMillis) {
        if (ttlMinutes <= 0) {
            return;
        }
        long ttlMillis = ttlMinutes * 60_000L;
        workspaces.entrySet().removeIf(entry -> nowMillis - entry.getValue().createdAtMillis() > ttlMillis);
    }

    private List<String> touchedRelativePaths(EditComposer.ComposedEdit composed) {
        java.util.LinkedHashSet<String> touched = new java.util.LinkedHashSet<>();
        for (var op : composed.fileOperations()) {
            if (op.path() != null) {
                touched.add(op.path());
            }
            if (op.oldPath() != null) {
                touched.add(op.oldPath());
            }
            if (op.newPath() != null) {
                touched.add(op.newPath());
            }
        }
        // Text edits are keyed by absolute path; the revision capturer relativizes, so we pass the absolute file paths
        // via their string form only when the composer already produced relative file-op paths. Edits' files are covered
        // by their owning file operations for moved files; for in-place edits the capturer also accepts absolute paths.
        for (var edit : composed.edits()) {
            Path path = edit.file().toAbsolutePath().normalize();
            touched.add(path.startsWith(projectRoot)
                    ? projectRoot.relativize(path).toString().replace('\\', '/')
                    : path.toString());
        }
        return new ArrayList<>(touched);
    }

    private String augmentWithWorkspaceMeta(TransformationWorkspace workspace, String acceptedJson) {
        // Splice the workspace id + stats into the accepted JSON object (the validator returns a complete JSON object).
        String trimmed = acceptedJson.trim();
        if (trimmed.endsWith("}")) {
            String body = trimmed.substring(0, trimmed.length() - 1);
            return body
                    + ",\"workspaceId\":" + JsonUtil.quote(workspace.workspaceId())
                    + ",\"workspaceStatus\":" + JsonUtil.quote(workspace.status().wire())
                    + ",\"stats\":" + workspace.computeStats().toJson() + "}";
        }
        return acceptedJson;
    }

    private static boolean isAccepted(String json) {
        try {
            Object value = io.serena.javarefactor.protocol.Json.parseObject(json).get("accepted");
            return value instanceof Boolean accepted && accepted;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String summaryFor(String goal, TransformationWorkspace.Stats stats) {
        StringBuilder builder = new StringBuilder();
        if (goal != null && !goal.isBlank()) {
            builder.append(goal).append(": ");
        }
        builder.append(stats.javaFilesMoved()).append(" Java file(s) moved, ")
                .append(stats.javaFilesEdited()).append(" edited, ")
                .append(stats.textEdits()).append(" text edit(s), ")
                .append(stats.fileOperations()).append(" file operation(s).");
        return builder.toString();
    }

    private static String refusal(String code, String message) {
        return "{\"accepted\":false,\"refusal\":{\"code\":" + JsonUtil.quote(code)
                + ",\"message\":" + JsonUtil.quote(message) + "}}";
    }
}
