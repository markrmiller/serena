package io.serena.javarefactor.v3.deletion;

import io.serena.javarefactor.ast.RefactorAnalysisResult;
import io.serena.javarefactor.compiler.DanglingImports;
import io.serena.javarefactor.compiler.ReachabilityGraph;
import io.serena.javarefactor.compiler.ReachabilityGraphCache;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.v3.resources.ResourceEdit;
import io.serena.javarefactor.v3.resources.ResourcePlanner;
import io.serena.javarefactor.v3.resources.ResourceQuery;
import io.serena.javarefactor.v3.resources.ResourceReference;
import io.serena.javarefactor.v3.resources.ResourceReferenceKind;
import io.serena.javarefactor.v3.resources.ResourceScanScope;
import io.serena.javarefactor.v3.frameworks.FrameworkParticipationCoordinator;
import io.serena.javarefactor.v3.frameworks.SymbolChange;
import io.serena.javarefactor.v3.transformation.TransformationStep;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * V3 compiler-backed propagating safe delete (refactor-feature-plan-V3.md §7.1–§7.4).
 *
 * <p>Given a set of explicitly-requested deletion roots, this planner builds a {@link ReachabilityGraph} over the whole
 * project, deletes each requested symbol that the API-boundary/entry-point policy permits, then iterates to a fixed
 * point pulling in every additional symbol that is referenced ONLY by already-deleted symbols (the cascade). Any symbol
 * a deleted symbol referenced but that is still reachable from a non-deleted symbol is reported as BLOCKED with the live
 * referrer named. The result is the graph-shaped {@code deletePlan {requested, cascade, blocked}} (NOT a flat edit
 * list), accompanied by a {@code workspaceEdit} that removes the deletable declarations (whole-file deletes for sole
 * top-level types, declaration-range deletes otherwise) and rewrites {@code META-INF/services} provider entries for any
 * deleted service implementation. The composed edit is handed to the sidecar's authoritative before/after javac
 * validator by the caller, so a cascade that would not compile is rejected.
 */
// Non-final so a test can override the single file-read seam (readDeclarationSource); all real logic stays here.
public class PropagatingSafeDeletePlanner {

    /** Framework SPI participant (refactor-feature-plan-V3.md §16): contributes deletion vetoes + review warnings. */
    private final FrameworkParticipationCoordinator frameworkParticipation = new FrameworkParticipationCoordinator();

    /** One requested deletion root, identified either by canonical symbol key or by source position. */
    public record RootSpec(String symbol, String relativePath, Integer line, Integer column) {
        public static RootSpec ofSymbol(String symbol) {
            return new RootSpec(symbol, null, null, null);
        }

        public static RootSpec ofPosition(String relativePath, int line, int column) {
            return new RootSpec(null, relativePath, line, column);
        }
    }

    /** Caller options mirroring the {@code java_propagate_safe_delete} tool signature (§4.2). */
    public record Options(boolean deletePrivateOnly, boolean includeTests, boolean includeResources,
            int maxCascadeDepth) {
        public static Options defaults() {
            return new Options(true, false, true, 5);
        }
    }

    /**
     * A precondition refusal carrying its registry code for the step-planner dispatch.
     *
     * <p>Canonical codes: {@code no_roots} (no deletion root supplied), {@code no_sources} (no Java sources to analyse),
     * {@code delete_source_unreadable} (a symbol admitted to the delete set has an unreadable source file, so the
     * declaration edit cannot be emitted; the whole delete is refused rather than claim a deletion it cannot perform).
     */
    private static final class DeletionRefusal extends RuntimeException
            implements io.serena.javarefactor.v3.packages.CodedRefusal {
        private final String code;

        DeletionRefusal(String code, String message) {
            super(message);
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }
    }

    /**
     * The pre-serialization structured result of a propagating safe delete: the composed workspace edit (declaration/
     * import/resource text edits + whole-file delete operations + emptied directories), the merged warnings, and the
     * already-rendered {@code deletePlan}/{@code stats} JSON fragments the standalone endpoint reports. Shared by {@link
     * #plan} (standalone JSON) and {@link #planStep} (workspace composition) so both carry the identical edits/deletes.
     */
    private record DeletionResult(WorkspaceEdit workspaceEdit, List<String> allWarnings, String deletePlanJson,
            String statsJson, List<String> incompleteResourceFiles) {
    }

