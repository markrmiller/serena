package io.serena.javarefactor.session;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * G002: the full incremental-apply revision guard ({@link ProjectRevision#mismatchFull}) must refuse on build-file,
 * compiler-arg, classpath, source-root, and generated-source drift between session creation and incremental apply,
 * while tolerating benign file-inventory growth (which legitimately shifts the opaque model hash mid-session) and
 * exempting only the source files an earlier acknowledged partial apply already committed.
 */
final class ProjectRevisionFullGuardTest {

    @Test
    void identicalModelDoesNotMismatch() throws IOException {
        Path root = Files.createTempDirectory("serena-fullguard");
        JavaProjectModel model = model(root, List.of(root.resolve("src")), List.of(), List.of(), defaultOptions(),
                List.of(), List.of());
        ProjectRevision captured = ProjectRevision.capture(model, List.of());
        assertNull(captured.mismatchFull(ProjectRevision.capture(model, List.of()), Set.of()));
    }

    @Test
    void pomXmlContentChangeIsRefused() throws IOException {
        Path root = Files.createTempDirectory("serena-fullguard-pom");
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project><dependencies/></project>\n");
        JavaProjectModel model = model(root, List.of(root.resolve("src")), List.of(), List.of(), defaultOptions(),
                List.of(pom), List.of());
        ProjectRevision captured = ProjectRevision.capture(model, List.of());
        Files.writeString(pom, "<project><dependencies><dep>added</dep></dependencies></project>\n");
        String mismatch = captured.mismatchFull(ProjectRevision.capture(model, List.of()), Set.of());
        assertNotNull(mismatch, "a pom.xml content change must be refused");
        assertTrue(mismatch.contains("buildFilesDigest"), mismatch);
    }

    @Test
    void buildGradleContentChangeIsRefused() throws IOException {
        Path root = Files.createTempDirectory("serena-fullguard-gradle");
        Path gradle = root.resolve("build.gradle");
        Files.writeString(gradle, "dependencies {}\n");
        JavaProjectModel model = model(root, List.of(root.resolve("src")), List.of(), List.of(), defaultOptions(),
                List.of(gradle), List.of());
        ProjectRevision captured = ProjectRevision.capture(model, List.of());
        Files.writeString(gradle, "dependencies { implementation 'a:b:1' }\n");
        String mismatch = captured.mismatchFull(ProjectRevision.capture(model, List.of()), Set.of());
        assertNotNull(mismatch, "a build.gradle content change must be refused");
        assertTrue(mismatch.contains("buildFilesDigest"), mismatch);
    }

    @Test
    void compilerArgChangeIsRefused() throws IOException {
        Path root = Files.createTempDirectory("serena-fullguard-args");
        JavaProjectModel before = model(root, List.of(root.resolve("src")), List.of(), List.of(),
                List.of("-source", "17", "-encoding", "UTF-8"), List.of(), List.of());
        JavaProjectModel after = model(root, List.of(root.resolve("src")), List.of(), List.of(),
                List.of("-source", "17", "-encoding", "UTF-8", "--enable-preview"), List.of(), List.of());
        ProjectRevision captured = ProjectRevision.capture(before, List.of());
        String mismatch = captured.mismatchFull(ProjectRevision.capture(after, List.of()), Set.of());
        assertNotNull(mismatch, "a compiler-arg change must be refused");
        assertTrue(mismatch.contains("compilerOptions"), mismatch);
    }

    @Test
    void classpathChangeIsRefused() throws IOException {
        Path root = Files.createTempDirectory("serena-fullguard-cp");
        JavaProjectModel before = model(root, List.of(root.resolve("src")), List.of(root.resolve("lib/a.jar")),
                List.of(), defaultOptions(), List.of(), List.of());
        JavaProjectModel after = model(root, List.of(root.resolve("src")),
                List.of(root.resolve("lib/a.jar"), root.resolve("lib/b.jar")), List.of(), defaultOptions(),
                List.of(), List.of());
        ProjectRevision captured = ProjectRevision.capture(before, List.of());
        String mismatch = captured.mismatchFull(ProjectRevision.capture(after, List.of()), Set.of());
        assertNotNull(mismatch, "a classpath change must be refused");
        assertTrue(mismatch.contains("classpath"), mismatch);
    }

    @Test
    void sourceRootChangeIsRefused() throws IOException {
        Path root = Files.createTempDirectory("serena-fullguard-roots");
        JavaProjectModel before = model(root, List.of(root.resolve("src/main/java")), List.of(), List.of(),
                defaultOptions(), List.of(), List.of());
        JavaProjectModel after = model(root,
                List.of(root.resolve("src/main/java"), root.resolve("src/gen/java")), List.of(), List.of(),
                defaultOptions(), List.of(), List.of());
        ProjectRevision captured = ProjectRevision.capture(before, List.of());
        String mismatch = captured.mismatchFull(ProjectRevision.capture(after, List.of()), Set.of());
        assertNotNull(mismatch, "a source-root change must be refused");
        assertTrue(mismatch.contains("sourceRoots"), mismatch);
    }

    @Test
    void generatedSourceContentChangeIsRefused() throws IOException {
        Path root = Files.createTempDirectory("serena-fullguard-gen");
        Path genRoot = root.resolve("build/generated/sources/annotationProcessor/java/main");
        Files.createDirectories(genRoot.resolve("demo"));
        Path genFile = genRoot.resolve("demo/Gen.java");
        Files.writeString(genFile, "package demo;\npublic final class Gen { int v() { return 1; } }\n");
        JavaProjectModel model = model(root, List.of(root.resolve("src")), List.of(), List.of(genRoot),
                defaultOptions(), List.of(), List.of());
        ProjectRevision captured = ProjectRevision.capture(model, List.of());
        Files.writeString(genFile, "package demo;\npublic final class Gen { int v() { return 2; } }\n");
        String mismatch = captured.mismatchFull(ProjectRevision.capture(model, List.of()), Set.of());
        assertNotNull(mismatch, "a generated-source content change must be refused");
        assertTrue(mismatch.contains("generatedSourcesDigest"), mismatch);
    }

    @Test
    void benignFileInventoryGrowthIsTolerated() throws IOException {
        // An earlier acknowledged partial apply that creates a file shifts the opaque model hash (toJson folds in the
        // file inventory). mismatchFull must NOT trip on that drift while every config input is unchanged — otherwise
        // legitimate multi-step incremental applies would be impossible.
        Path root = Files.createTempDirectory("serena-fullguard-inv");
        JavaProjectModel before = model(root, List.of(root.resolve("src")), List.of(), List.of(), defaultOptions(),
                List.of(), List.of(root.resolve("src/demo/A.java")));
        JavaProjectModel after = model(root, List.of(root.resolve("src")), List.of(), List.of(), defaultOptions(),
                List.of(), List.of(root.resolve("src/demo/A.java"), root.resolve("src/demo/B.java")));
        ProjectRevision captured = ProjectRevision.capture(before, List.of());
        ProjectRevision grown = ProjectRevision.capture(after, List.of());
        // The full token (including the model hash) DOES differ — proving the inventory really changed...
        assertNotNull(captured.mismatch(grown), "model-hash inventory drift is expected here");
        // ...but the inventory-independent guard tolerates it.
        assertNull(captured.mismatchFull(grown, Set.of()),
                "benign file-inventory growth must not trip the full revision guard");
    }

    @Test
    void onlyExemptedSourcePathsBypassSourceHashCheck() throws IOException {
        Path root = Files.createTempDirectory("serena-fullguard-exempt");
        Files.createDirectories(root.resolve("src"));
        Path touched = root.resolve("src/Touched.java");
        Files.writeString(touched, "class Touched {}\n");
        JavaProjectModel model = model(root, List.of(root.resolve("src")), List.of(), List.of(), defaultOptions(),
                List.of(), List.of());
        ProjectRevision captured = ProjectRevision.capture(model, List.of("src/Touched.java"));
        Files.writeString(touched, "class Touched { int x; }\n");
        ProjectRevision after = ProjectRevision.capture(model, List.of("src/Touched.java"));
        // Not exempt -> the source-hash change is refused.
        String mismatch = captured.mismatchFull(after, Set.of());
        assertNotNull(mismatch, "an unexempted source change must be refused");
        assertTrue(mismatch.contains("src/Touched.java"), mismatch);
        // Exempt (an earlier acknowledged partial apply committed it) -> bypassed.
        assertNull(captured.mismatchFull(after, Set.of("src/Touched.java")),
                "an explicitly exempted committed path must bypass the source-hash check");
    }

    @Test
    void modelFieldOutsideTheLegacyAllowlistIsRefused() throws IOException {
        // G003: discoveryKind is part of the project model identity (JavaProjectModel#toJson) but was NOT one of the
        // hand-curated invalidationInputs, so it previously drifted through the incremental guard unchecked. The
        // mechanically derived model digest now refuses it, proving the silent-escape gap is closed.
        Path root = Files.createTempDirectory("serena-fullguard-derived");
        JavaProjectModel before = modelWithKind(root, "maven");
        JavaProjectModel after = modelWithKind(root, "gradle");
        ProjectRevision captured = ProjectRevision.capture(before, List.of());
        String mismatch = captured.mismatchFull(ProjectRevision.capture(after, List.of()), Set.of());
        assertNotNull(mismatch, "a model field outside the legacy allowlist must still be refused");
        assertTrue(mismatch.contains("derivedModelDigest"), mismatch);
    }

    private static JavaProjectModel modelWithKind(Path root, String discoveryKind) {
        SourceSet sourceSet = new SourceSet(
                "main", List.of(root.resolve("src")), List.of(), List.of(), List.of(), List.of(), List.of(),
                "17", null, null, "UTF-8", false, "none", List.of(), false, defaultOptions(), List.of(), List.of());
        return new JavaProjectModel(
                root, discoveryKind, List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }

    private static List<String> defaultOptions() {
        return List.of("-source", "17", "-target", "17", "-encoding", "UTF-8");
    }

    private static JavaProjectModel model(
            Path root, List<Path> sourceRoots, List<Path> classpath, List<Path> generatedRoots,
            List<String> javacOptions, List<Path> invalidationFiles, List<Path> javaFiles) {
        SourceSet sourceSet = new SourceSet(
                "main", sourceRoots, javaFiles, List.of(), classpath, List.of(), generatedRoots,
                "17", null, null, "UTF-8", false, "none", List.of(), false, javacOptions, List.of(), List.of());
        return new JavaProjectModel(
                root, "test", List.of(sourceSet), List.of(), List.of(), invalidationFiles, false, false, List.of());
    }
}
