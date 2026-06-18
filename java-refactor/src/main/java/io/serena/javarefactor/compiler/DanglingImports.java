package io.serena.javarefactor.compiler;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Locates the import declarations in a Java source that name a type the current edit removes, so a propagating safe
 * delete can strip them before the composed edit reaches the authoritative before/after javac validator. The
 * {@link io.serena.javarefactor.compiler.ReachabilityGraph} models USAGES, not import statements, so a file that carries
 * a stale single-type import of a now-deleted type (without otherwise using it) keeps that type deletable — and the
 * leftover import becomes a {@code cannot find symbol} error that would reject an otherwise-safe deletion. Removing the
 * import is exact and safe precisely because the type is being deleted: a file that actually USED the type would have a
 * live reachability edge and the type would not be deletable in the first place.
 *
 * <p>Compiler-backed (a real javac parse via {@link Trees}/{@link SourcePositions}) and parse-only — import FQNs are
 * literal in the source, so no classpath is needed and it operates on in-memory post-edit content. The match is by exact
 * fully-qualified name: a single-type {@code import a.b.C} (or a static {@code import static a.b.C.member} /
 * {@code import static a.b.C.*}) is removed only when {@code a.b.C} is one of the removed FQNs. On-demand wildcard
 * imports ({@code import a.b.*}) are never removed — deleting one type does not, in general, empty its package.</p>
 */
public final class DanglingImports {

    private DanglingImports() {
    }

