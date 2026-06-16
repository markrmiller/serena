package io.serena.javarefactor.shared;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.serena.javarefactor.ast.SemanticKey;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Normalized, javac-backed model of a method body for V2 refactoring operations.
 *
 * <p>The model is built from resolved javac {@link Element}s rather than textual identifier scanning, so it captures
 * <em>element identity</em>: the {@code element*} sets contain canonical {@link SemanticKey} strings that distinguish a
 * field {@code value} from a local {@code value} from a parameter {@code value} even though all three share a simple
 * name. The simple-name sets ({@link #reads()}, {@link #writes()}, {@link #calls()}, {@link #referencedTypes()}) are
 * retained as a best-effort textual view and as the only available signal for unresolved snippets, but downstream
 * operations (inline G019, extract method G015, move instance G013, introduce field G017, purity G004) should prefer the
 * element-key sets and the structural facts (statement list, control-flow flags) for any soundness-critical decision.
 *
 * <p>Construction:
 * <ul>
 *   <li>{@link #fromMethod(TreePath, Trees, Types)} — primary entry point against an analyzed compilation unit; populates
 *       {@link #methodKey()}, the {@code element*} key sets, the statement list and all control-flow flags.</li>
 *   <li>{@link #fromSource(String, String)} — compiles a complete class in-memory and models the named method with full
 *       element resolution (used by unit tests and self-contained callers).</li>
 *   <li>{@link #fromSnippet(String)} / {@link #fromTreePath(TreePath)} — legacy textual paths; element-key sets are
 *       populated only insofar as symbols resolve.</li>
 * </ul>
 */
public record MethodBodyModel(
        String methodKey,
        TreePath methodPath,
        List<StatementTree> statements,
        Set<String> reads,
        Set<String> writes,
        Set<String> calls,
        Set<String> referencedTypes,
        Set<String> elementReads,
        Set<String> elementWrites,
        Set<String> elementCalls,
        Set<String> referencedTypeKeys,
        boolean usesThis,
        boolean usesSuper,
        boolean hasReturn,
        boolean hasThrow,
        boolean usesSynchronized,
        boolean usesLambda,
        boolean usesAnonymousClass,
        Set<String> checkedExceptions) {

    /** True when the body can transfer control out via {@code return} or {@code throw}. */
    public boolean hasControlFlowExit() {
        return hasReturn || hasThrow;
    }

    /** True when the body introduces a lambda or anonymous/local class scope boundary. */
    public boolean crossesLambdaBoundary() {
        return usesLambda || usesAnonymousClass;
    }

    /**
     * Pure external reads: simple names read in the body that are never assigned or declared within it. This is a
     * <em>derived view</em> over the canonical {@link #reads()} and {@link #writes()} sets, which are kept independent so
     * a read-modify-write variable (e.g. {@code x = x + 1}, {@code this.count = this.count + delta}, compound assignment,
     * {@code ++}/{@code --}) is reported in BOTH {@code reads} and {@code writes}. Consumers that need the legacy
     * "inputs not produced locally" notion (e.g. cross-file inline resolvability) should use this view rather than
     * relying on {@code reads()} being pre-subtracted.
     */
    public Set<String> pureExternalReads() {
        return difference(reads, writes);
    }

    private static Set<String> difference(Set<String> base, Set<String> subtract) {
        if (base.isEmpty() || subtract.isEmpty()) {
            return base;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>(base);
        result.removeAll(subtract);
        return Set.copyOf(result);
    }

    public static MethodBodyModel fromSnippet(String source) {
        String snippet = source == null ? "" : source;
        return parseSnippet(snippet).map(MethodBodyModel::fromParsedMethod).orElseGet(MethodBodyModel::empty);
    }

    /**
     * Models the named method of a complete, self-contained class source, resolving against javac element identity.
     * Returns {@link #empty()} when the source fails to parse/analyze or the method is not found.
     */
    public static MethodBodyModel fromSource(String classSource, String methodName) {
        if (classSource == null || methodName == null) {
            return empty();
        }
        JavaFileObject source = new StringJavaFileObject("__SerenaSource", classSource);
        try {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                    .getTask(null, null, null, List.of("-proc:none"), null, List.of(source));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            Trees trees = Trees.instance(task);
            Types types = task.getTypes();
            List<TreePath> matches = new ArrayList<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    if (methodName.contentEquals(node.getName()) && node.getBody() != null) {
                        matches.add(getCurrentPath());
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(units, null);
            if (matches.size() == 1) {
                return fromMethod(matches.get(0), trees, types);
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // Conservatively model unparseable/ambiguous sources as empty.
        }
        return empty();
    }

    /**
     * Overload-safe variant of {@link #fromSource(String, String)} (G014). Where {@code fromSource} models a method only
     * when exactly one declaration shares the simple name — falling back to {@link #empty()} for any overloaded name — this
     * binds to the <em>specific</em> selected executable by the source position of its body's opening brace. The body start
     * offset is unique within a compilation unit, so it disambiguates overloads with the same name but different parameter
     * lists. The offset is the position of the method body's {@code '{'} (matching {@code SemanticMethod.bodyRange().start()}).
     * Returns {@link #empty()} when the source fails to parse/analyze or no method body opens at {@code bodyStartOffset}.
     */
    public static MethodBodyModel fromSourceAtBody(String classSource, String methodName, int bodyStartOffset) {
        if (classSource == null || methodName == null || bodyStartOffset < 0) {
            return empty();
        }
        JavaFileObject source = new StringJavaFileObject("__SerenaSource", classSource);
        try {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                    .getTask(null, null, null, List.of("-proc:none"), null, List.of(source));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            Trees trees = Trees.instance(task);
            Types types = task.getTypes();
            com.sun.source.util.SourcePositions positions = trees.getSourcePositions();
            List<TreePath> matches = new ArrayList<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    if (methodName.contentEquals(node.getName()) && node.getBody() != null) {
                        CompilationUnitTree unit = getCurrentPath().getCompilationUnit();
                        long bodyStart = positions.getStartPosition(unit, node.getBody());
                        if (bodyStart == bodyStartOffset) {
                            matches.add(getCurrentPath());
                        }
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(units, null);
            if (matches.size() == 1) {
                return fromMethod(matches.get(0), trees, types);
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // Conservatively model unparseable/unmatched sources as empty so the caller refuses rather than guesses.
        }
        return empty();
    }

    /**
     * Primary javac-backed entry point. {@code methodPath} must point at a {@link MethodTree} with a body; {@code trees}
     * and {@code types} come from the analyzed task that produced {@code methodPath} and enable element-identity facts.
     */
    public static MethodBodyModel fromMethod(TreePath methodPath, Trees trees, Types types) {
        if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree method) || method.getBody() == null) {
            return empty();
        }
        CompilationUnitTree unit = methodPath.getCompilationUnit();
        Path file = fileOf(unit);
        BlockTree body = method.getBody();
        BodyScanner scanner = new BodyScanner(trees, types, unit, file);
        scanner.scan(new TreePath(methodPath, body), null);
        String methodKey = methodKeyFor(methodPath, trees, types, unit, file);
        List<StatementTree> statements = List.copyOf(body.getStatements());
        return scanner.toModel(methodKey, methodPath, statements);
    }

    public static MethodBodyModel fromTreePath(TreePath methodPath) {
        return fromMethod(methodPath, null, null);
    }

    private static Optional<ParsedMethod> parseSnippet(String snippet) {
        JavaFileObject source = new StringJavaFileObject(
                "__SerenaBody",
                "class __SerenaBody { void __serena() throws Exception {\n" + snippet + "\n} }");
        try {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                    .getTask(null, null, null, List.of("-proc:none"), null, List.of(source));
            List<ParsedMethod> methods = new ArrayList<>();
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            Trees trees = Trees.instance(task);
            Types types = task.getTypes();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    if ("__serena".contentEquals(node.getName())) {
                        methods.add(new ParsedMethod(getCurrentPath(), trees, types));
                    }
                    return null;
                }
            }.scan(units, null);
            if (methods.size() == 1) {
                return Optional.of(methods.get(0));
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // Invalid snippets are conservatively modeled as empty so callers refuse on missing required facts.
        }
        return Optional.empty();
    }

    private static MethodBodyModel fromParsedMethod(ParsedMethod parsed) {
        return fromMethod(parsed.path(), parsed.trees(), parsed.types());
    }

    private static MethodBodyModel empty() {
        return new MethodBodyModel(
                null,
                null,
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                Set.of());
    }

    private static String methodKeyFor(TreePath methodPath, Trees trees, Types types, CompilationUnitTree unit, Path file) {
        if (trees == null) {
            return null;
        }
        Element element = trees.getElement(methodPath);
        if (element == null) {
            return null;
        }
        return SemanticKey.from(element, trees, types, unit, file).canonical();
    }

    private static Path fileOf(CompilationUnitTree unit) {
        if (unit == null) {
            return null;
        }
        try {
            URI uri = unit.getSourceFile().toUri();
            if ("file".equals(uri.getScheme())) {
                return Path.of(uri).toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            // Fall through to the in-memory source-name fallback below.
        }
        try {
            return Path.of(unit.getSourceFile().getName());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record ParsedMethod(TreePath path, Trees trees, Types types) {}

    private static final class BodyScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Types types;
        private final CompilationUnitTree unit;
        private final Path file;
        private final LinkedHashSet<String> reads = new LinkedHashSet<>();
        private final LinkedHashSet<String> writes = new LinkedHashSet<>();
        private final LinkedHashSet<String> calls = new LinkedHashSet<>();
        private final LinkedHashSet<String> referencedTypes = new LinkedHashSet<>();
        private final LinkedHashSet<String> elementReads = new LinkedHashSet<>();
        private final LinkedHashSet<String> elementWrites = new LinkedHashSet<>();
        private final LinkedHashSet<String> elementCalls = new LinkedHashSet<>();
        private final LinkedHashSet<String> referencedTypeKeys = new LinkedHashSet<>();
        private final LinkedHashSet<String> checkedExceptions = new LinkedHashSet<>();
        private boolean usesThis;
        private boolean usesSuper;
        private boolean hasReturn;
        private boolean hasThrow;
        private boolean usesSynchronized;
        private boolean usesLambda;
        private boolean usesAnonymousClass;
        private boolean writeTarget;

        private BodyScanner(Trees trees, Types types, CompilationUnitTree unit, Path file) {
            this.trees = trees;
            this.types = types;
            this.unit = unit;
            this.file = file;
        }

        private MethodBodyModel toModel(String methodKey, TreePath methodPath, List<StatementTree> statements) {
            // Keep reads and writes independent (Blocker 6): a variable that is both read and written (read-modify-write,
            // compound assignment, ++/--) must remain in BOTH sets. Consumers needing "inputs not produced locally" use
            // the pureExternalReads() derived view instead of mutating the canonical model.
            return new MethodBodyModel(
                    methodKey,
                    methodPath,
                    statements,
                    Set.copyOf(reads),
                    Set.copyOf(writes),
                    Set.copyOf(calls),
                    Set.copyOf(referencedTypes),
                    Set.copyOf(elementReads),
                    Set.copyOf(elementWrites),
                    Set.copyOf(elementCalls),
                    Set.copyOf(referencedTypeKeys),
                    usesThis,
                    usesSuper,
                    hasReturn,
                    hasThrow,
                    usesSynchronized,
                    usesLambda,
                    usesAnonymousClass,
                    Set.copyOf(checkedExceptions));
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            writes.add(node.getName().toString());
            recordElement(getCurrentPath(), true);
            recordType(node.getType());
            scan(node.getInitializer(), unused);
            return null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            String name = node.getName().toString();
            if ("this".equals(name)) {
                usesThis = true;
            } else if ("super".equals(name)) {
                usesSuper = true;
            } else if (writeTarget) {
                writes.add(name);
                recordElement(getCurrentPath(), true);
            } else {
                reads.add(name);
                recordElement(getCurrentPath(), false);
            }
            return null;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            String selected = node.getIdentifier().toString();
            if ("this".equals(selected)) {
                usesThis = true;
            } else if ("super".equals(selected)) {
                usesSuper = true;
            } else if (writeTarget) {
                writes.add(selected);
                recordElement(getCurrentPath(), true);
            } else {
                recordElement(getCurrentPath(), false);
            }
            if (node.getExpression() instanceof IdentifierTree qualifier && "super".equals(qualifier.getName().toString())) {
                usesSuper = true;
            }
            scan(node.getExpression(), unused);
            return null;
        }

        @Override
        public Void visitAssignment(AssignmentTree node, Void unused) {
            scanWriteTarget(node.getVariable(), unused);
            scan(node.getExpression(), unused);
            return null;
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
            scan(node.getVariable(), unused);
            scanWriteTarget(node.getVariable(), unused);
            scan(node.getExpression(), unused);
            return null;
        }

        @Override
        public Void visitUnary(UnaryTree node, Void unused) {
            switch (node.getKind()) {
                case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> {
                    scan(node.getExpression(), unused);
                    scanWriteTarget(node.getExpression(), unused);
                    return null;
                }
                default -> {
                    return super.visitUnary(node, unused);
                }
            }
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            Tree methodSelect = node.getMethodSelect();
            if (methodSelect instanceof IdentifierTree identifier) {
                calls.add(identifier.getName().toString());
            } else if (methodSelect instanceof MemberSelectTree memberSelect) {
                calls.add(memberSelect.getIdentifier().toString());
                if (memberSelect.getExpression() instanceof IdentifierTree qualifier) {
                    String qualifierName = qualifier.getName().toString();
                    if ("this".equals(qualifierName)) {
                        usesThis = true;
                    } else if ("super".equals(qualifierName)) {
                        usesSuper = true;
                    }
                }
                scan(memberSelect.getExpression(), unused);
            }
            recordCall(getCurrentPath());
            for (var argument : node.getArguments()) {
                scan(argument, unused);
            }
            return null;
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            referencedTypes.add(node.getIdentifier().toString());
            recordTypeElement(new TreePath(getCurrentPath(), node.getIdentifier()));
            for (var argument : node.getArguments()) {
                scan(argument, unused);
            }
            if (node.getClassBody() != null) {
                usesAnonymousClass = true;
            }
            return null;
        }

        @Override
        public Void visitReturn(ReturnTree node, Void unused) {
            hasReturn = true;
            return super.visitReturn(node, unused);
        }

        @Override
        public Void visitThrow(ThrowTree node, Void unused) {
            hasThrow = true;
            recordThrownType(node.getExpression());
            return super.visitThrow(node, unused);
        }

        @Override
        public Void visitSynchronized(SynchronizedTree node, Void unused) {
            usesSynchronized = true;
            return super.visitSynchronized(node, unused);
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
            usesLambda = true;
            return null;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            usesAnonymousClass = true;
            return null;
        }

        private void scanWriteTarget(Tree target, Void unused) {
            boolean previous = writeTarget;
            writeTarget = true;
            scan(target, unused);
            writeTarget = previous;
        }

        private void recordElement(TreePath path, boolean write) {
            if (trees == null) {
                return;
            }
            Element element = trees.getElement(path);
            if (!(element instanceof VariableElement variable)) {
                return;
            }
            String key = keyOf(variable);
            if (key == null) {
                return;
            }
            if (write) {
                elementWrites.add(key);
            } else {
                elementReads.add(key);
            }
        }

        private void recordCall(TreePath path) {
            if (trees == null) {
                return;
            }
            Element element = trees.getElement(path);
            if (element instanceof ExecutableElement executable) {
                String key = keyOf(executable);
                if (key != null) {
                    elementCalls.add(key);
                }
            }
        }

        private void recordType(Tree typeTree) {
            if (typeTree != null) {
                referencedTypes.add(typeTree.toString());
                recordTypeElement(new TreePath(getCurrentPath(), typeTree));
            }
        }

        private void recordTypeElement(TreePath typePath) {
            if (trees == null) {
                return;
            }
            Element element = trees.getElement(typePath);
            if (element instanceof TypeElement type) {
                String key = keyOf(type);
                if (key != null) {
                    referencedTypeKeys.add(key);
                }
            }
        }

        private void recordThrownType(Tree expression) {
            if (expression instanceof NewClassTree newClass) {
                checkedExceptions.add(newClass.getIdentifier().toString());
                return;
            }
            if (trees != null) {
                TypeMirror mirror = trees.getTypeMirror(getCurrentPath());
                if (mirror != null) {
                    checkedExceptions.add(mirror.toString());
                }
            }
        }

        private String keyOf(Element element) {
            try {
                return SemanticKey.from(element, trees, types, unit, file).canonical();
            } catch (RuntimeException ignored) {
                return null;
            }
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
