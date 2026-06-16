package io.serena.javarefactor.shared;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Expression purity / evaluation-order classifier used before V2 transformations reorder, duplicate, or hoist
 * expressions.
 *
 * <p>Two analysis modes are provided:
 * <ul>
 *   <li><b>Snippet mode</b> ({@link #classify(String)}, {@link #classify(ExpressionTree)}): a conservative,
 *       structure-only classifier that never resolves symbols. It is retained for callers that only have detached
 *       text and cannot supply an analyzed compilation context.</li>
 *   <li><b>Context mode</b> ({@link #analyze(TreePath, Trees, Types)} and friends): a symbol-resolved classifier that
 *       drives its decisions from javac {@link Element} identity and the enclosing method's flow facts rather than
 *       re-parsed snippet text. It distinguishes <em>final / effectively-final</em> reads (stable under reordering)
 *       from mutable-state reads, classifies method references by inspecting the referenced target, and flags
 *       evaluation-order sensitivity introduced by side-effecting subexpressions.</li>
 * </ul>
 *
 * <p>Soundness-critical callers (introduce parameter, introduce field, move instance method, inline) should prefer
 * context mode and consult {@link PurityAnalysis#isReorderSafe()} rather than purity alone, because a non-final field
 * read can be {@link ExpressionPurity#PURE} yet still unsafe to reorder past an intervening write.
 *
 * <h2>Reorder-safety contract (the only green-light path)</h2>
 *
 * <p>Any transformation that <em>reorders, duplicates, hoists, removes, or otherwise changes the evaluation timing</em>
 * of an expression (move-instance receiver, inline argument/receiver, change-signature removed argument, introduce
 * parameter/field default value) MUST gate that decision on {@link #isReorderSafe(TreePath, Trees, Types)} — the single
 * canonical green-light entry point. It returns {@code true} only for verdicts derived from real javac
 * {@link TreePath} symbol resolution.
 *
 * <p><b>Detached strings are a refusal-only fallback.</b> {@link #classify(String)} and
 * {@link #classify(ExpressionTree)} answer only the coarse {@link ExpressionPurity} question and resolve no symbols, so
 * a structurally-pure snippet such as {@code "customer.name"} is reported {@link ExpressionPurity#PURE} even though it
 * reads a possibly non-final field that is unsafe to reorder. <b>Their result must never be treated as a green-light to
 * reorder/duplicate/hoist.</b> When a caller holds only detached text it must call {@link #isReorderSafe(String)},
 * which always returns {@code false} (refuse), or obtain a {@link TreePath} and use the context API. There is, by
 * construction, no path from a string-only input to a reorder-safe verdict.
 */
public final class ExpressionPurityAnalyzer {

    /**
     * Result of a context-aware purity analysis.
     *
     * @param purity coarse side-effect classification of the expression itself
     * @param evaluationOrderSensitive true when the expression contains a side effect (assignment, increment,
     *     side-effecting argument) whose result or observable behavior depends on evaluation order, so the expression
     *     may not be freely reordered relative to other side effects
     * @param readsNonFinalState true when the expression reads a non-final field or a reassigned (not
     *     effectively-final) local/parameter, whose value can change between evaluations; such a read is pure but not
     *     stable under reordering
     */
    public record PurityAnalysis(ExpressionPurity purity, boolean evaluationOrderSensitive, boolean readsNonFinalState) {

        /** Conservative fallback used when no resolved context is available. */
        public static final PurityAnalysis UNKNOWN = new PurityAnalysis(ExpressionPurity.UNKNOWN, true, true);

        public boolean isPure() {
            return purity == ExpressionPurity.PURE;
        }

        /**
         * True when the expression is free of side effects, reads only stable (final / effectively-final) state, and
         * carries no evaluation-order sensitivity — i.e. it can be safely hoisted, duplicated, or reordered.
         */
        public boolean isReorderSafe() {
            return (purity == ExpressionPurity.PURE || purity == ExpressionPurity.ALLOCATION_ONLY)
                    && !evaluationOrderSensitive
                    && !readsNonFinalState;
        }
    }

    public ExpressionPurity classify(String expression) {
        String normalized = expression == null ? "" : expression.trim();
        if (normalized.isEmpty()) {
            return ExpressionPurity.UNKNOWN;
        }
        return parseExpression(normalized).map(this::classify).orElse(ExpressionPurity.UNKNOWN);
    }

    public ExpressionPurity classify(ExpressionTree expression) {
        if (expression == null) {
            return ExpressionPurity.UNKNOWN;
        }
        return new PurityScanner().scan(expression, null);
    }

    /**
     * Context-aware classification of the expression at {@code expressionPath}, resolving symbols against
     * {@code trees}/{@code types}. Returns only the coarse purity; prefer {@link #analyze(TreePath, Trees, Types)}
     * when reordering safety matters.
     */
    public ExpressionPurity classify(TreePath expressionPath, Trees trees, Types types) {
        return analyze(expressionPath, trees, types).purity();
    }

    /**
     * Context-aware analysis of the expression at {@code expressionPath}. The path must point at an
     * {@link ExpressionTree} within an analyzed compilation unit; {@code trees}/{@code types} come from the task that
     * produced it. When {@code trees} is {@code null} the analysis degrades to the structure-only classifier and
     * reports {@link PurityAnalysis#UNKNOWN}-style sensitivity conservatively.
     */
    public PurityAnalysis analyze(TreePath expressionPath, Trees trees, Types types) {
        if (expressionPath == null || !(expressionPath.getLeaf() instanceof ExpressionTree)) {
            return PurityAnalysis.UNKNOWN;
        }
        if (trees == null) {
            ExpressionPurity purity = classify((ExpressionTree) expressionPath.getLeaf());
            boolean side = purity == ExpressionPurity.SIDE_EFFECTING;
            return new PurityAnalysis(purity, side, true);
        }
        Set<Element> reassigned = reassignedVariables(expressionPath, trees);
        ContextPurityScanner scanner = new ContextPurityScanner(trees, types, reassigned);
        ExpressionPurity purity = scanner.scan(expressionPath, null);
        if (purity == null) {
            purity = ExpressionPurity.PURE;
        }
        return new PurityAnalysis(purity, scanner.evaluationOrderSensitive, scanner.readsNonFinalState);
    }

    /**
     * Canonical green-light for soundness-critical transformations that reorder, duplicate, hoist, or remove an
     * expression. Returns {@code true} only when the expression at {@code expressionPath} is proven side-effect-free,
     * reads exclusively stable (final / effectively-final) state, and carries no evaluation-order sensitivity — a
     * verdict that is derived entirely from real javac {@link TreePath} symbol resolution.
     *
     * <p>Without a resolved context ({@code trees == null}) no such proof is possible, so this method refuses by
     * returning {@code false}. It never green-lights from structure alone.
     *
     * @param expressionPath path to the {@link ExpressionTree} under consideration (argument, receiver, selected
     *     expression, default value, or hoisted/duplicated expression)
     * @param trees the {@link Trees} of the analyzed task that produced {@code expressionPath}; {@code null} forces a
     *     conservative refusal
     * @param types the {@link Types} of the same task
     * @return {@code true} only when reordering/duplicating/hoisting is provably safe
     */
    public boolean isReorderSafe(TreePath expressionPath, Trees trees, Types types) {
        if (trees == null) {
            return false;
        }
        return analyze(expressionPath, trees, types).isReorderSafe();
    }

    /**
     * Refusal fallback for callers that hold only detached expression text. A detached string carries no resolvable
     * symbols, so its reorder safety can never be proven; this method therefore <em>always</em> returns {@code false}.
     * It exists so that the absence of a {@link TreePath} produces an explicit refusal at the call site rather than a
     * silent misuse of {@link #classify(String)} as a green-light.
     *
     * @param expression detached expression text (ignored beyond documenting intent)
     * @return {@code false}, always
     */
    @SuppressWarnings("unused")
    public boolean isReorderSafe(String expression) {
        return false;
    }

    /**
     * Strict, proven-safe classifier for a <em>detached</em> default-argument expression (change-signature added
     * parameters): the only context in which an expression must be duplicated verbatim at every call site without any
     * resolvable {@link TreePath}. Because no symbols can be resolved, this admits ONLY constructs whose
     * reorder/duplication safety is decidable from syntax alone:
     *
     * <ul>
     *   <li>syntactically literal compile-time constants ({@code 0}, {@code 1L}, {@code 3.14}, {@code true},
     *       {@code 'c'}, {@code "s"}, {@code null}) and constant expressions built from them with parentheses, the
     *       unary {@code + - ~ !} operators, the binary arithmetic/logical/relational/shift operators, primitive casts,
     *       and the conditional operator;</li>
     *   <li>class literals ({@code Foo.class}, {@code java.util.List.class});</li>
     *   <li>enum constants / static constants accessed through a type name ({@code Currency.USD},
     *       {@code java.math.RoundingMode.HALF_UP}, {@code Math.PI}) — recognized strictly by requiring a type-name
     *       qualifier and a constant-style member name.</li>
     * </ul>
     *
     * <p>Everything else — method invocations ({@code System.nanoTime()}, {@code UUID.randomUUID()}), object/array
     * allocation, instance field/member access through a value receiver ({@code customer.name}), bare identifiers,
     * lambdas, assignments — is refused, because its purity cannot be proven from detached text. There is deliberately
     * no opt-in: a detached default has no {@link TreePath}, so {@link #isReorderSafe(TreePath, Trees, Types)} can never
     * green-light it, and an {@code allow_side_effects} flag must not bypass this contract.
     *
     * @param expression detached default-argument text
     * @return {@code true} only when the expression is a provably reorder/duplication-safe compile-time constant,
     *     class literal, or type-qualified enum/static constant
     */
    public boolean provesDetachedDefaultSafe(String expression) {
        String normalized = expression == null ? "" : expression.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return parseExpression(normalized).map(ExpressionPurityAnalyzer::isProvableConstantExpression).orElse(false);
    }

    /** Recursive syntactic proof that {@code tree} is a reorder/duplication-safe constant (see provesDetachedDefaultSafe). */
    private static boolean isProvableConstantExpression(ExpressionTree tree) {
        if (tree == null) {
            return false;
        }
        switch (tree.getKind()) {
            case INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL,
                    BOOLEAN_LITERAL, CHAR_LITERAL, STRING_LITERAL, NULL_LITERAL:
                return true;
            case PARENTHESIZED:
                return isProvableConstantExpression(((ParenthesizedTree) tree).getExpression());
            case UNARY_PLUS, UNARY_MINUS, BITWISE_COMPLEMENT, LOGICAL_COMPLEMENT:
                return isProvableConstantExpression(((UnaryTree) tree).getExpression());
            case TYPE_CAST:
                return isProvableConstantExpression(((TypeCastTree) tree).getExpression());
            case CONDITIONAL_EXPRESSION: {
                ConditionalExpressionTree conditional = (ConditionalExpressionTree) tree;
                return isProvableConstantExpression(conditional.getCondition())
                        && isProvableConstantExpression(conditional.getTrueExpression())
                        && isProvableConstantExpression(conditional.getFalseExpression());
            }
            case PLUS, MINUS, MULTIPLY, DIVIDE, REMAINDER, AND, OR, XOR,
                    CONDITIONAL_AND, CONDITIONAL_OR, EQUAL_TO, NOT_EQUAL_TO,
                    LESS_THAN, GREATER_THAN, LESS_THAN_EQUAL, GREATER_THAN_EQUAL,
                    LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT: {
                BinaryTree binary = (BinaryTree) tree;
                return isProvableConstantExpression(binary.getLeftOperand())
                        && isProvableConstantExpression(binary.getRightOperand());
            }
            case MEMBER_SELECT: {
                MemberSelectTree select = (MemberSelectTree) tree;
                String member = select.getIdentifier().toString();
                // Class literal: Foo.class / java.util.List.class.
                if (member.equals("class")) {
                    return isTypeReference(select.getExpression());
                }
                // Enum constant / static constant: TypeName.CONSTANT. The qualifier must denote a type (not a value
                // receiver) and the member must follow constant naming, so customer.name is refused while Currency.USD
                // is admitted. Reading a static constant is reorder- and duplication-safe at every call site.
                return isTypeReference(select.getExpression()) && isConstantMemberName(member);
            }
            default:
                return false;
        }
    }

    /** True when {@code expr} is a dotted type-name reference (an identifier or package/type chain ending in a type). */
    private static boolean isTypeReference(ExpressionTree expr) {
        if (expr.getKind() == Tree.Kind.IDENTIFIER) {
            return startsUpperCase(((IdentifierTree) expr).getName().toString());
        }
        if (expr.getKind() == Tree.Kind.MEMBER_SELECT) {
            MemberSelectTree select = (MemberSelectTree) expr;
            return startsUpperCase(select.getIdentifier().toString()) && isPackageOrTypeChain(select.getExpression());
        }
        return false;
    }

    /** True when {@code expr} is a dotted chain of identifiers (package segments and/or nested type names). */
    private static boolean isPackageOrTypeChain(ExpressionTree expr) {
        if (expr.getKind() == Tree.Kind.IDENTIFIER) {
            return true;
        }
        if (expr.getKind() == Tree.Kind.MEMBER_SELECT) {
            return isPackageOrTypeChain(((MemberSelectTree) expr).getExpression());
        }
        return false;
    }

    /** Constant-style member name: an enum constant or static constant begins with an uppercase letter. */
    private static boolean isConstantMemberName(String name) {
        return startsUpperCase(name);
    }

    private static boolean startsUpperCase(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    /**
     * Self-contained driver: compiles {@code classSource} in memory, analyzes the single {@code return <expr>;} in the
     * method named {@code methodName}, and classifies that expression with full symbol resolution. Returns
     * {@link PurityAnalysis#UNKNOWN} when the source fails to parse/analyze or the return expression is not unique.
     * Intended for self-contained callers and unit tests that do not already hold an analyzed task.
     */
    public PurityAnalysis analyzeReturnExpression(String classSource, String methodName) {
        if (classSource == null || methodName == null) {
            return PurityAnalysis.UNKNOWN;
        }
        JavaFileObject source = new StringJavaFileObject("__SerenaPurityCtx", classSource);
        try {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                    .getTask(null, null, null, List.of("-proc:none"), null, List.of(source));
            Iterable<? extends com.sun.source.tree.CompilationUnitTree> units = task.parse();
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
            if (returns.size() == 1) {
                return analyze(returns.get(0), trees, types);
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // Conservatively report unknown for unparseable/ambiguous sources.
        }
        return PurityAnalysis.UNKNOWN;
    }

    private static java.util.Optional<ExpressionTree> parseExpression(String expression) {
        JavaFileObject source = new StringJavaFileObject(
                "__SerenaPurity",
                "class __SerenaPurity { Object __serena() { return " + expression + "; } }");
        try {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                    .getTask(null, null, null, List.of("-proc:none"), null, List.of(source));
            List<ExpressionTree> expressions = new ArrayList<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitReturn(ReturnTree node, Void unused) {
                    expressions.add(node.getExpression());
                    return null;
                }
            }.scan(task.parse(), null);
            if (expressions.size() == 1) {
                return java.util.Optional.ofNullable(expressions.get(0));
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // Invalid or context-dependent snippets are intentionally refused conservatively.
        }
        return java.util.Optional.empty();
    }

    private static ExpressionPurity combine(ExpressionPurity left, ExpressionPurity right) {
        if (left == ExpressionPurity.SIDE_EFFECTING || right == ExpressionPurity.SIDE_EFFECTING) {
            return ExpressionPurity.SIDE_EFFECTING;
        }
        if (left == ExpressionPurity.UNKNOWN || right == ExpressionPurity.UNKNOWN) {
            return ExpressionPurity.UNKNOWN;
        }
        if (left == ExpressionPurity.ALLOCATION_ONLY || right == ExpressionPurity.ALLOCATION_ONLY) {
            return ExpressionPurity.ALLOCATION_ONLY;
        }
        return ExpressionPurity.PURE;
    }

    private static ExpressionPurity allocationFromArguments(List<? extends ExpressionTree> arguments) {
        ExpressionPurity argumentsPurity = ExpressionPurity.PURE;
        PurityScanner scanner = new PurityScanner();
        for (ExpressionTree argument : arguments) {
            argumentsPurity = combine(argumentsPurity, scanner.scan(argument, null));
        }
        if (argumentsPurity == ExpressionPurity.SIDE_EFFECTING || argumentsPurity == ExpressionPurity.UNKNOWN) {
            return argumentsPurity;
        }
        return ExpressionPurity.ALLOCATION_ONLY;
    }

    /**
     * Collects the {@link VariableElement}s that are reassigned (assignment target, compound assignment, or
     * increment/decrement) anywhere in the method enclosing {@code expressionPath}. A local or parameter whose element
     * is absent from this set is effectively final; an explicitly {@code final} declaration is final regardless. This
     * derives the effectively-final fact from the compiler's resolved symbols + the enclosing method's writes rather
     * than from snippet text.
     */
    private static Set<Element> reassignedVariables(TreePath expressionPath, Trees trees) {
        Set<Element> written = new HashSet<>();
        if (trees == null) {
            return written;
        }
        TreePath methodPath = null;
        for (TreePath current = expressionPath; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof MethodTree) {
                methodPath = current;
                break;
            }
        }
        if (methodPath == null) {
            return written;
        }
        new TreePathScanner<Void, Void>() {
            private void record(Tree target) {
                Element element = trees.getElement(new TreePath(getCurrentPath(), target));
                if (element instanceof VariableElement variable) {
                    written.add(variable);
                }
            }

            @Override
            public Void visitAssignment(AssignmentTree node, Void unused) {
                record(node.getVariable());
                return super.visitAssignment(node, unused);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                record(node.getVariable());
                return super.visitCompoundAssignment(node, unused);
            }

            @Override
            public Void visitUnary(UnaryTree node, Void unused) {
                switch (node.getKind()) {
                    case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> record(node.getExpression());
                    default -> { }
                }
                return super.visitUnary(node, unused);
            }
        }.scan(methodPath, null);
        return written;
    }

    /** Structure-only classifier: no symbol resolution, used for detached snippets. */
    private static final class PurityScanner extends TreeScanner<ExpressionPurity, Void> {
        @Override
        public ExpressionPurity scan(Tree tree, Void unused) {
            if (tree == null) {
                return ExpressionPurity.PURE;
            }
            ExpressionPurity result = super.scan(tree, unused);
            return result == null ? ExpressionPurity.PURE : result;
        }

        @Override
        public ExpressionPurity reduce(ExpressionPurity left, ExpressionPurity right) {
            return combine(left == null ? ExpressionPurity.PURE : left, right == null ? ExpressionPurity.PURE : right);
        }

        @Override
        public ExpressionPurity visitAssignment(AssignmentTree node, Void unused) {
            return ExpressionPurity.SIDE_EFFECTING;
        }

        @Override
        public ExpressionPurity visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
            return ExpressionPurity.SIDE_EFFECTING;
        }

        @Override
        public ExpressionPurity visitUnary(UnaryTree node, Void unused) {
            return switch (node.getKind()) {
                case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> ExpressionPurity.SIDE_EFFECTING;
                default -> scan(node.getExpression(), unused);
            };
        }

        @Override
        public ExpressionPurity visitMethodInvocation(MethodInvocationTree node, Void unused) {
            return ExpressionPurity.UNKNOWN;
        }

        @Override
        public ExpressionPurity visitNewClass(NewClassTree node, Void unused) {
            if (node.getClassBody() != null) {
                return ExpressionPurity.UNKNOWN;
            }
            return allocationFromArguments(node.getArguments());
        }

        @Override
        public ExpressionPurity visitNewArray(NewArrayTree node, Void unused) {
            ExpressionPurity dimensions = ExpressionPurity.PURE;
            for (ExpressionTree dimension : node.getDimensions()) {
                dimensions = combine(dimensions, scan(dimension, unused));
            }
            ExpressionPurity initializers = ExpressionPurity.PURE;
            if (node.getInitializers() != null) {
                for (ExpressionTree initializer : node.getInitializers()) {
                    initializers = combine(initializers, scan(initializer, unused));
                }
            }
            ExpressionPurity contents = combine(dimensions, initializers);
            if (contents == ExpressionPurity.SIDE_EFFECTING || contents == ExpressionPurity.UNKNOWN) {
                return contents;
            }
            return ExpressionPurity.ALLOCATION_ONLY;
        }

        @Override
        public ExpressionPurity visitLambdaExpression(LambdaExpressionTree node, Void unused) {
            return ExpressionPurity.ALLOCATION_ONLY;
        }

        @Override
        public ExpressionPurity visitConditionalExpression(ConditionalExpressionTree node, Void unused) {
            return combine(scan(node.getCondition(), unused), combine(scan(node.getTrueExpression(), unused), scan(node.getFalseExpression(), unused)));
        }

        @Override
        public ExpressionPurity visitParenthesized(ParenthesizedTree node, Void unused) {
            return scan(node.getExpression(), unused);
        }

        @Override
        public ExpressionPurity visitTypeCast(TypeCastTree node, Void unused) {
            return scan(node.getExpression(), unused);
        }

        @Override
        public ExpressionPurity visitInstanceOf(InstanceOfTree node, Void unused) {
            return scan(node.getExpression(), unused);
        }
    }

    /**
     * Symbol-resolved classifier. Reads are classified by the finality of their resolved {@link VariableElement};
     * method references are classified by inspecting the referenced target's body; evaluation-order sensitivity is
     * tracked as side effects are encountered.
     */
    private final class ContextPurityScanner extends TreePathScanner<ExpressionPurity, Void> {
        private final Trees trees;
        private final Types types;
        private final Set<Element> reassigned;
        private boolean evaluationOrderSensitive;
        private boolean readsNonFinalState;

        private ContextPurityScanner(Trees trees, Types types, Set<Element> reassigned) {
            this.trees = trees;
            this.types = types;
            this.reassigned = reassigned;
        }

        @Override
        public ExpressionPurity scan(Tree tree, Void unused) {
            if (tree == null) {
                return ExpressionPurity.PURE;
            }
            ExpressionPurity result = super.scan(tree, unused);
            return result == null ? ExpressionPurity.PURE : result;
        }

        @Override
        public ExpressionPurity reduce(ExpressionPurity left, ExpressionPurity right) {
            return combine(left == null ? ExpressionPurity.PURE : left, right == null ? ExpressionPurity.PURE : right);
        }

        @Override
        public ExpressionPurity visitIdentifier(IdentifierTree node, Void unused) {
            classifyRead(trees.getElement(getCurrentPath()));
            return ExpressionPurity.PURE;
        }

        @Override
        public ExpressionPurity visitMemberSelect(MemberSelectTree node, Void unused) {
            classifyRead(trees.getElement(getCurrentPath()));
            return scan(node.getExpression(), unused);
        }

        @Override
        public ExpressionPurity visitAssignment(AssignmentTree node, Void unused) {
            scan(node.getExpression(), unused);
            evaluationOrderSensitive = true;
            return ExpressionPurity.SIDE_EFFECTING;
        }

        @Override
        public ExpressionPurity visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
            scan(node.getExpression(), unused);
            evaluationOrderSensitive = true;
            return ExpressionPurity.SIDE_EFFECTING;
        }

        @Override
        public ExpressionPurity visitUnary(UnaryTree node, Void unused) {
            return switch (node.getKind()) {
                case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> {
                    evaluationOrderSensitive = true;
                    yield ExpressionPurity.SIDE_EFFECTING;
                }
                default -> scan(node.getExpression(), unused);
            };
        }

        @Override
        public ExpressionPurity visitMethodInvocation(MethodInvocationTree node, Void unused) {
            // Scan the receiver and arguments so nested side effects propagate to evaluation-order sensitivity, then
            // report the call itself as UNKNOWN: without an effects model we cannot prove a call is side-effect-free.
            scan(node.getMethodSelect(), unused);
            for (ExpressionTree argument : node.getArguments()) {
                ExpressionPurity argumentPurity = scan(argument, unused);
                if (argumentPurity == ExpressionPurity.SIDE_EFFECTING) {
                    evaluationOrderSensitive = true;
                }
            }
            return ExpressionPurity.UNKNOWN;
        }

        @Override
        public ExpressionPurity visitMemberReference(MemberReferenceTree node, Void unused) {
            // A method reference is an allocation of a functional instance; its safety depends on whether the
            // referenced target is side-effect-free. The qualifier is still scanned for nested side effects.
            ExpressionPurity qualifier = scan(node.getQualifierExpression(), unused);
            ExpressionPurity target = classifyMethodReferenceTarget(getCurrentPath());
            return combine(qualifier, target);
        }

        @Override
        public ExpressionPurity visitNewClass(NewClassTree node, Void unused) {
            if (node.getClassBody() != null) {
                return ExpressionPurity.UNKNOWN;
            }
            ExpressionPurity argumentsPurity = ExpressionPurity.PURE;
            for (ExpressionTree argument : node.getArguments()) {
                ExpressionPurity argumentPurity = scan(argument, unused);
                if (argumentPurity == ExpressionPurity.SIDE_EFFECTING) {
                    evaluationOrderSensitive = true;
                }
                argumentsPurity = combine(argumentsPurity, argumentPurity);
            }
            if (argumentsPurity == ExpressionPurity.SIDE_EFFECTING || argumentsPurity == ExpressionPurity.UNKNOWN) {
                return argumentsPurity;
            }
            return ExpressionPurity.ALLOCATION_ONLY;
        }

        @Override
        public ExpressionPurity visitNewArray(NewArrayTree node, Void unused) {
            ExpressionPurity contents = ExpressionPurity.PURE;
            for (ExpressionTree dimension : node.getDimensions()) {
                contents = combine(contents, scan(dimension, unused));
            }
            if (node.getInitializers() != null) {
                for (ExpressionTree initializer : node.getInitializers()) {
                    contents = combine(contents, scan(initializer, unused));
                }
            }
            if (contents == ExpressionPurity.SIDE_EFFECTING || contents == ExpressionPurity.UNKNOWN) {
                return contents;
            }
            return ExpressionPurity.ALLOCATION_ONLY;
        }

        @Override
        public ExpressionPurity visitLambdaExpression(LambdaExpressionTree node, Void unused) {
            return ExpressionPurity.ALLOCATION_ONLY;
        }

        @Override
        public ExpressionPurity visitConditionalExpression(ConditionalExpressionTree node, Void unused) {
            return combine(scan(node.getCondition(), unused), combine(scan(node.getTrueExpression(), unused), scan(node.getFalseExpression(), unused)));
        }

        @Override
        public ExpressionPurity visitParenthesized(ParenthesizedTree node, Void unused) {
            return scan(node.getExpression(), unused);
        }

        @Override
        public ExpressionPurity visitTypeCast(TypeCastTree node, Void unused) {
            return scan(node.getExpression(), unused);
        }

        @Override
        public ExpressionPurity visitInstanceOf(InstanceOfTree node, Void unused) {
            return scan(node.getExpression(), unused);
        }

        /**
         * Records evaluation-order risk for a read. A resolved non-stable {@link VariableElement} (non-final field or
         * reassigned local/parameter) flags mutable-state. A {@code null} element means the read could not be resolved
         * against the analyzed task; since stability cannot then be proven, it is treated conservatively as a
         * mutable-state read so an unresolved read can never be green-lit. Non-variable elements (types, packages,
         * methods) are not reads of state and are ignored.
         */
        private void classifyRead(Element element) {
            if (element == null) {
                readsNonFinalState = true;
                return;
            }
            if (!(element instanceof VariableElement variable)) {
                return;
            }
            if (!isStableRead(variable)) {
                readsNonFinalState = true;
            }
        }

        /**
         * A read is stable (safe to reorder) when the variable is explicitly {@code final}, or is a local/parameter
         * that is never reassigned in the enclosing method (effectively final). Non-final fields and reassigned locals
         * are unstable because an intervening write could change the observed value.
         */
        private boolean isStableRead(VariableElement variable) {
            if (variable.getModifiers().contains(Modifier.FINAL)) {
                return true;
            }
            ElementKind kind = variable.getKind();
            if (kind == ElementKind.LOCAL_VARIABLE
                    || kind == ElementKind.PARAMETER
                    || kind == ElementKind.EXCEPTION_PARAMETER
                    || kind == ElementKind.RESOURCE_VARIABLE
                    || kind == ElementKind.BINDING_VARIABLE) {
                return !reassigned.contains(variable);
            }
            // Fields, enum constants, and anything else conservatively treated as mutable shared state.
            return false;
        }

        /**
         * Classifies the target of a method reference: a constructor reference is an allocation; a referenced method
         * whose resolvable body writes state, throws, synchronizes, or calls out is side-effecting; a clean body is
         * allocation-only; an unresolvable (e.g. library) target is unknown.
         */
        private ExpressionPurity classifyMethodReferenceTarget(TreePath referencePath) {
            Element element = trees.getElement(referencePath);
            if (!(element instanceof ExecutableElement executable)) {
                return ExpressionPurity.UNKNOWN;
            }
            if (executable.getKind() == ElementKind.CONSTRUCTOR) {
                return ExpressionPurity.ALLOCATION_ONLY;
            }
            TreePath targetPath = trees.getPath(executable);
            if (targetPath == null) {
                return ExpressionPurity.UNKNOWN;
            }
            MethodBodyModel target = MethodBodyModel.fromMethod(targetPath, trees, types);
            if (target.statements().isEmpty() && target.methodKey() == null) {
                return ExpressionPurity.UNKNOWN;
            }
            boolean sideEffecting = !target.elementWrites().isEmpty()
                    || !target.writes().isEmpty()
                    || target.hasThrow()
                    || target.usesSynchronized()
                    || !target.calls().isEmpty();
            return sideEffecting ? ExpressionPurity.SIDE_EFFECTING : ExpressionPurity.ALLOCATION_ONLY;
        }
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String source;

        private StringJavaFileObject(String className, String source) {
            super(URI.create("string:///" + className + JavaFileObject.Kind.SOURCE.extension), JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
