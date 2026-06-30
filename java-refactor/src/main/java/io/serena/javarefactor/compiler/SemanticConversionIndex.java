package io.serena.javarefactor.compiler;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler-backed analysis for the two V3 conversion refactorings (refactor-feature-plan-V3.md §12 convert anonymous
 * class to lambda, §13 convert lambda to method reference). Like {@link SemanticInlineIndex} this is a thin façade over
 * a {@link SemanticIndex}'s javac model ({@code Trees}/{@code Types}/{@code Elements} and the parsed units); it locates
 * the node at a source position, decides whether the conversion is semantics-preserving against the spec's refusal
 * lists, and returns the single text replacement (or a refusal) as a {@link ConversionResult}.
 *
 * <p>The decision is deliberately conservative: every precondition the spec calls out is checked here, and any node
 * shape not explicitly handled is refused with {@code *_unsupported_shape}. The sidecar's before/after javac validation
 * is the final backstop — a replacement that would not compile or would change overload/receiver binding is rejected by
 * the planner's validation step regardless of what this analysis returned.
 */
public final class SemanticConversionIndex {

    private static final Set<String> OBJECT_METHOD_NAMES = Set.of("equals", "hashCode", "toString");

    private final SemanticIndex index;
    private final Trees trees;
    private final Types types;
    private final Elements elements;
    private final SourcePositions positions;
    private final List<CompilationUnitTree> units;

    public SemanticConversionIndex(SemanticIndex index) {
        this.index = index;
        this.trees = index.trees;
        this.types = index.types;
        this.elements = index.elements;
        this.positions = index.positions;
        this.units = index.units;
    }

    // ── §12 convert anonymous class to lambda ────────────────────────────────────────────────────────────────────

