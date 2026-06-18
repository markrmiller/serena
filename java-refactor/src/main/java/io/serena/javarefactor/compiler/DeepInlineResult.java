package io.serena.javarefactor.compiler;

import java.util.List;

/**
 * Result of a V3 generalized inline-method analysis (refactor-feature-plan-V3.md §11). Unlike the single-edit
 * {@link ConversionResult}, a block inline rewrites one or more call-site statements (and optionally deletes the method
 * declaration), so the accepted result carries a list of in-file text edits. All offsets are into the declaring file's
 * source.
 */
public record DeepInlineResult(boolean accepted, String refusalCode, String refusalMessage, List<Edit> edits,
        List<String> warnings) {

    /** A single text replacement {@code [start, end)} -> {@code replacement} within the declaring file. */
    public record Edit(int start, int end, String replacement) {
    }

    public static DeepInlineResult refuse(String code, String message) {
        return new DeepInlineResult(false, code, message, List.of(), List.of());
    }

    public static DeepInlineResult accept(List<Edit> edits, List<String> warnings) {
        return new DeepInlineResult(true, null, null, edits, warnings);
    }
}
