package io.serena.javarefactor.shared;

import io.serena.javarefactor.edits.PlannerSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

/** Shared formatting-preserving import rewrite planner for conservative V2 source edits. */
public final class ImportRewritePlanner {
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT_STATEMENT =
            Pattern.compile("(?m)^[ \\t]*import[ \\t]+(static[ \\t]+)?([\\w.*]+)[ \\t]*;[ \\t]*\\r?\\n?");
    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void");
    /** Dotted-or-simple identifier tokens; annotation markers ({@code @}) are not captured. */
    private static final Pattern TYPE_TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    /** Java keywords that may appear inside a type expression but never name an importable type. */
    private static final Set<String> TYPE_EXPRESSION_KEYWORDS = Set.of(
            "extends", "super", "throws", "class", "interface", "enum", "record", "var", "final",
            "true", "false", "null", "instanceof", "new", "return");
    /** Recursion guard for {@link TypeMirror} traversal of pathological recursive generics. */
    private static final int MAX_MIRROR_DEPTH = 64;

    private final String source;
    private final String currentPackage;
    private final LinkedHashSet<String> plannedImports = new LinkedHashSet<>();
    private final List<ImportStmt> imports;
    /** Optional compiler-backed collision oracle; {@code null} means text-only conflict detection. */
    private SimpleNameConflictResolver conflictResolver;

    public ImportRewritePlanner(String source) {
        this.source = source == null ? "" : source;
        this.currentPackage = packageName(this.source);
        this.imports = parseImports(this.source);
    }

    /**
     * Resolves whether a referenced simple name collides with a type visible in the edited file's
     * scope beyond the file's own single-type imports — namely a type in the SAME package as the
     * edited file, or another project type already visible/imported under that simple name. This is
     * the javac/{@code SemanticIndex}-backed extension to the conservative text-only conflict check:
     * when it reports a collision, the reference is left fully qualified (and
     * {@link TypeUse#conflict()} is set) rather than imported under an ambiguous simple name.
     */
    @FunctionalInterface
    public interface SimpleNameConflictResolver {
        /**
         * @param simpleName the unqualified name the reference would be rendered as
         * @param candidateFqn the fully-qualified type the reference actually denotes
         * @return {@code true} when a <em>different</em> same-package or visible project type already
         *     claims {@code simpleName}, so importing/simplifying {@code candidateFqn} would be ambiguous
         */
        boolean conflictsForSimpleName(String simpleName, String candidateFqn);
    }

    /**
     * Installs a compiler-backed {@link SimpleNameConflictResolver} that augments the conservative
     * text-only conflict check with same-package and visible-project-type collision facts. Passing
     * {@code null} restores pure text-based detection. Returns {@code this} for fluent use at call
     * sites. The resolver never overrides an existing-import collision (that already leaves the
     * reference fully qualified); it only adds collisions the text scan cannot see.
     */
    public ImportRewritePlanner withConflictResolver(SimpleNameConflictResolver resolver) {
        this.conflictResolver = resolver;
        return this;
    }

    /**
     * Plans a type reference and any import edit needed to make the rendered reference compile safely.
     *
     * <p>This considers only the <em>outer</em> raw type (generic arguments are left fully qualified).
     * For comprehensive nested rewriting (generic arguments, array/varargs components, wildcard bounds,
     * and annotation types) use {@link #planTypeUsageDeep(Path, String, String)}.
     */
    public TypeUse planTypeUsage(Path file, String type, String kind) {
        if (type == null) {
            return typeUse("void", Optional.empty(), List.of(), false, false);
        }
        String importableName = importableName(type);
        if (importableName == null) {
            return typeUse(stripJavaLang(type), Optional.empty(), List.of(), false, false);
        }
        PlannedType planned = planImportableType(file, importableName, kind);
        if (planned.conflict()) {
            return typeUse(type, Optional.empty(), List.of(), true, true);
        }
        String rendered = planned.simplify() ? replaceQualifiedType(type, importableName, simpleName(importableName)) : type;
        List<PlannerSupport.TextEdit> edits = planned.edit().map(List::of).orElseGet(List::of);
        return typeUse(rendered, planned.edit(), edits, false, false);
    }

