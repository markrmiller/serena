package io.serena.javarefactor.v3.frameworks;

import io.serena.javarefactor.compiler.FrameworkAnnotationIndex;
import io.serena.javarefactor.compiler.FrameworkAnnotationIndex.AnnotationOccurrence;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.ResourceRootModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.v3.resources.ResourceConfidence;
import io.serena.javarefactor.v3.resources.ResourceReference;
import io.serena.javarefactor.v3.resources.ResourceReferenceKind;
import io.serena.javarefactor.v3.resources.ResourceReferenceScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Entry point for the read-only framework SPI ops {@code frameworks.detect} and {@code frameworks.findReferences}
 * (refactor-feature-plan-V3.md §16). Both are backed by exact compiler-resolved annotation facts
 * ({@link FrameworkAnnotationIndex}); neither edits files.
 *
 * <p>{@code detect} reports which frameworks are present (by the annotations actually applied in the project).
 * {@code findReferences} reports framework-significant references to a target type: where the target's own
 * declaration/members carry framework annotations ({@code role: "declares"}), and where the target is named inside a
 * framework annotation's arguments elsewhere ({@code role: "names"}). These read-only facts and the planners' deletion
 * conservatism draw from one source of truth — {@link io.serena.javarefactor.compiler.FrameworkAnnotationCatalog} —
 * so {@code ReachabilityGraph} blocks deletion of a framework-managed type using exactly the annotations reported here
 * (more conservative, never more aggressive).
 */
public final class FrameworkScanner {

    private final Path projectRoot;
    private final JavaProjectModel model;
    private final FrameworkRegistry registry = new FrameworkRegistry();

