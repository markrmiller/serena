package io.serena.javarefactor.v3.graph;

import io.serena.javarefactor.compiler.ReachabilityGraph;
import io.serena.javarefactor.compiler.ReachabilityGraphCache;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.compiler.TransformationGraphFacts;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.ResourceRootModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.v3.resources.ResourceReference;
import io.serena.javarefactor.v3.resources.ResourceReferenceScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the unified {@link TransformationGraph} for a project revision from the real models
 * (refactor-feature-plan-V3.md §1.2/§3): the validated {@link JavaProjectModel} (build layout + source-root
 * classification), the compiler-resolved {@link TransformationGraphFacts} (symbols/hierarchy/calls), the resource SPI's
 * {@link ResourceReferenceScanner} (exact provider-backed resource references), and the cached {@link ReachabilityGraph}
 * (test&rarr;production reference edges). Nothing here is a stub: every node and edge is sourced from a real model.
 *
 * <p>The builder shares the singleton {@link ReachabilityGraphCache} so the symbol/call reachability is computed exactly
 * once per revision and reused by the impact, delete, and report consumers — i.e. building the graph does not duplicate
 * the reachability walk those consumers already perform; it composes the same cached graph into the wider seven-component
 * view. {@link GraphInvalidation} layers a per-revision cache on top of this builder so the whole graph is materialized
 * once per revision too.
 */
public final class TransformationGraphBuilder {

    /**
     * The full result of a build: the assembled {@link TransformationGraph} plus the {@link TransformationGraphFacts} it
     * was assembled from, so the R05 incremental updater can snapshot the facts and carry untouched files forward on the
     * next revision. {@code facts} is {@code null} for an empty (no-Java-source) project.
     */
    public record BuildArtifacts(TransformationGraph graph, TransformationGraphFacts facts) {
    }

    /**
     * Builds the full transformation graph for {@code model}'s current revision with the default limits.
     *
     * @throws IOException if the project cannot be opened or resources cannot be walked
     */
    public TransformationGraph build(JavaProjectModel model) throws IOException {
        return build(model, GraphCacheLimits.defaults());
    }

    /**
     * Builds the full transformation graph for {@code model}'s current revision honoring {@code limits} (the resource
     * max-file-size cap).
     *
     * @throws IOException if the project cannot be opened or resources cannot be walked
     */
    public TransformationGraph build(JavaProjectModel model, GraphCacheLimits limits) throws IOException {
        return buildArtifacts(model, limits).graph();
    }

    /**
     * Full build that also returns the extracted facts (for the incremental snapshot). Extracts EVERY file's facts
     * (the from-scratch path), so its graph is byte-for-byte equivalent to the legacy whole-project build.
     *
     * @throws IOException if the project cannot be opened or resources cannot be walked
     */
    public BuildArtifacts buildArtifacts(JavaProjectModel model, GraphCacheLimits limits) throws IOException {
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();

        String representative = firstJavaRelative(model);
        if (representative == null) {
            // No Java sources: an empty-but-real graph (build layout still present).
            ProjectGraph project = projectGraph(model, projectRoot);
            BuildGraph build = buildGraph(model, projectRoot, buildSystem(model));
            TransformationGraph graph = new TransformationGraph(
                    project,
                    new JavaSymbolGraph(Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Set.of()),
                    new TypeHierarchyIndex(Map.of(), Map.of(), Map.of()),
                    new CallGraph(Map.of(), Map.of(), Map.of(), 0),
                    resourceGraph(model, projectRoot, Set.of(), limits),
                    build,
                    new TestGraph(List.of()));
            return new BuildArtifacts(graph, null);
        }

        try (SemanticIndex index = SemanticIndex.open(model, representative)) {
            TransformationGraphFacts facts = TransformationGraphFacts.extract(index, model);

            // Shared cached reachability graph (includeTests=true so test->production edges are visible) — the same
            // instance the impact/delete/report consumers read, so building the graph reuses, never duplicates, the walk.
            String projectKey = ReachabilityGraphCache.projectKey(model);
            ReachabilityGraph reachability = ReachabilityGraphCache.INSTANCE.get(projectKey, true,
                    () -> ReachabilityGraph.build(index, model, true));

            TransformationGraph graph = assemble(model, projectRoot, facts, reachability, limits);
            return new BuildArtifacts(graph, facts);
        }
    }

