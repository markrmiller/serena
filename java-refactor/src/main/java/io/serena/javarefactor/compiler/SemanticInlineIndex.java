package io.serena.javarefactor.compiler;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.serena.javarefactor.compiler.SemanticIndex.InitializerInfo;
import io.serena.javarefactor.compiler.SemanticIndex.UsageReplacement;
final class SemanticInlineIndex {
    private final SemanticIndex index;
    private final Trees trees;
    private final SourcePositions positions;
    private final Map<Path, CharSequence> sourceByPath;
    private final List<CompilationUnitTree> units;
    private final IdentifierSpanFinder spanFinder;

    SemanticInlineIndex(SemanticIndex index) {
        this.index = index;
        this.trees = index.trees;
        this.positions = index.positions;
        this.sourceByPath = index.sourceByPath;
        this.units = index.units;
        this.spanFinder = index.spanFinder;
    }

    InitializerInfo initializerInfo(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null || !(path.getLeaf() instanceof VariableTree variable) || variable.getInitializer() == null) {
            return null;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        ExpressionTree initializer = variable.getInitializer();
        long start = positions.getStartPosition(unit, initializer);
        long end = positions.getEndPosition(unit, initializer);
        if (start < 0 || end < start) {
            return null;
        }
        CharSequence source = sourceByPath.get(SemanticIndex.pathOf(unit));
        if (source == null || end > source.length()) {
            return null;
        }
        return new InitializerInfo(source.subSequence((int) start, (int) end).toString(), (int) start, (int) end);
    }
    boolean isCompileTimeConstant(Element element) {
        return element instanceof VariableElement variable && variable.getConstantValue() != null;
    }
    Tree.Kind initializerKind(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null || !(path.getLeaf() instanceof VariableTree variable) || variable.getInitializer() == null) {
            return null;
        }
        return variable.getInitializer().getKind();
    }
    boolean isUsedInNestedScope(Element element) {
        TreePath declPath = trees.getPath(element);
        if (declPath == null) {
            return true;
        }
        TreePath methodPath = enclosingExecutablePath(declPath);
        if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree methodTree) || methodTree.getBody() == null) {
            return true;
        }
        boolean[] captured = {false};
        new TreePathScanner<Void, Void>() {
            private int nestingDepth;

            @Override
    public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                nestingDepth++;
                super.visitLambdaExpression(node, unused);
                nestingDepth--;
                return null;
            }

            @Override
    public Void visitClass(ClassTree node, Void unused) {
                nestingDepth++;
                super.visitClass(node, unused);
                nestingDepth--;
                return null;
            }

            @Override
    public Void visitIdentifier(IdentifierTree node, Void unused) {
                if (nestingDepth > 0) {
                    Element resolved = trees.getElement(getCurrentPath());
                    if (resolved != null && resolved.equals(element)) {
                        captured[0] = true;
                    }
                }
                return super.visitIdentifier(node, unused);
            }
        }.scan(new TreePath(methodPath, methodTree.getBody()), null);
        return captured[0];
    }