    public FrameworkScanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
    }

    public String detect(Map<String, Object> fields) {
        try {
            return detectChecked();
        } catch (FrameworkRefusal refusal) {
            return PlannerSupport.refusalJson("detectFrameworks", false, refusal.code(), refusal.getMessage());
        } catch (IOException e) {
            return PlannerSupport.refusalJson("detectFrameworks", false, "io_error", String.valueOf(e.getMessage()));
        }
    }

    public String findReferences(Map<String, Object> fields) {
        try {
            return findReferencesChecked(fields);
        } catch (FrameworkRefusal refusal) {
            return PlannerSupport.refusalJson("findFrameworkReferences", false, refusal.code(), refusal.getMessage());
        } catch (IOException e) {
            return PlannerSupport.refusalJson(
                    "findFrameworkReferences", false, "io_error", String.valueOf(e.getMessage()));
        }
    }

    private String detectChecked() throws IOException {
        // frameworkId -> (annotationFqn -> occurrence count)
        Map<String, Map<String, Integer>> evidence = new TreeMap<>();
        try (SemanticIndex index = SemanticIndex.open(model, seedRelativePath())) {
            for (AnnotationOccurrence occ : new FrameworkAnnotationIndex(index).annotations()) {
                FrameworkRegistry.Owner owner = registry.ownerOf(occ.annotationFqn());
                if (owner != null) {
                    evidence.computeIfAbsent(owner.frameworkId(), k -> new TreeMap<>())
                            .merge(occ.annotationFqn(), 1, Integer::sum);
                }
            }
        }

        List<String> resourceEvidence = resourceFrameworkEvidence(null);
        StringBuilder frameworks = new StringBuilder("[");
        boolean first = true;
        for (FrameworkPlugin plugin : registry.plugins()) {
            Map<String, Integer> annoCounts = evidence.get(plugin.id());
            List<String> pluginResourceEvidence = resourceEvidenceFor(resourceEvidence, plugin.id());
            boolean detected = (annoCounts != null && !annoCounts.isEmpty()) || !pluginResourceEvidence.isEmpty();
            if (!first) {
                frameworks.append(",");
            }
            first = false;
            frameworks.append("{")
                    .append("\"framework\":").append(JsonUtil.quote(plugin.id())).append(",")
                    .append("\"detected\":").append(detected).append(",")
                    .append("\"evidence\":").append(evidenceJson(annoCounts)).append(",")
                    .append("\"resourceEvidence\":").append(rawJsonArray(pluginResourceEvidence))
                    .append("}");
        }
        frameworks.append("]");

        return "{"
                + "\"accepted\":true,"
                + "\"operation\":\"detectFrameworks\","
                + "\"frameworks\":" + frameworks
                + "}";
    }

    private String findReferencesChecked(Map<String, Object> fields) throws IOException {
        String target = optString(fields, "target");
        if (target == null || target.isBlank()) {
            throw new FrameworkRefusal("framework_target_unresolved",
                    "A non-empty 'target' fully-qualified class name is required.");
        }
        String fqn = target.trim();
        Pattern named = Pattern.compile("(?<![A-Za-z0-9_$.])" + Pattern.quote(fqn) + "(?![A-Za-z0-9_$])");

        List<String> references = new ArrayList<>();
        try (SemanticIndex index = SemanticIndex.open(model, seedRelativePath())) {
            for (AnnotationOccurrence occ : new FrameworkAnnotationIndex(index).annotations()) {
                FrameworkRegistry.Owner owner = registry.ownerOf(occ.annotationFqn());
                if (owner == null) {
                    continue;
                }
                if (fqn.equals(occ.enclosingTypeFqn())) {
                    references.add(referenceJson(occ, owner, "declares", "HIGH"));
                } else if (named.matcher(occ.argumentText()).find()) {
                    references.add(referenceJson(occ, owner, "names", "MEDIUM"));
                }
            }
        }
        references.addAll(resourceFrameworkEvidence(fqn));

        StringBuilder refs = new StringBuilder("[");
        for (int i = 0; i < references.size(); i++) {
            if (i > 0) {
                refs.append(",");
            }
            refs.append(references.get(i));
        }
        refs.append("]");

        return "{"
                + "\"accepted\":true,"
                + "\"operation\":\"findFrameworkReferences\","
                + "\"target\":" + JsonUtil.quote(fqn) + ","
                + "\"references\":" + refs + ","
                + "\"stats\":{\"count\":" + references.size() + "}"
                + "}";
    }

    private String referenceJson(AnnotationOccurrence occ, FrameworkRegistry.Owner owner, String matchKind,
                                 String confidence) {
        return "{"
                + "\"framework\":" + JsonUtil.quote(owner.frameworkId()) + ","
                + "\"role\":" + JsonUtil.quote(owner.role()) + ","
                + "\"matchKind\":" + JsonUtil.quote(matchKind) + ","
                + "\"annotation\":" + JsonUtil.quote(occ.annotationFqn()) + ","
                + "\"path\":" + JsonUtil.quote(PlannerSupport.relative(projectRoot, occ.file())) + ","
                + "\"startOffset\":" + occ.start() + ","
                + "\"endOffset\":" + occ.end() + ","
                + "\"enclosingType\":" + JsonUtil.quote(occ.enclosingTypeFqn()) + ","
                + "\"elementKind\":" + JsonUtil.quote(occ.elementKind()) + ","
                + "\"elementName\":" + JsonUtil.quote(occ.elementName()) + ","
                + "\"confidence\":" + JsonUtil.quote(confidence)
                + "}";
    }


    private List<String> resourceFrameworkEvidence(String targetFqn) throws IOException {
        List<String> facts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (targetFqn != null && !targetFqn.isBlank()) {
            ResourceReferenceScanner.ScanResult scan = new ResourceReferenceScanner(projectRoot, model).referencesFor(Set.of(targetFqn));
            for (ResourceReference ref : scan.references()) {
                addResourceFact(facts, seen, ref, targetFqn);
            }
        }
        for (Path file : resourceFiles()) {
            String relative = PlannerSupport.relative(projectRoot, file);
            String lowerPath = relative.toLowerCase(Locale.ROOT);
            String content;
            try {
                content = Files.readString(file);
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
            String lower = content.toLowerCase(Locale.ROOT);
            if ((targetFqn == null || content.contains(targetFqn)) && lowerPath.endsWith(".xml")
                    && lower.contains("<bean") && lower.contains("class=")) {
                addRawResourceFact(facts, seen, "spring", "SPRING_XML_BEAN_CLASS", relative,
                        targetFqn == null ? "detects" : "names", "HIGH", targetFqn);
            }
            if ((targetFqn == null || content.contains(targetFqn))
                    && (lowerPath.endsWith("persistence.xml") || lowerPath.endsWith("orm.xml")
                    || lower.contains("<entity-mappings") || lower.contains("<persistence"))) {
                addRawResourceFact(facts, seen, "jpa", "JPA_XML_CLASS", relative,
                        targetFqn == null ? "detects" : "names", "HIGH", targetFqn);
            }
            if (targetFqn != null && (lower.contains("select ") || lower.contains(" from ") || lower.contains(" join "))
                    && content.contains(targetFqn)) {
                addRawResourceFact(facts, seen, "jpa", "JPQL_STRING_CANDIDATE", relative,
                        "names", "LOW", targetFqn);
            }
        }
        return facts;
    }

    private List<Path> resourceFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : ResourceRootModel.resourceRoots(model)) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .map(path -> path.toAbsolutePath().normalize())
                        .forEach(files::add);
            }
        }
        return files;
    }

    private void addResourceFact(List<String> facts, Set<String> seen, ResourceReference ref, String targetFqn) {
        FrameworkRegistry.Owner owner = ownerForResource(ref);
        if (owner == null) {
            return;
        }
        String confidence = ref.confidence() == ResourceConfidence.HIGH ? "HIGH"
                : ref.confidence() == ResourceConfidence.MEDIUM ? "MEDIUM" : "LOW";
        addRawResourceFact(facts, seen, owner.frameworkId(), ref.kind().name(),
                PlannerSupport.relative(projectRoot, ref.file()), "names", confidence, targetFqn);
    }

    private FrameworkRegistry.Owner ownerForResource(ResourceReference ref) {
        if (ref.kind() == ResourceReferenceKind.SPRING_BEAN_CLASS) {
            return new FrameworkRegistry.Owner("spring", "XML_BEAN");
        }
        if (ref.kind() == ResourceReferenceKind.JPA_ENTITY_CLASS) {
            return new FrameworkRegistry.Owner("jpa", "XML_ENTITY");
        }
        String path = PlannerSupport.relative(projectRoot, ref.file()).toLowerCase(Locale.ROOT);
        if (path.endsWith("persistence.xml") || path.endsWith("orm.xml")) {
            return new FrameworkRegistry.Owner("jpa", "XML_ENTITY");
        }
        return null;
    }

    private static void addRawResourceFact(List<String> facts, Set<String> seen, String framework, String kind,
                                           String relativePath, String matchKind, String confidence, String targetFqn) {
        String key = framework + "|" + kind + "|" + relativePath + "|" + matchKind + "|" + targetFqn;
        if (!seen.add(key)) {
            return;
        }
        facts.add("{"
                + "\"framework\":" + JsonUtil.quote(framework) + ","
                + "\"role\":" + JsonUtil.quote(kind) + ","
                + "\"matchKind\":" + JsonUtil.quote(matchKind) + ","
                + "\"path\":" + JsonUtil.quote(relativePath) + ","
                + "\"confidence\":" + JsonUtil.quote(confidence)
                + (targetFqn == null ? "" : ",\"target\":" + JsonUtil.quote(targetFqn))
                + "}");
    }

    private static String rawJsonArray(List<String> jsonObjects) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < jsonObjects.size(); i++) {
            if (i > 0) {
                out.append(",");
            }
            out.append(jsonObjects.get(i));
        }
        return out.append("]").toString();
    }

    private static List<String> resourceEvidenceFor(List<String> facts, String frameworkId) {
        List<String> selected = new ArrayList<>();
        String needle = "\"framework\":" + JsonUtil.quote(frameworkId);
        for (String fact : facts) {
            if (fact.contains(needle)) {
                selected.add(fact);
            }
        }
        return selected;
    }

    private static String evidenceJson(Map<String, Integer> annoCounts) {
        if (annoCounts == null || annoCounts.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : annoCounts.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("{")
                    .append("\"annotation\":").append(JsonUtil.quote(entry.getKey())).append(",")
                    .append("\"count\":").append(entry.getValue())
                    .append("}");
        }
        return sb.append("]").toString();
    }

    private String seedRelativePath() {
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path file : sourceSet.javaFiles()) {
                return PlannerSupport.relative(projectRoot, file);
            }
        }
        throw new FrameworkRefusal("framework_target_unresolved", "Project has no Java sources to scan.");
    }

    private static String optString(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
