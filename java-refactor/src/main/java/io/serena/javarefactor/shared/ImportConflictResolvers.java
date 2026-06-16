package io.serena.javarefactor.shared;

import io.serena.javarefactor.compiler.SemanticIndex;
import java.nio.file.Path;

/**
 * Factories for {@link ImportManager.SimpleNameConflictResolver} backed by javac/
 * {@link SemanticIndex} facts. These let the otherwise pure-text {@link ImportManager}
 * recognise simple-name collisions it cannot see from the edited file's import section alone —
 * specifically a type in the SAME package as the edited file, or another visible project type
 * already claiming the simple name. When the compiler-backed facts are unavailable the manager's
 * existing text-based existing-import check is used unchanged, so behaviour never regresses.
 */
public final class ImportConflictResolvers {

    private ImportConflictResolvers() {
    }

    /**
     * Builds a resolver that reports a collision when the rendered simple name would clash with a
     * <em>different</em> top-level type declared in {@code editedFilePackage} (case (a): same-package
     * collision). Same-package siblings are visible without an import, so importing a different type
     * under the same simple name — or simplifying it — would be ambiguous and must be left fully
     * qualified. The edited file itself is excluded so a self-reference is never flagged.
     *
     * @param index the semantic index over the project, used to enumerate same-package project types
     * @param editedFile the file whose source is being rewritten (excluded from collision matches)
     * @param editedFilePackage the package the edited file lives in
     */
    public static ImportManager.SimpleNameConflictResolver samePackageAndProject(
            SemanticIndex index, Path editedFile, String editedFilePackage) {
        if (index == null || editedFile == null) {
            return (simpleName, candidateFqn) -> false;
        }
        String pkg = editedFilePackage == null ? "" : editedFilePackage;
        return (simpleName, candidateFqn) -> {
            if (simpleName == null || simpleName.isBlank() || candidateFqn == null) {
                return false;
            }
            String candidatePackage = packagePart(candidateFqn);
            // The candidate already lives in the edited file's package: it is the same-package type,
            // not a collision against one.
            if (candidatePackage.equals(pkg)) {
                return false;
            }
            // (a) A different top-level type with this simple name lives in the edited file's package.
            return index.targetPackageHasType(pkg, simpleName, editedFile);
        };
    }

    private static String packagePart(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? "" : qualifiedName.substring(0, lastDot);
    }
}