    public String plan(JavaProjectModel model, List<RootSpec> roots, Options options) throws IOException {
        DeletionResult result;
        try {
            result = compute(model, roots, options);
        } catch (DeletionRefusal refusal) {
            return PlannerSupport.refusalJson(refusal.code(), refusal.getMessage());
        }
        WorkspaceEdit workspaceEdit = result.workspaceEdit();
        // Blocker B4 / story R06: when any in-scope resource scan (service-loader, Spring bean, dangling-reference)
        // could not be fully examined, the delete cannot rule out an external resource reference to a deleted type, so
        // it must escalate to needs_review. Emit BOTH the existing top-level boolean (resourceScanIncomplete) AND a
        // structured riskFacts.analysisIncomplete array (shared contract 1, exact key names); CanonicalEnvelope
        // .classifyRisk escalates on either, blocking SAFE auto-apply of the delete.
        List<String> incompleteResourceFiles = result.incompleteResourceFiles();
        boolean resourceScanIncomplete = !incompleteResourceFiles.isEmpty();
        String riskFactsJson = "";
        if (resourceScanIncomplete) {
            List<String> analysisIncomplete = new ArrayList<>();
            for (String file : incompleteResourceFiles) {
                analysisIncomplete.add("Resource scan incomplete: '" + file
                        + "' could not be examined for references to the deleted types.");
            }
            riskFactsJson = "\"riskFacts\":{\"analysisIncomplete\":" + JsonUtil.array(analysisIncomplete) + "},";
        }
        return "{"
                + "\"accepted\":true,"
                + "\"operation\":\"propagateSafeDelete\","
                + "\"deletePlan\":" + result.deletePlanJson() + ","
                + "\"workspaceEdit\":{"
                + "\"changes\":" + workspaceEdit.changesJson() + ","
                + "\"fileOperations\":" + workspaceEdit.fileOperationsJson()
                + "},"
                + "\"removedDirectories\":" + JsonUtil.array(workspaceEdit.removedDirectories()) + ","
                + "\"resourceScanIncomplete\":" + resourceScanIncomplete + ","
                + riskFactsJson
                + "\"warnings\":" + PlannerSupport.warningsJson(result.allWarnings()) + ","
                + "\"stats\":" + result.statsJson()
                + "}";
    }

    /**
     * The structured workspace step (refactor-feature-plan-V3.md §3) for composing the propagating delete into a
     * transformation workspace: the declaration/import/resource edits plus the whole-file deletes as real {@link
     * FileOperation#delete} operations (so the composer's file-op conflict check and the applier's pre-delete hash
     * precondition both see structured deletes, not JSON strings). Because {@link TransformationStep} has no slot for
     * {@code removedDirectories}, each emptied package directory is surfaced as a warning so the composed-apply caller
     * still learns the cascade left it empty (the directory itself is pruned post-apply, exactly as in the standalone
     * path). Precondition refusals propagate as {@link DeletionRefusal} (a {@code CodedRefusal}) for the caller to map.
     */
    public TransformationStep planStep(JavaProjectModel model, List<RootSpec> roots, Options options)
            throws IOException {
        DeletionResult result = compute(model, roots, options);
        WorkspaceEdit workspaceEdit = result.workspaceEdit();
        List<String> warnings = new ArrayList<>(result.allWarnings());
        for (String directory : workspaceEdit.removedDirectories()) {
            warnings.add("Package directory '" + directory + "' is emptied by this delete and will be pruned after the"
                    + " workspace is applied.");
        }
        return new TransformationStep(
                "propagateSafeDelete", workspaceEdit.edits(), workspaceEdit.fileOperations(), warnings,
                "{\"operation\":\"propagateSafeDelete\"}");
    }

