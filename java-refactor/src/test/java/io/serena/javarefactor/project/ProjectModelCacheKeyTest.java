package io.serena.javarefactor.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reviewer blocker #1: {@link ProjectModelCache#keyFor} must fold the module path, annotation-processor path and
 * compiler output directories into the cache key, so a change to a module-path jar, a processor jar, or any file under
 * an output directory invalidates the cached validated model. These tests stamp the same model against successive disk
 * states and assert the key changes on every relevant mutation and is stable when nothing changes.
 */
class ProjectModelCacheKeyTest {

    /** Builds a single-source-set model whose module/processor/output paths point at the given entries. */
    private static JavaProjectModel modelWith(Path projectRoot, List<Path> modulePath,
                                              List<Path> annotationProcessorPath, List<Path> outputDirs) {
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(),            // sourceRoots
                List.of(),            // javaFiles
                outputDirs,
                List.of(),            // classpath
                modulePath,
                List.of(),            // generatedRoots
                "",                   // releaseVersion
                "",                   // sourceVersion
                "",                   // targetVersion
                "UTF-8",              // encoding
                false,                // modular
                "full",               // annotationProcessing
                annotationProcessorPath,
                false,                // allowIncompleteAnalysis
                List.of(),            // javacOptions
                List.of(),            // invalidationFiles
                List.of()             // dependsOn
        );
        return new JavaProjectModel(
                projectRoot,
                "test",
                List.of(sourceSet),
                List.of(),            // errors
                List.of(),            // warnings
                List.of(),            // invalidationFiles
                false,                // allowIncompleteAnalysis
                false,                // conventionalFallbackUsed
                List.of()             // compilerDiagnostics
        );
    }

    private static void touch(Path file, long lastModifiedMillis) throws IOException {
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(lastModifiedMillis));
    }

    @Test
    void modulePathArtifactChangeChangesKey(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("lib.jar");
        Files.write(jar, new byte[]{1, 2, 3});
        touch(jar, 1_000L);
        JavaProjectModel model = modelWith(tmp, List.of(jar), List.of(), List.of());

        String before = ProjectModelCache.keyFor(model, "cfg");
        // Change the artifact's contents so mtime+size differ.
        Files.write(jar, new byte[]{1, 2, 3, 4, 5});
        touch(jar, 2_000L);
        String after = ProjectModelCache.keyFor(model, "cfg");

        assertNotEquals(before, after, "A changed module-path artifact must invalidate the key");
    }

    @Test
    void annotationProcessorPathArtifactChangeChangesKey(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("processor.jar");
        Files.write(jar, new byte[]{9});
        touch(jar, 1_000L);
        JavaProjectModel model = modelWith(tmp, List.of(), List.of(jar), List.of());

        String before = ProjectModelCache.keyFor(model, "cfg");
        Files.write(jar, new byte[]{9, 9, 9});
        touch(jar, 2_000L);
        String after = ProjectModelCache.keyFor(model, "cfg");

        assertNotEquals(before, after, "A changed annotation-processor-path artifact must invalidate the key");
    }

    @Test
    void addingFileUnderOutputDirChangesKey(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("classes");
        Files.createDirectories(out);
        Files.write(out.resolve("A.class"), new byte[]{1});
        JavaProjectModel model = modelWith(tmp, List.of(), List.of(), List.of(out));

        String before = ProjectModelCache.keyFor(model, "cfg");
        Files.write(out.resolve("B.class"), new byte[]{2});
        String after = ProjectModelCache.keyFor(model, "cfg");

        assertNotEquals(before, after, "Adding a file under an output dir must invalidate the key (recursive fingerprint)");
    }

    @Test
    void modifyingFileUnderOutputDirChangesKey(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("classes");
        Files.createDirectories(out);
        Path cls = out.resolve("A.class");
        Files.write(cls, new byte[]{1});
        touch(cls, 1_000L);
        JavaProjectModel model = modelWith(tmp, List.of(), List.of(), List.of(out));

        String before = ProjectModelCache.keyFor(model, "cfg");
        Files.write(cls, new byte[]{1, 2, 3});
        touch(cls, 2_000L);
        String after = ProjectModelCache.keyFor(model, "cfg");

        assertNotEquals(before, after, "Modifying a file under an output dir must invalidate the key");
    }

    @Test
    void removingFileUnderOutputDirChangesKey(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("classes");
        Files.createDirectories(out);
        Files.write(out.resolve("A.class"), new byte[]{1});
        Files.write(out.resolve("B.class"), new byte[]{2});
        JavaProjectModel model = modelWith(tmp, List.of(), List.of(), List.of(out));

        String before = ProjectModelCache.keyFor(model, "cfg");
        Files.delete(out.resolve("B.class"));
        String after = ProjectModelCache.keyFor(model, "cfg");

        assertNotEquals(before, after, "Removing a file under an output dir must invalidate the key");
    }

    @Test
    void directoryEntryOnModulePathIsRecursivelyFingerprinted(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("explodedModule");
        Files.createDirectories(dir);
        Files.write(dir.resolve("module-info.class"), new byte[]{1});
        JavaProjectModel model = modelWith(tmp, List.of(dir), List.of(), List.of());

        String before = ProjectModelCache.keyFor(model, "cfg");
        Files.write(dir.resolve("C.class"), new byte[]{7});
        String after = ProjectModelCache.keyFor(model, "cfg");

        assertNotEquals(before, after, "A directory module-path entry must be recursively fingerprinted");
    }

    /**
     * G003: the classpath-unproven signal is derived from the source sets, so the validation transforms that re-wrap a
     * model ({@code withCompilerDiagnostics} / {@code withValidatedDiagnostics} — the path a cache rehydrate takes) must
     * preserve it. A model whose source set is unproven stays unproven through both transforms.
     */
    @Test
    void classpathUnprovenSurvivesValidationTransforms(@TempDir Path tmp) {
        SourceSet unproven = new SourceSet(
                "main", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "17", null, null, "UTF-8", false, "none", List.of(), false, List.of(), List.of(), List.of(),
                false /* classpathProven -> UNPROVEN */);
        JavaProjectModel model = new JavaProjectModel(
                tmp, "maven", List.of(unproven), List.of(), List.of(), List.of(), false, false, List.of());
        assertTrue(model.classpathUnproven(), "precondition: model is unproven");

        JavaProjectModel withDiagnostics = model.withCompilerDiagnostics(List.of("X.java:1: error: cannot find symbol"));
        assertTrue(withDiagnostics.classpathUnproven(), "withCompilerDiagnostics must preserve classpathUnproven");

        JavaProjectModel rehydrated = model.withValidatedDiagnostics(List.of(), List.of("warn"), List.of());
        assertTrue(rehydrated.classpathUnproven(), "withValidatedDiagnostics (cache rehydrate) must preserve classpathUnproven");

        SourceSet proven = new SourceSet(
                "main", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "17", null, null, "UTF-8", false, "none", List.of(), false, List.of(), List.of(), List.of());
        JavaProjectModel provenModel = new JavaProjectModel(
                tmp, "maven", List.of(proven), List.of(), List.of(), List.of(), false, false, List.of());
        assertFalse(provenModel.classpathUnproven(), "the backward-compatible source-set constructor defaults to proven");
    }

    @Test
    void unchangedDiskYieldsIdenticalKey(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("lib.jar");
        Files.write(jar, new byte[]{1, 2, 3});
        touch(jar, 1_000L);
        Path out = tmp.resolve("classes");
        Files.createDirectories(out);
        Path cls = out.resolve("A.class");
        Files.write(cls, new byte[]{1});
        touch(cls, 1_000L);
        JavaProjectModel model = modelWith(tmp, List.of(jar), List.of(), List.of(out));

        String first = ProjectModelCache.keyFor(model, "cfg");
        String second = ProjectModelCache.keyFor(model, "cfg");

        assertEquals(first, second, "Unchanged disk state must yield an identical key (determinism)");
    }
}
