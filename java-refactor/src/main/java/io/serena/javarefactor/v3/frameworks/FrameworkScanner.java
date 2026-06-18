package io.serena.javarefactor.v3.frameworks;

import io.serena.javarefactor.compiler.FrameworkAnnotationIndex;
import io.serena.javarefactor.compiler.FrameworkAnnotationIndex.AnnotationOccurrence;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.protocol.JsonUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

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

        StringBuilder frameworks = new StringBuilder("[");
        boolean first = true;
        for (FrameworkPlugin plugin : registry.plugins()) {
            Map<String, Integer> annoCounts = evidence.get(plugin.id());
            boolean detected = annoCounts != null && !annoCounts.isEmpty();
            if (!first) {
                frameworks.append(",");
            }
            first = false;
            frameworks.append("{")
                    .append("\"framework\":").append(JsonUtil.quote(plugin.id())).append(",")
                    .append("\"detected\":").append(detected).append(",")
                    .append("\"evidence\":").append(evidenceJson(annoCounts))
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
