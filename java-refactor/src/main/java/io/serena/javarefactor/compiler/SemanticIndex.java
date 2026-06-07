package io.serena.javarefactor.compiler;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
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
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PackageTree;
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
import javax.lang.model.element.VariableElement;
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
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final SourcePositions positions;
    private final List<CompilationUnitTree> units;
    private final Map<Path, CharSequence> sourceByPath;
    private final IdentifierSpanFinder spanFinder = new IdentifierSpanFinder();

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
    private List<CompilerTask> allTasks() {
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
        private final SourcePositions positions;
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
        CharSequence source = sourceByPath.get(pathOf(unit));
        if (source == null || end > source.length()) {
            return null;
        }
        return new InitializerInfo(source.subSequence((int) start, (int) end).toString(), (int) start, (int) end);
    }

    /** Whether the element is a Java compile-time constant (a {@code static final} field with a constant value). */
    public boolean isCompileTimeConstant(Element element) {
        return element instanceof VariableElement variable && variable.getConstantValue() != null;
    }

    /** The syntactic kind of a variable/field initializer expression, or null when there is no initializer. */
    public Tree.Kind initializerKind(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null || !(path.getLeaf() instanceof VariableTree variable) || variable.getInitializer() == null) {
            return null;
        }
        return variable.getInitializer().getKind();
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

    /**
     * The name of the first usage parent context not covered by the inline parenthesization model, or null when every
     * usage sits in a modelled (operator/precedence) or known full-expression context. Method-reference qualifiers
     * ({@code expr::m}) and any future/unknown syntax fall here, so the planner can refuse instead of guessing.
     */
    public String firstUnsupportedInlineUsageContext(Element element) {
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

    /** Whether substituting an inlined expression under {@code parent} is covered by the parenthesization model. */
    private static boolean isModelledInlineParent(Tree parent) {
        return parent instanceof BinaryTree || SAFE_NO_PAREN_INLINE_PARENTS.contains(parent.getKind());
    }

    public List<UsageReplacement> usageReplacements(Element element, String initializerText) {
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
        Path file = pathOf(unit);
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

    /** The nearest enclosing executable (method/constructor) declaration path of {@code path}, or null. */
    private static TreePath enclosingExecutablePath(TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof MethodTree) {
                return current;
            }
        }
        return null;
    }

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

    /**
     * The minimum operator precedence the usage's position in {@code parentPath} requires of the spliced expression, or
     * {@code -1} when the position is a full-expression slot that needs no parentheses (statement, return, argument,
     * assignment RHS, ternary branches, etc.). For a binary/instanceof parent it is that operator's precedence.
     */
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

    /** Precedence of a binary/relational operator kind (higher binds tighter). */
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

    /** Precedence of a whole expression by the kind of its top-level operator (higher binds tighter). */
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

    /** A single inline usage replacement: the reference span and the initializer text parenthesized for that site. */
    public record UsageReplacement(IdentifierSpan span, String replacement) {
    }

    /**
     * Whether a variable's initializer performs observable side effects (method/constructor calls, array creation,
     * assignments, or in/decrements). Such initializers cannot be safely inlined into multiple usages because each
     * usage would re-run the effect. Returns true (conservative) when the initializer cannot be analyzed.
     */
    public boolean initializerHasSideEffects(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null || !(path.getLeaf() instanceof VariableTree variable) || variable.getInitializer() == null) {
            return true;
        }
        return expressionHasObservableSideEffects(variable.getInitializer());
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
        TreePath path = trees.getPath(element);
        if (path == null || !(path.getLeaf() instanceof VariableTree variable)) {
            return true;
        }
        if (variable.getInitializer() == null) {
            return false;
        }
        return expressionHasObservableSideEffects(variable.getInitializer());
    }

    /**
     * Whether {@code expression} performs an observable side effect: a method or constructor invocation, array creation,
     * (compound) assignment, or pre/post increment/decrement. Shared by the inline and safe-delete side-effect gates.
     */
    private static boolean expressionHasObservableSideEffects(ExpressionTree expression) {
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

    /** Whether the element is ever assigned, compound-assigned, or incremented/decremented anywhere in the project. */
    public boolean isReassigned(Element element) {
        ReassignmentScanner scanner = new ReassignmentScanner(element);
        for (CompilationUnitTree unit : units) {
            scanner.scan(unit, null);
        }
        return scanner.reassigned;
    }

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
        for (int i = Math.max(declAstEnd, nameEnd); i < n; i++) {
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

    private Optional<CompilationUnitTree> findUnit(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return units.stream().filter(unit -> pathOf(unit).equals(normalized)).findFirst();
    }

    private static Path pathOf(CompilationUnitTree unit) {
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

    private static CharSequence readSource(Path path, Charset charset) {
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
    private static final class CompilerTask {
        private final Trees trees;
        private final com.sun.source.util.DocTrees docTrees;
        private final Elements elements;
        private final Types types;
        private final SourcePositions positions;
        private final List<CompilationUnitTree> units;
        private final Map<Path, CharSequence> sourceByPath;
        private List<TypeElement> projectTypesCache;

        private CompilerTask(Trees trees, com.sun.source.util.DocTrees docTrees, Elements elements, Types types, SourcePositions positions, List<CompilationUnitTree> units, Map<Path, CharSequence> sourceByPath) {
            this.trees = trees;
            this.docTrees = docTrees;
            this.elements = elements;
            this.types = types;
            this.positions = positions;
            this.units = units;
            this.sourceByPath = sourceByPath;
        }

        private static CompilerTask open(JavaCompiler compiler, FileManagerPool fileManagerPool, SourceSet sourceSet, List<SourceSet> allSourceSets) throws IOException {
            List<Path> javaFiles = sourceSet.javaFiles().stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .distinct()
                    .toList();
            Charset charset = Charset.forName(sourceSet.encoding() == null ? "UTF-8" : sourceSet.encoding());
            DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
            List<String> options = crossSourceSetOptions(sourceSet, allSourceSets);
            // Reuse the pooled standard file manager for this (charset, options) configuration so the classpath/jar scan
            // is amortized across source sets and passes. The manager is owned by the pool and must NOT be closed here;
            // SemanticIndex.close() leaves it alone and the pool drops it on model change / shutdown.
            StandardJavaFileManager fileManager = fileManagerPool.acquire(compiler, charset, options);
            Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromPaths(javaFiles);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, collector, options, null, files);
            List<CompilationUnitTree> units = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                units.add(unit);
            }
            task.analyze();
            Map<Path, CharSequence> sourceByPath = javaFiles.stream()
                    .collect(Collectors.toMap(path -> path, path -> readSource(path, charset), (left, ignored) -> left));
            Trees trees = Trees.instance(task);
            com.sun.source.util.DocTrees docTrees = com.sun.source.util.DocTrees.instance(task);
            return new CompilerTask(trees, docTrees, task.getElements(), task.getTypes(), trees.getSourcePositions(), units, sourceByPath);
        }

        /**
         * The source set's own javacOptions, augmented with the other source sets' source roots on {@code -sourcepath}
         * and {@code -implicit:none} (so cross-source-set references resolve against source without requiring the
         * referenced set to be pre-compiled). Modular source sets resolve cross-module references via their own
         * {@code --module-source-path}, so a flat {@code -sourcepath} would conflict; their options are left untouched.
         * Mirrors {@code JavacSession.crossSourceSetOptions}.
         */
        private static List<String> crossSourceSetOptions(SourceSet sourceSet, List<SourceSet> allSourceSets) {
            // Mirrors JavacSession.crossSourceSetOptions: only the depended-on source sets' roots (e.g. test -> main)
            // are added to -sourcepath, so main is indexed without visibility into test.
            List<String> otherRoots = SourceSet.crossSourceRoots(sourceSet, allSourceSets);
            if (otherRoots.isEmpty() || sourceSet.modular()) {
                return sourceSet.javacOptions();
            }
            List<String> options = new ArrayList<>(sourceSet.javacOptions());
            options.add("-sourcepath");
            options.add(String.join(File.pathSeparator, otherRoots));
            options.add("-implicit:none");
            return options;
        }

        /** Top-level and nested type declarations of this task's own compilation units. */
        private List<TypeElement> projectTypes() {
            if (projectTypesCache == null) {
                LinkedHashSet<TypeElement> result = new LinkedHashSet<>();
                for (CompilationUnitTree unit : units) {
                    TreePath unitPath = new TreePath(unit);
                    for (Tree decl : unit.getTypeDecls()) {
                        Element element = trees.getElement(new TreePath(unitPath, decl));
                        if (element instanceof TypeElement type) {
                            addTypeRecursively(type, result);
                        }
                    }
                }
                projectTypesCache = new ArrayList<>(result);
            }
            return projectTypesCache;
        }

        private static void addTypeRecursively(TypeElement type, Set<TypeElement> out) {
            if (!out.add(type)) {
                return;
            }
            for (Element enclosed : type.getEnclosedElements()) {
                if (enclosed instanceof TypeElement nested) {
                    addTypeRecursively(nested, out);
                }
            }
        }

        /** Canonical key of an element resolved in this task (using this task's trees/types/unit/file). */
        private String canonicalKey(Element element) {
            TreePath path = trees.getPath(element);
            if (path == null) {
                return SemanticKey.from(element).canonical();
            }
            CompilationUnitTree unit = path.getCompilationUnit();
            return SemanticKey.from(element, trees, types, unit, pathOf(unit)).canonical();
        }
    }

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
