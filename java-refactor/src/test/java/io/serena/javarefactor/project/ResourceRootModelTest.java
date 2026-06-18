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
 * B11 model-first resource-root discovery. {@link ResourceRootModel} must prefer the build model's authoritative
 * {@link SourceSet#resourceRoots()} — so a NON-conventional resource directory (one not named {@code resources} and not
 * a sibling of a {@code java} root) is discovered — and fall back to the filename convention ONLY when the model
 * declared no resource roots at all.
 */
class ResourceRootModelTest {

    @Test
    void nonConventionalModelResourceRootIsDiscoveredModelFirst(@TempDir Path root) throws IOException {
        // A resource directory the model declares explicitly but that the filename convention would NEVER find:
        // it is named "config" (not "resources") and is not a sibling of the java source root.
        Path javaRoot = root.resolve("src/main/java");
        Path nonConventional = root.resolve("config");
        Files.createDirectories(javaRoot);
        Files.createDirectories(nonConventional);

        SourceSet sourceSet = sourceSetWithResourceRoots(javaRoot, List.of(nonConventional));
        JavaProjectModel model = modelOf(root, sourceSet);

        Set<Path> roots = ResourceRootModel.resourceRoots(model);
        assertTrue(roots.contains(nonConventional.toAbsolutePath().normalize()),
                "Model-declared non-conventional resource root must be discovered model-first: " + roots);
        // The model path is authoritative; with a model root present the resolver does not also pull in convention dirs.
        assertEquals(Set.of(nonConventional.toAbsolutePath().normalize()), roots);
    }

    @Test
    void onlyExistingModelResourceRootsAreReturned(@TempDir Path root) throws IOException {
        Path javaRoot = root.resolve("src/main/java");
        Path existing = root.resolve("res-existing");
        Path missing = root.resolve("res-missing"); // declared by the model but never created on disk
        Files.createDirectories(javaRoot);
        Files.createDirectories(existing);

        SourceSet sourceSet = sourceSetWithResourceRoots(javaRoot, List.of(existing, missing));
        JavaProjectModel model = modelOf(root, sourceSet);

        Set<Path> roots = ResourceRootModel.resourceRoots(model);
        assertTrue(roots.contains(existing.toAbsolutePath().normalize()));
        assertFalse(roots.contains(missing.toAbsolutePath().normalize()),
                "A model resource root that does not exist on disk must not be returned");
    }

    @Test
    void conventionUsedOnlyWhenModelDeclaresNoResourceRoots(@TempDir Path root) throws IOException {
        // Conventional layout: java root with a sibling "resources" directory, and the model carries NO resourceRoots()
        // (as a plain/conventional source set would). The resolver must fall back to the convention and find it.
        Path javaRoot = root.resolve("src/main/java");
        Path conventionalResources = root.resolve("src/main/resources");
        Files.createDirectories(javaRoot);
        Files.createDirectories(conventionalResources);

        SourceSet sourceSet = sourceSetWithResourceRoots(javaRoot, List.of()); // model declares no resource roots
        JavaProjectModel model = modelOf(root, sourceSet);

        Set<Path> roots = ResourceRootModel.resourceRoots(model);
        assertEquals(Set.of(conventionalResources.toAbsolutePath().normalize()), roots,
                "With no model resource roots the convention fallback must discover the resources sibling: " + roots);
    }

    @Test
    void modelResourceRootSuppressesConventionEvenWhenBothPresent(@TempDir Path root) throws IOException {
        // Both a conventional sibling "resources" dir AND a model-declared non-conventional "config" dir exist. Because
        // the model declares a resource root, the convention is NOT consulted: discovery is strictly model-first.
        Path javaRoot = root.resolve("src/main/java");
        Path conventionalResources = root.resolve("src/main/resources");
        Path nonConventional = root.resolve("config");
        Files.createDirectories(javaRoot);
        Files.createDirectories(conventionalResources);
        Files.createDirectories(nonConventional);

        SourceSet sourceSet = sourceSetWithResourceRoots(javaRoot, List.of(nonConventional));
        JavaProjectModel model = modelOf(root, sourceSet);

        Set<Path> roots = ResourceRootModel.resourceRoots(model);
        assertEquals(Set.of(nonConventional.toAbsolutePath().normalize()), roots,
                "When the model declares resource roots, the filename convention must not contribute: " + roots);
    }

    private static SourceSet sourceSetWithResourceRoots(Path javaRoot, List<Path> resourceRoots) {
        // Full (B11) constructor so resourceRoots() is populated; everything else is a minimal valid source set.
        return new SourceSet(
                "main",
                List.of(javaRoot),
                List.of(),
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
                List.of(),
                true,
                resourceRoots);
    }

    private static JavaProjectModel modelOf(Path root, SourceSet sourceSet) {
        return new JavaProjectModel(root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }
}
