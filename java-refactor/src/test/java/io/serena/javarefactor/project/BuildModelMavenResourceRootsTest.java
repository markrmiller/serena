package io.serena.javarefactor.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B11 model-first resource-root discovery, Maven side. The build model must record each module's CONFIGURED resource
 * directories from the effective POM's {@code <build><resources>} / {@code <build><testResources>} so a NON-conventional
 * resource directory (e.g. {@code <resource><directory>config</directory>}, which is neither named {@code resources} nor a
 * sibling of a {@code java} root) flows into {@link BuildModel.ModelSourceSet#resourceDirs()}, on into
 * {@link SourceSet#resourceRoots()}, and is therefore discovered by {@link ResourceRootModel}.
 *
 * <p>The tests drive the package-private {@link BuildModelExtractor#parseMavenModel} directly with a crafted effective
 * POM — the same shape {@code help:effective-pom} produces — exactly as {@link BuildModelClasspathCompletenessTest} does.
 * No live Maven invocation is required (and no live Gradle/Maven extraction harness exists in this suite), so this is a
 * UNIT test of the parse + mapping path; see the FINAL REPORT for the live-vs-unit rationale.</p>
 */
class BuildModelMavenResourceRootsTest {

    @Test
    void nonConventionalResourceDirectoryFlowsIntoModelSourceSet(@TempDir Path tmp) throws IOException {
        Path projectRoot = tmp.resolve("proj");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        // Declares a NON-conventional main resource dir ("config") and a non-conventional test resource dir ("tres").
        Path effectivePom = writeEffectivePom(tmp, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build>
                    <resources>
                      <resource>
                        <directory>config</directory>
                      </resource>
                    </resources>
                    <testResources>
                      <testResource>
                        <directory>tres</directory>
                      </testResource>
                    </testResources>
                  </build>
                </project>
                """);

        BuildModel model = BuildModelExtractor.parseMavenModel(projectRoot, effectivePom, classpathDir(),
                Set.of());

        BuildModel.ModelSourceSet main = sourceSet(model, "main");
        String expectedMain = projectRoot.resolve("config").normalize().toString();
        assertTrue(main.resourceDirs().contains(expectedMain),
                "main.resourceDirs() must carry the non-conventional <resource><directory>config</directory>: "
                        + main.resourceDirs());
        // Strictly model-declared: the conventional src/main/resources must NOT be added when the POM declares resources.
        assertFalse(main.resourceDirs().contains(projectRoot.resolve("src/main/resources").normalize().toString()),
                "When the POM declares resources, the conventional src/main/resources must not be injected");
    }

    @Test
    void conventionalDefaultRecordedWhenPomDeclaresNoResources(@TempDir Path tmp) throws IOException {
        Path projectRoot = tmp.resolve("proj");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        // No <build><resources>: Maven's super-POM default (src/main/resources) is the model-declared resource root, so
        // the model is still authoritative and ResourceRootModel never needs the filename convention for Maven.
        Path effectivePom = writeEffectivePom(tmp, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                </project>
                """);

        BuildModel model = BuildModelExtractor.parseMavenModel(projectRoot, effectivePom, classpathDir(),
                Set.of());

        BuildModel.ModelSourceSet main = sourceSet(model, "main");
        assertEquals(List.of(projectRoot.resolve("src/main/resources").normalize().toString()), main.resourceDirs(),
                "With no declared resources the model must default to Maven's conventional src/main/resources");
    }

    @Test
    void nonConventionalResourceDirReachesSourceSetResourceRoots(@TempDir Path tmp) throws IOException {
        // End-to-end through the real discoverer: the non-conventional Maven resource dir must surface on the assembled
        // SourceSet.resourceRoots() (and thus be discoverable by ResourceRootModel).
        Path projectRoot = tmp.resolve("proj");
        Path nonConventional = projectRoot.resolve("config");
        Files.createDirectories(nonConventional);
        Path effectivePom = writeEffectivePom(tmp, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build>
                    <resources>
                      <resource>
                        <directory>config</directory>
                      </resource>
                    </resources>
                  </build>
                </project>
                """);
        // Stage a Java file under the conventional main source root before extraction: parseMavenModel only records a
        // source root that exists on disk, so the main source set (carrying the resource roots) is recorded here, and
        // the discoverer then retains it because it has Java files.
        Path javaFile = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, "package demo; public class App {}\n");
        BuildModel model = BuildModelExtractor.parseMavenModel(projectRoot, effectivePom, classpathDir(), Set.of());

        // Run the real discoverer with stubbed extraction.
        ProjectModelDiscoverer discoverer = new ProjectModelDiscoverer(
                (buildKind, root, config) -> BuildModelExtractor.Result.ok(model));
        JavaProjectModel projectModel = discoverer.buildUnvalidatedModel(projectRoot, "{\"buildToolMode\":\"maven\"}");

        SourceSet main = projectModel.sourceSets().stream()
                .filter(s -> s.name().equals("main"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no main source set in " + projectModel.sourceSets()));
        Path expected = nonConventional.toAbsolutePath().normalize();
        assertTrue(main.resourceRoots().contains(expected),
                "The non-conventional Maven resource dir must reach SourceSet.resourceRoots(): " + main.resourceRoots());
        assertTrue(ResourceRootModel.resourceRoots(projectModel).contains(expected),
                "ResourceRootModel must discover the model-carried non-conventional resource dir");
    }

    private static BuildModel.ModelSourceSet sourceSet(BuildModel model, String name) {
        return model.modules().stream()
                .flatMap(module -> module.sourceSets().stream())
                .filter(sourceSet -> sourceSet.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " source set in " + model.modules()));
    }

    private static Path classpathDir() throws IOException {
        return Files.createTempDirectory("serena-maven-res-test");
    }

    private static Path writeEffectivePom(Path tmp, String xml) throws IOException {
        Path pom = Files.createTempFile(tmp, "effective-pom", ".xml");
        Files.writeString(pom, xml);
        return pom;
    }
}
