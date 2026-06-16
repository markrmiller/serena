package io.serena.javarefactor.operations.extract_method;

/**
 * Selection normalization and line/column ↔ offset translation for the V2 extract-method planner.
 *
 * <p>This unit owns the purely textual concerns of a raw editor selection: converting one-based
 * line/column coordinates into absolute character offsets, trimming leading/trailing whitespace so a
 * selection that visually spans whole lines is treated as the tight statement/expression range, and
 * recovering line/column coordinates for diagnostics. It performs no semantic (javac) analysis — that
 * is the job of {@link DataFlowAnalyzer} and {@link ControlFlowAnalyzer}. Keeping it separate makes the
 * normalization independently testable and removes coordinate bookkeeping from the planner.
 */
final class SelectionAnalyzer {

    private SelectionAnalyzer() {}

    /** A normalized, whitespace-trimmed selection expressed as absolute character offsets. */
    record NormalizedRange(int start, int end) {
        boolean isEmpty() {
            return start >= end;
        }
    }

    /**
     * Translates a one-based {@code line}/{@code column} pair into an absolute character offset.
     *
     * @throws SelectionException with code {@code invalid_selection} when the coordinates are not
     *     one-based, or {@code selection_out_of_range} when the line is past the end of the source.
     */
    static int offset(String source, int line, int column) throws SelectionException {
        if (line <= 0 || column <= 0) {
            throw new SelectionException("invalid_selection", "Selection lines and columns are one-based.");
        }
        int currentLine = 1;
        int lineStart = 0;
        for (int index = 0; index < source.length() && currentLine < line; index++) {
            if (source.charAt(index) == '\n') {
                currentLine++;
                lineStart = index + 1;
            }
        }
        if (currentLine != line) {
            throw new SelectionException("selection_out_of_range", "Selection line is outside the source file.");
        }
        return Math.min(source.length(), lineStart + column - 1);
    }

    /**
     * Trims leading and trailing whitespace from the half-open {@code [start, end)} range and returns the
     * tightened bounds. The result {@linkplain NormalizedRange#isEmpty() is empty} when the range collapses
     * after trimming (e.g. the selection was all whitespace).
     */
    static NormalizedRange normalize(String source, int start, int end) {
        int normalizedStart = start;
        while (normalizedStart < end && Character.isWhitespace(source.charAt(normalizedStart))) {
            normalizedStart++;
        }
        int normalizedEnd = end;
        while (normalizedEnd > normalizedStart && Character.isWhitespace(source.charAt(normalizedEnd - 1))) {
            normalizedEnd--;
        }
        return new NormalizedRange(normalizedStart, normalizedEnd);
    }

    /** The leading horizontal whitespace (the indentation) of the line containing {@code offset}. */
    static String leadingWhitespace(String source, int offset) {
        int start = offset;
        while (start > 0 && source.charAt(start - 1) != '\n') {
            start--;
        }
        int end = start;
        while (end < source.length() && Character.isWhitespace(source.charAt(end)) && source.charAt(end) != '\n') {
            end++;
        }
        return source.substring(start, end);
    }

    /** The one-based {@code [line, column]} of {@code offset}, clamped to the source bounds. */
    static int[] lineColumn(String source, int offset) {
        int line = 1;
        int column = 1;
        int bounded = Math.max(0, Math.min(offset, source.length()));
        for (int index = 0; index < bounded; index++) {
            if (source.charAt(index) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new int[] {line, column};
    }

    /** A structured selection failure carrying a stable refusal {@code code}. */
    static final class SelectionException extends Exception {
        private final String code;

        SelectionException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
