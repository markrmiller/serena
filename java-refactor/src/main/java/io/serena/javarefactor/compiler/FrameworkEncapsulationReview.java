package io.serena.javarefactor.compiler;

import io.serena.javarefactor.compiler.FrameworkAnnotationCatalog.Owner;
import io.serena.javarefactor.compiler.FrameworkAnnotationIndex.AnnotationOccurrence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Encapsulating a field makes it {@code private} and routes access through generated getters/setters. Two frameworks
 * bind to the FIELD directly and can change behaviour when accessors appear (refactor-feature-plan-V3.md §16.2 / §16.3):
 *
 * <ul>
 *   <li><b>JPA</b> — a managed type ({@code @Entity}/{@code @Embeddable}/{@code @MappedSuperclass}) whose field carries a
 *       JPA mapping annotation ({@code @Id}, {@code @Column}, {@code @OneToMany}, …) uses <em>field</em> access. The new
 *       accessors are harmless while the annotations stay on the field, but moving them onto the generated getter would
 *       flip JPA to <em>property</em> access and change the persistence mapping — so a review warning is required.</li>
 *   <li><b>Jackson</b> — a field carrying {@code @JsonProperty}/{@code @JsonIgnore}/{@code @JsonValue} drives JSON
 *       binding. A newly public getter is a second accessor Jackson discovers via reflection, which can duplicate or
 *       rename the serialized property unless the annotation's binding is preserved — so a review warning is required.</li>
 * </ul>
 *
 * <p>Each warning is exact-FQN-gated through {@link FrameworkAnnotationCatalog} (never a package/name heuristic) and
 * emitted only when a real annotation occurrence is found on the encapsulated field — never a vacuous caveat. Lives in
 * the {@code compiler} layer beside {@link FrameworkAnnotationIndex} so V2 planners and V3 transformations share it.
 */
public final class FrameworkEncapsulationReview {

    private static final Set<String> JPA_MANAGED_TYPE_ROLES = Set.of("ENTITY", "EMBEDDABLE", "MAPPED_SUPERCLASS");
    private static final Set<String> JACKSON_MEMBER_ROLES = Set.of("JSON_PROPERTY", "JSON_IGNORE", "JSON_VALUE");

    private FrameworkEncapsulationReview() {
    }

    /**
     * Review-required warnings for encapsulating the field {@code fieldName} declared on {@code enclosingTypeFqn}.
     * Returns an empty list when the field binds to no framework (so the caller adds nothing rather than a vacuous note).
     */
    public static List<String> reviewWarnings(SemanticIndex index, String enclosingTypeFqn, String fieldName) {
        List<AnnotationOccurrence> occurrences = new FrameworkAnnotationIndex(index).annotations();

        List<String> jpaFieldAnnotations = new ArrayList<>();
        List<String> jacksonFieldAnnotations = new ArrayList<>();
        boolean enclosingIsManagedEntity = false;

        for (AnnotationOccurrence occ : occurrences) {
            if (!enclosingTypeFqn.equals(occ.enclosingTypeFqn())) {
                continue;
            }
            Owner owner = FrameworkAnnotationCatalog.ownerOf(occ.annotationFqn());
            if (owner == null) {
                continue;
            }
            if (isTypeDeclarationKind(occ.elementKind()) && JPA_MANAGED_TYPE_ROLES.contains(owner.role())) {
                enclosingIsManagedEntity = true;
                continue;
            }
            if (!"FIELD".equals(occ.elementKind()) || !fieldName.equals(occ.elementName())) {
                continue;
            }
            if ("jpa".equals(owner.frameworkId())) {
                jpaFieldAnnotations.add(occ.annotationFqn());
            } else if ("jackson".equals(owner.frameworkId()) && JACKSON_MEMBER_ROLES.contains(owner.role())) {
                jacksonFieldAnnotations.add(occ.annotationFqn());
            }
        }

        List<String> warnings = new ArrayList<>();
        if (enclosingIsManagedEntity && !jpaFieldAnnotations.isEmpty()) {
            warnings.add("JPA field encapsulation: field '" + fieldName + "' of managed type '" + enclosingTypeFqn
                    + "' carries JPA mapping annotation(s) " + distinct(jpaFieldAnnotations) + ", so the type uses FIELD"
                    + " access. The generated accessors keep the annotations on the field (mapping unchanged) — but do"
                    + " NOT move them onto the getter, which would switch JPA to PROPERTY access and change the mapping.");
        }
        if (!jacksonFieldAnnotations.isEmpty()) {
            warnings.add("Jackson field encapsulation: field '" + fieldName + "' carries " + distinct(jacksonFieldAnnotations)
                    + "; the new public getter is a second accessor Jackson discovers by reflection. Verify the serialized"
                    + " property name is unchanged — keep the Jackson annotation on the field (or move it to the getter)"
                    + " to preserve the JSON binding.");
        }
        return warnings;
    }

    private static String distinct(List<String> fqns) {
        Set<String> unique = new LinkedHashSet<>(fqns);
        return "@" + String.join(", @", unique);
    }

    private static boolean isTypeDeclarationKind(String elementKind) {
        return switch (elementKind) {
            case "CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION_TYPE" -> true;
            default -> false;
        };
    }
}