    /**
     * Plans a type reference and every import edit needed by the <em>whole</em> type expression,
     * walking nested generic arguments, array/varargs components, wildcard bounds, intersection
     * bounds, and annotation types. Every importable (dotted) referenced type is simplified in the
     * rendered output and, where required, gets its own import edit. Types whose simple name would
     * collide with an existing single-type import are left fully qualified (and {@link TypeUse#conflict()}
     * is set), exactly mirroring the conservative single-type contract.
     *
     * <p>The returned {@link TypeUse#importEdits()} lists all required imports; {@link TypeUse#importEdit()}
     * returns the first of them for callers that only consume a single edit.
     */
    public TypeUse planTypeUsageDeep(Path file, String type, String kind) {
        if (type == null) {
            return typeUse("void", Optional.empty(), List.of(), false, false);
        }
        // Replace longest qualified names first so a prefix FQN cannot corrupt an already-simplified one.
        List<String> importable = new ArrayList<>(importableReferencedNames(type));
        importable.sort((left, right) -> Integer.compare(right.length(), left.length()));
        String rendered = type;
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        boolean conflict = false;
        for (String importableName : importable) {
            PlannedType planned = planImportableType(file, importableName, kind);
            if (planned.conflict()) {
                conflict = true;
                continue;
            }
            if (planned.simplify()) {
                rendered = replaceQualifiedType(rendered, importableName, simpleName(importableName));
            }
            planned.edit().ifPresent(edits::add);
        }
        Optional<PlannerSupport.TextEdit> primary = edits.isEmpty() ? Optional.empty() : Optional.of(edits.get(0));
        return typeUse(rendered, primary, List.copyOf(edits), conflict, conflict);
    }

    /**
     * Plans the import edits required to reference {@code qualifiedTypeNames} (for example, the
     * fully-qualified types a moved or inlined body depends on). Non-importable entries (primitives,
     * blanks, simple names) and types already covered by an import, the same package, or
     * {@code java.lang} are skipped; conflicting simple names yield no edit so the caller can render
     * them fully qualified. State is shared with the per-type planners, so repeated calls deduplicate.
     *
     * <p>This is the stable entry point for the Phase-2 move and inline planners, which resolve the
     * dependency set from javac {@code Element}/{@code TypeMirror} facts (see
     * {@link #collectReferencedTypeNames(TypeMirror)}) and then ask for the matching imports here.
     */
    public List<PlannerSupport.TextEdit> planTypesForBody(Path file, Collection<String> qualifiedTypeNames, String kind) {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        if (qualifiedTypeNames == null) {
            return edits;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String name : qualifiedTypeNames) {
            String importableName = importableName(name == null ? "" : name);
            if (importableName != null) {
                unique.add(importableName);
            }
        }
        for (String importableName : unique) {
            planImportableType(file, importableName, kind).edit().ifPresent(edits::add);
        }
        return edits;
    }

