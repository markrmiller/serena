package io.serena.javarefactor.v3.packages;

import io.serena.javarefactor.edits.PlannerSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Owner-aware package-reference rewriting shared by {@link RenamePackagePlanner} and {@link MovePackagePlanner}
 * (refactor-feature-plan-V3.md §5.4). For a single source file it rewrites every reference whose true owning package is
 * in the moved set — single-type imports ({@code import old.Type;}), on-demand/wildcard imports ({@code import old.*;}),
 * static imports ({@code import static old.Type.member;}), fully-qualified references ({@code old.Type}), and Javadoc
 * references ({@code @link}/{@code @linkplain}/{@code @see}/{@code @value}/{@code @throws} targeting {@code old.Type}).
 *
 * <p>Detection is driven entirely by the javac parse tree carried in the {@link PackageReferenceScanner.Scan}: a code
 * occurrence is rewritten only where the code-name mask says a real identifier/member-select node covers it, and a
 * Javadoc occurrence only where a DocTree reference span covers it. An occurrence of the package name inside a string
 * literal or a plain comment is covered by neither and is therefore never rewritten — so the "do not rewrite arbitrary
 * string literals" default holds for both planners, not just rename.
 */
final class PackageReferenceRewriter {
    private PackageReferenceRewriter() {
    }

    /**
     * Rewrites references to the moved package tree in {@code source}. The owning package of an occurrence is the LONGEST
     * known project package ({@code allPackages}) that begins at the occurrence and owns a type/wildcard reference there;
     * the occurrence is rewritten only when that owner is in {@code movedPackages}, swapping its {@code oldPackage} prefix
     * for {@code newPackage}. {@code packageDeclStart} is the offset of the file's own package-declaration name (or -1),
     * which is rewritten separately and must never be treated as a reference here.
     */
    static List<PlannerSupport.TextEdit> rewrite(Path file, String source, String oldPackage, String newPackage,
            Set<String> allPackages, Set<String> movedPackages, PackageReferenceScanner.Scan scan,
            int packageDeclStart) {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        boolean[] codeMask = scan.codeMask();
        int from = 0;
        while (true) {
            int index = source.indexOf(oldPackage, from);
            if (index < 0) {
                break;
            }
            from = index + 1;
            // Skip the file's own package declaration occurrence (rewritten by the dedicated package-decl edit; never a
            // "reference"). When the file does not move, its declaration is a sibling that must not be touched here.
            if (index == packageDeclStart) {
                continue;
            }
            // AST gate: only rewrite where the parse tree marks a real code name (excludes strings/comments/Javadoc).
            if (index >= codeMask.length || !codeMask[index]) {
                continue;
            }
            // Left boundary: the preceding char must not continue an identifier or be a '.' (which would make this the
            // tail of a LONGER package, e.g. "acme.app" inside "com.acme.app" — only rewrite at a true token start).
            if (index > 0) {
                char before = source.charAt(index - 1);
                if (Character.isJavaIdentifierPart(before) || before == '.') {
                    continue;
                }
            }
            String owner = longestKnownPackageAt(source, index, oldPackage, allPackages);
            if (owner == null || !movedPackages.contains(owner)) {
                continue;
            }
            int ownerEnd = index + owner.length();
            edits.add(new PlannerSupport.TextEdit(file, index, ownerEnd,
                    newPackage + owner.substring(oldPackage.length()), "PACKAGE_REFERENCE"));
            from = ownerEnd;
        }
        // Javadoc references are not covered by the code-name mask; each DocTree reference span begins at the
        // fully-qualified target, so a moving-package prefix is rewritten in place while the #member tail is preserved.
        for (int[] span : scan.javadocRefs()) {
            int index = span[0];
            if (index == packageDeclStart) {
                continue;
            }
            String owner = longestKnownPackageAt(source, index, oldPackage, allPackages);
            if (owner == null || !movedPackages.contains(owner)) {
                continue;
            }
            int ownerEnd = index + owner.length();
            if (ownerEnd > span[1]) {
                continue;
            }
            edits.add(new PlannerSupport.TextEdit(file, index, ownerEnd,
                    newPackage + owner.substring(oldPackage.length()), "JAVADOC_REFERENCE"));
        }
        return edits;
    }

    /**
     * The longest known project package in {@code allPackages} that begins exactly at {@code index} and is immediately
     * followed by {@code '.'} then either an identifier start (it owns a {@code .Type} reference / single or static
     * import / FQN) or {@code '*'} (an on-demand wildcard import {@code old.*}). Only packages equal to or prefixed by
     * {@code oldPackage} are considered, since the caller already anchored on it. Returns {@code null} when no known
     * package owns a reference at this position.
     */
    static String longestKnownPackageAt(String source, int index, String oldPackage, Set<String> allPackages) {
        String best = null;
        for (String candidate : allPackages) {
            if (!candidate.equals(oldPackage) && !candidate.startsWith(oldPackage + ".")) {
                continue;
            }
            if (best != null && candidate.length() <= best.length()) {
                continue;
            }
            int end = index + candidate.length();
            if (end + 1 >= source.length()) {
                continue;
            }
            if (!source.regionMatches(index, candidate, 0, candidate.length())) {
                continue;
            }
            if (source.charAt(end) != '.') {
                continue;
            }
            char tail = source.charAt(end + 1);
            if (!Character.isJavaIdentifierStart(tail) && tail != '*') {
                continue;
            }
            best = candidate;
        }
        return best;
    }
}
