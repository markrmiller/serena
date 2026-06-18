package io.serena.javarefactor.v3.validation;

import io.serena.javarefactor.compiler.DeclaredTypeNames;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.v3.resources.ResourcePlanner;
import io.serena.javarefactor.v3.resources.ResourceQuery;
import io.serena.javarefactor.v3.resources.ResourceReference;
import io.serena.javarefactor.v3.resources.ResourceReferenceKind;
import io.serena.javarefactor.v3.resources.ResourceScanScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Static-validation layer 7 (refactor-feature-plan-V3.md §18.1.7) and the resolution half of framework validation
 * (§18.3): after a staged edit, EXACT string-encoded class references in resources — Spring/CDI {@code <bean class="…">},
 * JPA {@code <class>…</class>}, {@code META-INF/services/<fqn>} provider lines, exact-FQN property/yaml/json values — must
 * still point to a class that exists. A rename/move/delete that changes or removes a type's FQN without rewriting the
 * resource leaves a dangling reference the compiler never sees.
 *
 * <p>The check is <em>edit-scoped and exact</em>, so it produces no false positives. It flags a resource reference ONLY
 * when the referenced FQN is one the staged edit actually removes — the set difference between the types declared in the
 * files the overlay deletes/renames-away and the types it (re)declares in its changed files (both computed by a real
 * javac parse via {@link DeclaredTypeNames}). A library type, an unchanged project type, or a type the edit also renamed
 * inside the resource is never flagged: removed FQNs come from the edit itself, and the post-overlay content of each
 * resource is consulted so a reference the same edit already rewrote is not reported.</p>
 */
public final class ResourceReferenceValidation {

    /**
     * Resource reference kinds that name an EXACT class (so a dangling one is a hard breakage). Package-prefix tokens and
     * reflective string candidates are intentionally excluded: the former is a broad heuristic, the latter is never an
     * authoritative class reference, and §18.1.7 scopes this check to exact references.
     */
    private static final Set<ResourceReferenceKind> EXACT_KINDS = Set.of(
            ResourceReferenceKind.EXACT_CLASS_NAME,
            ResourceReferenceKind.SPRING_BEAN_CLASS,
            ResourceReferenceKind.JPA_ENTITY_CLASS,
            ResourceReferenceKind.JACKSON_TYPE_NAME,
            ResourceReferenceKind.JUNIT_CLASS_NAME,
            ResourceReferenceKind.SERVICE_LOADER_PROVIDER);

    private ResourceReferenceValidation() {
    }

    /**
     * Dangling-resource-reference findings for a staged overlay. {@code changedFiles} maps project-relative paths to new
     * full content, {@code deletedFiles} lists relative paths removed, and {@code renamedFiles} pairs {@code oldPath}
     * with {@code newPath}. Returns one human-readable finding per dangling exact reference, deduplicated and ordered;
     * empty when the edit removes no type or every reference to a removed type was rewritten by the same edit.
     */
    public static List<String> findings(
            JavaProjectModel model,
            Map<String, Object> changedFiles,
            List<Object> deletedFiles,
            List<Object> renamedFiles) {
        Path projectRoot = model.projectRoot();

        Map<String, String> changedByRel = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : changedFiles.entrySet()) {
            changedByRel.put(normalizeRel(entry.getKey()), String.valueOf(entry.getValue()));
        }
        Set<String> removedRel = new LinkedHashSet<>();
        for (Object deleted : deletedFiles) {
            removedRel.add(normalizeRel(String.valueOf(deleted)));
        }
        List<String> renamedOldRel = new ArrayList<>();
        for (Object rename : renamedFiles) {
            if (rename instanceof Map<?, ?> pair && pair.get("oldPath") != null) {
                String oldRel = normalizeRel(String.valueOf(pair.get("oldPath")));
                removedRel.add(oldRel);
                renamedOldRel.add(oldRel);
            }
        }

        // Types declared in the files this edit deletes or renames-away (read from disk: the overlay is in-memory).
        List<String> goneSources = new ArrayList<>();
        for (String rel : removedRel) {
            if (rel.endsWith(".java")) {
                String content = readDisk(projectRoot.resolve(rel));
                if (content != null) {
                    goneSources.add(content);
                }
            }
        }
        Set<String> declaredInRemoved = DeclaredTypeNames.from(goneSources);
        if (declaredInRemoved.isEmpty()) {
            return List.of();
        }

        // Types the edit (re)declares in its changed Java files — a rename's new file re-declares the new FQN, so the old
        // FQN is genuinely gone while the new one is not. Subtracting these yields exactly the FQNs the edit removes.
        List<String> changedJavaSources = new ArrayList<>();
        for (Map.Entry<String, String> entry : changedByRel.entrySet()) {
            if (entry.getKey().endsWith(".java")) {
                changedJavaSources.add(entry.getValue());
            }
        }
        Set<String> declaredAfter = DeclaredTypeNames.from(changedJavaSources);

        Set<String> removedFqns = new LinkedHashSet<>(declaredInRemoved);
        removedFqns.removeAll(declaredAfter);
        if (removedFqns.isEmpty()) {
            return List.of();
        }

        List<ResourceReference> references = scanReferences(model, removedFqns);
        Set<String> findings = new TreeSet<>();
        for (ResourceReference ref : references) {
            if (!EXACT_KINDS.contains(ref.kind())) {
                continue;
            }
            String rel = relativize(projectRoot, ref.file());
            if (removedRel.contains(rel)) {
                continue; // the resource file itself is being removed — nothing dangles
            }
            String postContent = changedByRel.get(rel); // null => the overlay does not change this resource
            String token = ref.oldText() != null ? ref.oldText() : ref.target();
            boolean stillReferences = postContent == null || postContent.contains(token);
            if (stillReferences) {
                findings.add("Dangling resource reference: '" + rel + "' references type '" + ref.target()
                        + "' (" + ref.kind().name() + ") which this edit removes. Rewrite or remove the reference, or"
                        + " include the resource rewrite in this edit.");
            }
        }
        return new ArrayList<>(findings);
    }

    private static List<ResourceReference> scanReferences(JavaProjectModel model, Collection<String> fqns) {
        List<ResourceQuery> queries = new ArrayList<>();
        for (String fqn : fqns) {
            queries.add(new ResourceQuery(fqn, false));
        }
        try {
            return new ResourcePlanner(model.projectRoot(), model).referencesTo(queries, ResourceScanScope.all());
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static String readDisk(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String relativize(Path projectRoot, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        Path rel = absolute.startsWith(root) ? root.relativize(absolute) : absolute;
        return rel.toString().replace('\\', '/');
    }

    private static String normalizeRel(String path) {
        return path.replace('\\', '/');
    }
}