    /**
     * Re-extracts facts for {@code affectedFiles} only, merges them with {@code priorFacts} (carrying untouched files
     * forward), and assembles the new graph (R05 incremental graph maintenance). The reachability graph and resource
     * scan are recomputed because their per-revision cache key already changed; the win is skipping the per-file
     * declaration/edge walk for untouched files. The merged facts — and therefore the resulting graph's symbol/hierarchy/
     * call JSON — are equivalent to a from-scratch {@link #buildArtifacts} for the same revision (proven by the
     * equivalence tests).
     *
     * @throws IOException if the project cannot be opened or resources cannot be walked
     */
    public BuildArtifacts buildIncremental(JavaProjectModel model, TransformationGraphFacts priorFacts,
            Set<String> affectedFiles, Set<String> survivingFiles, GraphCacheLimits limits) throws IOException {
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();
        String representative = firstJavaRelative(model);
        if (representative == null) {
            return buildArtifacts(model, limits);
        }
        try (SemanticIndex index = SemanticIndex.open(model, representative)) {
            Set<String> carriedMemberKeys = new LinkedHashSet<>();
            for (TransformationGraphFacts.MemberFact member : priorFacts.members()) {
                if (survivingFiles.contains(member.relativePath()) && !affectedFiles.contains(member.relativePath())) {
                    carriedMemberKeys.add(member.canonicalKey());
                }
            }
            TransformationGraphFacts fresh =
                    TransformationGraphFacts.extract(index, model, affectedFiles, carriedMemberKeys);
            TransformationGraphFacts merged =
                    TransformationGraphFacts.merge(priorFacts, fresh, affectedFiles, survivingFiles);

            String projectKey = ReachabilityGraphCache.projectKey(model);
            ReachabilityGraph reachability = ReachabilityGraphCache.INSTANCE.get(projectKey, true,
                    () -> ReachabilityGraph.build(index, model, true));

            TransformationGraph graph = assemble(model, projectRoot, merged, reachability, limits);
            return new BuildArtifacts(graph, merged);
        }
    }

    private ProjectGraph projectGraph(JavaProjectModel model, Path projectRoot) throws IOException {
        String revision = ReachabilityGraphCache.projectKey(model);
        String buildSystem = buildSystem(model);
        BuildGraph build = buildGraph(model, projectRoot, buildSystem);
        return new ProjectGraph(
                projectRoot.toString().replace('\\', '/'),
                revision,
                buildSystem,
                build.modules().stream().map(BuildGraph.ModuleNode::moduleId).toList());
    }

    /** Assembles the seven-component graph from already-extracted facts and the cached reachability graph. */
    private TransformationGraph assemble(JavaProjectModel model, Path projectRoot, TransformationGraphFacts facts,
            ReachabilityGraph reachability, GraphCacheLimits limits) throws IOException {
        String buildSystem = buildSystem(model);
        BuildGraph build = buildGraph(model, projectRoot, buildSystem);
        ProjectGraph project = new ProjectGraph(
                projectRoot.toString().replace('\\', '/'),
                ReachabilityGraphCache.projectKey(model),
                buildSystem,
                build.modules().stream().map(BuildGraph.ModuleNode::moduleId).toList());

        JavaSymbolGraph symbols = symbolGraph(facts, model, projectRoot);
        TypeHierarchyIndex hierarchy = hierarchyIndex(facts);
        CallGraph calls = callGraph(facts);
        Set<String> allTypeFqns = new LinkedHashSet<>(symbols.typesByFqn().keySet());
        ResourceReferenceGraph resources = resourceGraph(model, projectRoot, allTypeFqns, limits);
        TestGraph tests = testGraph(reachability, projectRoot);

        return new TransformationGraph(project, symbols, hierarchy, calls, resources, build, tests);
    }

