package io.serena.javarefactor.compiler;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
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
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler-backed semantic match primitive for the V3 API-migration recipe engine
 * (refactor-feature-plan-V3.md §14). Given a set of resolved {@link RecipeRule}s it walks the javac model of the whole
 * project ({@code Trees}/{@code Types}/{@code Elements} plus the parsed {@code units}) and returns, for each rule, the
 * exact source ranges that the rule applies to — never a textual/regex guess.
 *
 * <p><b>Correctness over coverage (§11/§14.3).</b> A rule only matches a node whose <em>resolved</em> element
 * (the invoked method, the constructed type, the referenced type/field/annotation) equals the rule's declared
 * owner+signature. When javac cannot resolve the element, the node is skipped — the engine never emits a "safe" edit it
 * cannot prove. Risk is carried verbatim from the rule (the recipe author's classification), with the floor that an
 * unresolved target is never matched at all and a rule with no {@code replacement} is emitted as a report-only finding
 * (no edit), so virtual-dispatch / behaviour-changing migrations surface as {@code needs_review} rather than silent
 * rewrites. The sidecar's before/after javac validation is the final backstop for everything this primitive emits.
 */
public final class RecipeMatchIndex {

    /** Risk classifications mirrored from §14.3. */
    public static final String RISK_SAFE = "safe";
    public static final String RISK_NEEDS_REVIEW = "needs_review";
    public static final String RISK_REFUSED = "refused";

    /**
     * A single resolved rule (one entry of a recipe's {@code rules} array, §14.1). {@code kind} is one of the §14.1 rule
     * kinds; the remaining fields are interpreted per kind (unused fields are {@code null}/empty):
     * <ul>
     *   <li>{@code replaceMethodCall} / {@code replaceStaticMethodCall}: {@code owner}=declaring FQN, {@code name}=method
     *       name, {@code paramTypes}=optional erased parameter types, {@code replacement}=template (may use
     *       {@code ${receiver}} / {@code ${argN}}); a {@code null} replacement = report-only.</li>
     *   <li>{@code replaceConstructor}: {@code owner}=constructed FQN, {@code paramTypes}=optional, {@code replacement}=
     *       template using {@code ${argN}}.</li>
     *   <li>{@code replaceType} / {@code replaceImport} / {@code replaceAnnotation}: {@code oldType}->{@code newType}
     *       FQN swap (applied to imports, qualified references and simple-name references that resolve to the type).</li>
     *   <li>{@code replaceFieldAccess}: {@code owner}=declaring FQN, {@code name}=field name, {@code replacement}=
     *       template using {@code ${receiver}}.</li>
     *   <li>{@code removeAnnotation}: {@code owner}=annotation FQN; the whole {@code @Owner(...)} is removed.</li>
     *   <li>{@code addAnnotation}: {@code owner}=declaring type FQN, {@code name}=optional member (method/field) to
     *       annotate (absent → annotate the type declaration), {@code paramTypes}=optional overload disambiguation,
     *       {@code newType}=annotation FQN to add, {@code replacement}=optional full annotation text override (default
     *       {@code @SimpleName}), {@code requiredImports}=optional (defaults to the annotation FQN unless it is in
     *       {@code java.lang}). Idempotent: a declaration that already carries the annotation is skipped.</li>
     * </ul>
     */
    public record RecipeRule(String id, String kind, String owner, String name, List<String> paramTypes,
                             String replacement, List<String> requiredImports, String oldType, String newType,
                             String risk) {
        public RecipeRule {
            paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
            requiredImports = requiredImports == null ? List.of() : List.copyOf(requiredImports);
        }
    }

    /** A single matched source range. {@code newText == null} marks a report-only finding (no edit emitted on apply). */
    public record RecipeMatch(Path file, int start, int end, String oldText, String newText, List<String> addImports,
                              String risk, String ruleKind, String ruleId, String detail) {
        public RecipeMatch {
            addImports = addImports == null ? List.of() : List.copyOf(addImports);
        }
    }

    private final SemanticIndex index;
    private final Trees trees;
    private final Types types;
    private final Elements elements;
    private final SourcePositions positions;
    private final List<CompilationUnitTree> units;

    public RecipeMatchIndex(SemanticIndex index) {
        this.index = index;
        this.trees = index.trees;
        this.types = index.types;
        this.elements = index.elements;
        this.positions = index.positions;
        this.units = index.units;
    }

    /** True if {@code fqn} resolves to a type on the project's compile classpath (used to flag unresolved recipe targets). */
    public boolean typeResolves(String fqn) {
        return fqn != null && !fqn.isBlank() && elements.getTypeElement(fqn) != null;
    }

    /** Result of {@link #resolveExecutableDeclaration}: located file/line, how many declarations matched, and whether the owner type resolved. */
    public record DeclarationSite(Path file, int line, int matchCount, boolean ownerResolved) {
        public boolean resolved() {
            return file != null && line > 0 && matchCount == 1;
        }
    }

    /**
     * Resolves the unique method/constructor declaration named by a {@code changeMethodSignature} recipe rule (F13):
     * {@code ownerFqn} + simple {@code name} + optional erased {@code paramTypes} for overload disambiguation. Returns
     * the declaration's source file and 1-based line so the recipe engine can drive the compiler-backed change-signature
     * operation at that position — never a textual guess. {@code matchCount} lets the caller distinguish "no such method"
     * (0) from "ambiguous, add paramTypes" (&gt;1), and {@code ownerResolved} separates an unresolved owner type from a
     * resolved owner with no matching member.
     */
    public DeclarationSite resolveExecutableDeclaration(String ownerFqn, String name, List<String> paramTypes) {
        TypeElement owner = ownerFqn == null || ownerFqn.isBlank() ? null : elements.getTypeElement(ownerFqn);
        if (owner == null) {
            return new DeclarationSite(null, -1, 0, false);
        }
        String ownerSimple = owner.getSimpleName().toString();
        List<ExecutableElement> candidates = new ArrayList<>();
        for (Element member : owner.getEnclosedElements()) {
            if (!(member instanceof ExecutableElement exec)) {
                continue;
            }
            boolean nameMatch;
            if (exec.getKind() == ElementKind.CONSTRUCTOR) {
                nameMatch = name != null && (name.equals("<init>") || name.equals(ownerSimple));
            } else if (exec.getKind() == ElementKind.METHOD) {
                nameMatch = name != null && exec.getSimpleName().contentEquals(name);
            } else {
                continue;
            }
            if (nameMatch && paramsMatch(exec, paramTypes == null ? List.of() : paramTypes)) {
                candidates.add(exec);
            }
        }
        if (candidates.size() != 1) {
            return new DeclarationSite(null, -1, candidates.size(), true);
        }
        TreePath path = trees.getPath(candidates.get(0));
        if (path == null) {
            return new DeclarationSite(null, -1, 1, true);
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        int start = startOf(unit, path.getLeaf());
        if (start < 0) {
            return new DeclarationSite(null, -1, 1, true);
        }
        int line = (int) unit.getLineMap().getLineNumber(start);
        return new DeclarationSite(SemanticIndex.pathOf(unit), line, 1, true);
    }

    /** 1-based line number of {@code offset} in {@code file}, or {@code -1} if it cannot be computed. */
    public int lineOf(Path file, int offset) {
        CompilationUnitTree unit = unitFor(file);
        if (unit == null || offset < 0) {
            return -1;
        }
        try {
            return (int) unit.getLineMap().getLineNumber(offset);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /** Scans the whole project for matches of {@code rules}; returns one {@link RecipeMatch} per applicable source range. */
    public List<RecipeMatch> scan(List<RecipeRule> rules) {
        // Bucket rules by kind for cheap per-node lookup.
        List<RecipeRule> methodCallRules = new ArrayList<>();
        List<RecipeRule> staticCallRules = new ArrayList<>();
        List<RecipeRule> ctorRules = new ArrayList<>();
        List<RecipeRule> fieldRules = new ArrayList<>();
        List<RecipeRule> addAnnoRules = new ArrayList<>();
        Map<String, RecipeRule> typeRules = new LinkedHashMap<>();
        Map<String, RecipeRule> removeAnnoRules = new LinkedHashMap<>();
        for (RecipeRule rule : rules) {
            switch (rule.kind()) {
                case "replaceMethodCall" -> methodCallRules.add(rule);
                case "replaceStaticMethodCall" -> staticCallRules.add(rule);
                case "replaceConstructor" -> ctorRules.add(rule);
                case "replaceFieldAccess" -> fieldRules.add(rule);
                case "addAnnotation" -> addAnnoRules.add(rule);
                case "replaceType", "replaceImport", "replaceAnnotation" -> typeRules.put(rule.oldType(), rule);
                case "removeAnnotation" -> removeAnnoRules.put(rule.owner(), rule);
                default -> { /* unsupported kinds are rejected by the parser; ignore defensively */ }
            }
        }

        List<RecipeMatch> matches = new ArrayList<>();
        for (CompilationUnitTree unit : units) {
            Path file = SemanticIndex.pathOf(unit);
            CharSequence source = index.sourceText(file);
            if (source == null) {
                continue;
            }
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    matchInvocation(unit, file, source, node, getCurrentPath(), methodCallRules, staticCallRules, matches);
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    matchConstructor(unit, file, source, node, getCurrentPath(), ctorRules, matches);
                    return super.visitNewClass(node, unused);
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                    matchTypeRef(unit, file, source, node, getCurrentPath(), typeRules, true, matches);
                    matchFieldAccess(unit, file, source, node, getCurrentPath(), fieldRules, matches);
                    return super.visitMemberSelect(node, unused);
                }

                @Override
                public Void visitIdentifier(IdentifierTree node, Void unused) {
                    matchTypeRef(unit, file, source, node, getCurrentPath(), typeRules, false, matches);
                    return super.visitIdentifier(node, unused);
                }

                @Override
                public Void visitAnnotation(AnnotationTree node, Void unused) {
                    matchRemoveAnnotation(unit, file, source, node, getCurrentPath(), removeAnnoRules, matches);
                    return super.visitAnnotation(node, unused);
                }

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    matchAddAnnotation(unit, file, source, node, getCurrentPath(), addAnnoRules, matches);
                    return super.visitClass(node, unused);
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    matchAddAnnotation(unit, file, source, node, getCurrentPath(), addAnnoRules, matches);
                    return super.visitMethod(node, unused);
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    matchAddAnnotation(unit, file, source, node, getCurrentPath(), addAnnoRules, matches);
                    return super.visitVariable(node, unused);
                }
            }.scan(unit, null);
        }
        return matches;
    }

    /**
     * Computes the import-insertion edit for {@code file} adding any of {@code imports} not already present, or returns
     * {@code null} if every import already exists. Used by the engine on apply to satisfy a rule's {@code requiredImports}.
     */
    public RecipeMatch importInsertion(Path file, List<String> imports) {
        CompilationUnitTree unit = unitFor(file);
        if (unit == null || imports.isEmpty()) {
            return null;
        }
        Set<String> existing = new LinkedHashSet<>();
        for (ImportTree imp : unit.getImports()) {
            existing.add(normalizeImport(imp.getQualifiedIdentifier().toString()));
        }
        List<String> missing = new ArrayList<>();
        for (String imp : imports) {
            String fqn = normalizeImport(imp);
            if (!fqn.isEmpty() && existing.add(fqn)) {
                missing.add(fqn);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        CharSequence source = index.sourceText(file);
        int offset = importInsertOffset(unit, source);
        StringBuilder text = new StringBuilder();
        for (String fqn : missing) {
            text.append('\n').append("import ").append(fqn).append(';');
        }
        return new RecipeMatch(file, offset, offset, "", text.toString(), List.of(),
                RISK_SAFE, "addImport", "_imports", "Add required imports: " + String.join(", ", missing));
    }

    /**
     * The "remove stale imports" step of the recipe engine (refactor-feature-plan-V3.md §14.2), symmetric to
     * {@link #importInsertion}: given a file and the body replacements a recipe applies to it (each {@code int[]} a
     * {@code {start, end}} half-open span replaced by the parallel entry of {@code replacementTexts}), it materializes the
     * post-edit source and asks {@link DanglingImports#staleSingleTypeImportSpans} which single-type imports are no longer
     * referenced afterwards. Each such import becomes a removal {@code RecipeMatch} ({@code newText == ""}) addressed in the
     * <em>original</em> source's coordinates, so it composes with the recipe's other edits. An import still referenced
     * anywhere in the post-edit unit is left untouched.
     *
     * @param starts            start offset of each body replacement, in original-source coordinates
     * @param ends              end offset of each body replacement (parallel to {@code starts})
     * @param replacementTexts  replacement text of each body replacement (parallel to {@code starts})
     */
    public List<RecipeMatch> staleImportRemovals(Path file, int[] starts, int[] ends, List<String> replacementTexts) {
        CharSequence original = index.sourceText(file);
        if (original == null) {
            return List.of();
        }
        String originalSource = original.toString();
        String postEdit = applyEdits(originalSource, starts, ends, replacementTexts);
        List<long[]> spans = DanglingImports.staleSingleTypeImportSpans(originalSource, postEdit);
        if (spans.isEmpty()) {
            return List.of();
        }
        List<RecipeMatch> removals = new ArrayList<>(spans.size());
        for (long[] span : spans) {
            int start = (int) span[0];
            int end = (int) span[1];
            removals.add(new RecipeMatch(file, start, end, slice(originalSource, start, end), "", List.of(),
                    RISK_SAFE, "removeImport", "_imports", "Remove stale import"));
        }
        return removals;
    }

    /**
     * Applies the {@code {start, end} -> replacement} edits to {@code source}, processing them right-to-left so earlier
     * offsets stay valid. The recipe engine has already dropped overlapping edits, so a plain descending-by-start splice
     * is sufficient and order-stable.
     */
    private static String applyEdits(String source, int[] starts, int[] ends, List<String> replacementTexts) {
        int n = starts.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(starts[b], starts[a]));
        StringBuilder buffer = new StringBuilder(source);
        for (int idx : order) {
            int start = starts[idx];
            int end = ends[idx];
            if (start < 0 || end < start || end > buffer.length()) {
                continue;
            }
            buffer.replace(start, end, replacementTexts.get(idx));
        }
        return buffer.toString();
    }

    // ── matchers ─────────────────────────────────────────────────────────────────────────────────────────────────

    private void matchInvocation(CompilationUnitTree unit, Path file, CharSequence source, MethodInvocationTree node,
                                 TreePath path, List<RecipeRule> instanceRules, List<RecipeRule> staticRules,
                                 List<RecipeMatch> out) {
        if (instanceRules.isEmpty() && staticRules.isEmpty()) {
            return;
        }
        ExecutableElement method = resolveExecutable(path, node.getMethodSelect());
        if (method == null || !(method.getEnclosingElement() instanceof TypeElement owner)) {
            return; // unresolved → never matched (correctness over coverage)
        }
        String ownerFqn = owner.getQualifiedName().toString();
        String name = method.getSimpleName().toString();
        boolean isStatic = method.getModifiers().contains(Modifier.STATIC);

        List<RecipeRule> candidates = isStatic ? staticRules : instanceRules;
        for (RecipeRule rule : candidates) {
            if (!ownerFqn.equals(rule.owner()) || !name.equals(rule.name()) || !paramsMatch(method, rule.paramTypes())) {
                continue;
            }
            int start = startOf(unit, node);
            int end = endOf(unit, node);
            if (start < 0 || end < start) {
                continue;
            }
            String oldText = slice(source, start, end);
            String newText = rule.replacement() == null ? null
                    : expand(rule.replacement(), receiverText(unit, source, node.getMethodSelect()), argTexts(unit, source, node.getArguments()));
            String risk = effectiveRisk(rule, newText);
            out.add(new RecipeMatch(file, start, end, oldText, newText, rule.requiredImports(), risk,
                    rule.kind(), rule.id(), ownerFqn + "." + name + (newText == null ? " (no safe replacement)" : "")));
        }
    }

    private void matchConstructor(CompilationUnitTree unit, Path file, CharSequence source, NewClassTree node,
                                  TreePath path, List<RecipeRule> ctorRules, List<RecipeMatch> out) {
        if (ctorRules.isEmpty()) {
            return;
        }
        Element el = trees.getElement(path);
        if (!(el instanceof ExecutableElement ctor) || !(ctor.getEnclosingElement() instanceof TypeElement owner)) {
            return;
        }
        String ownerFqn = owner.getQualifiedName().toString();
        for (RecipeRule rule : ctorRules) {
            if (!ownerFqn.equals(rule.owner()) || !paramsMatch(ctor, rule.paramTypes())) {
                continue;
            }
            int start = startOf(unit, node);
            int end = endOf(unit, node);
            if (start < 0 || end < start) {
                continue;
            }
            String oldText = slice(source, start, end);
            String newText = rule.replacement() == null ? null
                    : expand(rule.replacement(), null, argTexts(unit, source, node.getArguments()));
            String risk = effectiveRisk(rule, newText);
            out.add(new RecipeMatch(file, start, end, oldText, newText, rule.requiredImports(), risk,
                    rule.kind(), rule.id(), "new " + ownerFqn));
        }
    }

    private void matchTypeRef(CompilationUnitTree unit, Path file, CharSequence source, ExpressionTree node,
                              TreePath path, Map<String, RecipeRule> typeRules, boolean qualified, List<RecipeMatch> out) {
        if (typeRules.isEmpty()) {
            return;
        }
        Element el = trees.getElement(path);
        if (!(el instanceof TypeElement type)) {
            return;
        }
        String fqn = type.getQualifiedName().toString();
        RecipeRule rule = typeRules.get(fqn);
        if (rule == null) {
            return;
        }
        int start = startOf(unit, node);
        int end = endOf(unit, node);
        if (start < 0 || end < start) {
            return;
        }
        String newText;
        if (qualified) {
            // A qualified reference (or an import's qualified id) → swap the whole FQN.
            newText = rule.newType();
        } else {
            // A simple-name reference resolving to the type → only rewrite if the simple name actually changes; an
            // unchanged simple name is covered by the import rewrite, so emitting an edit here would be a no-op overlap.
            String oldSimple = simpleName(rule.oldType());
            String newSimple = simpleName(rule.newType());
            if (oldSimple.equals(newSimple)) {
                return;
            }
            newText = newSimple;
        }
        out.add(new RecipeMatch(file, start, end, slice(source, start, end), newText, List.of(),
                effectiveRisk(rule, newText), rule.kind(), rule.id(), rule.oldType() + " -> " + rule.newType()));
    }

    private void matchFieldAccess(CompilationUnitTree unit, Path file, CharSequence source, MemberSelectTree node,
                                  TreePath path, List<RecipeRule> fieldRules, List<RecipeMatch> out) {
        if (fieldRules.isEmpty()) {
            return;
        }
        Element el = trees.getElement(path);
        if (!(el instanceof VariableElement field) || !(field.getEnclosingElement() instanceof TypeElement owner)) {
            return;
        }
        String ownerFqn = owner.getQualifiedName().toString();
        String name = field.getSimpleName().toString();
        for (RecipeRule rule : fieldRules) {
            if (!ownerFqn.equals(rule.owner()) || !name.equals(rule.name())) {
                continue;
            }
            int start = startOf(unit, node);
            int end = endOf(unit, node);
            if (start < 0 || end < start) {
                continue;
            }
            String newText = rule.replacement() == null ? null
                    : expand(rule.replacement(), receiverText(unit, source, node), List.of());
            out.add(new RecipeMatch(file, start, end, slice(source, start, end), newText, rule.requiredImports(),
                    effectiveRisk(rule, newText), rule.kind(), rule.id(), ownerFqn + "." + name));
        }
    }

    private void matchRemoveAnnotation(CompilationUnitTree unit, Path file, CharSequence source, AnnotationTree node,
                                       TreePath path, Map<String, RecipeRule> removeRules, List<RecipeMatch> out) {
        if (removeRules.isEmpty()) {
            return;
        }
        Element el = trees.getElement(new TreePath(path, node.getAnnotationType()));
        if (!(el instanceof TypeElement type)) {
            return;
        }
        RecipeRule rule = removeRules.get(type.getQualifiedName().toString());
        if (rule == null) {
            return;
        }
        int start = startOf(unit, node);
        int end = endOf(unit, node);
        if (start < 0 || end < start) {
            return;
        }
        // Extend the removal to trailing whitespace/newline so no blank residue is left on the declaration line.
        while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) {
            end++;
        }
        if (end < source.length() && source.charAt(end) == '\n') {
            end++;
        }
        out.add(new RecipeMatch(file, start, end, slice(source, start, end), "", List.of(),
                effectiveRisk(rule, ""), rule.kind(), rule.id(), "remove @" + type.getQualifiedName()));
    }

    private void matchAddAnnotation(CompilationUnitTree unit, Path file, CharSequence source, Tree decl,
                                    TreePath path, List<RecipeRule> addRules, List<RecipeMatch> out) {
        if (addRules.isEmpty()) {
            return;
        }
        Element el = trees.getElement(path);
        if (el == null) {
            return;
        }
        // Resolve the declaration's owner FQN and (for members) its simple name. Only type/method/field declarations
        // are annotatable here; local variables, parameters and enum constants are skipped (kind guard below).
        String ownerFqn;
        String memberName;
        boolean isType;
        if (el instanceof TypeElement type) {
            ownerFqn = type.getQualifiedName().toString();
            memberName = null;
            isType = true;
        } else if (el instanceof ExecutableElement method
                && method.getEnclosingElement() instanceof TypeElement owner) {
            ownerFqn = owner.getQualifiedName().toString();
            memberName = method.getSimpleName().toString();
            isType = false;
        } else if (el instanceof VariableElement field && field.getKind() == ElementKind.FIELD
                && field.getEnclosingElement() instanceof TypeElement owner) {
            ownerFqn = owner.getQualifiedName().toString();
            memberName = field.getSimpleName().toString();
            isType = false;
        } else {
            return;
        }

        for (RecipeRule rule : addRules) {
            if (!ownerFqn.equals(rule.owner())) {
                continue;
            }
            boolean typeLevel = rule.name() == null || rule.name().isBlank();
            if (typeLevel) {
                if (!isType) {
                    continue; // a type-level rule annotates only the type declaration
                }
            } else {
                if (isType || !rule.name().equals(memberName)) {
                    continue;
                }
                if (el instanceof ExecutableElement m && !paramsMatch(m, rule.paramTypes())) {
                    continue;
                }
            }
            if (hasAnnotation(el, rule.newType())) {
                continue; // idempotent: already annotated
            }
            int declStart = startOf(unit, decl);
            if (declStart < 0) {
                continue;
            }
            String indent = lineIndent(source, declStart);
            String annoText = rule.replacement() != null ? rule.replacement() : "@" + simpleName(rule.newType());
            String newText = annoText + "\n" + indent;
            out.add(new RecipeMatch(file, declStart, declStart, "", newText, annotationImports(rule),
                    effectiveRisk(rule, newText), rule.kind(), rule.id(),
                    "add " + annoText + " to " + ownerFqn + (memberName == null ? "" : "#" + memberName)));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private boolean hasAnnotation(Element element, String annotationFqn) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Element annotationType = mirror.getAnnotationType().asElement();
            if (annotationType instanceof TypeElement te && te.getQualifiedName().contentEquals(annotationFqn)) {
                return true;
            }
        }
        return false;
    }

    /** Leading whitespace (indent) of the line that {@code offset} starts; used to align an inserted annotation. */
    private String lineIndent(CharSequence source, int offset) {
        if (source == null) {
            return "";
        }
        int lineStart = Math.min(offset, source.length());
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < offset && i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == ' ' || c == '\t') {
                indent.append(c);
            } else {
                break;
            }
        }
        return indent.toString();
    }

    /**
     * Imports an {@code addAnnotation} rule needs: the author's {@code requiredImports} verbatim if given; otherwise the
     * annotation FQN itself when the default simple-name text is used and the annotation is not in {@code java.lang}
     * (which is auto-imported). An explicit {@code replacement} text leaves import management to the author.
     */
    private List<String> annotationImports(RecipeRule rule) {
        if (!rule.requiredImports().isEmpty()) {
            return rule.requiredImports();
        }
        String fqn = rule.newType();
        if (rule.replacement() != null || fqn == null || fqn.isBlank() || !fqn.contains(".")) {
            return List.of();
        }
        String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
        return pkg.equals("java.lang") ? List.of() : List.of(fqn);
    }

    private ExecutableElement resolveExecutable(TreePath invPath, ExpressionTree methodSelect) {
        Element el = trees.getElement(invPath);
        if (el instanceof ExecutableElement m) {
            return m;
        }
        if (methodSelect != null) {
            el = trees.getElement(new TreePath(invPath, methodSelect));
            if (el instanceof ExecutableElement m) {
                return m;
            }
        }
        return null;
    }

    private boolean paramsMatch(ExecutableElement method, List<String> ruleParamTypes) {
        if (ruleParamTypes.isEmpty()) {
            return true; // no declared signature → match by owner+name (every overload)
        }
        List<? extends VariableElement> params = method.getParameters();
        if (params.size() != ruleParamTypes.size()) {
            return false;
        }
        for (int i = 0; i < params.size(); i++) {
            String actual = types.erasure(params.get(i).asType()).toString();
            String expected = ruleParamTypes.get(i);
            if (!actual.equals(expected) && !simpleName(actual).equals(simpleName(expected))) {
                return false;
            }
        }
        return true;
    }

    private String effectiveRisk(RecipeRule rule, String newText) {
        if (newText == null) {
            return RISK_NEEDS_REVIEW; // report-only finding: a human must decide the replacement
        }
        String declared = rule.risk();
        if (RISK_SAFE.equals(declared) || RISK_NEEDS_REVIEW.equals(declared) || RISK_REFUSED.equals(declared)) {
            return declared;
        }
        // No author classification → be conservative: pure type/import swaps are mechanical (safe); call/ctor/field
        // rewrites can change nullability/exception/overload behaviour, so default to needs_review (§14.3).
        return switch (rule.kind()) {
            case "replaceType", "replaceImport", "replaceAnnotation", "removeAnnotation" -> RISK_SAFE;
            default -> RISK_NEEDS_REVIEW;
        };
    }

    private String receiverText(CompilationUnitTree unit, CharSequence source, ExpressionTree expr) {
        if (expr instanceof MemberSelectTree select) {
            return slice(source, startOf(unit, select.getExpression()), endOf(unit, select.getExpression()));
        }
        return null;
    }

    private List<String> argTexts(CompilationUnitTree unit, CharSequence source, List<? extends ExpressionTree> args) {
        List<String> out = new ArrayList<>(args.size());
        for (ExpressionTree arg : args) {
            out.add(slice(source, startOf(unit, arg), endOf(unit, arg)));
        }
        return out;
    }

    private static String expand(String template, String receiver, List<String> args) {
        String out = template.replace("${receiver}", receiver == null ? "" : receiver);
        for (int i = 0; i < args.size(); i++) {
            out = out.replace("${arg" + i + "}", args.get(i));
        }
        return out;
    }

    private int importInsertOffset(CompilationUnitTree unit, CharSequence source) {
        List<? extends ImportTree> imports = unit.getImports();
        if (!imports.isEmpty()) {
            return Math.max(0, endOf(unit, imports.get(imports.size() - 1)));
        }
        if (unit.getPackageName() != null) {
            int pkgEnd = endOf(unit, unit.getPackageName());
            if (pkgEnd >= 0 && source != null) {
                int semi = indexOf(source, ';', pkgEnd);
                if (semi >= 0) {
                    return semi + 1;
                }
            }
        }
        return 0;
    }

    private static int indexOf(CharSequence source, char ch, int from) {
        for (int i = Math.max(0, from); i < source.length(); i++) {
            if (source.charAt(i) == ch) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeImport(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String simpleName(String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private String slice(CharSequence source, int start, int end) {
        if (source == null || start < 0 || end < start || end > source.length()) {
            return "";
        }
        return source.subSequence(start, end).toString();
    }

    private int startOf(CompilationUnitTree unit, Tree tree) {
        return (int) positions.getStartPosition(unit, tree);
    }

    private int endOf(CompilationUnitTree unit, Tree tree) {
        return (int) positions.getEndPosition(unit, tree);
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
}