    /**
     * Analyzes the anonymous class at {@code (line, column)} (1-based; {@code column <= 0} selects the first anonymous
     * class starting on {@code line}) and returns the lambda replacement or a §12 refusal.
     */
    public ConversionResult anonymousToLambda(Path file, int line, int column) {
        CompilationUnitTree unit = unitFor(file);
        if (unit == null) {
            return ConversionResult.refuse("anon_not_found", "File is not in the Java project model: " + file);
        }
        TreePath nodePath = locateAnonymous(unit, file, line, column);
        if (nodePath == null) {
            return ConversionResult.refuse("anon_not_found",
                    "No anonymous class expression was found at " + line + ":" + column + ".");
        }
        NewClassTree node = (NewClassTree) nodePath.getLeaf();
        ClassTree body = node.getClassBody();

        Element anonElement = trees.getElement(new TreePath(nodePath, body));
        if (!(anonElement instanceof TypeElement anon)) {
            return ConversionResult.refuse("anon_not_found", "The anonymous class could not be resolved by javac.");
        }

        // The single supertype must be a functional interface (the anonymous class extends Object + implements exactly
        // one interface). Extending a class, or implementing zero/many interfaces, is not representable as a lambda.
        TypeMirror superclass = anon.getSuperclass();
        boolean extendsClass = superclass != null && superclass.getKind() == TypeKind.DECLARED
                && !((DeclaredType) superclass).asElement().toString().equals("java.lang.Object");
        if (extendsClass) {
            return ConversionResult.refuse("anon_extends_class",
                    "Anonymous class extends a class; only functional-interface anonymous classes convert to lambdas.");
        }
        List<? extends TypeMirror> interfaces = anon.getInterfaces();
        if (interfaces.size() != 1) {
            return ConversionResult.refuse("anon_not_functional_interface",
                    "Anonymous class must implement exactly one interface to convert to a lambda (found "
                            + interfaces.size() + ").");
        }
        TypeMirror target = interfaces.get(0);
        if (target.getKind() != TypeKind.DECLARED
                || !(((DeclaredType) target).asElement() instanceof TypeElement targetInterface)
                || targetInterface.getKind() != ElementKind.INTERFACE) {
            return ConversionResult.refuse("anon_not_functional_interface",
                    "The implemented supertype is not an interface.");
        }
        int abstractMethods = countAbstractMethods(targetInterface);
        if (abstractMethods != 1) {
            return ConversionResult.refuse(abstractMethods > 1 ? "anon_multiple_abstract_methods"
                    : "anon_not_functional_interface",
                    targetInterface.getQualifiedName() + " is not a functional interface (abstract methods: "
                            + abstractMethods + ").");
        }

        // The body must contain exactly the single abstract-method override and nothing else: no fields, no instance
        // initializers, no extra methods, and no override of Object's equals/hashCode/toString.
        MethodTree sam = null;
        for (Tree member : body.getMembers()) {
            if (member instanceof VariableTree) {
                return ConversionResult.refuse("anon_declares_field",
                        "Anonymous class declares a field; lambdas cannot hold state.");
            }
            if (member instanceof BlockTree) {
                return ConversionResult.refuse("anon_has_instance_initializer",
                        "Anonymous class has an instance initializer; not representable as a lambda.");
            }
            if (member instanceof MethodTree method) {
                if (method.getName().contentEquals("<init>")) {
                    continue; // synthetic/implicit constructor of the anonymous class
                }
                if (sam != null) {
                    return ConversionResult.refuse("anon_declares_extra_method",
                            "Anonymous class declares more than one method; not representable as a lambda.");
                }
                sam = method;
            }
        }
        if (sam == null || sam.getBody() == null) {
            return ConversionResult.refuse("anon_not_functional_interface",
                    "Anonymous class does not implement a single abstract method with a body.");
        }
        if (OBJECT_METHOD_NAMES.contains(sam.getName().toString())) {
            return ConversionResult.refuse("anon_overrides_object_method",
                    "Anonymous class overrides Object#" + sam.getName() + "; not a functional-interface conversion.");
        }
        String thisOrSuper = usesThisOrSuper(sam.getBody(), nodePath, unit);
        if (thisOrSuper != null) {
            return ConversionResult.refuse(thisOrSuper,
                    "Anonymous method body uses '" + ("anon_uses_this".equals(thisOrSuper) ? "this" : "super")
                            + "', which changes meaning inside a lambda.");
        }

        // §12.3 step 6: omit parameter types when inferable; keep them if ambiguity exists. Dropping the explicit
        // parameter types of an implicitly-typed lambda can change (or fail) overload resolution when the converted
        // expression sits in an argument position of an overloaded method, so we retain the source types in that case.
        boolean retainParamTypes = !sam.getParameters().isEmpty()
                && lambdaTargetIsAmbiguous(nodePath, node);

        String lambda = buildLambda(sam, file, retainParamTypes);
        if (lambda == null) {
            return ConversionResult.refuse("anon_not_functional_interface",
                    "Could not render the anonymous method body as a lambda.");
        }
        int start = startOf(unit, node);
        int end = endOf(unit, node);
        if (start < 0 || end < start) {
            return ConversionResult.refuse("anon_not_found", "Could not locate the anonymous class source range.");
        }
        return ConversionResult.accept(start, end, lambda);
    }

    private int countAbstractMethods(TypeElement interfaceType) {
        int count = 0;
        for (Element member : elements.getAllMembers(interfaceType)) {
            if (!(member instanceof ExecutableElement method) || method.getKind() != ElementKind.METHOD) {
                continue;
            }
            Set<Modifier> modifiers = method.getModifiers();
            if (!modifiers.contains(Modifier.ABSTRACT) || modifiers.contains(Modifier.STATIC)
                    || modifiers.contains(Modifier.DEFAULT)) {
                continue;
            }
            if (OBJECT_METHOD_NAMES.contains(method.getSimpleName().toString())) {
                continue; // public Object methods redeclared as abstract do not count toward the SAM
            }
            count++;
        }
        return count;
    }