private static final Set<Tree.Kind> SAFE_NO_PAREN_INLINE_PARENTS = java.util.EnumSet.of(
            Tree.Kind.ASSIGNMENT, Tree.Kind.PLUS_ASSIGNMENT, Tree.Kind.MINUS_ASSIGNMENT, Tree.Kind.MULTIPLY_ASSIGNMENT,
            Tree.Kind.DIVIDE_ASSIGNMENT, Tree.Kind.REMAINDER_ASSIGNMENT, Tree.Kind.AND_ASSIGNMENT, Tree.Kind.OR_ASSIGNMENT,
            Tree.Kind.XOR_ASSIGNMENT, Tree.Kind.LEFT_SHIFT_ASSIGNMENT, Tree.Kind.RIGHT_SHIFT_ASSIGNMENT,
            Tree.Kind.UNSIGNED_RIGHT_SHIFT_ASSIGNMENT, Tree.Kind.VARIABLE, Tree.Kind.RETURN, Tree.Kind.EXPRESSION_STATEMENT,
            Tree.Kind.NEW_CLASS, Tree.Kind.NEW_ARRAY, Tree.Kind.PARENTHESIZED, Tree.Kind.LAMBDA_EXPRESSION,
            Tree.Kind.CONDITIONAL_EXPRESSION, Tree.Kind.IF, Tree.Kind.WHILE_LOOP, Tree.Kind.DO_WHILE_LOOP,
            Tree.Kind.FOR_LOOP, Tree.Kind.ENHANCED_FOR_LOOP, Tree.Kind.SYNCHRONIZED, Tree.Kind.THROW, Tree.Kind.ASSERT,
            Tree.Kind.YIELD, Tree.Kind.SWITCH, Tree.Kind.SWITCH_EXPRESSION, Tree.Kind.CASE, Tree.Kind.ARRAY_ACCESS,
            Tree.Kind.MEMBER_SELECT, Tree.Kind.METHOD_INVOCATION, Tree.Kind.TYPE_CAST, Tree.Kind.INSTANCE_OF,
            Tree.Kind.UNARY_PLUS, Tree.Kind.UNARY_MINUS, Tree.Kind.BITWISE_COMPLEMENT, Tree.Kind.LOGICAL_COMPLEMENT,
            Tree.Kind.PREFIX_INCREMENT, Tree.Kind.PREFIX_DECREMENT, Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.POSTFIX_DECREMENT);
    String firstUnsupportedInlineUsageContext(Element element) {
        TreePath declPath = trees.getPath(element);
        if (declPath == null || !(declPath.getLeaf() instanceof VariableTree)) {
            return null;
        }
        TreePath methodPath = enclosingExecutablePath(declPath);
        if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree methodTree) || methodTree.getBody() == null) {
            return null;
        }
        String[] unsupported = {null};
        new TreePathScanner<Void, Void>() {
            @Override
    public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element resolved = trees.getElement(getCurrentPath());
                if (unsupported[0] == null && resolved != null && resolved.equals(element)) {
                    TreePath parentPath = getCurrentPath().getParentPath();
                    Tree parent = parentPath == null ? null : parentPath.getLeaf();
                    if (parent != null && !isModelledInlineParent(parent)) {
                        unsupported[0] = parent.getKind().toString();
                    }
                }
                return super.visitIdentifier(node, unused);
            }
        }.scan(new TreePath(methodPath, methodTree.getBody()), null);
        return unsupported[0];
    }

private static boolean isModelledInlineParent(Tree parent) {
        return parent instanceof BinaryTree || SAFE_NO_PAREN_INLINE_PARENTS.contains(parent.getKind());
    }
    List<UsageReplacement> usageReplacements(Element element, String initializerText) {
        TreePath declPath = trees.getPath(element);
        if (declPath == null || !(declPath.getLeaf() instanceof VariableTree variable) || variable.getInitializer() == null) {
            return List.of();
        }
        TreePath methodPath = enclosingExecutablePath(declPath);
        if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree methodTree) || methodTree.getBody() == null) {
            return List.of();
        }
        Tree.Kind initializerKind = variable.getInitializer().getKind();
        CompilationUnitTree unit = declPath.getCompilationUnit();
        Path file = SemanticIndex.pathOf(unit);
        CharSequence source = sourceByPath.get(file.toAbsolutePath().normalize());
        if (source == null) {
            return List.of();
        }
        List<UsageReplacement> result = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
    public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element resolved = trees.getElement(getCurrentPath());
                if (resolved != null && resolved.equals(element)) {
                    IdentifierSpan span = spanFinder.find(file, unit, positions, node, resolved, source);
                    if (span != null) {
                        boolean parenthesize = needsParentheses(initializerKind, getCurrentPath());
                        String replacement = parenthesize ? "(" + initializerText + ")" : initializerText;
                        result.add(new UsageReplacement(span, replacement));
                    }
                }
                return super.visitIdentifier(node, unused);
            }
        }.scan(new TreePath(methodPath, methodTree.getBody()), null);
        return result;
    }

private static TreePath enclosingExecutablePath(TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof MethodTree) {
                return current;
            }
        }
        return null;
    }

private static boolean needsParentheses(Tree.Kind initializerKind, TreePath usagePath) {
        int initializerPrecedence = expressionPrecedence(initializerKind);
        if (initializerPrecedence >= PRECEDENCE_PRIMARY) {
            return false;
        }
        Tree usage = usagePath.getLeaf();
        Tree parent = usagePath.getParentPath() == null ? null : usagePath.getParentPath().getLeaf();
        if (parent == null) {
            return false;
        }
        switch (parent.getKind()) {
            case MEMBER_SELECT -> {
                // `x.foo` / `x.field`: a non-primary initializer used as the receiver must be parenthesized.
                return ((MemberSelectTree) parent).getExpression() == usage;
            }
            case ARRAY_ACCESS -> {
                // The array expression requires a primary; the index is a full expression and never needs parens.
                return ((ArrayAccessTree) parent).getExpression() == usage;
            }
            case METHOD_INVOCATION -> {
                // The usage can only be the method-select (e.g. `x.run()` would surface as MEMBER_SELECT above); an
                // argument is a full expression slot needing no parens.
                return ((MethodInvocationTree) parent).getMethodSelect() == usage;
            }
            case UNARY_PLUS, UNARY_MINUS, BITWISE_COMPLEMENT, LOGICAL_COMPLEMENT, PREFIX_INCREMENT, PREFIX_DECREMENT,
                    POSTFIX_INCREMENT, POSTFIX_DECREMENT -> {
                return true;
            }
            case TYPE_CAST -> {
                return ((TypeCastTree) parent).getExpression() == usage;
            }
            default -> {
                int required = parentPositionPrecedence(usage, usagePath.getParentPath());
                if (required < 0) {
                    return false;
                }
                if (initializerPrecedence < required) {
                    return true;
                }
                // Equal precedence: parenthesize only on the associativity-conflicting side of a left-associative
                // binary operator (the right operand), e.g. `y - (a + b)`.
                if (initializerPrecedence == required && parent instanceof BinaryTree binary) {
                    return binary.getRightOperand() == usage;
                }
                return false;
            }
        }
    }

