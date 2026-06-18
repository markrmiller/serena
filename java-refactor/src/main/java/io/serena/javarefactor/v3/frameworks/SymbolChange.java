package io.serena.javarefactor.v3.frameworks;

/**
 * A pending refactoring change a {@link FrameworkPlugin} is asked to participate in
 * (refactor-feature-plan-V3.md §16, the {@code participate(SymbolChange, TransformationContext)} hook). It is a plain
 * carrier describing <em>what</em> is changing so a plugin can decide whether to block it, contribute resource
 * edits/warnings, validate framework metadata, or contribute reachability roots — without the plugin needing to know
 * how any particular planner is implemented.
 *
 * <p>The {@link Kind} distinguishes the operation seams the SPI participates in (§16: type rename, package rename, safe
 * delete, dead-code scan). {@link #targetFqn()} is the fully-qualified name of the primary symbol being changed (the
 * type being deleted/renamed, or the package being renamed); it is {@code null} for a whole-project
 * {@link Kind#DEAD_CODE_SCAN}, which asks every plugin to contribute its framework-managed reachability roots rather
 * than reasoning about one target. {@link #newName()} is the proposed new fully-qualified name for a rename, {@code null}
 * for the other kinds.
 *
 * @param kind      which operation seam this change belongs to
 * @param targetFqn the fully-qualified name of the symbol being changed, or {@code null} for a whole-project scan
 * @param newName   the proposed new fully-qualified name for a rename, or {@code null}
 */
public record SymbolChange(Kind kind, String targetFqn, String newName) {

    /** The operation seams a {@link FrameworkPlugin} can participate in (refactor-feature-plan-V3.md §16). */
    public enum Kind {
        /** A top-level type is being safe-deleted. */
        SAFE_DELETE,
        /** A type is being renamed. */
        RENAME_TYPE,
        /** A package is being renamed. */
        RENAME_PACKAGE,
        /** A whole-project dead-code scan is running; plugins contribute framework-managed reachability roots. */
        DEAD_CODE_SCAN
    }

    /** A pending safe-delete of {@code targetFqn}. */
    public static SymbolChange safeDelete(String targetFqn) {
        return new SymbolChange(Kind.SAFE_DELETE, targetFqn, null);
    }

    /** A pending type rename of {@code targetFqn} to {@code newFqn}. */
    public static SymbolChange renameType(String targetFqn, String newFqn) {
        return new SymbolChange(Kind.RENAME_TYPE, targetFqn, newFqn);
    }

    /** A pending package rename of {@code targetPackage} to {@code newPackage}. */
    public static SymbolChange renamePackage(String targetPackage, String newPackage) {
        return new SymbolChange(Kind.RENAME_PACKAGE, targetPackage, newPackage);
    }

    /** A whole-project dead-code scan; plugins contribute their framework-managed reachability roots. */
    public static SymbolChange deadCodeScan() {
        return new SymbolChange(Kind.DEAD_CODE_SCAN, null, null);
    }
}
