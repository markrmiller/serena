package io.serena.javarefactor.compiler;

import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiler-backed extractor that materializes the Java-symbol, type-hierarchy, and call-graph facts a V3
 * {@code TransformationGraph} needs (refactor-feature-plan-V3.md §1.2). It walks the same set of analyzed compiler tasks
 * as {@link ReachabilityGraph} (home + secondaries held by an open {@link SemanticIndex}) and emits plain, materialized
 * value objects — canonical key strings, FQNs, file paths, and string-keyed edge maps — never live javac {@code
 * Element}/{@code TypeMirror} references. A {@code Facts} instance therefore stays valid after the {@link SemanticIndex}
 * it was built from is closed, exactly like a {@link ReachabilityGraph}.
 *
 * <p>This class lives in the {@code compiler} package (the same reason as {@link ReachabilityGraph} and
 * {@link ImpactFactsAnalyzer}) so it can read {@link SemanticIndex#allTasks()}, the package-private {@link CompilerTask}
 * facets, and {@link SemanticIndex#pathOf(CompilationUnitTree)}. It performs no mutation. The {@code v3.graph}
 * {@code TransformationGraphBuilder} composes these facts with the build model, the resource SPI, and the framework
 * index to assemble the full graph.
 *
 * <p>The materialized facts are organized as:
 * <ul>
 *   <li><b>types</b>: every top-level and nested type with its FQN, simple name, package, declaring file, kind, and
 *       public-API visibility (the {@link JavaSymbolGraph} type nodes);</li>
 *   <li><b>members</b>: every method/constructor/field declaration with owner FQN, name, and a parameter count (the
 *       {@link JavaSymbolGraph}/{@link CallGraph} member index);</li>
 *   <li><b>supertypes</b>: the resolved direct supertype FQNs of each project type (the {@link TypeHierarchyIndex});</li>
 *   <li><b>overrideGroups</b>: members that override the same supertype contract, grouped by the canonical key of the
 *       supertype member they satisfy;</li>
 *   <li><b>callEdges</b>: caller-member canonical key &rarr; the canonical keys of the members/constructors it invokes
 *       or method-references (the {@link CallGraph}).</li>
 * </ul>
 */
public final class TransformationGraphFacts {

    /** A top-level or nested type declaration. */
    public record TypeFact(
            String canonicalKey,
            String fqn,
            String simpleName,
            String packageName,
            String relativePath,
            String kind,
            boolean topLevel,
            boolean publicApi,
            boolean testSource) {
    }

    /** A method, constructor, or field declaration owned by a type. */
    public record MemberFact(
            String canonicalKey,
            String ownerFqn,
            String ownerKey,
            String name,
            String memberKind,
            int parameterCount,
            String relativePath,
            boolean publicApi,
            boolean testSource) {
    }

    private final List<TypeFact> types;
    private final List<MemberFact> members;
    private final Map<String, Set<String>> supertypeFqns;
    private final Map<String, Set<String>> callEdges;
    private final Map<String, Set<String>> methodReferenceEdges;
    private final Map<String, Set<String>> constructorEdges;
    private final Map<String, Set<String>> overrideGroups;

    private TransformationGraphFacts(List<TypeFact> types, List<MemberFact> members,
            Map<String, Set<String>> supertypeFqns, Map<String, Set<String>> callEdges,
            Map<String, Set<String>> methodReferenceEdges, Map<String, Set<String>> constructorEdges,
            Map<String, Set<String>> overrideGroups) {
        this.types = types;
        this.members = members;
        this.supertypeFqns = supertypeFqns;
        this.callEdges = callEdges;
        this.methodReferenceEdges = methodReferenceEdges;
        this.constructorEdges = constructorEdges;
        this.overrideGroups = overrideGroups;
    }

    public List<TypeFact> types() {
        return types;
    }

    public List<MemberFact> members() {
        return members;
    }

    /** Type FQN -> resolved direct supertype FQNs (project + library), excluding {@code java.lang.Object}. */
    public Map<String, Set<String>> supertypeFqns() {
        return supertypeFqns;
    }

    /** Caller member canonical key -> invoked method/constructor canonical keys. */
    public Map<String, Set<String>> callEdges() {
        return callEdges;
    }

    /** Caller member canonical key -> method-reference target canonical keys. */
    public Map<String, Set<String>> methodReferenceEdges() {
        return methodReferenceEdges;
    }

    /** Caller member canonical key -> invoked constructor canonical keys. */
    public Map<String, Set<String>> constructorEdges() {
        return constructorEdges;
    }

    /** Supertype member canonical key -> the project member keys that override/implement it. */
    public Map<String, Set<String>> overrideGroups() {
        return overrideGroups;
    }

    /**
     * Canonical key (member or type) -> the project-relative declaring file of that declaration. Used by the R05
     * incremental updater to partition every fact map by declaring file so untouched files' contributions are carried
     * forward and affected files' contributions are replaced.
     */
    public Map<String, String> keyToRelativePath() {
        Map<String, String> map = new LinkedHashMap<>();
        for (TypeFact type : types) {
            map.put(type.canonicalKey(), type.relativePath());
        }
        for (MemberFact member : members) {
            map.put(member.canonicalKey(), member.relativePath());
        }
        return map;
    }

    /** Type FQN -> the project-relative declaring file of its top-level declaration (for supertype-map partitioning). */
    public Map<String, String> typeFqnToRelativePath() {
        Map<String, String> map = new LinkedHashMap<>();
        for (TypeFact type : types) {
            // A supertypeFqns key is the child type's FQN; map it to the file declaring that FQN.
            map.putIfAbsent(type.fqn(), type.relativePath());
        }
        return map;
    }

    /**
     * Walks every analyzed task of {@code index} and materializes the symbol/hierarchy/call facts.
     *
     * @param index        an open semantic index (home + secondary tasks)
     * @param model        the validated project model (for test-source classification)
     */
    public static TransformationGraphFacts extract(SemanticIndex index, JavaProjectModel model) {
        return extract(index, model, null);
    }

    /**
     * Scoped extraction (R05 incremental graph maintenance): materializes the symbol/hierarchy/call facts only for
     * declarations whose declaring file is in {@code includeRelativePaths}, skipping the per-file fact materialization for
     * untouched files. Resolution still uses the whole-project {@link SemanticIndex} (javac is not made incremental), but
     * the expensive per-file declaration/edge walk is restricted to the affected file set; the caller carries forward the
     * untouched files' previously-extracted facts and re-stitches the cross-file indices. A {@code null} include set
     * extracts every file (the from-scratch path), making this overload byte-for-byte equivalent to {@link
     * #extract(SemanticIndex, JavaProjectModel)} when {@code includeRelativePaths} is {@code null}.
     *
     * @param index                an open semantic index (home + secondary tasks)
     * @param model                the validated project model (for test-source classification)
     * @param includeRelativePaths project-relative declaring files to extract facts for, or {@code null} for all files
     */
    public static TransformationGraphFacts extract(SemanticIndex index, JavaProjectModel model,
            Set<String> includeRelativePaths) {
        return extract(index, model, includeRelativePaths, Set.of());
    }

    /**
     * Scoped extraction with a carried-forward member-key universe (R05 incremental graph maintenance). Identical to
     * {@link #extract(SemanticIndex, JavaProjectModel, Set)} except edge targets are resolved against
     * {@code carriedMemberKeys} in addition to the freshly-extracted member keys, so an included caller's edge to a
     * member declared in an UNTOUCHED (carried-forward) file is still recorded. Edges are still only attributed to a
     * caller declared in an included file; the untouched callers' edges are carried forward by the updater.
     */
    public static TransformationGraphFacts extract(SemanticIndex index, JavaProjectModel model,
            Set<String> includeRelativePaths, Set<String> carriedMemberKeys) {
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();
        Set<Path> testRoots = testSourceRoots(model);

        Map<String, TypeFact> typesByKey = new LinkedHashMap<>();
        Map<String, MemberFact> membersByKey = new LinkedHashMap<>();
        Map<String, Set<String>> supertypeFqns = new LinkedHashMap<>();
        Map<String, Set<String>> overrideGroups = new LinkedHashMap<>();
        Map<String, Set<String>> callEdges = new LinkedHashMap<>();
        Map<String, Set<String>> methodReferenceEdges = new LinkedHashMap<>();
        Map<String, Set<String>> constructorEdges = new LinkedHashMap<>();

        // Pass 1: declarations — types, members, supertypes, override groups.
        for (CompilerTask task : index.allTasks()) {
            Trees trees = task.trees;
            Types types = task.types;
            for (TypeElement type : task.projectTypes()) {
                TreePath typePath = trees.getPath(type);
                CompilationUnitTree unit = typePath == null ? null : typePath.getCompilationUnit();
                Path file = unit == null ? null : SemanticIndex.pathOf(unit);
                String relPath = file == null ? "" : PlannerSupport.relative(projectRoot, file);
                if (includeRelativePaths != null && !includeRelativePaths.contains(relPath)) {
                    // Scoped extraction: this file's facts are carried forward by the incremental updater, not re-walked.
                    continue;
                }
                boolean testSource = file != null && isUnderAny(file, testRoots);
                String typeKey = canonicalKey(type, trees, types, unit, file);
                if (typeKey == null || typeKey.isBlank()) {
                    continue;
                }
                boolean topLevel = type.getEnclosingElement() != null
                        && type.getEnclosingElement().getKind() == ElementKind.PACKAGE;
                boolean publicApi = type.getModifiers().contains(Modifier.PUBLIC)
                        || type.getModifiers().contains(Modifier.PROTECTED);
                typesByKey.putIfAbsent(typeKey, new TypeFact(
                        typeKey,
                        type.getQualifiedName().toString(),
                        type.getSimpleName().toString(),
                        packageNameOf(type),
                        relPath,
                        typeKindLabel(type.getKind()),
                        topLevel,
                        publicApi,
                        testSource));

                // Supertypes: resolved direct supertype FQNs (excluding java.lang.Object), library or project.
                Set<String> parents = supertypeFqns.computeIfAbsent(
                        type.getQualifiedName().toString(), k -> new LinkedHashSet<>());
                for (TypeMirror supertype : types.directSupertypes(type.asType())) {
                    String fqn = declaredTypeFqn(supertype);
                    if (fqn != null && !"java.lang.Object".equals(fqn)) {
                        parents.add(fqn);
                    }
                }

                // Members + override groups.
                for (Element enclosed : type.getEnclosedElements()) {
                    ElementKind kind = enclosed.getKind();
                    String memberKind = memberKindLabel(kind);
                    if (memberKind == null) {
                        continue;
                    }
                    String memberKey = canonicalKey(enclosed, trees, types, unit, file);
                    if (memberKey == null || memberKey.isBlank() || membersByKey.containsKey(memberKey)) {
                        continue;
                    }
                    int paramCount = enclosed instanceof ExecutableElement exec ? exec.getParameters().size() : 0;
                    boolean memberPublicApi = enclosed.getModifiers().contains(Modifier.PUBLIC)
                            || enclosed.getModifiers().contains(Modifier.PROTECTED);
                    membersByKey.put(memberKey, new MemberFact(
                            memberKey,
                            type.getQualifiedName().toString(),
                            typeKey,
                            enclosed.getSimpleName().toString(),
                            memberKind,
                            paramCount,
                            relPath,
                            memberPublicApi,
                            testSource));

                    // Override groups: a method that overrides a supertype contract is grouped under the supertype
                    // member's canonical key, so callers can see "which project members satisfy this contract".
                    if (enclosed instanceof ExecutableElement method && kind == ElementKind.METHOD) {
                        recordOverrides(index, method, memberKey, trees, types, unit, file, overrideGroups);
                    }
                }
            }
        }

        // Pass 2: call/reference edges between registered members. Edge TARGETS are resolved against the full member-key
        // universe (freshly-extracted members + the carried-forward untouched members), so an included caller's edge to
        // an untouched member is recorded; edges are only attributed to callers declared in an included unit.
        Set<String> targetMemberKeys = new LinkedHashSet<>(membersByKey.keySet());
        targetMemberKeys.addAll(carriedMemberKeys);
        Set<String> registeredKeys = new LinkedHashSet<>(targetMemberKeys);
        registeredKeys.addAll(typesByKey.keySet());
        for (CompilerTask task : index.allTasks()) {
            for (CompilationUnitTree unit : task.units) {
                Path file = SemanticIndex.pathOf(unit);
                String relPath = file == null ? "" : PlannerSupport.relative(projectRoot, file);
                if (includeRelativePaths != null && !includeRelativePaths.contains(relPath)) {
                    // Scoped extraction: this caller's edges are carried forward, not re-attributed.
                    continue;
                }
                new CallEdgeScanner(task.trees, task.types, unit, file, registeredKeys, targetMemberKeys,
                        callEdges, methodReferenceEdges, constructorEdges).scan(unit, null);
            }
        }

        return new TransformationGraphFacts(
                new ArrayList<>(typesByKey.values()),
                new ArrayList<>(membersByKey.values()),
                supertypeFqns,
                callEdges,
                methodReferenceEdges,
                constructorEdges,
                overrideGroups);
    }

    /**
     * Merges carried-forward facts (untouched files) with freshly-extracted facts (affected files) into a complete facts
     * object equivalent to a from-scratch {@link #extract(SemanticIndex, JavaProjectModel)} over the new revision (R05
     * incremental graph maintenance). For every map and list, prior entries DECLARED IN an affected/removed file are
     * dropped and replaced by the fresh entries; entries declared in untouched files are carried forward verbatim.
     *
     * @param prior         the previously-extracted full facts of the cached revision
     * @param fresh         facts extracted with the scoped overload over exactly {@code affectedFiles}
     * @param affectedFiles project-relative files whose facts {@code fresh} recomputed (touched + cross-file neighbors)
     * @param survivingFiles project-relative files that still exist in the new revision (removed files are pruned)
     */
    public static TransformationGraphFacts merge(TransformationGraphFacts prior, TransformationGraphFacts fresh,
            Set<String> affectedFiles, Set<String> survivingFiles) {
        Map<String, String> priorKeyToFile = prior.keyToRelativePath();
        Map<String, String> priorTypeFqnToFile = prior.typeFqnToRelativePath();

        // types / members: carry forward those declared in an untouched, surviving file; add every fresh entry.
        Map<String, TypeFact> typesByKey = new LinkedHashMap<>();
        for (TypeFact type : prior.types) {
            if (survivingFiles.contains(type.relativePath()) && !affectedFiles.contains(type.relativePath())) {
                typesByKey.put(type.canonicalKey(), type);
            }
        }
        for (TypeFact type : fresh.types) {
            typesByKey.put(type.canonicalKey(), type);
        }

        Map<String, MemberFact> membersByKey = new LinkedHashMap<>();
        for (MemberFact member : prior.members) {
            if (survivingFiles.contains(member.relativePath()) && !affectedFiles.contains(member.relativePath())) {
                membersByKey.put(member.canonicalKey(), member);
            }
        }
        for (MemberFact member : fresh.members) {
            membersByKey.put(member.canonicalKey(), member);
        }

        // supertypeFqns: key = child type FQN; carry forward children declared in untouched, surviving files.
        Map<String, Set<String>> supertypeFqns = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : prior.supertypeFqns.entrySet()) {
            String file = priorTypeFqnToFile.get(entry.getKey());
            if (file != null && survivingFiles.contains(file) && !affectedFiles.contains(file)) {
                supertypeFqns.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
        }
        for (Map.Entry<String, Set<String>> entry : fresh.supertypeFqns.entrySet()) {
            supertypeFqns.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }

        // call / constructor / method-reference edges: key = caller member key; carry forward untouched callers.
        Map<String, Set<String>> callEdges =
                mergeEdges(prior.callEdges, fresh.callEdges, priorKeyToFile, affectedFiles, survivingFiles);
        Map<String, Set<String>> constructorEdges =
                mergeEdges(prior.constructorEdges, fresh.constructorEdges, priorKeyToFile, affectedFiles, survivingFiles);
        Map<String, Set<String>> methodReferenceEdges = mergeEdges(prior.methodReferenceEdges,
                fresh.methodReferenceEdges, priorKeyToFile, affectedFiles, survivingFiles);

        // overrideGroups: value member keys are overriders; drop overriders declared in an affected/removed file from the
        // carried-forward groups, then union in the fresh groups. Empty groups are dropped.
        Map<String, Set<String>> overrideGroups = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : prior.overrideGroups.entrySet()) {
            Set<String> kept = new LinkedHashSet<>();
            for (String overrider : entry.getValue()) {
                String file = priorKeyToFile.get(overrider);
                if (file != null && survivingFiles.contains(file) && !affectedFiles.contains(file)) {
                    kept.add(overrider);
                }
            }
            if (!kept.isEmpty()) {
                overrideGroups.put(entry.getKey(), kept);
            }
        }
        for (Map.Entry<String, Set<String>> entry : fresh.overrideGroups.entrySet()) {
            overrideGroups.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).addAll(entry.getValue());
        }

        return new TransformationGraphFacts(
                new ArrayList<>(typesByKey.values()),
                new ArrayList<>(membersByKey.values()),
                supertypeFqns,
                callEdges,
                methodReferenceEdges,
                constructorEdges,
                overrideGroups);
    }

    private static Map<String, Set<String>> mergeEdges(Map<String, Set<String>> prior, Map<String, Set<String>> fresh,
            Map<String, String> priorKeyToFile, Set<String> affectedFiles, Set<String> survivingFiles) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : prior.entrySet()) {
            String file = priorKeyToFile.get(entry.getKey());
            if (file != null && survivingFiles.contains(file) && !affectedFiles.contains(file)) {
                merged.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
        }
        for (Map.Entry<String, Set<String>> entry : fresh.entrySet()) {
            merged.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        return merged;
    }

    private static void recordOverrides(SemanticIndex index, ExecutableElement method, String memberKey, Trees trees,
            Types types, CompilationUnitTree unit, Path file, Map<String, Set<String>> overrideGroups) {
        List<Element> group = index.overrideGroup(method);
        for (Element member : group) {
            if (member == method) {
                continue;
            }
            // Group under the supertype member's canonical key. overrideGroup returns the full group across the
            // hierarchy; we record the edge supertypeMemberKey -> overridingMemberKey for every other group member
            // whose owner is a supertype of this method's owner (best-effort using the group's own key).
            String otherKey = canonicalKey(member, trees, types, unit, file);
            if (otherKey == null || otherKey.isBlank() || otherKey.equals(memberKey)) {
                continue;
            }
            overrideGroups.computeIfAbsent(otherKey, k -> new LinkedHashSet<>()).add(memberKey);
        }
    }

    private static String canonicalKey(Element element, Trees trees, Types types, CompilationUnitTree unit, Path file) {
        if (unit != null && file != null) {
            return SemanticKey.from(element, trees, types, unit, file).canonical();
        }
        return SemanticKey.from(element).canonical();
    }

    private static String packageNameOf(TypeElement type) {
        Element enclosing = type.getEnclosingElement();
        while (enclosing != null && enclosing.getKind() != ElementKind.PACKAGE) {
            enclosing = enclosing.getEnclosingElement();
        }
        if (enclosing instanceof javax.lang.model.element.PackageElement pkg) {
            return pkg.getQualifiedName().toString();
        }
        return "";
    }

    private static String declaredTypeFqn(TypeMirror mirror) {
        if (mirror == null || mirror.getKind() != TypeKind.DECLARED) {
            return null;
        }
        Element element = ((DeclaredType) mirror).asElement();
        return element instanceof TypeElement type ? type.getQualifiedName().toString() : null;
    }

    private static String typeKindLabel(ElementKind kind) {
        return switch (kind) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            case ANNOTATION_TYPE -> "annotation";
            default -> "class";
        };
    }

    private static String memberKindLabel(ElementKind kind) {
        return switch (kind) {
            case METHOD -> "method";
            case CONSTRUCTOR -> "constructor";
            case FIELD, ENUM_CONSTANT -> "field";
            default -> null;
        };
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

    /**
     * Resolves call, constructor, and method-reference edges between registered declarations, attributing each to the
     * nearest enclosing member declaration. Mirrors {@link ReachabilityGraph}'s reference scanner but keeps the three
     * edge categories the plan's {@code CallGraph} distinguishes (method&rarr;called methods, method&rarr;constructors,
     * method&rarr;method references) separate.
     */
    private static final class CallEdgeScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Types types;
        private final CompilationUnitTree unit;
        private final Path file;
        private final Set<String> registeredKeys;
        private final Set<String> memberKeys;
        private final Map<String, Set<String>> callEdges;
        private final Map<String, Set<String>> methodReferenceEdges;
        private final Map<String, Set<String>> constructorEdges;

        CallEdgeScanner(Trees trees, Types types, CompilationUnitTree unit, Path file, Set<String> registeredKeys,
                Set<String> memberKeys, Map<String, Set<String>> callEdges,
                Map<String, Set<String>> methodReferenceEdges, Map<String, Set<String>> constructorEdges) {
            this.trees = trees;
            this.types = types;
            this.unit = unit;
            this.file = file;
            this.registeredKeys = registeredKeys;
            this.memberKeys = memberKeys;
            this.callEdges = callEdges;
            this.methodReferenceEdges = methodReferenceEdges;
            this.constructorEdges = constructorEdges;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            Element referenced = trees.getElement(getCurrentPath());
            if (isMethod(referenced)) {
                record(referenced, callEdges);
            }
            return super.visitMethodInvocation(tree, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            Element referenced = trees.getElement(getCurrentPath());
            if (isConstructor(referenced)) {
                record(referenced, constructorEdges);
            }
            return super.visitNewClass(tree, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
            Element referenced = trees.getElement(getCurrentPath());
            if (isMethod(referenced) || isConstructor(referenced)) {
                record(referenced, methodReferenceEdges);
            }
            return super.visitMemberReference(tree, unused);
        }

        private void record(Element referenced, Map<String, Set<String>> edges) {
            String enclosingKey = enclosingMemberKey();
            if (enclosingKey == null) {
                return;
            }
            String toKey = SemanticKey.from(referenced, trees, types, unit, file).canonical();
            if (toKey == null || toKey.equals(enclosingKey) || !memberKeys.contains(toKey)) {
                return;
            }
            edges.computeIfAbsent(enclosingKey, k -> new LinkedHashSet<>()).add(toKey);
        }

        private static boolean isMethod(Element element) {
            return element != null && element.getKind() == ElementKind.METHOD;
        }

        private static boolean isConstructor(Element element) {
            return element != null && element.getKind() == ElementKind.CONSTRUCTOR;
        }

        /** The nearest enclosing method/constructor/field declaration that is a registered member. */
        private String enclosingMemberKey() {
            TreePath path = getCurrentPath();
            while (path != null) {
                Tree leaf = path.getLeaf();
                if (leaf instanceof MethodTree || leaf instanceof ClassTree) {
                    Element element = trees.getElement(path);
                    if (element != null) {
                        ElementKind kind = element.getKind();
                        if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
                            String key = SemanticKey.from(element, trees, types, unit, file).canonical();
                            if (key != null && memberKeys.contains(key)) {
                                return key;
                            }
                        }
                    }
                }
                path = path.getParentPath();
            }
            return null;
        }
    }
}
