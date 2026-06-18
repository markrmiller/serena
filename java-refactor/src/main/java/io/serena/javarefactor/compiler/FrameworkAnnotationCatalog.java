package io.serena.javarefactor.compiler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single, exact source of truth for the framework annotations V3 recognizes (refactor-feature-plan-V3.md §16):
 * Spring, JPA (jakarta + legacy javax), Jackson and JUnit (Jupiter + legacy JUnit 4). Every annotation is keyed by its
 * fully-qualified name and mapped to the framework that owns it plus the semantic role it assigns — never matched by
 * package-name or simple-name heuristics.
 *
 * <p>This catalog is consumed by BOTH halves of the framework story so they can never drift apart:
 * <ul>
 *   <li>the read-only SPI ({@code frameworks.detect}/{@code frameworks.findReferences}, via
 *       {@code io.serena.javarefactor.v3.frameworks}) reports presence + references from these exact FQNs; and</li>
 *   <li>the planners' deletion conservatism ({@link ReachabilityGraph}) treats a symbol carrying one of these exact
 *       annotations as a framework entry point — so a framework-managed type makes deletion <em>more</em> conservative
 *       (never more aggressive), and a user's own same-simple-name annotation (e.g. {@code com.example.Service}) does
 *       NOT falsely block deletion.</li>
 * </ul>
 *
 * <p>It lives in the {@code compiler} package (alongside {@link FrameworkAnnotationIndex}) so the lower compiler layer
 * can use it without depending on the higher {@code v3} layer; the {@code v3.frameworks} plugins source their per-
 * framework maps from here.
 */
public final class FrameworkAnnotationCatalog {

    /** Which framework owns an annotation, and the semantic role it assigns. */
    public record Owner(String frameworkId, String role) {
    }

    private static final Map<String, Owner> OWNERS = build();

    private FrameworkAnnotationCatalog() {
    }

    /** Immutable map of every recognized annotation FQN → its owning framework + role. */
    public static Map<String, Owner> owners() {
        return OWNERS;
    }

    /** The framework + role that owns {@code annotationFqn}, or {@code null} if no framework claims it. */
    public static Owner ownerOf(String annotationFqn) {
        return OWNERS.get(annotationFqn);
    }

    /**
     * The reason a symbol carrying {@code annotationFqn} is a framework entry point (a deletion-conservatism fragment
     * such as {@code "carries Spring @org.springframework.stereotype.Service (SERVICE), a framework entry point"}), or
     * {@code null} if the annotation is not a recognized framework annotation.
     */
    public static String entryPointReason(String annotationFqn) {
        Owner owner = OWNERS.get(annotationFqn);
        if (owner == null) {
            return null;
        }
        return "carries " + frameworkLabel(owner.frameworkId()) + " @" + annotationFqn
                + " (" + owner.role() + "), a framework entry point";
    }

    private static String frameworkLabel(String frameworkId) {
        return switch (frameworkId) {
            case "spring" -> "Spring";
            case "jpa" -> "JPA";
            case "jackson" -> "Jackson";
            case "junit" -> "JUnit";
            default -> frameworkId;
        };
    }

