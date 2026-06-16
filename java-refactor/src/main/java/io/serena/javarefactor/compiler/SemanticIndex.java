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
import com.sun.source.tree.SynchronizedTree;
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
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
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

public final class SemanticIndex implements AutoCloseable {
    private final JavaProjectModel model;
    private final CompilerTask home;
    private final List<CompilerTask> secondaryTasks;
    // Home-task bindings (kept as fields so the existing element-specific helpers, which all operate on the TARGET
    // element living in the home source set, continue to work unchanged against the home task).
    final Trees trees;
    final Elements elements;
    final Types types;
    final SourcePositions positions;
    final List<CompilationUnitTree> units;
    final Map<Path, CharSequence> sourceByPath;
    final IdentifierSpanFinder spanFinder = new IdentifierSpanFinder();
    private final SemanticSelectionIndex selectionIndex;
    private final SemanticInlineIndex inlineIndex;

    private SemanticIndex(JavaProjectModel model, CompilerTask home, List<CompilerTask> secondaryTasks) {
        this.model = model;
        this.home = home;
        this.secondaryTasks = secondaryTasks;
        this.trees = home.trees;
        this.elements = home.elements;
        this.types = home.types;
        this.positions = home.positions;
        this.units = home.units;
        this.sourceByPath = home.sourceByPath;
        this.selectionIndex = new SemanticSelectionIndex(this);
        this.inlineIndex = new SemanticInlineIndex(this);
    }

