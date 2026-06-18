package io.serena.javarefactor.v3.packages;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.DocSourcePositions;
import com.sun.source.util.DocTreeScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Parse-only javac scan that drives owner-aware package-reference rewriting from the compiler's own parse tree rather
 * than from raw text. It produces two things for a source file:
 * <ul>
 *   <li>a <em>code-name mask</em> marking offsets covered by a real code-level NAME node — an {@link IdentifierTree} or
 *       a qualified {@link MemberSelectTree} chain (this includes the {@code a.b.*} member-select of an on-demand
 *       import) — so an occurrence of the package name inside a string/char literal or a plain comment is never
 *       rewritten, and</li>
 *   <li>the file offsets of every Javadoc <em>reference</em> ({@link ReferenceTree}: the {@code pkg.Type#member} target
 *       of {@code @link}/{@code @linkplain}/{@code @see}/{@code @value}/{@code @throws}), so a fully-qualified Javadoc
 *       reference into a moved package is rewritten too — required by refactor-feature-plan-V3.md §5.4.</li>
 * </ul>
 *
 * <p>The scan is parse-only (no attribution, no classpath), so it is cheap and cannot fail on missing dependencies; it
 * needs only the lexical/syntactic structure to know where names occur. The {@link JavacTask} API keeps doc comments
 * during parse, so {@link DocTrees#getDocCommentTree(TreePath)} resolves them without attribution.
 */
final class PackageReferenceScanner {
    private PackageReferenceScanner() {
    }

    /**
     * The result of a parse-only scan: the code-name {@code codeMask} (length {@code source.length()}) and the
     * {@code [start, end)} file offsets of each Javadoc reference target.
     */
    record Scan(boolean[] codeMask, List<int[]> javadocRefs) {
    }

    /**
     * Parses {@code source} and returns its {@link Scan}. Throws {@link ParseFailure} when the source cannot be parsed,
     * so the caller can fail closed rather than fall back to scanning raw text (which could corrupt strings/comments).
     */
    static Scan scan(String source) throws ParseFailure {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ParseFailure("JDK JavaCompiler is unavailable; run Serena with a JDK rather than a JRE.");
        }
        JavaFileObject fileObject = new StringSource(source);
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> { }, List.of(), null, List.of(fileObject));
        CompilationUnitTree unit;
        DocTrees docTrees;
        try {
            Iterator<? extends CompilationUnitTree> units = task.parse().iterator();
            if (!units.hasNext()) {
                throw new ParseFailure("the parser produced no compilation unit");
            }
            unit = units.next();
            docTrees = DocTrees.instance(task);
        } catch (IOException | RuntimeException error) {
            throw new ParseFailure(String.valueOf(error.getMessage()));
        }
        boolean[] mask = new boolean[source.length()];
        List<int[]> javadocRefs = new ArrayList<>();
        final CompilationUnitTree scannedUnit = unit;
        final DocSourcePositions docPositions = docTrees.getSourcePositions();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null) {
                    collectJavadoc(tree);
                }
                return super.scan(tree, unused);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                mark(node);
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                mark(node);
                return super.visitMemberSelect(node, unused);
            }

            private void collectJavadoc(Tree tree) {
                DocCommentTree docComment;
                try {
                    docComment = docTrees.getDocCommentTree(new TreePath(getCurrentPath(), tree));
                } catch (RuntimeException ignored) {
                    return;
                }
                if (docComment == null) {
                    return;
                }
                new DocTreeScanner<Void, Void>() {
                    @Override
                    public Void visitReference(ReferenceTree reference, Void unused) {
                        long start = docPositions.getStartPosition(scannedUnit, docComment, reference);
                        long end = docPositions.getEndPosition(scannedUnit, docComment, reference);
                        if (start >= 0 && end > start && end <= source.length()) {
                            javadocRefs.add(new int[] {(int) start, (int) end});
                        }
                        return super.visitReference(reference, unused);
                    }
                }.scan(docComment, null);
            }

            private void mark(Tree node) {
                long start = docPositions.getStartPosition(scannedUnit, node);
                long end = docPositions.getEndPosition(scannedUnit, node);
                if (start < 0 || end < 0) {
                    return;
                }
                int from = (int) Math.max(0, start);
                int to = (int) Math.min(mask.length, end);
                for (int i = from; i < to; i++) {
                    mask[i] = true;
                }
            }
        }.scan(unit, null);
        return new Scan(mask, javadocRefs);
    }

    /** Raised when a source file cannot be parsed, so the caller fails closed instead of scanning raw text. */
    static final class ParseFailure extends Exception {
        ParseFailure(String message) {
            super(message);
        }
    }

    /** An in-memory source file for the parse-only task; carries the exact text so mask offsets align with the caller's. */
    private static final class StringSource extends SimpleJavaFileObject {
        private final String content;

        StringSource(String content) {
            super(URI.create("string:///RenamePackageScan.java"), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