    private String buildLambda(MethodTree sam, Path file, boolean retainParamTypes) {
        List<String> params = new ArrayList<>();
        for (VariableTree parameter : sam.getParameters()) {
            if (retainParamTypes) {
                // Reuse the explicit type written in the anonymous-method override verbatim (e.g. "String s"). An
                // explicitly-typed lambda must always be parenthesized, even for a single parameter.
                String typeText = text(file, parameter.getType());
                if (typeText == null) {
                    return null;
                }
                params.add(typeText + " " + parameter.getName());
            } else {
                params.add(parameter.getName().toString());
            }
        }
        String paramText = (!retainParamTypes && params.size() == 1)
                ? params.get(0)
                : "(" + String.join(", ", params) + ")";

        BlockTree block = sam.getBody();
        List<? extends StatementTree> statements = block.getStatements();
        if (statements.size() == 1) {
            StatementTree only = statements.get(0);
            if (only instanceof ReturnTree returnTree && returnTree.getExpression() != null) {
                String expr = text(file, returnTree.getExpression());
                return expr == null ? null : paramText + " -> " + expr;
            }
            if (only instanceof ExpressionStatementTree statement) {
                String expr = text(file, statement.getExpression());
                return expr == null ? null : paramText + " -> " + expr;
            }
        }
        String blockText = text(file, block);
        return blockText == null ? null : paramText + " -> " + blockText;
    }

    /**
     * §12.3 step 6 ambiguity test: returns {@code true} when the anonymous expression {@code anon} sits in an argument
     * position of an <em>overloaded</em> method invocation. There, an implicitly-typed lambda (no parameter types) can
     * become applicable to several overloads — or to none — so the explicit parameter types must be retained to keep
     * overload resolution identical to the original anonymous-class argument.
     */
    private boolean lambdaTargetIsAmbiguous(TreePath anonPath, NewClassTree anon) {
        TreePath parentPath = anonPath.getParentPath();
        if (parentPath == null || !(parentPath.getLeaf() instanceof MethodInvocationTree invocation)) {
            return false;
        }
        boolean isArgument = false;
        for (ExpressionTree argument : invocation.getArguments()) {
            if (unwrap(argument) == anon) {
                isArgument = true;
                break;
            }
        }
        if (!isArgument) {
            return false;
        }
        Element invoked = trees.getElement(parentPath);
        if (!(invoked instanceof ExecutableElement method)) {
            // Could not resolve the overload; conservatively retain the types so the result keeps compiling.
            return true;
        }
        if (!(method.getEnclosingElement() instanceof TypeElement declaring)) {
            return true;
        }
        return countMethodsNamed(declaring, method.getSimpleName().toString()) > 1;
    }

