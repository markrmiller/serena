package io.serena.javarefactor.v3.packages;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build-graph package-to-source-root facts (refactor-feature-plan-V3.md §5.3 collision check "split packages across
 * modules" / §5.4 module-info handling).
 *
 * <p>This is the AUTHORITATIVE, build-model-derived map of which physical source roots (or, in a module-source-path
 * layout, which module roots) each Java package occupies — for EVERY source package, exported/opened or not. A package
 * that is declared by files landing under more than one distinct root is a <em>split package</em>: the same package name
 * is owned by two source roots/modules at once. Split-package detection driven by these facts is therefore independent
 * of {@code module-info.java} {@code exports}/{@code opens}, which only describe a SUBSET of packages and cannot reveal a
 * split of a non-exported package.
 *
 * <p>The "effective root" of a file mirrors the planners' own relocation rooting: the file's on-disk parent directory
 * with its declared package path stripped off. In a flat layout this is the configured source root; in a
 * module-source-path layout ({@code <sourceRoot>/<moduleDir>/<packagePath>}) it is the per-module root, so two modules
 * that both declare {@code com.x.a} under one configured {@code src/main/java} are still seen as two distinct roots — the
 * real cross-module split. When a file's directory does not end with its declared package path (an unconventional
 * layout), the longest configured source root containing it is used instead, so the bucketing never silently mis-roots.
 */
final class PackageSourceRootFacts {

    /** package name -> the distinct effective source roots (insertion-ordered) that physically contain that package. */
    private final Map<String, LinkedHashSet<Path>> rootsByPackage;

    private PackageSourceRootFacts(Map<String, LinkedHashSet<Path>> rootsByPackage) {
        this.rootsByPackage = rootsByPackage;
    }

    /**
     * Computes the package-to-source-root facts for the whole project from the build model. {@code packageByFile} carries
     * each project Java file's declared package (already parsed by the caller). {@code module-info.java} contributes no
     * package of its own (it declares no type), so it is naturally absent from the map.
     */
    static PackageSourceRootFacts compute(JavaProjectModel model, Map<Path, String> packageByFile) {
        List<Path> configuredRoots = configuredSourceRoots(model);
        Map<String, LinkedHashSet<Path>> rootsByPackage = new LinkedHashMap<>();
        for (Map.Entry<Path, String> entry : packageByFile.entrySet()) {
            String declared = entry.getValue();
            if (declared.isEmpty()) {
                continue;
            }
            Path root = effectiveSourceRoot(entry.getKey(), declared, configuredRoots);
            rootsByPackage.computeIfAbsent(declared, key -> new LinkedHashSet<>()).add(root);
        }
        return new PackageSourceRootFacts(rootsByPackage);
    }

    /** The distinct effective source/module roots that physically contain {@code packageName} (empty when unknown). */
    Set<Path> rootsFor(String packageName) {
        LinkedHashSet<Path> roots = rootsByPackage.get(packageName);
        return roots == null ? Set.of() : roots;
    }

    /** A package is split when its declarations land under more than one distinct effective source/module root. */
    boolean isSplit(String packageName) {
        return rootsFor(packageName).size() > 1;
    }

    /**
     * The first package in {@code packages} that the build graph reports as split across more than one source root/module,
     * or {@code null} when none is split. Used by the planners to refuse a rename/move that would carry a physically
     * split package across roots without an explicit module strategy.
     */
    String firstSplitPackage(Iterable<String> packages) {
        for (String packageName : packages) {
            if (isSplit(packageName)) {
                return packageName;
            }
        }
        return null;
    }

    private static List<Path> configuredSourceRoots(JavaProjectModel model) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                roots.add(root.toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(roots);
    }

    private static Path effectiveSourceRoot(Path file, String declaredPackage, List<Path> configuredRoots) {
        Path dir = file.toAbsolutePath().normalize().getParent();
        if (dir == null) {
            return sourceRootFor(file, configuredRoots);
        }
        Path packagePath = packageToPath(declaredPackage);
        if (!dir.endsWith(packagePath)) {
            return sourceRootFor(file, configuredRoots);
        }
        Path root = dir;
        for (int i = 0; i < packagePath.getNameCount() && root != null; i++) {
            root = root.getParent();
        }
        return root != null ? root : sourceRootFor(file, configuredRoots);
    }

    private static Path sourceRootFor(Path file, List<Path> configuredRoots) {
        Path normalized = file.toAbsolutePath().normalize();
        Path best = null;
        for (Path root : configuredRoots) {
            if (normalized.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) {
                best = root;
            }
        }
        return best != null ? best : normalized.getParent();
    }

    private static Path packageToPath(String dottedPackage) {
        if (dottedPackage.isEmpty()) {
            return Path.of("");
        }
        return Path.of("", dottedPackage.split("\\."));
    }
}