    private static Map<String, Owner> build() {
        Map<String, Owner> m = new LinkedHashMap<>();

        // ── Spring ────────────────────────────────────────────────────────────────────────────────────────────────
        put(m, "spring", "COMPONENT", "org.springframework.stereotype.Component");
        put(m, "spring", "SERVICE", "org.springframework.stereotype.Service");
        put(m, "spring", "REPOSITORY", "org.springframework.stereotype.Repository");
        put(m, "spring", "CONTROLLER", "org.springframework.stereotype.Controller");
        put(m, "spring", "REST_CONTROLLER", "org.springframework.web.bind.annotation.RestController");
        put(m, "spring", "CONFIGURATION", "org.springframework.context.annotation.Configuration");
        put(m, "spring", "BEAN", "org.springframework.context.annotation.Bean");
        put(m, "spring", "IMPORT", "org.springframework.context.annotation.Import");
        put(m, "spring", "COMPONENT_SCAN", "org.springframework.context.annotation.ComponentScan");
        put(m, "spring", "AUTOWIRED", "org.springframework.beans.factory.annotation.Autowired");
        put(m, "spring", "QUALIFIER", "org.springframework.beans.factory.annotation.Qualifier");
        put(m, "spring", "REQUEST_MAPPING", "org.springframework.web.bind.annotation.RequestMapping");
        put(m, "spring", "GET_MAPPING", "org.springframework.web.bind.annotation.GetMapping");
        put(m, "spring", "POST_MAPPING", "org.springframework.web.bind.annotation.PostMapping");
        put(m, "spring", "PUT_MAPPING", "org.springframework.web.bind.annotation.PutMapping");
        put(m, "spring", "DELETE_MAPPING", "org.springframework.web.bind.annotation.DeleteMapping");
        put(m, "spring", "PATCH_MAPPING", "org.springframework.web.bind.annotation.PatchMapping");
        put(m, "spring", "EVENT_LISTENER", "org.springframework.context.event.EventListener");
        put(m, "spring", "SCHEDULED", "org.springframework.scheduling.annotation.Scheduled");

        // ── JPA (jakarta + legacy javax) ──────────────────────────────────────────────────────────────────────────
        for (String pkg : new String[] {"jakarta.persistence", "javax.persistence"}) {
            put(m, "jpa", "ENTITY", pkg + ".Entity");
            put(m, "jpa", "EMBEDDABLE", pkg + ".Embeddable");
            put(m, "jpa", "MAPPED_SUPERCLASS", pkg + ".MappedSuperclass");
            put(m, "jpa", "TABLE", pkg + ".Table");
            put(m, "jpa", "ID", pkg + ".Id");
            put(m, "jpa", "EMBEDDED_ID", pkg + ".EmbeddedId");
            put(m, "jpa", "COLUMN", pkg + ".Column");
            put(m, "jpa", "BASIC", pkg + ".Basic");
            put(m, "jpa", "VERSION", pkg + ".Version");
            put(m, "jpa", "LOB", pkg + ".Lob");
            put(m, "jpa", "ENUMERATED", pkg + ".Enumerated");
            put(m, "jpa", "TEMPORAL", pkg + ".Temporal");
            put(m, "jpa", "TRANSIENT", pkg + ".Transient");
            put(m, "jpa", "ACCESS", pkg + ".Access");
            put(m, "jpa", "EMBEDDED", pkg + ".Embedded");
            put(m, "jpa", "ELEMENT_COLLECTION", pkg + ".ElementCollection");
            put(m, "jpa", "CONVERTER", pkg + ".Converter");
            put(m, "jpa", "ONE_TO_ONE", pkg + ".OneToOne");
            put(m, "jpa", "ONE_TO_MANY", pkg + ".OneToMany");
            put(m, "jpa", "MANY_TO_ONE", pkg + ".ManyToOne");
            put(m, "jpa", "MANY_TO_MANY", pkg + ".ManyToMany");
            put(m, "jpa", "JOIN_COLUMN", pkg + ".JoinColumn");
            put(m, "jpa", "NAMED_QUERY", pkg + ".NamedQuery");
            put(m, "jpa", "NAMED_QUERIES", pkg + ".NamedQueries");
        }

        // ── Jackson ───────────────────────────────────────────────────────────────────────────────────────────────
        put(m, "jackson", "JSON_PROPERTY", "com.fasterxml.jackson.annotation.JsonProperty");
        put(m, "jackson", "JSON_CREATOR", "com.fasterxml.jackson.annotation.JsonCreator");
        put(m, "jackson", "JSON_VALUE", "com.fasterxml.jackson.annotation.JsonValue");
        put(m, "jackson", "JSON_IGNORE", "com.fasterxml.jackson.annotation.JsonIgnore");
        put(m, "jackson", "JSON_IGNORE_PROPERTIES", "com.fasterxml.jackson.annotation.JsonIgnoreProperties");
        put(m, "jackson", "JSON_TYPE_INFO", "com.fasterxml.jackson.annotation.JsonTypeInfo");
        put(m, "jackson", "JSON_SUB_TYPES", "com.fasterxml.jackson.annotation.JsonSubTypes");
        put(m, "jackson", "JSON_TYPE_NAME", "com.fasterxml.jackson.annotation.JsonTypeName");
        put(m, "jackson", "JSON_SERIALIZE", "com.fasterxml.jackson.databind.annotation.JsonSerialize");
        put(m, "jackson", "JSON_DESERIALIZE", "com.fasterxml.jackson.databind.annotation.JsonDeserialize");

        // ── JUnit (Jupiter + legacy JUnit 4) ──────────────────────────────────────────────────────────────────────
        put(m, "junit", "TEST", "org.junit.jupiter.api.Test");
        put(m, "junit", "PARAMETERIZED_TEST", "org.junit.jupiter.params.ParameterizedTest");
        put(m, "junit", "REPEATED_TEST", "org.junit.jupiter.api.RepeatedTest");
        put(m, "junit", "TEST_FACTORY", "org.junit.jupiter.api.TestFactory");
        put(m, "junit", "BEFORE_EACH", "org.junit.jupiter.api.BeforeEach");
        put(m, "junit", "AFTER_EACH", "org.junit.jupiter.api.AfterEach");
        put(m, "junit", "BEFORE_ALL", "org.junit.jupiter.api.BeforeAll");
        put(m, "junit", "AFTER_ALL", "org.junit.jupiter.api.AfterAll");
        put(m, "junit", "NESTED", "org.junit.jupiter.api.Nested");
        put(m, "junit", "DISABLED", "org.junit.jupiter.api.Disabled");
        put(m, "junit", "EXTEND_WITH", "org.junit.jupiter.api.extension.ExtendWith");
        put(m, "junit", "TEST", "org.junit.Test");
        put(m, "junit", "RUN_WITH", "org.junit.runner.RunWith");
        put(m, "junit", "BEFORE", "org.junit.Before");
        put(m, "junit", "AFTER", "org.junit.After");
        put(m, "junit", "BEFORE_CLASS", "org.junit.BeforeClass");
        put(m, "junit", "AFTER_CLASS", "org.junit.AfterClass");

        return Map.copyOf(m);
    }

    private static void put(Map<String, Owner> m, String frameworkId, String role, String annotationFqn) {
        m.putIfAbsent(annotationFqn, new Owner(frameworkId, role));
    }
}
