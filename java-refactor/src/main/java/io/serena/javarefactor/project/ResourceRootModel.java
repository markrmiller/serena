package io.serena.javarefactor.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single, authoritative resolver of a project's resource roots (blocker B11). Every resource-aware consumer
 * (package rename/move rewriting, the transformation graph's resource view, and the impact analyzer) MUST discover
 * resource roots through this resolver so they all agree and so the discovery is MODEL-FIRST rather than convention-only.
 *
 * <p><b>The model now carries resource roots.</b> The build-model extraction records each source set's CONFIGURED
 * resource directories directly from the build tool: Gradle {@code sourceSets.*.resources.srcDirs} (emitted by the
 * bundled init script as the {@code resourceDirs} JSON field) and Maven {@code <build><resources>} /
 * {@code <build><testResources>} {@code <directory>} entries. {@link ProjectModelDiscoverer} threads those into
 * {@link SourceSet#resourceRoots()}. {@link #modelDerivedResourceRoots(JavaProjectModel)} therefore prefers that
 * authoritative field for every source set, which is what lets a NON-conventional resource directory (e.g. a Maven
 * {@code <resource><directory>config</directory>} or a Gradle {@code resources.srcDir('res')}) be discovered even though
 * it is not named {@code resources} and is not a sibling of a {@code java} root.</p>
 *
 * <p>The filename convention (a source root that IS a {@code resources} directory, or the {@code resources} sibling of a
 * {@code java} source root) is demoted to a GUARDED FALLBACK: it is consulted only when the model declared no resource
 * roots at all (e.g. a conventional/explicit/plain source set produced without build-model extraction). Either path
 * returns normalized, EXISTING directories, de-duplicated, in a stable order.</p>
 */
public final class ResourceRootModel {

    private ResourceRootModel() {
    }

    /**
     * The project's resource roots, MODEL-FIRST: the authoritative {@link SourceSet#resourceRoots()} declared by the
     * build model when those yield any existing resource directory, else the filename convention as a guarded fallback.
     * Returns normalized, existing directories in a stable, de-duplicated order.
     */
    public static Set<Path> resourceRoots(JavaProjectModel model) {
        Set<Path> modelRoots = modelDerivedResourceRoots(model);
        if (!modelRoots.isEmpty()) {
            return modelRoots;
        }
        return conventionResourceRoots(model);
    }

    /**
     * Resource roots taken MODEL-FIRST from the authoritative {@link SourceSet#resourceRoots()} the build model recorded
     * (Gradle {@code resources.srcDirs}; Maven {@code <build><resources>}/{@code <testResources>}). Only when the model
     * declared NO resource roots for ANY source set does this fall back to the filename convention (a configured source
     * root named {@code resources}, or the {@code resources} sibling of a {@code java} source root). Returns normalized,
     * existing directories, de-duplicated, in a stable order.
     */
    public static Set<Path> modelDerivedResourceRoots(JavaProjectModel model) {
        Set<Path> dirs = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path resourceRoot : sourceSet.resourceRoots()) {
                addIfDirectory(dirs, resourceRoot);
            }
        }
        if (!dirs.isEmpty()) {
            return dirs;
        }
        // The model declared no resource roots at all (no build-model extraction, or a build that registers none); use
        // the filename convention as the guarded fallback so resource-aware consumers still find conventional resources.
        return conventionResourceRoots(model);
    }

    /**
     * Filename-convention fallback: a configured source root that is itself a {@code resources} directory, plus the
     * {@code resources} sibling of every configured {@code java} source root. Used only when the model declared no
     * resource roots; kept identical in shape to the historical derivation.
     */
    private static Set<Path> conventionResourceRoots(JavaProjectModel model) {
        Set<Path> dirs = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                Path normalized = root.toAbsolutePath().normalize();
                if (normalized.getFileName() != null
                        && "resources".equals(normalized.getFileName().toString())) {
                    addIfDirectory(dirs, normalized);
                }
                Path parent = normalized.getParent();
                if (parent != null) {
                    addIfDirectory(dirs, parent.resolve("resources"));
                }
            }
        }
        return dirs;
    }

    private static void addIfDirectory(Set<Path> dirs, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            dirs.add(normalized);
        }
    }
}
