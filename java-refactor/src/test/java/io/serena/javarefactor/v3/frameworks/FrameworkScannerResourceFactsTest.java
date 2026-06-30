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

import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrameworkScannerResourceFactsTest {

    @Test
    void detectAndFindReferencesIncludeSpringAndJpaResourceFacts(@TempDir Path root) throws IOException {
        JavaProjectModel model = model(root,
                Map.of("com/acme/App.java", "package com.acme; public class App {}"),
                Map.of(
                        "beans.xml", "<beans><bean id=\"svc\" class=\"com.acme.XmlOnlyService\"/></beans>",
                        "META-INF/persistence.xml", "<persistence><persistence-unit name=\"pu\"><class>com.acme.XmlEntity</class></persistence-unit></persistence>",
                        "queries.xml", "<named-query>select e from com.acme.XmlEntity e</named-query>"));

        FrameworkScanner scanner = new FrameworkScanner(root, model);

        String detected = scanner.detect(Map.of());
        assertTrue(detected.contains("\"framework\":\"spring\""), detected);
        assertTrue(detected.contains("\"framework\":\"jpa\""), detected);
        assertTrue(detected.contains("SPRING_XML_BEAN_CLASS"), detected);
        assertTrue(detected.contains("JPA_XML_CLASS"), detected);
        assertTrue(detected.contains("\"resourceEvidence\""), detected);

        String refs = scanner.findReferences(Map.of("target", "com.acme.XmlEntity"));
        assertTrue(refs.contains("JPA_XML_CLASS"), refs);
        assertTrue(refs.contains("JPQL_STRING_CANDIDATE"), refs);
        assertTrue(refs.contains("\"target\":\"com.acme.XmlEntity\""), refs);
    }

    @Test
    void fieldLevelParticipationWarnsForJpaAccessAndJacksonWireSemantics(@TempDir Path root) throws IOException {
        JavaProjectModel model = model(root,
                Map.of(
                        "jakarta/persistence/Entity.java", "package jakarta.persistence; public @interface Entity {}",
                        "jakarta/persistence/Id.java", "package jakarta.persistence; public @interface Id {}",
                        "com/fasterxml/jackson/annotation/JsonProperty.java", "package com.fasterxml.jackson.annotation; public @interface JsonProperty { String value() default \"\"; }",
                        "com/acme/Customer.java", "package com.acme; import jakarta.persistence.*; import com.fasterxml.jackson.annotation.JsonProperty; @Entity public class Customer { @Id @JsonProperty(\"customer_name\") public String name; }"),
                Map.of());

        FrameworkParticipationCoordinator.Result result = new FrameworkParticipationCoordinator()
                .participate(model, SymbolChange.encapsulateField("com.acme.Customer", "name"));

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("JPA access strategy risk")),
                result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Jackson") && w.contains("serialized property")),
                result.warnings().toString());
    }

    private static JavaProjectModel model(Path root, Map<String, String> javaSources, Map<String, String> resources)
            throws IOException {
        Path sourceRoot = root.resolve("src/main/java");
        Path resourceRoot = root.resolve("src/main/resources");
        List<Path> javaFiles = new ArrayList<>();
        Files.createDirectories(sourceRoot);
        Files.createDirectories(resourceRoot);
        for (Map.Entry<String, String> entry : javaSources.entrySet()) {
            Path file = sourceRoot.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            javaFiles.add(file);
        }
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            Path file = resourceRoot.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                List.copyOf(javaFiles),
                List.of(),
                List.of(),
                List.of(resourceRoot),
                List.of(),
                "17",
                "17",
                "17",
                "UTF-8",
                false,
                "",
                List.of(),
                false,
                List.of("-source", "17", "-target", "17", "-encoding", "UTF-8"),
                List.of(),
                List.of());
        return new JavaProjectModel(root, "plain", List.of(sourceSet), List.of(), List.of(), List.of(),
                false, false, List.of());
    }
}