    private int countMethodsNamed(TypeElement declaring, String name) {
        int count = 0;
        for (Element member : elements.getAllMembers(declaring)) {
            if (member instanceof ExecutableElement candidate
                    && candidate.getKind() == ElementKind.METHOD
                    && candidate.getSimpleName().contentEquals(name)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns {@code "anon_uses_this"}/{@code "anon_uses_super"} if the anonymous method body references {@code this} or
     * {@code super} in a way that would rebind under a lambda, else {@code null}. Nested type declarations rebind
     * {@code this}, so their bodies are skipped; nested lambdas do <em>not</em> rebind {@code this}, so they are scanned.
     */
    private String usesThisOrSuper(BlockTree body, TreePath anonPath, CompilationUnitTree unit) {
        String[] found = {null};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree node, Void unused) {
                return null; // a nested/local/anonymous class rebinds `this`; do not descend
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                flag(node.getName().toString());
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                flag(node.getIdentifier().toString());
                return super.visitMemberSelect(node, unused);
            }

            private void flag(String name) {
                if (found[0] != null) {
                    return;
                }
                if ("this".equals(name)) {
                    found[0] = "anon_uses_this";
                } else if ("super".equals(name)) {
                    found[0] = "anon_uses_super";
                }
            }
        }.scan(body, null);
        return found[0];
    }

    // ── §13 convert lambda to method reference ───────────────────────────────────────────────────────────────────

    /**
     * Analyzes the lambda at {@code (line, column)} (1-based; {@code column <= 0} selects the first lambda starting on
     * {@code line}) and returns the method-reference replacement or a §13 refusal.
     */
    public ConversionResult lambdaToMethodReference(Path file, int line, int column) {
        CompilationUnitTree unit = unitFor(file);
        if (unit == null) {
            return ConversionResult.refuse("lambda_not_found", "File is not in the Java project model: " + file);
        }
        TreePath nodePath = locateLambda(unit, file, line, column);
        if (nodePath == null) {
            return ConversionResult.refuse("lambda_not_found",
                    "No lambda expression was found at " + line + ":" + column + ".");
        }
        LambdaExpressionTree lambda = (LambdaExpressionTree) nodePath.getLeaf();

        ExpressionTree call = singleCallExpression(lambda);
        if (call == null) {
            return ConversionResult.refuse("lambda_not_single_call",
                    "Lambda body is not a single method or constructor invocation.");
        }

        List<String> paramNames = new ArrayList<>();
        for (VariableTree parameter : lambda.getParameters()) {
            paramNames.add(parameter.getName().toString());
        }

        String reference = call instanceof NewClassTree newClass
                ? constructorReference(newClass, paramNames, file)
                : methodReference((MethodInvocationTree) call, paramNames, nodePath, file);
        if (reference == null) {
            return ConversionResult.refuse("lambda_unsupported_shape",
                    "Lambda body shape is not convertible to a method reference.");
        }
        if (reference.startsWith("@")) {
            // Encoded refusal: "@code:message".
            int colon = reference.indexOf(':');
            return ConversionResult.refuse(reference.substring(1, colon), reference.substring(colon + 1));
        }
        int start = startOf(unit, lambda);
        int end = endOf(unit, lambda);
        if (start < 0 || end < start) {
            return ConversionResult.refuse("lambda_not_found", "Could not locate the lambda source range.");
        }
        return ConversionResult.accept(start, end, reference);
    }

    private ExpressionTree singleCallExpression(LambdaExpressionTree lambda) {
        Tree body = lambda.getBody();
        ExpressionTree expression;
        if (body instanceof BlockTree block) {
            List<? extends StatementTree> statements = block.getStatements();
            if (statements.size() != 1) {
                return null;
            }
            StatementTree only = statements.get(0);
            if (only instanceof ReturnTree returnTree && returnTree.getExpression() != null) {
                expression = returnTree.getExpression();
            } else if (only instanceof ExpressionStatementTree statement) {
                expression = statement.getExpression();
            } else {
                return null;
            }
        } else if (body instanceof ExpressionTree bodyExpression) {
            expression = bodyExpression;
        } else {
            return null;
        }
        expression = unwrap(expression);
        return (expression instanceof MethodInvocationTree || expression instanceof NewClassTree) ? expression : null;
    }

    private String constructorReference(NewClassTree newClass, List<String> paramNames, Path file) {
        if (newClass.getClassBody() != null) {
            return "@lambda_unsupported_shape:Anonymous-class constructor cannot become a constructor reference.";
        }
        String argCheck = forwardsParamsInOrder(newClass.getArguments(), paramNames, 0);
        if (argCheck != null) {
            return argCheck;
        }
        String typeText = text(file, newClass.getIdentifier());
        return typeText == null ? null : typeText + "::new";
    }

    private String methodReference(MethodInvocationTree invocation, List<String> paramNames, TreePath lambdaPath,
            Path file) {
        ExpressionTree select = invocation.getMethodSelect();
        List<? extends ExpressionTree> arguments = invocation.getArguments();

        if (select instanceof IdentifierTree identifier) {
            // Unqualified call `foo(args)`: forwards all lambda params, binds to this/enclosing-type.
            String argCheck = forwardsParamsInOrder(arguments, paramNames, 0);
            if (argCheck != null) {
                return argCheck;
            }
            Element method = trees.getElement(new TreePath(invocationPath(lambdaPath, invocation), select));
            if (method == null) {
                return "@lambda_unsupported_shape:Could not resolve the invoked method.";
            }
            String name = identifier.getName().toString();
            if (method.getModifiers().contains(Modifier.STATIC)) {
                Element owner = method.getEnclosingElement();
                if (!(owner instanceof TypeElement type)) {
                    return "@lambda_unsupported_shape:Could not resolve the static method's declaring type.";
                }
                return type.getSimpleName() + "::" + name;
            }
            return "this::" + name;
        }

        if (select instanceof MemberSelectTree member) {
            ExpressionTree receiver = unwrap(member.getExpression());
            String name = member.getIdentifier().toString();
            TreePath invPath = invocationPath(lambdaPath, invocation);
            Element receiverElement = trees.getElement(new TreePath(invPath, member.getExpression()));

            // Static call on a type name: `Type.create(args)` -> `Type::create`.
            if (receiverElement instanceof TypeElement type) {
                String argCheck = forwardsParamsInOrder(arguments, paramNames, 0);
                if (argCheck != null) {
                    return argCheck;
                }
                String receiverText = text(file, member.getExpression());
                return (receiverText != null ? receiverText : type.getSimpleName().toString()) + "::" + name;
            }

            // Unbound instance reference: `x -> x.trim()` / `x -> x.foo(rest...)` where the receiver is the FIRST
            // lambda param and the remaining params are forwarded as arguments in order.
            if (receiver instanceof IdentifierTree receiverId
                    && !paramNames.isEmpty()
                    && receiverId.getName().contentEquals(paramNames.get(0))) {
                String argCheck = forwardsParamsInOrder(arguments, paramNames, 1);
                if (argCheck == null) {
                    TypeMirror receiverType = trees.getTypeMirror(new TreePath(invPath, member.getExpression()));
                    String typeName = erasedTypeName(receiverType);
                    if (typeName == null) {
                        return "@lambda_unsupported_shape:Could not resolve the receiver's type.";
                    }
                    return typeName + "::" + name;
                }
                // Receiver is param0 but arguments are not the remaining params in order.
                return argCheck;
            }

            // Bound instance reference: `x -> obj.foo(x)` where the receiver does not reference any lambda param and
            // all params are forwarded in order -> `obj::foo`.
            if (referencesAnyParam(member.getExpression(), paramNames, invPath)) {
                return "@lambda_receiver_uses_param:Lambda receiver references a parameter; not a clean method reference.";
            }
            String argCheck = forwardsParamsInOrder(arguments, paramNames, 0);
            if (argCheck != null) {
                return argCheck;
            }
            if (!isStableBoundReceiver(member.getExpression(), invPath)) {
                return null;
            }
            String receiverText = text(file, member.getExpression());
            return receiverText == null ? null : receiverText + "::" + name;
        }

        return "@lambda_unsupported_shape:Unsupported invocation target for a method reference.";
    }

    /**
     * Verifies the {@code arguments} are exactly the lambda params {@code paramNames[fromParam..]} passed straight
     * through, in order, untransformed. Returns {@code null} when they forward cleanly, else an encoded refusal
     * ({@code @code:message}).
     */
    private String forwardsParamsInOrder(List<? extends ExpressionTree> arguments, List<String> paramNames,
            int fromParam) {
        List<String> expected = paramNames.subList(Math.min(fromParam, paramNames.size()), paramNames.size());
        if (arguments.size() != expected.size()) {
            return "@lambda_partial_args:Lambda does not forward its parameters one-to-one to the call.";
        }
        List<String> argNames = new ArrayList<>();
        for (ExpressionTree argument : arguments) {
            ExpressionTree unwrapped = unwrap(argument);
            if (!(unwrapped instanceof IdentifierTree identifier)) {
                return "@lambda_arg_transformed:A call argument is not a bare lambda parameter.";
            }
            argNames.add(identifier.getName().toString());
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(argNames.get(i))) {
                if (sameElements(expected, argNames)) {
                    return "@lambda_arg_reordered:Lambda reorders its parameters; not a method reference.";
                }
                return "@lambda_arg_transformed:A call argument is not the matching lambda parameter.";
            }
        }
        return null;
    }

