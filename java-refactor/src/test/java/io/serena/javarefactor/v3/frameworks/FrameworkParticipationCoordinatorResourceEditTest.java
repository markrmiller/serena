package io.serena.javarefactor.v3.frameworks;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B07: proves the {@link FrameworkParticipationCoordinator} drives the §15 base resource SPI to emit CONCRETE,
 * parse-verified {@link FrameworkResourceEdit}s for a type rename — not the descriptor-level manual-review markers the
 * plugins emit on their own — for every covered descriptor case: a Spring {@code <bean class="…">}, an exact dotted FQN
 * token in a {@code .properties} resource, and a JPA {@code persistence.xml} {@code <class>…</class>} element. It also
 * proves the genuinely-ambiguous constructs the plugins flag (string bean names, JPQL/@NamedQuery strings) stay as
 * review-required WARNINGS rather than being silently rewritten.
 *
 * <p>The framework annotation types are declared IN the fixture so javac resolves them through the {@code Element}/
 * {@code AnnotationMirror} model exactly as it would against the real Spring/JPA jars (the
 * {@link io.serena.javarefactor.compiler.FrameworkAnnotationIndex} never guesses from text).
 */
class FrameworkParticipationCoordinatorResourceEditTest {

    @Test
    void emitsConcreteEditsForSpringBeanClassAndExactPropertyToken(@TempDir Path tmp) throws IOException {
        Map<String, String> sources = new TreeMap<>();
        springStereotype(sources);
        sources.put("com/acme/OldService.java", ""
                + "package com.acme;\n"
                + "import org.springframework.stereotype.Service;\n"
                + "@Service\n"
                + "public class OldService {}\n");

        Map<String, String> resources = new TreeMap<>();
        resources.put("beans.xml", ""
                + "<beans>\n"
                + "    <bean id=\"svc\" class=\"com.acme.OldService\"/>\n"
                + "</beans>\n");
        resources.put("wiring.properties", "handler=com.acme.OldService\n");

        JavaProjectModel model = model(tmp, sources, resources);
        FrameworkParticipationCoordinator.Result result = new FrameworkParticipationCoordinator()
                .participate(model, SymbolChange.renameType("com.acme.OldService", "com.acme.NewService"));

        List<FrameworkResourceEdit> concrete = concreteEdits(result);
        // Two concrete, parse-verified EXACT_CLASS_NAME edits: the Spring bean class attribute and the properties token.
        assertEquals(2, concrete.size(),
                "expected concrete edits for the Spring bean class and the properties token: " + concrete);
        for (FrameworkResourceEdit edit : concrete) {
            assertEquals(FrameworkResourceEdit.Kind.EXACT_CLASS_NAME, edit.kind(), edit.toString());
            assertNotNull(edit.textEdit(), "a concrete edit must carry a TextEdit: " + edit);
            assertEquals("com.acme.NewService", edit.textEdit().newText(), edit.toString());
        }
        assertTrue(targets(concrete).contains("src/main/resources/beans.xml"),
                "expected a concrete edit on the Spring bean XML: " + concrete);
        assertTrue(targets(concrete).contains("src/main/resources/wiring.properties"),
                "expected a concrete edit on the properties token: " + concrete);
        // The plugin's manual-review EXACT_CLASS_NAME marker is now redundant and must be suppressed.
        assertFalse(hasManualReview(result, FrameworkResourceEdit.Kind.EXACT_CLASS_NAME),
                "Spring manual-review marker must be dropped once a concrete edit proves the span: "
                        + result.resourceEdits());
        // The genuinely-ambiguous string bean name / component-scan case stays a review-required WARNING.
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("string bean name")),
                "string bean names must remain review-required warnings: " + result.warnings());
    }

    @Test
    void emitsConcreteMetadataMappingEditForJpaPersistenceXmlClassElement(@TempDir Path tmp) throws IOException {
        Map<String, String> sources = new TreeMap<>();
        jpaAnnotations(sources);
        sources.put("com/acme/OldEntity.java", ""
                + "package com.acme;\n"
                + "import jakarta.persistence.Entity;\n"
                + "import jakarta.persistence.Id;\n"
                + "@Entity\n"
                + "public class OldEntity {\n"
                + "    @Id\n"
                + "    public long id;\n"
                + "}\n");

        Map<String, String> resources = new TreeMap<>();
        resources.put("META-INF/persistence.xml", ""
                + "<persistence>\n"
                + "    <persistence-unit name=\"pu\">\n"
                + "        <class>com.acme.OldEntity</class>\n"
                + "    </persistence-unit>\n"
                + "</persistence>\n");

        JavaProjectModel model = model(tmp, sources, resources);
        FrameworkParticipationCoordinator.Result result = new FrameworkParticipationCoordinator()
                .participate(model, SymbolChange.renameType("com.acme.OldEntity", "com.acme.NewEntity"));

        List<FrameworkResourceEdit> concrete = concreteEdits(result);
        assertEquals(1, concrete.size(), "expected one concrete edit for the persistence.xml <class>: " + concrete);
        FrameworkResourceEdit edit = concrete.get(0);
        // An edit in a JPA persistence/ORM descriptor is classified METADATA_MAPPING (matching the JPA plugin's marker).
        assertEquals(FrameworkResourceEdit.Kind.METADATA_MAPPING, edit.kind(), edit.toString());
        assertNotNull(edit.textEdit(), "a concrete edit must carry a TextEdit: " + edit);
        assertEquals("com.acme.NewEntity", edit.textEdit().newText(), edit.toString());
        assertEquals("src/main/resources/META-INF/persistence.xml", edit.targetResource(), edit.toString());
        // The JPA plugin's manual-review METADATA_MAPPING marker is now redundant and must be suppressed.
        assertFalse(hasManualReview(result, FrameworkResourceEdit.Kind.METADATA_MAPPING),
                "JPA manual-review marker must be dropped once a concrete edit proves the <class>: "
                        + result.resourceEdits());
        // JPQL / @NamedQuery strings remain review-required warnings (never auto-rewritten).
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("JPQL")),
                "JPQL string queries must remain review-required warnings: " + result.warnings());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private static List<FrameworkResourceEdit> concreteEdits(FrameworkParticipationCoordinator.Result result) {
        List<FrameworkResourceEdit> concrete = new ArrayList<>();
        for (FrameworkResourceEdit edit : result.resourceEdits()) {
            if (!edit.manualReviewRequired()) {
                concrete.add(edit);
            }
        }
        return concrete;
    }

    private static List<String> targets(List<FrameworkResourceEdit> edits) {
        List<String> targets = new ArrayList<>();
        for (FrameworkResourceEdit edit : edits) {
            targets.add(edit.targetResource());
        }
        return targets;
    }

    private static boolean hasManualReview(FrameworkParticipationCoordinator.Result result,
            FrameworkResourceEdit.Kind kind) {
        return result.resourceEdits().stream()
                .anyMatch(edit -> edit.manualReviewRequired() && edit.kind() == kind);
    }

    private static void springStereotype(Map<String, String> sources) {
        sources.put("org/springframework/stereotype/Service.java", ""
                + "package org.springframework.stereotype;\n"
                + "import java.lang.annotation.*;\n"
                + "@Retention(RetentionPolicy.RUNTIME)\n"
                + "@Target(ElementType.TYPE)\n"
                + "public @interface Service {}\n");
    }

    private static void jpaAnnotations(Map<String, String> sources) {
        sources.put("jakarta/persistence/Entity.java", ""
                + "package jakarta.persistence;\n"
                + "import java.lang.annotation.*;\n"
                + "@Retention(RetentionPolicy.RUNTIME)\n"
                + "@Target(ElementType.TYPE)\n"
                + "public @interface Entity {}\n");
        sources.put("jakarta/persistence/Id.java", ""
                + "package jakarta.persistence;\n"
                + "import java.lang.annotation.*;\n"
                + "@Retention(RetentionPolicy.RUNTIME)\n"
                + "@Target({ElementType.FIELD, ElementType.METHOD})\n"
                + "public @interface Id {}\n");
    }

    // ── Fixture ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private static JavaProjectModel model(Path root, Map<String, String> javaSources, Map<String, String> resources)
            throws IOException {
        Path sourceRoot = root.resolve("src/main/java");
        Path resourceRoot = root.resolve("src/main/resources");
        List<Path> javaFiles = new ArrayList<>();
        Files.createDirectories(sourceRoot);
        for (Map.Entry<String, String> entry : javaSources.entrySet()) {
            Path javaFile = sourceRoot.resolve(entry.getKey());
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, entry.getValue(), StandardCharsets.UTF_8);
            javaFiles.add(javaFile);
        }
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            Path resourceFile = resourceRoot.resolve(entry.getKey());
            Files.createDirectories(resourceFile.getParent());
            Files.writeString(resourceFile, entry.getValue(), StandardCharsets.UTF_8);
        }

        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                List.copyOf(javaFiles),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "17",
                null,
                null,
                "UTF-8",
                false,
                "none",
                List.of(),
                false,
                List.of("-source", "17", "-target", "17", "-encoding", "UTF-8"),
                List.of(),
                List.of());
        return new JavaProjectModel(
                root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }
}