    private DeletionResult compute(JavaProjectModel model, List<RootSpec> roots, Options options) throws IOException {
        if (roots == null || roots.isEmpty()) {
            throw new DeletionRefusal("no_roots",
                    "Propagating safe delete requires at least one deletion root.");
        }
        String representative = firstJavaRelative(model);
        if (representative == null) {
            throw new DeletionRefusal("no_sources",
                    "No Java source files are available for deletion analysis.");
        }
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();
        Charset charset = SemanticIndex.charsetOf(model);
        // When delete_private_only is set, public/protected API stays a root (never auto-deletable); otherwise the
        // caller has opted into deleting public API too, so visibility alone no longer roots a symbol.
        boolean honorPublicApi = options.deletePrivateOnly();

        try (SemanticIndex index = SemanticIndex.open(model, representative)) {
            // Whole-project revision-keyed memoization (see ReachabilityGraphCache); a content edit to any source file
            // (touched or not) changes the key and forces a rebuild, so a stale graph can never be served.
            String projectKey = ReachabilityGraphCache.projectKey(model);
            boolean includeTests = options.includeTests();
            ReachabilityGraph graph = ReachabilityGraphCache.INSTANCE.get(projectKey, includeTests,
                    () -> ReachabilityGraph.build(index, model, includeTests));

            List<String> requestedKeys = new ArrayList<>();
            Map<String, String> blocked = new LinkedHashMap<>();
            Set<String> deleted = new LinkedHashSet<>();
            List<String> frameworkWarnings = new ArrayList<>();

            // Resolve and admit each requested root, blocking the ones the policy/boundary forbids.
            for (RootSpec root : roots) {
                String key = resolve(index, graph, root);
                if (key == null) {
                    String label = root.symbol() != null ? root.symbol()
                            : root.relativePath() + ":" + root.line() + ":" + root.column();
                    requestedKeys.add(label);
                    blocked.putIfAbsent(label, "No deletable Java declaration resolved for this root.");
                    continue;
                }
                requestedKeys.add(key);
                ReachabilityGraph.Node node = graph.node(key);
                // Framework participation (refactor-feature-plan-V3.md §16): ask the framework plugins whether deleting
                // this type should be vetoed and gather their review-required warnings. Participation is purely additive:
                // it never replaces the reachability-graph's own framework/structural block, it only enriches the block
                // reason with the owning framework's detail (e.g. JPA "persistence entry point") and contributes
                // warnings. It can also block a type the graph did not already root, but never makes deletion more
                // aggressive. Participation runs per requested top-level type over the project's exact compiler-resolved
                // annotations.
                String frameworkBlock = null;
                if (node.kind() == ReachabilityGraph.NodeKind.TYPE && node.topLevelType()) {
                    FrameworkParticipationCoordinator.Result participation = frameworkParticipation
                            .participate(model, SymbolChange.safeDelete(node.ownerTypeFqn()));
                    frameworkWarnings.addAll(participation.warnings());
                    frameworkBlock = participation.blockReasonFor(node.ownerTypeFqn());
                }
                if (node.isCascadeRoot(honorPublicApi)) {
                    String reason = "Refused: " + node.rootReason(honorPublicApi)
                            + (node.publicApi() && honorPublicApi
                                    ? " (pass delete_private_only=false to allow deleting public API)" : "");
                    if (frameworkBlock != null) {
                        reason = reason + " — " + frameworkBlock;
                    }
                    blocked.putIfAbsent(key, reason);
                    continue;
                }
                if (frameworkBlock != null) {
                    // A framework plugin vetoes a type the reachability graph did not already root (defense in depth).
                    blocked.putIfAbsent(key, "Refused: " + frameworkBlock);
                    continue;
                }
                deleted.add(key);
            }

            // Fixed-point cascade: a symbol becomes deletable once EVERY symbol that references it is already deleted.
            Map<String, String> cascade = new LinkedHashMap<>();
            boolean changed = true;
            int depth = 0;
            while (changed && depth < Math.max(1, options.maxCascadeDepth())) {
                changed = false;
                depth++;
                for (ReachabilityGraph.Node node : graph.nodes()) {
                    String key = node.key();
                    if (deleted.contains(key) || node.isCascadeRoot(honorPublicApi)) {
                        continue;
                    }
                    Set<String> incoming = graph.incoming(key);
                    if (incoming.isEmpty()) {
                        continue; // unreferenced symbols are dead-code candidates, not cascade targets
                    }
                    if (deleted.containsAll(incoming)) {
                        deleted.add(key);
                        cascade.put(key, cascadeReason(graph, incoming));
                        changed = true;
                    }
                }
            }

            // Blocked-by-live-referrer: symbols a deleted symbol referenced but that a non-deleted symbol still needs.
            for (ReachabilityGraph.Node node : graph.nodes()) {
                String key = node.key();
                if (deleted.contains(key) || blocked.containsKey(key) || node.isCascadeRoot(honorPublicApi)) {
                    continue;
                }
                Set<String> incoming = graph.incoming(key);
                boolean referencedByDeleted = incoming.stream().anyMatch(deleted::contains);
                if (!referencedByDeleted) {
                    continue;
                }
                String liveReferrer = pickLiveReferrer(graph, incoming, deleted);
                if (liveReferrer != null) {
                    blocked.put(key, "Referenced by " + describe(graph.node(liveReferrer)));
                }
            }

            // When public-API deletion is permitted (!honorPublicApi), warn for each deleted public/protected symbol
            // so callers know the operation crosses the public-API boundary and requires review.
            List<String> publicApiWarnings = new ArrayList<>();
            if (!honorPublicApi) {
                for (String key : deleted) {
                    ReachabilityGraph.Node node = graph.node(key);
                    if (node != null && node.publicApi()) {
                        publicApiWarnings.add("Deleting '" + key
                                + "' crosses the public-API boundary — confirm it is not part of a published"
                                + " interface before applying.");
                    }
                }
            }

            WorkspaceEdit workspaceEdit =
                    computeEdit(graph, deleted, projectRoot, model, charset, options.includeResources());

            List<String> allWarnings = new ArrayList<>(publicApiWarnings);
            allWarnings.addAll(frameworkWarnings);
            allWarnings.addAll(workspaceEdit.warnings());

            String deletePlanJson = "{"
                    + "\"requested\":" + JsonUtil.array(requestedKeys) + ","
                    + "\"cascade\":" + symbolReasonArray(cascade) + ","
                    + "\"blocked\":" + symbolReasonArray(blocked)
                    + "}";

            int autoDeleted = deleted.size() - requestedKeys.stream().filter(deleted::contains).toList().size();
            String statsJson = "{"
                    + "\"deleted\":" + deleted.size() + ","
                    + "\"cascade\":" + cascade.size() + ","
                    + "\"blocked\":" + blocked.size() + ","
                    + "\"autoDeleted\":" + Math.max(0, autoDeleted)
                    + "}";

            return new DeletionResult(workspaceEdit, allWarnings, deletePlanJson, statsJson,
                    workspaceEdit.incompleteResourceFiles());
        }
    }

