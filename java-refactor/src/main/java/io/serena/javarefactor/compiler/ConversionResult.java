package io.serena.javarefactor.compiler;

/**
 * Outcome of a V3 conversion analysis (refactor-feature-plan-V3.md §12 anonymous-class-to-lambda and §13
 * lambda-to-method-reference). The analysis lives in {@link SemanticConversionIndex} because deciding whether a
 * conversion is semantics-preserving needs javac's resolved {@code Trees}/{@code Types} model; the thin v3 planner only
 * translates this result into the wire envelope.
 *
 * <p>An accepted result describes a single in-file text replacement: the half-open UTF-16 offset range
 * {@code [start, end)} of the node to rewrite (the {@code new T(){...}} expression for §12, the lambda expression for
 * §13) and the {@code replacement} text. A refused result carries a canonical {@code refusalCode} drawn from the
 * §12.2/§12.4 and §13.3 refusal lists plus a human-readable {@code refusalMessage}.
 */
public record ConversionResult(
        boolean accepted,
        String refusalCode,
        String refusalMessage,
        int start,
        int end,
        String replacement) {

    /** A refusal carrying a canonical registry {@code code} and message; offsets are unset. */
    public static ConversionResult refuse(String code, String message) {
        return new ConversionResult(false, code, message, -1, -1, null);
    }

    /** An accepted single-range replacement {@code [start, end) -> replacement}. */
    public static ConversionResult accept(int start, int end, String replacement) {
        return new ConversionResult(true, null, null, start, end, replacement);
    }
}