    private boolean isStableBoundReceiver(ExpressionTree expression, TreePath invocationPath) {
        if (expression instanceof ParenthesizedTree parenthesized) {
            return isStableBoundReceiver(parenthesized.getExpression(), invocationPath);
        }
        if (!(expression instanceof IdentifierTree)) {
            return false;
        }
        Element element = trees.getElement(new TreePath(invocationPath, expression));
        if (element == null) {
            return false;
        }
        ElementKind kind = element.getKind();
        return kind == ElementKind.LOCAL_VARIABLE
                || kind == ElementKind.PARAMETER
                || kind == ElementKind.EXCEPTION_PARAMETER
                || kind == ElementKind.RESOURCE_VARIABLE;
    }

    private boolean referencesAnyParam(ExpressionTree expression, List<String> paramNames, TreePath invocationPath) {
        boolean[] found = {false};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                if (paramNames.contains(node.getName().toString())) {
                    found[0] = true;
                }
                return super.visitIdentifier(node, unused);
            }
        }.scan(expression, null);
        return found[0];
    }

    // ── shared helpers ───────────────────────────────────────────────────────────────────────────────────────────

    private static boolean sameElements(List<String> left, List<String> right) {
        return left.size() == right.size() && left.containsAll(right) && right.containsAll(left);
    }

    private String erasedTypeName(TypeMirror type) {
        if (type == null) {
            return "";
        }
        return types.erasure(type).toString();
    }

    private static ExpressionTree unwrap(ExpressionTree expression) {
        ExpressionTree current = expression;
        while (current instanceof ParenthesizedTree parenthesized) {
            current = parenthesized.getExpression();
        }
        return current;
    }

    private TreePath invocationPath(TreePath lambdaPath, MethodInvocationTree invocation) {
        TreePath found = findPath(lambdaPath, invocation);
        return found != null ? found : new TreePath(lambdaPath, invocation);
    }

    private TreePath findPath(TreePath root, Tree target) {
        TreePath[] result = {null};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (result[0] != null) {
                    return null;
                }
                if (tree == target) {
                    result[0] = new TreePath(getCurrentPath(), tree);
                    return null;
                }
                return super.scan(tree, unused);
            }
        }.scan(root, null);
        return result[0];
    }

    private CompilationUnitTree unitFor(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilationUnitTree unit : units) {
            if (SemanticIndex.pathOf(unit).equals(normalized)) {
                return unit;
            }
        }
        return null;
    }

    private TreePath locateAnonymous(CompilationUnitTree unit, Path file, int line, int column) {
        return locate(unit, file, line, column, tree ->
                tree instanceof NewClassTree newClass && newClass.getClassBody() != null);
    }

    private TreePath locateLambda(CompilationUnitTree unit, Path file, int line, int column) {
        return locate(unit, file, line, column, tree -> tree instanceof LambdaExpressionTree);
    }

    /**
     * Finds the matching node for a target position. With an explicit {@code column} the innermost matching node whose
     * source range contains the offset is returned; with {@code column <= 0} the first matching node starting on
     * {@code line} (by source offset) is returned.
     */
    private TreePath locate(CompilationUnitTree unit, Path file, int line, int column, NodeMatcher matcher) {
        long targetOffset = column > 0 ? unit.getLineMap().getPosition(line, column) : -1;
        TreePath[] best = {null};
        int[] bestSpan = {Integer.MAX_VALUE};
        long[] bestStart = {Long.MAX_VALUE};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null && matcher.matches(tree)) {
                    int start = startOf(unit, tree);
                    int end = endOf(unit, tree);
                    if (start >= 0 && end >= start) {
                        if (targetOffset >= 0) {
                            if (targetOffset >= start && targetOffset < end && (end - start) < bestSpan[0]) {
                                bestSpan[0] = end - start;
                                best[0] = new TreePath(getCurrentPath(), tree);
                            }
                        } else {
                            long startLine = unit.getLineMap().getLineNumber(start);
                            if (startLine == line && start < bestStart[0]) {
                                bestStart[0] = start;
                                best[0] = new TreePath(getCurrentPath(), tree);
                            }
                        }
                    }
                }
                return super.scan(tree, unused);
            }
        }.scan(unit, null);
        return best[0];
    }

    private int startOf(CompilationUnitTree unit, Tree tree) {
        return (int) positions.getStartPosition(unit, tree);
    }

    private int endOf(CompilationUnitTree unit, Tree tree) {
        return (int) positions.getEndPosition(unit, tree);
    }

    private String text(Path file, Tree tree) {
        CompilationUnitTree unit = unitFor(file);
        if (unit == null) {
            return null;
        }
        int start = startOf(unit, tree);
        int end = endOf(unit, tree);
        CharSequence source = index.sourceText(file.toAbsolutePath().normalize());
        if (source == null || start < 0 || end < start || end > source.length()) {
            return null;
        }
        return source.subSequence(start, end).toString();
    }

    @FunctionalInterface
    private interface NodeMatcher {
        boolean matches(Tree tree);
    }
}
