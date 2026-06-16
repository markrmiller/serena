package io.serena.javarefactor.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Verifies the G004 reorder-safety contract: a reorder-safe verdict can only be produced from real javac
 * {@link TreePath} analysis, and a detached string is a refusal-only fallback that can never green-light.
 */
class ExpressionPurityReorderSafetyTest {

    private final ExpressionPurityAnalyzer analyzer = new ExpressionPurityAnalyzer();

    @Test
    void treePathPureExpressionIsReorderSafe() {
        assertTrue(reorderSafe("class C { final int f = 1; int m() { return f; } }", "m"));
        assertTrue(reorderSafe("class C { int m(int p) { int x = p; return x; } }", "m"));
    }

    @Test
    void treePathSideEffectingExpressionIsNotReorderSafe() {
        // Method invocation: no effects model -> never reorder-safe.
        assertFalse(reorderSafe(
                "class C { java.util.List<String> l = new java.util.ArrayList<>();"
                        + " boolean m(String s) { return l.add(s); } }",
                "m"));
        // Increment is a side effect.
        assertFalse(reorderSafe("class C { int c; int m() { return c++; } }", "m"));
        // Assignment is a side effect.
        assertFalse(reorderSafe("class C { int c; int m() { return (c = 3); } }", "m"));
    }

    @Test
    void treePathNonFinalFieldReadIsPureButNotReorderSafe() {
        // Structurally pure, but reads mutable shared state -> unsafe to reorder past an intervening write.
        assertFalse(reorderSafe("class C { int f = 1; int m() { return f; } }", "m"));
    }

    @Test
    void detachedStringIsNeverGreenLit() {
        // The string overload is a hard refusal regardless of how "pure" the snippet looks.
        assertFalse(analyzer.isReorderSafe("customer.name"));
        assertFalse(analyzer.isReorderSafe("42"));
        assertFalse(analyzer.isReorderSafe("a + b"));
        assertFalse(analyzer.isReorderSafe("counter++"));

        // classify(String) still answers the coarse purity question, but that PURE verdict is NOT a safety verdict:
        // the same snippet refuses under the reorder-safety contract.
        assertEquals(ExpressionPurity.PURE, analyzer.classify("customer.name"));
        assertFalse(analyzer.isReorderSafe("customer.name"));
    }

    @Test
    void provesDetachedDefaultSafeAcceptsOnlyCompileTimeConstants() {
        // Literals and compile-time-constant compositions are provably stable from detached text alone.
        assertTrue(analyzer.provesDetachedDefaultSafe("42"));
        assertTrue(analyzer.provesDetachedDefaultSafe("-1"));
        assertTrue(analyzer.provesDetachedDefaultSafe("1 + 2 * 3"));
        assertTrue(analyzer.provesDetachedDefaultSafe("\"literal\""));
        assertTrue(analyzer.provesDetachedDefaultSafe("true"));
        assertTrue(analyzer.provesDetachedDefaultSafe("null"));
        assertTrue(analyzer.provesDetachedDefaultSafe("(1 << 4)"));
        assertTrue(analyzer.provesDetachedDefaultSafe("'a'"));
        assertTrue(analyzer.provesDetachedDefaultSafe("true ? 1 : 2"));
    }

    @Test
    void provesDetachedDefaultSafeAcceptsTypeQualifiedConstantsAndClassLiterals() {
        // Strict classifier: an uppercase-initial type chain selecting an uppercase-initial member, or a class literal,
        // is the only member-select shape accepted from detached text (enum constants / static constants / Foo.class).
        assertTrue(analyzer.provesDetachedDefaultSafe("Currency.USD"));
        assertTrue(analyzer.provesDetachedDefaultSafe("Math.PI"));
        assertTrue(analyzer.provesDetachedDefaultSafe("java.math.RoundingMode.HALF_UP"));
        assertTrue(analyzer.provesDetachedDefaultSafe("String.class"));
    }

    @Test
    void provesDetachedDefaultSafeRefusesMethodCallsAllocationAndValueAccess() {
        // Method invocations and allocation can observe/mutate state or vary per call -> never green-lit from text.
        assertFalse(analyzer.provesDetachedDefaultSafe("System.nanoTime()"));
        assertFalse(analyzer.provesDetachedDefaultSafe("nanoTime()"));
        assertFalse(analyzer.provesDetachedDefaultSafe("UUID.randomUUID()"));
        assertFalse(analyzer.provesDetachedDefaultSafe("new Object()"));
        // A value-receiver member access (lowercase qualifier) is unprovable without AST proof.
        assertFalse(analyzer.provesDetachedDefaultSafe("customer.name"));
        assertFalse(analyzer.provesDetachedDefaultSafe("customer.getName()"));
        // A bare identifier could be a local/field read; not provably constant from text.
        assertFalse(analyzer.provesDetachedDefaultSafe("value"));
        assertFalse(analyzer.provesDetachedDefaultSafe(""));
        assertFalse(analyzer.provesDetachedDefaultSafe(null));
    }

    @Test
    void missingTreesContextRefusesEvenForStructurallyPureExpression() {
        // No resolved context (trees == null) cannot prove stability, so the TreePath overload refuses.
        assertFalse(analyzer.isReorderSafe(null, null, null));
    }

    /** Compiles {@code classSource}, locates the single return expression in {@code methodName}, and asks the
     * canonical {@link ExpressionPurityAnalyzer#isReorderSafe(TreePath, Trees, Types)} entry point about it. */
    private boolean reorderSafe(String classSource, String methodName) {
        JavaFileObject source = new SimpleJavaFileObject(
                URI.create("string:///ReorderCtx.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return classSource;
            }
        };
        try {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                    .getTask(null, null, null, List.of("-proc:none"), null, List.of(source));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            Trees trees = Trees.instance(task);
            Types types = task.getTypes();
            List<TreePath> returns = new ArrayList<>();
            new TreePathScanner<Void, Void>() {
                private boolean inTarget;

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    boolean previous = inTarget;
                    inTarget = methodName.contentEquals(node.getName());
                    super.visitMethod(node, unused);
                    inTarget = previous;
                    return null;
                }

                @Override
                public Void visitReturn(ReturnTree node, Void unused) {
                    if (inTarget && node.getExpression() != null) {
                        returns.add(new TreePath(getCurrentPath(), node.getExpression()));
                    }
                    return super.visitReturn(node, unused);
                }
            }.scan(units, null);
            if (returns.size() != 1) {
                throw new IllegalStateException("expected exactly one return expression, found " + returns.size());
            }
            return analyzer.isReorderSafe(returns.get(0), trees, types);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