    public static SemanticIndex open(JavaProjectModel model, String relativePath) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("JDK JavaCompiler is unavailable; run Serena with a JDK rather than a JRE.");
        }
        // Each source set is analyzed with its OWN compiler options (release/classpath/encoding/module settings may
        // diverge between main/test/custom sets), so analyzing them all with one set's options can mis-attribute
        // elements. The target file's source set is the "home" task; every other non-empty source set becomes a
        // "secondary" task. Cross-source-set references (e.g. a main symbol referenced from a test source set) are still
        // discovered because each task adds the other sets' source roots to -sourcepath (see crossSourceSetOptions),
        // and scanReferences traverses every task, matching by canonical key rather than Element identity.
        SourceSet targetSourceSet = sourceSetForTarget(model, relativePath);
        List<CompilerTask> secondary = new ArrayList<>();
        CompilerTask homeTask = null;
        boolean anyFiles = false;
        for (SourceSet sourceSet : model.sourceSets()) {
            if (sourceSet.javaFiles().isEmpty()) {
                continue;
            }
            anyFiles = true;
            CompilerTask task = CompilerTask.open(compiler, FileManagerPool.INSTANCE, sourceSet, model.sourceSets());
            if (sourceSet == targetSourceSet) {
                homeTask = task;
            } else {
                secondary.add(task);
            }
        }
        if (!anyFiles) {
            throw new IOException("No Java source files are available for semantic indexing.");
        }
        if (homeTask == null) {
            // The target source set has no Java files of its own (e.g. an empty configured set); fall back to opening it
            // directly so resolveTarget still reports a precise target_not_found rather than a misleading error.
            homeTask = CompilerTask.open(compiler, FileManagerPool.INSTANCE, targetSourceSet, model.sourceSets());
        }
        return new SemanticIndex(model, homeTask, secondary);
    }

    /** All compiler tasks (home first, then secondaries) that hold analyzed source for this index. */
    List<CompilerTask> allTasks() {
        List<CompilerTask> tasks = new ArrayList<>();
        tasks.add(home);
        tasks.addAll(secondaryTasks);
        return tasks;
    }

    private static SourceSet sourceSetForTarget(JavaProjectModel model, String relativePath) throws IOException {
        Path target = model.projectRoot().resolve(relativePath).toAbsolutePath().normalize();
        for (SourceSet sourceSet : model.sourceSets()) {
            if (sourceSet.javaFiles().stream().map(path -> path.toAbsolutePath().normalize()).anyMatch(target::equals)) {
                return sourceSet;
            }
        }
        throw new IOException("Target file is not in the Java project model: " + relativePath);
    }

    public RefactorAnalysisResult resolveTarget(String relativePath, long line, long column, String nameHint) throws IOException {
        Path file = model.projectRoot().resolve(relativePath).toAbsolutePath().normalize();
        CompilationUnitTree unit = findUnit(file).orElseThrow(() -> new IOException("Target file is not in the Java project model: " + relativePath));
        long offset = unit.getLineMap().getPosition(line, column);
        ResolvedTarget target = new TargetResolver(file, unit, offset, nameHint).resolve();
        return new RefactorAnalysisResult(target, target == null ? List.of() : scanReferences(target));
    }

    /**
     * Every refactorable DECLARATION whose identifier sits on {@code oneBasedLine} of {@code relativePath},
     * deduplicated by canonical key and ordered by source offset.
     *
     * <p>Used by the V2 semantic-target gate ({@code SemanticTargetGate}) to refuse line-only target selections that
     * are ambiguous — most importantly, two overloads of the same simple name declared on one line, which a name-hint
     * alone cannot separate. {@link #resolveTarget} already refuses imprecise positions that would climb to an
     * enclosing declaration (it requires the cursor inside the identifier span or an exact name-hint match), so this
     * method only needs to surface the residual same-line collision set, never to re-implement resolution.
     */
    public List<ResolvedTarget> declarationsOnLine(String relativePath, long oneBasedLine) throws IOException {
        Path file = model.projectRoot().resolve(relativePath).toAbsolutePath().normalize();
        CompilationUnitTree unit = findUnit(file).orElseThrow(
                () -> new IOException("Target file is not in the Java project model: " + relativePath));
        CharSequence source = sourceByPath.get(file);
        Map<String, ResolvedTarget> byKey = new LinkedHashMap<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree == null) {
                    return null;
                }
                TreePath path = new TreePath(getCurrentPath(), tree);
                super.scan(tree, unused);
                if (!(tree instanceof ClassTree || tree instanceof MethodTree || tree instanceof VariableTree)) {
                    return null;
                }
                Element element = trees.getElement(path);
                if (!isMemberOrTypeDeclaration(element)) {
                    return null;
                }
                IdentifierSpan span = spanFinder.find(file, unit, positions, tree, element, source);
                if (span == null || span.line() != oneBasedLine) {
                    return null;
                }
                SemanticKey key = SemanticKey.from(element, trees, types, unit, file);
                byKey.putIfAbsent(key.canonical(), new ResolvedTarget(element, key, span));
                return null;
            }
        }.scan(unit, null);
        return byKey.values().stream()
                .sorted(Comparator.comparingLong(t -> t.span().startOffset()))
                .toList();
    }

    /** Member/type declarations whose simple name could collide on a source line (excludes parameters/locals). */
    private static boolean isMemberOrTypeDeclaration(Element element) {
        if (element == null) {
            return false;
        }
        return switch (element.getKind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, METHOD, CONSTRUCTOR, FIELD, ENUM_CONSTANT,
                    RECORD_COMPONENT -> true;
            default -> false;
        };
    }

    List<IdentifierSpan> scanReferences(ResolvedTarget target) {
        return scanReferences(List.of(target.element()));
    }

    /**
     * Rich refusal-payload JSON array for blocking references (plan section 9): each entry is
     * {@code {relativePath, line, column, containingSymbol, snippet}} where {@code containingSymbol} is the
     * enclosing method/type name-path of the reference and {@code snippet} is the full source line text. Built here
     * (rather than in the planner) because resolving the enclosing declaration and the source line requires the AST and
     * source text held by this index.
     */
    public String referencesJsonRich(Path projectRoot, List<IdentifierSpan> spans) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < spans.size(); i++) {
            IdentifierSpan span = spans.get(i);
            if (i > 0) {
                builder.append(',');
            }
            String relative = relativeTo(span.file());
            builder.append("{")
                    .append("\"relativePath\":").append(JsonUtil.quote(relative)).append(',')
                    .append("\"path\":").append(JsonUtil.quote(relative)).append(',')
                    .append("\"line\":").append(span.line()).append(',')
                    .append("\"column\":").append(span.column()).append(',')
                    .append("\"startOffset\":").append(span.startOffset()).append(',')
                    .append("\"endOffset\":").append(span.endOffset()).append(',')
                    .append("\"text\":").append(JsonUtil.quote(span.text())).append(',')
                    .append("\"containingSymbol\":").append(JsonUtil.quote(containingSymbolFor(span))).append(',')
                    .append("\"snippet\":").append(JsonUtil.quote(snippetFor(span)))
                    .append("}");
        }
        return builder.append("]").toString();
    }

    /**
     * The slash-separated name-path of the smallest enclosing method/type declaration containing {@code span}
     * (e.g. {@code OrderController/createOrder}), or "" when none can be located.
     */
    private String containingSymbolFor(IdentifierSpan span) {
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                if (!file.equals(span.file().toAbsolutePath().normalize())) {
                    continue;
                }
                String[] result = {""};
                long offset = span.startOffset();
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void scan(Tree tree, Void unused) {
                        if (tree == null) {
                            return null;
                        }
                        long start = task.positions.getStartPosition(unit, tree);
                        long end = task.positions.getEndPosition(unit, tree);
                        if (start <= offset && offset <= end) {
                            if (tree instanceof MethodTree method) {
                                result[0] = appendName(result[0], method.getName().toString());
                            } else if (tree instanceof ClassTree clazz && !clazz.getSimpleName().isEmpty()) {
                                result[0] = appendName(result[0], clazz.getSimpleName().toString());
                            }
                            return super.scan(tree, unused);
                        }
                        return null;
                    }

                    private String appendName(String current, String name) {
                        return current.isEmpty() ? name : current + "/" + name;
                    }
                }.scan(unit, null);
                return result[0];
            }
        }
        return "";
    }

    /** The full source line text containing {@code span} (trimmed of the trailing line break), or "" when unavailable. */
    private String snippetFor(IdentifierSpan span) {
        CharSequence source = null;
        Path normalized = span.file().toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            source = task.sourceByPath.get(normalized);
            if (source != null) {
                break;
            }
        }
        if (source == null) {
            return "";
        }
        int offset = (int) span.startOffset();
        if (offset > source.length()) {
            return "";
        }
        int start = offset;
        while (start > 0 && source.charAt(start - 1) != '\n') {
            start--;
        }
        int end = offset;
        while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
            end++;
        }
        return source.subSequence(start, end).toString();
    }

    /**
     * Scans references to any element in {@code targets} across every compiler task (home + secondaries), deduplicated
     * and ordered by file/offset. Matching is by {@link SemanticKey#canonical()} string, not Element identity, because
     * Element objects are not comparable across separate compiler tasks: the target's canonical keys are computed from
     * the home task, and each candidate node in each task is resolved via THAT task's {@code Trees}/{@code Types} and
     * compared by canonical key. Declaration-location keys (locals/params) embed the home file+offset, so they only ever
     * match within the home task — which is correct, since those targets are method-scoped.
     */
    List<IdentifierSpan> scanReferences(java.util.Collection<? extends Element> targets) {
        Set<String> targetKeys = new LinkedHashSet<>();
        for (Element target : targets) {
            targetKeys.add(canonicalKeyInHome(target));
        }
        return scanReferencesForKeys(targetKeys);
    }

    /**
     * Like {@link #scanReferences(java.util.Collection)} for renaming an override/implementation {@code group}, but also
     * rewrites override declarations that live in OTHER source sets. The home {@code overrideGroup} only contains the
     * home task's members (so its elements stay usable by the home-task conflict/library checks); a method overridden by
     * a declaration in a secondary source set (e.g. a test subclass overriding a main method) is a distinct method with
     * its own canonical key, so we widen the target key set across all tasks via {@link #crossTaskOverrideKeys}.
     */
    public List<IdentifierSpan> scanReferencesForRename(java.util.Collection<? extends Element> group) {
        return scanReferencesForKeys(renameTargetKeys(group));
    }

    /**
     * The canonical key set used to rename an override/implementation {@code group} (home keys plus cross-task override
     * keys for methods), exposed so the Javadoc scanner can match {@code @link}/{@code @see} references to the same
     * logical members the code-reference scan rewrites.
     */
    public Set<String> renameTargetKeys(java.util.Collection<? extends Element> group) {
        Set<String> targetKeys = new LinkedHashSet<>();
        for (Element target : group) {
            targetKeys.add(canonicalKeyInHome(target));
            if (target instanceof ExecutableElement method && method.getKind() == ElementKind.METHOD) {
                targetKeys.addAll(crossTaskOverrideKeys(method));
            }
        }
        return targetKeys;
    }

    private List<IdentifierSpan> scanReferencesForKeys(Set<String> targetKeys) {
        List<IdentifierSpan> references = new ArrayList<>();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                CharSequence source = task.sourceByPath.get(file.toAbsolutePath().normalize());
                if (source == null) {
                    continue;
                }
                new ReferenceScanner(task, targetKeys, file, unit, source, references).scan(unit, null);
            }
        }
        return dedupeAndSort(references);
    }

    /** The canonical key of an element resolved in the home task (using the home task's trees/types/unit/file). */
    private String canonicalKeyInHome(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null) {
            return SemanticKey.from(element).canonical();
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        return SemanticKey.from(element, trees, types, unit, pathOf(unit)).canonical();
    }

    /**
     * Returns the override/implementation group for a method target: the method plus every project method that it
     * overrides (in supertypes) or that overrides it (in subtypes), as the connected component over the "overrides"
     * relation. Non-methods, and static/private methods that cannot participate in overriding, return a singleton.
     */
    public List<Element> overrideGroup(Element target) {
        if (!(target instanceof ExecutableElement method) || method.getKind() != ElementKind.METHOD) {
            return List.of(target);
        }
        // Returned as home-task elements so the downstream conflict/library checks (which use the home task's
        // Elements/Types) stay sound. Cross-source-set override declarations (resolvable only in another task) are folded
        // in by scanReferences via crossTaskOverrideKeys, so their reference/declaration spans are still rewritten.
        return new ArrayList<>(overrideGroupIn(home, method));
    }

    /**
     * Connected component of {@code method} over the "overrides" relation within a single compiler task, computed with
     * that task's own {@link Elements}. Returns a singleton when the method is not present in the task.
     */
    private LinkedHashSet<ExecutableElement> overrideGroupIn(CompilerTask task, ExecutableElement method) {
        List<ExecutableElement> candidates = new ArrayList<>();
        for (TypeElement type : task.projectTypes()) {
            for (Element enclosed : type.getEnclosedElements()) {
                if (enclosed instanceof ExecutableElement candidate
                        && candidate.getKind() == ElementKind.METHOD
                        && candidate.getSimpleName().equals(method.getSimpleName())
                        && candidate.getParameters().size() == method.getParameters().size()) {
                    candidates.add(candidate);
                }
            }
        }
        LinkedHashSet<ExecutableElement> group = new LinkedHashSet<>();
        Deque<ExecutableElement> queue = new ArrayDeque<>();
        group.add(method);
        queue.add(method);
        while (!queue.isEmpty()) {
            ExecutableElement current = queue.poll();
            for (ExecutableElement candidate : candidates) {
                if (group.contains(candidate)) {
                    continue;
                }
                if (overrideRelatedIn(task, current, candidate)) {
                    group.add(candidate);
                    queue.add(candidate);
                }
            }
        }
        return group;
    }

    /**
     * Canonical keys of the method's override group across EVERY task. Within each secondary task we re-seed the BFS from
     * the methods whose canonical key matches the home group's keys (the same method is visible in that task too, because
     * the home set's roots are on its {@code -sourcepath}), then expand to discover overrides that live only in that task
     * (e.g. a test subclass overriding a main method). The home group's own keys are included.
     */
    private Set<String> crossTaskOverrideKeys(ExecutableElement method) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (ExecutableElement member : overrideGroupIn(home, method)) {
            keys.add(canonicalKeyInHome(member));
        }
        for (CompilerTask task : secondaryTasks) {
            for (TypeElement type : task.projectTypes()) {
                for (Element enclosed : type.getEnclosedElements()) {
                    if (enclosed instanceof ExecutableElement candidate
                            && candidate.getKind() == ElementKind.METHOD
                            && keys.contains(task.canonicalKey(candidate))) {
                        for (ExecutableElement member : overrideGroupIn(task, candidate)) {
                            keys.add(task.canonicalKey(member));
                        }
                    }
                }
            }
        }
        return keys;
    }

    public boolean methodParticipatesInOverrideHierarchy(SemanticMethod method) {
        if (method == null || !(method.element() instanceof ExecutableElement executable)
                || executable.getKind() != ElementKind.METHOD) {
            return false;
        }
        return crossTaskOverrideKeys(executable).size() > 1;
    }

    public boolean checkedExceptionsCompatible(SemanticMethod method, SemanticCallSite site) {
        if (method == null || site == null) {
            return false;
        }
        javax.lang.model.type.ExecutableType memberType = method.memberType();
        if (memberType == null || memberType.getThrownTypes().isEmpty()) {
            return true;
        }
        TreePath invocationPath = callSitePath(site);
        if (invocationPath == null) {
            return false;
        }
        CompilerTask task = taskFor(site.file());
        if (task == null) {
            return false;
        }
        for (TypeMirror thrown : memberType.getThrownTypes()) {
            if (!isUncheckedException(task, thrown) && !isHandledAtCallSite(task, invocationPath, thrown)) {
                return false;
            }
        }
        return true;
    }

    CompilerTask taskFor(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            if (task.sourceByPath.containsKey(normalized)) {
                return task;
            }
        }
        return null;
    }

    private TreePath callSitePath(SemanticCallSite site) {
        Path normalized = site.file().toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                if (!normalized.equals(pathOf(unit))) {
                    continue;
                }
                TreePath[] match = new TreePath[1];
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        long start = task.positions.getStartPosition(unit, node);
                        long end = task.positions.getEndPosition(unit, node);
                        if (start == site.invocationRange().start() && end == site.invocationRange().end()) {
                            match[0] = getCurrentPath();
                        }
                        return match[0] == null ? super.visitMethodInvocation(node, unused) : null;
                    }
                }.scan(unit, null);
                if (match[0] != null) {
                    return match[0];
                }
            }
        }
        return null;
    }

    private boolean isUncheckedException(CompilerTask task, TypeMirror thrown) {
        TypeMirror erased = task.types.erasure(thrown);
        TypeElement runtimeException = task.elements.getTypeElement("java.lang.RuntimeException");
        TypeElement error = task.elements.getTypeElement("java.lang.Error");
        return (runtimeException != null && task.types.isAssignable(erased, task.types.erasure(runtimeException.asType())))
                || (error != null && task.types.isAssignable(erased, task.types.erasure(error.asType())));
    }

    private boolean isHandledAtCallSite(CompilerTask task, TreePath invocationPath, TypeMirror thrown) {
        TypeMirror erased = task.types.erasure(thrown);
        TreePath child = invocationPath;
        for (TreePath current = invocationPath.getParentPath(); current != null; current = current.getParentPath()) {
            Tree leaf = current.getLeaf();
            if (leaf instanceof TryTree tryTree && tryBodyContains(tryTree, child.getLeaf())) {
                for (CatchTree catchTree : tryTree.getCatches()) {
                    TreePath catchTypePath = new TreePath(new TreePath(current, catchTree), catchTree.getParameter().getType());
                    TypeMirror catchType = task.trees.getTypeMirror(catchTypePath);
                    if (catchesThrownType(task, erased, catchType)) {
                        return true;
                    }
                }
            }
            if (leaf instanceof MethodTree) {
                Element element = task.trees.getElement(current);
                if (element instanceof ExecutableElement executable) {
                    for (TypeMirror declared : executable.getThrownTypes()) {
                        if (task.types.isAssignable(erased, task.types.erasure(declared))) {
                            return true;
                        }
                    }
                }
                return false;
            }
            if (leaf instanceof LambdaExpressionTree) {
                return false;
            }
            child = current;
        }
        return false;
    }

    private boolean tryBodyContains(TryTree tryTree, Tree child) {
        if (child == tryTree.getBlock()) {
            return true;
        }
        for (Tree resource : tryTree.getResources()) {
            if (resource == child) {
                return true;
            }
        }
        return false;
    }

    private boolean catchesThrownType(CompilerTask task, TypeMirror thrown, TypeMirror catchType) {
        if (catchType == null) {
            return false;
        }
        if (catchType instanceof javax.lang.model.type.UnionType unionType) {
            for (TypeMirror alternative : unionType.getAlternatives()) {
                if (task.types.isAssignable(thrown, task.types.erasure(alternative))) {
                    return true;
                }
            }
            return false;
        }
        return task.types.isAssignable(thrown, task.types.erasure(catchType));
    }

    /**
     * Whether the method (or any project method in its override group) overrides a method declared outside the project
     * (e.g. a JDK or dependency type). Such renames cannot be performed safely because the external declaration cannot
     * be changed, so the rename would silently break the override relationship.
     */
    public boolean overridesLibraryMethod(List<Element> group) {
        Set<TypeElement> projectTypes = new LinkedHashSet<>(projectTypes());
        for (Element element : group) {
            if (!(element instanceof ExecutableElement method) || !(method.getEnclosingElement() instanceof TypeElement owner)) {
                continue;
            }
            for (TypeElement superType : supertypeElements(owner)) {
                if (projectTypes.contains(superType)) {
                    continue;
                }
                for (Element enclosed : superType.getEnclosedElements()) {
                    if (!(enclosed instanceof ExecutableElement superMethod) || superMethod.getKind() != ElementKind.METHOD) {
                        continue;
                    }
                    // Precise check, plus a conservative same-name/arity fallback: a library supertype method with the
                    // same name and parameter count is treated as a potential override we cannot rewrite, so we refuse
                    // rather than risk shipping a preview that silently breaks the external override contract.
                    if (elements.overrides(method, superMethod, owner)
                            || (superMethod.getSimpleName().equals(method.getSimpleName())
                                    && superMethod.getParameters().size() == method.getParameters().size())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether the element is a record component or the backing field of a record component. javac may resolve a record
     * header component to its backing FIELD depending on context; record-component rename treats both as the same
     * atomic symbol surface.
     */
    public boolean isRecordComponentBackedField(Element element) {
        return recordComponentOwner(element) != null;
    }

    /** Returns every editable semantic key that must move with a record component rename. */
    public Set<String> recordComponentRenameTargetKeys(Element element) {
        TypeElement owner = recordComponentOwner(element);
        if (owner == null) {
            return Set.of();
        }
        String ownerName = owner.getQualifiedName().toString();
        String componentName = element.getSimpleName().toString();
        int componentIndex = recordComponentIndex(owner, componentName);
        if (componentIndex < 0) {
            return Set.of();
        }

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (CompilerTask task : allTasks()) {
            TypeElement taskOwner = task.elements.getTypeElement(ownerName);
            if (taskOwner == null) {
                continue;
            }
            for (Element enclosed : taskOwner.getEnclosedElements()) {
                if (enclosed.getSimpleName().contentEquals(componentName)
                        && (enclosed.getKind() == ElementKind.RECORD_COMPONENT
                                || enclosed.getKind().isField()
                                || (enclosed instanceof ExecutableElement executable
                                        && executable.getKind() == ElementKind.METHOD
                                        && executable.getParameters().isEmpty()))) {
                    keys.add(task.canonicalKey(enclosed));
                }
            }
            for (Element enclosed : taskOwner.getEnclosedElements()) {
                if (enclosed instanceof ExecutableElement executable
                        && executable.getKind() == ElementKind.CONSTRUCTOR
                        && executable.getParameters().size() == taskOwner.getRecordComponents().size()
                        && componentIndex < executable.getParameters().size()) {
                    VariableElement parameter = executable.getParameters().get(componentIndex);
                    if (parameter.getSimpleName().contentEquals(componentName)) {
                        keys.add(task.canonicalKey(parameter));
                    }
                }
            }
        }
        return keys;
    }

    /** Scans every source set for record-component declaration, backing-field, accessor, constructor-parameter, and use spans. */
    public List<IdentifierSpan> scanReferencesForRecordComponentRename(Element element) {
        return scanReferencesForKeys(recordComponentRenameTargetKeys(element));
    }

    /** The project-editable parameter declarations that correspond to {@code parameter} across its method hierarchy. */
    public List<Element> parameterRenameTargets(Element parameter) {
        if (parameter.getKind() != ElementKind.PARAMETER
                || !(parameter.getEnclosingElement() instanceof ExecutableElement method)
                || method.getKind() != ElementKind.METHOD) {
            return List.of(parameter);
        }
        int parameterIndex = method.getParameters().indexOf(parameter);
        if (parameterIndex < 0) {
            return List.of(parameter);
        }

        LinkedHashMap<String, Element> targets = new LinkedHashMap<>();
        for (Element member : overrideGroup(method)) {
            if (member instanceof ExecutableElement executable && parameterIndex < executable.getParameters().size()) {
                VariableElement candidate = executable.getParameters().get(parameterIndex);
                targets.put(canonicalKeyForAnyTask(candidate), candidate);
            }
        }

        Set<String> methodKeys = crossTaskOverrideKeys(method);
        for (CompilerTask task : allTasks()) {
            for (TypeElement type : task.projectTypes()) {
                for (Element enclosed : type.getEnclosedElements()) {
                    if (enclosed instanceof ExecutableElement candidate
                            && candidate.getKind() == ElementKind.METHOD
                            && parameterIndex < candidate.getParameters().size()
                            && methodKeys.contains(task.canonicalKey(candidate))) {
                        VariableElement parameterCandidate = candidate.getParameters().get(parameterIndex);
                        targets.put(task.canonicalKey(parameterCandidate), parameterCandidate);
                    }
                }
            }
        }
        return new ArrayList<>(targets.values());
    }

    /** Semantic keys for every project-editable corresponding parameter in a hierarchy-safe parameter rename. */
    public Set<String> parameterRenameTargetKeys(Element parameter) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Element target : parameterRenameTargets(parameter)) {
            keys.add(canonicalKeyForAnyTask(target));
        }
        return keys;
    }

    /** Scans every source set for declaration and use spans of corresponding hierarchy parameters. */
    public List<IdentifierSpan> scanReferencesForParameterRename(Element parameter) {
        return scanReferencesForKeys(parameterRenameTargetKeys(parameter));
    }

    /** Whether parameter rename would require editing an external/library declaration that is not in the project. */
    public boolean parameterRenameTouchesExternalHierarchy(Element parameter) {
        if (parameter.getKind() != ElementKind.PARAMETER
                || !(parameter.getEnclosingElement() instanceof ExecutableElement method)
                || method.getKind() != ElementKind.METHOD) {
            return false;
        }
        return overridesLibraryMethod(overrideGroup(method));
    }

    /**
     * Whether {@code parameter}'s enclosing method participates in an override/implementation hierarchy. Returns true
     * when the enclosing executable is an interface or {@code abstract} method, or when it overrides / is overridden by
     * another project or library method; false for a private/static/final standalone method with no override relation.
     *
     * <p>Used by safe-delete, where removing a parameter from one declaration changes the method's arity and would
     * therefore break sibling overrides.
     */
    public boolean parameterParticipatesInHierarchy(Element parameter) {
        if (parameter.getKind() != ElementKind.PARAMETER
                || !(parameter.getEnclosingElement() instanceof ExecutableElement method)
                || method.getKind() != ElementKind.METHOD) {
            return false;
        }
        Set<javax.lang.model.element.Modifier> modifiers = method.getModifiers();
        if (method.getEnclosingElement() instanceof TypeElement owner
                && (owner.getKind() == ElementKind.INTERFACE || owner.getKind() == ElementKind.ANNOTATION_TYPE)) {
            return true;
        }
        if (modifiers.contains(javax.lang.model.element.Modifier.ABSTRACT)) {
            return true;
        }
        List<Element> group = overrideGroup(method);
        if (group.size() > 1) {
            return true;
        }
        if (overridesLibraryMethod(group)) {
            return true;
        }
        // A method overridden by a declaration in a secondary source set (e.g. a test subclass) does not enlarge the
        // home-task override group, so widen the check across all tasks via the cross-task key set.
        return crossTaskOverrideKeys(method).size() > 1;
    }

    private TypeElement recordComponentOwner(Element element) {
        if (element.getKind() == ElementKind.RECORD_COMPONENT && element.getEnclosingElement() instanceof TypeElement owner) {
            return owner;
        }
        if (element.getKind().isField()
                && element.getEnclosingElement() instanceof TypeElement owner
                && owner.getKind() == ElementKind.RECORD
                && recordComponentIndex(owner, element.getSimpleName().toString()) >= 0) {
            return owner;
        }
        return null;
    }

    private int recordComponentIndex(TypeElement owner, String componentName) {
        List<? extends Element> components = owner.getRecordComponents();
        for (int i = 0; i < components.size(); i++) {
            if (components.get(i).getSimpleName().contentEquals(componentName)) {
                return i;
            }
        }
        return -1;
    }

    private String canonicalKeyForAnyTask(Element element) {
        for (CompilerTask task : allTasks()) {
            if (task.trees.getPath(element) != null) {
                return task.canonicalKey(element);
            }
        }
        return canonicalKeyInHome(element);
    }

    /** Declaration-name spans of every constructor declared directly in {@code type} (for constructor-via-type rename). */
    public List<IdentifierSpan> constructorDeclarationSpans(TypeElement type) {
        List<IdentifierSpan> spans = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement constructor) || constructor.getKind() != ElementKind.CONSTRUCTOR) {
                continue;
            }
            TreePath path = trees.getPath(constructor);
            if (path == null) {
                continue;
            }
            CompilationUnitTree unit = path.getCompilationUnit();
            Path file = pathOf(unit);
            CharSequence source = sourceByPath.get(file.toAbsolutePath().normalize());
            if (source == null) {
                continue;
            }
            IdentifierSpan span = spanFinder.find(file, unit, positions, path.getLeaf(), constructor, source);
            if (span != null) {
                spans.add(span);
            }
        }
        return spans;
    }

    /**
     * Semantic name-collision detection for a rename. Returns a human-readable reason when applying {@code newName}
     * would create a duplicate member or shadow/clash with an existing one, or null when no collision is detected.
     */
    public String detectRenameConflict(Element target, List<Element> group, String newName) {
        if (target instanceof ExecutableElement method && method.getKind() == ElementKind.METHOD) {
            for (Element member : group) {
                ExecutableElement grouped = (ExecutableElement) member;
                TypeElement owner = (TypeElement) grouped.getEnclosingElement();
                if (declaresMethodCollision(owner, grouped, newName)) {
                    return "Type " + owner.getQualifiedName() + " already declares a method '" + newName
                            + "' with the same parameter types.";
                }
                // Same-NAME, same-ARITY overload (but a different signature): the rename would merge the method into an
                // existing overload set with the same parameter count, where Java overload resolution could change which
                // method a call site binds to (or become ambiguous). The same-erasure case above is a hard duplicate;
                // this catches the broader resolution-changing case the plan requires. javac alone cannot be trusted
                // here because a changed-but-still-applicable dispatch compiles silently.
                ExecutableElement ambiguous = declaresOverloadAmbiguity(owner, grouped, newName);
                if (ambiguous != null) {
                    return "Type " + owner.getQualifiedName() + " already declares an overload '" + newName
                            + "' with the same number of parameters; renaming could change which overload call sites "
                            + "resolve to. Choose a name with no same-arity overload.";
                }
                for (TypeElement superType : supertypeElements(owner)) {
                    if (declaresMethodCollision(superType, grouped, newName)) {
                        return "Renaming to '" + newName + "' would collide with an inherited method of the same "
                                + "signature from " + superType.getQualifiedName() + ".";
                    }
                    if (declaresOverloadAmbiguity(superType, grouped, newName) != null) {
                        return "Renaming to '" + newName + "' would form a same-arity overload with an inherited method "
                                + "from " + superType.getQualifiedName() + ", which could change overload resolution.";
                    }
                }
            }
            return null;
        }
        if (target.getKind() == ElementKind.FIELD || target.getKind() == ElementKind.ENUM_CONSTANT) {
            if (target.getEnclosingElement() instanceof TypeElement owner) {
                // Duplicate member: the owner already declares a field/enum constant with the new name.
                if (declaresFieldNamed(owner, newName)) {
                    return "Type " + owner.getQualifiedName() + " already declares a field '" + newName + "'.";
                }
                // Inherited-field hiding / super-visible conflict: a supertype declares an accessible (non-private) field
                // with the new name. Renaming would make the renamed field HIDE the inherited one, silently rebinding
                // unqualified or `super.`-qualified accesses (which still compile). javac post-validation cannot catch
                // this because the result is valid-but-different code, so it must be a pre-edit conflict.
                for (TypeElement superType : supertypeElements(owner)) {
                    if (declaresAccessibleFieldNamed(superType, newName)) {
                        return "Renaming to '" + newName + "' would hide an inherited field of the same name from "
                                + superType.getQualifiedName() + "; unqualified or 'super.' accesses could silently rebind. "
                                + "Choose a name that is not visible from a supertype.";
                    }
                }
            }
            // Staged binding-change check: renaming the field rewrites its unqualified reference sites to newName; if a
            // local/parameter named newName is in scope at any such site, the rewritten token would bind to that variable
            // instead of the field (a silent rebind that still compiles).
            return detectFieldRenameRebind(target, newName);
        }
        if (target.getKind() == ElementKind.LOCAL_VARIABLE || target.getKind() == ElementKind.PARAMETER
                || target.getKind() == ElementKind.RESOURCE_VARIABLE) {
            if (siblingVariableNamed(target, newName)) {
                return "A local variable or parameter named '" + newName + "' already exists in the same scope.";
            }
            // Staged binding-change check: a same-named local/parameter declaration is caught above, but an existing
            // reference to a FIELD (or an outer-scope variable) named newName that lies inside the renamed variable's
            // scope would silently rebind to the renamed variable. javac cannot catch this (the result still compiles),
            // so it must be a pre-edit refusal.
            return detectLocalRenameRebind(target, newName);
        }
        if (target instanceof TypeElement type) {
            if (siblingTypeNamed(type, newName)) {
                return "A sibling type named '" + newName + "' already exists in the same scope.";
            }
            return null;
        }
        return null;
    }

    /**
     * Runs the method rename conflict checks against every override declaration that lives in a SECONDARY source-set
     * task (e.g. a test subclass overriding a main method). The home-task group is already checked by
     * {@link #detectRenameConflict}; this widens the same duplicate-member, same-arity-overload, and inherited-conflict
     * analysis to each other declaration the cross-task rename will rewrite, evaluated with that task's own
     * {@link Elements}/{@link Types} so inherited-context resolution is sound. Returns the first conflict reason found,
     * or null. A non-method target has no cross-task override declarations and returns null.
     */
    public String detectCrossTaskRenameConflict(Element target, String newName) {
        if (!(target instanceof ExecutableElement method) || method.getKind() != ElementKind.METHOD) {
            return null;
        }
        Set<String> renameKeys = crossTaskOverrideKeys(method);
        for (CompilerTask task : secondaryTasks) {
            for (TypeElement type : task.projectTypes()) {
                for (Element enclosed : type.getEnclosedElements()) {
                    if (enclosed instanceof ExecutableElement candidate
                            && candidate.getKind() == ElementKind.METHOD
                            && renameKeys.contains(task.canonicalKey(candidate))) {
                        String conflict = detectMethodRenameConflictIn(task, candidate, newName);
                        if (conflict != null) {
                            return conflict;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Method rename duplicate/overload/inherited conflict checks for a single declaration, scoped to {@code task}. */
    private String detectMethodRenameConflictIn(CompilerTask task, ExecutableElement method, String newName) {
        if (!(method.getEnclosingElement() instanceof TypeElement owner)) {
            return null;
        }
        if (declaresMethodCollisionIn(task, owner, method, newName)) {
            return "Type " + owner.getQualifiedName() + " already declares a method '" + newName
                    + "' with the same parameter types.";
        }
        if (declaresOverloadAmbiguityIn(task, owner, method, newName) != null) {
            return "Type " + owner.getQualifiedName() + " already declares an overload '" + newName
                    + "' with the same number of parameters; renaming could change which overload call sites resolve "
                    + "to. Choose a name with no same-arity overload.";
        }
        for (TypeElement superType : supertypeElementsIn(task, owner)) {
            if (declaresMethodCollisionIn(task, superType, method, newName)) {
                return "Renaming to '" + newName + "' would collide with an inherited method of the same signature from "
                        + superType.getQualifiedName() + ".";
            }
            if (declaresOverloadAmbiguityIn(task, superType, method, newName) != null) {
                return "Renaming to '" + newName + "' would form a same-arity overload with an inherited method from "
                        + superType.getQualifiedName() + ", which could change overload resolution.";
            }
        }
        return null;
    }

    private static boolean declaresMethodCollisionIn(CompilerTask task, TypeElement owner, ExecutableElement original, String newName) {
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement other
                    && other.getKind() == ElementKind.METHOD
                    && !other.equals(original)
                    && other.getSimpleName().contentEquals(newName)
                    && sameParameterTypesIn(task, other, original)) {
                return true;
            }
        }
        return false;
    }

    private static ExecutableElement declaresOverloadAmbiguityIn(CompilerTask task, TypeElement owner, ExecutableElement original, String newName) {
        int arity = original.getParameters().size();
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement other
                    && other.getKind() == ElementKind.METHOD
                    && !other.equals(original)
                    && other.getSimpleName().contentEquals(newName)
                    && other.getParameters().size() == arity
                    && !sameParameterTypesIn(task, other, original)) {
                return other;
            }
        }
        return null;
    }

    private static boolean sameParameterTypesIn(CompilerTask task, ExecutableElement left, ExecutableElement right) {
        if (left.getParameters().size() != right.getParameters().size()) {
            return false;
        }
        for (int i = 0; i < left.getParameters().size(); i++) {
            if (!task.types.isSameType(task.types.erasure(left.getParameters().get(i).asType()),
                    task.types.erasure(right.getParameters().get(i).asType()))) {
                return false;
            }
        }
        return true;
    }

    private static Set<TypeElement> supertypeElementsIn(CompilerTask task, TypeElement type) {
        Set<TypeElement> result = new LinkedHashSet<>();
        Deque<TypeElement> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            TypeElement current = queue.poll();
            for (javax.lang.model.type.TypeMirror supertype : task.types.directSupertypes(current.asType())) {
                Element element = task.types.asElement(supertype);
                if (element instanceof TypeElement superType && result.add(superType)) {
                    queue.add(superType);
                }
            }
        }
        return result;
    }

    private boolean declaresMethodCollision(TypeElement owner, ExecutableElement original, String newName) {
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement other
                    && other.getKind() == ElementKind.METHOD
                    && !other.equals(original)
                    && other.getSimpleName().contentEquals(newName)
                    && sameParameterTypes(other, original)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns an existing method in {@code owner} that would form a same-name, same-arity overload with {@code original}
     * under {@code newName} (excluding the exact same-erasure duplicate handled by {@link #declaresMethodCollision},
     * and excluding the original itself and its overrides). Such a method shares the parameter count, so renaming could
     * change overload resolution at call sites. Returns null when no such overload exists.
     */
    private ExecutableElement declaresOverloadAmbiguity(TypeElement owner, ExecutableElement original, String newName) {
        int arity = original.getParameters().size();
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement other
                    && other.getKind() == ElementKind.METHOD
                    && !other.equals(original)
                    && other.getSimpleName().contentEquals(newName)
                    && other.getParameters().size() == arity
                    && !sameParameterTypes(other, original)) {
                return other;
            }
        }
        return null;
    }

    private boolean sameParameterTypes(ExecutableElement left, ExecutableElement right) {
        if (left.getParameters().size() != right.getParameters().size()) {
            return false;
        }
        for (int i = 0; i < left.getParameters().size(); i++) {
            if (!types.isSameType(types.erasure(left.getParameters().get(i).asType()), types.erasure(right.getParameters().get(i).asType()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether two resolved methods share the same erased parameter-type signature — the rule the Java language uses to
     * decide an overload clash (JLS §8.4.2: two methods with the same erasure cannot coexist). This is the canonical
     * semantic-signature comparison; cross-package planners (e.g. member moves) MUST use it instead of comparing
     * {@code asType().toString()} against parsed source text, which fails on simple-vs-FQN names, generic instantiation
     * vs erasure, and annotation/formatting differences. Returns false for a different arity (a legal overload).
     */
    public boolean sameErasedParameterTypes(ExecutableElement left, ExecutableElement right) {
        if (left == null || right == null) {
            return false;
        }
        return sameParameterTypes(left, right);
    }

    /** Erased same-type comparison of two resolved type mirrors (the per-parameter rule behind {@link #sameErasedParameterTypes}). */
    public boolean isSameErasedType(javax.lang.model.type.TypeMirror left, javax.lang.model.type.TypeMirror right) {
        if (left == null || right == null) {
            return false;
        }
        return types.isSameType(types.erasure(left), types.erasure(right));
    }

    private static boolean declaresFieldNamed(TypeElement owner, String name) {
        for (Element enclosed : owner.getEnclosedElements()) {
            if ((enclosed.getKind() == ElementKind.FIELD || enclosed.getKind() == ElementKind.ENUM_CONSTANT)
                    && enclosed.getSimpleName().contentEquals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code owner} declares a field/enum constant with {@code name} that is INHERITED by subtypes — i.e. not
     * {@code private} (private fields are not inherited and so cannot be hidden by a subtype's same-named field). Used to
     * detect inherited-field hiding when renaming a subtype field.
     */
    private static boolean declaresAccessibleFieldNamed(TypeElement owner, String name) {
        for (Element enclosed : owner.getEnclosedElements()) {
            if ((enclosed.getKind() == ElementKind.FIELD || enclosed.getKind() == ElementKind.ENUM_CONSTANT)
                    && enclosed.getSimpleName().contentEquals(name)
                    && !enclosed.getModifiers().contains(Modifier.PRIVATE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lexical-scope-accurate local/parameter rename conflict check. A rename to {@code name} conflicts only when some
     * OTHER local/parameter with that name has a scope that OVERLAPS the target's scope — i.e. one is visible where the
     * other is declared (same block, or one nested inside the other). Two variables in DISJOINT sibling blocks do NOT
     * conflict, so a valid rename into a name used only in a disjoint nested block is allowed (the old whole-method name
     * scan over-refused these). A variable's scope runs from its declaration to the end of its nearest enclosing scope
     * construct (block, for/enhanced-for, try-with-resources, catch, lambda body, or method for a parameter).
     */
    private boolean siblingVariableNamed(Element target, String name) {
        TreePath targetPath = trees.getPath(target);
        Element method = target.getEnclosingElement();
        while (method != null && !(method instanceof ExecutableElement)) {
            method = method.getEnclosingElement();
        }
        if (method == null) {
            return false;
        }
        TreePath methodPath = trees.getPath(method);
        if (methodPath == null || targetPath == null) {
            return false;
        }
        CompilationUnitTree unit = methodPath.getCompilationUnit();
        SourcePositions positions = trees.getSourcePositions();
        long[] targetScope = scopeRange(targetPath, unit, positions);
        if (targetScope == null) {
            // Could not locate the target's scope precisely; fall back to the conservative whole-method name scan.
            VariableNameScanner scanner = new VariableNameScanner(name, target);
            scanner.scan(methodPath, null);
            return scanner.found;
        }
        ScopeOverlapScanner scanner = new ScopeOverlapScanner(name, target, unit, positions, targetScope);
        scanner.scan(methodPath, null);
        return scanner.conflict;
    }

    /** The source range [declStart, scopeEnd) of a variable declaration: from its start to the end of its enclosing scope. */
    private static long[] scopeRange(TreePath declPath, CompilationUnitTree unit, SourcePositions positions) {
        Tree scopeTree = enclosingScopeTree(declPath);
        if (scopeTree == null) {
            return null;
        }
        long declStart = positions.getStartPosition(unit, declPath.getLeaf());
        long scopeEnd = positions.getEndPosition(unit, scopeTree);
        if (declStart < 0 || scopeEnd < 0) {
            return null;
        }
        return new long[]{declStart, scopeEnd};
    }

    /** The nearest enclosing lexical-scope construct of a declaration (block, loop, try, catch, lambda, or method). */
    private static Tree enclosingScopeTree(TreePath declPath) {
        for (TreePath parent = declPath.getParentPath(); parent != null; parent = parent.getParentPath()) {
            Tree leaf = parent.getLeaf();
            if (leaf instanceof BlockTree || leaf instanceof ForLoopTree || leaf instanceof EnhancedForLoopTree
                    || leaf instanceof CatchTree || leaf instanceof LambdaExpressionTree || leaf instanceof TryTree
                    || leaf instanceof MethodTree) {
                return leaf;
            }
        }
        return null;
    }

    private static boolean scopesOverlap(long[] a, long[] b) {
        return a[0] < b[1] && b[0] < a[1];
    }

    /**
     * Staged binding-change check for a local/parameter/resource-variable rename. Renaming the variable to
     * {@code newName} is unsafe when, inside the variable's lexical scope, an existing identifier {@code newName} already
     * resolves to a DIFFERENT symbol visible from an enclosing scope (a field, or an outer local/parameter): after the
     * rename that unqualified reference would silently rebind to the renamed variable while still compiling. References
     * to a more deeply nested {@code newName} declaration are NOT affected (the inner declaration keeps winning), and the
     * same-scope local/parameter collision is already handled by {@link #siblingVariableNamed}. Returns a human-readable
     * reason, or null when no rebinding reference is found.
     */
    private String detectLocalRenameRebind(Element target, String newName) {
        TreePath targetPath = trees.getPath(target);
        if (targetPath == null) {
            return null;
        }
        CompilationUnitTree unit = targetPath.getCompilationUnit();
        long[] scope = scopeRange(targetPath, unit, positions);
        if (scope == null) {
            return null;
        }
        // A reference inside the declaration's own initializer (before the variable enters scope) binds to the outer
        // symbol and stays bound to it, so the scan starts at the END of the declaration, not its start.
        long declEnd = positions.getEndPosition(unit, targetPath.getLeaf());
        long scanStart = declEnd >= 0 ? declEnd : scope[0];
        LocalRenameRebindScanner scanner = new LocalRenameRebindScanner(newName, target, unit, scanStart, scope[1]);
        scanner.scan(new TreePath(unit), null);
        return scanner.conflict;
    }

    /**
     * Staged binding-change check for a field/enum-constant rename. Scans every unqualified reference to the field; if a
     * local variable or parameter named {@code newName} is in scope at such a site, the reference (rewritten to
     * {@code newName} by the rename) would bind to that variable instead of the field — a silent rebind. Returns a
     * human-readable reason, or null when no shadowing variable is in scope at any reference.
     */
    private String detectFieldRenameRebind(Element target, String newName) {
        for (CompilationUnitTree unit : units) {
            FieldRenameRebindScanner scanner = new FieldRenameRebindScanner(target, newName);
            scanner.scan(new TreePath(unit), null);
            if (scanner.conflict != null) {
                return scanner.conflict;
            }
        }
        return null;
    }

    /** Whether {@code element} is a value-binding variable (local, parameter, resource, catch, or pattern binding). */
    private static boolean isVariableElement(Element element) {
        ElementKind kind = element.getKind();
        return kind == ElementKind.LOCAL_VARIABLE || kind == ElementKind.PARAMETER
                || kind == ElementKind.RESOURCE_VARIABLE || kind == ElementKind.EXCEPTION_PARAMETER
                || kind == ElementKind.BINDING_VARIABLE;
    }

    /**
     * The nearest visible local variable/parameter named {@code name} at {@code path}, or null when none is in scope.
     * Fields and types are ignored: only a value-binding variable can shadow a field reference. Defensive against scope
     * resolution failures on malformed/partial sources (returns null rather than throwing).
     */
    private Element visibleVariableNamed(TreePath path, String name) {
        try {
            for (Scope scope = trees.getScope(path); scope != null; scope = scope.getEnclosingScope()) {
                for (Element local : scope.getLocalElements()) {
                    if (isVariableElement(local) && local.getSimpleName().contentEquals(name)) {
                        return local;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Scope resolution can fail on malformed/partial sources; treat as no shadowing.
        }
        return null;
    }

    /**
     * HB-7: whether an unqualified reference to {@code name} at {@code selection}'s site would bind to a LOCAL value
     * binding (local variable, method/lambda parameter, pattern/record binding, resource variable, catch parameter, or
     * for-loop variable) rather than to a field. The decision uses javac's lexical {@link Scope} at the selection's
     * resolved {@link TreePath} (via {@link #visibleVariableNamed}), so it is exact for implicitly-typed lambda
     * parameters, {@code instanceof}/{@code switch} pattern bindings, try-with-resources variables, and nested-scope
     * shadowing, and is never fooled by declaration-like text inside a comment or string literal. When the selection
     * site cannot be resolved it returns false (no shadowing assumed; the caller then emits the unqualified name).
     */
    public boolean selectionNameBindsToLocal(SemanticExpressionSelection selection, String name) {
        if (selection == null || selection.range() == null || name == null || name.isEmpty()) {
            return false;
        }
        TreePath path = pathAtOffset(selection.file(), selection.range().start());
        return path != null && visibleVariableNamed(path, name) != null;
    }

    // ── HB-8: scope-aware unique-name generation for synthesized extract-method locals/types ──────────

    /**
     * HB-8: whether {@code candidate} is a safe simple name for a NEW local variable introduced at
     * {@code callSiteOffset} inside {@code enclosingExecutable}. A name is free only when it is BOTH (a) not bound to any
     * visible local/parameter/pattern/resource/catch binding at the call site (lexical-scope shadowing, via javac
     * {@link Scope}), AND (b) not declared anywhere in the enclosing executable's body — a later same-block or
     * sibling-block re-declaration of the same name would clash with the newly introduced holder just as a prior one
     * would. The body scan walks every {@link VariableTree} reachable from the executable (parameters, locals, for/loop
     * variables, try-with-resources variables, catch parameters, and pattern bindings). When the call site or executable
     * cannot be resolved the method conservatively returns {@code false} so the caller falls back to suffixing.
     */
    public boolean localNameFreeAt(Path file, int callSiteOffset, Element enclosingExecutable, String candidate) {
        if (file == null || candidate == null || candidate.isEmpty()) {
            return false;
        }
        TreePath path = pathAtOffset(file, callSiteOffset);
        if (path == null || visibleVariableNamed(path, candidate) != null) {
            return false;
        }
        return !scopeDeclaresVariableNamed(path, enclosingExecutable, candidate);
    }

    /**
     * HB-8: a guaranteed-free simple name for a new local at {@code callSiteOffset}, starting from {@code base} and
     * appending {@code 2}, {@code 3}, … until {@link #localNameFreeAt} holds. Returns {@code null} when no free name is
     * found within {@code maxAttempts} (the caller then refuses rather than emitting a clashing edit).
     */
    public String uniqueLocalName(Path file, int callSiteOffset, Element enclosingExecutable, String base, int maxAttempts) {
        if (localNameFreeAt(file, callSiteOffset, enclosingExecutable, base)) {
            return base;
        }
        for (int suffix = 2; suffix < 2 + maxAttempts; suffix++) {
            String candidate = base + suffix;
            if (localNameFreeAt(file, callSiteOffset, enclosingExecutable, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * HB-8: whether {@code candidate} is a safe simple name for a NEW member or nested type added to the type that
     * declares {@code enclosingExecutable}. A name is free only when that {@link TypeElement} declares no enclosed
     * element (field, method, constructor, enum constant, or nested type) with the same simple name — so a synthesized
     * {@code <Name>Result}/{@code <Name>Signal} record cannot collide with an existing nested type or member. When the
     * enclosing type cannot be resolved the method conservatively returns {@code false}.
     */
    public boolean typeMemberNameFree(Element enclosingExecutable, String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        TypeElement owner = enclosingTypeOf(enclosingExecutable);
        if (owner == null) {
            return false;
        }
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed.getSimpleName().contentEquals(candidate)) {
                return false;
            }
        }
        return true;
    }

    /**
     * HB-8: a guaranteed-free simple name for a new nested type/member of the type declaring {@code enclosingExecutable},
     * starting from {@code base} and appending {@code 2}, {@code 3}, … until {@link #typeMemberNameFree} holds. Returns
     * {@code null} when no free name is found within {@code maxAttempts}.
     */
    public String uniqueTypeMemberName(Element enclosingExecutable, String base, int maxAttempts) {
        if (typeMemberNameFree(enclosingExecutable, base)) {
            return base;
        }
        for (int suffix = 2; suffix < 2 + maxAttempts; suffix++) {
            String candidate = base + suffix;
            if (typeMemberNameFree(enclosingExecutable, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The {@link TypeElement} that directly declares {@code executable}, or {@code null} when not resolvable. */
    private static TypeElement enclosingTypeOf(Element executable) {
        for (Element current = executable; current != null; current = current.getEnclosingElement()) {
            if (current != executable && current instanceof TypeElement type) {
                return type;
            }
        }
        return null;
    }

    /** Whether the body of {@code executable} declares any {@link VariableTree} (param/local/binding) named {@code name}. */
    /**
     * Whether the enclosing method/initializer body declares any variable named {@code name} (HB-8 body-wide collision
     * half of {@link #localNameFreeAt}). Prefers the resolved {@code executable}'s method body; when that is absent or
     * not a method body (extract-method inside a class/instance initializer block or a field initializer, where the
     * enclosing executable does not resolve to a {@link MethodTree}), it falls back to the nearest enclosing method or
     * class-initializer block found from the call-site path, so the body-wide check still runs there instead of being
     * silently skipped. A field-initializer scope with no enclosing block returns false (the lexical-scope check in
     * {@link #localNameFreeAt} already covered the only names that could bind there).
     */
    private boolean scopeDeclaresVariableNamed(TreePath callSitePath, Element executable, String name) {
        Tree scope = null;
        if (executable != null) {
            TreePath execPath = trees.getPath(executable);
            if (execPath != null && execPath.getLeaf() instanceof MethodTree) {
                scope = execPath.getLeaf();
            }
        }
        if (scope == null) {
            for (TreePath cursor = callSitePath; cursor != null; cursor = cursor.getParentPath()) {
                Tree leaf = cursor.getLeaf();
                TreePath parent = cursor.getParentPath();
                if (leaf instanceof MethodTree
                        || (leaf instanceof BlockTree && parent != null && parent.getLeaf() instanceof ClassTree)) {
                    scope = leaf;
                    break;
                }
            }
        }
        if (scope == null) {
            return false;
        }
        boolean[] found = {false};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree variable, Void unused) {
                if (variable.getName() != null && variable.getName().contentEquals(name)) {
                    found[0] = true;
                }
                return super.visitVariable(variable, unused);
            }
        }.scan(scope, null);
        return found[0];
    }

    /** The deepest tree whose javac source range contains {@code offset} in {@code file}, as a {@link TreePath}, or null. */
    private TreePath pathAtOffset(Path file, int offset) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilationUnitTree unit : units) {
            if (!pathOf(unit).equals(normalized)) {
                continue;
            }
            TreePath[] best = {null};
            new TreePathScanner<Void, Void>() {
                @Override
                public Void scan(Tree tree, Void unused) {
                    if (tree != null) {
                        long start = positions.getStartPosition(unit, tree);
                        long end = positions.getEndPosition(unit, tree);
                        if (start >= 0 && end >= start && start <= offset && offset < end) {
                            best[0] = new TreePath(getCurrentPath() == null ? new TreePath(unit) : getCurrentPath(), tree);
                        }
                    }
                    return super.scan(tree, unused);
                }
            }.scan(new TreePath(unit), null);
            return best[0];
        }
        return null;
    }

    private boolean siblingTypeNamed(TypeElement type, String name) {
        Element enclosing = type.getEnclosingElement();
        if (enclosing instanceof TypeElement enclosingType) {
            for (Element enclosed : enclosingType.getEnclosedElements()) {
                if (enclosed instanceof TypeElement sibling && !sibling.equals(type) && sibling.getSimpleName().contentEquals(name)) {
                    return true;
                }
            }
            return false;
        }
        // Top-level type: a sibling top-level type with the new name in the same package.
        String packageName = elements.getPackageOf(type).getQualifiedName().toString();
        for (TypeElement candidate : projectTypes()) {
            if (candidate.equals(type) || !(candidate.getEnclosingElement() instanceof javax.lang.model.element.PackageElement)) {
                continue;
            }
            if (elements.getPackageOf(candidate).getQualifiedName().contentEquals(packageName)
                    && candidate.getSimpleName().contentEquals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Import-collision check for a type rename. After the rename, every reference to {@code type} becomes the simple
     * name {@code newName}; in any file that references {@code type}, a pre-existing single-type import of a DIFFERENT
     * type whose simple name is {@code newName} would then clash (two distinct {@code NewName} types in scope). Returns a
     * human-readable reason for the first such collision, or null when none is found. Wildcard imports are not flagged
     * because they do not bind a specific simple name that the compiler would treat as a hard duplicate-import error.
     */
    public String detectImportCollision(TypeElement type, List<IdentifierSpan> references, String newName) {
        String renamedFqn = type.getQualifiedName().toString();
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (IdentifierSpan span : references) {
            files.add(span.file().toAbsolutePath().normalize());
        }
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                if (!files.contains(file)) {
                    continue;
                }
                for (com.sun.source.tree.ImportTree importTree : unit.getImports()) {
                    if (importTree.isStatic()) {
                        continue;
                    }
                    Tree qualified = importTree.getQualifiedIdentifier();
                    if (!(qualified instanceof com.sun.source.tree.MemberSelectTree memberSelect)
                            || !memberSelect.getIdentifier().contentEquals(newName)) {
                        continue;
                    }
                    String importedFqn = qualified.toString();
                    if (!importedFqn.equals(renamedFqn)) {
                        return "Renaming type to '" + newName + "' collides with an existing import '" + importedFqn
                                + "' in " + relativeTo(file) + ".";
                    }
                }
            }
        }
        return null;
    }

    private String relativeTo(Path file) {
        return model.projectRoot().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /**
     * Refuses a rename whose edits would land in a non-editable location: a file under any source set's generated
     * source root, or a file outside every (non-generated) source root of the project model. Such files are generated or
     * belong to a dependency/out-of-tree location and must not be rewritten. Returns a human-readable reason for the
     * first offending file, or null when every span is in an editable, non-generated source root.
     */
    public String detectNonEditableEditTarget(List<IdentifierSpan> spans, FileRenameView fileRename) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (IdentifierSpan span : spans) {
            files.add(span.file().toAbsolutePath().normalize());
        }
        if (fileRename != null) {
            files.add(model.projectRoot().resolve(fileRename.oldPath()).toAbsolutePath().normalize());
        }
        return detectNonEditableFiles(files);
    }

    /**
     * Centralized generated/dependency-source safety gate, shared by every mutating operation (rename, safe delete,
     * move, inline). Refuses if any affected file lies under a generated source root, or outside every (non-generated)
     * editable source root — i.e. generated code or a dependency/out-of-tree file that must not be rewritten or deleted.
     * Returns a human-readable reason for the first offending file, or null when every file is editable.
     *
     * @param files absolute, normalized paths of every file the operation would edit, create, delete, or rename.
     */
    public String detectNonEditableFiles(Collection<Path> files) {
        List<Path> generatedRoots = model.generatedSourceRoots();
        List<Path> editableRoots = model.sourceSets().stream()
                .flatMap(sourceSet -> sourceSet.sourceRoots().stream())
                .map(root -> root.toAbsolutePath().normalize())
                .filter(root -> generatedRoots.stream().noneMatch(root::startsWith))
                .toList();
        for (Path rawFile : files) {
            Path file = rawFile.toAbsolutePath().normalize();
            for (Path generatedRoot : generatedRoots) {
                if (file.startsWith(generatedRoot)) {
                    return "Refusing edit: operation would modify generated source under '" + relativeTo(generatedRoot)
                            + "' (" + relativeTo(file) + ").";
                }
            }
            if (!editableRoots.isEmpty() && editableRoots.stream().noneMatch(file::startsWith)) {
                return "Refusing edit: target '" + relativeTo(file)
                        + "' is outside the project's editable source roots.";
            }
        }
        return null;
    }

    /**
     * Centralized target-origin editability gate. Designed to run in every planner immediately after target resolution
     * and before any operation-specific logic, it rejects a resolved target whose declaration does not live in an
     * editable, transactionally-applicable project source file:
     * <ul>
     *   <li>a dependency or binary element with no source file at all (e.g. a JDK/library type known only from the
     *       classpath);</li>
     *   <li>a declaration outside the project root — an external source attachment or out-of-tree file that the
     *       transactional applier cannot safely rewrite;</li>
     *   <li>a generated source root, or a file outside the project's editable (non-generated) source roots (reuses
     *       {@link #detectNonEditableFiles}).</li>
     * </ul>
     * Returns a human-readable reason, or null when the target originates from an editable project source file.
     */
    public String targetOriginRefusal(ResolvedTarget target) {
        if (target == null) {
            return null;
        }
        // A binary/dependency element (a JDK or library symbol known only from the classpath) has no source tree, even
        // when the clicked span sits in an editable file. Detect it by the absence of a compiler source path for the
        // resolved element so it is refused before any op-specific logic treats the click's file as the edit origin.
        Element element = target.element();
        if (element != null && trees.getPath(element) == null) {
            return "Refusing edit: the target resolves to a dependency or binary element with no source declaration "
                    + "(it is known only from the classpath).";
        }
        Path file = target.span() == null ? null : target.span().file();
        if (file == null) {
            return "Refusing edit: the target resolves to a dependency or binary element with no editable source file "
                    + "(it is known only from the classpath).";
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(model.projectRoot())) {
            return "Refusing edit: the target's declaration '" + normalized + "' is outside the project root "
                    + "(an external source attachment or dependency source path) and is not transactionally editable.";
        }
        return detectNonEditableFiles(List.of(normalized));
    }

    /** Minimal view of a planned file rename for the non-editable-target check (avoids a planner-package dependency). */
    public record FileRenameView(String oldPath) {
    }

    /**
     * Fully-qualified names of types in {@code packageName} (excluding {@code exclude}) that are referenced from the
     * given file's compilation unit. Used by Move to add imports for former same-package siblings the moved file used
     * unqualified, which would otherwise fail to resolve once the file leaves the package.
     */
    public Set<String> referencedTypeFqnsInPackage(Path file, String packageName, Element exclude) {
        Optional<CompilationUnitTree> unitOpt = findUnit(file);
        if (unitOpt.isEmpty()) {
            return Set.of();
        }
        CompilationUnitTree unit = unitOpt.get();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null && isIdentifierBearing(tree)) {
                    Element element = trees.getElement(new TreePath(getCurrentPath(), tree));
                    if (element instanceof TypeElement type
                            && !type.equals(exclude)
                            && type.getNestingKind() == javax.lang.model.element.NestingKind.TOP_LEVEL
                            && elements.getPackageOf(type).getQualifiedName().contentEquals(packageName)) {
                        result.add(type.getQualifiedName().toString());
                    }
                }
                return super.scan(tree, unused);
            }
        }.scan(unit, null);
        return result;
    }

    /**
     * The single-type imports a top-level type declaration needs when EXTRACTED into a new file in {@code targetPackage}.
     * Scans only the moved declaration's own AST subtree for simple-name ({@link IdentifierTree}) type references and
     * resolves each to its element, so the new file carries exactly the imports the moved type uses — "import splitting":
     * imports used only by the siblings left behind are naturally excluded, and former same-package siblings the moved
     * type references become explicit {@code import oldPackage.Sibling;} entries (since the type leaves that package).
     * Skipped: the moved type itself and its nested types, {@code java.lang}, the destination package (visible without an
     * import), and the default package (its types cannot be imported). A simple name bound by a wildcard import is still
     * resolved to its concrete FQN, so the new file does not depend on the original's wildcard.
     */
    public Set<String> typeImportsForExtraction(Element movedType, String targetPackage) {
        TreePath declPath = trees.getPath(movedType);
        if (declPath == null) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element element = trees.getElement(getCurrentPath());
                if (element instanceof TypeElement type && !isEnclosedIn(type, movedType)) {
                    String pkg = elements.getPackageOf(type).getQualifiedName().toString();
                    if (!pkg.equals("java.lang") && !pkg.equals(targetPackage) && !pkg.isEmpty()) {
                        result.add(type.getQualifiedName().toString());
                    }
                }
                return super.visitIdentifier(node, unused);
            }
        }.scan(declPath, null);
        return result;
    }

    /**
     * The compiler-backed dependency surface a moved static member's body needs to compile in its new home (G007).
     *
     * <p>Unlike a regex import transplant, this walks the moved member's own declaration subtree with javac
     * {@code Trees}/{@code Types} and resolves every reference precisely:
     * <ul>
     *   <li>{@link MovedBodyDependencies#referencedTypeFqns()} — the fully-qualified names of every declared type the
     *       body references, descending through generic arguments, array/varargs components, and bounds via
     *       {@link io.serena.javarefactor.shared.ImportRewritePlanner#collectReferencedTypeNames(TypeMirror)}. Simple-name type usages (resolved to a
     *       {@link TypeElement}) and the declared types of variables/casts/new-expressions are both covered, so a name
     *       brought in by a single-type import, a wildcard import, or a same-package sibling is captured uniformly. Type
     *       variables, the moved member's own enclosing type, and its nested types are excluded.</li>
     *   <li>{@link MovedBodyDependencies#staticMemberRefs()} — every unqualified static method/field/enum-constant the
     *       body uses whose declaring type is NOT the source type (i.e. it resolves through a static import in the source
     *       file). Each carries the declaring type FQN and the member simple name so the target can reproduce the static
     *       import.</li>
     * </ul>
     *
     * <p>Degrades to empty when the member element/path/tree cannot be resolved with the available model.
     *
     * @param member the moved static member element (an {@code ExecutableElement} or field {@code VariableElement})
     * @param sourceType the source type the member is being moved out of (excluded as a self-dependency)
     */
    public MovedBodyDependencies movedStaticBodyDependencies(Element member, TypeElement sourceType) {
        if (member == null) {
            return new MovedBodyDependencies(Set.of(), List.of());
        }
        CompilerTask task = taskFor(declaringFile(member));
        if (task == null) {
            return new MovedBodyDependencies(Set.of(), List.of());
        }
        TreePath declPath = task.trees.getPath(member);
        if (declPath == null) {
            return new MovedBodyDependencies(Set.of(), List.of());
        }
        LinkedHashSet<String> typeFqns = new LinkedHashSet<>();
        LinkedHashSet<StaticMemberRef> staticRefs = new LinkedHashSet<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                collect(getCurrentPath());
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                collect(getCurrentPath());
                return super.visitMemberReference(node, unused);
            }

            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null) {
                    TreePath path = new TreePath(getCurrentPath() == null ? declPath : getCurrentPath(), tree);
                    TypeMirror mirror = safeTypeMirror(task, path);
                    if (mirror != null) {
                        addExternalTypes(mirror);
                    }
                }
                return super.scan(tree, unused);
            }

            private void collect(TreePath path) {
                Element resolved = task.trees.getElement(path);
                if (resolved == null) {
                    return;
                }
                if (resolved instanceof TypeElement type) {
                    addExternalType(type);
                    return;
                }
                if (isStaticMemberRef(resolved, path)) {
                    Element owner = resolved.getEnclosingElement();
                    // A statically-imported member may be owned by a NESTED type (e.g. `import static a.Outer.Inner.C;`);
                    // its owner's canonical qualified name (`a.Outer.Inner`) is the importable prefix. Only local and
                    // anonymous owners have no importable name (empty getQualifiedName) and cannot back a static import.
                    if (owner instanceof TypeElement ownerType
                            && !ownerType.equals(sourceType)
                            && !ownerType.getQualifiedName().toString().isEmpty()) {
                        staticRefs.add(new StaticMemberRef(
                                ownerType.getQualifiedName().toString(), resolved.getSimpleName().toString()));
                    }
                }
            }

            private boolean isStaticMemberRef(Element resolved, TreePath path) {
                if (!resolved.getModifiers().contains(Modifier.STATIC)) {
                    return false;
                }
                ElementKind kind = resolved.getKind();
                if (kind != ElementKind.METHOD && kind != ElementKind.FIELD && kind != ElementKind.ENUM_CONSTANT) {
                    return false;
                }
                // Only UNQUALIFIED uses (a bare identifier, or a member reference whose qualifier is the type itself)
                // come in through a static import; a qualified `Type.member` use already carries its own type reference,
                // which the type-collection path above handles.
                Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
                if (parent instanceof MemberSelectTree) {
                    return false;
                }
                return true;
            }

            private void addExternalTypes(TypeMirror mirror) {
                for (String fqn : io.serena.javarefactor.shared.ImportRewritePlanner.collectReferencedTypeNames(mirror)) {
                    addExternalTypeFqn(fqn);
                }
            }

            private void addExternalType(TypeElement type) {
                if (type.getNestingKind() != javax.lang.model.element.NestingKind.TOP_LEVEL) {
                    // resolve a nested type usage to its top-level encloser so the import names an importable type
                    Element enclosing = type;
                    while (enclosing instanceof TypeElement enclosingType
                            && enclosingType.getNestingKind() != javax.lang.model.element.NestingKind.TOP_LEVEL) {
                        enclosing = enclosingType.getEnclosingElement();
                    }
                    if (enclosing instanceof TypeElement topLevel) {
                        addExternalTypeFqn(topLevel.getQualifiedName().toString());
                    }
                    return;
                }
                addExternalTypeFqn(type.getQualifiedName().toString());
            }

            private void addExternalTypeFqn(String fqn) {
                if (fqn == null || fqn.isBlank() || !fqn.contains(".")) {
                    return;
                }
                if (sourceType != null) {
                    String sourceFqn = sourceType.getQualifiedName().toString();
                    if (fqn.equals(sourceFqn) || fqn.startsWith(sourceFqn + ".")) {
                        return; // the moved member's own enclosing type (or its nested types) is not a dependency
                    }
                }
                typeFqns.add(fqn);
            }
        }.scan(declPath, null);
        return new MovedBodyDependencies(typeFqns, new ArrayList<>(staticRefs));
    }

    /** {@link Trees#getTypeMirror} that swallows the resolver exceptions javac throws for non-expression trees. */
    private static TypeMirror safeTypeMirror(CompilerTask task, TreePath path) {
        try {
            return task.trees.getTypeMirror(path);
        } catch (RuntimeException | Error ignored) {
            return null;
        }
    }

    /**
     * Whether {@code referenceFile} still references any static member of {@code sourceType} OTHER than the moved member,
     * proving whether an {@code import static sourceType.*;} in that file is still needed (G007). This replaces the
     * source-side member-count heuristic with a real per-file usage proof: the file's own AST is scanned for unqualified
     * identifiers/member-references that resolve to a static method/field/enum-constant declared by {@code sourceType}
     * whose simple name is not {@code movedMemberName}. When none remain, the wildcard is provably unused in that file and
     * may be removed; otherwise it is preserved.
     *
     * @param referenceFile the file whose static wildcard import is being evaluated
     * @param sourceType the type the moved member is leaving
     * @param movedMemberName the simple name of the moved member (its own uses do not keep the wildcard alive)
     */
    public boolean fileStillUsesOtherStaticMembers(Path referenceFile, TypeElement sourceType, String movedMemberName) {
        if (sourceType == null) {
            return false;
        }
        CompilerTask task = taskFor(referenceFile);
        if (task == null) {
            return true; // cannot prove staleness: conservatively keep the wildcard
        }
        Optional<CompilationUnitTree> unitOpt = task.units.stream()
                .filter(unit -> pathOf(unit).equals(referenceFile.toAbsolutePath().normalize()))
                .findFirst();
        if (unitOpt.isEmpty()) {
            return true;
        }
        boolean[] used = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                check(getCurrentPath());
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                check(getCurrentPath());
                return super.visitMemberReference(node, unused);
            }

            private void check(TreePath path) {
                if (used[0]) {
                    return;
                }
                Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
                if (parent instanceof MemberSelectTree) {
                    return; // a qualified Type.member use does not rely on the wildcard static import
                }
                Element resolved = task.trees.getElement(path);
                if (resolved == null || !resolved.getModifiers().contains(Modifier.STATIC)) {
                    return;
                }
                ElementKind kind = resolved.getKind();
                if (kind != ElementKind.METHOD && kind != ElementKind.FIELD && kind != ElementKind.ENUM_CONSTANT) {
                    return;
                }
                if (resolved.getSimpleName().contentEquals(movedMemberName)) {
                    return;
                }
                if (sourceType.equals(resolved.getEnclosingElement())) {
                    used[0] = true;
                }
            }
        }.scan(unitOpt.get(), null);
        return used[0];
    }

    /**
     * Initializer-order safety analysis for relocating a NON-constant static field (Blocker 1). A compile-time constant
     * carries no class-initialization-order dependency, but a mutable / reference-typed / non-final static field's
     * initialization timing is observable, so moving it to another type is unsafe ONLY when its initialization is
     * entangled with other static-initialization ordering. Returns the FIRST proven coupling (so the planner can refuse
     * with a precise reason) or a safe verdict. Proven-unsafe cases:
     * <ul>
     *   <li>the field's initializer reads another non-constant static field, or invokes a static method, of the SOURCE
     *       type — its value/side effect is tied to the source type's static-init order, which the move would change;</li>
     *   <li>the field's initializer reads a non-constant static field, or invokes a static method, of the TARGET type —
     *       the move would introduce a new initialization-order coupling at the destination;</li>
     *   <li>a {@code static { ... }} block of the SOURCE type, or another static field initializer of the SOURCE type,
     *       reads or writes the moved field — the field participates in the source type's static-init ordering.</li>
     * </ul>
     * A field whose initializer depends only on constants and self-contained expressions, read/written only by ordinary
     * (non-static-init) code, is reported safe; its references elsewhere are rewritten by the move planner. Degrades to
     * safe when the model cannot resolve the field/types (the planner still runs its other gates).
     */
    public StaticFieldMoveSafety analyzeStaticFieldMoveSafety(Element fieldElement, TypeElement sourceType, TypeElement targetType) {
        if (!(fieldElement instanceof VariableElement field) || sourceType == null) {
            return StaticFieldMoveSafety.allowed();
        }
        CompilerTask task = taskFor(declaringFile(fieldElement));
        if (task == null) {
            return StaticFieldMoveSafety.allowed();
        }
        TreePath fieldPath = task.trees.getPath(fieldElement);
        if (fieldPath == null || !(fieldPath.getLeaf() instanceof VariableTree fieldTree)) {
            return StaticFieldMoveSafety.allowed();
        }
        String fieldName = field.getSimpleName().toString();
        if (fieldTree.getInitializer() != null) {
            StaticFieldMoveSafety initializerVerdict = analyzeStaticInitializerCoupling(
                    task, new TreePath(fieldPath, fieldTree.getInitializer()), field, sourceType, targetType, fieldName);
            if (!initializerVerdict.safe()) {
                return initializerVerdict;
            }
        }
        TreePath sourceTypePath = task.trees.getPath(sourceType);
        if (sourceTypePath != null && sourceTypePath.getLeaf() instanceof ClassTree sourceClass) {
            return analyzeSourceStaticInitEntanglement(task, sourceTypePath, sourceClass, field, fieldName);
        }
        return StaticFieldMoveSafety.allowed();
    }

    /** Scans the moved field's initializer for a static-init-order dependency on the source or target type (cases A/B). */
    private StaticFieldMoveSafety analyzeStaticInitializerCoupling(
            CompilerTask task, TreePath initializerPath, VariableElement movedField,
            TypeElement sourceType, TypeElement targetType, String fieldName) {
        StaticFieldMoveSafety[] verdict = {StaticFieldMoveSafety.allowed()};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                check(getCurrentPath());
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                check(getCurrentPath());
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                check(getCurrentPath());
                return super.visitMemberReference(node, unused);
            }

            private void check(TreePath path) {
                if (!verdict[0].safe()) {
                    return;
                }
                Element resolved = task.trees.getElement(path);
                if (resolved == null || resolved.equals(movedField) || !resolved.getModifiers().contains(Modifier.STATIC)) {
                    return;
                }
                ElementKind kind = resolved.getKind();
                boolean isField = kind == ElementKind.FIELD || kind == ElementKind.ENUM_CONSTANT;
                boolean isMethod = kind == ElementKind.METHOD;
                if (!isField && !isMethod) {
                    return;
                }
                Element owner = resolved.getEnclosingElement();
                String coupledType;
                if (sourceType.equals(owner)) {
                    coupledType = "source";
                } else if (targetType != null && targetType.equals(owner)) {
                    coupledType = "target";
                } else {
                    return;
                }
                // A compile-time constant field carries no class-initialization-order dependency.
                if (isField && resolved instanceof VariableElement constant && constant.getConstantValue() != null) {
                    return;
                }
                String memberDesc = resolved.getSimpleName() + (isMethod ? "(...)" : "");
                verdict[0] = StaticFieldMoveSafety.refused(
                        "static_field_initializer_order_coupling",
                        "moveStaticMember refuses static field '" + fieldName + "': its initializer depends on the " + coupledType
                                + " type's static member '" + memberDesc + "', whose value or side effect is tied to the "
                                + coupledType + " type's class-initialization order, so moving the field would change when it "
                                + "initializes relative to that member. Move or inline the dependency first, or keep the field "
                                + "in place.");
            }
        }.scan(initializerPath, null);
        return verdict[0];
    }

    /** Scans the source type's static blocks and sibling static field initializers for a read/write of the moved field (case C). */
    private StaticFieldMoveSafety analyzeSourceStaticInitEntanglement(
            CompilerTask task, TreePath classPath, ClassTree sourceClass, VariableElement movedField, String fieldName) {
        StaticFieldMoveSafety[] verdict = {StaticFieldMoveSafety.allowed()};
        for (Tree member : sourceClass.getMembers()) {
            if (!verdict[0].safe()) {
                break;
            }
            TreePath memberPath = new TreePath(classPath, member);
            TreePath scanRoot;
            String context;
            if (member instanceof BlockTree block && block.isStatic()) {
                scanRoot = memberPath;
                context = "a static initializer block";
            } else if (member instanceof VariableTree variable && variable.getInitializer() != null) {
                Element varElement = task.trees.getElement(memberPath);
                if (varElement == null || varElement.equals(movedField) || !varElement.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                scanRoot = new TreePath(memberPath, variable.getInitializer());
                context = "the static field '" + variable.getName() + "' initializer";
            } else {
                continue;
            }
            final String ctx = context;
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitIdentifier(IdentifierTree node, Void unused) {
                    check(getCurrentPath());
                    return super.visitIdentifier(node, unused);
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                    check(getCurrentPath());
                    return super.visitMemberSelect(node, unused);
                }

                private void check(TreePath path) {
                    if (!verdict[0].safe()) {
                        return;
                    }
                    if (movedField.equals(task.trees.getElement(path))) {
                        verdict[0] = StaticFieldMoveSafety.refused(
                                "static_field_initializer_order_coupling",
                                "moveStaticMember refuses static field '" + fieldName + "': " + ctx + " in the source type reads "
                                        + "or writes it, so the field participates in the source type's static-initialization "
                                        + "order, and moving it to another type would change when it initializes. Keep the field "
                                        + "in place or move the dependent static initialization with it.");
                    }
                }
            }.scan(scanRoot, null);
        }
        return verdict[0];
    }

    /** The source file declaring {@code element}, via its declaration path; {@code null} when unresolved. */
    private Path declaringFile(Element element) {
        TreePath path = trees.getPath(element);
        if (path != null && path.getCompilationUnit() != null) {
            return pathOf(path.getCompilationUnit());
        }
        for (CompilerTask task : allTasks()) {
            TreePath taskPath = task.trees.getPath(element);
            if (taskPath != null && taskPath.getCompilationUnit() != null) {
                return pathOf(taskPath.getCompilationUnit());
            }
        }
        return null;
    }

    /** A static member referenced (unqualified) by a moved body: its declaring type FQN and the member simple name. */
    public record StaticMemberRef(String declaringTypeFqn, String memberName) {
        /** The {@code import static} target: {@code declaringTypeFqn.memberName}. */
        public String qualifiedMember() {
            return declaringTypeFqn + "." + memberName;
        }
    }

    /** The compiler-resolved dependency surface of a moved member's body: referenced types and static member uses. */
    public record MovedBodyDependencies(Set<String> referencedTypeFqns, List<StaticMemberRef> staticMemberRefs) {
    }

    /**
     * The verdict of {@link #analyzeStaticFieldMoveSafety}: {@code safe} when no proven initializer-order coupling was
     * found; otherwise {@code code}/{@code message} carry the refusal reason for the first coupling detected.
     */
    public record StaticFieldMoveSafety(boolean safe, String code, String message) {
        public static StaticFieldMoveSafety allowed() {
            return new StaticFieldMoveSafety(true, null, null);
        }

        public static StaticFieldMoveSafety refused(String code, String message) {
            return new StaticFieldMoveSafety(false, code, message);
        }
    }

    /** Whether {@code element} is {@code ancestor} or is (transitively) enclosed by it. */
    private static boolean isEnclosedIn(Element element, Element ancestor) {
        for (Element e = element; e != null; e = e.getEnclosingElement()) {
            if (e.equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code file} references {@code type} by a simple name ({@link IdentifierTree}) at a position OUTSIDE the
     * half-open {@code [excludeStart, excludeEnd)} span. Used by the extraction move to decide whether the original file
     * (which keeps its remaining sibling declarations) needs an {@code import targetPackage.MovedType;} after the moved
     * type leaves the package; the moved declaration's own self-references fall inside the excluded span and are ignored.
     */
    public boolean referencesTypeBySimpleNameOutside(Path file, TypeElement type, int excludeStart, int excludeEnd) {
        Optional<CompilationUnitTree> unitOpt = findUnit(file);
        if (unitOpt.isEmpty()) {
            return false;
        }
        CompilationUnitTree unit = unitOpt.get();
        boolean[] found = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                if (!found[0]) {
                    long start = positions.getStartPosition(unit, node);
                    if (start < excludeStart || start >= excludeEnd) {
                        Element element = trees.getElement(getCurrentPath());
                        if (element != null && element.equals(type)) {
                            found[0] = true;
                        }
                    }
                }
                return super.visitIdentifier(node, unused);
            }
        }.scan(unit, null);
        return found[0];
    }

    /**
     * Classification of a single move-relevant reference to the moved type, anchored on AST positions (never a substring
     * scan), so a longer-named sibling such as {@code com.old.FooBar} is never touched when moving {@code com.old.Foo}.
     *
     * <ul>
     *   <li>{@code IMPORT} — a {@code import com.old.Foo;} or {@code import static com.old.Foo.X;} whose package
     *       qualifier span is {@code [start,end)} (the {@code com.old} prefix of the qualified import). The planner
     *       rewrites the qualifier to the new package, or removes the whole import line when the importing file ends up
     *       in the new package.</li>
     *   <li>{@code QUALIFIED_FQN} — a fully-qualified usage {@code com.old.Foo} (also {@code new com.old.Foo()} /
     *       {@code com.old.Foo.CONST}); {@code [start,end)} is the {@code com.old} package-qualifier span of the
     *       {@link MemberSelectTree}, rewritten to the new package.</li>
     *   <li>{@code SIMPLE} — a simple-name usage relying on an import / same-package visibility; no qualifier edit is
     *       emitted (the import edit, or a newly inserted import, covers it). {@code [start,end)} is the simple-name
     *       span and is informational only.</li>
     * </ul>
     */
    public enum MoveReferenceKind { IMPORT, QUALIFIED_FQN, SIMPLE }

    /** A classified move reference: its file, kind, the rewritable span {@code [start,end)}, and whether the import is static. */
    public record MoveReference(Path file, MoveReferenceKind kind, int start, int end, boolean staticImport, boolean importRemovable) {
    }

    /**
     * Classifies every project reference to {@code movedType} (across all source sets) for the move, deriving exact edit
     * spans from the compiler's reference data rather than a raw text {@code indexOf}. {@code newPackage} is used only to
     * decide whether an import becomes obsolete (the importing file already lives in / moves to the destination package).
     * The moved file itself is skipped (its own package/imports are handled by the planner's outbound pass).
     */
    public List<MoveReference> classifyMoveReferences(TypeElement movedType, Path movedFile, String oldPackage, String newPackage) {
        String targetKey = canonicalKeyInHome(movedType);
        Path normalizedMovedFile = movedFile.toAbsolutePath().normalize();
        List<MoveReference> result = new ArrayList<>();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                if (file.equals(normalizedMovedFile)) {
                    continue;
                }
                CharSequence source = task.sourceByPath.get(file.toAbsolutePath().normalize());
                if (source == null) {
                    continue;
                }
                String filePackage = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                new MoveReferenceScanner(task, targetKey, file, unit, filePackage, oldPackage, newPackage, result).scan(unit, null);
            }
        }
        return result;
    }

    /**
     * Scans one compilation unit for references to the moved type and records classified {@link MoveReference}s. Imports
     * are matched on the {@link ImportTree} (the qualified-identifier sub-expression that resolves to the moved type);
     * fully-qualified usages are matched on the {@link MemberSelectTree} whose element is the moved type; simple-name
     * usages are matched on the {@link IdentifierTree} whose element is the moved type.
     */
    private final class MoveReferenceScanner extends TreePathScanner<Void, Void> {
        private final CompilerTask task;
        private final String targetKey;
        private final Path file;
        private final CompilationUnitTree unit;
        private final String filePackage;
        private final String oldPackage;
        private final String newPackage;
        private final List<MoveReference> out;

        private MoveReferenceScanner(CompilerTask task, String targetKey, Path file, CompilationUnitTree unit, String filePackage, String oldPackage, String newPackage, List<MoveReference> out) {
            this.task = task;
            this.targetKey = targetKey;
            this.file = file;
            this.unit = unit;
            this.filePackage = filePackage;
            this.oldPackage = oldPackage;
            this.newPackage = newPackage;
            this.out = out;
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            // The qualified identifier is `com.old.Foo` (type import) or `com.old.Foo.X` (static member import). Locate
            // the sub-expression that resolves to the moved type and rewrite (or drop) only its package qualifier.
            Tree qualified = node.getQualifiedIdentifier();
            MemberSelectTree typeSelect = importTypeSelect(node, qualified);
            if (typeSelect != null) {
                // typeSelect is `com.old.Foo`; its expression `com.old` is the package qualifier to rewrite/drop.
                ExpressionTree packageQualifier = typeSelect.getExpression();
                long qStart = task.positions.getStartPosition(unit, packageQualifier);
                long qEnd = task.positions.getEndPosition(unit, packageQualifier);
                if (qStart >= 0 && qEnd > qStart) {
                    // An import becomes obsolete (removable) when the importing file ends up in the moved type's NEW
                    // package: the type is then visible without an import. Whole-line removal is decided by the planner.
                    boolean obsolete = !node.isStatic() && filePackage.equals(newPackage);
                    out.add(new MoveReference(file, MoveReferenceKind.IMPORT, (int) qStart, (int) qEnd, node.isStatic(), obsolete));
                }
            }
            return super.visitImport(node, unused);
        }

        /** The {@code com.old.Foo} member-select inside an import whose element is the moved type, or null. */
        private MemberSelectTree importTypeSelect(ImportTree node, Tree qualified) {
            if (!(qualified instanceof MemberSelectTree memberSelect)) {
                return null;
            }
            if (node.isStatic()) {
                // `import static com.old.Foo.X;` -> the type `com.old.Foo` is the member-select's expression.
                if (memberSelect.getExpression() instanceof MemberSelectTree typeSelect && resolvesToTarget(typeSelect)) {
                    return typeSelect;
                }
                return null;
            }
            return resolvesToTarget(memberSelect) ? memberSelect : null;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            // A fully-qualified usage `com.old.Foo` (also the `com.old.Foo` inside `com.old.Foo.CONST` or
            // `new com.old.Foo()`): the node resolves to the moved type and its expression is a package qualifier. The
            // recorded span is the whole `com.old.Foo` member-select, replaced wholesale with the new FQN; the AST span
            // never overlaps a longer-named sibling such as `com.old.FooBar`.
            ExpressionTree qualifier = node.getExpression();
            if (!withinImport() && resolvesToTarget(node) && qualifier != null && qualifierIsPackage(qualifier)) {
                long start = task.positions.getStartPosition(unit, node);
                long end = task.positions.getEndPosition(unit, node);
                if (start >= 0 && end > start) {
                    out.add(new MoveReference(file, MoveReferenceKind.QUALIFIED_FQN, (int) start, (int) end, false, false));
                }
            }
            return super.visitMemberSelect(node, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            if (!withinImport() && resolvesToTarget(node)) {
                long start = task.positions.getStartPosition(unit, node);
                long end = task.positions.getEndPosition(unit, node);
                if (start >= 0 && end > start) {
                    out.add(new MoveReference(file, MoveReferenceKind.SIMPLE, (int) start, (int) end, false, false));
                }
            }
            return super.visitIdentifier(node, unused);
        }

        /** Whether the qualifier expression of a member-select is a (possibly nested) package name, not a value/type. */
        private boolean qualifierIsPackage(ExpressionTree qualifier) {
            Element element = task.trees.getElement(new TreePath(getCurrentPath(), qualifier));
            // The qualifier element is null for an unattributed package expression; a PACKAGE element confirms it. Either
            // way, a value/type qualifier (FIELD/METHOD/TYPE) is excluded so we never rewrite e.g. `Outer.Foo`.
            return element == null || element.getKind() == ElementKind.PACKAGE;
        }

        private boolean resolvesToTarget(Tree tree) {
            Element element = task.trees.getElement(new TreePath(getCurrentPath(), tree));
            if (!(element instanceof TypeElement)) {
                return false;
            }
            return targetKey.equals(SemanticKey.from(element, task.trees, task.types, unit, file).canonical());
        }

        /**
         * Whether the current node is inside an {@code import} declaration, whose member-select/identifier sub-nodes are
         * already handled by {@link #visitImport} as a single qualifier edit. Without this guard the qualified-FQN /
         * simple-name visitors would emit a second, overlapping edit on the same import line.
         */
        private boolean withinImport() {
            for (TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
                if (path.getLeaf() instanceof ImportTree) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Whether a top-level type with simple name {@code simpleName} already exists in {@code targetPackage} in ANY source
     * root / source set known to the project model (not merely whether the target file exists on disk). Used by Move to
     * refuse a destination collision even when the colliding type lives in a different source root or is not at the
     * conventional file path. {@code excludeFile} is the moved file itself, excluded so a same-name move is not self-blocked.
     */
    public boolean targetPackageHasType(String targetPackage, String simpleName, Path excludeFile) {
        Path normalizedExclude = excludeFile.toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            for (TypeElement type : task.projectTypes()) {
                if (type.getNestingKind() != javax.lang.model.element.NestingKind.TOP_LEVEL
                        || !type.getSimpleName().contentEquals(simpleName)) {
                    continue;
                }
                if (!task.elements.getPackageOf(type).getQualifiedName().contentEquals(targetPackage)) {
                    continue;
                }
                TreePath path = task.trees.getPath(type);
                if (path != null && pathOf(path.getCompilationUnit()).equals(normalizedExclude)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * AST/model-based module-info detection (plan section 10 "Module handling"): whether any {@code module-info.java}
     * compiled in the project declares an {@code exports} or {@code opens} directive for {@code packageName}. Parses the
     * module declaration via the compiler ({@link CompilationUnitTree#getModule()} / {@link ModuleTree} directives)
     * instead of scanning source text, so commented-out or string-literal directives are not misread. Returns the
     * directive keyword ("exports"/"opens") and the declaring file when found, or null otherwise.
     */
    public ModuleExportInfo moduleExportsPackage(String packageName) {
        if (packageName.isEmpty()) {
            return null;
        }
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                com.sun.source.tree.ModuleTree module = unit.getModule();
                if (module == null) {
                    continue;
                }
                Path file = pathOf(unit);
                for (com.sun.source.tree.DirectiveTree directive : module.getDirectives()) {
                    if (directive instanceof com.sun.source.tree.ExportsTree exports
                            && exports.getPackageName().toString().equals(packageName)) {
                        return new ModuleExportInfo("exports", file);
                    }
                    if (directive instanceof com.sun.source.tree.OpensTree opens
                            && opens.getPackageName().toString().equals(packageName)) {
                        return new ModuleExportInfo("opens", file);
                    }
                }
            }
        }
        return null;
    }

    /** The {@code exports}/{@code opens} directive keyword and the {@code module-info.java} declaring it, for a refusal. */
    public record ModuleExportInfo(String directive, Path moduleInfoFile) {
    }

    /**
     * AST/model-based module-info detection for type-referencing directives (plan section 10 "Module handling"): whether
     * any {@code module-info.java} compiled in the project declares a {@code uses} or {@code provides} directive that
     * references the type whose fully-qualified name is {@code typeFqn}. Unlike {@code exports}/{@code opens} (which name
     * packages), {@code uses}/{@code provides} directives name TYPES, so a move of the named service interface or a
     * provider implementation can leave a stale FQN in the descriptor. Matches FQNs precisely (a {@code uses a.b.C} must
     * equal {@code a.b.C}; a type merely sharing a package prefix is not matched). Parses directives via the compiler's
     * {@link com.sun.source.tree.ModuleTree} ({@link com.sun.source.tree.UsesTree} /
     * {@link com.sun.source.tree.ProvidesTree}), not a regex over source text. Returns the directive keyword
     * ("uses"/"provides") and the declaring file when found, or null otherwise.
     */
    public ModuleExportInfo moduleReferencesType(String typeFqn) {
        if (typeFqn == null || typeFqn.isEmpty()) {
            return null;
        }
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                com.sun.source.tree.ModuleTree module = unit.getModule();
                if (module == null) {
                    continue;
                }
                Path file = pathOf(unit);
                for (com.sun.source.tree.DirectiveTree directive : module.getDirectives()) {
                    if (directive instanceof com.sun.source.tree.UsesTree uses
                            && uses.getServiceName().toString().equals(typeFqn)) {
                        return new ModuleExportInfo("uses", file);
                    }
                    if (directive instanceof com.sun.source.tree.ProvidesTree provides) {
                        if (provides.getServiceName().toString().equals(typeFqn)) {
                            return new ModuleExportInfo("provides", file);
                        }
                        for (com.sun.source.tree.ExpressionTree impl : provides.getImplementationNames()) {
                            if (impl.toString().equals(typeFqn)) {
                                return new ModuleExportInfo("provides", file);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * AST-accurate source range of the {@code package ...;} declaration in {@code file}, or null when the file has no
     * package declaration. Anchored at the {@link PackageTree}'s start position (so leading comments or string literals
     * containing the text {@code package} cannot produce a false match) and extended forward from the AST end to include
     * the terminating {@code ';'}.
     */
    public int[] packageDeclarationRange(Path file) {
        Optional<CompilationUnitTree> unitOpt = findUnit(file);
        if (unitOpt.isEmpty()) {
            return null;
        }
        CompilationUnitTree unit = unitOpt.get();
        PackageTree packageTree = unit.getPackage();
        if (packageTree == null) {
            return null;
        }
        long start = positions.getStartPosition(unit, packageTree);
        // The package-name expression's end position is reliable (the whole PackageTree's end can extend to the next
        // sibling). Scan forward from the name to the terminating ';' to span the full declaration.
        long astEnd = positions.getEndPosition(unit, packageTree.getPackageName());
        if (start < 0 || astEnd < start) {
            return null;
        }
        CharSequence source = sourceByPath.get(file.toAbsolutePath().normalize());
        if (source == null) {
            return null;
        }
        int end = (int) astEnd;
        while (end < source.length() && source.charAt(end) != ';') {
            end++;
        }
        if (end >= source.length()) {
            return null;
        }
        return new int[]{(int) start, end + 1};
    }

    /**
     * Javadoc reference spans whose linked element resolves to a member of {@code targetKeys}. Uses {@code DocTrees} to
     * walk every declaration's doc comment and find {@code {@link Foo#bar}}, {@code {@linkplain ...}}, and {@code @see}
     * reference trees; for each, the referenced element is resolved with the owning task's {@code DocTrees} and matched by
     * {@link SemanticKey#canonical()} (Element identity is not comparable across tasks). When {@code paramName} is
     * non-null (a parameter rename), {@code @param paramName} tags on the enclosing executable's doc comment are also
     * rewritten. The emitted span covers only the simple-name token of the {@code #member}/type/param identifier.
     */
    public List<IdentifierSpan> javadocReferenceSpans(Set<String> targetKeys, String paramName, Element parameterTarget) {
        List<IdentifierSpan> spans = new ArrayList<>();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                CharSequence source = task.sourceByPath.get(file.toAbsolutePath().normalize());
                if (source == null) {
                    continue;
                }
                new DocReferenceScanner(task, task.docTrees, targetKeys, paramName, parameterTarget, file, unit, source, spans).scan(unit, null);
            }
        }
        return dedupeAndSort(spans);
    }

    /**
     * Whole-token textual occurrences of {@code oldName} inside line/block comments and string literals across every
     * file (identifier-boundary matched). These are heuristic, opt-in (include_comments) edits; the planner attaches a
     * warning that they are textual rather than semantic. Code occurrences are excluded by only scanning comment and
     * string-literal regions.
     */
    public List<IdentifierSpan> textualOccurrenceSpans(String oldName) {
        List<IdentifierSpan> spans = new ArrayList<>();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                CharSequence source = task.sourceByPath.get(file.toAbsolutePath().normalize());
                if (source == null) {
                    continue;
                }
                collectTextualOccurrences(file, unit, source, oldName, spans);
            }
        }
        return dedupeAndSort(spans);
    }

    private static List<IdentifierSpan> dedupeAndSort(List<IdentifierSpan> spans) {
        return spans.stream()
                .collect(Collectors.toMap(
                        span -> span.file() + ":" + span.startOffset() + ":" + span.endOffset(),
                        span -> span,
                        (left, ignored) -> left))
                .values()
                .stream()
                .sorted(Comparator.comparing((IdentifierSpan span) -> span.file().toString()).thenComparingLong(IdentifierSpan::startOffset))
                .toList();
    }

    /**
     * Scans comment and string-literal regions of {@code source} for whole-token occurrences of {@code oldName}. A
     * single forward pass classifies regions (line comment, block comment, string/char literal) using the same
     * comment/literal skipping rules as {@link IdentifierSpanFinder}, and within each comment/string region records
     * identifier-boundary matches of {@code oldName}.
     */
    private static void collectTextualOccurrences(Path file, CompilationUnitTree unit, CharSequence source, String oldName, List<IdentifierSpan> out) {
        int length = source.length();
        int i = 0;
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = i + 2;
                while (end < length && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
                    end++;
                }
                recordTokenMatches(file, unit, source, oldName, i + 2, end, out);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = i + 2;
                while (end + 1 < length && !(source.charAt(end) == '*' && source.charAt(end + 1) == '/')) {
                    end++;
                }
                recordTokenMatches(file, unit, source, oldName, i + 2, Math.min(end, length), out);
                i = Math.min(end + 2, length);
                continue;
            }
            if (c == '"' || c == '\'') {
                int bodyStart = i + 1;
                int end = bodyStart;
                while (end < length) {
                    char d = source.charAt(end);
                    if (d == '\\') {
                        end += 2;
                        continue;
                    }
                    if (d == c) {
                        break;
                    }
                    end++;
                }
                recordTokenMatches(file, unit, source, oldName, bodyStart, Math.min(end, length), out);
                i = Math.min(end + 1, length);
                continue;
            }
            i += Character.charCount(Character.codePointAt(source, i));
        }
    }

    private static void recordTokenMatches(Path file, CompilationUnitTree unit, CharSequence source, String oldName, int from, int to, List<IdentifierSpan> out) {
        int nameLen = oldName.length();
        for (int i = from; i + nameLen <= to; i++) {
            if (source.charAt(i) != oldName.charAt(0)) {
                continue;
            }
            boolean matches = true;
            for (int k = 1; k < nameLen; k++) {
                if (source.charAt(i + k) != oldName.charAt(k)) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            boolean leftBoundary = i == 0 || !Character.isJavaIdentifierPart(source.charAt(i - 1));
            boolean rightBoundary = i + nameLen >= source.length() || !Character.isJavaIdentifierPart(source.charAt(i + nameLen));
            if (leftBoundary && rightBoundary) {
                out.add(IdentifierSpan.fromOffsets(file, unit, source, i, i + nameLen));
                i += nameLen - 1;
            }
        }
    }

    /**
     * The dotted package name declared in {@code file} searched across ALL source-set tasks ("" for a default-package
     * file), or {@code null} when the file is not a compilation unit of any task. Unlike {@link #packageNameOf}, which
     * only sees the home source set and silently reports "" for unknown files, this distinguishes "provably in the
     * default package" from "not in the model" so callers can fail closed on the latter.
     */
    public String packageNameOfAnyTask(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                if (pathOf(unit).equals(normalized)) {
                    var packageName = unit.getPackageName();
                    return packageName == null ? "" : packageName.toString();
                }
            }
        }
        return null;
    }

    /** The dotted package name declared in {@code file}, or "" when the file has no package declaration. */
    public String packageNameOf(Path file) {
        Optional<CompilationUnitTree> unitOpt = findUnit(file);
        if (unitOpt.isEmpty()) {
            return "";
        }
        var packageName = unitOpt.get().getPackageName();
        return packageName == null ? "" : packageName.toString();
    }

    private List<TypeElement> projectTypes() {
        return home.projectTypes();
    }

    public io.serena.javarefactor.operations.hierarchy.TypeHierarchyIndex typeHierarchyIndex() {
        List<io.serena.javarefactor.operations.hierarchy.TypeDescriptor> descriptors = new ArrayList<>();
        for (TypeElement type : projectTypes()) {
            if (!(type.getEnclosingElement() instanceof TypeElement)) {
                descriptors.add(typeDescriptor(type));
            }
        }
        // Real override groups from the canonical Elements.overrides relation, keyed by SemanticKey canonical strings so
        // they line up with each MemberDescriptor's semanticKey.
        List<java.util.Set<String>> overrideGroups = io.serena.javarefactor.operations.hierarchy.OverrideGroupComputer.compute(
                projectTypes(), elements, this::canonicalKeyInHome);
        return new io.serena.javarefactor.operations.hierarchy.TypeHierarchyIndex(descriptors, overrideGroups);
    }

    private io.serena.javarefactor.operations.hierarchy.TypeDescriptor typeDescriptor(TypeElement type) {
        List<io.serena.javarefactor.operations.hierarchy.TypeDescriptor> nestedTypes = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof TypeElement nested) {
                nestedTypes.add(typeDescriptor(nested));
            }
        }
        return new io.serena.javarefactor.operations.hierarchy.TypeDescriptor(
                type.getQualifiedName().toString(),
                type.getSimpleName().toString(),
                directSupertypeNames(type),
                permittedSubtypeNames(type),
                memberDescriptors(type),
                modifierNames(type),
                sourceLocation(type),
                nestedTypes,
                superclassName(type),
                interfaceNames(type));
    }

    private String superclassName(TypeElement type) {
        return qualifiedTypeName(type.getSuperclass()).orElse("");
    }

    private Set<String> interfaceNames(TypeElement type) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (javax.lang.model.type.TypeMirror mirror : type.getInterfaces()) {
            qualifiedTypeName(mirror).ifPresent(names::add);
        }
        return names;
    }

    private Set<String> directSupertypeNames(TypeElement type) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (javax.lang.model.type.TypeMirror mirror : types.directSupertypes(type.asType())) {
            qualifiedTypeName(mirror).ifPresent(names::add);
        }
        return names;
    }

    private Set<String> permittedSubtypeNames(TypeElement type) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (javax.lang.model.type.TypeMirror mirror : type.getPermittedSubclasses()) {
            qualifiedTypeName(mirror).ifPresent(names::add);
        }
        return names;
    }

    private Optional<String> qualifiedTypeName(javax.lang.model.type.TypeMirror mirror) {
        Element element = types.asElement(mirror);
        if (element instanceof TypeElement type) {
            return Optional.of(type.getQualifiedName().toString());
        }
        return Optional.empty();
    }

    private List<io.serena.javarefactor.operations.hierarchy.MemberDescriptor> memberDescriptors(TypeElement type) {
        List<io.serena.javarefactor.operations.hierarchy.MemberDescriptor> descriptors = new ArrayList<>();
        for (Element member : type.getEnclosedElements()) {
            String kind = switch (member.getKind()) {
                case METHOD -> "method";
                case CONSTRUCTOR -> "constructor";
                case FIELD, ENUM_CONSTANT -> "field";
                default -> null;
            };
            if (kind != null) {
                descriptors.add(memberDescriptor(member, kind));
            }
        }
        return descriptors;
    }

    private io.serena.javarefactor.operations.hierarchy.MemberDescriptor memberDescriptor(Element member, String kind) {
        Set<String> modifiers = modifierNames(member);
        if (member instanceof ExecutableElement executable) {
            List<io.serena.javarefactor.operations.hierarchy.MemberDescriptor.ParameterModel> parameters = new ArrayList<>();
            for (VariableElement parameter : executable.getParameters()) {
                parameters.add(new io.serena.javarefactor.operations.hierarchy.MemberDescriptor.ParameterModel(
                        parameter.getSimpleName().toString(),
                        parameter.asType().toString(),
                        types.erasure(parameter.asType()).toString()));
            }
            List<String> thrownTypes = new ArrayList<>();
            for (TypeMirror thrown : executable.getThrownTypes()) {
                thrownTypes.add(thrown.toString());
            }
            List<String> typeParameters = new ArrayList<>();
            executable.getTypeParameters().forEach(parameter -> typeParameters.add(parameter.toString()));
            return new io.serena.javarefactor.operations.hierarchy.MemberDescriptor(
                    member.getSimpleName().toString(),
                    kind,
                    modifiers,
                    sourceLocation(member),
                    canonicalKeyInHome(member),
                    erasedOverrideKey(executable),
                    executable.getReturnType().toString(),
                    parameters,
                    thrownTypes,
                    annotationNames(member),
                    typeParameters,
                    io.serena.javarefactor.operations.hierarchy.MemberDescriptor.visibilityOf(modifiers));
        }
        return new io.serena.javarefactor.operations.hierarchy.MemberDescriptor(
                member.getSimpleName().toString(),
                kind,
                modifiers,
                sourceLocation(member),
                canonicalKeyInHome(member),
                "",
                member.asType().toString(),
                List.of(),
                List.of(),
                annotationNames(member),
                List.of(),
                io.serena.javarefactor.operations.hierarchy.MemberDescriptor.visibilityOf(modifiers));
    }

    /** JLS override identity: method name plus erased parameter types (covariant returns / generics collapse here). */
    private String erasedOverrideKey(ExecutableElement executable) {
        List<String> erased = new ArrayList<>();
        for (VariableElement parameter : executable.getParameters()) {
            erased.add(types.erasure(parameter.asType()).toString());
        }
        return executable.getSimpleName() + "(" + String.join(",", erased) + ")";
    }

    private List<String> annotationNames(Element member) {
        List<String> names = new ArrayList<>();
        for (javax.lang.model.element.AnnotationMirror annotation : member.getAnnotationMirrors()) {
            Element annotationElement = annotation.getAnnotationType().asElement();
            if (annotationElement instanceof TypeElement annotationType) {
                names.add(annotationType.getQualifiedName().toString());
            } else {
                names.add(annotation.getAnnotationType().toString());
            }
        }
        return names;
    }

    private Set<String> modifierNames(Element element) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (javax.lang.model.element.Modifier modifier : element.getModifiers()) {
            names.add(modifier.toString());
        }
        return names;
    }

    private io.serena.javarefactor.shared.SourceLocation sourceLocation(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null) {
            return new io.serena.javarefactor.shared.SourceLocation("", 0, 0, 0, 0);
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        Tree tree = path.getLeaf();
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        if (start < 0 || end < 0) {
            return new io.serena.javarefactor.shared.SourceLocation(relativeTo(pathOf(unit)), 0, 0, 0, 0);
        }
        return new io.serena.javarefactor.shared.SourceLocation(
                relativeTo(pathOf(unit)),
                (int) unit.getLineMap().getLineNumber(start),
                (int) unit.getLineMap().getColumnNumber(start),
                (int) unit.getLineMap().getLineNumber(end),
                (int) unit.getLineMap().getColumnNumber(end));
    }

    private Set<TypeElement> supertypeElements(TypeElement type) {
        Set<TypeElement> result = new LinkedHashSet<>();
        Deque<TypeElement> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            TypeElement current = queue.poll();
            for (javax.lang.model.type.TypeMirror supertype : types.directSupertypes(current.asType())) {
                Element element = types.asElement(supertype);
                if (element instanceof TypeElement superType && result.add(superType)) {
                    queue.add(superType);
                }
            }
        }
        return result;
    }

    private boolean overrideRelatedIn(CompilerTask task, ExecutableElement a, ExecutableElement b) {
        if (a.equals(b) || !(a.getEnclosingElement() instanceof TypeElement at) || !(b.getEnclosingElement() instanceof TypeElement bt)) {
            return false;
        }
        // Elements.overrides already encodes subtype semantics from the viewpoint type; test both directions directly
        // rather than pre-gating on an erasure subtype check, which can wrongly exclude valid generic override pairs.
        // Uses the owning task's Elements so the subtype relation is resolved within that task's element universe.
        return task.elements.overrides(a, b, at) || task.elements.overrides(b, a, bt);
    }

    private final class VariableNameScanner extends TreePathScanner<Void, Void> {
        private final String name;
        private final Element ignore;
        private boolean found;

        private VariableNameScanner(String name, Element ignore) {
            this.name = name;
            this.ignore = ignore;
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element != null && !element.equals(ignore) && node.getName().contentEquals(name)) {
                found = true;
            }
            return super.visitVariable(node, unused);
        }
    }

    /** Finds a same-named local/parameter whose lexical scope overlaps the target's (a genuine rename conflict). */
    private final class ScopeOverlapScanner extends TreePathScanner<Void, Void> {
        private final String name;
        private final Element ignore;
        private final CompilationUnitTree unit;
        final SourcePositions positions;
        private final long[] targetScope;
        private boolean conflict;

        private ScopeOverlapScanner(String name, Element ignore, CompilationUnitTree unit, SourcePositions positions, long[] targetScope) {
            this.name = name;
            this.ignore = ignore;
            this.unit = unit;
            this.positions = positions;
            this.targetScope = targetScope;
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element != null && !element.equals(ignore) && node.getName().contentEquals(name)) {
                long[] otherScope = scopeRange(getCurrentPath(), unit, positions);
                if (otherScope != null && scopesOverlap(targetScope, otherScope)) {
                    conflict = true;
                }
            }
            return super.visitVariable(node, unused);
        }
    }

    /**
     * Finds an identifier reference to {@code newName} inside a renamed local/parameter's scope that currently resolves
     * to a field or an outer-scope variable — a reference that would silently rebind to the renamed variable.
     */
    private final class LocalRenameRebindScanner extends TreePathScanner<Void, Void> {
        private final String newName;
        private final Element target;
        private final CompilationUnitTree unit;
        private final long scanStart;
        private final long scopeEnd;
        private String conflict;

        private LocalRenameRebindScanner(String newName, Element target, CompilationUnitTree unit, long scanStart, long scopeEnd) {
            this.newName = newName;
            this.target = target;
            this.unit = unit;
            this.scanStart = scanStart;
            this.scopeEnd = scopeEnd;
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            if (conflict == null && node.getName().contentEquals(newName)) {
                long pos = positions.getStartPosition(unit, node);
                if (pos >= scanStart && pos < scopeEnd) {
                    Element resolved = trees.getElement(getCurrentPath());
                    if (resolved != null && !resolved.equals(target)) {
                        if (resolved.getKind() == ElementKind.FIELD || resolved.getKind() == ElementKind.ENUM_CONSTANT) {
                            conflict = rebindMessage("field");
                        } else if (isVariableElement(resolved) && declaredOutsideTargetScope(resolved)) {
                            conflict = rebindMessage("variable");
                        }
                    }
                }
            }
            return super.visitIdentifier(node, unused);
        }

        // A newName-named variable declared INSIDE the target's scope is a nested declaration that keeps winning after
        // the rename (no rebind to the target), so only references resolving to a declaration OUTSIDE the scope rebind.
        private boolean declaredOutsideTargetScope(Element resolved) {
            TreePath declPath = trees.getPath(resolved);
            if (declPath == null || declPath.getCompilationUnit() != unit) {
                return true;
            }
            long declStart = positions.getStartPosition(unit, declPath.getLeaf());
            return declStart < scanStart || declStart >= scopeEnd;
        }

        private String rebindMessage(String kind) {
            return "Renaming '" + target.getSimpleName() + "' to '" + newName + "' would shadow an existing reference to "
                    + "a " + kind + " named '" + newName + "' inside the variable's scope; that unqualified reference "
                    + "would silently rebind to the renamed variable while still compiling. Choose a name that is not "
                    + "already referenced in this scope, or qualify the existing reference.";
        }
    }

    /**
     * Finds an unqualified reference to a renamed field that sits where a local/parameter named {@code newName} is in
     * scope — the rewritten reference would bind to that variable instead of the field.
     */
    private final class FieldRenameRebindScanner extends TreePathScanner<Void, Void> {
        private final Element field;
        private final String newName;
        private String conflict;

        private FieldRenameRebindScanner(Element field, String newName) {
            this.field = field;
            this.newName = newName;
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            if (conflict == null && node.getName().contentEquals(field.getSimpleName())
                    && field.equals(trees.getElement(getCurrentPath()))
                    && visibleVariableNamed(getCurrentPath(), newName) != null) {
                conflict = "Renaming field '" + field.getSimpleName() + "' to '" + newName + "' would rebind an existing "
                        + "unqualified reference: a local variable or parameter named '" + newName + "' is in scope at "
                        + "the reference, so the rewritten access would bind to it instead of the field. Qualify the "
                        + "field access (e.g. this." + field.getSimpleName() + ") or choose another name.";
            }
            return super.visitIdentifier(node, unused);
        }
    }

    /**
     * Returns the full syntactic source range (modifiers/annotations through the declaration body) of the element's
     * declaration tree, or null if it cannot be located precisely. This is the AST-accurate alternative to scanning
     * source lines, so multi-line members are deleted/replaced as whole declarations.
     */
    /**
     * Counts top-level type declarations (classes, interfaces, enums, records, annotation types) in the compilation
     * unit for {@code file}, ignoring non-type trivia such as stray empty {@code ;} declarations. Used by safe delete to
     * decide between deleting the whole file (sole top-level type) and deleting only the selected type's declaration.
     */
    public int topLevelTypeDeclarationCount(Path file) {
        Optional<CompilationUnitTree> unitOpt = findUnit(file);
        if (unitOpt.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Tree decl : unitOpt.get().getTypeDecls()) {
            if (decl instanceof ClassTree) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether the compilation unit for {@code file} holds exactly one top-level declaration and it is the selected type
     * {@code typeName}. Move refuses otherwise. Any additional top-level entry — a package-private companion type, a
     * second public type (parseable only under allow_incomplete_analysis), or non-type trivia such as a stray {@code ;}
     * (which javac surfaces as a non-{@link ClassTree} entry in {@link CompilationUnitTree#getTypeDecls()}) — would
     * either silently change another type's visibility/package membership or be left behind in an emptied/renamed file.
     * Returns a human-readable refusal description, or null when the file declares only the selected type.
     */
    public String soleTopLevelTypeRefusal(Path file, String typeName) {
        Optional<CompilationUnitTree> unitOpt = findUnit(file);
        if (unitOpt.isEmpty()) {
            return "Could not parse the source file to verify it declares only the type being moved.";
        }
        int classCount = 0;
        boolean nonTypeTrivia = false;
        List<String> otherTypes = new ArrayList<>();
        for (Tree decl : unitOpt.get().getTypeDecls()) {
            if (decl instanceof ClassTree classTree) {
                classCount++;
                String name = classTree.getSimpleName().toString();
                if (!name.equals(typeName)) {
                    otherTypes.add(name);
                }
            } else {
                nonTypeTrivia = true;
            }
        }
        if (classCount == 1 && otherTypes.isEmpty() && !nonTypeTrivia) {
            return null;
        }
        StringBuilder reason = new StringBuilder(
                "Move refuses a source file that contains top-level declarations other than the type being moved; the "
                        + "moved type must be the sole top-level declaration in its file.");
        if (!otherTypes.isEmpty()) {
            reason.append(" Additional top-level type(s): ").append(String.join(", ", otherTypes)).append('.');
        }
        if (nonTypeTrivia) {
            reason.append(" The file also has non-type top-level trivia (e.g. a stray ';').");
        }
        return reason.toString();
    }

    public DeclarationRange declarationRange(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null) {
            return null;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        Tree leaf = path.getLeaf();
        long start = positions.getStartPosition(unit, leaf);
        long end = positions.getEndPosition(unit, leaf);
        if (start < 0 || end < start) {
            return null;
        }
        return new DeclarationRange(pathOf(unit), (int) start, (int) end);
    }

    /**
     * Number of declarators that share the SAME physical field declaration as the given field element. A source line
     * like {@code static final int A = 1, B = 2;} produces two distinct {@link VariableTree} declarators that javac
     * surfaces as separate {@link VariableElement}s but which share a single type tree (and therefore a single declared
     * declaration). Declarators are grouped purely from the javac model: siblings whose {@code getType()} tree begins at
     * the same source position belong to the same declaration. Returns 1 for an ordinary single-declarator field, the
     * declarator count for a multi-declarator field, and 0 when the element is not a field with a resolvable path. This
     * is exact (no text/regex scanning) so callers can refuse refactorings that would silently drag sibling declarators.
     */
    public int fieldDeclaratorCount(Element element) {
        if (element == null || element.getKind() != ElementKind.FIELD) {
            return 0;
        }
        TreePath path = trees.getPath(element);
        if (path == null || !(path.getLeaf() instanceof VariableTree targetVariable)) {
            return 0;
        }
        TreePath parentPath = path.getParentPath();
        if (parentPath == null || !(parentPath.getLeaf() instanceof ClassTree enclosing)) {
            return 0;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        long targetTypeStart = targetVariable.getType() == null
                ? -1
                : positions.getStartPosition(unit, targetVariable.getType());
        if (targetTypeStart < 0) {
            return 1;
        }
        int count = 0;
        for (Tree member : enclosing.getMembers()) {
            if (!(member instanceof VariableTree sibling) || sibling.getType() == null) {
                continue;
            }
            if (positions.getStartPosition(unit, sibling.getType()) == targetTypeStart) {
                count++;
            }
        }
        return count == 0 ? 1 : count;
    }

    /**
     * Whether the element is a local variable whose declaration is a direct statement of a block (its declaration
     * tree's parent leaf is a {@link BlockTree}). This excludes for-init variables (ForLoopTree parent), enhanced-for
     * variables (EnhancedForLoopTree parent), try-with-resources resources (RESOURCE_VARIABLE kind / TryTree parent),
     * lambda parameters (LambdaExpressionTree parent), catch parameters (CatchTree parent), and fields (ClassTree
     * parent). Only such standalone block-statement locals are safe to inline via the line-oriented declaration removal.
     */
    public boolean isStandaloneBlockStatementLocal(Element element) {
        if (element.getKind() != ElementKind.LOCAL_VARIABLE) {
            return false;
        }
        TreePath path = trees.getPath(element);
        if (path == null) {
            return false;
        }
        TreePath parent = path.getParentPath();
        return parent != null && parent.getLeaf() instanceof BlockTree;
    }

    /**
     * Construct-specific safe-delete refusal for a local/resource/exception variable target, or null when the target is
     * a block-statement local that safe delete supports (standalone OR sharing its line with sibling code — both are
     * removable with an exact span). Rather than a single broad "standalone block-statement locals only" message, each
     * genuinely-undeletable construct gets its own semantic reason: a try-with-resources resource owns a closeable that
     * must be closed; a catch clause's exception parameter is a mandatory part of the clause; an enhanced-for loop
     * variable is a required part of the for-each statement; a for-loop initializer variable lives in the loop header.
     */
    public String localDeleteConstructRefusal(Element element) {
        ElementKind kind = element.getKind();
        if (kind == ElementKind.RESOURCE_VARIABLE) {
            return "Safe delete refuses a try-with-resources resource variable: it owns a resource that must be closed, "
                    + "so removing its declaration would drop the close. Remove it manually if the resource is unused.";
        }
        if (kind == ElementKind.EXCEPTION_PARAMETER) {
            return "Safe delete refuses a catch clause's exception parameter: the parameter is a required part of the "
                    + "catch clause and cannot be removed on its own.";
        }
        if (kind != ElementKind.LOCAL_VARIABLE) {
            return null;
        }
        TreePath path = trees.getPath(element);
        if (path == null) {
            return "Safe delete could not locate the declaration of the local variable.";
        }
        TreePath parentPath = path.getParentPath();
        Tree parentLeaf = parentPath == null ? null : parentPath.getLeaf();
        if (parentLeaf instanceof BlockTree) {
            return null;
        }
        if (parentLeaf instanceof ForLoopTree) {
            return "Safe delete refuses a for-loop initializer variable: removing it would alter the loop header. "
                    + "Delete it from the for-statement manually if it is truly unused.";
        }
        if (parentLeaf instanceof EnhancedForLoopTree) {
            return "Safe delete refuses an enhanced-for loop variable: the loop variable is a required part of the "
                    + "for-each statement and cannot be removed.";
        }
        return "Safe delete supports a local variable only when it is declared as a statement in a block; this "
                + "declaration context is not supported.";
    }

    /**
     * Whether the declaration at {@code [start, end)} shares its line with other code, which would make the
     * line-oriented {@link #expandDeclarationRangeForDelete} corrupt sibling statements. Returns true when there is
     * non-whitespace before {@code start} on its first line, or non-whitespace (other than the terminating {@code ;})
     * after the declaration's end on its last line.
     */
    public static boolean declarationSharesLineWithOtherCode(String source, int start, int end) {
        int before = start - 1;
        while (before >= 0 && source.charAt(before) != '\n') {
            if (!Character.isWhitespace(source.charAt(before))) {
                return true;
            }
            before--;
        }
        int after = end;
        // Skip whitespace, then an optional terminating semicolon, then trailing whitespace up to the line break.
        while (after < source.length() && (source.charAt(after) == ' ' || source.charAt(after) == '\t')) {
            after++;
        }
        if (after < source.length() && source.charAt(after) == ';') {
            after++;
        }
        while (after < source.length() && source.charAt(after) != '\n') {
            if (!Character.isWhitespace(source.charAt(after))) {
                return true;
            }
            after++;
        }
        return false;
    }

    /** Returns the initializer expression source and range for a variable/field declaration, or null when absent. */
    public InitializerInfo initializerInfo(Element element) {
        return inlineIndex.initializerInfo(element);
    }



    /** Whether the element is a Java compile-time constant (a {@code static final} field with a constant value). */
    public boolean isCompileTimeConstant(Element element) {
        return inlineIndex.isCompileTimeConstant(element);
    }



    /** The syntactic kind of a variable/field initializer expression, or null when there is no initializer. */
    public Tree.Kind initializerKind(Element element) {
        return inlineIndex.initializerKind(element);
    }



    /**
     * Whether {@code element} (a local variable) is referenced from inside a lambda body ({@link LambdaExpressionTree})
     * or an inner/anonymous class body ({@link ClassTree}, including the anonymous body of a {@link NewClassTree}) that
     * is nested in its enclosing method. Inlining a local across such a capture boundary can change semantics or timing
     * (the initializer would be re-evaluated inside the closure rather than captured once) and can break
     * effectively-final guarantees, so the inline is refused. Returns true (conservative) when the enclosing method body
     * cannot be located. The variable's own declaration is not a capturing use.
     */
    public boolean isUsedInNestedScope(Element element) {
        return inlineIndex.isUsedInNestedScope(element);
    }



    /**
     * Per-usage inline replacements for {@code element} (a local variable): for each reference to the variable in its
     * enclosing method body (excluding the declaration), the {@code initializerText} parenthesized exactly as needed for
     * THAT usage site. Parenthesization is decided from BOTH the initializer expression's top-level operator precedence
     * and the usage's parent-expression context (the operator/position the usage occupies), per the Java operator
     * precedence model in {@link #needsParentheses}. Returns an empty list when the method body or initializer cannot be
     * located (the planner then refuses for lack of usages).
     */
    /**
     * Parent tree kinds in which substituting an inlined expression needs no parentheses (full-expression slots), beyond
     * the operator/precedence contexts {@link #needsParentheses} handles explicitly. A usage whose parent is none of
     * these AND is not a precedence-bearing operator context is unmodelled, so {@link #firstUnsupportedInlineUsageContext}
     * flags it and the planner refuses rather than emit a possibly-mis-parenthesized edit.
     */
        /**
     * The name of the first usage parent context not covered by the inline parenthesization model, or null when every
     * usage sits in a modelled (operator/precedence) or known full-expression context. Method-reference qualifiers
     * ({@code expr::m}) and any future/unknown syntax fall here, so the planner can refuse instead of guessing.
     */
    public String firstUnsupportedInlineUsageContext(Element element) {
        return inlineIndex.firstUnsupportedInlineUsageContext(element);
    }



    /** Whether substituting an inlined expression under {@code parent} is covered by the parenthesization model. */

    public List<UsageReplacement> usageReplacements(Element element, String initializerText) {
        return inlineIndex.usageReplacements(element, initializerText);
    }



    /** The nearest enclosing executable (method/constructor) declaration path of {@code path}, or null. */


    /**
     * Whether splicing an initializer expression of kind {@code initializerKind} into the usage position identified by
     * {@code usagePath} (whose leaf is the usage {@link IdentifierTree}) requires wrapping it in parentheses to preserve
     * meaning. The decision combines the initializer's top-level operator precedence with the precedence/position the
     * usage occupies in its PARENT expression:
     * <ul>
     *   <li>An atomic initializer (identifier, literal, parenthesized, member-select, call, array-access, {@code new})
     *       binds at least as tightly as any operator and is never parenthesized.</li>
     *   <li>Otherwise parentheses are added when the initializer's precedence is strictly looser than the minimum the
     *       parent position requires, or equal to it but on the associativity-conflicting side (e.g. the right operand of
     *       a left-associative binary operator at the same precedence).</li>
     *   <li>A usage acting as the receiver/selector of a member-select, the array of an array-access, the method-select
     *       of an invocation, or the operand of a unary/cast expression requires a primary, so any non-atomic initializer
     *       is parenthesized.</li>
     * </ul>
     */


    /**
     * The minimum operator precedence the usage's position in {@code parentPath} requires of the spliced expression, or
     * {@code -1} when the position is a full-expression slot that needs no parentheses (statement, return, argument,
     * assignment RHS, ternary branches, etc.). For a binary/instanceof parent it is that operator's precedence.
     */




        /** Precedence of a binary/relational operator kind (higher binds tighter). */


    /** Precedence of a whole expression by the kind of its top-level operator (higher binds tighter). */


    /** A single inline usage replacement: the reference span and the initializer text parenthesized for that site. */
    public record UsageReplacement(IdentifierSpan span, String replacement) {
    }

    /**
     * Whether a variable's initializer performs observable side effects (method/constructor calls, array creation,
     * assignments, or in/decrements). Such initializers cannot be safely inlined into multiple usages because each
     * usage would re-run the effect. Returns true (conservative) when the initializer cannot be analyzed.
     */
    public boolean initializerHasSideEffects(Element element) {
        return inlineIndex.initializerHasSideEffects(element);
    }



    /**
     * Why the initializer of {@code element} cannot be safely duplicated at later usage sites, or {@code null} when
     * every value the initializer reads is proven stable. A syntactically pure expression is still unsound to inline
     * when a value it reads can change between the declaration and a use ({@code int a = 1; int x = a; a = 2;
     * return x;} must not become {@code return a;}): javac validation cannot catch this because the result still
     * compiles. Rather than tracking assignment positions relative to each use, this proves the whole read set stable:
     * <ul>
     *   <li>local/parameter/resource/exception/binding variable reads are stable only when the variable is never
     *       assigned, compound-assigned, or incremented/decremented anywhere (effectively final);</li>
     *   <li>field reads are stable only when the field is a compile-time constant (enum constants are stable);</li>
     *   <li>array-element reads are never provably stable and always refuse;</li>
     *   <li>a read that cannot be resolved or classified fails closed.</li>
     * </ul>
     */
    public String initializerUnstableDependencyReason(Element element) {
        return inlineIndex.initializerUnstableDependencyReason(element);
    }



    /**
     * Safe-delete variant of {@link #initializerHasSideEffects}. A declaration whose initializer expression performs an
     * observable side effect (method/constructor call, array creation, assignment, in/decrement) cannot be deleted
     * without dropping that effect, so this returns true. The difference from the inline detector: a declaration that is
     * <em>genuinely absent</em> an initializer (a {@link VariableTree} with {@code null} initializer, e.g.
     * {@code private int x;} or one declarator of {@code int a, b;}) drops nothing and is treated as safe (returns
     * false). It fails closed (returns true) only when the element cannot be resolved to a variable declaration at all —
     * the "absent-but-required" case where side-effect freedom cannot be proven.
     */
    public boolean safeDeleteInitializerHasSideEffects(Element element) {
        return inlineIndex.safeDeleteInitializerHasSideEffects(element);
    }

    public boolean isReassigned(Element element) {
        return inlineIndex.isReassigned(element);
    }

    /**
     * G003 (Case B): whether the given field's initializer expression references any type in {@code subtypeQualifiedNames}
     * — i.e. a member (field/method/constant) declared by one of those types, or a direct use of one of those types
     * itself. This is the safety check for pull-up: a field whose initializer reads a subclass-only member or type cannot
     * legally move to the supertype, because the referenced declaration does not exist there. The walk is javac-backed:
     * every {@link IdentifierTree}/{@link MemberSelectTree} in the initializer is resolved to its {@link Element} and the
     * element (or its enclosing type) is matched by qualified name against the provided set. References that cannot be
     * resolved are ignored (they are not subclass references by name); the caller's set already restricts the match to
     * the proven subtype subtree. Returns false when the element is not a field, has no initializer, or has no resolvable
     * declaration tree.
     */
    public boolean fieldInitializerReferencesType(Element element, Set<String> subtypeQualifiedNames) {
        if (element == null || element.getKind() != ElementKind.FIELD || subtypeQualifiedNames == null
                || subtypeQualifiedNames.isEmpty()) {
            return false;
        }
        TreePath path = trees.getPath(element);
        if (path == null || !(path.getLeaf() instanceof VariableTree variable) || variable.getInitializer() == null) {
            return false;
        }
        TreePath initializerPath = new TreePath(path, variable.getInitializer());
        boolean[] referencesSubtype = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                checkResolved(getCurrentPath());
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                checkResolved(getCurrentPath());
                return super.visitMemberSelect(node, unused);
            }

            private void checkResolved(TreePath current) {
                if (referencesSubtype[0]) {
                    return;
                }
                Element resolved = trees.getElement(current);
                if (resolved == null) {
                    return;
                }
                if (matchesSubtype(resolved)) {
                    referencesSubtype[0] = true;
                }
            }

            private boolean matchesSubtype(Element resolved) {
                if (resolved instanceof TypeElement type
                        && subtypeQualifiedNames.contains(type.getQualifiedName().toString())) {
                    return true;
                }
                Element enclosing = resolved.getEnclosingElement();
                return enclosing instanceof TypeElement owner
                        && subtypeQualifiedNames.contains(owner.getQualifiedName().toString());
            }
        }.scan(initializerPath, null);
        return referencesSubtype[0];
    }



    /**
     * Whether {@code expression} performs an observable side effect: a method or constructor invocation, array creation,
     * (compound) assignment, or pre/post increment/decrement. Shared by the inline and safe-delete side-effect gates.
     */
    static boolean expressionHasObservableSideEffects(ExpressionTree expression) {
        return SemanticInlineIndex.expressionHasObservableSideEffects(expression);
    }



    /** Whether the element is ever assigned, compound-assigned, or incremented/decremented anywhere in the project. */


    /**
     * Detects whether a variable/field declaration is part of a multi-declarator statement (e.g. {@code int a, b;}),
     * by inspecting the first significant character on either side of the declaration tree's range.
     */
    public static boolean isMultiDeclarator(String source, int astStart, int astEnd) {
        // Per-declarator ranges (javac sometimes ends one declarator before the comma): check the adjacent character.
        int after = astEnd;
        while (after < source.length() && Character.isWhitespace(source.charAt(after))) {
            after++;
        }
        if (after < source.length() && source.charAt(after) == ',') {
            return true;
        }
        int before = astStart - 1;
        while (before >= 0 && Character.isWhitespace(source.charAt(before))) {
            before--;
        }
        if (before >= 0 && source.charAt(before) == ',') {
            return true;
        }
        // Shared-type ranges (e.g. "int a, b"): a top-level comma inside the declaration range, ignoring commas nested
        // in generic arguments, parameter lists, array dimensions, and array initializers.
        int depth = 0;
        for (int i = astStart; i < astEnd && i < source.length(); i++) {
            char c = source.charAt(i);
            switch (c) {
                case '<', '(', '[', '{' -> depth++;
                case '>', ')', ']', '}' -> depth = Math.max(0, depth - 1);
                case ',' -> {
                    if (depth == 0) {
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }

    /**
     * Expands an AST declaration range to a deletable source span: backward over the declaration's own leading
     * annotation/modifier lines and indentation and over a directly-attached Javadoc ({@code /** ... *&#47;}
     * immediately preceding the declaration with only whitespace between), but NOT over plain {@code //} line
     * comments, {@code /* *&#47;} block comments, or {@code *} continuation lines — those are ordinary user comments
     * that the plan's deletion-span rules (section 9) do not authorize removing, so they are preserved. Forward over
     * an optional trailing semicolon and a single line break. Used by safe delete and by inline declaration removal.
     */
    public static int[] expandDeclarationRangeForDelete(String source, int astStart, int astEnd) {
        int start = astStart;
        while (start > 0 && source.charAt(start - 1) != '\n') {
            start--;
        }
        // Absorb the declaration's own leading annotation/modifier lines (e.g. "@Deprecated", "public static"), but
        // stop at any line that is a comment — only an attached Javadoc is absorbed, and only as a single block below.
        int blockStart = start;
        while (blockStart > 0) {
            int previousEnd = blockStart - 1;
            int previousStart = previousEnd;
            while (previousStart > 0 && source.charAt(previousStart - 1) != '\n') {
                previousStart--;
            }
            String previousLine = source.substring(previousStart, previousEnd).trim();
            if (previousLine.startsWith("@")) {
                blockStart = previousStart;
            } else {
                break;
            }
        }
        blockStart = absorbAttachedJavadoc(source, blockStart);
        int end = astEnd;
        while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) {
            end++;
        }
        if (end < source.length() && source.charAt(end) == ';') {
            end++;
        }
        while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t' || source.charAt(end) == '\r')) {
            end++;
        }
        if (end < source.length() && source.charAt(end) == '\n') {
            end++;
        }
        return new int[]{blockStart, end};
    }

    /**
     * The deletable source span for a local-variable declaration statement. When the declaration occupies its own
     * line(s), this is the whole-line removal of {@link #expandDeclarationRangeForDelete} (indentation, attached
     * Javadoc, and the trailing line break included). When it shares a line with sibling code, this is an exact removal
     * of just the declaration text, its terminating semicolon, and exactly one adjacent separating space — never the
     * sibling statements and never the line break — so inlining or deleting a same-line local cannot corrupt
     * neighbours. The exact path lets inline-local and safe-delete handle valid same-line placements that whole-line
     * removal cannot, instead of refusing them.
     */
    public static int[] localDeclarationDeleteRange(String source, int astStart, int astEnd) {
        if (!declarationSharesLineWithOtherCode(source, astStart, astEnd)) {
            return expandDeclarationRangeForDelete(source, astStart, astEnd);
        }
        int end = astEnd;
        while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) {
            end++;
        }
        if (end < source.length() && source.charAt(end) == ';') {
            end++;
        }
        int start = astStart;
        // Trim exactly one separating whitespace so surviving siblings keep a single space between them: prefer a
        // trailing space (code follows on the line), else a single leading space (code precedes), else neither.
        if (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) {
            end++;
        } else if (start > 0 && (source.charAt(start - 1) == ' ' || source.charAt(start - 1) == '\t')) {
            start--;
        }
        return new int[]{start, end};
    }

    /**
     * Exact comma-surgery removal range for ONE declarator of a multi-declarator local declaration
     * (e.g. deleting {@code b} from {@code int a = 1, b = 2, c = 3;}), keeping the shared type and the other declarators
     * intact. {@code nameStart}/{@code nameEnd} locate the target declarator's name. Returns null when the declaration's
     * structure cannot be parsed unambiguously from source, so the caller refuses rather than emitting a risky edit.
     *
     * <p>The algorithm is purely source-based because javac's per-declarator start positions for a shared-type
     * declaration are unreliable (the same reason {@link #isMultiDeclarator} scans source). It finds the statement's
     * terminating semicolon and start by depth-aware scanning, collects the depth-0 commas separating declarators,
     * locates the segment holding {@code nameStart}, then removes either {@code [name, firstComma]} for the first
     * declarator (the shared type stays) or {@code [precedingComma, segmentEnd)} for a later declarator (its leading
     * comma is removed with it; a trailing comma stays as the surviving separator). The apply-time javac re-validation
     * is the backstop against any mis-computed span.
     */
    public static int[] multiDeclaratorLocalDeleteRange(String source, int declAstStart, int declAstEnd, int nameStart, int nameEnd) {
        int n = source.length();
        if (nameStart < 0 || nameEnd > n || nameStart >= nameEnd) {
            return null;
        }
        // Statement-terminating semicolon: forward from the target declarator's end at depth 0 (commas separate
        // declarators and are skipped; the first depth-0 ';' ends the statement).
        int semicolon = -1;
        int depth = 0;
        for (int i = nameEnd; i < n; i++) {
            char c = source.charAt(i);
            switch (c) {
                case '<', '(', '[', '{' -> depth++;
                case '>', ')', ']', '}' -> depth = Math.max(0, depth - 1);
                case ';' -> {
                    if (depth == 0) {
                        semicolon = i;
                    }
                }
                default -> {
                }
            }
            if (semicolon >= 0) {
                break;
            }
        }
        if (semicolon < 0) {
            return null;
        }
        // Statement start: backward from the name at depth 0 to the previous ';' '{' '}' boundary (or file start).
        int stmtStart = 0;
        depth = 0;
        for (int i = Math.min(declAstStart, nameStart) - 1; i >= 0; i--) {
            char c = source.charAt(i);
            switch (c) {
                case '>', ')', ']', '}' -> depth++;
                case '<', '(', '[', '{' -> depth = Math.max(0, depth - 1);
                default -> {
                }
            }
            if (depth == 0 && (c == ';' || c == '{' || c == '}')) {
                stmtStart = i + 1;
                break;
            }
        }
        // Depth-0 commas within [stmtStart, semicolon) separate the declarators.
        java.util.List<Integer> commas = new java.util.ArrayList<>();
        depth = 0;
        for (int i = stmtStart; i < semicolon; i++) {
            char c = source.charAt(i);
            switch (c) {
                case '<', '(', '[', '{' -> depth++;
                case '>', ')', ']', '}' -> depth = Math.max(0, depth - 1);
                case ',' -> {
                    if (depth == 0) {
                        commas.add(i);
                    }
                }
                default -> {
                }
            }
        }
        if (commas.isEmpty()) {
            return null;
        }
        int segCount = commas.size() + 1;
        int target = -1;
        for (int k = 0; k < segCount; k++) {
            int segStart = (k == 0) ? stmtStart : commas.get(k - 1) + 1;
            int segEnd = (k == commas.size()) ? semicolon : commas.get(k);
            if (nameStart >= segStart && nameEnd <= segEnd) {
                target = k;
                break;
            }
        }
        if (target < 0) {
            return null;
        }
        int removeStart;
        int removeEnd;
        if (target == 0) {
            // Keep the shared type that precedes the name; remove the name, its initializer, and the following comma.
            removeStart = nameStart;
            removeEnd = commas.get(0) + 1;
            while (removeEnd < n && (source.charAt(removeEnd) == ' ' || source.charAt(removeEnd) == '\t')) {
                removeEnd++;
            }
        } else {
            // Remove the preceding comma together with this declarator; a following comma (if any) stays as separator.
            removeStart = commas.get(target - 1);
            removeEnd = (target == commas.size()) ? semicolon : commas.get(target);
        }
        if (removeStart < 0 || removeEnd > n || removeStart >= removeEnd) {
            return null;
        }
        return new int[]{removeStart, removeEnd};
    }

    /**
     * If the lines immediately preceding {@code declStart} (only whitespace between) form a directly-attached Javadoc
     * block comment ({@code /** ... *​/}), returns the offset of that block's start; otherwise returns
     * {@code declStart} unchanged. A {@code /*}-but-not-{@code /**} block comment or a {@code //} line comment is NOT
     * absorbed.
     */
    private static int absorbAttachedJavadoc(String source, int declStart) {
        int cursor = declStart - 1;
        // Skip only whitespace (including newlines) directly before the declaration up to a potential comment close.
        while (cursor >= 0 && Character.isWhitespace(source.charAt(cursor))) {
            cursor--;
        }
        // The attached comment must end with "*/" immediately above (modulo whitespace).
        if (cursor < 1 || source.charAt(cursor) != '/' || source.charAt(cursor - 1) != '*') {
            return declStart;
        }
        // Find the matching block-comment open scanning backward for "/*".
        int open = -1;
        for (int i = cursor - 1; i >= 1; i--) {
            if (source.charAt(i) == '*' && source.charAt(i - 1) == '/') {
                open = i - 1;
                break;
            }
        }
        if (open < 0) {
            return declStart;
        }
        // Javadoc only: the open must be "/**".
        if (open + 2 >= source.length() || source.charAt(open + 2) != '*') {
            return declStart;
        }
        // Expand to the start of the Javadoc's first line (its indentation), so the whole block is removed cleanly.
        int lineStart = open;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        return lineStart;
    }

    /**
     * Plans the conservative deletion of an unused {@code private}-method parameter (plan section 9): removes the
     * parameter from the declaration's parameter list (with correct comma surgery, varargs, and parameter annotations)
     * and removes the corresponding positional argument at every call site. Returns a {@link ParameterDeletionPlan}
     * carrying either a refusal reason or the full edit list. The caller is responsible for the private/hierarchy/no-use
     * gates being a precondition documented here:
     * <ul>
     *   <li>refuses if the parameter is varargs and not the last positional argument can be located safely (a varargs
     *       call site may pass 0..n arguments, so the positional mapping is not removable);</li>
     *   <li>refuses if the method is referenced as a method reference ({@code Type::method}) — the parameter list is
     *       then part of a functional contract that cannot be edited;</li>
     *   <li>refuses if any call site's positional argument cannot be precisely located.</li>
     * </ul>
     */
    public ParameterDeletionPlan planParameterDeletion(Element parameterElement) {
        if (parameterElement.getKind() != ElementKind.PARAMETER
                || !(parameterElement.getEnclosingElement() instanceof ExecutableElement method)
                || method.getKind() != ElementKind.METHOD) {
            return ParameterDeletionPlan.refuse("Safe delete supports only method parameters.");
        }
        int paramIndex = method.getParameters().indexOf(parameterElement);
        if (paramIndex < 0) {
            return ParameterDeletionPlan.refuse("Could not locate the parameter within its method's parameter list.");
        }
        if (method.isVarArgs() && paramIndex == method.getParameters().size() - 1) {
            return ParameterDeletionPlan.refuse("Safe delete refuses removing a varargs parameter; call sites may pass a "
                    + "variable number of arguments that cannot be mapped positionally.");
        }

        // Declaration parameter span (with comma surgery + annotations).
        TreePath methodPath = trees.getPath(method);
        if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree methodTree)) {
            return ParameterDeletionPlan.refuse("Could not locate the method declaration to edit.");
        }
        CompilationUnitTree declUnit = methodPath.getCompilationUnit();
        Path declFile = pathOf(declUnit);
        CharSequence declSourceSeq = sourceByPath.get(declFile.toAbsolutePath().normalize());
        if (declSourceSeq == null) {
            return ParameterDeletionPlan.refuse("Could not read the method declaration source.");
        }
        String declSource = declSourceSeq.toString();
        List<? extends VariableTree> declParams = methodTree.getParameters();
        if (paramIndex >= declParams.size()) {
            return ParameterDeletionPlan.refuse("Could not locate the parameter within the method declaration.");
        }
        int[] declRemoval = listElementRemovalRange(declSource, declUnit, declParams, paramIndex);
        if (declRemoval == null) {
            return ParameterDeletionPlan.refuse("Could not determine a precise removal span for the parameter declaration.");
        }

        List<ParameterEdit> edits = new ArrayList<>();
        edits.add(new ParameterEdit(declFile, declRemoval[0], declRemoval[1]));

        // Call sites: scan every task for invocations resolving to this method, and a method-reference guard.
        String methodKey = canonicalKeyInHome(method);
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                CharSequence sourceSeq = task.sourceByPath.get(file.toAbsolutePath().normalize());
                if (sourceSeq == null) {
                    continue;
                }
                String source = sourceSeq.toString();
                String[] refusal = {null};
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        Element resolved = task.trees.getElement(getCurrentPath());
                        if (resolved != null && task.canonicalKey(resolved).equals(methodKey)) {
                            List<? extends ExpressionTree> args = node.getArguments();
                            // Call-site argument purity gate: the positional argument being removed must be provably
                            // pure (a literal or a plain identifier). Removing an argument that invokes a method/ctor,
                            // assigns, increments/decrements, creates an array, or otherwise carries observable
                            // evaluation (lambda, ternary, ...) would silently drop that behavior, so the whole delete
                            // is refused rather than producing a behavior-changing edit.
                            if (paramIndex >= args.size() || !isProvablyPureArgument(args.get(paramIndex))) {
                                refusal[0] = "Safe delete refuses removing this parameter: a call site passes an "
                                        + "argument that is not provably pure (only literals and plain identifiers are "
                                        + "accepted). Removing it could drop a side effect or change evaluation order.";
                            } else {
                                int[] removal = listElementRemovalRange(source, unit, node.getArguments(), paramIndex);
                                if (removal == null) {
                                    refusal[0] = "Could not precisely locate the positional argument to remove at a call site.";
                                } else {
                                    edits.add(new ParameterEdit(file, removal[0], removal[1]));
                                }
                            }
                        }
                        return super.visitMethodInvocation(node, unused);
                    }

                    @Override
                    public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                        Element resolved = task.trees.getElement(getCurrentPath());
                        if (resolved != null && task.canonicalKey(resolved).equals(methodKey)) {
                            refusal[0] = "Safe delete refuses removing a parameter of a method used as a method "
                                    + "reference; its parameter list is part of a functional-interface contract.";
                        }
                        return super.visitMemberReference(node, unused);
                    }
                }.scan(unit, null);
                if (refusal[0] != null) {
                    return ParameterDeletionPlan.refuse(refusal[0]);
                }
            }
        }
        return ParameterDeletionPlan.accept(edits);
    }

    /**
     * Whether a call-site argument expression is provably pure to remove — conservatively, a literal or a plain
     * identifier (optionally parenthesized or behind a side-effect-free unary +/-/~/! operator). Everything else
     * (method/constructor invocations, assignments, increment/decrement, array creation, lambdas, method references,
     * ternaries, binary expressions, member selects, casts, ...) is treated as not provably pure so the parameter
     * deletion is refused rather than dropping observable evaluation.
     */
    private static boolean isProvablyPureArgument(ExpressionTree argument) {
        return switch (argument.getKind()) {
            case INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL, BOOLEAN_LITERAL, CHAR_LITERAL,
                    STRING_LITERAL, NULL_LITERAL, IDENTIFIER -> true;
            case PARENTHESIZED -> isProvablyPureArgument(((ParenthesizedTree) argument).getExpression());
            case UNARY_PLUS, UNARY_MINUS, BITWISE_COMPLEMENT, LOGICAL_COMPLEMENT ->
                    isProvablyPureArgument(((UnaryTree) argument).getExpression());
            default -> false;
        };
    }

    /**
     * Whether {@code parameterElement} is read or written anywhere in its enclosing method's body. Returns true
     * (conservative) when the body cannot be located.
     */
    public boolean parameterHasUses(Element parameterElement) {
        if (!(parameterElement.getEnclosingElement() instanceof ExecutableElement method)) {
            return true;
        }
        TreePath methodPath = trees.getPath(method);
        if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree methodTree) || methodTree.getBody() == null) {
            // The body is unavailable (e.g. abstract/native/bodyless method, or no source path): we cannot prove the
            // parameter is unused, so report it as used to fail closed and refuse the delete.
            return true;
        }
        boolean[] used = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null && tree.getKind() == Tree.Kind.IDENTIFIER) {
                    Element resolved = trees.getElement(new TreePath(getCurrentPath(), tree));
                    if (resolved != null && resolved.equals(parameterElement)) {
                        used[0] = true;
                    }
                }
                return super.scan(tree, unused);
            }
        }.scan(new TreePath(methodPath, methodTree.getBody()), null);
        return used[0];
    }

    /**
     * Source removal range {@code [start, end)} for the element at {@code index} of a comma-separated tree list (method
     * declaration parameters or invocation arguments), with comma surgery: a non-last element removes its trailing
     * comma and following whitespace; the last element removes its preceding comma and the whitespace between. Includes
     * any leading annotations/modifiers of the element (its AST start already covers them). Returns null when positions
     * cannot be resolved.
     */
    private int[] listElementRemovalRange(String source, CompilationUnitTree unit, List<? extends Tree> elements, int index) {
        if (index < 0 || index >= elements.size()) {
            return null;
        }
        SourcePositions taskPositions = treesFor(unit).getSourcePositions();
        long elemStart = taskPositions.getStartPosition(unit, elements.get(index));
        long elemEnd = taskPositions.getEndPosition(unit, elements.get(index));
        if (elemStart < 0 || elemEnd < elemStart || elemEnd > source.length()) {
            return null;
        }
        int start = (int) elemStart;
        int end = (int) elemEnd;
        if (elements.size() == 1) {
            return new int[]{start, end};
        }
        if (index < elements.size() - 1) {
            // Remove the trailing comma and following whitespace up to the next element's start.
            int cursor = end;
            while (cursor < source.length() && (source.charAt(cursor) == ' ' || source.charAt(cursor) == '\t')) {
                cursor++;
            }
            if (cursor >= source.length() || source.charAt(cursor) != ',') {
                return null;
            }
            cursor++;
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
            }
            return new int[]{start, cursor};
        }
        // Last element: remove the preceding comma and whitespace between it and the previous element.
        int cursor = start - 1;
        while (cursor >= 0 && Character.isWhitespace(source.charAt(cursor))) {
            cursor--;
        }
        if (cursor < 0 || source.charAt(cursor) != ',') {
            return null;
        }
        return new int[]{cursor, end};
    }

    private Trees treesFor(CompilationUnitTree unit) {
        Path normalized = pathOf(unit).toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            if (task.sourceByPath.containsKey(normalized)) {
                return task.trees;
            }
        }
        return trees;
    }

    /** Outcome of {@link #planParameterDeletion}: either a refusal reason, or the list of edits to apply. */
    public record ParameterDeletionPlan(String refusalReason, List<ParameterEdit> edits) {
        static ParameterDeletionPlan refuse(String reason) {
            return new ParameterDeletionPlan(reason, List.of());
        }

        static ParameterDeletionPlan accept(List<ParameterEdit> edits) {
            return new ParameterDeletionPlan(null, edits);
        }

        public boolean accepted() {
            return refusalReason == null;
        }
    }

    /** A single text removal {@code [start, end)} (replace with empty text) in a file, for parameter deletion. */
    public record ParameterEdit(Path file, int start, int end) {
    }

    public record DeclarationRange(Path file, int start, int end) {
    }

    public record InitializerInfo(String text, int start, int end) {
    }




    public enum SemanticMemberKind {
        METHOD,
        FIELD
    }

    public record SourceRange(Path file, int start, int end) {
        public String text(SemanticIndex index) {
            CharSequence source = index.sourceText(file);
            if (source == null || start < 0 || end < start || end > source.length()) {
                return "";
            }
            return source.subSequence(start, end).toString();
        }
    }

    public record SemanticParameter(String type, String name, SourceRange range, Element element) {
    }

    public record SemanticType(
            Path file,
            String packageName,
            String name,
            String qualifiedName,
            String kind,
            SourceRange declarationRange,
            SourceRange bodyRange,
            SourceRange inheritanceTailRange,
            Element element) {
    }

    public record SemanticMethod(
            Path file,
            String ownerName,
            String ownerQualifiedName,
            String name,
            String returnType,
            List<SemanticParameter> parameters,
            Set<Modifier> modifiers,
            SourceRange declarationRange,
            SourceRange headerRange,
            SourceRange bodyRange,
            SourceRange returnTypeRange,
            Element element,
            javax.lang.model.type.ExecutableType memberType) {
        public boolean isStatic() {
            return modifiers.contains(Modifier.STATIC);
        }

        public boolean isPrivate() {
            return modifiers.contains(Modifier.PRIVATE);
        }
    }

    public record SemanticField(
            Path file,
            String ownerName,
            String ownerQualifiedName,
            String name,
            String type,
            Set<Modifier> modifiers,
            SourceRange declarationRange,
            SourceRange initializerRange,
            SourceRange typeRange,
            Element element) {
        public boolean isStatic() {
            return modifiers.contains(Modifier.STATIC);
        }

        public boolean isPrivate() {
            return modifiers.contains(Modifier.PRIVATE);
        }
    }

    public record SemanticMember(SemanticMemberKind kind, SemanticMethod method, SemanticField field) {
        public String name() {
            return kind == SemanticMemberKind.METHOD ? method.name() : field.name();
        }

        public SourceRange declarationRange() {
            return kind == SemanticMemberKind.METHOD ? method.declarationRange() : field.declarationRange();
        }

        public Element element() {
            return kind == SemanticMemberKind.METHOD ? method.element() : field.element();
        }
    }

    public record SemanticArgument(String text, SourceRange range) {
    }

    public record SemanticCallSite(
            Path file,
            SourceRange invocationRange,
            SourceRange nameRange,
            SourceRange receiverRange,
            String receiverText,
            List<SemanticArgument> arguments,
            boolean methodReference,
            boolean statementExpression) {
    }

    public record SemanticExtractVariable(String type, String name, boolean declaredInSelection) {}

    public record SemanticExpressionSelection(
            Path file,
            SourceRange range,
            SourceRange enclosingMethodRange,
            SourceRange enclosingTypeBodyRange,
            boolean initializerScope,
            String text,
            String type,
            io.serena.javarefactor.shared.ExpressionPurity purity,
            Element enclosingExecutable,
            List<SemanticExtractVariable> inputs,
            Set<String> checkedExceptions,
            boolean usesThis,
            boolean usesSuper,
            boolean enclosingMethodStatic,
            boolean compileTimeConstant,
            // Blocker 4: a stable, re-run-deterministic identity for the initializer host when the selection has no
            // enclosing executable (field initializer or class/instance initializer block). null outside initializer
            // scope (the enclosingExecutable's key is used there instead). Lets an initializer-scope preview carry a
            // session target so it can be applied as a V2 session rather than being a dead-end accepted preview.
            String initializerTargetKey) {}

    public record SemanticStatementSelection(
            Path file,
            SourceRange range,
            SourceRange enclosingMethodRange,
            SourceRange enclosingTypeBodyRange,
            SourceRange suggestedRange,
            boolean completeStatements,
            boolean crossesLambdaOrClass,
            boolean hasControlFlowExit,
            List<SemanticExtractVariable> inputs,
            List<SemanticExtractVariable> outputs,
            Set<String> checkedExceptions,
            Element enclosingExecutable,
            boolean usesThis,
            boolean usesSuper,
            boolean enclosingMethodStatic) {
    }

    public record SemanticConstructor(SourceRange declarationRange, int bodyStart, int assignmentOffset, boolean delegatesToThis) {
    }

    /**
     * The semantic classification of a usage-narrowing candidate declaration, derived from the javac
     * {@link VariableElement} rather than any source-text nesting heuristic. {@code kind} is the precise declaration
     * role (field/parameter/local/record-component/etc.); {@code apiVisible} is the compiler-decided answer to "is this
     * declaration part of a type's visible API surface", which the extract-interface public-API confirmation gate uses
     * instead of brace/paren counting.
     */
    public record SemanticUsageNarrowing(
            SourceRange declarationTypeRange,
            List<String> calledMethodKeys,
            List<String> unsafeUses,
            DeclarationKind kind,
            boolean apiVisible,
            boolean enclosingTypeNested) {
    }

    /**
     * The precise semantic role of a variable-like declaration, mapped from {@link ElementKind} so the extract-interface
     * planner can classify every candidate without re-parsing source. {@code apiVisible} marks the kinds whose declared
     * type is observable from outside the declaring method body — i.e. it participates in a type's public API surface.
     */
    public enum DeclarationKind {
        FIELD(true),
        PARAMETER(true),
        RECORD_COMPONENT(true),
        ENUM_CONSTANT(true),
        LOCAL_VARIABLE(false),
        RESOURCE_VARIABLE(false),
        EXCEPTION_PARAMETER(false),
        BINDING_VARIABLE(false),
        OTHER(false);

        private final boolean apiVisible;

        DeclarationKind(boolean apiVisible) {
            this.apiVisible = apiVisible;
        }

        /** Whether a declaration of this kind contributes to a type's externally visible API surface. */
        public boolean isApiVisible() {
            return apiVisible;
        }

        /** Maps a javac {@link ElementKind} to its narrowing declaration kind. */
        static DeclarationKind from(ElementKind elementKind) {
            return switch (elementKind) {
                case FIELD -> FIELD;
                case PARAMETER -> PARAMETER;
                case RECORD_COMPONENT -> RECORD_COMPONENT;
                case ENUM_CONSTANT -> ENUM_CONSTANT;
                case LOCAL_VARIABLE -> LOCAL_VARIABLE;
                case RESOURCE_VARIABLE -> RESOURCE_VARIABLE;
                case EXCEPTION_PARAMETER -> EXCEPTION_PARAMETER;
                case BINDING_VARIABLE -> BINDING_VARIABLE;
                default -> OTHER;
            };
        }
    }

    public CharSequence sourceText(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            CharSequence source = task.sourceByPath.get(normalized);
            if (source != null) {
                return source;
            }
        }
        return null;
    }

    public SemanticExpressionSelection selectedExpression(Path file, int startLine, int startColumn, int endLine, int endColumn) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            CharSequence source = task.sourceByPath.get(normalized);
            if (source == null) {
                continue;
            }
            CompilationUnitTree unit = task.units.stream()
                    .filter(candidate -> normalized.equals(pathOf(candidate)))
                    .findFirst()
                    .orElse(null);
            if (unit == null) {
                continue;
            }
            int start = selectionOffset(unit, source, startLine, startColumn);
            int end = selectionOffset(unit, source, endLine, endColumn);
            if (end < start) {
                throw new IllegalArgumentException("Introduce parameter selection end must not precede start.");
            }
            int trimmedStart = trimSelectionStart(source, start, end);
            int trimmedEnd = trimSelectionEnd(source, trimmedStart, end);
            if (trimmedStart >= trimmedEnd) {
                throw new IllegalArgumentException("Introduce parameter selection must contain a Java expression.");
            }

            TreePath expressionPath = expressionPathCovering(task, unit, source, trimmedStart, trimmedEnd);
            if (expressionPath == null) {
                return null;
            }

            TypeMirror typeMirror = task.trees.getTypeMirror(expressionPath);
            String type = expressionTypeName(typeMirror);
            ExpressionTree expression = (ExpressionTree) expressionPath.getLeaf();
            io.serena.javarefactor.shared.ExpressionPurity purity = new io.serena.javarefactor.shared.ExpressionPurityAnalyzer().classify(expression);
            SourceRange range = new SourceRange(normalized, trimmedStart, trimmedEnd);
            TreePath methodPath = enclosingMethodPath(expressionPath);
            // When the selection is not inside a method/constructor body, the extraction scope is an initializer:
            // a class/instance initializer block (BlockTree) or a field initializer (VariableTree), each a direct
            // child of a ClassTree. The data-flow root is that scope; insertion targets the enclosing type body.
            TreePath scopePath = methodPath != null ? methodPath : enclosingInitializerScopePath(expressionPath);
            boolean initializerScope = methodPath == null && scopePath != null;
            ExpressionExtractionFlow flow = selectionIndex.expressionExtractionFlow(task, unit, source, scopePath, range);
            return new SemanticExpressionSelection(
                    normalized,
                    range,
                    enclosingMethodRange(task, unit, expressionPath),
                    initializerScope ? enclosingTypeBodyRange(task, unit, source, expressionPath) : null,
                    initializerScope,
                    range.text(this),
                    type,
                    purity,
                    enclosingExecutable(task, expressionPath),
                    flow.inputs(),
                    flow.checkedExceptions(),
                    flow.usesThis(),
                    flow.usesSuper(),
                    flow.enclosingMethodStatic(),
                    isCompileTimeConstantExpression(expressionPath),
                    initializerScope ? initializerTargetKey(task, scopePath, unit) : null);
        }
        return null;
    }

    /**
     * A stable, re-run-deterministic identity for an initializer extraction scope (Blocker 4), used as the V2 session
     * target when there is no enclosing executable. For a field initializer the host is the field, so its semantic key
     * is returned directly. For a class/instance initializer block (which has no element) the identity is the enclosing
     * type's semantic key plus a static/instance discriminator and the block's normalized start offset, which both
     * disambiguates sibling blocks and lets apply-time re-resolution detect that the block moved.
     */
    private String initializerTargetKey(CompilerTask task, TreePath scopePath, CompilationUnitTree unit) {
        if (scopePath == null) {
            return null;
        }
        Tree leaf = scopePath.getLeaf();
        if (leaf instanceof VariableTree) {
            Element field = task.trees.getElement(scopePath);
            return field == null ? null : SemanticKey.from(field).canonical();
        }
        if (leaf instanceof BlockTree block) {
            TreePath classPath = scopePath.getParentPath();
            Element type = classPath == null ? null : task.trees.getElement(classPath);
            if (type == null) {
                return null;
            }
            long offset = task.positions.getStartPosition(unit, block);
            String kind = block.isStatic() ? "static-initializer" : "instance-initializer";
            return SemanticKey.from(type).canonical() + "#" + kind + "@" + offset;
        }
        return null;
    }

    /**
     * Finds the narrowest {@link ExpressionTree} whose trimmed source bounds exactly equal the trimmed {@code [start,end]}
     * range within {@code unit}, or {@code null} when no expression node matches. Shared by {@link #selectedExpression}
     * and the purity bridge so both resolve a selection range to the same javac {@link TreePath}.
     */
    private TreePath expressionPathCovering(CompilerTask task, CompilationUnitTree unit, CharSequence source, int start, int end) {
        int trimmedStart = trimSelectionStart(source, start, end);
        int trimmedEnd = trimSelectionEnd(source, trimmedStart, end);
        if (trimmedStart >= trimmedEnd) {
            return null;
        }
        final int targetStart = trimmedStart;
        final int targetEnd = trimmedEnd;
        class SelectionScanner extends TreePathScanner<Void, Void> {
            private TreePath bestPath;
            private int bestWidth = Integer.MAX_VALUE;

            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree instanceof ExpressionTree) {
                    long rawStart = task.positions.getStartPosition(unit, tree);
                    long rawEnd = task.positions.getEndPosition(unit, tree);
                    if (rawStart <= targetStart && rawEnd >= targetEnd) {
                        int treeStart = trimSelectionStart(source, (int) rawStart, (int) rawEnd);
                        int treeEnd = trimSelectionEnd(source, treeStart, (int) rawEnd);
                        if (treeStart == targetStart && treeEnd == targetEnd) {
                            int width = treeEnd - treeStart;
                            if (width < bestWidth) {
                                bestPath = new TreePath(getCurrentPath(), tree);
                                bestWidth = width;
                            }
                        }
                    }
                }
                return super.scan(tree, unused);
            }
        }
        SelectionScanner scanner = new SelectionScanner();
        scanner.scan(unit, null);
        return scanner.bestPath;
    }

    /**
     * G002: returns the simple names of every field (or enum constant) that the expression covering {@code range} reads,
     * resolved through javac element bindings (so {@code (Target) raw} yields {@code raw} and {@code holder.target}
     * yields {@code holder} and {@code target}). The instance-method-move source-state guard excludes these from its
     * "moved body depends on source instance state" check because they ARE the receiver navigation that becomes
     * {@code this}; any standalone use the receiver rewrite leaves dangling is still caught by the javac diagnostic delta.
     * Returns an empty set when the range cannot be resolved to an expression node.
     */
    public java.util.Set<String> fieldNamesReadBy(Path file, SourceRange range) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        if (range == null) {
            return names;
        }
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            CharSequence source = task.sourceByPath.get(normalized);
            if (source == null) {
                continue;
            }
            CompilationUnitTree unit = task.units.stream()
                    .filter(candidate -> normalized.equals(pathOf(candidate)))
                    .findFirst()
                    .orElse(null);
            if (unit == null) {
                continue;
            }
            TreePath path = expressionPathCovering(task, unit, source, range.start(), range.end());
            if (path == null) {
                return names;
            }
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitIdentifier(IdentifierTree node, Void unused) {
                    record(new TreePath(getCurrentPath(), node));
                    return super.visitIdentifier(node, unused);
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                    record(new TreePath(getCurrentPath(), node));
                    return super.visitMemberSelect(node, unused);
                }

                private void record(TreePath nodePath) {
                    Element element = task.trees.getElement(nodePath);
                    if (element != null
                            && (element.getKind() == ElementKind.FIELD || element.getKind() == ElementKind.ENUM_CONSTANT)) {
                        names.add(element.getSimpleName().toString());
                    }
                }
            }.scan(path, null);
            return names;
        }
        return names;
    }

    /**
     * G004 purity bridge: resolves the tightest expression AST node covering {@code range} in its owning compiler task
     * and returns the canonical {@link io.serena.javarefactor.shared.ExpressionPurityAnalyzer#isReorderSafe(TreePath, Trees, Types)}
     * verdict. This is the only green-light a planner holding an AST-resolved expression range should consult before it
     * reorders, duplicates, hoists, or drops that expression. Returns {@code false} (refuse) when the range cannot be
     * resolved to an expression node — an unresolvable range can never be proven reorder-safe.
     */
    public boolean isExpressionReorderSafe(Path file, SourceRange range) {
        if (range == null) {
            return false;
        }
        return reorderSafeAt(file == null ? range.file() : file, range.start(), range.end());
    }

    /**
     * G004 purity bridge for a resolved call site: returns the canonical reorder-safety verdict for the argument at
     * {@code argumentIndex} (whose AST range was captured when the call site was indexed). Returns {@code false} when the
     * index is out of bounds or the argument expression cannot be resolved.
     */
    public boolean isCallArgumentReorderSafe(SemanticCallSite callSite, int argumentIndex) {
        if (callSite == null || argumentIndex < 0 || argumentIndex >= callSite.arguments().size()) {
            return false;
        }
        SemanticArgument argument = callSite.arguments().get(argumentIndex);
        if (argument == null || argument.range() == null) {
            return false;
        }
        return reorderSafeAt(argument.range().file(), argument.range().start(), argument.range().end());
    }

    /**
     * Resolves the expression node covering {@code [start,end]} in {@code file} and returns the canonical
     * {@code isReorderSafe} verdict against the owning task's {@code trees}/{@code types}. Refuses ({@code false}) when no
     * task owns the file or the range resolves to no expression node.
     */
    private boolean reorderSafeAt(Path file, int start, int end) {
        if (file == null) {
            return false;
        }
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            CharSequence source = task.sourceByPath.get(normalized);
            if (source == null) {
                continue;
            }
            CompilationUnitTree unit = task.units.stream()
                    .filter(candidate -> normalized.equals(pathOf(candidate)))
                    .findFirst()
                    .orElse(null);
            if (unit == null) {
                continue;
            }
            TreePath expressionPath = expressionPathCovering(task, unit, source, start, end);
            if (expressionPath == null) {
                return false;
            }
            return new io.serena.javarefactor.shared.ExpressionPurityAnalyzer()
                    .isReorderSafe(expressionPath, task.trees, task.types);
        }
        return false;
    }

    private boolean isCompileTimeConstantExpression(TreePath path) {
        Tree tree = path.getLeaf();
        if (tree instanceof LiteralTree) {
            return true;
        }
        if (tree instanceof ParenthesizedTree parenthesized) {
            return isCompileTimeConstantExpression(new TreePath(path, parenthesized.getExpression()));
        }
        if (tree instanceof UnaryTree unary) {
            return switch (unary.getKind()) {
                case UNARY_PLUS, UNARY_MINUS, LOGICAL_COMPLEMENT, BITWISE_COMPLEMENT ->
                        isCompileTimeConstantExpression(new TreePath(path, unary.getExpression()));
                default -> false;
            };
        }
        if (tree instanceof BinaryTree binary) {
            return isCompileTimeConstantExpression(new TreePath(path, binary.getLeftOperand()))
                    && isCompileTimeConstantExpression(new TreePath(path, binary.getRightOperand()));
        }
        if (tree instanceof ConditionalExpressionTree conditional) {
            return isCompileTimeConstantExpression(new TreePath(path, conditional.getCondition()))
                    && isCompileTimeConstantExpression(new TreePath(path, conditional.getTrueExpression()))
                    && isCompileTimeConstantExpression(new TreePath(path, conditional.getFalseExpression()));
        }
        Element element = trees.getElement(path);
        return element instanceof VariableElement variable && variable.getConstantValue() != null;
    }

    private static int selectionOffset(CompilationUnitTree unit, CharSequence source, int line, int column) {
        if (line < 1 || column < 1) {
            throw new IllegalArgumentException("Introduce parameter selection positions are one-based line and column values.");
        }
        long offset = unit.getLineMap().getPosition(line, column);
        if (offset < 0 || offset > source.length()) {
            throw new IllegalArgumentException("Introduce parameter selection range is outside the source file.");
        }
        return (int) offset;
    }

    static int trimSelectionStart(CharSequence source, int start, int end) {
        int cursor = Math.max(0, start);
        int limit = Math.min(source.length(), end);
        while (cursor < limit && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    static int trimSelectionEnd(CharSequence source, int start, int end) {
        int cursor = Math.min(source.length(), end);
        while (cursor > start && Character.isWhitespace(source.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    static String expressionTypeName(TypeMirror typeMirror) {
        if (typeMirror == null) {
            return null;
        }
        javax.lang.model.type.TypeKind kind = typeMirror.getKind();
        if (kind == javax.lang.model.type.TypeKind.NONE
                || kind == javax.lang.model.type.TypeKind.NULL
                || kind == javax.lang.model.type.TypeKind.VOID
                || kind == javax.lang.model.type.TypeKind.ERROR) {
            return null;
        }
        return typeMirror.toString();
    }

    private SourceRange enclosingMethodRange(CompilerTask task, CompilationUnitTree unit, TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof MethodTree method) {
                long start = task.positions.getStartPosition(unit, method);
                long end = task.positions.getEndPosition(unit, method);
                if (start >= 0 && end >= start) {
                    return new SourceRange(pathOf(unit), (int) start, (int) end);
                }
            }
        }
        return null;
    }

    Element enclosingExecutable(CompilerTask task, TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof MethodTree) {
                return task.trees.getElement(current);
            }
        }
        return null;
    }

    /**
     * The extraction scope for a selection that is NOT inside a method/constructor body: the nearest enclosing
     * class/instance initializer block ({@link BlockTree}) or field initializer ({@link VariableTree}) that is a direct
     * child of a {@link ClassTree}. Returns {@code null} when the selection has no such initializer scope (e.g. it sits
     * directly in an annotation argument). The returned path is the data-flow root used by the extraction-flow analysis.
     */
    private TreePath enclosingInitializerScopePath(TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            Tree leaf = current.getLeaf();
            if (leaf instanceof MethodTree) {
                return null;
            }
            if ((leaf instanceof BlockTree || leaf instanceof VariableTree)
                    && current.getParentPath() != null
                    && current.getParentPath().getLeaf() instanceof ClassTree) {
                return current;
            }
        }
        return null;
    }

    /** The {@code { ... }} body range (inclusive of both braces) of the nearest enclosing {@link ClassTree}. */
    private SourceRange enclosingTypeBodyRange(CompilerTask task, CompilationUnitTree unit, CharSequence source, TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof ClassTree clazz) {
                long start = task.positions.getStartPosition(unit, clazz);
                long end = task.positions.getEndPosition(unit, clazz);
                if (start >= 0 && end >= start) {
                    int open = firstChar(source, (int) start, (int) end, '{');
                    int close = lastChar(source, (int) start, (int) end, '}');
                    if (open >= 0 && close >= open) {
                        return new SourceRange(pathOf(unit), open, close + 1);
                    }
                }
            }
        }
        return null;
    }

    public SemanticType primaryType(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                if (!pathOf(unit).equals(normalized)) {
                    continue;
                }
                TreePath unitPath = new TreePath(unit);
                for (Tree decl : unit.getTypeDecls()) {
                    if (decl instanceof ClassTree clazz) {
                        return semanticType(task, unit, new TreePath(unitPath, clazz), clazz);
                    }
                }
            }
        }
        return null;
    }

    public SemanticMethod selectedMethod(Path file, int oneBasedLine, String nameHint) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                if (!pathOf(unit).equals(normalized)) {
                    continue;
                }
                long[] lineRange = lineRange(unit, oneBasedLine);
                if (lineRange == null) {
                    return null;
                }
                CharSequence source = task.sourceByPath.get(normalized);
                List<SemanticMethod> matches = new ArrayList<>();
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree node, Void unused) {
                        Element element = task.trees.getElement(getCurrentPath());
                        if (element instanceof ExecutableElement executable
                                && (executable.getKind() == ElementKind.METHOD || executable.getKind() == ElementKind.CONSTRUCTOR)) {
                            long start = task.positions.getStartPosition(unit, node);
                            long end = task.positions.getEndPosition(unit, node);
                            boolean lineHits = oneBasedLine <= 0 || (start <= lineRange[1] && end >= lineRange[0]);
                            boolean nameHits = nameHint == null || nameHint.isBlank() || node.getName().contentEquals(nameHint);
                            if (!nameHits
                                    && executable.getKind() == ElementKind.CONSTRUCTOR
                                    && executable.getEnclosingElement() instanceof TypeElement owner) {
                                nameHits = owner.getSimpleName().contentEquals(nameHint);
                            }
                            if (lineHits && nameHits) {
                    SemanticMethod method = semanticMethod(task, unit, getCurrentPath(), node, executable, source, (javax.lang.model.type.ExecutableType) executable.asType());
                                if (method != null) {
                                    matches.add(method);
                                }
                            }
                        }
                        return super.visitMethod(node, unused);
                    }
                }.scan(unit, null);
                return matches.stream()
                        .min(Comparator.comparingInt(m -> m.declarationRange().end() - m.declarationRange().start()))
                        .orElse(null);
            }
        }
        return null;
    }

    public SemanticField selectedField(Path file, int oneBasedLine, String nameHint) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                if (!pathOf(unit).equals(normalized)) {
                    continue;
                }
                long[] lineRange = lineRange(unit, oneBasedLine);
                if (lineRange == null) {
                    return null;
                }
                CharSequence source = task.sourceByPath.get(normalized);
                List<SemanticField> matches = new ArrayList<>();
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree node, Void unused) {
                        Element element = task.trees.getElement(getCurrentPath());
                        Tree parent = getCurrentPath().getParentPath() == null ? null : getCurrentPath().getParentPath().getLeaf();
                        if (parent instanceof ClassTree && element instanceof VariableElement variable && variable.getKind() == ElementKind.FIELD) {
                            long start = task.positions.getStartPosition(unit, node);
                            long end = task.positions.getEndPosition(unit, node);
                            boolean lineHits = oneBasedLine <= 0 || (start <= lineRange[1] && end >= lineRange[0]);
                            boolean nameHits = nameHint == null || nameHint.isBlank() || node.getName().contentEquals(nameHint);
                            if (lineHits && nameHits) {
                                SemanticField field = semanticField(task, unit, getCurrentPath(), node, variable, source);
                                if (field != null) {
                                    matches.add(field);
                                }
                            }
                        }
                        return super.visitVariable(node, unused);
                    }
                }.scan(unit, null);
                return matches.stream()
                        .min(Comparator.comparingInt(f -> f.declarationRange().end() - f.declarationRange().start()))
                        .orElse(null);
            }
        }
        return null;
    }

    public SemanticMember selectedMember(Path file, int oneBasedLine, String nameHint) {
        SemanticMethod method = selectedMethod(file, oneBasedLine, nameHint);
        SemanticField field = selectedField(file, oneBasedLine, nameHint);
        if (method == null) {
            return field == null ? null : new SemanticMember(SemanticMemberKind.FIELD, null, field);
        }
        if (field == null) {
            return new SemanticMember(SemanticMemberKind.METHOD, method, null);
        }
        int methodWidth = method.declarationRange().end() - method.declarationRange().start();
        int fieldWidth = field.declarationRange().end() - field.declarationRange().start();
        return methodWidth <= fieldWidth
                ? new SemanticMember(SemanticMemberKind.METHOD, method, null)
                : new SemanticMember(SemanticMemberKind.FIELD, null, field);
    }

    /**
     * G006: inspects every {@code return} statement in {@code method}'s own body and tests whether its returned
     * expression is assignable to {@code newReturnType}. Returns a {@code file:line:col} location string for the first
     * return expression that is NOT assignable to the requested type, or {@code null} when every return expression is
     * assignable (a compatible widening such as {@code int -> long} or {@code String -> Object}). Returns {@code null}
     * (defers to the javac preview-diagnostic backstop) when the method element/path/tree or the new return type cannot
     * be resolved with the available model, so this check only ever produces a precise refusal it is certain about.
     *
     * <p>Returns of {@code void} (bare {@code return;}) and {@code null} literals are always compatible. Returns nested
     * inside lambdas or anonymous/local classes belong to a different executable and are skipped.
     */
    public String returnBodyIncompatibility(SemanticMethod method, String newReturnType) {
        if (method == null || newReturnType == null || newReturnType.isBlank()
                || !(method.element() instanceof ExecutableElement executable)) {
            return null;
        }
        CompilerTask task = taskFor(method.file());
        if (task == null) {
            return null;
        }
        TreePath methodPath = task.trees.getPath(executable);
        if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree methodTree) || methodTree.getBody() == null) {
            return null;
        }
        CompilationUnitTree unit = methodPath.getCompilationUnit();
        TypeMirror targetType = resolveTypeMirrorInScope(task, methodPath, newReturnType);
        if (targetType == null) {
            // Cannot resolve the requested type precisely; leave the verdict to the javac preview-diagnostic backstop.
            return null;
        }
        String[] location = new String[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitReturn(ReturnTree node, Void unused) {
                if (location[0] == null) {
                    ExpressionTree expression = node.getExpression();
                    if (expression != null && !isNullLiteral(expression)) {
                        TreePath expressionPath = new TreePath(getCurrentPath(), expression);
                        TypeMirror returnType = task.trees.getTypeMirror(expressionPath);
                        if (returnType != null && !isUnresolvedType(returnType)
                                && !task.types.isAssignable(returnType, targetType)) {
                            location[0] = locationOf(task, unit, expression);
                        }
                    }
                }
                return super.visitReturn(node, unused);
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                return null; // returns inside a lambda belong to a different functional interface method
            }

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                return null; // returns inside an anonymous/local class belong to a different executable
            }
        }.scan(methodPath, null);
        return location[0];
    }

    /**
     * G001: body-return-conversion support. Collects the source spans of every value-returning {@code return}
     * expression that belongs to {@code method}'s own body (returns inside nested lambdas/anonymous-or-local classes
     * are excluded — they target a different executable, exactly as {@link #returnBodyIncompatibility} excludes them).
     * When a method-owned {@code return;} carries no value expression it cannot be wrapped by a single-expression
     * conversion template, so its location is reported in {@code unsupportedLocation} and the planner refuses. Spans are
     * returned in source order; the planner slices each span out of the declaring file's text and substitutes it into
     * the {@code $return} placeholder.
     */
    public ReturnBodyRewrite bodyReturnRewrite(SemanticMethod method) {
        if (method == null || !(method.element() instanceof ExecutableElement executable)) {
            return new ReturnBodyRewrite(java.util.List.of(), null);
        }
        CompilerTask task = taskFor(method.file());
        if (task == null) {
            return new ReturnBodyRewrite(java.util.List.of(), null);
        }
        TreePath methodPath = task.trees.getPath(executable);
        if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree methodTree) || methodTree.getBody() == null) {
            return new ReturnBodyRewrite(java.util.List.of(), null);
        }
        CompilationUnitTree unit = methodPath.getCompilationUnit();
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        String[] unsupported = new String[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitReturn(ReturnTree node, Void unused) {
                ExpressionTree expression = node.getExpression();
                if (expression == null) {
                    if (unsupported[0] == null) {
                        unsupported[0] = locationOf(task, unit, node);
                    }
                    return super.visitReturn(node, unused);
                }
                long start = task.positions.getStartPosition(unit, expression);
                long end = task.positions.getEndPosition(unit, expression);
                if (start >= 0 && end > start) {
                    spans.add(new int[] {(int) start, (int) end});
                } else if (unsupported[0] == null) {
                    unsupported[0] = locationOf(task, unit, node);
                }
                return super.visitReturn(node, unused);
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                return null; // returns inside a lambda belong to a different functional interface method
            }

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                return null; // returns inside an anonymous/local class belong to a different executable
            }
        }.scan(methodPath, null);
        return new ReturnBodyRewrite(spans, unsupported[0]);
    }

    /** Result of {@link #bodyReturnRewrite}: value-return spans to wrap, plus the location of any unwrappable bare return. */
    public record ReturnBodyRewrite(java.util.List<int[]> spans, String unsupportedLocation) {}

    private static boolean isNullLiteral(ExpressionTree expression) {
        return expression instanceof LiteralTree literal && literal.getKind() == Tree.Kind.NULL_LITERAL;
    }

    private static boolean isUnresolvedType(TypeMirror type) {
        javax.lang.model.type.TypeKind kind = type.getKind();
        return kind == javax.lang.model.type.TypeKind.ERROR || kind == javax.lang.model.type.TypeKind.NONE;
    }

    private String locationOf(CompilerTask task, CompilationUnitTree unit, Tree tree) {
        long start = task.positions.getStartPosition(unit, tree);
        Path file = pathOf(unit);
        if (start < 0) {
            return file.toString();
        }
        long line = unit.getLineMap().getLineNumber(start);
        long column = unit.getLineMap().getColumnNumber(start);
        return file + ":" + line + ":" + column;
    }

    /**
     * Resolves a type-name string to a {@link TypeMirror} in the lexical scope of {@code path}. Handles primitives,
     * {@code void}, fully-qualified names, simple names visible via the scope/imports of the declaring file, and arrays
     * of any of these. Generic type arguments are erased to the raw type for the assignability comparison. Returns
     * {@code null} when the name cannot be resolved precisely.
     */
    private TypeMirror resolveTypeMirrorInScope(CompilerTask task, TreePath path, String typeName) {
        String name = typeName.trim();
        if (name.endsWith("[]")) {
            TypeMirror component = resolveTypeMirrorInScope(task, path, name.substring(0, name.length() - 2).trim());
            return component == null ? null : task.types.getArrayType(component);
        }
        int generic = name.indexOf('<');
        String raw = generic >= 0 ? name.substring(0, generic).trim() : name;
        switch (raw) {
            case "void":
                return task.types.getNoType(javax.lang.model.type.TypeKind.VOID);
            case "boolean":
                return task.types.getPrimitiveType(javax.lang.model.type.TypeKind.BOOLEAN);
            case "byte":
                return task.types.getPrimitiveType(javax.lang.model.type.TypeKind.BYTE);
            case "short":
                return task.types.getPrimitiveType(javax.lang.model.type.TypeKind.SHORT);
            case "int":
                return task.types.getPrimitiveType(javax.lang.model.type.TypeKind.INT);
            case "long":
                return task.types.getPrimitiveType(javax.lang.model.type.TypeKind.LONG);
            case "char":
                return task.types.getPrimitiveType(javax.lang.model.type.TypeKind.CHAR);
            case "float":
                return task.types.getPrimitiveType(javax.lang.model.type.TypeKind.FLOAT);
            case "double":
                return task.types.getPrimitiveType(javax.lang.model.type.TypeKind.DOUBLE);
            default:
                break;
        }
        TypeElement typeElement = resolveTypeElementInScope(task, path, raw);
        if (typeElement == null) {
            return null;
        }
        return task.types.erasure(typeElement.asType());
    }

    /**
     * Resolves a type-name string to a {@link TypeElement}: directly when fully qualified, otherwise by matching the
     * simple name against the types visible in {@code path}'s scope (imports, same package, nested/inherited types).
     */
    private TypeElement resolveTypeElementInScope(CompilerTask task, TreePath path, String raw) {
        if (raw.contains(".")) {
            TypeElement direct = task.elements.getTypeElement(raw);
            if (direct != null) {
                return direct;
            }
        }
        TypeElement qualified = task.elements.getTypeElement(raw);
        if (qualified != null && qualified.getSimpleName().contentEquals(raw)) {
            return qualified;
        }
        String simple = raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
        try {
            for (Scope scope = task.trees.getScope(path); scope != null; scope = scope.getEnclosingScope()) {
                for (TypeElement type : typeElementsOf(scope.getLocalElements())) {
                    if (type.getSimpleName().contentEquals(simple)) {
                        return type;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Scope resolution can fail on malformed/partial sources; fall through to other strategies.
        }
        // java.lang is implicitly imported; resolve common simple names there.
        TypeElement javaLang = task.elements.getTypeElement("java.lang." + simple);
        if (javaLang != null) {
            return javaLang;
        }
        // Same-package or project type by simple name.
        for (TypeElement candidate : task.projectTypes()) {
            if (candidate.getSimpleName().contentEquals(simple)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<TypeElement> typeElementsOf(Iterable<? extends Element> elements) {
        List<TypeElement> result = new ArrayList<>();
        for (Element element : elements) {
            if (element instanceof TypeElement type) {
                result.add(type);
            }
        }
        return result;
    }

    /**
     * G005: resolves the default expression {@code defaultExpr} in {@code site}'s lexical scope and reports whether a
     * type it references cannot be resolved to an accessible type at that call site. Returns a {@code file:line:col}
     * location string for the call site when a simple-name type token in the default is either unresolvable or resolves
     * to a type that is not accessible from the call site's scope (e.g. a package-private type in another package, or an
     * ambiguous simple name with no visible binding). Returns {@code null} when every referenced type resolves to an
     * accessible type, or when the call-site path/scope cannot be resolved (deferring to the javac preview-diagnostic
     * backstop). Fully-qualified type tokens are skipped here: they are handled by the import-rewrite default path.
     */
    public String defaultExpressionResolutionFailure(SemanticCallSite site, String defaultExpr) {
        if (site == null || defaultExpr == null || defaultExpr.isBlank()) {
            return null;
        }
        TreePath callPath = callSitePath(site);
        if (callPath == null) {
            return null;
        }
        CompilerTask task = taskFor(site.file());
        if (task == null) {
            return null;
        }
        Scope scope;
        try {
            scope = task.trees.getScope(callPath);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (scope == null) {
            return null;
        }
        for (String token : typePositionSimpleNames(defaultExpr)) {
            TypeElement resolved = resolveSimpleTypeInScope(task, scope, token);
            if (resolved == null || !task.trees.isAccessible(scope, resolved)) {
                return locationOf(task, callPath.getCompilationUnit(), callPath.getLeaf());
            }
        }
        return null;
    }

    /**
     * G005: extracts only the SIMPLE type names that appear in an unambiguous type position within a value default
     * expression: {@code new TypeName(...)}/{@code new TypeName[...]} creations, a {@code TypeName.member} static access
     * whose leftmost qualifier is a type (capitalized simple name), and {@code (TypeName) expr} casts. String/char
     * literals are stripped first so identifiers inside them are never treated as types, and value identifiers, method
     * names, parameters, and locals are not in any of these positions — keeping the resolution check free of false
     * positives. Fully-qualified type references (containing a dot before the simple name) are excluded: the
     * import-rewrite default path handles those.
     */
    private static Set<String> typePositionSimpleNames(String defaultExpr) {
        String stripped = stripLiterals(defaultExpr);
        Set<String> names = new LinkedHashSet<>();
        // new TypeName( ... ) or new TypeName[ ... ] — capture the final simple segment, skip qualified creations.
        java.util.regex.Matcher creation = java.util.regex.Pattern.compile("\\bnew\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*[\\(\\[<]").matcher(stripped);
        while (creation.find()) {
            addIfBareType(names, creation.group(1));
        }
        // (TypeName) expr cast — a capitalized simple name in parenthesized cast position.
        java.util.regex.Matcher cast = java.util.regex.Pattern.compile("\\(\\s*([A-Z][A-Za-z0-9_$]*)\\s*\\)").matcher(stripped);
        while (cast.find()) {
            names.add(cast.group(1));
        }
        // TypeName.member static access — a capitalized leftmost qualifier that is not preceded by '.' (so not a member).
        java.util.regex.Matcher staticAccess = java.util.regex.Pattern.compile("(?<![\\w.$])([A-Z][A-Za-z0-9_$]*)\\s*\\.").matcher(stripped);
        while (staticAccess.find()) {
            names.add(staticAccess.group(1));
        }
        return names;
    }

    private static void addIfBareType(Set<String> names, String reference) {
        if (reference != null && !reference.isBlank() && !reference.contains(".")) {
            names.add(reference);
        }
    }

    /** Replaces the contents of string and char literals with spaces so their inner identifiers are never scanned. */
    private static String stripLiterals(String expression) {
        StringBuilder out = new StringBuilder(expression.length());
        int index = 0;
        int length = expression.length();
        while (index < length) {
            char ch = expression.charAt(index);
            if (ch == '"' || ch == '\'') {
                char quote = ch;
                out.append(' ');
                index++;
                while (index < length) {
                    char inner = expression.charAt(index);
                    out.append(' ');
                    index++;
                    if (inner == '\\' && index < length) {
                        out.append(' ');
                        index++;
                    } else if (inner == quote) {
                        break;
                    }
                }
            } else {
                out.append(ch);
                index++;
            }
        }
        return out.toString();
    }

    /** Resolves a simple type name visible in {@code scope}, or {@code null} when no accessible binding exists. */
    private TypeElement resolveSimpleTypeInScope(CompilerTask task, Scope scope, String simple) {
        for (Scope current = scope; current != null; current = current.getEnclosingScope()) {
            for (TypeElement type : typeElementsOf(current.getLocalElements())) {
                if (type.getSimpleName().contentEquals(simple)) {
                    return type;
                }
            }
        }
        TypeElement javaLang = task.elements.getTypeElement("java.lang." + simple);
        if (javaLang != null) {
            return javaLang;
        }
        return null;
    }

    public List<SemanticCallSite> methodCallSites(SemanticMethod method) {
        if (method == null || method.element() == null) {
            return List.of();
        }
        String homeKey = canonicalKeyInHome(method.element());
        List<SemanticCallSite> result = new ArrayList<>();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                CharSequence source = task.sourceByPath.get(file.toAbsolutePath().normalize());
                if (source == null) {
                    continue;
                }
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        Element resolved = task.trees.getElement(getCurrentPath());
                        if (resolved != null && homeKey.equals(SemanticKey.from(resolved, task.trees, task.types, unit, file).canonical())) {
                            SemanticCallSite site = invocationSite(task, unit, file, source, getCurrentPath(), node);
                            if (site != null) {
                                result.add(site);
                            }
                        }
                        return super.visitMethodInvocation(node, unused);
                    }

                    @Override
                    public Void visitNewClass(NewClassTree node, Void unused) {
                        Element resolved = task.trees.getElement(getCurrentPath());
                        if (resolved != null && homeKey.equals(SemanticKey.from(resolved, task.trees, task.types, unit, file).canonical())) {
                            SemanticCallSite site = constructorSite(task, unit, file, source, getCurrentPath(), node);
                            if (site != null) {
                                result.add(site);
                            }
                        }
                        return super.visitNewClass(node, unused);
                    }

                    @Override
                    public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                        Element resolved = resolveMemberReferenceTarget(task, unit, file, getCurrentPath(), node);
                        if (resolved != null && homeKey.equals(SemanticKey.from(resolved, task.trees, task.types, unit, file).canonical())) {
                            long start = task.positions.getStartPosition(unit, node);
                            long end = task.positions.getEndPosition(unit, node);
                            IdentifierSpan span = spanFinder.find(file, unit, task.positions, node, resolved, source);
                            if (start >= 0 && end >= start && span != null) {
                                SourceRange full = new SourceRange(file, (int) start, (int) end);
                                SourceRange name = new SourceRange(file, (int) span.startOffset(), (int) span.endOffset());
                                result.add(new SemanticCallSite(file, full, name, null, "", List.of(), true, false));
                            }
                        }
                        return super.visitMemberReference(node, unused);
                    }
                }.scan(unit, null);
            }
        }
        return result.stream()
                .sorted(Comparator.comparing((SemanticCallSite site) -> site.file().toString())
                        .thenComparingInt(site -> site.invocationRange().start()))
                .toList();
    }

    /**
     * G006: structured verdict for a single method-reference call site under a proposed signature change. A method
     * reference {@code Type::m} / {@code obj::m} / {@code Type::new} is converted by javac to a functional-interface
     * target type whose single abstract method (SAM) descriptor is a hard contract: it fixes the arity and the
     * parameter/return types the reference must satisfy. The target functional interface does NOT change when the
     * referenced executable's signature changes, so the reference stays valid iff the NEW executable shape still
     * conforms to that SAM under the same binding mode (bound receiver, unbound instance, static, or constructor).
     *
     * <p>{@code newParameterTypes} are the proposed parameter types (source spellings, resolved in the reference's
     * lexical scope), {@code newReturnType} the proposed return type (ignored/blank for constructors), and
     * {@code newName} the proposed simple name. The verdict is {@link MethodReferenceVerdict#safe(boolean)} when the new
     * shape conforms — carrying whether the visible name token must be rewritten — or
     * {@link MethodReferenceVerdict#incompatible(String, String)} with a structured reason and {@code file:line:col}
     * location when the change would break the functional-interface contract (arity no longer matches, a parameter is no
     * longer assignable from the SAM's, or the return type is no longer assignable to the SAM's). When the target SAM or
     * binding cannot be resolved precisely the verdict is {@link MethodReferenceVerdict#unresolved(String)} so the caller
     * can fail closed.
     */
    public MethodReferenceVerdict methodReferenceVerdict(
            SemanticCallSite refSite, List<String> newParameterTypes, String newReturnType, String newName) {
        if (refSite == null || !refSite.methodReference()) {
            return MethodReferenceVerdict.unresolved("Call site is not a method reference.");
        }
        CompilerTask task = taskFor(refSite.file());
        if (task == null) {
            return MethodReferenceVerdict.unresolved("No compiler task owns " + refSite.file() + ".");
        }
        for (CompilationUnitTree unit : task.units) {
            if (!refSite.file().toAbsolutePath().normalize().equals(pathOf(unit))) {
                continue;
            }
            MethodReferenceVerdict[] verdict = {null};
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                    long start = task.positions.getStartPosition(unit, node);
                    long end = task.positions.getEndPosition(unit, node);
                    if (start == refSite.invocationRange().start() && end == refSite.invocationRange().end()) {
                        verdict[0] = evaluateMethodReference(
                                task, unit, getCurrentPath(), node, newParameterTypes, newReturnType, newName);
                    }
                    return verdict[0] == null ? super.visitMemberReference(node, unused) : null;
                }
            }.scan(unit, null);
            if (verdict[0] != null) {
                return verdict[0];
            }
        }
        return MethodReferenceVerdict.unresolved("Could not locate the method-reference node at " + refSite.file() + ".");
    }

    private MethodReferenceVerdict evaluateMethodReference(
            CompilerTask task,
            CompilationUnitTree unit,
            TreePath refPath,
            MemberReferenceTree node,
            List<String> newParameterTypes,
            String newReturnType,
            String newName) {
        String location = locationOf(task, unit, node);
        Element referenced = resolveMemberReferenceTarget(task, unit, pathOf(unit), refPath, node);
        if (!(referenced instanceof ExecutableElement referencedMethod)) {
            return MethodReferenceVerdict.unresolved("Method reference at " + location + " did not resolve to an executable.");
        }
        boolean constructorRef = node.getMode() == MemberReferenceTree.ReferenceMode.NEW
                || referencedMethod.getKind() == ElementKind.CONSTRUCTOR;

        // Resolve the target functional interface from the reference's target-typing context (javac reports the member
        // reference node's own type as a poly/error type, so the contract comes from the enclosing assignment, return,
        // variable, or argument position) and its single abstract method as instantiated for that type.
        TypeMirror targetType = targetFunctionalType(task, refPath);
        ExecutableType sam = resolveFunctionalDescriptor(task, targetType);
        if (sam == null) {
            return MethodReferenceVerdict.unresolved(
                    "Could not resolve the target functional-interface descriptor for the method reference at " + location + ".");
        }
        List<? extends TypeMirror> samParameters = sam.getParameterTypes();
        TypeMirror samReturn = sam.getReturnType();

        // Determine how the SAM parameters map onto the referenced method's value parameters. For an UNBOUND instance
        // reference (Type::instanceMethod with no receiver value), SAM parameter 0 is the receiver and SAM parameters
        // 1.. bind to the method's parameters; otherwise (bound receiver, static, or constructor) the SAM parameters
        // bind 1:1 to the method's parameters.
        boolean unboundInstance = !constructorRef
                && !referencedMethod.getModifiers().contains(Modifier.STATIC)
                && qualifierIsType(task, refPath, node);
        int receiverOffset = unboundInstance ? 1 : 0;
        int expectedMethodArity = samParameters.size() - receiverOffset;
        if (expectedMethodArity < 0) {
            return MethodReferenceVerdict.unresolved("Method reference at " + location + " has an inconsistent receiver binding.");
        }
        if (newParameterTypes.size() != expectedMethodArity) {
            return MethodReferenceVerdict.incompatible(
                    "METHOD_REFERENCE_ARITY_CHANGE",
                    "The method reference at " + location + " binds to a functional interface whose single abstract "
                            + "method takes " + expectedMethodArity + " argument(s); the new signature takes "
                            + newParameterTypes.size() + ". Changing the arity breaks the functional-interface contract.");
        }
        // Each new method parameter must accept the value the SAM will pass it: the SAM parameter type must be
        // assignable to the new method parameter type (contravariant parameter position).
        for (int methodIndex = 0; methodIndex < newParameterTypes.size(); methodIndex++) {
            TypeMirror samParam = samParameters.get(methodIndex + receiverOffset);
            TypeMirror newParam = resolveTypeMirrorInScope(task, refPath, newParameterTypes.get(methodIndex));
            if (newParam == null) {
                return MethodReferenceVerdict.unresolved(
                        "Could not resolve new parameter type '" + newParameterTypes.get(methodIndex)
                                + "' at the method reference " + location + ".");
            }
            if (!isAssignableErased(task, samParam, newParam)) {
                return MethodReferenceVerdict.incompatible(
                        "METHOD_REFERENCE_PARAMETER_INCOMPATIBLE",
                        "The method reference at " + location + " would pass a " + samParam
                                + " into the new parameter of type '" + newParameterTypes.get(methodIndex)
                                + "', which is not assignable. The change breaks the functional-interface contract.");
            }
        }
        // The new return must be assignable to what the SAM produces (covariant return position). A void SAM accepts any
        // return (the result is discarded); constructors always yield the constructed type, handled by javac, so the
        // return check only applies to non-constructor references with a non-void SAM return.
        if (!constructorRef && samReturn.getKind() != TypeKind.VOID) {
            TypeMirror newReturn = resolveTypeMirrorInScope(task, refPath, newReturnType);
            if (newReturn == null) {
                return MethodReferenceVerdict.unresolved(
                        "Could not resolve new return type '" + newReturnType + "' at the method reference " + location + ".");
            }
            if (!isAssignableErased(task, newReturn, samReturn)) {
                return MethodReferenceVerdict.incompatible(
                        "METHOD_REFERENCE_RETURN_INCOMPATIBLE",
                        "The method reference at " + location + " feeds its result to a functional interface expecting "
                                + samReturn + "; the new return type '" + newReturnType + "' is not assignable to it.");
            }
        }
        boolean nameRewriteRequired = !constructorRef && !referencedMethod.getSimpleName().contentEquals(newName);
        return MethodReferenceVerdict.safe(nameRewriteRequired);
    }

    /** Whether {@code from} is assignable to {@code to}, comparing erased types so generic instantiations do not block a sound rewrite. */
    private boolean isAssignableErased(CompilerTask task, TypeMirror from, TypeMirror to) {
        if (from == null || to == null) {
            return false;
        }
        try {
            return task.types.isAssignable(task.types.erasure(from), task.types.erasure(to));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Resolves the executable a method reference targets. {@code Trees.getElement} on a {@link MemberReferenceTree} path
     * returns {@code null} in some javac configurations, so when it does we fall back to resolving the qualifier's type
     * and matching the referenced member by name: for {@code Type::new} the constructors of the qualifier type; otherwise
     * the method(s) named by the reference. When the name is overloaded we return the sole match (overload-ambiguous
     * references are handled by the caller's arity/type conformance check).
     */
    private Element resolveMemberReferenceTarget(CompilerTask task, CompilationUnitTree unit, Path file, TreePath refPath, MemberReferenceTree node) {
        Element direct = task.trees.getElement(refPath);
        if (direct instanceof ExecutableElement) {
            return direct;
        }
        ExpressionTree qualifier = node.getQualifierExpression();
        if (qualifier == null) {
            return null;
        }
        TypeMirror qualifierType = task.trees.getTypeMirror(new TreePath(refPath, qualifier));
        TypeElement owner = null;
        if (qualifierType instanceof javax.lang.model.type.DeclaredType declared
                && declared.asElement() instanceof TypeElement typeElement) {
            owner = typeElement;
        } else {
            Element qualifierElement = task.trees.getElement(new TreePath(refPath, qualifier));
            if (qualifierElement instanceof TypeElement typeElement) {
                owner = typeElement;
            }
        }
        if (owner == null) {
            return null;
        }
        boolean constructorRef = node.getMode() == MemberReferenceTree.ReferenceMode.NEW;
        String referenceName = node.getName().toString();
        ExecutableElement match = null;
        for (Element member : task.elements.getAllMembers(owner)) {
            if (!(member instanceof ExecutableElement executable)) {
                continue;
            }
            boolean candidate = constructorRef
                    ? executable.getKind() == ElementKind.CONSTRUCTOR
                    : executable.getKind() == ElementKind.METHOD && executable.getSimpleName().contentEquals(referenceName);
            if (!candidate) {
                continue;
            }
            if (match != null) {
                return null; // overloaded reference target: fail closed and let the caller refuse
            }
            match = executable;
        }
        return match;
    }

    /** Whether the method reference's qualifier denotes a TYPE (an unbound {@code Type::instanceMethod}) rather than a value receiver. */
    private boolean qualifierIsType(CompilerTask task, TreePath refPath, MemberReferenceTree node) {
        ExpressionTree qualifier = node.getQualifierExpression();
        if (qualifier == null) {
            return false;
        }
        Element qualifierElement = task.trees.getElement(new TreePath(refPath, qualifier));
        return qualifierElement instanceof TypeElement;
    }

    /**
     * Resolves the functional-interface type a method reference is converted to, taken from its target-typing context.
     * javac reports the member-reference node's own type as a poly/error type, so the contract is read from the enclosing
     * tree: a variable initializer gives the variable's declared type, a return gives the enclosing method's return type,
     * an assignment gives the assigned variable's type, and an argument position gives the invoked executable's parameter
     * type at that index (widening to the varargs component when applicable). Returns {@code null} when the context cannot
     * be resolved to a declared type, so the caller fails closed.
     */
    private TypeMirror targetFunctionalType(CompilerTask task, TreePath refPath) {
        TreePath parentPath = refPath.getParentPath();
        if (parentPath == null) {
            return null;
        }
        Tree parent = parentPath.getLeaf();
        switch (parent.getKind()) {
            case VARIABLE: {
                TypeMirror type = task.trees.getTypeMirror(parentPath);
                return type != null && type.getKind() == TypeKind.DECLARED ? type : null;
            }
            case ASSIGNMENT: {
                com.sun.source.tree.AssignmentTree assignment = (com.sun.source.tree.AssignmentTree) parent;
                TypeMirror type = task.trees.getTypeMirror(new TreePath(parentPath, assignment.getVariable()));
                return type != null && type.getKind() == TypeKind.DECLARED ? type : null;
            }
            case RETURN: {
                for (TreePath cursor = parentPath; cursor != null; cursor = cursor.getParentPath()) {
                    if (cursor.getLeaf() instanceof MethodTree methodTree) {
                        Element method = task.trees.getElement(cursor);
                        if (method instanceof ExecutableElement executable) {
                            TypeMirror returnType = executable.getReturnType();
                            return returnType != null && returnType.getKind() == TypeKind.DECLARED ? returnType : null;
                        }
                        return null;
                    }
                    if (cursor.getLeaf() instanceof LambdaExpressionTree) {
                        return null; // the reference is returned from a lambda: its target is the lambda's SAM return, unsupported here
                    }
                }
                return null;
            }
            case METHOD_INVOCATION: {
                MethodInvocationTree invocation = (MethodInvocationTree) parent;
                return argumentTargetType(task, parentPath, invocation.getArguments(), task.trees.getElement(parentPath), refPath);
            }
            case NEW_CLASS: {
                NewClassTree creation = (NewClassTree) parent;
                return argumentTargetType(task, parentPath, creation.getArguments(), task.trees.getElement(parentPath), refPath);
            }
            default:
                return null;
        }
    }

    /** The declared parameter type of {@code invoked} at the position the method reference occupies in {@code arguments}. */
    private TypeMirror argumentTargetType(
            CompilerTask task, TreePath invocationPath, List<? extends ExpressionTree> arguments, Element invoked, TreePath refPath) {
        if (!(invoked instanceof ExecutableElement executable)) {
            return null;
        }
        int argIndex = -1;
        for (int index = 0; index < arguments.size(); index++) {
            if (arguments.get(index) == refPath.getLeaf()) {
                argIndex = index;
                break;
            }
        }
        if (argIndex < 0) {
            return null;
        }
        List<? extends VariableElement> parameters = executable.getParameters();
        if (parameters.isEmpty()) {
            return null;
        }
        TypeMirror parameterType;
        if (argIndex < parameters.size()) {
            parameterType = parameters.get(argIndex).asType();
        } else if (executable.isVarArgs()) {
            parameterType = parameters.get(parameters.size() - 1).asType();
        } else {
            return null;
        }
        if (parameterType.getKind() == TypeKind.ARRAY && executable.isVarArgs() && argIndex >= parameters.size() - 1) {
            parameterType = ((javax.lang.model.type.ArrayType) parameterType).getComponentType();
        }
        return parameterType.getKind() == TypeKind.DECLARED ? parameterType : null;
    }

    /**
     * Resolves the single-abstract-method (SAM) descriptor of {@code functionalType}, instantiated for that type's type
     * arguments via {@link Types#asMemberOf}. Returns {@code null} when the type is not a declared interface with exactly
     * one abstract instance method (so the caller fails closed rather than rewriting against an unknown contract).
     */
    private ExecutableType resolveFunctionalDescriptor(CompilerTask task, TypeMirror functionalType) {
        if (!(functionalType instanceof javax.lang.model.type.DeclaredType declared)
                || !(declared.asElement() instanceof TypeElement typeElement)) {
            return null;
        }
        ExecutableElement sam = null;
        for (Element member : task.elements.getAllMembers(typeElement)) {
            if (member instanceof ExecutableElement executable
                    && executable.getModifiers().contains(Modifier.ABSTRACT)
                    && executable.getKind() == ElementKind.METHOD
                    && !isObjectClassMethod(task, executable)) {
                if (sam != null) {
                    return null; // more than one abstract method: not a functional interface descriptor we can use
                }
                sam = executable;
            }
        }
        if (sam == null) {
            return null;
        }
        try {
            TypeMirror member = task.types.asMemberOf(declared, sam);
            return member instanceof ExecutableType executableType ? executableType : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Whether {@code method} is a public method declared by {@code java.lang.Object} (those never count toward the SAM). */
    private boolean isObjectClassMethod(CompilerTask task, ExecutableElement method) {
        Element owner = method.getEnclosingElement();
        return owner instanceof TypeElement type && type.getQualifiedName().contentEquals("java.lang.Object");
    }

    /** Outcome of a method-reference conformance check under a proposed signature change (G006). */
    public record MethodReferenceVerdict(Kind kind, boolean nameRewriteRequired, String code, String message) {
        public enum Kind { SAFE, INCOMPATIBLE, UNRESOLVED }

        static MethodReferenceVerdict safe(boolean nameRewriteRequired) {
            return new MethodReferenceVerdict(Kind.SAFE, nameRewriteRequired, null, null);
        }

        static MethodReferenceVerdict incompatible(String code, String message) {
            return new MethodReferenceVerdict(Kind.INCOMPATIBLE, false, code, message);
        }

        static MethodReferenceVerdict unresolved(String message) {
            return new MethodReferenceVerdict(Kind.UNRESOLVED, false, "METHOD_REFERENCE_UNRESOLVED", message);
        }

        public boolean safe() {
            return kind == Kind.SAFE;
        }

        public boolean incompatible() {
            return kind == Kind.INCOMPATIBLE;
        }

        public boolean unresolved() {
            return kind == Kind.UNRESOLVED;
        }
    }

    public List<IdentifierSpan> referencesTo(SemanticMember member) {
        if (member == null || member.element() == null) {
            return List.of();
        }
        return scanReferences(List.of(member.element()));
    }

    public List<IdentifierSpan> parameterReferences(SemanticMethod method, String parameterName) {
        if (method == null || parameterName == null) {
            return List.of();
        }
        for (SemanticParameter parameter : method.parameters()) {
            if (parameter.name().equals(parameterName) && parameter.element() != null) {
                return scanReferences(List.of(parameter.element()));
            }
        }
        return List.of();
    }

    public boolean methodReferencePresent(SemanticMethod method) {
        return methodCallSites(method).stream().anyMatch(SemanticCallSite::methodReference);
    }

    public boolean hasSameArityMethod(SemanticMethod method, String name, int arity) {
        if (method == null || method.element() == null || !(method.element().getEnclosingElement() instanceof TypeElement owner)) {
            return false;
        }
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement other
                    && other.getKind() == ElementKind.METHOD
                    && !other.equals(method.element())
                    && other.getSimpleName().contentEquals(name)
                    && other.getParameters().size() == arity) {
                return true;
            }
        }
        return false;
    }

    public boolean hasOverloadSibling(SemanticMethod method) {
        if (method == null || method.element() == null || !(method.element().getEnclosingElement() instanceof TypeElement owner)) {
            return false;
        }
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement other
                    && other.getKind() == ElementKind.METHOD
                    && !other.equals(method.element())
                    && other.getSimpleName().contentEquals(method.name())) {
                return true;
            }
        }
        return false;
    }

    public boolean methodNameExists(Path file, String name) {
        SemanticType type = primaryType(file);
        if (type == null || !(type.element() instanceof TypeElement owner)) {
            return false;
        }
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement method
                    && method.getKind() == ElementKind.METHOD
                    && method.getSimpleName().contentEquals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The declaration range of the first method in {@code file}'s primary type that collides with an accessor named
     * {@code name} taking {@code arity} parameters (a getter is no-arg, a setter is 1-arg), or {@code null} when no
     * such conflicting method exists. Used by encapsulate field to refuse with a precise location when a generated
     * accessor name already exists with the same arity, while leaving genuinely-distinct overloads alone.
     */
    public SourceRange accessorCollisionRange(Path file, String name, int arity) {
        SemanticType type = primaryType(file);
        if (type == null || !(type.element() instanceof TypeElement owner)) {
            return null;
        }
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement method
                    && method.getKind() == ElementKind.METHOD
                    && method.getSimpleName().contentEquals(name)
                    && method.getParameters().size() == arity) {
                TreePath path = trees.getPath(method);
                if (path == null || !(path.getLeaf() instanceof MethodTree methodTree)) {
                    continue;
                }
                CompilationUnitTree unit = path.getCompilationUnit();
                long start = positions.getStartPosition(unit, methodTree);
                long end = positions.getEndPosition(unit, methodTree);
                if (start < 0 || end < start) {
                    return new SourceRange(pathOf(unit), 0, 0);
                }
                return new SourceRange(pathOf(unit), (int) start, (int) end);
            }
        }
        return null;
    }

    public boolean fieldNameExists(Path file, String name) {
        SemanticType type = primaryType(file);
        if (type == null || !(type.element() instanceof TypeElement owner)) {
            return false;
        }
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD && enclosed.getSimpleName().contentEquals(name)) {
                return true;
            }
        }
        return false;
    }

    public SemanticField fieldByName(Path file, String name) {
        SemanticType type = primaryType(file);
        if (type == null || !(type.element() instanceof TypeElement owner)) {
            return null;
        }
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof VariableElement field
                    && field.getKind() == ElementKind.FIELD
                    && field.getSimpleName().contentEquals(name)) {
                TreePath path = trees.getPath(field);
                if (path != null && path.getLeaf() instanceof VariableTree variable) {
                    return semanticField(home, path.getCompilationUnit(), path, variable, field, sourceByPath.get(pathOf(path.getCompilationUnit())));
                }
            }
        }
        return null;
    }

    public List<SemanticMember> privateMembers(Path file, String excludedName) {
        SemanticType type = primaryType(file);
        if (type == null || !(type.element() instanceof TypeElement owner)) {
            return List.of();
        }
        List<SemanticMember> members = new ArrayList<>();
        for (Element enclosed : owner.getEnclosedElements()) {
            if (!enclosed.getModifiers().contains(Modifier.PRIVATE)
                    || enclosed.getSimpleName().contentEquals(excludedName)) {
                continue;
            }
            TreePath path = trees.getPath(enclosed);
            if (path == null) {
                continue;
            }
            if (enclosed instanceof ExecutableElement method
                    && method.getKind() == ElementKind.METHOD
                    && path.getLeaf() instanceof MethodTree methodTree) {
                SemanticMethod semantic = semanticMethod(home, path.getCompilationUnit(), path, methodTree, method, sourceByPath.get(pathOf(path.getCompilationUnit())), (javax.lang.model.type.ExecutableType) method.asType());
                if (semantic != null) {
                    members.add(new SemanticMember(SemanticMemberKind.METHOD, semantic, null));
                }
            } else if (enclosed instanceof VariableElement field
                    && field.getKind() == ElementKind.FIELD
                    && path.getLeaf() instanceof VariableTree variable) {
                SemanticField semantic = semanticField(home, path.getCompilationUnit(), path, variable, field, sourceByPath.get(pathOf(path.getCompilationUnit())));
                if (semantic != null) {
                    members.add(new SemanticMember(SemanticMemberKind.FIELD, null, semantic));
                }
            }
        }
        return members;
    }

    /**
     * AST-backed safety facts for relocating an instance method to a new receiver (HB-4). Every fact is derived from the
     * method's resolved javac {@link TreePath} and symbol bindings — never from raw source text — so comments, strings,
     * Javadoc, and incidental whitespace (e.g. {@code super . foo()}) cannot produce a false positive or a false miss:
     * <ul>
     *   <li>{@link #usesSuper()} — the body dispatches through {@code super} (or {@code Outer.super}).</li>
     *   <li>{@link #synchronizedOnReceiver()} — the method is {@code synchronized} or contains a {@code synchronized}
     *       block locking on {@code this}; the monitor object would change with the receiver.</li>
     *   <li>{@link #sourceTypeParameterDependency()} — a resolved type in the signature or body binds to a type variable
     *       declared by the SOURCE type (or an enclosing type), which the new receiver type cannot supply. Shadowing
     *       method/nested type parameters resolve to their own element and are correctly excluded.</li>
     *   <li>{@link #thisEscapes()} — the source {@code this} is used as a value (argument, return, assignment, or
     *       {@code this::m}) rather than only as an implicit/explicit receiver.</li>
     * </ul>
     * {@link #resolved()} is false when the method's tree could not be analyzed; callers must fail closed (refuse).
     */
    public record InstanceMoveFacts(
            boolean resolved,
            boolean usesSuper,
            boolean synchronizedOnReceiver,
            boolean thisEscapes,
            String sourceTypeParameterDependency) {

        static InstanceMoveFacts unresolved() {
            return new InstanceMoveFacts(false, false, false, false, null);
        }
    }

    public InstanceMoveFacts instanceMoveFacts(SemanticMethod method) {
        if (method == null || !(method.element() instanceof ExecutableElement executable)) {
            return InstanceMoveFacts.unresolved();
        }
        CompilerTask task = taskFor(method.file());
        if (task == null) {
            return InstanceMoveFacts.unresolved();
        }
        TreePath methodPath = task.trees.getPath(executable);
        if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree)) {
            return InstanceMoveFacts.unresolved();
        }

        Set<Element> sourceTypeParameters = enclosingTypeParameters(executable);
        Set<Element> methodTypeParameters = new java.util.HashSet<>(executable.getTypeParameters());

        boolean[] usesSuper = {false};
        boolean[] synchronizedOnReceiver = {executable.getModifiers().contains(Modifier.SYNCHRONIZED)};
        boolean[] thisEscapes = {false};
        String[] typeParameterDependency = {null};

        // Pre-seed the type-variable scan with the resolved signature types (return/params/throws) — these bind to
        // source type variables without ever appearing as a body identifier.
        if (method.memberType() != null) {
            recordSourceTypeVariable(method.memberType(), sourceTypeParameters, typeParameterDependency);
        }

        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                // `super.x` (the selected-on expression IS the `super` keyword) and qualified `Outer.super.x` (where
                // `super` is the SELECTED name, not the expression) both dispatch through super and must be flagged.
                if ((node.getExpression() instanceof IdentifierTree identifier && identifier.getName().contentEquals("super"))
                        || node.getIdentifier().contentEquals("super")) {
                    usesSuper[0] = true;
                }
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitSynchronized(SynchronizedTree node, Void unused) {
                ExpressionTree lock = stripParens(node.getExpression());
                if (lock instanceof IdentifierTree identifier && identifier.getName().contentEquals("this")) {
                    synchronizedOnReceiver[0] = true;
                }
                return super.visitSynchronized(node, unused);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                if (node.getName().contentEquals("super")) {
                    usesSuper[0] = true;
                } else if (node.getName().contentEquals("this")) {
                    // Skip enclosing parentheses when reading the syntactic context so `(this).foo()` is still a
                    // receiver and `synchronized((this))` is still a lock — not a value escape.
                    TreePath effective = getCurrentPath().getParentPath();
                    while (effective != null && effective.getLeaf() instanceof ParenthesizedTree) {
                        effective = effective.getParentPath();
                    }
                    Tree parent = effective == null ? null : effective.getLeaf();
                    boolean asReceiver = parent instanceof MemberSelectTree select && stripParens(select.getExpression()) == node;
                    boolean asSyncLock = parent instanceof SynchronizedTree sync && stripParens(sync.getExpression()) == node;
                    if (!asReceiver && !asSyncLock) {
                        thisEscapes[0] = true; // `this` used as a value, or `this::m`
                    }
                }
                recordSourceTypeVariable(task.trees.getTypeMirror(getCurrentPath()), sourceTypeParameters, typeParameterDependency);
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                if (node.getQualifierExpression() instanceof IdentifierTree identifier
                        && identifier.getName().contentEquals("this")) {
                    thisEscapes[0] = true; // `this::m`
                }
                return super.visitMemberReference(node, unused);
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                recordSourceTypeVariable(task.trees.getTypeMirror(getCurrentPath()), sourceTypeParameters, typeParameterDependency);
                return super.visitVariable(node, unused);
            }

            @Override
            public Void visitTypeCast(TypeCastTree node, Void unused) {
                recordSourceTypeVariable(
                        task.trees.getTypeMirror(new TreePath(getCurrentPath(), node.getType())),
                        sourceTypeParameters,
                        typeParameterDependency);
                return super.visitTypeCast(node, unused);
            }

            @Override
            public Void visitInstanceOf(InstanceOfTree node, Void unused) {
                recordSourceTypeVariable(
                        task.trees.getTypeMirror(new TreePath(getCurrentPath(), node.getType())),
                        sourceTypeParameters,
                        typeParameterDependency);
                return super.visitInstanceOf(node, unused);
            }
        }.scan(methodPath, null);

        // A method's OWN type parameters are never a blocker even if their bounds reference source variables; only a
        // dependency on a SOURCE type variable blocks the move.
        if (typeParameterDependency[0] != null && methodTypeParameters.stream()
                .anyMatch(tp -> tp.getSimpleName().contentEquals(typeParameterDependency[0]))) {
            typeParameterDependency[0] = null;
        }

        return new InstanceMoveFacts(true, usesSuper[0], synchronizedOnReceiver[0], thisEscapes[0], typeParameterDependency[0]);
    }

    private static ExpressionTree stripParens(ExpressionTree expression) {
        ExpressionTree current = expression;
        while (current instanceof ParenthesizedTree parenthesized) {
            current = parenthesized.getExpression();
        }
        return current;
    }

    /** Type variables declared by the method's enclosing type and every lexically enclosing type. */
    private static Set<Element> enclosingTypeParameters(ExecutableElement method) {
        Set<Element> parameters = new java.util.LinkedHashSet<>();
        Element enclosing = method.getEnclosingElement();
        while (enclosing != null) {
            if (enclosing instanceof TypeElement type) {
                parameters.addAll(type.getTypeParameters());
            }
            enclosing = enclosing.getEnclosingElement();
        }
        return parameters;
    }

    /** Records the simple name of the first source type variable contained in {@code type} (if not already found). */
    private static void recordSourceTypeVariable(TypeMirror type, Set<Element> sourceTypeParameters, String[] sink) {
        if (sink[0] != null || type == null) {
            return;
        }
        String found = findSourceTypeVariable(type, sourceTypeParameters, new java.util.HashSet<>());
        if (found != null) {
            sink[0] = found;
        }
    }

    private static String findSourceTypeVariable(TypeMirror type, Set<Element> sourceTypeParameters, Set<TypeMirror> seen) {
        return findSourceTypeVariable(type, sourceTypeParameters, seen, 0);
    }

    // javax.lang.model.type.TypeMirror does not contract equals/hashCode (the spec mandates Types#isSameType), so the
    // identity seen-set cannot be relied on to break cycles when javac yields fresh mirror instances per step (e.g. a
    // recursive bound like <U extends Comparable<U>>). A hard depth cap guarantees termination regardless.
    private static final int MAX_TYPE_RECURSION_DEPTH = 64;

    private static String findSourceTypeVariable(
            TypeMirror type, Set<Element> sourceTypeParameters, Set<TypeMirror> seen, int depth) {
        if (type == null || depth > MAX_TYPE_RECURSION_DEPTH || !seen.add(type)) {
            return null;
        }
        switch (type.getKind()) {
            case TYPEVAR -> {
                javax.lang.model.type.TypeVariable variable = (javax.lang.model.type.TypeVariable) type;
                if (sourceTypeParameters.contains(variable.asElement())) {
                    return variable.asElement().getSimpleName().toString();
                }
                return findSourceTypeVariable(variable.getUpperBound(), sourceTypeParameters, seen, depth + 1);
            }
            case ARRAY -> {
                return findSourceTypeVariable(
                        ((javax.lang.model.type.ArrayType) type).getComponentType(), sourceTypeParameters, seen, depth + 1);
            }
            case WILDCARD -> {
                javax.lang.model.type.WildcardType wildcard = (javax.lang.model.type.WildcardType) type;
                String extendsBound = findSourceTypeVariable(wildcard.getExtendsBound(), sourceTypeParameters, seen, depth + 1);
                return extendsBound != null
                        ? extendsBound
                        : findSourceTypeVariable(wildcard.getSuperBound(), sourceTypeParameters, seen, depth + 1);
            }
            case DECLARED -> {
                for (TypeMirror argument : ((javax.lang.model.type.DeclaredType) type).getTypeArguments()) {
                    String found = findSourceTypeVariable(argument, sourceTypeParameters, seen, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
                return null;
            }
            case EXECUTABLE -> {
                ExecutableType executable = (ExecutableType) type;
                String returnType = findSourceTypeVariable(executable.getReturnType(), sourceTypeParameters, seen, depth + 1);
                if (returnType != null) {
                    return returnType;
                }
                for (TypeMirror parameter : executable.getParameterTypes()) {
                    String found = findSourceTypeVariable(parameter, sourceTypeParameters, seen, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
                for (TypeMirror thrown : executable.getThrownTypes()) {
                    String found = findSourceTypeVariable(thrown, sourceTypeParameters, seen, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    public boolean referencesWithin(SemanticMember target, SourceRange range) {
        if (target == null || range == null) {
            return false;
        }
        Path normalized = range.file().toAbsolutePath().normalize();
        for (IdentifierSpan span : referencesTo(target)) {
            if (span.file().toAbsolutePath().normalize().equals(normalized)
                    && span.startOffset() >= range.start()
                    && span.endOffset() <= range.end()) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern VISIBILITY_KEYWORD =
            java.util.regex.Pattern.compile("\\b(private|protected|public)\\b[ \\t]*");

    /**
     * A precise, javac-bounded edit that widens {@code member}'s declared visibility to
     * {@code toVisibility} ({@code "package-private"}, {@code "protected"}, or {@code "public"}), or empty
     * when no change is needed or the declaration positions cannot be resolved safely.
     *
     * <p>The visibility keyword is located strictly between the member's modifiers (after any annotations)
     * and its type / return-type — bounds taken from javac source positions — so the edit never touches an
     * annotation argument, a type parameter, the member body, or any unrelated token. When the member has
     * no explicit visibility keyword (package-private), a widening keyword is inserted at the start of the
     * declaration's modifier region (before annotations and type parameters), which is always legal. This
     * makes a referenced private/package-private member source-valid at its own declaration site rather than
     * relying on a compiler-synthesized accessor bridge.
     */
    public Optional<PlannerSupport.TextEdit> visibilityWideningEdit(SemanticMember member, String toVisibility, String editKind) {
        if (member == null || toVisibility == null || toVisibility.isBlank()) {
            return Optional.empty();
        }
        Element element = member.element();
        TreePath path = element == null ? null : trees.getPath(element);
        if (path == null) {
            return Optional.empty();
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        Tree leaf = path.getLeaf();
        com.sun.source.tree.ModifiersTree modifiers;
        Tree boundary;
        if (leaf instanceof MethodTree method) {
            modifiers = method.getModifiers();
            boundary = method.getReturnType();
        } else if (leaf instanceof VariableTree variable) {
            modifiers = variable.getModifiers();
            boundary = variable.getType();
        } else {
            return Optional.empty();
        }
        if (boundary == null) {
            return Optional.empty();
        }
        Path file = pathOf(unit);
        CharSequence source = sourceByPath.get(file.toAbsolutePath().normalize());
        if (source == null) {
            return Optional.empty();
        }
        long declStart = positions.getStartPosition(unit, leaf);
        long modifiersStart = modifiers == null ? -1 : positions.getStartPosition(unit, modifiers);
        long insertAt = modifiersStart >= 0 ? modifiersStart : declStart;
        long annotationsEnd = -1;
        if (modifiers != null) {
            for (Tree annotation : modifiers.getAnnotations()) {
                annotationsEnd = Math.max(annotationsEnd, positions.getEndPosition(unit, annotation));
            }
        }
        long boundaryStart = positions.getStartPosition(unit, boundary);
        long scanStart = annotationsEnd >= 0 ? annotationsEnd : insertAt;
        if (insertAt < 0 || scanStart < 0 || boundaryStart < 0 || scanStart > boundaryStart || boundaryStart > source.length()) {
            return Optional.empty();
        }
        String head = source.subSequence((int) scanStart, (int) boundaryStart).toString();
        java.util.regex.Matcher matcher = VISIBILITY_KEYWORD.matcher(head);
        String current = "package-private";
        int keywordStart = -1;
        int keywordEnd = -1;
        if (matcher.find()) {
            current = matcher.group(1);
            keywordStart = (int) scanStart + matcher.start();
            keywordEnd = (int) scanStart + matcher.end();
        }
        String target = "package-private".equals(toVisibility) ? "package-private" : toVisibility;
        if (current.equals(target)) {
            return Optional.empty();
        }
        if (keywordStart >= 0) {
            String replacement = "package-private".equals(target) ? "" : target + " ";
            return Optional.of(new PlannerSupport.TextEdit(file, keywordStart, keywordEnd, replacement, editKind));
        }
        if ("package-private".equals(target)) {
            return Optional.empty();
        }
        return Optional.of(new PlannerSupport.TextEdit(file, (int) insertAt, (int) insertAt, target + " ", editKind));
    }

    public List<SemanticMethod> publicInstanceMethods(Path file, Set<String> requested) {
        SemanticType type = primaryType(file);
        if (type == null || !(type.element() instanceof TypeElement owner)) {
            return List.of();
        }
        Set<String> requestedNames = requested == null ? Set.of() : requested;
        for (Element enclosed : owner.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement method) || method.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (!method.getModifiers().contains(Modifier.PUBLIC) || method.getModifiers().contains(Modifier.STATIC)) {
                if (requestedNames.contains(method.getSimpleName().toString())) {
                    throw new IllegalArgumentException("Selected members must be public instance methods, not static or non-public members.");
                }
            }
        }
        Map<String, SemanticMethod> methods = new LinkedHashMap<>();
        for (Element member : elements.getAllMembers(owner)) {
            if (!(member instanceof ExecutableElement method) || method.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (!method.getModifiers().contains(Modifier.PUBLIC) || method.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            if (!requestedNames.isEmpty() && !requestedNames.contains(method.getSimpleName().toString())) {
                continue;
            }
            String key = erasedSignatureKey(method);
            if (methods.containsKey(key)) {
                continue;
            }
            SemanticMethod semantic = semanticMethod(method, owner);
            if (semantic != null) {
                methods.put(key, semantic);
            }
        }
        return new ArrayList<>(methods.values());
    }

    public SemanticMethod semanticMethod(Element element) {
        if (!(element instanceof ExecutableElement executable)) {
            return null;
        }
        Element owner = executable.getEnclosingElement();
        if (!(owner instanceof TypeElement ownerType)) {
            return null;
        }
        return semanticMethod(executable, ownerType);
    }

    private SemanticMethod semanticMethod(ExecutableElement executable, TypeElement useSite) {
        TreePath path = trees.getPath(executable);
        if (path == null || !(path.getLeaf() instanceof MethodTree methodTree)) {
            return null;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        Path file = pathOf(unit);
        CharSequence source = sourceByPath.get(file.toAbsolutePath().normalize());
        if (source == null) {
            source = sourceByPath.get(file);
        }
        javax.lang.model.type.ExecutableType memberType = executable.asType() instanceof javax.lang.model.type.ExecutableType executableType
                ? executableType
                : null;
        if (useSite.asType() instanceof javax.lang.model.type.DeclaredType declared) {
            memberType = (javax.lang.model.type.ExecutableType) types.asMemberOf(declared, executable);
        }
        return semanticMethod(home, unit, path, methodTree, executable, source, memberType);
    }

    public boolean exposesPrivateType(SemanticMethod method) {
        if (!(method.element() instanceof ExecutableElement executable)) {
            return true;
        }
        if (isPrivateType(executable.getReturnType())) {
            return true;
        }
        for (VariableElement parameter : executable.getParameters()) {
            if (isPrivateType(parameter.asType())) {
                return true;
            }
        }
        for (TypeMirror thrown : executable.getThrownTypes()) {
            if (isPrivateType(thrown)) {
                return true;
            }
        }
        return false;
    }

    public void requireSignatureTypesAccessible(SemanticMethod method, String targetPackage) {
        if (!(method.element() instanceof ExecutableElement executable)) {
            throw new IllegalArgumentException("Selected method is not javac-resolved: '" + method.name() + "'.");
        }
        javax.lang.model.type.ExecutableType memberType = method.memberType();
        if (memberType == null) {
            throw new IllegalArgumentException("Selected method has no javac-resolved executable type: '" + method.name() + "'.");
        }
        requireTypeAccessible(memberType.getReturnType(), targetPackage);
        for (TypeMirror parameter : memberType.getParameterTypes()) {
            requireTypeAccessible(parameter, targetPackage);
        }
        for (TypeMirror thrown : memberType.getThrownTypes()) {
            requireTypeAccessible(thrown, targetPackage);
        }
        for (TypeParameterElement typeParameter : executable.getTypeParameters()) {
            for (TypeMirror bound : typeParameter.getBounds()) {
                if (!bound.toString().equals("java.lang.Object")) {
                    requireTypeAccessible(bound, targetPackage);
                }
            }
        }
    }

    public String renderMethodTypeParameters(SemanticMethod method, String targetPackage, Set<String> imports) {
        if (!(method.element() instanceof ExecutableElement executable) || executable.getTypeParameters().isEmpty()) {
            return "";
        }
        List<String> rendered = new ArrayList<>();
        for (TypeParameterElement typeParameter : executable.getTypeParameters()) {
            List<String> bounds = new ArrayList<>();
            for (TypeMirror bound : typeParameter.getBounds()) {
                if (!bound.toString().equals("java.lang.Object")) {
                    bounds.add(renderType(bound, targetPackage, imports));
                }
            }
            rendered.add(typeParameter.getSimpleName() + (bounds.isEmpty() ? "" : " extends " + String.join(" & ", bounds)));
        }
        return "<" + String.join(", ", rendered) + ">";
    }

    public String renderMethodReturnType(SemanticMethod method, String targetPackage, Set<String> imports) {
        return renderType(method.memberType().getReturnType(), targetPackage, imports);
    }

    public List<String> renderMethodParameters(SemanticMethod method, String targetPackage, Set<String> imports) {
        List<? extends TypeMirror> parameterTypes = method.memberType().getParameterTypes();
        List<String> rendered = new ArrayList<>();
        for (int i = 0; i < method.parameters().size(); i++) {
            String type = i < parameterTypes.size() ? renderType(parameterTypes.get(i), targetPackage, imports) : method.parameters().get(i).type().trim();
            if (method.element() instanceof ExecutableElement executable && executable.isVarArgs() && i == method.parameters().size() - 1 && type.endsWith("[]")) {
                type = type.substring(0, type.length() - 2) + "...";
            }
            rendered.add(type + " " + method.parameters().get(i).name());
        }
        return rendered;
    }

    public String renderMethodThrowsClause(SemanticMethod method, String targetPackage, Set<String> imports) {
        List<String> thrown = new ArrayList<>();
        for (TypeMirror thrownType : method.memberType().getThrownTypes()) {
            thrown.add(renderType(thrownType, targetPackage, imports));
        }
        return thrown.isEmpty() ? "" : " throws " + String.join(", ", thrown);
    }

    public String methodSignatureKey(SemanticMethod method) {
        return method.element() instanceof ExecutableElement executable ? erasedSignatureKey(executable) : method.name();
    }

    private String erasedSignatureKey(ExecutableElement method) {
        List<String> parameterTypes = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            parameterTypes.add(types.erasure(parameter.asType()).toString());
        }
        return method.getSimpleName() + "(" + String.join(",", parameterTypes) + ")";
    }

    private void requireTypeAccessible(TypeMirror mirror, String targetPackage) {
        if (mirror == null) {
            return;
        }
        switch (mirror.getKind()) {
            case ARRAY -> requireTypeAccessible(((javax.lang.model.type.ArrayType) mirror).getComponentType(), targetPackage);
            case DECLARED -> {
                javax.lang.model.type.DeclaredType declared = (javax.lang.model.type.DeclaredType) mirror;
                if (declared.asElement() instanceof TypeElement type) {
                    requireTypeElementAccessible(type, targetPackage);
                }
                for (TypeMirror argument : declared.getTypeArguments()) {
                    requireTypeAccessible(argument, targetPackage);
                }
            }
            case TYPEVAR -> {
                javax.lang.model.type.TypeVariable variable = (javax.lang.model.type.TypeVariable) mirror;
                requireTypeAccessible(variable.getUpperBound(), targetPackage);
                requireTypeAccessible(variable.getLowerBound(), targetPackage);
            }
            case WILDCARD -> {
                javax.lang.model.type.WildcardType wildcard = (javax.lang.model.type.WildcardType) mirror;
                requireTypeAccessible(wildcard.getExtendsBound(), targetPackage);
                requireTypeAccessible(wildcard.getSuperBound(), targetPackage);
            }
            default -> {
            }
        }
    }

    private void requireTypeElementAccessible(TypeElement type, String targetPackage) {
        for (Element current = type; current instanceof TypeElement currentType; current = current.getEnclosingElement()) {
            String packageName = elements.getPackageOf(currentType).getQualifiedName().toString();
            Set<Modifier> modifiers = currentType.getModifiers();
            if (modifiers.contains(Modifier.PRIVATE)) {
                throw new IllegalArgumentException("Selected method exposes private type '" + currentType.getQualifiedName() + "'.");
            }
            if (!packageName.equals(targetPackage) && !modifiers.contains(Modifier.PUBLIC)) {
                throw new IllegalArgumentException("Selected method exposes package-private type '" + currentType.getQualifiedName() + "' outside package '" + packageName + "'.");
            }
        }
    }

    private String renderType(TypeMirror mirror, String targetPackage, Set<String> imports) {
        if (mirror == null) {
            return "";
        }
        return switch (mirror.getKind()) {
            case ARRAY -> renderType(((javax.lang.model.type.ArrayType) mirror).getComponentType(), targetPackage, imports) + "[]";
            case DECLARED -> renderDeclaredType((javax.lang.model.type.DeclaredType) mirror, targetPackage, imports);
            case TYPEVAR -> ((javax.lang.model.type.TypeVariable) mirror).asElement().getSimpleName().toString();
            case WILDCARD -> renderWildcardType((javax.lang.model.type.WildcardType) mirror, targetPackage, imports);
            default -> mirror.toString();
        };
    }

    private String renderDeclaredType(javax.lang.model.type.DeclaredType declared, String targetPackage, Set<String> imports) {
        Element element = declared.asElement();
        String base = element instanceof TypeElement type ? renderTypeName(type, targetPackage, imports) : declared.toString();
        if (declared.getTypeArguments().isEmpty()) {
            return base;
        }
        List<String> arguments = new ArrayList<>();
        for (TypeMirror argument : declared.getTypeArguments()) {
            arguments.add(renderType(argument, targetPackage, imports));
        }
        return base + "<" + String.join(", ", arguments) + ">";
    }

    private String renderWildcardType(javax.lang.model.type.WildcardType wildcard, String targetPackage, Set<String> imports) {
        if (wildcard.getExtendsBound() != null) {
            return "? extends " + renderType(wildcard.getExtendsBound(), targetPackage, imports);
        }
        if (wildcard.getSuperBound() != null) {
            return "? super " + renderType(wildcard.getSuperBound(), targetPackage, imports);
        }
        return "?";
    }

    private String renderTypeName(TypeElement type, String targetPackage, Set<String> imports) {
        String qualified = type.getQualifiedName().toString();
        String packageName = elements.getPackageOf(type).getQualifiedName().toString();
        String packageRelative = packageName.isEmpty() ? qualified : qualified.substring(packageName.length() + 1);
        if (packageName.isEmpty()) {
            return packageRelative;
        }
        if (packageName.equals(targetPackage)) {
            return packageRelative;
        }
        if (packageName.equals("java.lang")) {
            return type.getNestingKind().isNested() ? packageRelative : type.getSimpleName().toString();
        }
        imports.add(qualified);
        return type.getSimpleName().toString();
    }
    private static TreePath enclosingMethodPath(TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof MethodTree) {
                return current;
            }
        }
        return null;
    }
    public SemanticStatementSelection statementSelection(Path file, int start, int end) {
        return selectionIndex.statementSelection(file, start, end);
    }

    public SemanticConstructor singleConstructor(Path file) {
        List<SemanticConstructor> constructors = constructors(file);
        return constructors.size() == 1 ? constructors.get(0) : null;
    }

    public List<SemanticConstructor> constructors(Path file) {
        SemanticType type = primaryType(file);
        if (type == null || !(type.element() instanceof TypeElement owner)) {
            return List.of();
        }

        List<SemanticConstructor> constructors = new ArrayList<>();
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement executable && executable.getKind() == ElementKind.CONSTRUCTOR) {
                TreePath path = trees.getPath(executable);
                if (path != null && path.getLeaf() instanceof MethodTree constructor && constructor.getBody() != null) {
                    CompilationUnitTree unit = path.getCompilationUnit();
                    long start = positions.getStartPosition(unit, constructor);
                    long end = positions.getEndPosition(unit, constructor);
                    long bodyStart = positions.getStartPosition(unit, constructor.getBody());
                    int assignmentOffset = constructorAssignmentOffset(unit, constructor);
                    boolean delegatesToThis = constructorDelegatesToThis(constructor);
                    if (start >= 0 && end >= start && bodyStart >= 0 && assignmentOffset >= 0) {
                        constructors.add(new SemanticConstructor(
                                new SourceRange(pathOf(unit), (int) start, (int) end),
                                (int) bodyStart + 1,
                                assignmentOffset,
                                delegatesToThis));
                    }
                }
            }
        }
        return List.copyOf(constructors);
    }

    public int constructorCount(Path file) {
        return constructors(file).size();
    }

    private int constructorAssignmentOffset(CompilationUnitTree unit, MethodTree constructor) {
        long bodyStart = positions.getStartPosition(unit, constructor.getBody());
        if (bodyStart < 0) {
            return -1;
        }
        int offset = (int) bodyStart + 1;
        List<? extends StatementTree> statements = constructor.getBody().getStatements();
        if (!statements.isEmpty() && isExplicitConstructorInvocation(statements.get(0))) {
            long firstEnd = positions.getEndPosition(unit, statements.get(0));
            if (firstEnd >= 0) {
                offset = (int) firstEnd;
            }
        }
        return offset;
    }

    private boolean constructorDelegatesToThis(MethodTree constructor) {
        List<? extends StatementTree> statements = constructor.getBody().getStatements();
        return !statements.isEmpty() && isThisConstructorInvocation(statements.get(0));
    }

    private boolean isExplicitConstructorInvocation(StatementTree statement) {
        return statement instanceof ExpressionStatementTree expressionStatement
                && expressionStatement.getExpression() instanceof MethodInvocationTree invocation
                && ("this".equals(invocation.getMethodSelect().toString()) || "super".equals(invocation.getMethodSelect().toString()));
    }

    private boolean isThisConstructorInvocation(StatementTree statement) {
        return statement instanceof ExpressionStatementTree expressionStatement
                && expressionStatement.getExpression() instanceof MethodInvocationTree invocation
                && "this".equals(invocation.getMethodSelect().toString());
    }

    public List<SemanticCallSite> methodInvocationsNamed(String name) {
        List<SemanticCallSite> result = new ArrayList<>();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                CharSequence source = task.sourceByPath.get(file.toAbsolutePath().normalize());
                if (source == null) {
                    continue;
                }
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        String invokedName = null;
                        if (node.getMethodSelect() instanceof MemberSelectTree memberSelect) {
                            invokedName = memberSelect.getIdentifier().toString();
                        } else if (node.getMethodSelect() instanceof IdentifierTree identifier) {
                            invokedName = identifier.getName().toString();
                        }
                        if (name.equals(invokedName)) {
                            SemanticCallSite site = syntacticInvocationSite(task, unit, file, source, getCurrentPath(), node, name);
                            if (site != null) {
                                result.add(site);
                            }
                        }
                        return super.visitMethodInvocation(node, unused);
                    }
                }.scan(unit, null);
            }
        }
        return result.stream()
                .sorted(Comparator.comparing((SemanticCallSite site) -> site.file().toString())
                        .thenComparingInt(site -> site.invocationRange().start()))
                .toList();
    }

    public List<SemanticUsageNarrowing> usageNarrowingCandidates(SemanticType concreteType) {
        if (concreteType == null || concreteType.element() == null) {
            return List.of();
        }
        List<SemanticUsageNarrowing> candidates = new ArrayList<>();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                CharSequence source = task.sourceByPath.get(file.toAbsolutePath().normalize());
                if (source == null) {
                    continue;
                }
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree node, Void unused) {
                        Element variableElement = task.trees.getElement(getCurrentPath());
                        if (!(variableElement instanceof VariableElement variable)) {
                            return super.visitVariable(node, unused);
                        }
                        Element variableType = task.types.asElement(task.types.erasure(variable.asType()));
                        if (variableType == null || !sameCanonical(concreteType.element(), variableType, task, unit, file)) {
                            return super.visitVariable(node, unused);
                        }
                        long typeStart = task.positions.getStartPosition(unit, node.getType());
                        long typeEnd = task.positions.getEndPosition(unit, node.getType());
                        if (typeStart < 0 || typeEnd < typeStart) {
                            return super.visitVariable(node, unused);
                        }
                        VariableUseAnalysis uses = methodCallsOnVariable(task, unit, variable);
                        DeclarationKind declarationKind = declarationKindOf(variable);
                        boolean apiVisible = isApiVisibleDeclaration(variable, declarationKind);
                        boolean enclosingTypeNested = isEnclosingTypeNested(variable);
                        candidates.add(new SemanticUsageNarrowing(
                                new SourceRange(file, (int) typeStart, (int) typeEnd),
                                uses.calledMethodKeys(),
                                uses.unsafeUses(),
                                declarationKind,
                                apiVisible,
                                enclosingTypeNested));
                        return super.visitVariable(node, unused);
                    }
                }.scan(unit, null);
            }
        }
        return candidates;
    }

    /**
     * Semantically decides whether a usage-narrowing declaration participates in a type's externally visible API
     * surface, using the javac {@link VariableElement}'s kind and effective visibility — never source nesting. A
     * field/record-component/enum-constant is API-visible unless it is {@code private}; a method/constructor parameter is
     * API-visible only when its enclosing executable is itself non-private (a parameter of a private method cannot be
     * observed by any caller). Locals, resources, exception parameters and pattern bindings are internal to one body and
     * are never API-visible.
     */
    /**
     * The narrowing declaration kind of {@code variable}, mapping javac's {@link ElementKind} to {@link DeclarationKind}
     * but promoting a record-component-backed field (which javac models as a synthetic {@code private final} FIELD) to
     * {@link DeclarationKind#RECORD_COMPONENT} so the planner sees the API-bearing component, not the backing field.
     */
    private DeclarationKind declarationKindOf(VariableElement variable) {
        if (variable.getKind() == ElementKind.FIELD && isRecordComponentBackedField(variable)) {
            return DeclarationKind.RECORD_COMPONENT;
        }
        return DeclarationKind.from(variable.getKind());
    }

    private boolean isApiVisibleDeclaration(VariableElement variable, DeclarationKind kind) {
        if (!kind.isApiVisible()) {
            return false;
        }
        return switch (kind) {
            // A record component is exposed by its accessor regardless of the synthetic backing field's modifiers.
            case RECORD_COMPONENT, ENUM_CONSTANT -> true;
            case FIELD -> !variable.getModifiers().contains(Modifier.PRIVATE);
            case PARAMETER -> variable.getEnclosingElement() instanceof ExecutableElement executable
                    && !executable.getModifiers().contains(Modifier.PRIVATE);
            default -> false;
        };
    }

    /** Whether the type that lexically encloses {@code variable}'s declaration is itself a nested (non-top-level) type. */
    private static boolean isEnclosingTypeNested(VariableElement variable) {
        Element enclosing = variable.getEnclosingElement();
        while (enclosing != null && !(enclosing instanceof TypeElement)) {
            enclosing = enclosing.getEnclosingElement();
        }
        return enclosing instanceof TypeElement type && type.getNestingKind().isNested();
    }

    private record VariableUseAnalysis(List<String> calledMethodKeys, List<String> unsafeUses) {
    }

    private VariableUseAnalysis methodCallsOnVariable(CompilerTask task, CompilationUnitTree unit, VariableElement variable) {
        List<String> calls = new ArrayList<>();
        List<String> unsafeUses = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if (node.getMethodSelect() instanceof MemberSelectTree memberSelect) {
                    Element receiver = task.trees.getElement(new TreePath(getCurrentPath(), memberSelect.getExpression()));
                    if (variable.equals(receiver)) {
                        Element invoked = task.trees.getElement(getCurrentPath());
                        if (invoked instanceof ExecutableElement executable) {
                            calls.add(erasedSignatureKey(executable));
                        } else {
                            unsafeUses.add("unresolved method call '" + memberSelect.getIdentifier() + "'");
                        }
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element element = task.trees.getElement(getCurrentPath());
                if (variable.equals(element) && !isReceiverOfMethodInvocation(getCurrentPath())) {
                    unsafeUses.add("non-method use of variable '" + variable.getSimpleName() + "'");
                }
                return super.visitIdentifier(node, unused);
            }
        }.scan(unit, null);
        return new VariableUseAnalysis(calls, unsafeUses);
    }

    private boolean isReceiverOfMethodInvocation(TreePath path) {
        TreePath parent = path.getParentPath();
        if (parent == null || !(parent.getLeaf() instanceof MemberSelectTree memberSelect) || memberSelect.getExpression() != path.getLeaf()) {
            return false;
        }
        TreePath grandparent = parent.getParentPath();
        return grandparent != null
                && grandparent.getLeaf() instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() == memberSelect;
    }

    public List<PlannerSupport.TextEdit> fieldAccessorReferenceEdits(
            SemanticField field,
            String getterName,
            String setterName,
            boolean generateSetter) {
        return fieldAccessorReferenceEdits(field, getterName, setterName, generateSetter, true);
    }

    /**
     * Plans accessor-routing reference edits for an encapsulated field.
     *
     * <p>{@code refuseCompoundAssignments} carries the {@code encapsulate_field.refuse_compound_assignments} policy
     * (default true). When true a compound assignment ({@code field += x}) or increment/decrement ({@code field++})
     * remains refused with {@code compound_field_usage}. When false those statement-position usages are rewritten with
     * an expression-preserving accessor transform: {@code field += x} becomes {@code setField(getField() + (x))} and
     * {@code field++} becomes {@code setField(getField() + 1)}. The transform requires a setter and only applies to
     * statement-position usages whose result value is unused; a compound/increment usage embedded in a larger
     * expression is still refused as {@code unsafe_field_usage} because its value escapes.
     */
    public List<PlannerSupport.TextEdit> fieldAccessorReferenceEdits(
            SemanticField field,
            String getterName,
            String setterName,
            boolean generateSetter,
            boolean refuseCompoundAssignments) {
        if (field == null || field.element() == null) {
            return List.of();
        }

        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        Path selectedFile = field.file().toAbsolutePath().normalize();
        for (CompilerTask task : allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = pathOf(unit);
                CharSequence source = task.sourceByPath.get(file.toAbsolutePath().normalize());
                if (source == null) {
                    continue;
                }
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitIdentifier(IdentifierTree node, Void unused) {
                        TreePath parentPath = getCurrentPath().getParentPath();
                        if (parentPath != null && parentPath.getLeaf() instanceof MemberSelectTree) {
                            return super.visitIdentifier(node, unused);
                        }
                        handleReference(node);
                        return super.visitIdentifier(node, unused);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                        handleReference(node);
                        return super.visitMemberSelect(node, unused);
                    }

                    private void handleReference(Tree node) {
                        Element resolved = task.trees.getElement(getCurrentPath());
                        if (resolved == null || !sameCanonical(field.element(), resolved, task, unit, file)) {
                            return;
                        }
                        IdentifierSpan span = spanFinder.find(file, unit, task.positions, node, resolved, source);
                        if (span == null || isSelectedFieldDeclaration(file, span)) {
                            return;
                        }

                        TreePath parentPath = getCurrentPath().getParentPath();
                        Tree parent = parentPath == null ? null : parentPath.getLeaf();
                        if (parent instanceof AssignmentTree assignment && assignment.getVariable() == node) {
                            addSetterEdit(node, parentPath, assignment);
                        } else if (parent instanceof CompoundAssignmentTree compoundAssignment
                                && compoundAssignment.getVariable() == node) {
                            if (refuseCompoundAssignments) {
                                throw new IllegalStateException("compound_field_usage");
                            }
                            addCompoundAssignmentEdit(node, parentPath, compoundAssignment);
                        } else if (parent instanceof UnaryTree unary && unary.getExpression() == node && isIncrementOrDecrement(unary)) {
                            if (refuseCompoundAssignments) {
                                throw new IllegalStateException("compound_field_usage");
                            }
                            addIncrementEdit(node, parentPath, unary);
                        } else {
                            edits.add(new PlannerSupport.TextEdit(file, span.startOffset(), span.endOffset(), getterName + "()", "ENCAPSULATE_FIELD_READ"));
                        }
                    }

                    private boolean isSelectedFieldDeclaration(Path file, IdentifierSpan span) {
                        return selectedFile.equals(file.toAbsolutePath().normalize())
                                && span.startOffset() >= field.declarationRange().start()
                                && span.endOffset() <= field.declarationRange().end();
                    }

                    private void addSetterEdit(Tree node, TreePath assignmentPath, AssignmentTree assignment) {
                        if (!generateSetter) {
                            throw new IllegalStateException("setter_required_for_writes");
                        }
                        TreePath assignmentParentPath = assignmentPath.getParentPath();
                        Tree assignmentParent = assignmentParentPath == null ? null : assignmentParentPath.getLeaf();
                        if (!(assignmentParent instanceof ExpressionStatementTree)) {
                            throw new IllegalStateException("unsafe_field_usage");
                        }
                        long assignmentStart = task.positions.getStartPosition(unit, assignment);
                        long assignmentEnd = task.positions.getEndPosition(unit, assignment);
                        long exprStart = task.positions.getStartPosition(unit, assignment.getExpression());
                        long exprEnd = task.positions.getEndPosition(unit, assignment.getExpression());
                        if (assignmentStart < 0 || assignmentEnd < assignmentStart || exprStart < 0 || exprEnd < exprStart) {
                            throw new IllegalStateException("unsafe_field_usage");
                        }
                        String expr = source.subSequence((int) exprStart, (int) exprEnd).toString();
                        edits.add(new PlannerSupport.TextEdit(
                                file,
                                assignmentStart,
                                assignmentEnd,
                                receiverPrefix(node) + setterName + "(" + expr + ")",
                                "ENCAPSULATE_FIELD_WRITE"));
                    }

                    private String receiverPrefix(Tree node) {
                        if (!(node instanceof MemberSelectTree memberSelect)) {
                            return "";
                        }
                        long selectStart = task.positions.getStartPosition(unit, memberSelect);
                        long receiverEnd = task.positions.getEndPosition(unit, memberSelect.getExpression());
                        if (selectStart < 0 || receiverEnd < selectStart) {
                            throw new IllegalStateException("unsafe_field_usage");
                        }
                        return source.subSequence((int) selectStart, (int) receiverEnd).toString() + ".";
                    }

                    /**
                     * Rewrites a statement-position compound assignment ({@code field OP= rhs}) into an
                     * expression-preserving accessor call {@code setField(getField() OP (rhs))}. The right-hand side is
                     * parenthesized so operator precedence is preserved when it expands inside the getter expression.
                     */
                    private void addCompoundAssignmentEdit(Tree node, TreePath compoundPath, CompoundAssignmentTree compound) {
                        if (!generateSetter) {
                            throw new IllegalStateException("setter_required_for_writes");
                        }
                        TreePath compoundParentPath = compoundPath.getParentPath();
                        Tree compoundParent = compoundParentPath == null ? null : compoundParentPath.getLeaf();
                        if (!(compoundParent instanceof ExpressionStatementTree)) {
                            // A compound assignment whose value is consumed (e.g. `y = (x += 1)`) cannot be split into a
                            // read+setter call without changing semantics, so it remains refused.
                            throw new IllegalStateException("unsafe_field_usage");
                        }
                        String operator = compoundOperator(compound.getKind());
                        if (operator == null) {
                            throw new IllegalStateException("unsafe_field_usage");
                        }
                        long compoundStart = task.positions.getStartPosition(unit, compound);
                        long compoundEnd = task.positions.getEndPosition(unit, compound);
                        long exprStart = task.positions.getStartPosition(unit, compound.getExpression());
                        long exprEnd = task.positions.getEndPosition(unit, compound.getExpression());
                        if (compoundStart < 0 || compoundEnd < compoundStart || exprStart < 0 || exprEnd < exprStart) {
                            throw new IllegalStateException("unsafe_field_usage");
                        }
                        String prefix = receiverPrefix(node);
                        String rhs = source.subSequence((int) exprStart, (int) exprEnd).toString();
                        String newText = prefix + setterName + "(" + prefix + getterName + "() " + operator + " (" + rhs + "))";
                        edits.add(new PlannerSupport.TextEdit(file, compoundStart, compoundEnd, newText, "ENCAPSULATE_FIELD_COMPOUND"));
                    }

                    /**
                     * Rewrites a statement-position increment/decrement ({@code field++}, {@code ++field}, {@code field--},
                     * {@code --field}) into {@code setField(getField() + 1)} / {@code setField(getField() - 1)}. Prefix and
                     * postfix forms are semantically identical at statement position (the result value is unused), so both
                     * map to the same accessor transform.
                     */
                    private void addIncrementEdit(Tree node, TreePath unaryPath, UnaryTree unary) {
                        if (!generateSetter) {
                            throw new IllegalStateException("setter_required_for_writes");
                        }
                        TreePath unaryParentPath = unaryPath.getParentPath();
                        Tree unaryParent = unaryParentPath == null ? null : unaryParentPath.getLeaf();
                        if (!(unaryParent instanceof ExpressionStatementTree)) {
                            // A prefix/postfix increment whose value is consumed (e.g. `y = x++`) has position-dependent
                            // semantics that a setter call cannot preserve, so it remains refused.
                            throw new IllegalStateException("unsafe_field_usage");
                        }
                        boolean increment = unary.getKind() == Tree.Kind.PREFIX_INCREMENT
                                || unary.getKind() == Tree.Kind.POSTFIX_INCREMENT;
                        long unaryStart = task.positions.getStartPosition(unit, unary);
                        long unaryEnd = task.positions.getEndPosition(unit, unary);
                        if (unaryStart < 0 || unaryEnd < unaryStart) {
                            throw new IllegalStateException("unsafe_field_usage");
                        }
                        String prefix = receiverPrefix(node);
                        String newText = prefix + setterName + "(" + prefix + getterName + "() " + (increment ? "+" : "-") + " 1)";
                        edits.add(new PlannerSupport.TextEdit(file, unaryStart, unaryEnd, newText, "ENCAPSULATE_FIELD_COMPOUND"));
                    }

                    /** Maps a compound-assignment tree kind to its binary operator token, or null if unsupported. */
                    private String compoundOperator(Tree.Kind kind) {
                        return switch (kind) {
                            case PLUS_ASSIGNMENT -> "+";
                            case MINUS_ASSIGNMENT -> "-";
                            case MULTIPLY_ASSIGNMENT -> "*";
                            case DIVIDE_ASSIGNMENT -> "/";
                            case REMAINDER_ASSIGNMENT -> "%";
                            case AND_ASSIGNMENT -> "&";
                            case OR_ASSIGNMENT -> "|";
                            case XOR_ASSIGNMENT -> "^";
                            case LEFT_SHIFT_ASSIGNMENT -> "<<";
                            case RIGHT_SHIFT_ASSIGNMENT -> ">>";
                            case UNSIGNED_RIGHT_SHIFT_ASSIGNMENT -> ">>>";
                            default -> null;
                        };
                    }

                    private boolean isIncrementOrDecrement(UnaryTree unary) {
                        return switch (unary.getKind()) {
                            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
                            default -> false;
                        };
                    }
                }.scan(unit, null);
            }
        }
        return edits;
    }

    private boolean sameCanonical(Element homeElement, Element candidate, CompilerTask task, CompilationUnitTree unit, Path file) {
        return canonicalKeyInHome(homeElement).equals(SemanticKey.from(candidate, task.trees, task.types, unit, file).canonical());
    }

    private boolean isPrivateType(TypeMirror mirror) {
        Element element = types.asElement(types.erasure(mirror));
        return element != null && element.getModifiers().contains(Modifier.PRIVATE);
    }

    private SemanticType semanticType(CompilerTask task, CompilationUnitTree unit, TreePath path, ClassTree clazz) {
        Element element = task.trees.getElement(path);
        if (!(element instanceof TypeElement type)) {
            return null;
        }
        Path file = pathOf(unit);
        CharSequence source = task.sourceByPath.get(file.toAbsolutePath().normalize());
        long start = task.positions.getStartPosition(unit, clazz);
        long end = task.positions.getEndPosition(unit, clazz);
        if (source == null || start < 0 || end < start) {
            return null;
        }
        IdentifierSpan nameSpan = spanFinder.find(file, unit, task.positions, clazz, type, source);
        int open = firstChar(source, (int) start, (int) end, '{');
        int close = lastChar(source, (int) start, (int) end, '}');
        if (nameSpan == null || open < 0 || close < open) {
            return null;
        }
        String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        return new SemanticType(
                file,
                packageName,
                clazz.getSimpleName().toString(),
                type.getQualifiedName().toString(),
                clazz.getKind().toString().toLowerCase(java.util.Locale.ROOT),
                new SourceRange(file, (int) start, (int) end),
                new SourceRange(file, open, close + 1),
                new SourceRange(file, (int) nameSpan.endOffset(), open),
                type);
    }

    private SemanticMethod semanticMethod(CompilerTask task, CompilationUnitTree unit, TreePath path, MethodTree node, ExecutableElement element, CharSequence source, javax.lang.model.type.ExecutableType memberType) {
        Path file = pathOf(unit);
        long start = task.positions.getStartPosition(unit, node);
        long end = task.positions.getEndPosition(unit, node);
        if (source == null || start < 0 || end < start) {
            return null;
        }
        int headerEnd = node.getBody() == null
                ? firstChar(source, (int) start, (int) end, ';')
                : (int) task.positions.getStartPosition(unit, node.getBody());
        if (headerEnd < 0) {
            headerEnd = (int) end;
        }
        long bodyStart = node.getBody() == null ? -1 : task.positions.getStartPosition(unit, node.getBody());
        long bodyEnd = node.getBody() == null ? -1 : task.positions.getEndPosition(unit, node.getBody());
        List<SemanticParameter> params = new ArrayList<>();
        List<? extends VariableElement> parameterElements = element.getParameters();
        for (int i = 0; i < node.getParameters().size(); i++) {
            VariableTree parameterTree = node.getParameters().get(i);
            VariableElement parameterElement = i < parameterElements.size() ? parameterElements.get(i) : null;
            long ps = task.positions.getStartPosition(unit, parameterTree);
            long pe = task.positions.getEndPosition(unit, parameterTree);
            String text = (ps >= 0 && pe >= ps && pe <= source.length()) ? source.subSequence((int) ps, (int) pe).toString() : parameterTree.toString();
            params.add(new SemanticParameter(text.replaceFirst("\\s+" + java.util.regex.Pattern.quote(parameterTree.getName().toString()) + "$", ""),
                    parameterTree.getName().toString(),
                    new SourceRange(file, (int) ps, (int) pe),
                    parameterElement));
        }
        String ownerName = "";
        String ownerQualified = "";
        if (element.getEnclosingElement() instanceof TypeElement owner) {
            ownerName = owner.getSimpleName().toString();
            ownerQualified = owner.getQualifiedName().toString();
        }
        SourceRange bodyRange = bodyStart >= 0 && bodyEnd >= bodyStart ? new SourceRange(file, (int) bodyStart, (int) bodyEnd) : null;
        String returnType = node.getReturnType() == null ? "" : sourceForTree(task, unit, source, node.getReturnType());
        // Javac-derived start of the return type tree: the modifier/annotation/type-parameter prefix is everything from the
        // declaration start up to this offset, so it is robust to multiline generics, type-use annotations, and annotations
        // interleaved with modifiers. A constructor has no return-type tree, so the prefix ends at the declaration start.
        SourceRange returnTypeRange = null;
        if (node.getReturnType() != null) {
            long rts = task.positions.getStartPosition(unit, node.getReturnType());
            long rte = task.positions.getEndPosition(unit, node.getReturnType());
            if (rts >= 0 && rte >= rts) {
                returnTypeRange = new SourceRange(file, (int) rts, (int) rte);
            }
        }
        if (returnTypeRange == null) {
            returnTypeRange = new SourceRange(file, (int) start, (int) start);
        }
        return new SemanticMethod(file, ownerName, ownerQualified, node.getName().toString(), returnType, params,
                Set.copyOf(element.getModifiers()), new SourceRange(file, (int) start, (int) end),
                new SourceRange(file, (int) start, headerEnd), bodyRange, returnTypeRange, element, memberType);
    }

    private SemanticField semanticField(CompilerTask task, CompilationUnitTree unit, TreePath path, VariableTree node, VariableElement element, CharSequence source) {
        Path file = pathOf(unit);
        long start = task.positions.getStartPosition(unit, node);
        long end = task.positions.getEndPosition(unit, node);
        if (source == null || start < 0 || end < start) {
            return null;
        }
        SourceRange initializer = null;
        if (node.getInitializer() != null) {
            long is = task.positions.getStartPosition(unit, node.getInitializer());
            long ie = task.positions.getEndPosition(unit, node.getInitializer());
            if (is >= 0 && ie >= is) {
                initializer = new SourceRange(file, (int) is, (int) ie);
            }
        }
        String ownerName = "";
        String ownerQualified = "";
        if (element.getEnclosingElement() instanceof TypeElement owner) {
            ownerName = owner.getSimpleName().toString();
            ownerQualified = owner.getQualifiedName().toString();
        }
        // Javac-derived start of the field's declared type tree: the modifier/annotation prefix is the slice from the
        // declaration start up to this offset (robust to type-use annotations and annotations interleaved with modifiers).
        SourceRange typeRange = null;
        if (node.getType() != null) {
            long ts = task.positions.getStartPosition(unit, node.getType());
            long te = task.positions.getEndPosition(unit, node.getType());
            if (ts >= 0 && te >= ts) {
                typeRange = new SourceRange(file, (int) ts, (int) te);
            }
        }
        if (typeRange == null) {
            typeRange = new SourceRange(file, (int) start, (int) start);
        }
        return new SemanticField(file, ownerName, ownerQualified, node.getName().toString(), sourceForTree(task, unit, source, node.getType()),
                Set.copyOf(element.getModifiers()), new SourceRange(file, (int) start, (int) end), initializer, typeRange, element);
    }

    private SemanticCallSite constructorSite(CompilerTask task, CompilationUnitTree unit, Path file, CharSequence source, TreePath path, NewClassTree node) {
        long start = task.positions.getStartPosition(unit, node);
        long end = task.positions.getEndPosition(unit, node);
        long typeStart = task.positions.getStartPosition(unit, node.getIdentifier());
        long typeEnd = task.positions.getEndPosition(unit, node.getIdentifier());
        if (start < 0 || end < start || typeStart < 0 || typeEnd < typeStart || end > source.length()) {
            return null;
        }
        if (node.getClassBody() != null) {
            long bodyStart = task.positions.getStartPosition(unit, node.getClassBody());
            if (bodyStart >= 0 && bodyStart <= source.length()) {
                int close = (int) bodyStart - 1;
                while (close >= start && Character.isWhitespace(source.charAt(close))) {
                    close--;
                }
                if (close >= start && source.charAt(close) == ')') {
                    end = close + 1L;
                }
            }
        }
        List<SemanticArgument> args = new ArrayList<>();
        for (ExpressionTree argument : node.getArguments()) {
            long as = task.positions.getStartPosition(unit, argument);
            long ae = task.positions.getEndPosition(unit, argument);
            if (as >= 0 && ae >= as && ae <= source.length()) {
                args.add(new SemanticArgument(source.subSequence((int) as, (int) ae).toString(), new SourceRange(file, (int) as, (int) ae)));
            }
        }
        boolean statement = path.getParentPath() != null && path.getParentPath().getLeaf() instanceof com.sun.source.tree.ExpressionStatementTree;
        return new SemanticCallSite(
                file,
                new SourceRange(file, (int) start, (int) end),
                new SourceRange(file, (int) typeStart, (int) typeEnd),
                null,
                "",
                args,
                false,
                statement);
    }

    private SemanticCallSite invocationSite(CompilerTask task, CompilationUnitTree unit, Path file, CharSequence source, TreePath path, MethodInvocationTree node) {
        Element resolved = task.trees.getElement(path);
        long start = task.positions.getStartPosition(unit, node);
        long end = task.positions.getEndPosition(unit, node);
        if (resolved == null || start < 0 || end < start) {
            return null;
        }
        IdentifierSpan span = spanFinder.find(file, unit, task.positions, node.getMethodSelect(), resolved, source);
        if (span == null) {
            span = spanFinder.find(file, unit, task.positions, node, resolved, source);
        }
        if (span == null) {
            return null;
        }
        List<SemanticArgument> args = new ArrayList<>();
        for (ExpressionTree argument : node.getArguments()) {
            long as = task.positions.getStartPosition(unit, argument);
            long ae = task.positions.getEndPosition(unit, argument);
            if (as >= 0 && ae >= as && ae <= source.length()) {
                args.add(new SemanticArgument(source.subSequence((int) as, (int) ae).toString(), new SourceRange(file, (int) as, (int) ae)));
            }
        }
        boolean statement = path.getParentPath() != null && path.getParentPath().getLeaf() instanceof com.sun.source.tree.ExpressionStatementTree;
        SourceRange receiverRange = null;
        String receiverText = "";
        if (node.getMethodSelect() instanceof MemberSelectTree memberSelect) {
            long rs = task.positions.getStartPosition(unit, memberSelect.getExpression());
            long re = task.positions.getEndPosition(unit, memberSelect.getExpression());
            if (rs >= 0 && re >= rs && re <= source.length()) {
                receiverRange = new SourceRange(file, (int) rs, (int) re);
                receiverText = source.subSequence((int) rs, (int) re).toString();
            }
        }
        return new SemanticCallSite(file, new SourceRange(file, (int) start, (int) end),
                new SourceRange(file, (int) span.startOffset(), (int) span.endOffset()), receiverRange, receiverText, args, false, statement);
    }

    private SemanticCallSite syntacticInvocationSite(
            CompilerTask task,
            CompilationUnitTree unit,
            Path file,
            CharSequence source,
            TreePath path,
            MethodInvocationTree node,
            String invokedName) {
        long start = task.positions.getStartPosition(unit, node);
        long end = task.positions.getEndPosition(unit, node);
        if (start < 0 || end < start || end > source.length()) {
            return null;
        }
        int nameStart = source.subSequence((int) start, (int) end).toString().indexOf(invokedName);
        SourceRange nameRange = nameStart < 0
                ? new SourceRange(file, (int) start, (int) start)
                : new SourceRange(file, (int) start + nameStart, (int) start + nameStart + invokedName.length());
        List<SemanticArgument> args = new ArrayList<>();
        for (ExpressionTree argument : node.getArguments()) {
            long as = task.positions.getStartPosition(unit, argument);
            long ae = task.positions.getEndPosition(unit, argument);
            if (as >= 0 && ae >= as && ae <= source.length()) {
                args.add(new SemanticArgument(source.subSequence((int) as, (int) ae).toString(), new SourceRange(file, (int) as, (int) ae)));
            }
        }
        SourceRange receiverRange = null;
        String receiverText = "";
        if (node.getMethodSelect() instanceof MemberSelectTree memberSelect) {
            long rs = task.positions.getStartPosition(unit, memberSelect.getExpression());
            long re = task.positions.getEndPosition(unit, memberSelect.getExpression());
            if (rs >= 0 && re >= rs && re <= source.length()) {
                receiverRange = new SourceRange(file, (int) rs, (int) re);
                receiverText = source.subSequence((int) rs, (int) re).toString();
            }
        }
        boolean statement = path.getParentPath() != null && path.getParentPath().getLeaf() instanceof com.sun.source.tree.ExpressionStatementTree;
        return new SemanticCallSite(file, new SourceRange(file, (int) start, (int) end), nameRange, receiverRange, receiverText, args, false, statement);
    }

    private long[] lineRange(CompilationUnitTree unit, int oneBasedLine) {
        if (oneBasedLine <= 0) {
            return new long[]{0, Long.MAX_VALUE};
        }
        try {
            long start = unit.getLineMap().getPosition(oneBasedLine, 1);
            long next = unit.getLineMap().getPosition(oneBasedLine + 1L, 1);
            return new long[]{start, next < 0 ? start : next};
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String sourceForTree(CompilerTask task, CompilationUnitTree unit, CharSequence source, Tree tree) {
        if (tree == null || source == null) {
            return "";
        }
        long start = task.positions.getStartPosition(unit, tree);
        long end = task.positions.getEndPosition(unit, tree);
        if (start < 0 || end < start || end > source.length()) {
            return tree.toString();
        }
        return source.subSequence((int) start, (int) end).toString();
    }

    static int firstChar(CharSequence source, int from, int to, char target) {
        if (source == null) {
            return -1;
        }
        int end = Math.min(to, source.length());
        for (int i = Math.max(0, from); i < end; i++) {
            if (source.charAt(i) == target) {
                return i;
            }
        }
        return -1;
    }

    static int lastChar(CharSequence source, int from, int to, char target) {
        if (source == null) {
            return -1;
        }
        for (int i = Math.min(to, source.length()) - 1; i >= Math.max(0, from); i--) {
            if (source.charAt(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private Optional<CompilationUnitTree> findUnit(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return units.stream().filter(unit -> pathOf(unit).equals(normalized)).findFirst();
    }

    static Path pathOf(CompilationUnitTree unit) {
        URI uri = unit.getSourceFile().toUri();
        return Path.of(uri).toAbsolutePath().normalize();
    }

    @Override
    public void close() {
        // The standard file managers backing this index's tasks are owned by FileManagerPool and shared across javac
        // tasks, so closing them here would discard the cached jar/file-system scan the pool exists to preserve. The
        // pool releases them on project-model change and on shutdown; this index holds no other resources to release.
    }

    /** The project source charset (from the Serena-configured/source-set encoding), defaulting to UTF-8. */
    public static Charset charsetOf(JavaProjectModel model) {
        for (SourceSet sourceSet : model.sourceSets()) {
            String encoding = sourceSet.encoding();
            if (encoding != null && !encoding.isBlank()) {
                try {
                    return Charset.forName(encoding);
                } catch (RuntimeException ignored) {
                    // fall through to the next source set / default
                }
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    static CharSequence readSource(Path path, Charset charset) {
        try {
            return Files.readString(path, charset);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * One {@link JavacTask} for a single source set, parsed and analyzed with that source set's OWN javacOptions
     * (augmented by the other source sets' roots on {@code -sourcepath} + {@code -implicit:none}, mirroring
     * {@code JavacSession.crossSourceSetOptions}, so cross-set references resolve against source). Holds the task's
     * {@code Trees}/{@code Elements}/{@code Types}/positions, its compilation units and source text. The
     * {@link StandardJavaFileManager} it compiled through is owned by {@link FileManagerPool} (shared across tasks and
     * passes), so this task neither stores nor closes it.
     */


    private final class TargetResolver extends TreePathScanner<Void, Void> {
        private final Path file;
        private final CompilationUnitTree unit;
        private final long offset;
        private final String nameHint;
        private Candidate best;

        private TargetResolver(Path file, CompilationUnitTree unit, long offset, String nameHint) {
            this.file = file;
            this.unit = unit;
            this.offset = offset;
            this.nameHint = nameHint == null ? "" : nameHint;
        }

        private ResolvedTarget resolve() {
            scan(unit, null);
            return best == null ? null : best.target();
        }

        @Override
        public Void scan(Tree tree, Void unused) {
            if (tree == null) {
                return null;
            }
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            if (start <= offset && offset <= end) {
                TreePath path = new TreePath(getCurrentPath(), tree);
                super.scan(tree, unused);
                consider(path, tree, start, end);
            }
            return null;
        }

        private void consider(TreePath path, Tree tree, long start, long end) {
            if (!isIdentifierBearing(tree)) {
                return;
            }
            Element element = trees.getElement(path);
            if (!isRefactorableElement(element)) {
                return;
            }
            CharSequence source = sourceByPath.get(file.toAbsolutePath().normalize());
            IdentifierSpan span = spanFinder.find(file, unit, positions, tree, element, source);
            // A precise editable identifier span is mandatory in every targeting mode: a refactor rewrites the
            // symbol's identifier, so without a span there is nothing safe to edit. Selecting the smallest enclosing
            // refactorable tree purely by AST containment (a method body, an initializer, braces, whitespace) is never
            // acceptable — it can silently rename/delete/inline the wrong symbol.
            if (span == null) {
                return;
            }
            // Exclusive-end span containment: startOffset <= offset < endOffset. A cursor one past the last identifier
            // character (offset == endOffset) is outside the symbol.
            boolean cursorInSpan = offset >= span.startOffset() && offset < span.endOffset();
            // A nameHint disambiguates only when the cursor is not already inside the identifier span: it must then match
            // the resolved symbol's simple name exactly to prove the (approximate) position intentionally maps to it.
            // Constructors expose the simple name "<init>", so a cursor placed directly on the constructor's type-name
            // span is accepted via cursorInSpan rather than via the hint.
            boolean nameHintMatches = !nameHint.isEmpty() && element.getSimpleName().contentEquals(nameHint);
            if (!cursorInSpan && !nameHintMatches) {
                // Direct line/column with the cursor outside the span (and no matching hint) would select an enclosing
                // declaration purely by AST containment, which can silently rename/delete/inline the wrong symbol.
                return;
            }
            Candidate candidate = new Candidate(new ResolvedTarget(element, SemanticKey.from(element, trees, types, unit, file), span), end - start);
            if (best == null || candidate.width() < best.width()) {
                best = candidate;
            }
        }
    }

    private final class ReferenceScanner extends TreePathScanner<Void, Void> {
        private final CompilerTask task;
        private final Set<String> targetKeys;
        private final Path file;
        private final CompilationUnitTree unit;
        private final CharSequence source;
        private final List<IdentifierSpan> references;

        private ReferenceScanner(CompilerTask task, Set<String> targetKeys, Path file, CompilationUnitTree unit, CharSequence source, List<IdentifierSpan> references) {
            this.task = task;
            this.targetKeys = targetKeys;
            this.file = file;
            this.unit = unit;
            this.source = source;
            this.references = references;
        }

        @Override
        public Void scan(Tree tree, Void unused) {
            if (tree == null) {
                return null;
            }
            TreePath path = new TreePath(getCurrentPath(), tree);
            if (isIdentifierBearing(tree)) {
                // Resolve the node's element via THIS task's Trees/Types and compare by canonical key: Element identity is
                // not comparable across separate compiler tasks.
                Element element = task.trees.getElement(path);
                if (element != null
                        && targetKeys.contains(SemanticKey.from(element, task.trees, task.types, unit, file).canonical())) {
                    IdentifierSpan span = spanFinder.find(file, unit, task.positions, tree, element, source);
                    if (span != null) {
                        references.add(span);
                    }
                } else if (element == null && tree instanceof com.sun.source.tree.ImportTree importTree && importTree.isStatic()) {
                    recordStaticImportMember(importTree, path);
                }
            }
            return super.scan(tree, unused);
        }

        /**
         * javac does not attribute an element to the member-select inside a {@code import static Type.member;}
         * declaration, so {@code getElement} returns null and the import line of a renamed static-imported field/method
         * would be missed. Resolve the owning type from the qualifier expression, then match any enclosed member with the
         * imported simple name by canonical key; on a match, edit the import's trailing simple-name token.
         */
        private void recordStaticImportMember(com.sun.source.tree.ImportTree importTree, TreePath importPath) {
            if (!(importTree.getQualifiedIdentifier() instanceof com.sun.source.tree.MemberSelectTree qualified)) {
                return;
            }
            Element owner = task.trees.getElement(new TreePath(importPath, qualified.getExpression()));
            if (!(owner instanceof TypeElement type)) {
                return;
            }
            String memberName = qualified.getIdentifier().toString();
            for (Element enclosed : type.getEnclosedElements()) {
                if (!enclosed.getSimpleName().contentEquals(memberName)) {
                    continue;
                }
                if (targetKeys.contains(SemanticKey.from(enclosed, task.trees, task.types, unit, file).canonical())) {
                    IdentifierSpan span = spanFinder.find(file, unit, task.positions, importTree, enclosed, source);
                    if (span != null) {
                        references.add(span);
                    }
                    return;
                }
            }
        }
    }

    /**
     * Walks the doc comment of every class/method/variable declaration in a compilation unit. For each
     * {@code @link}/{@code @linkplain}/{@code @see} reference it resolves the referenced element via {@code DocTrees}
     * and, when the element's canonical key is in {@code targetKeys}, emits the simple-name span of the referenced
     * member/type. For a parameter rename ({@code paramName} non-null) it additionally rewrites {@code @param paramName}
     * tags on the doc comment whose enclosing executable declares {@code parameterTarget}.
     */
    private final class DocReferenceScanner extends TreePathScanner<Void, Void> {
        private final CompilerTask task;
        private final com.sun.source.util.DocTrees docTrees;
        private final Set<String> targetKeys;
        private final String paramName;
        private final Element parameterTarget;
        private final Path file;
        private final CompilationUnitTree unit;
        private final CharSequence source;
        private final List<IdentifierSpan> out;

        private DocReferenceScanner(CompilerTask task, com.sun.source.util.DocTrees docTrees, Set<String> targetKeys, String paramName, Element parameterTarget, Path file, CompilationUnitTree unit, CharSequence source, List<IdentifierSpan> out) {
            this.task = task;
            this.docTrees = docTrees;
            this.targetKeys = targetKeys;
            this.paramName = paramName;
            this.parameterTarget = parameterTarget;
            this.file = file;
            this.unit = unit;
            this.source = source;
            this.out = out;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            handleDeclaration();
            return super.visitClass(node, unused);
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            handleDeclaration();
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            handleDeclaration();
            return super.visitVariable(node, unused);
        }

        private void handleDeclaration() {
            TreePath path = getCurrentPath();
            com.sun.source.doctree.DocCommentTree docComment = docTrees.getDocCommentTree(path);
            if (docComment == null) {
                return;
            }
            Element owner = task.trees.getElement(path);
            com.sun.source.util.DocTreePath rootDocPath = new com.sun.source.util.DocTreePath(path, docComment);
            new ReferenceTreeVisitor(path, docComment, owner).scan(rootDocPath, null);
        }

        private final class ReferenceTreeVisitor extends com.sun.source.util.DocTreePathScanner<Void, Void> {
            private final TreePath declPath;
            private final com.sun.source.doctree.DocCommentTree docComment;
            private final Element owner;

            private ReferenceTreeVisitor(TreePath declPath, com.sun.source.doctree.DocCommentTree docComment, Element owner) {
                this.declPath = declPath;
                this.docComment = docComment;
                this.owner = owner;
            }

            @Override
            public Void visitReference(com.sun.source.doctree.ReferenceTree node, Void unused) {
                com.sun.source.util.DocTreePath docPath = getCurrentPath();
                Element referenced = docTrees.getElement(docPath);
                if (referenced != null
                        && targetKeys.contains(SemanticKey.from(referenced, task.trees, task.types, unit, file).canonical())) {
                    recordReferenceSpan(node);
                }
                return super.visitReference(node, unused);
            }

            @Override
            public Void visitParam(com.sun.source.doctree.ParamTree node, Void unused) {
                if (paramName != null && parameterTarget != null && owner != null
                        && parameterTarget.getEnclosingElement() != null
                        && parameterTarget.getEnclosingElement().equals(owner)
                        && node.getName() != null
                        && node.getName().getName().contentEquals(paramName)) {
                    recordTokenSpan(node.getName(), paramName);
                }
                return super.visitParam(node, unused);
            }

            private void recordReferenceSpan(com.sun.source.doctree.ReferenceTree node) {
                com.sun.source.util.DocSourcePositions docPositions = docTrees.getSourcePositions();
                long start = docPositions.getStartPosition(unit, docComment, node);
                long end = docPositions.getEndPosition(unit, docComment, node);
                if (start < 0 || end < start || end > source.length()) {
                    return;
                }
                // The simple-name token to edit is the LAST identifier in the reference ("Foo#bar" -> "bar",
                // "pkg.Foo" -> "Foo", "Foo" -> "Foo"). Scan the reference text for the trailing identifier run.
                int rangeEnd = (int) end;
                int tokenEnd = rangeEnd;
                while (tokenEnd > start && !Character.isJavaIdentifierPart(source.charAt(tokenEnd - 1))) {
                    tokenEnd--;
                }
                int tokenStart = tokenEnd;
                while (tokenStart > start && Character.isJavaIdentifierPart(source.charAt(tokenStart - 1))) {
                    tokenStart--;
                }
                if (tokenStart < tokenEnd) {
                    out.add(IdentifierSpan.fromOffsets(file, unit, source, tokenStart, tokenEnd));
                }
            }

            private void recordTokenSpan(com.sun.source.doctree.DocTree node, String name) {
                com.sun.source.util.DocSourcePositions docPositions = docTrees.getSourcePositions();
                long start = docPositions.getStartPosition(unit, docComment, node);
                long end = docPositions.getEndPosition(unit, docComment, node);
                if (start < 0 || end < start || end > source.length()) {
                    return;
                }
                int nameLen = name.length();
                if ((int) end - (int) start == nameLen) {
                    out.add(IdentifierSpan.fromOffsets(file, unit, source, (int) start, (int) end));
                }
            }
        }
    }

    private static boolean isIdentifierBearing(Tree tree) {
        return switch (tree.getKind()) {
            case IDENTIFIER, MEMBER_SELECT, MEMBER_REFERENCE, NEW_CLASS, VARIABLE, METHOD, CLASS, INTERFACE, ENUM,
                    RECORD, ANNOTATION_TYPE, IMPORT, METHOD_INVOCATION -> true;
            default -> false;
        };
    }

    private static boolean isRefactorableElement(Element element) {
        if (element == null) {
            return false;
        }
        return switch (element.getKind()) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, METHOD, CONSTRUCTOR, FIELD, RECORD_COMPONENT,
                    LOCAL_VARIABLE, PARAMETER -> true;
            // EXCEPTION_PARAMETER is resolvable so safe delete can reach its construct-specific refusal (a catch
            // clause's parameter is mandatory and cannot be removed on its own) rather than a generic target_not_found.
            default -> element.getKind().isField() || element.getKind() == ElementKind.RESOURCE_VARIABLE
                    || element.getKind() == ElementKind.EXCEPTION_PARAMETER;
        };
    }

    private record Candidate(ResolvedTarget target, long width) {
    }
}
