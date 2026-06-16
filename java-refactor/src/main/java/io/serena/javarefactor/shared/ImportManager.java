package io.serena.javarefactor.shared;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import io.serena.javarefactor.edits.PlannerSupport;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.lang.model.type.TypeMirror;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A whole-compilation-unit import manager for conservative V2 source edits.
 *
 * <p>Unlike a purely textual planner, this manager drives its decisions from javac's parsed
 * compilation-unit tree: the package declaration, the import trees (single-type, wildcard, and
 * static), and the simple names actually referenced by the type bodies. That lets it:
 * <ul>
 *   <li>add single-type and static imports while skipping {@code java.lang.*} and same-package types,</li>
 *   <li>preserve existing static and wildcard imports verbatim (they are never auto-removed),</li>
 *   <li>detect simple-name ambiguity and refuse — signalling callers to fall back to a fully
 *       qualified name rather than emit a colliding import,</li>
 *   <li>compute unused single-type imports over the entire compilation unit, and</li>
 *   <li>render the import block in the file's own style (line ending, java/javax/other grouping, and
 *       static-imports-first vs -last) via {@link JavaStyleProfile}.</li>
 * </ul>
 *
 * <p>Alphabetical ordering is applied only when the source file's own imports were already in canonical
 * (group + alphabetical) order; a file using a deliberate non-canonical arrangement is preserved in its
 * original order, with newly added imports appended, rather than reflowed.</p>
 *
 * <p>When the JDK system compiler is unavailable, or the source does not parse into a compilation
 * unit with a type body, the manager degrades conservatively: it falls back to a tolerant textual
 * scan for the import section and treats every import as used (so {@link #removeUnusedImports()}
 * removes nothing rather than guess).
 */
public final class ImportManager {
    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void", "var");

    private final String currentPackage;
    /** The original source, retained for the delegated type-usage planning engine and static import insertions. */
    private final String source;
    /** Lazily built rewrite engine that backs the V2 type-usage planning entry points. */
    private ImportRewritePlanner rewriteEngine;
    /** Optional compiler-backed simple-name conflict resolver consulted by {@link #planTypeUsageDeep}. */
    private SimpleNameConflictResolver conflictResolver;
    private final JavaStyleProfile style;
    /** Non-static imports, including wildcard ({@code pkg.*}) entries, in insertion order. */
    private final LinkedHashSet<String> imports = new LinkedHashSet<>();
    private final LinkedHashSet<String> staticImports = new LinkedHashSet<>();
    /** Parsed compilation unit, when javac was able to parse the source. */
    private final CompilationUnitTree compilationUnit;
    /** Lazily computed set of simple names referenced by the type bodies. */
    private Set<String> usedSimpleNames;
    /** Simple names explicitly excluded from the used-names set via {@link #declareReferenceRemoved}. */
    private final Set<String> removedReferenceNames = new HashSet<>();
    /**
     * Whether the regular import block may be re-sorted into canonical (group + alphabetical) order on
     * render. True when the source file's own regular imports were already in canonical order (or there
     * were too few to tell); false when the file used a deliberate non-canonical arrangement, in which
     * case {@link #renderImportBlock()} preserves the file's existing order and appends new imports.
     */
    private final boolean canonicalOrdering;

    public ImportManager(String source) {
        String safeSource = source == null ? "" : source;
        this.source = safeSource;
        this.style = JavaStyleProfile.infer(safeSource.isEmpty() ? "\n" : safeSource);
        this.compilationUnit = parse(safeSource).orElse(null);
        if (compilationUnit != null) {
            this.currentPackage = compilationUnit.getPackageName() == null
                    ? ""
                    : compilationUnit.getPackageName().toString();
            for (ImportTree importTree : compilationUnit.getImports()) {
                if (importTree == null || importTree.getQualifiedIdentifier() == null) {
                    continue;
                }
                String name = importTree.getQualifiedIdentifier().toString().replaceAll("\\s+", "");
                if (importTree.isStatic()) {
                    staticImports.add(name);
                } else {
                    imports.add(name);
                }
            }
        } else {
            this.currentPackage = textualPackage(safeSource);
            parseImportsTextually(safeSource);
        }
        this.canonicalOrdering = isCanonicalOrder(new ArrayList<>(imports));
    }

    /**
     * Whether {@code fileOrder} (the regular imports exactly as they appeared in the source) is already
     * in the canonical group-then-alphabetical order this manager would otherwise impose. Fewer than two
     * entries are treated as canonical (there is nothing to reorder, so re-sorting is safe and stable).
     * A file that deviates from canonical order is assumed to use a deliberate arrangement that must be
     * preserved rather than reflowed.
     */
    private static boolean isCanonicalOrder(List<String> fileOrder) {
        if (fileOrder.size() < 2) {
            return true;
        }
        List<String> canonical = new ArrayList<>(fileOrder);
        canonical.sort(ImportManager::compareCanonical);
        return canonical.equals(fileOrder);
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Adds a single-type import for {@code qualifiedName}, returning a refusal when the simple name
     * collides with an existing single-type import of a different type (the caller should then
     * render the use fully qualified). Imports of {@code java.lang} types and same-package types are
     * silently skipped (no import is needed), reported as success.
     */
    public Optional<StructuredRefusal> addImport(String qualifiedName) {
        String name = normalize(qualifiedName);
        if (name == null) {
            return Optional.empty();
        }
        if (isImplicitlyVisible(name)) {
            return Optional.empty();
        }
        if (imports.contains(name) || isCoveredByWildcard(name)) {
            return Optional.empty();
        }
        if (hasSimpleNameConflict(name, imports)) {
            return Optional.of(new StructuredRefusal("import_conflict", "Import would conflict on simple name: " + name));
        }
        imports.add(name);
        return Optional.empty();
    }

    /** Adds a static member import, refusing on a colliding simple member name. */
    public Optional<StructuredRefusal> addStaticImport(String qualifiedMember) {
        String name = normalize(qualifiedMember);
        if (name == null) {
            return Optional.empty();
        }
        if (staticImports.contains(name)) {
            return Optional.empty();
        }
        if (hasSimpleNameConflict(name, staticImports)) {
            return Optional.of(new StructuredRefusal("static_import_conflict", "Static import would conflict: " + name));
        }
        staticImports.add(name);
        return Optional.empty();
    }

    /**
     * Adds single-type imports for every declared type referenced by {@code type}, walking nested
     * generic arguments, array components, and bounds via {@link ImportRewritePlanner#collectReferencedTypeNames(TypeMirror)}.
     * Returns the structured refusals for any types whose simple name collided (the caller should
     * render those fully qualified). This is the compiler-backed entry point for the Phase-2 move
     * (G012/G017) and inline (G032) planners when transplanting a body.
     */
    public List<StructuredRefusal> addReferencedTypes(TypeMirror type) {
        return addReferencedTypes(ImportRewritePlanner.collectReferencedTypeNames(type));
    }

    /**
     * Adds single-type imports for every fully-qualified name in {@code qualifiedNames}, collecting
     * the structured refusals for any that collide on simple name. Implicitly visible types
     * ({@code java.lang} and same-package) and already-covered types are silently skipped.
     */
    public List<StructuredRefusal> addReferencedTypes(Collection<String> qualifiedNames) {
        List<StructuredRefusal> refusals = new ArrayList<>();
        if (qualifiedNames == null) {
            return refusals;
        }
        for (String qualifiedName : qualifiedNames) {
            addImport(qualifiedName).ifPresent(refusals::add);
        }
        return refusals;
    }

    /** Removes an exact single-type import (wildcard and static imports are left untouched). */
    public boolean removeImport(String qualifiedName) {
        String name = normalize(qualifiedName);
        return name != null && !name.endsWith(".*") && imports.remove(name);
    }

    /**
     * Removes every single-type import whose simple name is not referenced anywhere in the
     * compilation unit's type bodies, preserving wildcard and static imports. Returns the number of
     * imports removed. Degrades to a no-op (returns 0) when the body could not be parsed.
     */
    public int removeUnusedImports() {
        List<String> unused = unusedImports();
        unused.forEach(imports::remove);
        return unused.size();
    }

    /**
     * Declares that {@code simpleName} is no longer referenced anywhere in the compilation
     * unit — for example, because an edit deleted the last use site.  Subsequent calls to
     * {@link #unusedImports()} and {@link #removeUnusedImports()} will treat the matching
     * single-type import as unused and eligible for removal.  Safe to call before the
     * used-names set has been lazily initialised (the removal is tracked separately and
     * applied on first access).  Has no observable effect when the compilation unit was not
     * parseable (fallback mode already removes nothing).
     *
     * @param simpleName the unqualified class or member name that is no longer referenced
     */
    public void declareReferenceRemoved(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return;
        }
        String name = simpleName.strip();
        removedReferenceNames.add(name);
        if (usedSimpleNames != null) {
            usedSimpleNames.remove(name);
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<String> imports() {
        return List.copyOf(imports);
    }

    public List<String> staticImports() {
        return List.copyOf(staticImports);
    }

    /**
     * The single-type imports whose simple names are not referenced by any type body in the
     * compilation unit. Wildcard and static imports are never reported (they are always preserved),
     * and the result is empty when the body is not parseable (conservative: remove nothing).
     */
    public List<String> unusedImports() {
        Set<String> used = usedSimpleNames();
        if (used == null) {
            return List.of();
        }
        List<String> unused = new ArrayList<>();
        for (String name : imports) {
            if (name.endsWith(".*")) {
                continue;
            }
            if (!used.contains(simpleName(name))) {
                unused.add(name);
            }
        }
        return unused;
    }

    /**
     * Returns {@code true} when {@code qualifiedName} must be referenced at call sites by its
     * fully-qualified name rather than by the simple name.  This is the case when the simple
     * name is already claimed by a different single-type import — emitting another import for
     * the same simple name would produce a compile error.
     *
     * <p>Returns {@code false} for any of the following conditions, in which case the simple
     * name may be used freely:
     * <ul>
     *   <li>the type is implicitly visible (same package or {@code java.lang.*}),</li>
     *   <li>the type is already present in the import set,</li>
     *   <li>the type is covered by an existing wildcard import, or</li>
     *   <li>no conflicting single-type import exists (the type can simply be imported).</li>
     * </ul>
     *
     * <p>This is a pure read-only query — it does not add or remove any import.  The typical
     * caller pattern is: check {@code mustUseFqn(fqn)}; if {@code false}, call
     * {@link #addImport(String)} and emit the simple name; if {@code true}, emit the FQN
     * directly and skip {@code addImport}.
     *
     * @param qualifiedName the fully-qualified type name to test
     */
    public boolean mustUseFqn(String qualifiedName) {
        String name = normalize(qualifiedName);
        if (name == null || name.endsWith(".*")) {
            return false;
        }
        if (isImplicitlyVisible(name)) {
            return false;
        }
        if (imports.contains(name) || isCoveredByWildcard(name)) {
            return false;
        }
        return hasSimpleNameConflict(name, imports);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * Renders the managed imports as a formatted block in the source file's inferred style: each
     * section sorted alphabetically, regular imports grouped {@code java.* / javax.* / other} with a
     * blank line between groups, and the static block ordered first or last per the inferred
     * convention. The file's own line ending is used throughout.
     */
    public String renderImportBlock() {
        String lineEnding = style.lineEnding();
        List<String> regularBlock = renderRegularBlock();
        List<String> staticBlock = new ArrayList<>();
        ordered(staticImports).forEach(name -> staticBlock.add("import static " + name + ";"));

        List<String> lines = new ArrayList<>();
        if (style.staticImportsFirst()) {
            lines.addAll(staticBlock);
            if (!staticBlock.isEmpty() && !regularBlock.isEmpty()) {
                lines.add("");
            }
            lines.addAll(regularBlock);
        } else {
            lines.addAll(regularBlock);
            if (!regularBlock.isEmpty() && !staticBlock.isEmpty()) {
                lines.add("");
            }
            lines.addAll(staticBlock);
        }
        return String.join(lineEnding, lines);
    }

    private List<String> renderRegularBlock() {
        List<String> lines = new ArrayList<>();
        if (!canonicalOrdering) {
            // The file used a deliberate non-canonical arrangement: preserve the existing imports in
            // their original order (newly added imports were appended in insertion order) without
            // reflowing into java/javax/other groups or alphabetical order.
            for (String name : imports) {
                lines.add("import " + name + ";");
            }
            return lines;
        }
        int previousGroup = -1;
        for (String name : ordered(imports)) {
            int group = importGroup(name);
            if (previousGroup != -1 && group != previousGroup) {
                lines.add("");
            }
            lines.add("import " + name + ";");
            previousGroup = group;
        }
        return lines;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private Set<String> usedSimpleNames() {
        if (usedSimpleNames != null) {
            return usedSimpleNames;
        }
        if (compilationUnit == null || compilationUnit.getTypeDecls().isEmpty()) {
            return null;
        }
        Set<String> used = new HashSet<>();
        TreeScanner<Void, Void> scanner = new TreeScanner<>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                used.add(node.getName().toString());
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                used.add(node.getIdentifier().toString());
                return super.visitMemberSelect(node, unused);
            }
        };
        for (Tree typeDecl : compilationUnit.getTypeDecls()) {
            scanner.scan(typeDecl, null);
        }
        usedSimpleNames = used;
        usedSimpleNames.removeAll(removedReferenceNames);
        return usedSimpleNames;
    }

    /** Whether the type is implicitly visible and so needs no import: {@code java.lang.*} or same-package. */
    private boolean isImplicitlyVisible(String qualifiedName) {
        if (qualifiedName.endsWith(".*")) {
            return false;
        }
        String typePackage = packagePart(qualifiedName);
        if (typePackage.equals("java.lang")) {
            return true;
        }
        return !typePackage.isBlank() && typePackage.equals(currentPackage);
    }

    private boolean isCoveredByWildcard(String qualifiedName) {
        String typePackage = packagePart(qualifiedName);
        for (String existing : imports) {
            if (existing.endsWith(".*") && existing.substring(0, existing.length() - 2).equals(typePackage)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSimpleNameConflict(String qualifiedName, Set<String> existing) {
        String simple = simpleName(qualifiedName);
        for (String current : existing) {
            if (!current.endsWith(".*") && !current.equals(qualifiedName) && simpleName(current).equals(simple)) {
                return true;
            }
        }
        return false;
    }

    /** Normalizes a candidate import, returning {@code null} when it is blank or a primitive. */
    private static String normalize(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        String name = qualifiedName.strip();
        if (name.isEmpty() || PRIMITIVE_TYPES.contains(name)) {
            return null;
        }
        return name;
    }

    private static String simpleName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    private static String packagePart(String qualifiedName) {
        String normalized = qualifiedName.endsWith(".*")
                ? qualifiedName.substring(0, qualifiedName.length() - 2)
                : qualifiedName;
        int lastDot = normalized.lastIndexOf('.');
        return lastDot < 0 ? "" : normalized.substring(0, lastDot);
    }

    /** Group ordering for the regular import block: {@code java.*}, then {@code javax.*}, then others. */
    private static int importGroup(String name) {
        if (name.startsWith("java.")) {
            return 0;
        }
        if (name.startsWith("javax.")) {
            return 1;
        }
        return 2;
    }

    /**
     * Orders {@code names} for rendering: canonical group-then-alphabetical order when the source file's
     * imports were already canonical, otherwise the set's insertion order (file order for pre-existing
     * imports, append order for newly added ones) so a deliberate arrangement is preserved.
     */
    private List<String> ordered(Set<String> names) {
        List<String> result = new ArrayList<>(names);
        if (canonicalOrdering) {
            result.sort(ImportManager::compareCanonical);
        }
        return result;
    }

    /** Canonical comparison: group ({@code java.*} / {@code javax.*} / other) first, then alphabetical. */
    private static int compareCanonical(String left, String right) {
        int group = Integer.compare(importGroup(left), importGroup(right));
        return group != 0 ? group : left.compareTo(right);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private static Optional<CompilationUnitTree> parse(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Optional.empty();
        }
        JavaFileObject file = new SimpleJavaFileObject(
                URI.create("string:///ImportManagerCompilationUnit.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> { }, List.of(), null, List.of(file));
            Iterator<? extends CompilationUnitTree> units = task.parse().iterator();
            return units.hasNext() ? Optional.ofNullable(units.next()) : Optional.empty();
        } catch (java.io.IOException | RuntimeException | Error e) {
            return Optional.empty();
        }
    }

    /** Tolerant textual fallback for the import section used only when javac is unavailable. */
    private void parseImportsTextually(String source) {
        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.endsWith(";")) {
                continue;
            }
            String withoutSemicolon = trimmed.substring(0, trimmed.length() - 1).trim();
            if (withoutSemicolon.startsWith("import static ")) {
                staticImports.add(withoutSemicolon.substring("import static ".length()).replaceAll("\\s+", ""));
            } else if (withoutSemicolon.startsWith("import ")) {
                imports.add(withoutSemicolon.substring("import ".length()).replaceAll("\\s+", ""));
            }
        }
    }

    private static String textualPackage(String source) {
        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                return trimmed.substring("package ".length(), trimmed.length() - 1).trim();
            }
        }
        return "";
    }

    // ── V2 type-usage planning (the single unified entry point for operation planners) ─────────────────────────────

    /**
     * Compiler-backed resolver of simple-name collisions the edited file's own import section cannot see — namely a type
     * in the SAME package as the edited file, or another visible project type already claiming the simple name. Installed
     * via {@link #withConflictResolver(SimpleNameConflictResolver)}; when it reports a collision the planned reference is
     * left fully qualified rather than imported/simplified under an ambiguous simple name.
     */
    @FunctionalInterface
    public interface SimpleNameConflictResolver {
        /**
         * @param simpleName the unqualified name the reference would be rendered as
         * @param candidateFqn the fully-qualified type the reference actually denotes
         * @return {@code true} when a <em>different</em> same-package or visible project type already claims
         *     {@code simpleName}, so importing/simplifying {@code candidateFqn} would be ambiguous
         */
        boolean conflictsForSimpleName(String simpleName, String candidateFqn);
    }

    /**
     * Installs the semantic conflict resolver consulted by {@link #planTypeUsageDeep}. Passing {@code null} restores
     * pure text-based detection. Returns {@code this} for fluent use at call sites.
     */
    public ImportManager withConflictResolver(SimpleNameConflictResolver resolver) {
        this.conflictResolver = resolver;
        this.rewriteEngine = null; // rebuild so the resolver is applied on the next planning call
        return this;
    }

    /**
     * Plans a type reference and every import edit required to render it so it compiles, walking nested generic
     * arguments, array/varargs components, wildcard/intersection bounds, and annotation types. Importable types are
     * simplified and imported in the file's own import style; a type whose simple name collides with an existing
     * single-type import — or with a same-package/visible-project type reported by the installed
     * {@link SimpleNameConflictResolver} — is left fully qualified. This is the unified replacement for the former
     * standalone import-rewrite planner used by the change-signature, introduce-field, move, inline, and
     * extract-interface operations.
     */
    public TypeUse planTypeUsageDeep(Path file, String type, String kind) {
        ImportRewritePlanner.TypeUse use = rewriteEngine().planTypeUsageDeep(file, type, kind);
        return new TypeUse(use.renderedType(), use.importEdits());
    }

    /**
     * Plans the import edits required to reference {@code qualifiedTypeNames} (for example the fully-qualified types a
     * moved or inlined body depends on). Non-importable, already-covered, same-package, and {@code java.lang} types are
     * skipped; conflicting simple names yield no edit so the caller can render them fully qualified.
     */
    public List<PlannerSupport.TextEdit> planTypesForBody(Path file, Collection<String> qualifiedTypeNames, String kind) {
        return rewriteEngine().planTypesForBody(file, qualifiedTypeNames, kind);
    }

    private ImportRewritePlanner rewriteEngine() {
        if (rewriteEngine == null) {
            ImportRewritePlanner engine = new ImportRewritePlanner(source);
            if (conflictResolver != null) {
                SimpleNameConflictResolver resolver = conflictResolver;
                engine.withConflictResolver(resolver::conflictsForSimpleName);
            }
            rewriteEngine = engine;
        }
        return rewriteEngine;
    }

    /**
     * Computes a deterministic single-type import insertion for {@code qualifiedName} in the source's own import style,
     * or empty when it is already covered by an existing single-type or wildcard import.
     */
    public static Optional<ImportInsertion> computeImportInsertion(String source, String qualifiedName) {
        return ImportRewritePlanner.computeImportInsertion(source, qualifiedName)
                .map(insertion -> new ImportInsertion(insertion.offset(), insertion.text()));
    }

    /**
     * Computes a deterministic {@code import static} insertion for {@code qualifiedMember} in the source's own import
     * style, or empty when it is already covered by an existing static single or wildcard import.
     */
    public static Optional<ImportInsertion> computeStaticImportInsertion(String source, String qualifiedMember) {
        return ImportRewritePlanner.computeStaticImportInsertion(source, qualifiedMember)
                .map(insertion -> new ImportInsertion(insertion.offset(), insertion.text()));
    }

    /** Enumerates every type-name token referenced by a textual type expression (the text fallback for body imports). */
    public static Set<String> collectReferencedTypeNames(String typeExpression) {
        return ImportRewritePlanner.collectReferencedTypeNames(typeExpression);
    }

    /** Enumerates the fully-qualified names of every declared type referenced by a {@link TypeMirror}. */
    public static Set<String> collectReferencedTypeNames(TypeMirror type) {
        return ImportRewritePlanner.collectReferencedTypeNames(type);
    }

    /**
     * The outcome of planning a type reference.
     *
     * @param renderedType the reference rewritten to use simple names where safe (left fully qualified on conflict)
     * @param importEdits every import edit the rendered reference requires (one per newly imported type)
     */
    public record TypeUse(String renderedType, List<PlannerSupport.TextEdit> importEdits) {
    }

    /** A deterministic import insertion: the offset to insert at and the text to insert. */
    public record ImportInsertion(int offset, String text) {
    }
}