    // ── symbols ─────────────────────────────────────────────────────────────────────────────────────

    private JavaSymbolGraph symbolGraph(TransformationGraphFacts facts, JavaProjectModel model, Path projectRoot) {
        Map<String, JavaSymbolGraph.TypeNode> typesByFqn = new LinkedHashMap<>();
        Map<String, String> typeToFile = new LinkedHashMap<>();
        Map<String, Set<String>> filesByPackage = new LinkedHashMap<>();
        Map<String, Set<String>> packageToSourceRoots = new LinkedHashMap<>();
        Set<String> publicApiFqns = new LinkedHashSet<>();

        List<Path> sourceRoots = allSourceRoots(model);
        // Deterministic order independent of extraction order: a full build and an incremental merge emit the same JSON.
        List<TransformationGraphFacts.TypeFact> sortedTypes = new ArrayList<>(facts.types());
        sortedTypes.sort(java.util.Comparator.comparing(TransformationGraphFacts.TypeFact::fqn)
                .thenComparing(TransformationGraphFacts.TypeFact::canonicalKey));
        for (TransformationGraphFacts.TypeFact type : sortedTypes) {
            JavaSymbolGraph.TypeNode node = new JavaSymbolGraph.TypeNode(
                    type.fqn(), type.simpleName(), type.packageName(), type.kind(),
                    type.relativePath(), type.topLevel(), type.publicApi(), type.testSource());
            typesByFqn.put(type.fqn(), node);
            if (type.publicApi()) {
                publicApiFqns.add(type.fqn());
            }
            if (type.topLevel()) {
                typeToFile.put(type.fqn(), type.relativePath());
            }
            filesByPackage.computeIfAbsent(type.packageName(), k -> new LinkedHashSet<>())
                    .add(type.relativePath());
            // package -> the project-relative source root(s) that contain this type's file.
            String sourceRoot = enclosingSourceRoot(projectRoot, sourceRoots, type.relativePath());
            if (sourceRoot != null) {
                packageToSourceRoots.computeIfAbsent(type.packageName(), k -> new LinkedHashSet<>())
                        .add(sourceRoot);
            }
        }

        List<JavaSymbolGraph.MemberNode> members = new ArrayList<>();
        List<TransformationGraphFacts.MemberFact> sortedMembers = new ArrayList<>(facts.members());
        sortedMembers.sort(java.util.Comparator.comparing(TransformationGraphFacts.MemberFact::canonicalKey));
        for (TransformationGraphFacts.MemberFact member : sortedMembers) {
            members.add(new JavaSymbolGraph.MemberNode(
                    member.canonicalKey(), member.ownerFqn(), member.name(), member.memberKind(),
                    member.parameterCount(), member.relativePath(), member.publicApi(), member.testSource()));
        }

        return new JavaSymbolGraph(typesByFqn, members, packageToSourceRoots, typeToFile,
                filesByPackage, publicApiFqns);
    }

    // ── hierarchy ───────────────────────────────────────────────────────────────────────────────────

    private TypeHierarchyIndex hierarchyIndex(TransformationGraphFacts facts) {
        Map<String, Set<String>> supertypes = new LinkedHashMap<>();
        Map<String, Set<String>> subtypes = new LinkedHashMap<>();
        Set<String> projectTypeFqns = new LinkedHashSet<>();
        for (TransformationGraphFacts.TypeFact type : facts.types()) {
            projectTypeFqns.add(type.fqn());
        }
        for (Map.Entry<String, Set<String>> entry : facts.supertypeFqns().entrySet()) {
            String child = entry.getKey();
            Set<String> parents = new LinkedHashSet<>(entry.getValue());
            supertypes.put(child, parents);
            for (String parent : parents) {
                // Invert only over project types (a library supertype has no project-visible subtypes index entry use).
                if (projectTypeFqns.contains(parent)) {
                    subtypes.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(child);
                }
            }
        }
        Map<String, Set<String>> overrideGroups = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : facts.overrideGroups().entrySet()) {
            overrideGroups.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        return new TypeHierarchyIndex(supertypes, subtypes, overrideGroups);
    }