    // ── cascade helpers ────────────────────────────────────────────────────────────────────────────────────────────

    private static String cascadeReason(ReachabilityGraph graph, Set<String> incoming) {
        for (String referrer : incoming) {
            ReachabilityGraph.Node node = graph.node(referrer);
            if (node != null) {
                return "Only referenced by deleted " + describe(node);
            }
        }
        return "Only referenced by deleted symbols";
    }

    private static String pickLiveReferrer(ReachabilityGraph graph, Set<String> incoming, Set<String> deleted) {
        String fallback = null;
        for (String referrer : incoming) {
            if (deleted.contains(referrer)) {
                continue;
            }
            ReachabilityGraph.Node node = graph.node(referrer);
            if (node == null) {
                continue;
            }
            if (node.publicApi()) {
                return referrer; // prefer naming a public/protected referrer — the clearest reason to keep the symbol
            }
            if (fallback == null) {
                fallback = referrer;
            }
        }
        return fallback;
    }

    private static String describe(ReachabilityGraph.Node node) {
        String visibility = node.publicApi() ? "public " : "";
        return visibility + node.kindLabel() + " " + node.key();
    }

    // ── edit computation ───────────────────────────────────────────────────────────────────────────────────────────

    private record WorkspaceEdit(List<PlannerSupport.TextEdit> edits, List<FileOperation> fileOperations,
            List<String> warnings, List<String> removedDirectories, List<String> incompleteResourceFiles,
            Path projectRoot) {
        String changesJson() throws IOException {
            return PlannerSupport.changesJson(projectRoot, edits);
        }

        /**
         * The {@code fileOperations[]} array, byte-for-byte identical to the V1 {@code deleteFileOp} wire shape
         * ({@code {"kind":"delete","path":…,"oldSha256":…}}). Serialized here (not via the package-private
         * {@code FileOperation.toJson}) so the standalone endpoint's output is unchanged.
         */
        String fileOperationsJson() {
            List<String> objects = new ArrayList<>();
            for (FileOperation op : fileOperations) {
                objects.add("{\"kind\":\"delete\",\"path\":" + JsonUtil.quote(op.path())
                        + ",\"oldSha256\":" + JsonUtil.quote(op.oldSha256()) + "}");
            }
            return "[" + String.join(",", objects) + "]";
        }
    }

