package io.serena.javarefactor.v3.frameworks;

import io.serena.javarefactor.compiler.FrameworkAnnotationCatalog;
import io.serena.javarefactor.compiler.FrameworkAnnotationIndex.AnnotationOccurrence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The built-in {@link FrameworkPlugin}s (refactor-feature-plan-V3.md §16): Spring, JPA (jakarta + legacy javax),
 * Jackson, and JUnit (Jupiter + legacy JUnit 4). Each plugin's annotation→role map is derived from the shared
 * {@link FrameworkAnnotationCatalog} so the read-only SPI ({@code detect}/{@code findReferences}) and the planners'
 * deletion conservatism recognize exactly the same fully-qualified annotations — never package-name heuristics.
 *
 * <p>Each plugin additionally implements {@link FrameworkPlugin#participate(SymbolChange, TransformationContext)} with
 * the per-framework participation rules from §16.1–§16.4: contributing deletion vetoes, reachability roots, resource-
 * edit descriptions, and review-required warnings, all keyed off the project's exact compiler-resolved annotation facts.
 */
final class FrameworkPlugins {

    private FrameworkPlugins() {
    }

    private static String ownerType(String symbol) {
        if (symbol == null) {
            return null;
        }
        int marker = symbol.indexOf('#');
        return marker > 0 ? symbol.substring(0, marker) : symbol;
    }

    private static String memberName(String symbol) {
        if (symbol == null) {
            return null;
        }
        int marker = symbol.indexOf('#');
        return marker > 0 && marker + 1 < symbol.length() ? symbol.substring(marker + 1) : null;
    }

    /** Base plugin holding the framework id + annotation→role map sourced from the shared catalog. */
    private abstract static class BasePlugin implements FrameworkPlugin {
        private final String id;
        private final Map<String, String> annotationRoles;

        BasePlugin(String id) {
            this.id = id;
            Map<String, String> roles = new LinkedHashMap<>();
            for (Map.Entry<String, FrameworkAnnotationCatalog.Owner> entry
                    : FrameworkAnnotationCatalog.owners().entrySet()) {
                if (entry.getValue().frameworkId().equals(id)) {
                    roles.put(entry.getKey(), entry.getValue().role());
                }
            }
            this.annotationRoles = Map.copyOf(roles);
        }

        @Override
        public final String id() {
            return id;
        }

        @Override
        public final Map<String, String> annotationRoles() {
            return annotationRoles;
        }

        /** Whether this plugin owns {@code annotationFqn}. */
        final boolean owns(String annotationFqn) {
            return annotationRoles.containsKey(annotationFqn);
        }

        /** The role this plugin assigns {@code annotationFqn}, or {@code null} if it does not own it. */
        final String roleOf(String annotationFqn) {
            return annotationRoles.get(annotationFqn);
        }
    }

    static FrameworkPlugin spring() {
        return new SpringPlugin();
    }

    static FrameworkPlugin jpa() {
        return new JpaPlugin();
    }

    static FrameworkPlugin jackson() {
        return new JacksonPlugin();
    }

    static FrameworkPlugin junit() {
        return new JUnitPlugin();
    }

    // ── Spring (§16.1) ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Spring participation (§16.1): annotated components/beans/handlers are framework entry points, so deleting one is
     * vetoed and request handlers are never reported dead by "no Java references" alone. Class names in Spring bean-
     * definition XML are flagged for the exact-class resource rewrite; string bean names are review-required.
     */
    private static final class SpringPlugin extends BasePlugin {
        SpringPlugin() {
            super("spring");
        }

        @Override
        public FrameworkParticipation participate(SymbolChange change, TransformationContext context) {
            FrameworkParticipation.Builder out = new FrameworkParticipation.Builder();
            for (AnnotationOccurrence occ : context.annotations()) {
                if (!owns(occ.annotationFqn())) {
                    continue;
                }
                String role = roleOf(occ.annotationFqn());
                String enclosing = occ.enclosingTypeFqn();
                boolean typeLevelComponent = isTypeLevelComponent(role);
                switch (change.kind()) {
                    case SAFE_DELETE -> {
                        if (typeLevelComponent && enclosing.equals(change.targetFqn())) {
                            out.block(enclosing, "Spring " + role + " bean (@" + occ.annotationFqn()
                                    + ") is managed by the container and may be wired by type — delete it through the"
                                    + " Spring configuration, not by Java references alone.");
                        }
                    }
                    case DEAD_CODE_SCAN -> {
                        // A type-level component is an externally-wired entry point: the whole class is reachable
                        // through the container, so it must not be reported dead. Method-level handlers (@Bean,
                        // request mappings) are deliberately left to the analyzer's existing low-confidence framework-
                        // entry treatment (surfaced for review, never silently rooted) so this only ever adds roots the
                        // analyzer did not already model — it never widens what is reported.
                        if (typeLevelComponent) {
                            out.root(enclosing);
                        }
                    }
                    case RENAME_TYPE -> {
                        if (typeLevelComponent && enclosing.equals(change.targetFqn())) {
                            // B5: a TYPED framework-owned resource edit. The SPI does not scan the descriptor files, so it
                            // cannot emit a concrete parse-verified TextEdit — this is a manual-review-required
                            // frameworkBoundaryChange, never auto-applied.
                            out.resourceEdit(FrameworkResourceEdit.manualReview(
                                    "Spring bean-definition XML (<bean class=\"…\">)",
                                    FrameworkResourceEdit.Kind.EXACT_CLASS_NAME,
                                    "Rewrite exact class name '" + enclosing + "' → '" + change.newName()
                                            + "' in any Spring bean-definition XML (<bean class=\"…\">)."));
                            out.warn("Spring bean '" + enclosing + "' renamed: any string bean name or"
                                    + " component-scan base-package referencing it needs manual review.");
                        }
                    }
                    case RENAME_PACKAGE -> {
                        if (typeLevelComponent && enclosing.startsWith(change.targetFqn() + ".")) {
                            out.resourceEdit(FrameworkResourceEdit.manualReview(
                                    "Spring bean-definition XML / @ComponentScan base packages",
                                    FrameworkResourceEdit.Kind.PACKAGE_PREFIX,
                                    "Rewrite Spring bean class '" + enclosing
                                            + "' to the new package in bean-definition XML and @ComponentScan base"
                                            + " packages."));
                        }
                    }
                    default -> {
                        // no participation for other kinds
                    }
                }
            }
            return out.build();
        }

        private static boolean isTypeLevelComponent(String role) {
            return switch (role) {
                case "COMPONENT", "SERVICE", "REPOSITORY", "CONTROLLER", "REST_CONTROLLER", "CONFIGURATION" -> true;
                default -> false;
            };
        }
    }

    // ── JPA (§16.2) ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * JPA participation (§16.2): entities/embeddables/mapped-superclasses are framework entry points (vetoed on delete,
     * rooted in the dead-code scan). On rename the entity class reference in persistence/ORM XML is flagged for the
     * exact-class rewrite; JPQL string queries are review-required, never auto-rewritten. Metadata is validated: an
     * {@code @Entity} type carrying no mapped {@code @Id} is reported as a likely-misconfigured entity.
     */
    private static final class JpaPlugin extends BasePlugin {
        JpaPlugin() {
            super("jpa");
        }

        @Override
        public FrameworkParticipation participate(SymbolChange change, TransformationContext context) {
            FrameworkParticipation.Builder out = new FrameworkParticipation.Builder();
            for (AnnotationOccurrence occ : context.annotations()) {
                if (!owns(occ.annotationFqn())) {
                    continue;
                }
                String role = roleOf(occ.annotationFqn());
                String enclosing = occ.enclosingTypeFqn();
                boolean entityType = isEntityType(role);
                switch (change.kind()) {
                    case SAFE_DELETE -> {
                        if (entityType && enclosing.equals(change.targetFqn())) {
                            out.block(enclosing, "JPA " + role + " (@" + occ.annotationFqn()
                                    + ") is a persistence entry point mapped to a database table — delete it through the"
                                    + " persistence configuration, not by Java references alone.");
                            validateEntityMetadata(occ, context, out);
                        }
                    }
                    case DEAD_CODE_SCAN -> {
                        if (entityType) {
                            out.root(enclosing);
                        }
                    }
                    case RENAME_TYPE -> {
                        if (entityType && enclosing.equals(change.targetFqn())) {
                            // B5: a TYPED, manual-review-required framework-owned resource edit (the SPI cannot produce a
                            // concrete parse-verified edit for the persistence/ORM descriptor here).
                            out.resourceEdit(FrameworkResourceEdit.manualReview(
                                    "persistence.xml / ORM mapping XML (<class>…</class>)",
                                    FrameworkResourceEdit.Kind.METADATA_MAPPING,
                                    "Rewrite exact entity class '" + enclosing + "' → '" + change.newName()
                                            + "' in persistence.xml / ORM mapping XML (<class>…</class>)."));
                            out.warn("JPA entity '" + enclosing + "' renamed: JPQL string queries naming it are NOT"
                                    + " rewritten automatically — review @NamedQuery and string queries.");
                            validateEntityMetadata(occ, context, out);
                        }
                    }
                    case RENAME_FIELD, ENCAPSULATE_FIELD -> {
                        String ownerType = ownerType(change.targetFqn());
                        if (entityType && enclosing.equals(ownerType)) {
                            out.warn("JPA access strategy risk on '" + change.targetFqn()
                                    + "': field/property access can change persistence semantics; review @Access, @Id placement, "
                                    + "and persistence/ORM XML before applying this member refactor.");
                        }
                    }
                    default -> {
                        // no participation for other kinds
                    }
                }
            }
            return out.build();
        }

        /**
         * Metadata validation (§16.2 / §16 "validate framework metadata"): an {@code @Entity}/{@code @MappedSuperclass}
         * type that carries no {@code @Id}/{@code @EmbeddedId} anywhere on the type is almost certainly misconfigured.
         */
        private void validateEntityMetadata(AnnotationOccurrence entity, TransformationContext context,
                FrameworkParticipation.Builder out) {
            String type = entity.enclosingTypeFqn();
            boolean hasId = false;
            for (AnnotationOccurrence occ : context.annotations()) {
                if (!type.equals(occ.enclosingTypeFqn())) {
                    continue;
                }
                String role = roleOf(occ.annotationFqn());
                if ("ID".equals(role) || "EMBEDDED_ID".equals(role)) {
                    hasId = true;
                    break;
                }
            }
            if (!hasId) {
                out.warn("JPA metadata: entity '" + type + "' declares no @Id/@EmbeddedId — verify the identifier"
                        + " mapping before changing this type.");
            }
        }

        private static boolean isEntityType(String role) {
            return switch (role) {
                case "ENTITY", "EMBEDDABLE", "MAPPED_SUPERCLASS" -> true;
                default -> false;
            };
        }
    }

    // ── Jackson (§16.3) ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Jackson participation (§16.3): Jackson does not own a type's reachability, so it never vetoes deletion. It warns
     * when a rename would change a serialized type/property name that must stay stable for wire compatibility.
     */
    private static final class JacksonPlugin extends BasePlugin {
        JacksonPlugin() {
            super("jackson");
        }

        @Override
        public FrameworkParticipation participate(SymbolChange change, TransformationContext context) {
            FrameworkParticipation.Builder out = new FrameworkParticipation.Builder();
            if (change.kind() != SymbolChange.Kind.RENAME_TYPE
                    && change.kind() != SymbolChange.Kind.RENAME_FIELD
                    && change.kind() != SymbolChange.Kind.ENCAPSULATE_FIELD) {
                return out.build();
            }
            String affectedType = change.kind() == SymbolChange.Kind.RENAME_TYPE ? change.targetFqn() : ownerType(change.targetFqn());
            String affectedMember = memberName(change.targetFqn());
            for (AnnotationOccurrence occ : context.annotations()) {
                if (!owns(occ.annotationFqn())) {
                    continue;
                }
                if (!occ.enclosingTypeFqn().equals(affectedType)) {
                    continue;
                }
                String role = roleOf(occ.annotationFqn());
                if ((change.kind() == SymbolChange.Kind.RENAME_FIELD || change.kind() == SymbolChange.Kind.ENCAPSULATE_FIELD)
                        && affectedMember != null && affectedMember.equals(occ.elementName())) {
                    out.warn("Jackson @" + occ.annotationFqn() + " on '" + change.targetFqn()
                            + "': field rename/encapsulation can change serialized property access semantics; preserve "
                            + "the wire name explicitly with @JsonProperty or review the JSON contract.");
                } else if ("JSON_TYPE_NAME".equals(role) || "JSON_SUB_TYPES".equals(role)) {
                    out.warn("Jackson @" + occ.annotationFqn() + " on '" + occ.enclosingTypeFqn()
                            + "': the serialized type name is NOT changed by this rename — confirm wire compatibility"
                            + " before changing the JSON type id.");
                } else if ("JSON_PROPERTY".equals(role)) {
                    out.warn("Jackson @JsonProperty on '" + occ.enclosingTypeFqn() + "#" + occ.elementName()
                            + "': the serialized property name stays stable across this rename — migrate it explicitly"
                            + " if the JSON contract must change.");
                }
            }
            return out.build();
        }
    }

    // ── JUnit (§16.4) ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * JUnit participation (§16.4): test methods/classes are reachability roots (run reflectively by the test runner),
     * so they are never reported dead and a delete of a test type is vetoed unless tests are explicitly in scope. Test
     * utilities reached only by parameterized/reflective runners are surfaced for review rather than silently deleted.
     */
    private static final class JUnitPlugin extends BasePlugin {
        JUnitPlugin() {
            super("junit");
        }

        @Override
        public FrameworkParticipation participate(SymbolChange change, TransformationContext context) {
            FrameworkParticipation.Builder out = new FrameworkParticipation.Builder();
            for (AnnotationOccurrence occ : context.annotations()) {
                if (!owns(occ.annotationFqn())) {
                    continue;
                }
                String role = roleOf(occ.annotationFqn());
                String enclosing = occ.enclosingTypeFqn();
                boolean testMethod = isTestMethod(role);
                switch (change.kind()) {
                    case DEAD_CODE_SCAN -> {
                        // A @Test method (and so its enclosing test class) is a reachability root: the runner invokes it
                        // reflectively, so "no Java references" must not report it dead.
                        if (testMethod) {
                            out.root(enclosing + "#" + occ.elementName());
                            out.root(enclosing);
                        }
                    }
                    case SAFE_DELETE -> {
                        if (testMethod && enclosing.equals(change.targetFqn())) {
                            out.block(enclosing, "JUnit test type '" + enclosing + "' is run reflectively by the test"
                                    + " runner — deleting it removes test coverage rather than dead code.");
                        }
                    }
                    default -> {
                        // no participation for other kinds
                    }
                }
            }
            return out.build();
        }

        private static boolean isTestMethod(String role) {
            return switch (role) {
                case "TEST", "PARAMETERIZED_TEST", "REPEATED_TEST", "TEST_FACTORY" -> true;
                default -> false;
            };
        }
    }
}
