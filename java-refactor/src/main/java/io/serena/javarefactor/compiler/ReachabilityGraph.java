package io.serena.javarefactor.compiler;

import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Types;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiler-backed, element-level reachability model for V3 propagating safe delete and dead-code scan
 * (refactor-feature-plan-V3.md §7.2).
 *
 * <p>The graph is built once over every analyzed compiler task (home + secondaries) held by a {@link SemanticIndex}.
 * Nodes are the deletable/declarable program elements — top-level and nested types, methods, constructors, and fields —
 * each keyed by its cross-task-stable {@link SemanticKey#canonical()} string. Edges are the semantic references between
 * those nodes resolved by javac (identifiers, member selects, method invocations, constructor calls, and method
 * references); an edge {@code A -> B} means the declaration {@code A} references the declaration {@code B}. {@code
 * incoming(B)} therefore yields every declaration that would break if {@code B} were deleted.
 *
 * <p>Each node carries the root facts the deletion algorithm needs: whether it is public/protected API, a framework or
 * reflective entry point (by annotation), a {@code main}/native/serialization hook, or a test symbol. The class computes
 * these facts but applies no policy itself — the planner and the dead-code analyzer combine them with the caller's
 * options (allow_public_api, include_tests, public_api_policy). Service-loader provider status is intentionally NOT a
 * graph root: an explicitly-requested provider must remain deletable (with its {@code META-INF/services} line rewritten
 * by the planner), so service-loader handling lives in the callers, not here.
 *
 * <p>This lives in the {@code compiler} package so it can read the package-private compiler-task internals of {@link
 * SemanticIndex}; it adds no mutation surface (it never edits source) and resolves only project-internal references —
 * references to JDK or dependency elements are ignored because their keys are never registered as nodes.
 */
public final class ReachabilityGraph {

    /** The element categories the graph tracks; locals/parameters are never nodes. */
    public enum NodeKind { TYPE, METHOD, CONSTRUCTOR, FIELD }

    /** A single declarable program element plus the facts root classification needs. */
    public static final class Node {
        private final String key;
        private final NodeKind kind;
        private final String simpleName;
        private final String ownerTypeFqn;
        private final String enclosingTypeKey;
        private final Path file;
        private final int declStart;
        private final int declEnd;
        private final boolean topLevelType;
        private final boolean publicApi;
        private final boolean privateMember;
        private final boolean frameworkEntry;
        private final String frameworkReason;
        private final boolean structuralRoot;
        private final String structuralReason;
        private final boolean testSource;

        private Node(String key, NodeKind kind, String simpleName, String ownerTypeFqn, String enclosingTypeKey,
                Path file, int declStart, int declEnd, boolean topLevelType, boolean publicApi, boolean privateMember,
                boolean frameworkEntry, String frameworkReason, boolean structuralRoot, String structuralReason,
                boolean testSource) {
            this.key = key;
            this.kind = kind;
            this.simpleName = simpleName;
            this.ownerTypeFqn = ownerTypeFqn;
            this.enclosingTypeKey = enclosingTypeKey;
            this.file = file;
            this.declStart = declStart;
            this.declEnd = declEnd;
            this.topLevelType = topLevelType;
            this.publicApi = publicApi;
            this.privateMember = privateMember;
            this.frameworkEntry = frameworkEntry;
            this.frameworkReason = frameworkReason;
            this.structuralRoot = structuralRoot;
            this.structuralReason = structuralReason;
            this.testSource = testSource;
        }

        public String key() {
            return key;
        }

        public NodeKind kind() {
            return kind;
        }

        public String simpleName() {
            return simpleName;
        }

        public String ownerTypeFqn() {
            return ownerTypeFqn;
        }

        public String enclosingTypeKey() {
            return enclosingTypeKey;
        }

        public Path file() {
            return file;
        }

        public int declStart() {
            return declStart;
        }

        public int declEnd() {
            return declEnd;
        }

        public boolean topLevelType() {
            return topLevelType;
        }

        public boolean publicApi() {
            return publicApi;
        }

        public boolean privateMember() {
            return privateMember;
        }

        public boolean frameworkEntry() {
            return frameworkEntry;
        }

        public String frameworkReason() {
            return frameworkReason;
        }

        /** True for a genuine non-API entry point: {@code main}/native/serialization hook, or a test symbol. */
        public boolean structuralRoot() {
            return structuralRoot;
        }

        public String structuralReason() {
            return structuralReason;
        }

        public boolean testSource() {
            return testSource;
        }

        /** Human-readable kind label for messages: {@code class}, {@code method}, {@code constructor}, {@code field}. */
        public String kindLabel() {
            return switch (kind) {
                case TYPE -> "class";
                case METHOD -> "method";
                case CONSTRUCTOR -> "constructor";
                case FIELD -> "field";
            };
        }

        /**
         * Whether this node is a deletion ROOT for the cascade (refactor-feature-plan-V3.md §7.2): public/protected API,
         * a framework/reflective entry point, a {@code main}/native/serialization hook, or a test symbol when tests are
         * excluded. Explicitly-requested roots may still be deleted by the planner; this only governs which symbols the
         * cascade may pull in automatically and which the dead-code scan keeps.
         *
         * @param honorPublicApi when false, public/protected visibility alone does not make the node a root (the caller
         *                       opted into deleting public API)
         */
        public boolean isCascadeRoot(boolean honorPublicApi) {
            if (structuralRoot || frameworkEntry) {
                return true;
            }
            return honorPublicApi && publicApi;
        }

        /** The reason this node is a root (or null when it is not), mirroring {@link #isCascadeRoot(boolean)}. */
        public String rootReason(boolean honorPublicApi) {
            if (structuralRoot) {
                return structuralReason;
            }
            if (frameworkEntry) {
                return frameworkReason;
            }
            if (honorPublicApi && publicApi) {
                return "public/protected API";
            }
            return null;
        }
    }

    /**
     * Count of how many times {@link #build} has actually executed (a full walk over the compiler tasks). The
     * {@link ReachabilityGraphCache} short-circuits {@code build} on a cache hit, so this counter only advances on a
     * genuine miss/rebuild — letting a sidecar protocol test observe cache hit vs. miss without any timing heuristic.
     */
    private static final java.util.concurrent.atomic.AtomicLong BUILD_COUNT = new java.util.concurrent.atomic.AtomicLong();

    /** The number of times {@link #build} has executed a full graph walk (advances on cache misses only). */
    public static long buildInvocationCount() {
        return BUILD_COUNT.get();
    }

    private final Map<String, Node> nodes;
    private final Map<String, Set<String>> outgoing;
    private final Map<String, Set<String>> incoming;

    private ReachabilityGraph(Map<String, Node> nodes, Map<String, Set<String>> outgoing,
            Map<String, Set<String>> incoming) {
        this.nodes = nodes;
        this.outgoing = outgoing;
        this.incoming = incoming;
    }

    public Collection<Node> nodes() {
        return nodes.values();
    }

    public Node node(String key) {
        return nodes.get(key);
    }

    /** The keys of every declaration that references {@code key} (its referrers). */
    public Set<String> incoming(String key) {
        return incoming.getOrDefault(key, Set.of());
    }

    /** The keys of every declaration that {@code key} references. */
    public Set<String> outgoing(String key) {
        return outgoing.getOrDefault(key, Set.of());
    }

    // ── construction ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Dependency-injection / lifecycle annotations (JSR-330 {@code @Inject}, JSR-250 {@code @Resource}/lifecycle, by
     * exact FQN) that are NOT owned by any of the four recognized frameworks but still mark a symbol as reflectively
     * wired, so it must not be deleted by "no Java references" alone. Recognized framework annotations are matched
     * separately through {@link FrameworkAnnotationCatalog}.
     */
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
            "jakarta.inject.Inject", "javax.inject.Inject",
            "jakarta.annotation.Resource", "javax.annotation.Resource",
            "jakarta.annotation.PostConstruct", "javax.annotation.PostConstruct",
            "jakarta.annotation.PreDestroy", "javax.annotation.PreDestroy");

    /** The {@code @Override} contract annotation, by exact FQN: an overriding member satisfies a supertype contract. */
    private static final String OVERRIDE_ANNOTATION = "java.lang.Override";

    /** Method names that are serialization hooks the JVM invokes reflectively. */
    private static final Set<String> SERIALIZATION_METHODS = Set.of(
            "readObject", "writeObject", "readResolve", "writeReplace", "readObjectNoData");

    /**
     * Builds the reachability graph over every analyzed task of {@code index}.
     *
     * @param index        an open semantic index (home + secondary tasks)
     * @param model        the validated project model (for source-set/test classification)
     * @param includeTests when false, symbols declared in a test source set are roots
     */
    public static ReachabilityGraph build(SemanticIndex index, JavaProjectModel model, boolean includeTests) {
        BUILD_COUNT.incrementAndGet();
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        Map<String, Set<String>> incoming = new LinkedHashMap<>();

        Set<Path> testRoots = testSourceRoots(model);

        // Pass 1: register every type/method/constructor/field declaration as a node.
        for (CompilerTask task : index.allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                boolean testSource = isUnderAny(file, testRoots);
                new DeclarationScanner(task.trees, task.types, task.positions, unit, file, testSource, includeTests,
                        nodes).scan(unit, null);
            }
        }

        // Pass 2: resolve references between registered nodes into directed edges.
        for (CompilerTask task : index.allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                new ReferenceScanner(task.trees, task.types, unit, file, nodes, outgoing, incoming).scan(unit, null);
            }
        }
        return new ReachabilityGraph(nodes, outgoing, incoming);
    }

    private static final class DeclarationScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Types types;
        private final SourcePositions positions;
        private final CompilationUnitTree unit;
        private final Path file;
        private final boolean testSource;
        private final boolean includeTests;
        private final Map<String, Node> nodes;

        DeclarationScanner(Trees trees, Types types, SourcePositions positions, CompilationUnitTree unit, Path file,
                boolean testSource, boolean includeTests, Map<String, Node> nodes) {
            this.trees = trees;
            this.types = types;
            this.positions = positions;
            this.unit = unit;
            this.file = file;
            this.testSource = testSource;
            this.includeTests = includeTests;
            this.nodes = nodes;
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            register(tree);
            return super.visitClass(tree, unused);
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            register(tree);
            return super.visitMethod(tree, unused);
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            register(tree);
            return super.visitVariable(tree, unused);
        }

        private void register(Tree tree) {
            Element element = trees.getElement(new TreePath(getCurrentPath(), tree));
            NodeKind kind = nodeKind(element);
            if (kind == null) {
                return;
            }
            String key = SemanticKey.from(element, trees, types, unit, file).canonical();
            if (key == null || key.isBlank() || nodes.containsKey(key)) {
                return;
            }
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            if (start < 0 || end < start) {
                return;
            }
            boolean topLevelType = kind == NodeKind.TYPE
                    && element.getEnclosingElement() != null
                    && element.getEnclosingElement().getKind() == ElementKind.PACKAGE;
            TypeElement enclosingType = enclosingType(element);
            String enclosingTypeKey = topLevelType || enclosingType == null
                    ? null
                    : SemanticKey.from(enclosingType, trees, types, unit, file).canonical();
            String ownerTypeFqn = enclosingType == null
                    ? (element instanceof TypeElement t ? t.getQualifiedName().toString() : "")
                    : enclosingType.getQualifiedName().toString();

            Set<Modifier> modifiers = element.getModifiers();
            boolean publicApi = modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED);
            boolean privateMember = modifiers.contains(Modifier.PRIVATE);
            String frameworkReason = frameworkReason(element);
            String structuralReason = structuralReason(element, kind, modifiers);

            nodes.put(key, new Node(key, kind, element.getSimpleName().toString(), ownerTypeFqn, enclosingTypeKey,
                    file, (int) start, (int) end, topLevelType, publicApi, privateMember,
                    frameworkReason != null, frameworkReason, structuralReason != null, structuralReason, testSource));
        }

        private String frameworkReason(Element element) {
            for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
                String fqn = annotationFqn(mirror);
                if (fqn == null) {
                    continue;
                }
                String reason = FrameworkAnnotationCatalog.entryPointReason(fqn);
                if (reason != null) {
                    return reason;
                }
            }
            return null;
        }

        private String structuralReason(Element element, NodeKind kind, Set<Modifier> modifiers) {
            if (!includeTests && testSource) {
                return "declared in a test source set (include_tests=false)";
            }
            for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
                String fqn = annotationFqn(mirror);
                if (fqn == null) {
                    continue;
                }
                if (INJECTION_ANNOTATIONS.contains(fqn)) {
                    return "reflectively wired via @" + fqn + " (dependency-injection/lifecycle hook)";
                }
                if (OVERRIDE_ANNOTATION.equals(fqn)) {
                    return "overrides a supertype member (@java.lang.Override)";
                }
            }
            if (modifiers.contains(Modifier.NATIVE)) {
                return "native method";
            }
            if (kind == NodeKind.METHOD && element instanceof ExecutableElement method) {
                if (isMainMethod(method)) {
                    return "main entry point";
                }
                if (SERIALIZATION_METHODS.contains(method.getSimpleName().toString())) {
                    return "serialization hook";
                }
            }
            if (kind == NodeKind.FIELD && "serialVersionUID".equals(element.getSimpleName().toString())) {
                return "serialization field";
            }
            return null;
        }

        private static boolean isMainMethod(ExecutableElement method) {
            return "main".equals(method.getSimpleName().toString())
                    && method.getModifiers().contains(Modifier.STATIC)
                    && method.getParameters().size() == 1;
        }

        /** The exact fully-qualified name of an applied annotation, or {@code null} if it cannot be resolved. */
        private static String annotationFqn(AnnotationMirror mirror) {
            Element annotation = mirror.getAnnotationType().asElement();
            return annotation instanceof TypeElement typeElement
                    ? typeElement.getQualifiedName().toString()
                    : null;
        }
    }

    private static final class ReferenceScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Types types;
        private final CompilationUnitTree unit;
        private final Path file;
        private final Map<String, Node> nodes;
        private final Map<String, Set<String>> outgoing;
        private final Map<String, Set<String>> incoming;

        ReferenceScanner(Trees trees, Types types, CompilationUnitTree unit, Path file, Map<String, Node> nodes,
                Map<String, Set<String>> outgoing, Map<String, Set<String>> incoming) {
            this.trees = trees;
            this.types = types;
            this.unit = unit;
            this.file = file;
            this.nodes = nodes;
            this.outgoing = outgoing;
            this.incoming = incoming;
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
            record(trees.getElement(getCurrentPath()));
            return super.visitIdentifier(tree, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            record(trees.getElement(getCurrentPath()));
            return super.visitMemberSelect(tree, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            record(trees.getElement(getCurrentPath()));
            return super.visitMethodInvocation(tree, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            record(trees.getElement(getCurrentPath()));
            return super.visitNewClass(tree, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
            record(trees.getElement(getCurrentPath()));
            return super.visitMemberReference(tree, unused);
        }

        private void record(Element referenced) {
            if (referenced == null) {
                return;
            }
            NodeKind kind = nodeKind(referenced);
            if (kind == null) {
                return;
            }
            String enclosingKey = enclosingDeclarationKey();
            if (enclosingKey == null) {
                return;
            }
            addEdge(enclosingKey, SemanticKey.from(referenced, trees, types, unit, file).canonical());
            // Referencing a member also keeps its declaring type alive (e.g. Foo.bar() uses Foo too).
            if (kind != NodeKind.TYPE) {
                TypeElement enclosingType = enclosingType(referenced);
                if (enclosingType != null) {
                    addEdge(enclosingKey, SemanticKey.from(enclosingType, trees, types, unit, file).canonical());
                }
            }
        }

        private void addEdge(String from, String to) {
            if (to == null || to.equals(from) || !nodes.containsKey(to) || !nodes.containsKey(from)) {
                return;
            }
            outgoing.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
            incoming.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
        }

        /** The nearest enclosing type/method/field declaration that is itself a registered node. */
        private String enclosingDeclarationKey() {
            TreePath path = getCurrentPath();
            while (path != null) {
                Tree leaf = path.getLeaf();
                if (leaf instanceof ClassTree || leaf instanceof MethodTree || leaf instanceof VariableTree) {
                    Element element = trees.getElement(path);
                    if (nodeKind(element) != null) {
                        String key = SemanticKey.from(element, trees, types, unit, file).canonical();
                        if (nodes.containsKey(key)) {
                            return key;
                        }
                    }
                }
                path = path.getParentPath();
            }
            return null;
        }
    }

    // ── shared helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private static NodeKind nodeKind(Element element) {
        if (element == null) {
            return null;
        }
        return switch (element.getKind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> NodeKind.TYPE;
            case METHOD -> NodeKind.METHOD;
            case CONSTRUCTOR -> NodeKind.CONSTRUCTOR;
            case FIELD, ENUM_CONSTANT -> NodeKind.FIELD;
            default -> null;
        };
    }

    private static TypeElement enclosingType(Element element) {
        Element enclosing = element.getEnclosingElement();
        while (enclosing != null) {
            if (enclosing instanceof TypeElement type) {
                return type;
            }
            enclosing = enclosing.getEnclosingElement();
        }
        return null;
    }

    private static Path pathOf(CompilationUnitTree unit) {
        return Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
    }

    private static Set<Path> testSourceRoots(JavaProjectModel model) {
        Set<Path> roots = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            if (sourceSet.name() != null && sourceSet.name().toLowerCase(Locale.ROOT).contains("test")) {
                for (Path root : sourceSet.sourceRoots()) {
                    roots.add(root.toAbsolutePath().normalize());
                }
            }
        }
        return roots;
    }

    private static boolean isUnderAny(Path file, Set<Path> roots) {
        for (Path root : roots) {
            if (file.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
}
