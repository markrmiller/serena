package io.serena.javarefactor.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G003 build-model completeness: a Maven module whose external dependency could not be resolved by
 * {@code dependency:build-classpath} (the scope is reported unresolved and the per-module classpath file is missing)
 * must yield a source set marked classpath-UNPROVEN, and the assembled {@link JavaProjectModel} must report
 * {@link JavaProjectModel#classpathUnproven()}. A module that declares only reactor-sibling or {@code system}-scoped
 * dependencies stays proven even when the scope failed, because those need no repository resolution.
 *
 * <p>The tests drive the package-private {@link BuildModelExtractor#parseMavenModel} directly with a crafted
 * effective-POM and an out-of-tree classpath directory (with the classpath file deliberately absent), exactly the
 * inputs the live Maven path produces when an external dependency cannot be resolved offline.</p>
 */
class BuildModelClasspathCompletenessTest {

    @Test
    void unresolvedExternalDependencyMarksSourceSetUnproven(@TempDir Path tmp) throws IOException {
        Path projectRoot = tmp.resolve("proj");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        // Effective POM for a single module that declares an EXTERNAL (non-reactor) compile dependency.
        Path effectivePom = writeEffectivePom(tmp, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>33.0.0-jre</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        // Out-of-tree classpath dir with NO per-module classpath file: build-classpath did not write one for the module
        // (the external dependency could not be resolved), so the compile scope is reported unresolved.
        Path classpathDir = Files.createTempDirectory("serena-maven-cp-test");

        BuildModel model = BuildModelExtractor.parseMavenModel(projectRoot, effectivePom, classpathDir,
                Set.of("compile", "test"));

        BuildModel.ModelSourceSet main = mainSourceSet(model);
        assertFalse(main.classpathProven(), "A module with an unresolved external dependency must be classpath-unproven");

        JavaProjectModel projectModel = modelFrom(projectRoot, model);
        assertTrue(projectModel.classpathUnproven(), "classpathUnproven() must be true when any source set is unproven");
        assertEquals(List.of("main"), projectModel.unprovenClasspathSourceSets());
    }

    @Test
    void resolvedScopeKeepsSourceSetProven(@TempDir Path tmp) throws IOException {
        Path projectRoot = tmp.resolve("proj");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Path effectivePom = writeEffectivePom(tmp, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>33.0.0-jre</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path classpathDir = Files.createTempDirectory("serena-maven-cp-test");

        // No scope reported unresolved: every scope's build-classpath succeeded, so the classpath is proven even though
        // we did not stage a classpath file (an empty resolved classpath is still a proven one).
        BuildModel model = BuildModelExtractor.parseMavenModel(projectRoot, effectivePom, classpathDir, Set.of());

        BuildModel.ModelSourceSet main = mainSourceSet(model);
        assertTrue(main.classpathProven(), "A module is proven when no scope failed to resolve");
        assertFalse(modelFrom(projectRoot, model).classpathUnproven());
    }

    @Test
    void moduleWithoutExternalDependenciesStaysProvenDespiteFailure(@TempDir Path tmp) throws IOException {
        Path projectRoot = tmp.resolve("proj");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        // The module's ONLY dependency is a system-scoped one (its own absolute systemPath, no repository resolution),
        // so a build-classpath failure does not endanger it; the module stays proven.
        Path effectivePom = writeEffectivePom(tmp, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.sun</groupId>
                      <artifactId>tools</artifactId>
                      <version>1.8</version>
                      <scope>system</scope>
                      <systemPath>/opt/jdk/lib/tools.jar</systemPath>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path classpathDir = Files.createTempDirectory("serena-maven-cp-test");

        BuildModel model = BuildModelExtractor.parseMavenModel(projectRoot, effectivePom, classpathDir,
                Set.of("compile", "test"));

        BuildModel.ModelSourceSet main = mainSourceSet(model);
        assertTrue(main.classpathProven(),
                "A module whose only dependency is system-scoped stays proven even when build-classpath failed");
        assertFalse(modelFrom(projectRoot, model).classpathUnproven());
    }

    private static BuildModel.ModelSourceSet mainSourceSet(BuildModel model) {
        return model.modules().stream()
                .flatMap(module -> module.sourceSets().stream())
                .filter(sourceSet -> sourceSet.name().equals("main"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no main source set in " + model.modules()));
    }

    /** Maps a build model to a JavaProjectModel via the real discoverer (stubbed extraction), so classpathProven flows through. */
    private static JavaProjectModel modelFrom(Path projectRoot, BuildModel model) throws IOException {
        // Ensure at least one Java file exists so the source set is retained.
        Path javaFile = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, "package demo; public class App {}\n");
        ProjectModelDiscoverer discoverer = new ProjectModelDiscoverer(
                (buildKind, root, config) -> BuildModelExtractor.Result.ok(rebaseSrcDirs(projectRoot, model)));
        return discoverer.buildUnvalidatedModel(projectRoot, "{\"buildToolMode\":\"maven\"}");
    }

    /** Points the model's source dirs at the real on-disk source root so the discoverer finds the staged Java file. */
    private static BuildModel rebaseSrcDirs(Path projectRoot, BuildModel model) {
        List<BuildModel.Module> rebased = model.modules().stream()
                .map(module -> new BuildModel.Module(module.project(), module.sourceSets().stream()
                        .map(sourceSet -> new BuildModel.ModelSourceSet(
                                sourceSet.name(),
                                List.of(projectRoot.resolve("src/main/java").toString()),
                                sourceSet.generatedRoots(),
                                sourceSet.outputDirs(),
                                sourceSet.classpath(),
                                sourceSet.modulePath(),
                                sourceSet.annotationProcessorPath(),
                                sourceSet.release(),
                                sourceSet.source(),
                                sourceSet.target(),
                                sourceSet.encoding(),
                                sourceSet.dependsOnProjects(),
                                sourceSet.compilerArgs(),
                                sourceSet.classpathProven()))
                        .toList()))
                .toList();
        return new BuildModel(rebased);
    }

    private static Path writeEffectivePom(Path tmp, String xml) throws IOException {
        Path pom = Files.createTempFile(tmp, "effective-pom", ".xml");
        Files.writeString(pom, xml);
        return pom;
    }
}