    private WorkspaceEdit computeEdit(ReachabilityGraph graph, Set<String> deleted, Path projectRoot,
            JavaProjectModel model, Charset charset, boolean includeResources) throws IOException {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        List<FileOperation> fileOps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        // Story R06 / blocker B4: every in-scope resource file the delete's resource scan could not examine
        // (unreadable or over-cap META-INF/services entries, bean XML, dangling-reference scans). Collected here so
        // BOTH the service-loader rewrite is withheld AND the incompleteness is propagated to the result JSON
        // (resourceScanIncomplete:true + riskFacts.analysisIncomplete) so classifyRisk escalates the whole delete to
        // needs_review — an external resource reference the scan could not read must never be silently ruled out.
        LinkedHashSet<String> incompleteResourceFiles = new LinkedHashSet<>();

        // Group top-level types per file; a file whose every top-level type is deleted is removed wholesale.
        Map<Path, List<ReachabilityGraph.Node>> topLevelByFile = new LinkedHashMap<>();
        for (ReachabilityGraph.Node node : graph.nodes()) {
            if (node.kind() == ReachabilityGraph.NodeKind.TYPE && node.topLevelType()) {
                topLevelByFile.computeIfAbsent(node.file(), k -> new ArrayList<>()).add(node);
            }
        }
        Set<Path> filesToDelete = new LinkedHashSet<>();
        for (Map.Entry<Path, List<ReachabilityGraph.Node>> entry : topLevelByFile.entrySet()) {
            boolean allDeleted = entry.getValue().stream().allMatch(node -> deleted.contains(node.key()));
            if (allDeleted && !entry.getValue().isEmpty()) {
                filesToDelete.add(entry.getKey());
            }
        }

        Map<Path, String> sourceCache = new HashMap<>();
        Set<String> deletedTypeFqns = new LinkedHashSet<>();
        for (String key : deleted) {
            ReachabilityGraph.Node node = graph.node(key);
            if (node == null) {
                continue;
            }
            if (node.kind() == ReachabilityGraph.NodeKind.TYPE && node.topLevelType()) {
                deletedTypeFqns.add(node.ownerTypeFqn());
            }
            if (isSubsumed(graph, node, deleted)) {
                continue; // an enclosing deleted type already removes this declaration
            }
            if (node.kind() == ReachabilityGraph.NodeKind.TYPE && node.topLevelType()
                    && filesToDelete.contains(node.file())) {
                continue; // handled by the whole-file delete below
            }
            String source = sourceCache.computeIfAbsent(node.file(), file -> readDeclarationSource(file, charset));
            if (source == null) {
                // R08: this node was ADMITTED to `deleted` (it counts in stats.deleted), but its source cannot be read,
                // so no declaration edit can be emitted. Warning-and-skipping here would leave stats claiming a deletion
                // the plan never performs — exactly the internal untruthfulness R08 forbids. Refuse the whole delete: an
                // unaccepted plan trivially keeps stats consistent with the (empty) emitted edit and avoids the
                // cascade-recomputation hazard of demoting one node to blocked after the fixed point already ran.
                throw new DeletionRefusal("delete_source_unreadable",
                        "Cannot read source for " + PlannerSupport.relative(projectRoot, node.file())
                                + " to delete " + node.key()
                                + "; refusing the delete so the plan never claims a deletion it cannot perform.");
            }
            int[] span = SemanticIndex.expandDeclarationRangeForDelete(source, node.declStart(), node.declEnd());
            edits.add(new PlannerSupport.TextEdit(node.file(), span[0], span[1], "", "DECLARATION"));
        }

        for (Path file : filesToDelete) {
            String relative = PlannerSupport.relative(projectRoot, file);
            fileOps.add(FileOperation.delete(relative, PlannerSupport.sha256(file)));
        }

        // Strip imports of the deleted types from every surviving file. The reachability graph models usages, not import
        // statements, so a stale single-type import of a now-deleted type keeps the type deletable yet would become a
        // "cannot find symbol" error and reject the whole delete; removing it is exact and safe (a file that USED the
        // type would have pinned it live and it would not be deletable). Always runs — this is core Java cleanup, not a
        // resource concern gated by include_resources.
        edits.addAll(removeDanglingImports(model, charset, deletedTypeFqns, filesToDelete, sourceCache));

        if (includeResources && !deletedTypeFqns.isEmpty()) {
            edits.addAll(rewriteServiceLoaderFiles(projectRoot, charset, deletedTypeFqns, warnings,
                    incompleteResourceFiles));
            edits.addAll(removeUnambiguousBeanDefinitions(projectRoot, model, deletedTypeFqns, warnings,
                    incompleteResourceFiles));
            warnings.addAll(danglingResourceReferenceWarnings(projectRoot, model, deletedTypeFqns,
                    incompleteResourceFiles));
        }

        // Plan §7.3 step 7 / §19.2: package directories that the cascade has emptied are removed too.
        List<String> removedDirectories = emptyPackageDirectories(model, projectRoot, filesToDelete);

        return new WorkspaceEdit(edits, fileOps, warnings, removedDirectories,
                new ArrayList<>(incompleteResourceFiles), projectRoot);
    }

