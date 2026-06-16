package io.serena.javarefactor.operations.inline_method;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.shared.ExpressionPurity;
import io.serena.javarefactor.shared.ExpressionPurityAnalyzer;
import io.serena.javarefactor.shared.SourceText;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Argument/receiver evaluation-order and side-effect safety unit for inline method (G014).
 *
 * <p>Extracted from the inline-method monolith, this is the single authority for the question "is it safe to substitute
 * this call-site expression into the inlined body?" Two concerns live here:
 * <ul>
 *   <li><b>Reorder safety</b> — substituting an argument or receiver into the body moves it relative to the other
 *       expressions at the call site. The only genuine green-light is
 *       {@link ExpressionPurityAnalyzer#isReorderSafe(TreePath, Trees, Types)} evaluated against a real javac
 *       {@link TreePath}, obtained by attributing the call-site file in memory and locating the expression by its source
 *       range. When the file cannot be attributed standalone, the guard degrades to a conservative structural purity
 *       classification — never a false green-light, and at least as strict as the prior text-only behavior.</li>
 *   <li><b>Duplicate-evaluation safety</b> — a parameter referenced more than once in the body duplicates its argument.
 *       Only a proven reorder-safe argument may be duplicated; anything else is refused so a side effect is never run a
 *       different number of times than the original call.</li>
 * </ul>
 */
final class EvaluationOrderGuard {

    private final JavaProjectModel model;
    private final ExpressionPurityAnalyzer purityAnalyzer;
    /**
     * Per-file in-memory attribution cache (G033/G014). Mapping a call-site argument/receiver {@code SourceRange} to a
     * real javac {@link TreePath} is the only way to obtain the canonical
     * {@link ExpressionPurityAnalyzer#isReorderSafe(TreePath, Trees, Types)} green-light, because the inline package
     * cannot reach the project-wide resolved trees held by {@code SemanticIndex}. Self-contained files attribute cleanly;
     * files that cannot be attributed standalone degrade to a conservative structural classifier, never a false
     * green-light.
     */
    private final Map<Path, Optional<Attributed>> attributions = new HashMap<>();

    EvaluationOrderGuard(JavaProjectModel model, ExpressionPurityAnalyzer purityAnalyzer) {
        this.model = model;
        this.purityAnalyzer = purityAnalyzer;
    }

    /**
     * Canonical reorder-safety gate for a call-site argument/receiver expression. Returns true only when the expression
     * can be proven reorder-safe — either via the resolved javac path, or (when no resolved context is reachable) via a
     * conservative structural PURE classification of its text.
     */
    boolean reorderSafe(Path file, SemanticIndex.SourceRange range, String expressionText) {
        if (range != null) {
            Optional<Attributed> attributed = attributionFor(file);
            if (attributed.isPresent()) {
                Optional<TreePath> path = attributed.get().expressionPathAt(range.start(), range.end());
                if (path.isPresent()) {
                    return purityAnalyzer.isReorderSafe(path.get(), attributed.get().trees(), attributed.get().types());
                }
            }
        }
        // Conservative fallback: no resolved context is reachable, so a structural PURE classification is the most that
        // can be said. This is not a reorder proof; it preserves the prior conservative behavior without ever upgrading
        // a detached string to a green-light.
        return purityAnalyzer.classify(expressionText) == ExpressionPurity.PURE;
    }

    /**
     * Counts how many times each parameter name is referenced as a standalone identifier in {@code expression},
     * skipping comments, string/char literals, and qualified ({@code a.b}) member names.
     */
    Map<String, Integer> parameterUseCounts(String expression, List<String> parameterNames) {
        Map<String, Integer> counts = new HashMap<>();
        for (String parameterName : parameterNames) {
            counts.put(parameterName, 0);
        }
        int index = 0;
        while (index < expression.length()) {
            if (expression.startsWith("//", index)) {
                index = skipLineComment(expression, index);
                continue;
            }
            if (expression.startsWith("/*", index)) {
                index = skipBlockComment(expression, index);
                continue;
            }
            char current = expression.charAt(index);
            if (current == '\"' || current == '\'') {
                index = skipQuoted(expression, index, current);
                continue;
            }
            if (Character.isJavaIdentifierStart(current)) {
                int start = index++;
                while (index < expression.length() && Character.isJavaIdentifierPart(expression.charAt(index))) {
                    index++;
                }
                String identifier = expression.substring(start, index);
                if (counts.containsKey(identifier) && !isQualifiedIdentifier(expression, start, identifier)) {
                    counts.put(identifier, counts.get(identifier) + 1);
                }
                continue;
            }
            index++;
        }
        return counts;
    }

    private Optional<Attributed> attributionFor(Path file) {
        Path key = file.toAbsolutePath().normalize();
        Optional<Attributed> cached = attributions.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<Attributed> attributed;
        try {
            String fileName = key.getFileName() == null ? "Source.java" : key.getFileName().toString();
            attributed = Attributed.attribute(SourceText.read(model, key), fileName);
        } catch (IOException error) {
            attributed = Optional.empty();
        }
        attributions.put(key, attributed);
        return attributed;
    }

    static boolean isQualifiedIdentifier(String expression, int start, String identifier) {
        if ("this".equals(identifier)) {
            return false;
        }
        int previous = start - 1;
        while (previous >= 0 && Character.isWhitespace(expression.charAt(previous))) {
            previous--;
        }
        return previous >= 0 && expression.charAt(previous) == '.';
    }

    private static int skipQuoted(String expression, int start, char quote) {
        int index = start + 1;
        while (index < expression.length()) {
            char current = expression.charAt(index++);
            if (current == '\\' && index < expression.length()) {
                index++;
            } else if (current == quote) {
                break;
            }
        }
        return index;
    }

    private static int skipLineComment(String expression, int start) {
        int index = start;
        while (index < expression.length() && expression.charAt(index++) != '\n') {
            // Skip to the line terminator.
        }
        return index;
    }

    private static int skipBlockComment(String expression, int start) {
        int index = start;
        while (index < expression.length()) {
            char current = expression.charAt(index++);
            if (current == '*' && index < expression.length() && expression.charAt(index) == '/') {
                return index + 1;
            }
        }
        return index;
    }

    /**
     * An in-memory attributed compilation of a single source file. It exists so the inline planner can recover real
     * javac {@link TreePath}s for call-site argument/receiver expressions (keyed by their source range) and feed them to
     * {@link ExpressionPurityAnalyzer#isReorderSafe(TreePath, Trees, Types)} — the only resolved reorder-safety verdict
     * available, since the project-wide trees held by {@code SemanticIndex} are not reachable from this package. A file
     * that cannot be attributed standalone yields {@link Optional#empty()} and callers degrade conservatively.
     */
    private static final class Attributed {
        private final Trees trees;
        private final Types types;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;

        private Attributed(Trees trees, Types types, CompilationUnitTree unit, SourcePositions positions) {
            this.trees = trees;
            this.types = types;
            this.unit = unit;
            this.positions = positions;
        }

        private Trees trees() {
            return trees;
        }

        private Types types() {
            return types;
        }

        static Optional<Attributed> attribute(String source, String fileName) {
            if (source == null) {
                return Optional.empty();
            }
            try {
                if (ToolProvider.getSystemJavaCompiler() == null) {
                    return Optional.empty();
                }
                JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                        .getTask(null, null, diagnostic -> { }, List.of("-proc:none"), null,
                                List.of(new StringJavaFileObject(stripExtension(fileName), source)));
                Iterable<? extends CompilationUnitTree> units = task.parse();
                task.analyze();
                Trees trees = Trees.instance(task);
                SourcePositions positions = trees.getSourcePositions();
                for (CompilationUnitTree unit : units) {
                    return Optional.of(new Attributed(trees, task.getTypes(), unit, positions));
                }
                return Optional.empty();
            } catch (RuntimeException | java.io.IOException error) {
                return Optional.empty();
            }
        }

        /** The smallest expression whose source span exactly matches {@code [start, end)}, or empty when none matches. */
        Optional<TreePath> expressionPathAt(int start, int end) {
            TreePath[] found = {null};
            new TreePathScanner<Void, Void>() {
                @Override
                public Void scan(Tree tree, Void unused) {
                    if (found[0] == null && tree instanceof ExpressionTree) {
                        long nodeStart = positions.getStartPosition(unit, tree);
                        long nodeEnd = positions.getEndPosition(unit, tree);
                        if (nodeStart == start && nodeEnd == end) {
                            found[0] = new TreePath(getCurrentPath(), tree);
                        }
                    }
                    return super.scan(tree, unused);
                }
            }.scan(unit, null);
            return Optional.ofNullable(found[0]);
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String source;

        private StringJavaFileObject(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