private static int parentPositionPrecedence(Tree usage, TreePath parentPath) {
        Tree parent = parentPath.getLeaf();
        if (parent instanceof BinaryTree binary) {
            return operatorPrecedence(binary.getKind());
        }
        if (parent instanceof InstanceOfTree) {
            // `x instanceof Foo`: the expression operand binds like a relational operand.
            return PRECEDENCE_RELATIONAL;
        }
        if (parent instanceof ConditionalExpressionTree ternary) {
            // Only the condition requires tighter-than-ternary binding; the branches are full expressions.
            return ternary.getCondition() == usage ? PRECEDENCE_TERNARY + 1 : -1;
        }
        return -1;
    }

private static final int PRECEDENCE_TERNARY = 3;

private static final int PRECEDENCE_RELATIONAL = 10;

private static final int PRECEDENCE_PRIMARY = 16;

private static int operatorPrecedence(Tree.Kind kind) {
        return switch (kind) {
            case MULTIPLY, DIVIDE, REMAINDER -> 13;
            case PLUS, MINUS -> 12;
            case LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> 11;
            case LESS_THAN, GREATER_THAN, LESS_THAN_EQUAL, GREATER_THAN_EQUAL -> PRECEDENCE_RELATIONAL;
            case EQUAL_TO, NOT_EQUAL_TO -> 9;
            case AND -> 8;
            case XOR -> 7;
            case OR -> 6;
            case CONDITIONAL_AND -> 5;
            case CONDITIONAL_OR -> 4;
            default -> PRECEDENCE_TERNARY;
        };
    }