    /**
     * Enumerates every type-name token referenced by a textual type expression, including the outer
     * type, nested generic arguments (recursively), array/varargs components, wildcard and
     * intersection bounds, thrown types, and annotation type names. Both simple and qualified names
     * are returned in encounter order; keywords and primitives are excluded. This is the text
     * fallback used when javac {@link TypeMirror} facts are not available.
     */
    public static Set<String> collectReferencedTypeNames(String typeExpression) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (typeExpression == null) {
            return names;
        }
        Matcher matcher = TYPE_TOKEN.matcher(typeExpression);
        while (matcher.find()) {
            String token = matcher.group();
            String simple = simpleName(token);
            if (TYPE_EXPRESSION_KEYWORDS.contains(token) || PRIMITIVE_TYPES.contains(token) || PRIMITIVE_TYPES.contains(simple)) {
                continue;
            }
            names.add(token);
        }
        return names;
    }

    /**
     * Enumerates the fully-qualified names of every declared type referenced by a {@link TypeMirror},
     * descending through generic arguments, array components, wildcard bounds, and intersection
     * bounds. Type variables are skipped (they are not importable). This is the preferred,
     * compiler-backed source of moved-body dependencies for the Phase-2 move and inline planners.
     */
    public static Set<String> collectReferencedTypeNames(TypeMirror type) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collectMirrorTypeNames(type, names, 0);
        return names;
    }

    private static void collectMirrorTypeNames(TypeMirror type, Set<String> out, int depth) {
        if (type == null || depth > MAX_MIRROR_DEPTH) {
            return;
        }
        if (type instanceof DeclaredType declared) {
            Element element = declared.asElement();
            if (element instanceof TypeElement typeElement) {
                out.add(typeElement.getQualifiedName().toString());
            }
            for (TypeMirror argument : declared.getTypeArguments()) {
                collectMirrorTypeNames(argument, out, depth + 1);
            }
        } else if (type instanceof ArrayType arrayType) {
            collectMirrorTypeNames(arrayType.getComponentType(), out, depth + 1);
        } else if (type instanceof WildcardType wildcardType) {
            collectMirrorTypeNames(wildcardType.getExtendsBound(), out, depth + 1);
            collectMirrorTypeNames(wildcardType.getSuperBound(), out, depth + 1);
        } else if (type instanceof IntersectionType intersectionType) {
            for (TypeMirror bound : intersectionType.getBounds()) {
                collectMirrorTypeNames(bound, out, depth + 1);
            }
        }
    }

    /** The distinct importable (dotted) type names referenced anywhere in a textual type expression. */
    private Set<String> importableReferencedNames(String typeExpression) {
        LinkedHashSet<String> importable = new LinkedHashSet<>();
        for (String token : collectReferencedTypeNames(typeExpression)) {
            String importableName = importableName(token);
            if (importableName != null) {
                importable.add(importableName);
            }
        }
        return importable;
    }

    /** The shared per-type decision used by every planning entry point. */
    private PlannedType planImportableType(Path file, String importableName, String kind) {
        if (samePackage(importableName) || importableName.startsWith("java.lang.")) {
            return new PlannedType(true, Optional.empty(), false);
        }
        if (hasSingleTypeConflict(importableName)) {
            return new PlannedType(false, Optional.empty(), true);
        }
        if (conflictResolver != null
                && !hasSingleTypeImport(importableName)
                && conflictResolver.conflictsForSimpleName(simpleName(importableName), importableName)) {
            // A same-package type or another visible project type already claims this simple name; the
            // compiler-backed oracle sees collisions the text scan cannot, so leave the reference FQN.
            return new PlannedType(false, Optional.empty(), true);
        }
        if (hasSingleTypeImport(importableName) || hasWildcardImportFor(importableName)) {
            return new PlannedType(true, Optional.empty(), false);
        }
        if (plannedImports.contains(importableName)) {
            return new PlannedType(true, Optional.empty(), false);
        }
        plannedImports.add(importableName);
        return new PlannedType(true, planImportEdit(file, importableName, kind), false);
    }

    private static TypeUse typeUse(
            String renderedType,
            Optional<PlannerSupport.TextEdit> importEdit,
            List<PlannerSupport.TextEdit> importEdits,
            boolean fullyQualified,
            boolean conflict) {
        return new TypeUse(renderedType, importEdit, importEdits, fullyQualified, conflict);
    }

    /** Plans a deterministic import insertion for {@code qualifiedName}, if it is not already covered. */
    public Optional<PlannerSupport.TextEdit> planImportEdit(Path file, String qualifiedName, String kind) {
        if (qualifiedName == null || qualifiedName.isBlank() || samePackage(qualifiedName) || qualifiedName.startsWith("java.lang.")) {
            return Optional.empty();
        }
        if (hasSingleTypeImport(qualifiedName) || hasWildcardImportFor(qualifiedName)) {
            return Optional.empty();
        }
        return computeImportInsertion(source, qualifiedName)
                .map(insertion -> new PlannerSupport.TextEdit(file, insertion.offset(), insertion.offset(), insertion.text(), kind));
    }

    /** Plans removal of an exact stale non-static single-type import while preserving wildcard and static imports. */
    public Optional<PlannerSupport.TextEdit> planStaleImportRemoval(Path file, String qualifiedName, String kind) {
        for (ImportStmt existing : imports) {
            if (!existing.isStatic() && !existing.name().endsWith(".*") && existing.name().equals(qualifiedName)) {
                return Optional.of(new PlannerSupport.TextEdit(file, existing.lineStart(), existing.lineEnd(), "", kind));
            }
        }
        return Optional.empty();
    }

    /** Computes a deterministic import insertion without mutating any planner state. */
    public static Optional<ImportInsertion> computeImportInsertion(String source, String newFqn) {
        String safeSource = source == null ? "" : source;
        List<ImportStmt> imports = parseImports(safeSource);
        for (ImportStmt existing : imports) {
            if (!existing.isStatic() && (existing.name().equals(newFqn) || wildcardCovers(existing.name(), newFqn))) {
                return Optional.empty();
            }
        }

        List<ImportStmt> regular = imports.stream().filter(stmt -> !stmt.isStatic()).toList();
        String lineSeparator = safeSource.contains("\r\n") ? "\r\n" : "\n";
        if (regular.isEmpty()) {
            Optional<ImportStmt> firstImport = imports.stream().findFirst();
            if (firstImport.isPresent()) {
                return Optional.of(new ImportInsertion(firstImport.get().lineStart(), "import " + newFqn + ";" + lineSeparator + lineSeparator));
            }
            int[] packageRange = packageDeclarationRange(safeSource);
            if (packageRange == null) {
                return Optional.of(new ImportInsertion(0, "import " + newFqn + ";" + lineSeparator + lineSeparator));
            }
            return Optional.of(new ImportInsertion(packageRange[1], lineSeparator + lineSeparator + "import " + newFqn + ";"));
        }

        // G004: the default placement below assumes the conventional java/javax/other ascending layout. When the file's
        // existing import block contradicts that (a different group order, a single ungrouped block, or descending
        // order), insert per the layout actually inferred from the file rather than imposing the hard-coded policy.
        if (!isCanonicalRegularLayout(regular)) {
            return Optional.of(inferredInsertion(safeSource, regular, newFqn, lineSeparator));
        }

        int newGroup = importGroup(newFqn);
        ImportStmt after = null;
        ImportStmt before = null;
        for (ImportStmt stmt : regular) {
            int cmp = compareImport(stmt, newGroup, newFqn);
            if (cmp < 0) {
                after = stmt;
            } else if (cmp > 0) {
                before = stmt;
                break;
            }
        }
        if (after != null) {
            boolean blankBefore = importGroup(after.name()) != newGroup;
            return Optional.of(new ImportInsertion(after.lineEnd(), (blankBefore ? lineSeparator : "") + "import " + newFqn + ";" + lineSeparator));
        }
        ImportStmt first = before == null ? regular.get(0) : before;
        boolean blankAfter = importGroup(first.name()) != newGroup;
        return Optional.of(new ImportInsertion(first.lineStart(), "import " + newFqn + ";" + lineSeparator + (blankAfter ? lineSeparator : "")));
    }

    /**
     * Computes a deterministic insertion for a {@code import static qualifiedMember;} line, or empty
     * when the member is already statically imported (single or covering wildcard). The static line
     * is appended after the last existing static import; failing that, placed just before the first
     * regular import; failing that, after the package declaration. This is the placement counterpart
     * to {@link #computeImportInsertion(String, String)} for static-import transplants (G012/G017).
     */
    public static Optional<ImportInsertion> computeStaticImportInsertion(String source, String qualifiedMember) {
        String safeSource = source == null ? "" : source;
        if (qualifiedMember == null || qualifiedMember.isBlank() || !qualifiedMember.contains(".")) {
            return Optional.empty();
        }
        List<ImportStmt> imports = parseImports(safeSource);
        String memberType = packagePart(qualifiedMember);
        for (ImportStmt existing : imports) {
            if (existing.isStatic()
                    && (existing.name().equals(qualifiedMember)
                            || (existing.name().endsWith(".*")
                                    && existing.name().substring(0, existing.name().length() - 2).equals(memberType)))) {
                return Optional.empty();
            }
        }
        String lineSeparator = safeSource.contains("\r\n") ? "\r\n" : "\n";
        String line = "import static " + qualifiedMember + ";";
        ImportStmt lastStatic = null;
        for (ImportStmt stmt : imports) {
            if (stmt.isStatic()) {
                lastStatic = stmt;
            }
        }
        if (lastStatic != null) {
            return Optional.of(new ImportInsertion(lastStatic.lineEnd(), line + lineSeparator));
        }
        Optional<ImportStmt> firstImport = imports.stream().findFirst();
        if (firstImport.isPresent()) {
            return Optional.of(new ImportInsertion(firstImport.get().lineStart(), line + lineSeparator + lineSeparator));
        }
        int[] packageRange = packageDeclarationRange(safeSource);
        if (packageRange == null) {
            return Optional.of(new ImportInsertion(0, line + lineSeparator + lineSeparator));
        }
        return Optional.of(new ImportInsertion(packageRange[1], lineSeparator + lineSeparator + line));
    }

    /**
     * The outcome of planning a type reference.
     *
     * @param renderedType the reference rewritten to use simple names where safe
     * @param importEdit the first required import edit, for callers consuming a single edit (backward compatible)
     * @param importEdits every required import edit (one per newly imported nested type); a superset of {@link #importEdit()}
     * @param fullyQualified whether the reference had to be left fully qualified
     * @param conflict whether a referenced simple name collided with an existing single-type import
     */
    public record TypeUse(
            String renderedType,
            Optional<PlannerSupport.TextEdit> importEdit,
            List<PlannerSupport.TextEdit> importEdits,
            boolean fullyQualified,
            boolean conflict) {
    }

    public record ImportInsertion(int offset, String text) {
    }

    /** Internal per-type planning decision shared by the public planning entry points. */
    private record PlannedType(boolean simplify, Optional<PlannerSupport.TextEdit> edit, boolean conflict) {
    }

    private record ImportStmt(int lineStart, int lineEnd, boolean isStatic, String name) {
    }

    private static List<ImportStmt> parseImports(String source) {
        List<ImportStmt> parsed = new ArrayList<>();
        Matcher matcher = IMPORT_STATEMENT.matcher(source);
        while (matcher.find()) {
            parsed.add(new ImportStmt(matcher.start(), matcher.end(), matcher.group(1) != null, matcher.group(2)));
        }
        return parsed;
    }

    private boolean hasSingleTypeImport(String qualifiedName) {
        return imports.stream().anyMatch(importStmt -> !importStmt.isStatic() && importStmt.name().equals(qualifiedName));
    }

    private boolean hasWildcardImportFor(String qualifiedName) {
        return imports.stream().anyMatch(importStmt -> !importStmt.isStatic() && wildcardCovers(importStmt.name(), qualifiedName));
    }

    private boolean hasSingleTypeConflict(String qualifiedName) {
        String simpleName = simpleName(qualifiedName);
        return imports.stream()
                .anyMatch(importStmt -> !importStmt.isStatic()
                        && !importStmt.name().endsWith(".*")
                        && !importStmt.name().equals(qualifiedName)
                        && simpleName(importStmt.name()).equals(simpleName));
    }

    private boolean samePackage(String qualifiedName) {
        String typePackage = packagePart(qualifiedName);
        return !typePackage.isBlank() && typePackage.equals(currentPackage);
    }

    private static boolean wildcardCovers(String importName, String qualifiedName) {
        return importName.endsWith(".*") && importName.substring(0, importName.length() - 2).equals(packagePart(qualifiedName));
    }

    private static String stripJavaLang(String type) {
        String importableName = importableName(type);
        if (importableName != null && importableName.startsWith("java.lang.")) {
            return replaceQualifiedType(type, importableName, simpleName(importableName));
        }
        return type;
    }

    private static String importableName(String type) {
        String normalized = type.strip();
        if (normalized.isEmpty() || PRIMITIVE_TYPES.contains(normalized)) {
            return null;
        }
        while (normalized.endsWith("[]")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        if (normalized.endsWith("...")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        int genericStart = normalized.indexOf('<');
        if (genericStart >= 0) {
            normalized = normalized.substring(0, genericStart);
        }
        if (!normalized.contains(".")) {
            return null;
        }
        return normalized;
    }

    private static String replaceQualifiedType(String type, String qualifiedName, String replacement) {
        return type.replace(qualifiedName, replacement);
    }

    private static String packageName(String source) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int[] packageDeclarationRange(String source) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(source);
        if (matcher.find()) {
            return new int[] {matcher.start(), matcher.end()};
        }
        return null;
    }

    private static String packagePart(String qualifiedName) {
        String normalized = qualifiedName.endsWith(".*") ? qualifiedName.substring(0, qualifiedName.length() - 2) : qualifiedName;
        int lastDot = normalized.lastIndexOf('.');
        return lastDot < 0 ? "" : normalized.substring(0, lastDot);
    }

    private static String simpleName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    /** Group ordering: {@code java.*}, then {@code javax.*}, then all other imports. */
    private static int importGroup(String name) {
        if (name.startsWith("java.")) {
            return 0;
        }
        if (name.startsWith("javax.")) {
            return 1;
        }
        return 2;
    }

    /** Negative if {@code stmt} sorts before the new import, else positive. */
    private static int compareImport(ImportStmt stmt, int newGroup, String newFqn) {
        int group = Integer.compare(importGroup(stmt.name()), newGroup);
        if (group != 0) {
            return group;
        }
        return stmt.name().compareTo(newFqn);
    }

    /**
     * True when the regular imports are already in the conventional {@code java.*}/{@code javax.*}/other ascending
     * order. In that case {@link #computeImportInsertion(String, String)} keeps its default placement (and the
     * blank-line-between-groups policy), which exactly reproduces the canonical layout. When false, the file's block
     * contradicts that policy and placement is inferred from the file instead (G004).
     */
    private static boolean isCanonicalRegularLayout(List<ImportStmt> regular) {
        for (int i = 1; i < regular.size(); i++) {
            if (canonicalCompare(regular.get(i - 1).name(), regular.get(i).name()) > 0) {
                return false;
            }
        }
        return true;
    }

    private static int canonicalCompare(String left, String right) {
        int group = Integer.compare(importGroup(left), importGroup(right));
        return group != 0 ? group : left.compareTo(right);
    }

    /**
     * Inserts {@code newFqn} according to the layout inferred from the file's existing regular import block: its group
     * boundaries (runs separated by a blank line), its ordering direction (ascending or descending), and its actual
     * group order (e.g. {@code java.*} placed last, or a single ungrouped block) rather than the hard-coded
     * {@code java}/{@code javax}/other policy. The new import joins the existing group sharing the longest package
     * prefix with it (preserving that group's direction and not introducing a blank-line split); when no group shares
     * any prefix it starts a new trailing group separated by a blank line.
     */
    private static ImportInsertion inferredInsertion(String source, List<ImportStmt> regular, String newFqn, String sep) {
        List<List<ImportStmt>> groups = inferGroups(source, regular);
        boolean descending = inferDescending(groups);

        int bestGroup = -1;
        int bestScore = 0;
        for (int g = 0; g < groups.size(); g++) {
            for (ImportStmt member : groups.get(g)) {
                int score = sharedPackagePrefix(member.name(), newFqn);
                if (score > bestScore) {
                    bestScore = score;
                    bestGroup = g;
                }
            }
        }

        List<ImportStmt> group;
        if (bestScore > 0) {
            group = groups.get(bestGroup);
        } else if (groups.size() == 1) {
            // A single ungrouped block absorbs every new import (its position is decided by direction below) regardless
            // of package family, so we never split an intentionally-merged block into the hard-coded groups.
            group = groups.get(0);
        } else {
            // The file groups its imports and the new one matches no existing group's package prefix: start a new
            // trailing group, separated by a blank line, rather than guessing which group it belongs to.
            ImportStmt last = regular.get(regular.size() - 1);
            return new ImportInsertion(last.lineEnd(), sep + "import " + newFqn + ";" + sep);
        }
        ImportStmt after = null;
        ImportStmt before = null;
        for (ImportStmt member : group) {
            int cmp = member.name().compareTo(newFqn);
            boolean sortsBefore = descending ? cmp > 0 : cmp < 0;
            if (sortsBefore) {
                after = member;
            } else {
                before = member;
                break;
            }
        }
        if (after != null) {
            return new ImportInsertion(after.lineEnd(), "import " + newFqn + ";" + sep);
        }
        ImportStmt first = before == null ? group.get(0) : before;
        return new ImportInsertion(first.lineStart(), "import " + newFqn + ";" + sep);
    }

    /** Splits the regular imports into groups, breaking on a blank line between consecutive import statements. */
    private static List<List<ImportStmt>> inferGroups(String source, List<ImportStmt> regular) {
        List<List<ImportStmt>> groups = new ArrayList<>();
        List<ImportStmt> current = new ArrayList<>();
        for (int i = 0; i < regular.size(); i++) {
            if (i > 0) {
                // Each import's match consumes its own trailing newline, so any newline remaining in the gap to the
                // next import means at least one blank line separates them: a group boundary.
                String gap = source.substring(regular.get(i - 1).lineEnd(), regular.get(i).lineStart());
                if (gap.contains("\n")) {
                    groups.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(regular.get(i));
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /** Infers descending order when, across all within-group adjacent pairs, descending pairs outnumber ascending. */
    private static boolean inferDescending(List<List<ImportStmt>> groups) {
        int ascending = 0;
        int descending = 0;
        for (List<ImportStmt> group : groups) {
            for (int i = 1; i < group.size(); i++) {
                int cmp = group.get(i - 1).name().compareTo(group.get(i).name());
                if (cmp < 0) {
                    ascending++;
                } else if (cmp > 0) {
                    descending++;
                }
            }
        }
        return descending > ascending;
    }

    /** Number of equal leading dot-separated segments shared by two qualified names (their common package prefix). */
    private static int sharedPackagePrefix(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int limit = Math.min(leftParts.length, rightParts.length);
        int shared = 0;
        for (int i = 0; i < limit; i++) {
            if (leftParts[i].equals(rightParts[i])) {
                shared++;
            } else {
                break;
            }
        }
        return shared;
    }
}
