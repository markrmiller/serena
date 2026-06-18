package io.serena.javarefactor.compiler;

import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.ResourceRootModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.v3.resources.ResourceConfidence;
import io.serena.javarefactor.v3.resources.ResourceReference;
import io.serena.javarefactor.v3.resources.ResourceReferenceKind;
import io.serena.javarefactor.v3.resources.ResourceReferenceScanner;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Stateless sidecar op {@code impact.facts} (refactor-feature-plan-V3.md §G011).
 *
 * <p>Given a list of touched project-relative paths, produces a structured JSON fact-sheet:
 * <ul>
 *   <li>source roots classified as main-Java, test-Java, and resource roots;</li>
 *   <li>top-level types declared in each touched file (FQN, relativePath, publicApi, testSource);</li>
 *   <li>all incoming semantic references to those types via the {@link ReachabilityGraph}, split by
 *       whether the referrer lives in a test source set;</li>
 *   <li>provider-backed resource references to those types — sourced from the resource SPI's
 *       {@link ResourceReferenceScanner} (ServiceLoader/Xml/StructuredText/Reflection providers), each carrying its
 *       structural {@link ResourceReferenceKind kind}, {@link ResourceConfidence confidence}, provider id and exact
 *       offsets — NOT bare substring matches;</li>
 *   <li>framework metadata impact: exact compiler-resolved framework annotations on the touched types;</li>
 *   <li>derived review aids: per-kind resource subtype counts, the exact (HIGH-confidence) resource entries that would
 *       change, the review-only reflection candidates (LOW/reflective, never auto-edited), and the per-build-model
 *       suggested test commands.</li>
 * </ul>
 *
 * <p>Lives in the {@code compiler} package so it can access the package-private {@link SemanticIndex}
 * internals ({@link SemanticIndex#allTasks()}, {@link SemanticIndex#pathOf(CompilationUnitTree)}, and
 * {@link CompilerTask}) — exactly the same reason {@link ReachabilityGraph} lives here. This op never
 * mutates files.
 */
public final class ImpactFactsAnalyzer {

    public String analyze(JavaProjectModel model, List<String> touchedRelPaths) throws IOException {
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();

        List<String> mainJavaRoots = new ArrayList<>();
        List<String> testJavaRoots = new ArrayList<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            boolean isTest = sourceSet.name() != null
                    && sourceSet.name().toLowerCase(Locale.ROOT).contains("test");
            List<String> bucket = isTest ? testJavaRoots : mainJavaRoots;
            for (Path root : sourceSet.sourceRoots()) {
                bucket.add(PlannerSupport.relative(projectRoot, root));
            }
        }
        List<String> resourceRoots = resourceDirStrings(model, projectRoot);

        String representative = firstJavaRelative(model);
        if (representative == null) {
            return envelope(touchedRelPaths, mainJavaRoots, testJavaRoots, resourceRoots,
                    List.of(), List.of(), List.of(), List.of(), lowRiskJson(),
                    new TreeMap<>(), List.of(), List.of(), suggestedTestCommands(projectRoot, model),
                    false, List.of());
        }

        Set<Path> touchedAbs = new LinkedHashSet<>();
        for (String rel : touchedRelPaths) {
            touchedAbs.add(projectRoot.resolve(rel).toAbsolutePath().normalize());
        }

        try (SemanticIndex index = SemanticIndex.open(model, representative)) {

            Set<Path> testRoots = testSourceRoots(model);
            // key: canonical SemanticKey string -> TouchedTypeInfo
            Map<String, TouchedTypeInfo> byKey = new LinkedHashMap<>();
            // FQN -> canonical key (for resource-ref and incoming-ref labelling)
            Map<String, String> fqnToKey = new LinkedHashMap<>();

            for (CompilerTask task : index.allTasks()) {
                for (CompilationUnitTree unit : task.units) {
                    Path file = SemanticIndex.pathOf(unit);
                    if (!touchedAbs.contains(file)) {
                        continue;
                    }
                    String relPath = PlannerSupport.relative(projectRoot, file);
                    boolean testSource = isUnderAny(file, testRoots);

                    for (Tree decl : unit.getTypeDecls()) {
                        if (!(decl instanceof ClassTree)) {
                            continue; // skip non-type trivia such as a stray ';'
                        }
                        Element element = task.trees.getElement(
                                new TreePath(new TreePath(unit), decl));
                        if (!(element instanceof TypeElement type)) {
                            continue;
                        }
                        String fqn = type.getQualifiedName().toString();
                        // Compute canonical key using this task's trees/types for accuracy.
                        TreePath typePath = task.trees.getPath(type);
                        String key;
                        if (typePath != null) {
                            CompilationUnitTree keyCu = typePath.getCompilationUnit();
                            key = SemanticKey.from(type, task.trees, task.types, keyCu,
                                    SemanticIndex.pathOf(keyCu)).canonical();
                        } else {
                            key = SemanticKey.from(type).canonical();
                        }
                        if (key == null || key.isBlank() || byKey.containsKey(key)) {
                            continue;
                        }
                        Set<Modifier> modifiers = type.getModifiers();
                        boolean publicApi = modifiers.contains(Modifier.PUBLIC)
                                || modifiers.contains(Modifier.PROTECTED);
                        byKey.put(key, new TouchedTypeInfo(fqn, relPath, key, publicApi, testSource));
                        fqnToKey.put(fqn, key);
                    }
                }
            }

            // ── build reachability graph (includeTests=true to see test referrers) ──────────────
            // Memoized on a whole-project revision key (see ReachabilityGraphCache): a repeat request over an unchanged
            // project reuses the prior walk; any source edit (touched or not) changes the key and rebuilds.
            String projectKey = ReachabilityGraphCache.projectKey(model);
            ReachabilityGraph graph = ReachabilityGraphCache.INSTANCE.get(projectKey, true,
                    () -> ReachabilityGraph.build(index, model, true));

            List<String> incomingRefJsons = new ArrayList<>();
            int incomingMainRefs = 0;
            int incomingTestRefs = 0;
            for (TouchedTypeInfo info : byKey.values()) {
                for (String referrerKey : graph.incoming(info.canonicalKey())) {
                    ReachabilityGraph.Node referrer = graph.node(referrerKey);
                    if (referrer == null) {
                        continue;
                    }
                    if (referrer.testSource()) {
                        incomingTestRefs++;
                    } else {
                        incomingMainRefs++;
                    }
                    String fromRel = PlannerSupport.relative(projectRoot, referrer.file());
                    incomingRefJsons.add("{"
                            + "\"fromKey\":" + JsonUtil.quote(referrerKey) + ","
                            + "\"fromFqn\":" + JsonUtil.quote(referrer.ownerTypeFqn()) + ","
                            + "\"fromRelativePath\":" + JsonUtil.quote(fromRel) + ","
                            + "\"fromTestSource\":" + referrer.testSource() + ","
                            + "\"fromPublicApi\":" + referrer.publicApi() + ","
                            + "\"toFqn\":" + JsonUtil.quote(info.fqn()) + ","
                            + "\"toRelativePath\":" + JsonUtil.quote(info.relPath())
                            + "}");
                }
            }

            // ── provider-backed resource references ───────────────────────────────────────────────
            // Reuse the resource SPI's provider registry (ServiceLoader/Xml/StructuredText/Reflection) so each ref is
            // structurally classified (kind + confidence + provider + exact offsets), never a bare substring hit.
            ResourceReferenceScanner scanner = new ResourceReferenceScanner(projectRoot, model);
            ResourceReferenceScanner.ScanResult resourceScan = scanner.referencesFor(fqnToKey.keySet());
            List<ResourceReference> resourceReferences = resourceScan.references();
            // Story R06 / shared contract 2: an in-scope resource file that could not be examined (unreadable or over the
            // size cap) means the resource impact could not be fully determined. Surface every incomplete file and force
            // the risk classification to escalate (never report a falsely-complete impact from a partial scan).
            List<String> resourceScanIncompleteFiles = resourceScan.completeness().incompleteFiles();
            boolean resourceScanIncomplete = !resourceScan.completeness().isComplete();

            List<String> resourceRefJsons = new ArrayList<>();
            // kind -> count, for the resourceSubtypeCounts review aid.
            Map<String, Integer> resourceSubtypeCounts = new TreeMap<>();
            // HIGH-confidence, non-reflective refs the rename/move would actually rewrite (the "exact changed entries").
            List<String> exactChangedEntryJsons = new ArrayList<>();
            // LOW/reflective candidates — surfaced for human review, NEVER auto-changed.
            List<String> reflectionCandidateJsons = new ArrayList<>();
            for (ResourceReference ref : resourceReferences) {
                String relPath = scanner.relativePathOf(ref);
                String refJson = "{"
                        + "\"resourcePath\":" + JsonUtil.quote(relPath) + ","
                        + "\"target\":" + JsonUtil.quote(ref.target()) + ","
                        + "\"kind\":" + JsonUtil.quote(ref.kind().name()) + ","
                        + "\"confidence\":" + JsonUtil.quote(ref.confidence().name()) + ","
                        + "\"provider\":" + JsonUtil.quote(ref.providerId()) + ","
                        + "\"startOffset\":" + ref.startOffset() + ","
                        + "\"endOffset\":" + ref.endOffset() + ","
                        + "\"oldText\":" + JsonUtil.quote(ref.oldText())
                        + "}";
                resourceRefJsons.add(refJson);
                resourceSubtypeCounts.merge(ref.kind().name(), 1, Integer::sum);
                boolean reflective = ref.kind() == ResourceReferenceKind.REFLECTIVE_STRING_CANDIDATE
                        || ref.confidence() == ResourceConfidence.LOW;
                if (reflective) {
                    reflectionCandidateJsons.add(refJson);
                } else if (ref.confidence() == ResourceConfidence.HIGH) {
                    exactChangedEntryJsons.add(refJson);
                }
            }
            // Count of resource refs that escape the compiler net (drives HIGH risk): every provider-backed ref.
            int resourceRefCount = resourceReferences.size();

            // ── framework participation: entry points among touched types (exact-FQN, never heuristic) ─
            List<String> frameworkRefJsons = new ArrayList<>();
            Set<String> frameworkEntryPointFqns = new LinkedHashSet<>();
            for (FrameworkAnnotationIndex.AnnotationOccurrence occ
                    : new FrameworkAnnotationIndex(index).annotations()) {
                String typeFqn = occ.enclosingTypeFqn();
                if (!fqnToKey.containsKey(typeFqn)) {
                    continue;
                }
                FrameworkAnnotationCatalog.Owner owner =
                        FrameworkAnnotationCatalog.ownerOf(occ.annotationFqn());
                if (owner == null) {
                    continue;
                }
                frameworkEntryPointFqns.add(typeFqn);
                TouchedTypeInfo info = byKey.get(fqnToKey.get(typeFqn));
                frameworkRefJsons.add("{"
                        + "\"typeFqn\":" + JsonUtil.quote(typeFqn) + ","
                        + "\"relativePath\":" + JsonUtil.quote(info == null ? "" : info.relPath()) + ","
                        + "\"frameworkId\":" + JsonUtil.quote(owner.frameworkId()) + ","
                        + "\"role\":" + JsonUtil.quote(owner.role()) + ","
                        + "\"annotationFqn\":" + JsonUtil.quote(occ.annotationFqn()) + ","
                        + "\"elementKind\":" + JsonUtil.quote(occ.elementKind()) + ","
                        + "\"elementName\":" + JsonUtil.quote(occ.elementName())
                        + "}");
            }

            // ── risk classification (refactor-feature-plan-V3.md §14.3): blast radius from the facts above ─
            int publicApiTypesTouched = 0;
            for (TouchedTypeInfo info : byKey.values()) {
                if (info.publicApi()) {
                    publicApiTypesTouched++;
                }
            }
            String riskJson = riskJson(publicApiTypesTouched, incomingMainRefs, incomingTestRefs,
                    resourceRefCount, frameworkEntryPointFqns.size(), resourceScanIncomplete,
                    resourceScanIncompleteFiles);

            List<String> suggestedTestCommands = suggestedTestCommands(projectRoot, model);

            List<String> touchedTypeJsons = new ArrayList<>();
            for (TouchedTypeInfo info : byKey.values()) {
                touchedTypeJsons.add("{"
                        + "\"fqn\":" + JsonUtil.quote(info.fqn()) + ","
                        + "\"relativePath\":" + JsonUtil.quote(info.relPath()) + ","
                        + "\"publicApi\":" + info.publicApi() + ","
                        + "\"testSource\":" + info.testSource()
                        + "}");
            }

            return envelope(touchedRelPaths, mainJavaRoots, testJavaRoots, resourceRoots,
                    touchedTypeJsons, incomingRefJsons, resourceRefJsons, frameworkRefJsons, riskJson,
                    resourceSubtypeCounts, exactChangedEntryJsons, reflectionCandidateJsons,
                    suggestedTestCommands, resourceScanIncomplete, resourceScanIncompleteFiles);
        }
    }

    // ── risk classification ─────────────────────────────────────────────────────────────────────────

    /**
     * A change is HIGH risk when its blast radius escapes the compiler's safety net: it touches a framework entry point
     * (behaviour driven by annotations), a string-encoded resource reference (invisible to javac), or a public-API type
     * with incoming main-source references. MEDIUM when there are internal main-source references or public API but none
     * of the high triggers. LOW when only test/no references exist.
     *
     * <p>Story R06 / shared contract 2: an incomplete resource scan ({@code resourceScanIncomplete}) is itself a hard
     * risk escalation — the analyzer could not determine whether an in-scope, but unexamined, resource file references a
     * touched type, so the impact CANNOT be classified as below HIGH. The specific incomplete files are listed as
     * {@code reasons} so the gap is surfaced, and {@code resourceScanIncomplete} is emitted as a first-class risk field.
     */
    private static String riskJson(int publicApiTypesTouched, int incomingMainRefs, int incomingTestRefs,
            int resourceRefs, int frameworkEntryPoints, boolean resourceScanIncomplete,
            List<String> resourceScanIncompleteFiles) {
        List<String> reasons = new ArrayList<>();
        if (frameworkEntryPoints > 0) {
            reasons.add("touches " + frameworkEntryPoints
                    + " framework entry-point type(s) whose behaviour is driven by annotations");
        }
        if (resourceRefs > 0) {
            reasons.add(resourceRefs + " resource reference(s) are string-encoded and invisible to the compiler");
        }
        if (resourceScanIncomplete) {
            reasons.add("resource scan was incomplete (" + String.join(", ", resourceScanIncompleteFiles)
                    + "); those resource files could not be examined for references and must be reviewed");
        }
        boolean publicApiExposed = publicApiTypesTouched > 0 && incomingMainRefs > 0;
        if (publicApiExposed) {
            reasons.add(publicApiTypesTouched + " public-API type(s) with " + incomingMainRefs
                    + " incoming main-source reference(s)");
        }
        boolean high = frameworkEntryPoints > 0 || resourceRefs > 0 || publicApiExposed || resourceScanIncomplete;
        boolean medium = incomingMainRefs > 0 || publicApiTypesTouched > 0;
        String level = high ? "HIGH" : (medium ? "MEDIUM" : "LOW");
        if (reasons.isEmpty()) {
            reasons.add(incomingMainRefs + incomingTestRefs == 0
                    ? "no incoming references found"
                    : "only internal/test references found");
        }
        return "{"
                + "\"level\":" + JsonUtil.quote(level) + ","
                + "\"publicApiTypesTouched\":" + publicApiTypesTouched + ","
                + "\"incomingMainRefs\":" + incomingMainRefs + ","
                + "\"incomingTestRefs\":" + incomingTestRefs + ","
                + "\"resourceRefs\":" + resourceRefs + ","
                + "\"frameworkEntryPoints\":" + frameworkEntryPoints + ","
                + "\"resourceScanIncomplete\":" + resourceScanIncomplete + ","
                + "\"reasons\":" + JsonUtil.array(reasons)
                + "}";
    }

    private static String lowRiskJson() {
        return riskJson(0, 0, 0, 0, 0, false, List.of());
    }

    // ── suggested test commands (per build model) ─────────────────────────────────────────────────────

    /**
     * The build-model-appropriate commands an agent should run to validate a change to the touched files
     * (refactor-feature-plan-V3.md §17 {@code tests.suggestedTestCommands}). Derived from the project's actual build
     * artifacts: a {@code gradlew}/{@code build.gradle(.kts)} project gets the Gradle test invocation (preferring the
     * committed wrapper), a {@code mvnw}/{@code pom.xml} project gets the Maven one, and a project with neither gets a
     * plain {@code javac}-based fallback. The model's {@link JavaProjectModel#discoveryKind()} is used as a tie-breaker
     * so an explicit/Gradle/Maven discovery still yields the right command even when wrapper files are absent.
     */
    private static List<String> suggestedTestCommands(Path projectRoot, JavaProjectModel model) {
        List<String> commands = new ArrayList<>();
        boolean gradle = Files.exists(projectRoot.resolve("build.gradle"))
                || Files.exists(projectRoot.resolve("build.gradle.kts"))
                || Files.exists(projectRoot.resolve("settings.gradle"))
                || Files.exists(projectRoot.resolve("settings.gradle.kts"));
        boolean maven = Files.exists(projectRoot.resolve("pom.xml"));
        String discovery = model.discoveryKind() == null ? "" : model.discoveryKind().toLowerCase(Locale.ROOT);
        if (gradle || discovery.contains("gradle")) {
            boolean wrapper = Files.exists(projectRoot.resolve("gradlew"));
            commands.add((wrapper ? "./gradlew" : "gradle") + " test");
        }
        if (maven || discovery.contains("maven")) {
            boolean wrapper = Files.exists(projectRoot.resolve("mvnw"));
            commands.add((wrapper ? "./mvnw" : "mvn") + " test");
        }
        if (commands.isEmpty()) {
            // No recognized build tool: the best generic suggestion is to compile + run JUnit against the sources.
            commands.add("javac -d out $(find . -name '*.java') && java -cp out org.junit.runner.JUnitCore");
        }
        return commands;
    }

    // ── source/resource root helpers ────────────────────────────────────────────────────────────────

    /**
     * Resource directories as project-relative strings, discovered MODEL-FIRST (blocker B11) via
     * {@link ResourceRootModel#resourceRoots(JavaProjectModel)} — which reads the authoritative model's configured source
     * roots and demotes the filename convention to a fallback — rather than the ad-hoc filesystem probing this method
     * previously did. Behavior is identical for the common {@code src/main/resources} layout.
     */
    private static List<String> resourceDirStrings(JavaProjectModel model, Path projectRoot) {
        List<String> result = new ArrayList<>();
        for (Path dir : ResourceRootModel.resourceRoots(model)) {
            result.add(PlannerSupport.relative(projectRoot, dir));
        }
        return result;
    }

    private static Set<Path> testSourceRoots(JavaProjectModel model) {
        Set<Path> roots = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            if (sourceSet.name() != null
                    && sourceSet.name().toLowerCase(Locale.ROOT).contains("test")) {
                for (Path root : sourceSet.sourceRoots()) {
                    roots.add(root.toAbsolutePath().normalize());
                }
            }
        }
        return roots;
    }

    private static boolean isUnderAny(Path file, Set<Path> roots) {
        for (Path root : roots) {
            if (file.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /** Any project-relative Java file path, used to anchor {@link SemanticIndex#open}. */
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

    // ── envelope ────────────────────────────────────────────────────────────────────────────────────

    private static String envelope(
            List<String> touchedRelPaths,
            List<String> mainJavaRoots,
            List<String> testJavaRoots,
            List<String> resourceRoots,
            List<String> touchedTypeJsons,
            List<String> incomingRefJsons,
            List<String> resourceRefJsons,
            List<String> frameworkRefJsons,
            String riskJson,
            Map<String, Integer> resourceSubtypeCounts,
            List<String> exactChangedEntryJsons,
            List<String> reflectionCandidateJsons,
            List<String> suggestedTestCommands,
            boolean resourceScanIncomplete,
            List<String> resourceScanIncompleteFiles) {
        // Story R06 / shared contract 1+2: an incomplete resource scan is emitted both as the top-level
        // resourceScanIncomplete boolean AND as a non-empty riskFacts.analysisIncomplete array, the two equivalent
        // signals CanonicalEnvelope.classifyRisk escalates on, so a consumer cannot treat this impact as SAFE.
        List<String> analysisIncomplete = new ArrayList<>();
        if (resourceScanIncomplete) {
            for (String file : resourceScanIncompleteFiles) {
                analysisIncomplete.add("resource scan incomplete: " + file
                        + " could not be examined for references");
            }
        }
        return "{"
                + "\"accepted\":true,"
                + "\"operation\":\"impact.facts\","
                + "\"resourceScanIncomplete\":" + resourceScanIncomplete + ","
                + "\"riskFacts\":{\"analysisIncomplete\":" + JsonUtil.array(analysisIncomplete) + "},"
                + "\"touchedPaths\":" + JsonUtil.array(touchedRelPaths) + ","
                + "\"sourceRoots\":{"
                +   "\"main\":" + JsonUtil.array(mainJavaRoots) + ","
                +   "\"test\":" + JsonUtil.array(testJavaRoots) + ","
                +   "\"resources\":" + JsonUtil.array(resourceRoots)
                + "},"
                + "\"touchedTypes\":" + JsonUtil.rawArray(touchedTypeJsons) + ","
                + "\"incomingRefs\":" + JsonUtil.rawArray(incomingRefJsons) + ","
                + "\"resourceRefs\":" + JsonUtil.rawArray(resourceRefJsons) + ","
                + "\"resourceSubtypeCounts\":" + subtypeCountsJson(resourceSubtypeCounts) + ","
                + "\"exactChangedEntries\":" + JsonUtil.rawArray(exactChangedEntryJsons) + ","
                + "\"reflectionCandidates\":" + JsonUtil.rawArray(reflectionCandidateJsons) + ","
                + "\"frameworkRefs\":" + JsonUtil.rawArray(frameworkRefJsons) + ","
                + "\"suggestedTestCommands\":" + JsonUtil.array(suggestedTestCommands) + ","
                + "\"risk\":" + riskJson + ","
                + "\"stats\":{"
                +   "\"touchedTypes\":" + touchedTypeJsons.size() + ","
                +   "\"incomingRefs\":" + incomingRefJsons.size() + ","
                +   "\"resourceRefs\":" + resourceRefJsons.size() + ","
                +   "\"exactChangedEntries\":" + exactChangedEntryJsons.size() + ","
                +   "\"reflectionCandidates\":" + reflectionCandidateJsons.size() + ","
                +   "\"frameworkRefs\":" + frameworkRefJsons.size()
                + "}"
                + "}";
    }

    /** Serializes the {@code kind -> count} resource-subtype tally as a JSON object with integer values. */
    private static String subtypeCountsJson(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(JsonUtil.quote(entry.getKey())).append(":").append(entry.getValue());
        }
        return sb.append("}").toString();
    }

    // ── internal value type ─────────────────────────────────────────────────────────────────────────

    private record TouchedTypeInfo(
            String fqn,
            String relPath,
            String canonicalKey,
            boolean publicApi,
            boolean testSource) {
    }
}