    /**
     * The source-package directories that become empty as a direct result of this delete (plan §7.3 step 7, §19.2
     * directory cleanup). A directory qualifies only when, after the planned whole-file deletes in {@code filesToDelete}
     * are removed, it (and recursively every directory below it) holds NO surviving file of any kind. Honoring §19.2:
     *
     * <ul>
     *   <li>Only directories strictly UNDER a known {@link SourceSet#sourceRoots() source root} are candidates; a source
     *       root itself is never removed, and nothing outside a source root is touched.</li>
     *   <li>Generated-source roots and anything beneath them are excluded — generated-source directories are never
     *       removed by default.</li>
     *   <li>A directory that still contains any file the delete did not remove (another {@code .java} source, a
     *       resource, a sibling type kept live by the public-API / framework-root block) keeps the directory non-empty,
     *       so it is left in place. The existing block contract is therefore honored for free: a blocked type's file is
     *       never in {@code filesToDelete}, so its package never reports as empty.</li>
     * </ul>
     *
     * <p>This is surfaced as plan metadata ({@code removedDirectories}) rather than a {@code workspaceEdit} file
     * operation: the text/file-op edit model removes files, and the now-empty directories are pruned as the final
     * post-delete cleanup step (§19.3 step 6) once those files are gone.
     */
    private static List<String> emptyPackageDirectories(JavaProjectModel model, Path projectRoot,
            Set<Path> filesToDelete) {
        if (filesToDelete.isEmpty()) {
            return List.of();
        }
        Set<Path> deletedAbs = new LinkedHashSet<>();
        for (Path file : filesToDelete) {
            deletedAbs.add(file.toAbsolutePath().normalize());
        }
        // Known source roots (candidate-bounding) and generated-source roots (excluded), all normalized.
        Set<Path> sourceRoots = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                sourceRoots.add(root.toAbsolutePath().normalize());
            }
        }
        Set<Path> generatedRoots = new LinkedHashSet<>();
        for (Path root : model.generatedSourceRoots()) {
            generatedRoots.add(root.toAbsolutePath().normalize());
        }

        // Walk up from each deleted file's directory toward (but never including) its source root, collecting every
        // intermediate package directory as a candidate. Visiting the parent chain catches a whole nested package
        // subtree collapsing at once (e.g. deleting com/acme/app/internal/*.java empties internal AND, if it was the
        // last child, app).
        Set<Path> candidates = new LinkedHashSet<>();
        for (Path deleted : deletedAbs) {
            Path sourceRoot = enclosingSourceRoot(deleted, sourceRoots, generatedRoots);
            if (sourceRoot == null) {
                continue;
            }
            Path dir = deleted.getParent();
            while (dir != null && dir.startsWith(sourceRoot) && !dir.equals(sourceRoot)) {
                candidates.add(dir);
                dir = dir.getParent();
            }
        }

        List<Path> empties = new ArrayList<>();
        for (Path candidate : candidates) {
            if (isEmptiedDirectory(candidate, deletedAbs)) {
                empties.add(candidate);
            }
        }
        // Stable, shallowest-first ordering so a parent package precedes its now-empty subpackages in the output.
        empties.sort((left, right) -> {
            int byDepth = Integer.compare(left.getNameCount(), right.getNameCount());
            return byDepth != 0 ? byDepth : left.toString().compareTo(right.toString());
        });
        List<String> relative = new ArrayList<>();
        for (Path dir : empties) {
            relative.add(PlannerSupport.relative(projectRoot, dir));
        }
        return relative;
    }

    /**
     * The normalized source root that contains {@code file}, or null when {@code file} is not under any known source
     * root or is under a generated-source root (which is never pruned). When source roots nest, the deepest containing
     * root wins so the candidate walk stops at the most specific package boundary.
     */
    private static Path enclosingSourceRoot(Path file, Set<Path> sourceRoots, Set<Path> generatedRoots) {
        for (Path generated : generatedRoots) {
            if (file.startsWith(generated)) {
                return null;
            }
        }
        Path best = null;
        for (Path root : sourceRoots) {
            if (file.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) {
                best = root;
            }
        }
        return best;
    }

    /**
     * Whether {@code directory} holds no file that survives the delete: every regular file beneath it (at any depth) is
     * in {@code deletedAbs}. A directory containing only subdirectories whose files are all deleted is itself emptied.
     * Read failures fail safe — an unreadable directory is treated as non-empty, so it is never reported for removal.
     */
    private static boolean isEmptiedDirectory(Path directory, Set<Path> deletedAbs) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk
                    .filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .allMatch(deletedAbs::contains);
        } catch (IOException error) {
            return false;
        }
    }

    /**
     * Removes single-type (and matching static) imports of the deleted types from every surviving source file. Files that
     * are themselves being deleted wholesale are skipped. The cheap {@code contains} pre-filter avoids parsing files that
     * could not possibly name a deleted FQN; the actual spans come from a real javac parse via {@link DanglingImports},
     * so the match is exact and overlay-safe.
     */
    private static List<PlannerSupport.TextEdit> removeDanglingImports(JavaProjectModel model, Charset charset,
            Set<String> deletedTypeFqns, Set<Path> filesToDelete, Map<Path, String> sourceCache) {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        if (deletedTypeFqns.isEmpty()) {
            return edits;
        }
        Set<Path> deleteAbs = new LinkedHashSet<>();
        for (Path file : filesToDelete) {
            deleteAbs.add(file.toAbsolutePath().normalize());
        }
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path javaFile : sourceSet.javaFiles()) {
                Path file = javaFile.toAbsolutePath().normalize();
                if (deleteAbs.contains(file)) {
                    continue;
                }
                String source = sourceCache.computeIfAbsent(file, f -> readSource(f, charset));
                if (source == null) {
                    continue;
                }
                boolean mentionsDeletedType = false;
                for (String fqn : deletedTypeFqns) {
                    if (source.contains(fqn)) {
                        mentionsDeletedType = true;
                        break;
                    }
                }
                if (!mentionsDeletedType) {
                    continue;
                }
                for (long[] span : DanglingImports.spansToRemove(source, deletedTypeFqns)) {
                    edits.add(new PlannerSupport.TextEdit(file, span[0], span[1], "", "IMPORT"));
                }
            }
        }
        return edits;
    }

    /**
     * Read-only resource participation (refactor-feature-plan-V3.md §15): consults the unified {@link ResourcePlanner}
     * for every reference to a deleted type's FQN that survives the delete. {@code META-INF/services} provider lines are
     * already rewritten by {@link #rewriteServiceLoaderFiles}, so those are skipped; everything else (Spring/JPA/Jackson
     * XML, structured-text config, reflective candidates) is surfaced as a warning so the caller knows a string-encoded
     * reference would be left dangling. This only makes deletion <em>more</em> conservative — it adds no edits.
     */
    private static List<PlannerSupport.TextEdit> removeUnambiguousBeanDefinitions(Path projectRoot,
            JavaProjectModel model, Set<String> deletedTypeFqns, List<String> warnings,
            Set<String> incompleteResourceFiles) throws IOException {
        ResourcePlanner.BeanRemovalPlan plan =
                new ResourcePlanner(projectRoot, model).beanRemovalEdits(deletedTypeFqns, ResourceScanScope.all());
        warnings.addAll(plan.warnings());
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        // Story R06 gate: an incomplete resource scan means the delete's bean participation could not be fully
        // determined, so the unambiguous-bean removals are NOT auto-applied (they would be written from an incomplete
        // picture); they are withheld and the incomplete files are surfaced so the delete stays review-required.
        if (!plan.completeness().isComplete()) {
            incompleteResourceFiles.addAll(plan.completeness().incompleteFiles());
            warnings.add("Resource scan was incomplete (" + String.join(", ", plan.completeness().incompleteFiles())
                    + "); Spring <bean> removals for the deleted types were NOT auto-applied and must be reviewed.");
            return edits;
        }
        for (ResourceEdit edit : plan.edits()) {
            edits.add(new PlannerSupport.TextEdit(edit.file(), edit.startOffset(), edit.endOffset(),
                    edit.newText(), "RESOURCE"));
        }
        return edits;
    }

    private static List<String> danglingResourceReferenceWarnings(Path projectRoot, JavaProjectModel model,
            Set<String> deletedTypeFqns, Set<String> incompleteResourceFiles) throws IOException {
        List<ResourceQuery> queries = new ArrayList<>();
        for (String fqn : deletedTypeFqns) {
            queries.add(new ResourceQuery(fqn, false));
        }
        List<String> warnings = new ArrayList<>();
        // Blocker B4 / story R06: take the completeness-checked scan so an in-scope resource file the walk could not
        // read (unreadable or over the size cap) is NOT silently dropped. Such a file might still reference a deleted
        // type, so its incompleteness is surfaced and propagated to the result (forcing needs_review) rather than
        // letting the delete classify "safe" on an unread file.
        ResourcePlanner.ReferenceScan scan =
                new ResourcePlanner(projectRoot, model).referencesToChecked(queries, ResourceScanScope.all());
        if (!scan.completeness().isComplete()) {
            incompleteResourceFiles.addAll(scan.completeness().incompleteFiles());
            warnings.add("Resource scan was incomplete (" + String.join(", ", scan.completeness().incompleteFiles())
                    + "); these resource files could not be examined for references to the deleted types and must be"
                    + " reviewed before applying this delete.");
        }
        for (ResourceReference reference : scan.references()) {
            if (reference.kind() == ResourceReferenceKind.SERVICE_LOADER_PROVIDER) {
                continue; // provider entries for deleted implementations are removed by the service-loader rewrite above
            }
            if (reference.kind() == ResourceReferenceKind.SPRING_BEAN_CLASS) {
                continue; // handled by removeUnambiguousBeanDefinitions (active removal, or its own ambiguity warning)
            }
            warnings.add("Deleted type '" + reference.target() + "' is still referenced in resource '"
                    + PlannerSupport.relative(projectRoot, reference.file()) + "' (" + reference.kind().name() + ", "
                    + reference.confidence().name() + " confidence); this reference is not auto-removed and must be "
                    + "reviewed.");
        }
        return warnings;
    }

    private static boolean isSubsumed(ReachabilityGraph graph, ReachabilityGraph.Node node, Set<String> deleted) {
        String enclosing = node.enclosingTypeKey();
        while (enclosing != null) {
            if (deleted.contains(enclosing)) {
                return true;
            }
            ReachabilityGraph.Node parent = graph.node(enclosing);
            enclosing = parent == null ? null : parent.enclosingTypeKey();
        }
        return false;
    }

    /**
     * Removes {@code META-INF/services/*} provider lines that name a deleted implementation class (§7.3 resource
     * rewrite). Only exact, unambiguous fully-qualified-name matches are removed; comments and unrelated providers are
     * left untouched.
     */
    private static List<PlannerSupport.TextEdit> rewriteServiceLoaderFiles(Path projectRoot, Charset charset,
            Set<String> deletedTypeFqns, List<String> warnings, Set<String> incompleteResourceFiles) {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        // Blocker B4 / story R06: a META-INF/services file that could not be read might still register a deleted
        // provider, so it must not be silently skipped. Any unreadable service file (or a failed walk) marks the
        // service-loader scan incomplete; on an incomplete scan the provider-line removals are WITHHELD (returned
        // empty) — exactly like the package ResourceRewriter / removeUnambiguousBeanDefinitions on incomplete scan —
        // and the incomplete files are surfaced + propagated so classifyRisk escalates the delete to needs_review.
        boolean incomplete = false;
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            List<Path> serviceFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(PropagatingSafeDeletePlanner::isServiceLoaderFile)
                    .toList();
            for (Path serviceFile : serviceFiles) {
                String content = readSource(serviceFile, charset);
                if (content == null) {
                    incomplete = true;
                    String relative = PlannerSupport.relative(projectRoot, serviceFile);
                    incompleteResourceFiles.add(relative);
                    warnings.add("Skipped unreadable META-INF/services file " + relative
                            + "; it may register a deleted provider, so service-loader cleanup could not be"
                            + " determined and must be reviewed.");
                    continue;
                }
                int offset = 0;
                int length = content.length();
                while (offset < length) {
                    int lineEnd = content.indexOf('\n', offset);
                    int sliceEnd = lineEnd < 0 ? length : lineEnd + 1; // include the trailing newline in the removal
                    String line = content.substring(offset, lineEnd < 0 ? length : lineEnd);
                    String provider = stripComment(line).trim();
                    if (deletedTypeFqns.contains(provider)) {
                        edits.add(new PlannerSupport.TextEdit(serviceFile, offset, sliceEnd, "", "RESOURCE"));
                    }
                    if (lineEnd < 0) {
                        break;
                    }
                    offset = lineEnd + 1;
                }
            }
        } catch (IOException error) {
            incomplete = true;
            incompleteResourceFiles.add("META-INF/services");
            warnings.add("Could not scan META-INF/services for deleted service providers: " + error.getMessage()
                    + "; service-loader cleanup could not be determined and must be reviewed.");
        }
        if (incomplete) {
            // Withhold every service-loader provider-line removal: they were derived from a scan that could not read
            // all in-scope registrations, so auto-applying them would act on an incomplete picture (R06 / B4).
            warnings.add("Service-loader provider-line removals for the deleted types were NOT auto-applied because the"
                    + " META-INF/services scan was incomplete; they must be reviewed.");
            return new ArrayList<>();
        }
        return edits;
    }

    private static boolean isServiceLoaderFile(Path path) {
        Path parent = path.getParent();
        Path grandParent = parent == null ? null : parent.getParent();
        return parent != null && grandParent != null
                && "services".equals(parent.getFileName().toString())
                && "META-INF".equals(grandParent.getFileName().toString());
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    // ── root resolution ────────────────────────────────────────────────────────────────────────────────────────────

    private static String resolve(SemanticIndex index, ReachabilityGraph graph, RootSpec root) throws IOException {
        if (root.symbol() != null && !root.symbol().isBlank()) {
            return resolveSymbol(graph, root.symbol().trim());
        }
        if (root.relativePath() != null && root.line() != null && root.column() != null) {
            RefactorAnalysisResult analysis =
                    index.resolveTarget(root.relativePath(), root.line(), root.column(), null);
            if (analysis.target() == null) {
                return null;
            }
            String key = analysis.target().key().canonical();
            return graph.node(key) != null ? key : null;
        }
        return null;
    }

    private static String resolveSymbol(ReachabilityGraph graph, String symbol) {
        if (graph.node(symbol) != null) {
            return symbol;
        }
        String normalized = symbol.replaceAll("\\s", "");
        String suffixMatch = null;
        boolean ambiguousSuffix = false;
        for (ReachabilityGraph.Node node : graph.nodes()) {
            String key = node.key();
            if (key.replaceAll("\\s", "").equals(normalized)) {
                return key;
            }
            if (key.endsWith(symbol)) {
                if (suffixMatch == null) {
                    suffixMatch = key;
                } else {
                    ambiguousSuffix = true;
                }
            }
        }
        return ambiguousSuffix ? null : suffixMatch;
    }

    // ── small utilities ────────────────────────────────────────────────────────────────────────────────────────────

    private static String symbolReasonArray(Map<String, String> entries) {
        List<String> objects = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            objects.add("{\"symbol\":" + JsonUtil.quote(entry.getKey())
                    + ",\"reason\":" + JsonUtil.quote(entry.getValue()) + "}");
        }
        return "[" + String.join(",", objects) + "]";
    }

    private static String readSource(Path file, Charset charset) {
        try {
            return Files.readString(file, charset);
        } catch (IOException error) {
            return null;
        }
    }

    /**
     * Reads the source of a file whose declaration is being deleted (the R08-critical admitted-deletion read path in
     * {@link #computeEdit}). Returns {@code null} when the file is unreadable, which {@code computeEdit} turns into a
     * {@code delete_source_unreadable} refusal. Overridable (package-private, non-static) purely as a deterministic test
     * seam: a test can inject unreadability for a specific node and still drive the real {@code compute}/{@code
     * computeEdit}/{@code plan} logic and assert the real refusal JSON. Production code never overrides it.
     */
    String readDeclarationSource(Path file, Charset charset) {
        return readSource(file, charset);
    }

    private static String firstJavaRelative(JavaProjectModel model) {
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path javaFile : sourceSet.javaFiles()) {
                Path absolute = javaFile.toAbsolutePath().normalize();
                if (absolute.startsWith(projectRoot)) {
                    return projectRoot.relativize(absolute).toString();
                }
            }
        }
        return null;
    }
}