    // ── calls ───────────────────────────────────────────────────────────────────────────────────────

    private CallGraph callGraph(TransformationGraphFacts facts) {
        Map<String, Set<String>> callEdges = copyOfSets(facts.callEdges());
        Map<String, Set<String>> constructorEdges = copyOfSets(facts.constructorEdges());
        Map<String, Set<String>> methodReferenceEdges = copyOfSets(facts.methodReferenceEdges());
        long executableMembers = facts.members().stream()
                .filter(m -> !"field".equals(m.memberKind()))
                .count();
        return new CallGraph(callEdges, constructorEdges, methodReferenceEdges, (int) executableMembers);
    }

    // ── resources ───────────────────────────────────────────────────────────────────────────────────

    private ResourceReferenceGraph resourceGraph(JavaProjectModel model, Path projectRoot, Set<String> targetFqns,
            GraphCacheLimits limits) throws IOException {
        ResourceReferenceScanner scanner =
                new ResourceReferenceScanner(projectRoot, model, limits.maxResourceFileBytes());
        List<ResourceReferenceGraph.Reference> refs = new ArrayList<>();
        if (targetFqns.isEmpty()) {
            // No targets to scan for: the scan is trivially complete (it examined nothing it needed to).
            return new ResourceReferenceGraph(refs, List.of());
        }
        // Story R06: carry the scan-completeness gate through to the graph so a consumer relying on the resource view to
        // judge rename/move safety can detect that an in-scope resource file was unreadable / over-cap and escalate.
        ResourceReferenceScanner.ScanResult scan = scanner.referencesFor(targetFqns);
        for (ResourceReference ref : scan.references()) {
            refs.add(new ResourceReferenceGraph.Reference(
                    ref.target(), scanner.relativePathOf(ref), ref.startOffset(), ref.endOffset(),
                    ref.oldText(), ref.kind().name(), ref.confidence().name(), ref.providerId()));
        }
        return new ResourceReferenceGraph(refs, scan.completeness().incompleteFiles());
    }

    // ── tests ───────────────────────────────────────────────────────────────────────────────────────

    private TestGraph testGraph(ReachabilityGraph reachability, Path projectRoot) {
        // Aggregate per test type: the production type FQNs its members reference (outgoing edges to non-test nodes).
        Map<String, TestAccumulator> byTestFqn = new LinkedHashMap<>();
        for (ReachabilityGraph.Node node : reachability.nodes()) {
            if (!node.testSource()) {
                continue;
            }
            String testFqn = node.ownerTypeFqn();
            if (testFqn == null || testFqn.isBlank()) {
                continue;
            }
            TestAccumulator acc = byTestFqn.computeIfAbsent(testFqn,
                    k -> new TestAccumulator(testFqn, PlannerSupport.relative(projectRoot, node.file())));
            for (String targetKey : reachability.outgoing(node.key())) {
                ReachabilityGraph.Node target = reachability.node(targetKey);
                if (target == null || target.testSource()) {
                    continue;
                }
                String producedFqn = target.ownerTypeFqn();
                if (producedFqn != null && !producedFqn.isBlank() && !producedFqn.equals(testFqn)) {
                    acc.referencedTypes.add(producedFqn);
                }
            }
        }
        List<TestGraph.TestNode> tests = new ArrayList<>();
        for (TestAccumulator acc : byTestFqn.values()) {
            tests.add(new TestGraph.TestNode(acc.testFqn, acc.relativePath, acc.referencedTypes));
        }
        return new TestGraph(tests);
    }

    private static final class TestAccumulator {
        final String testFqn;
        final String relativePath;
        final Set<String> referencedTypes = new LinkedHashSet<>();