    /**
     * Half-open {@code [start, end)} character spans of import declarations naming one of {@code removedTypeFqns}, each
     * expanded to cover the whole physical line (leading indentation through the trailing newline) so the removal leaves
     * no blank residue. Spans are in source order; empty when the source names none of the removed types or cannot be
     * parsed.
     */
    public static List<long[]> spansToRemove(String source, Set<String> removedTypeFqns) {
        List<long[]> spans = new ArrayList<>();
        if (source == null || source.isEmpty() || removedTypeFqns.isEmpty()) {
            return spans;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return spans;
        }
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> {
            }, List.of("-proc:none"), null, List.of(new StringSource(source)));
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                for (ImportTree imp : unit.getImports()) {
                    if (!namesRemovedType(imp, removedTypeFqns)) {
                        continue;
                    }
                    int start = (int) positions.getStartPosition(unit, imp);
                    int end = (int) positions.getEndPosition(unit, imp);
                    if (start < 0 || end < start) {
                        continue;
                    }
                    spans.add(lineSpan(source, start, end));
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // A malformed source contributes no removals; the edit's javac validation reports the real error.
        }
        return spans;
    }

    /**
     * Half-open {@code [start, end)} character spans of <em>single-type</em> import declarations whose imported simple
     * name is no longer referenced anywhere in {@code source} outside the import section, each expanded to cover the whole
     * physical line (as in {@link #spansToRemove}). This is the recipe engine's "remove stale imports" step
     * (refactor-feature-plan-V3.md §14.2): after a recipe replaces a referenced type/method/constructor/field/annotation,
     * the owning type's import can become dangling, and a leftover unused import is at best noise and at worst (with
     * {@code -Werror}/unused-import linting) a build break.
     *
     * <p>Conservative and exact, in the same parse-only spirit as {@link #spansToRemove}: a single-type {@code import a.b.C}
     * is removed only when the simple name {@code C} appears in <b>no</b> {@code IdentifierTree}/{@code MemberSelectTree}
     * of the parsed unit except the import declarations themselves — i.e. the type is genuinely no longer referenced. An
     * import whose simple name still occurs anywhere (a surviving reference, or a different rule that left a use in place)
     * is retained. Static imports and on-demand wildcard imports ({@code import a.b.*}) are never removed (a wildcard does
     * not bind a single simple name, and a static member's residual use is harder to prove gone parse-only). No classpath
     * is needed: the match is purely lexical-over-AST.</p>
     *
     * <p>The import <em>spans</em> are computed against {@code originalSource} (so the returned offsets address the caller's
     * pre-edit text, the coordinate system its other edits use), while "still referenced" is decided against
     * {@code postEditSource} (the content after the recipe's body replacements are applied). Recipe edits live in the body
     * below the import block, so an import declaration occupies the same span in both texts; passing them separately keeps
     * the staleness decision honest about the post-edit world without shifting the emitted removal offsets.</p>
     */
    public static List<long[]> staleSingleTypeImportSpans(String originalSource, String postEditSource) {
        List<long[]> spans = new ArrayList<>();
        if (originalSource == null || originalSource.isEmpty()) {
            return spans;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return spans;
        }
        try {
            Set<String> usedSimpleNames = usedSimpleNames(postEditSource == null ? originalSource : postEditSource);
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> {
            }, List.of("-proc:none"), null, List.of(new StringSource(originalSource)));
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                for (ImportTree imp : unit.getImports()) {
                    if (imp.isStatic()) {
                        continue;
                    }
                    String id = imp.getQualifiedIdentifier().toString();
                    if (id.endsWith(".*")) {
                        continue; // on-demand wildcard binds no single simple name
                    }
                    String simple = simpleNameOf(id);
                    if (simple.isEmpty() || usedSimpleNames.contains(simple)) {
                        continue; // still referenced somewhere → keep
                    }
                    int start = (int) positions.getStartPosition(unit, imp);
                    int end = (int) positions.getEndPosition(unit, imp);
                    if (start < 0 || end < start) {
                        continue;
                    }
                    spans.add(lineSpan(originalSource, start, end));
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // A malformed source contributes no removals; the edit's javac validation reports the real error.
        }
        return spans;
    }

    /** Parses {@code source} (parse-only, no classpath) and returns every simple name its body references. */
    private static Set<String> usedSimpleNames(String source) {
        Set<String> used = new LinkedHashSet<>();
        if (source == null || source.isEmpty()) {
            return used;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return used;
        }
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> {
            }, List.of("-proc:none"), null, List.of(new StringSource(source)));
            for (CompilationUnitTree unit : task.parse()) {
                collectUsedSimpleNames(unit, used);
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // Best effort: an unparseable post-edit source yields no names, which only keeps imports (never removes).
        }
        return used;
    }

    /**
     * Adds to {@code used} every simple name referenced by an {@link IdentifierTree} or the selected member of a
     * {@link MemberSelectTree} in the unit's type declarations (the import declarations are excluded, so an import never
     * counts as a use of itself). Conservative: any extra identifiers collected can only cause an import to be
     * <em>kept</em>, never spuriously removed.
     */
    private static void collectUsedSimpleNames(CompilationUnitTree unit, Set<String> used) {
        TreeScanner<Void, Void> scanner = new TreeScanner<>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                used.add(node.getName().toString());
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                used.add(node.getIdentifier().toString());
                return super.visitMemberSelect(node, unused);
            }
        };
        for (Tree decl : unit.getTypeDecls()) {
            scanner.scan(decl, null);
        }
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static boolean namesRemovedType(ImportTree imp, Set<String> removedTypeFqns) {
        String id = imp.getQualifiedIdentifier().toString();
        if (imp.isStatic()) {
            // import static <typeFqn>.<member>  or  import static <typeFqn>.*  — the type is the prefix.
            String typePart = id.endsWith(".*") ? id.substring(0, id.length() - 2) : stripLastSegment(id);
            return removedTypeFqns.contains(typePart);
        }
        // import <typeFqn>;  — an on-demand "import a.b.*" never equals a type FQN, so it is correctly left alone.
        return removedTypeFqns.contains(id);
    }

    private static String stripLastSegment(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(0, dot);
    }

    private static long[] lineSpan(String source, int start, int end) {
        int lineStart = start;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        // Only swallow the leading run if it is pure whitespace (imports normally stand alone on their line).
        int from = start;
        boolean onlyWhitespace = true;
        for (int i = lineStart; i < start; i++) {
            if (!Character.isWhitespace(source.charAt(i))) {
                onlyWhitespace = false;
                break;
            }
        }
        if (onlyWhitespace) {
            from = lineStart;
        }
        int to = end;
        while (to < source.length() && source.charAt(to) != '\n' && Character.isWhitespace(source.charAt(to))) {
            to++;
        }
        if (to < source.length() && source.charAt(to) == '\n') {
            to++;
        }
        return new long[] {from, to};
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        private StringSource(String code) {
            super(URI.create("string:///Source.java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