private static int expressionPrecedence(Tree.Kind kind) {
        return switch (kind) {
            case IDENTIFIER, MEMBER_SELECT, METHOD_INVOCATION, ARRAY_ACCESS, PARENTHESIZED, NEW_CLASS, NEW_ARRAY,
                    INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL, BOOLEAN_LITERAL, CHAR_LITERAL,
                    STRING_LITERAL, NULL_LITERAL -> PRECEDENCE_PRIMARY;
            case POSTFIX_INCREMENT, POSTFIX_DECREMENT -> 15;
            case UNARY_PLUS, UNARY_MINUS, BITWISE_COMPLEMENT, LOGICAL_COMPLEMENT, PREFIX_INCREMENT, PREFIX_DECREMENT,
                    TYPE_CAST -> 14;
            case MULTIPLY, DIVIDE, REMAINDER -> 13;
            case PLUS, MINUS -> 12;
            case LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> 11;
            case LESS_THAN, GREATER_THAN, LESS_THAN_EQUAL, GREATER_THAN_EQUAL, INSTANCE_OF -> PRECEDENCE_RELATIONAL;
            case EQUAL_TO, NOT_EQUAL_TO -> 9;
            case AND -> 8;
            case XOR -> 7;
            case OR -> 6;
            case CONDITIONAL_AND -> 5;
            case CONDITIONAL_OR -> 4;
            case CONDITIONAL_EXPRESSION -> PRECEDENCE_TERNARY;
            // Assignments and anything not classified above bind loosest; parenthesize defensively.
            default -> 0;
        };
    }
    boolean initializerHasSideEffects(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null || !(path.getLeaf() instanceof VariableTree variable) || variable.getInitializer() == null) {
            return true;
        }
        return expressionHasObservableSideEffects(variable.getInitializer());
    }
    String initializerUnstableDependencyReason(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null || !(path.getLeaf() instanceof VariableTree variable) || variable.getInitializer() == null) {
            return "the initializer could not be analyzed";
        }
        List<String> reasons = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
    public Void visitArrayAccess(ArrayAccessTree node, Void unused) {
                reasons.add("it reads an array element ('" + node + "') whose value can change before a usage site");
                return super.visitArrayAccess(node, unused);
            }

            @Override
    public Void visitIdentifier(IdentifierTree node, Void unused) {
                String name = node.getName().toString();
                // `this`/`super` are stable references; the members read THROUGH them are checked at their own nodes.
                if (!name.equals("this") && !name.equals("super")) {
                    checkRead(name, trees.getElement(getCurrentPath()));
                }
                return super.visitIdentifier(node, unused);
            }

            @Override
    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                checkRead(node.getIdentifier().toString(), trees.getElement(getCurrentPath()));
                return super.visitMemberSelect(node, unused);
            }

            private void checkRead(String name, Element read) {
                if (read == null) {
                    reasons.add("it reads '" + name + "', which could not be resolved");
                    return;
                }
                switch (read.getKind()) {
                    case FIELD -> {
                        if (((VariableElement) read).getConstantValue() == null) {
                            reasons.add("it reads field '" + name + "', which is not a compile-time constant and can "
                                    + "change before a usage site");
                        }
                    }
                    case LOCAL_VARIABLE, PARAMETER, RESOURCE_VARIABLE, EXCEPTION_PARAMETER, BINDING_VARIABLE -> {
                        if (isReassigned(read)) {
                            reasons.add("it reads variable '" + name + "', which is reassigned and can change before "
                                    + "a usage site");
                        }
                    }
                    // Stable reads: enum constants never change; method/constructor references have fixed identity;
                    // type parameters are not runtime values.
                    case ENUM_CONSTANT, METHOD, CONSTRUCTOR, PACKAGE, MODULE, TYPE_PARAMETER -> {
                    }
                    default -> {
                        // Type names appearing as qualifiers (Integer.MAX_VALUE) are not value reads.
                        if (!read.getKind().isClass() && !read.getKind().isInterface()) {
                            reasons.add("it reads '" + name + "' (" + read.getKind()
                                    + "), whose stability cannot be proven");
                        }
                    }
                }
            }
        }.scan(new TreePath(path, variable.getInitializer()), null);
        return reasons.isEmpty() ? null : reasons.get(0);
    }
    boolean safeDeleteInitializerHasSideEffects(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null || !(path.getLeaf() instanceof VariableTree variable)) {
            return true;
        }
        if (variable.getInitializer() == null) {
            return false;
        }
        return expressionHasObservableSideEffects(variable.getInitializer());
    }

static boolean expressionHasObservableSideEffects(ExpressionTree expression) {
        boolean[] impure = {false};
        new TreeScanner<Void, Void>() {
            @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                impure[0] = true;
                return null;
            }

            @Override
    public Void visitNewClass(NewClassTree node, Void unused) {
                impure[0] = true;
                return null;
            }

            @Override
    public Void visitNewArray(NewArrayTree node, Void unused) {
                impure[0] = true;
                return null;
            }

            @Override
    public Void visitAssignment(AssignmentTree node, Void unused) {
                impure[0] = true;
                return null;
            }

            @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                impure[0] = true;
                return null;
            }

            @Override
    public Void visitUnary(UnaryTree node, Void unused) {
                switch (node.getKind()) {
                    case PREFIX_INCREMENT, POSTFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_DECREMENT -> impure[0] = true;
                    default -> {
                    }
                }
                return super.visitUnary(node, unused);
            }
        }.scan(expression, null);
        return impure[0];
    }
    boolean isReassigned(Element element) {
        ReassignmentScanner scanner = new ReassignmentScanner(element);
        for (CompilationUnitTree unit : units) {
            scanner.scan(unit, null);
        }
        return scanner.reassigned;
    }

private final class ReassignmentScanner extends TreePathScanner<Void, Void> {
        private final Element target;
        private boolean reassigned;

        private ReassignmentScanner(Element target) {
            this.target = target;
        }

        @Override
        public Void visitAssignment(AssignmentTree node, Void unused) {
            if (matches(node.getVariable())) {
                reassigned = true;
            }
            return super.visitAssignment(node, unused);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
            if (matches(node.getVariable())) {
                reassigned = true;
            }
            return super.visitCompoundAssignment(node, unused);
        }

        @Override
        public Void visitUnary(UnaryTree node, Void unused) {
            switch (node.getKind()) {
                case PREFIX_INCREMENT, POSTFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_DECREMENT -> {
                    if (matches(node.getExpression())) {
                        reassigned = true;
                    }
                }
                default -> {
                }
            }
            return super.visitUnary(node, unused);
        }

        private boolean matches(Tree expression) {
            Element element = trees.getElement(new TreePath(getCurrentPath(), expression));
            return element != null && element.equals(target);
        }
    }

}