        TestAccumulator(String testFqn, String relativePath) {
            this.testFqn = testFqn;
            this.relativePath = relativePath;
        }
    }

    // ── build layout ────────────────────────────────────────────────────────────────────────────────

    private BuildGraph buildGraph(JavaProjectModel model, Path projectRoot, String buildSystem) {
        // Each source set becomes a module (single-module projects have one module per source set, which is the
        // conventional Maven/Gradle main/test split). Resource roots are derived MODEL-FIRST (blocker B11): the
        // authoritative resource-root set comes from ResourceRootModel (which reads the model's configured source roots
        // and demotes the filename convention to a fallback), and a configured source root is classified RESOURCES iff it
        // is in that set rather than by an ad-hoc filename check here.
        Set<Path> resourceRoots = ResourceRootModel.resourceRoots(model);
        List<BuildGraph.ModuleNode> modules = new ArrayList<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            String moduleId = sourceSet.name() == null ? "main" : sourceSet.name();
            boolean test = moduleId.toLowerCase(Locale.ROOT).contains("test");
            BuildGraph.RootKind kind = test ? BuildGraph.RootKind.TEST : BuildGraph.RootKind.MAIN;
            List<BuildGraph.SourceRoot> roots = new ArrayList<>();
            for (Path javaRoot : sourceSet.sourceRoots()) {
                Path normalized = javaRoot.toAbsolutePath().normalize();
                String rel = PlannerSupport.relative(projectRoot, normalized);
                boolean resourceRoot = resourceRoots.contains(normalized);
                roots.add(new BuildGraph.SourceRoot(
                        rel, kind,
                        resourceRoot ? BuildGraph.RootContent.RESOURCES : BuildGraph.RootContent.JAVA,
                        moduleId));
                // The model-derived resources root paired with a java/ root (e.g. src/main/java -> src/main/resources).
                if (!resourceRoot && normalized.getParent() != null) {
                    Path sibling = normalized.getParent().resolve("resources").toAbsolutePath().normalize();
                    if (resourceRoots.contains(sibling)) {
                        roots.add(new BuildGraph.SourceRoot(
                                PlannerSupport.relative(projectRoot, sibling), kind,
                                BuildGraph.RootContent.RESOURCES, moduleId));
                    }
                }
            }
            modules.add(new BuildGraph.ModuleNode(moduleId, buildSystem, roots));
        }
        return new BuildGraph(buildSystem, modules);
    }

    /** Maps the model's discovery kind to the {@code maven}/{@code gradle}/{@code plain} build-system label. */
    private static String buildSystem(JavaProjectModel model) {
        String discovery = model.discoveryKind() == null ? "" : model.discoveryKind().toLowerCase(Locale.ROOT);
        if (discovery.contains("maven")) {
            return "maven";
        }
        if (discovery.contains("gradle")) {
            return "gradle";
        }
        return "plain";
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Set<String>> copyOfSets(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        return copy;
    }

    private static List<Path> allSourceRoots(JavaProjectModel model) {
        List<Path> roots = new ArrayList<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                roots.add(root.toAbsolutePath().normalize());
            }
        }
        return roots;
    }

    /** The project-relative source root whose tree contains {@code relativePath}, longest match wins. */
    private static String enclosingSourceRoot(Path projectRoot, List<Path> sourceRoots, String relativePath) {
        Path file = projectRoot.resolve(relativePath).toAbsolutePath().normalize();
        Path best = null;
        for (Path root : sourceRoots) {
            if (file.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) {
                best = root;
            }
        }
        return best == null ? null : PlannerSupport.relative(projectRoot, best);
    }

    private static String firstJavaRelative(JavaProjectModel model) {
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path javaFile : sourceSet.javaFiles()) {
                Path abs = javaFile.toAbsolutePath().normalize();
                if (abs.startsWith(projectRoot)) {
                    return projectRoot.relativize(abs).toString();
                }
            }
        }
        return null;
    }
}
