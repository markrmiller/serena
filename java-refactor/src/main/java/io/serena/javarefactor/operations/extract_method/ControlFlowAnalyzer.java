package io.serena.javarefactor.operations.extract_method;

import java.util.ArrayList;
import java.util.List;

/**
 * Planner-local classification of non-local control-flow exits inside an extract-method selection.
 *
 * <p>The semantic ({@code javac}) layer reports only a single collapsed {@code hasControlFlowExit}
 * boolean for a selection, which is enough to <em>refuse</em> but not enough to <em>synthesize</em> a
 * correct extraction. To synthesize, the planner must know <em>which</em> kind of jump escapes the
 * selection (a value {@code return}, a bare {@code return}, a {@code break}, or a {@code continue}) and
 * whether the jump is genuinely non-local (escapes the selection) or is captured by a loop/switch that
 * lives entirely inside the selection.
 *
 * <p>This analyzer recovers those facts from the selected source text with a brace-aware lexer that
 * skips string/char literals and comments and tracks loop ({@code for}/{@code while}/{@code do}) and
 * {@code switch} nesting. A {@code break}/{@code continue} that targets a loop/switch opened inside the
 * selection is <em>local</em> and ignored; everything else escapes. It is deliberately conservative:
 * any construct it cannot prove safe (labeled jumps, a mix of jump kinds, value returns whose type it
 * cannot read) is reported as {@link ExitKind#UNSUPPORTED} so the planner refuses rather than emitting an
 * incorrect preview. Working on the selected text keeps the unit planner-local — it needs no compiler
 * facts beyond the collapsed boolean and the enclosing method header (for the return type).
 */
final class ControlFlowAnalyzer {

    private ControlFlowAnalyzer() {}

    /** The uniform kind of non-local exit a selection contains, or a refusal sentinel. */
    enum ExitKind {
        /** No non-local control-flow exit; the selection is a plain block. */
        NONE,
        /** One or more {@code return <expr>;} that escape the selection (enclosing method is non-void). */
        RETURN_VALUE,
        /** Only bare {@code return;} statements escape (enclosing method is void). */
        RETURN_VOID,
        /** Only non-local {@code break;} statements escape. */
        BREAK,
        /** Only non-local {@code continue;} statements escape. */
        CONTINUE,
        /** A mix of kinds, a labeled jump, or anything that cannot be synthesized soundly. */
        UNSUPPORTED
    }

    /**
     * The result of analyzing a selection's control flow.
     *
     * @param kind the uniform exit classification
     * @param returnType for {@link ExitKind#RETURN_VALUE}, the enclosing method's declared return type;
     *     otherwise {@code null}
     */
    record ControlFlow(ExitKind kind, String returnType) {
        boolean hasExit() {
            return kind != ExitKind.NONE;
        }
    }

    /** A single non-local jump token discovered in the selection, with its offsets within the selection text. */
    record Jump(ExitKind kind, int start, int end, String value) {}

    /**
     * Classifies the control flow of {@code selectedText}. {@code hasControlFlowExitFact} is the collapsed
     * boolean from the semantic layer: when it is {@code false} there is provably no escaping jump and we
     * shortcut to {@link ExitKind#NONE}. {@code enclosingMethodHeader} is the source text of the enclosing
     * method declaration up to (and excluding) the body, used to read the return type for value returns.
     */
    static ControlFlow analyze(String selectedText, boolean hasControlFlowExitFact, String enclosingMethodHeader) {
        if (!hasControlFlowExitFact) {
            return new ControlFlow(ExitKind.NONE, null);
        }
        List<Jump> jumps = nonLocalJumps(selectedText);
        if (jumps.isEmpty()) {
            // The javac boolean flagged an exit but every jump is captured by an in-selection loop/switch
            // (e.g. a break inside a wholly-selected for-loop). Nothing escapes; treat as a plain block.
            return new ControlFlow(ExitKind.NONE, null);
        }
        ExitKind unified = null;
        for (Jump jump : jumps) {
            if (jump.kind() == ExitKind.UNSUPPORTED) {
                return new ControlFlow(ExitKind.UNSUPPORTED, null);
            }
            if (unified == null) {
                unified = jump.kind();
            } else if (unified != jump.kind()) {
                // A bare `return;` mixed with `return <expr>;` is still inconsistent at the type level, and
                // any break/continue/return mixture cannot share one boolean/holder signal. Refuse.
                return new ControlFlow(ExitKind.UNSUPPORTED, null);
            }
        }
        if (unified == ExitKind.RETURN_VALUE) {
            String returnType = returnTypeOf(enclosingMethodHeader);
            if (returnType == null || returnType.isBlank() || "void".equals(returnType)) {
                return new ControlFlow(ExitKind.UNSUPPORTED, null);
            }
            return new ControlFlow(ExitKind.RETURN_VALUE, returnType);
        }
        return new ControlFlow(unified, null);
    }

    /** All non-local jumps in {@code text}, in source order. Jumps captured by in-selection loops/switch are skipped. */
    static List<Jump> nonLocalJumps(String text) {
        List<Jump> jumps = new ArrayList<>();
        Lexer lexer = new Lexer(text);
        int loopDepth = 0; // for/while/do nesting opened inside the selection
        int switchDepth = 0; // switch nesting opened inside the selection
        // Stack of brace frames; each frame remembers whether it was opened by a loop or a switch so we can
        // decrement the right counter on the matching close brace.
        List<FrameKind> braceStack = new ArrayList<>();
        FrameKind pendingFrame = FrameKind.PLAIN; // kind to assign to the next '{' we open
        int parenDepth = 0; // parenthesis nesting, so `for (;;)` header semicolons are not seen as statement ends

        while (lexer.hasNext()) {
            Lexer.Token token = lexer.next();
            switch (token.type()) {
                case WORD -> {
                    switch (token.text()) {
                        case "for", "while", "do" -> {
                            loopDepth++;
                            pendingFrame = FrameKind.LOOP;
                        }
                        case "switch" -> {
                            switchDepth++;
                            pendingFrame = FrameKind.SWITCH;
                        }
                        case "return" -> jumps.add(parseReturn(lexer, token.start()));
                        case "break" -> {
                            Jump jump = parseBreakOrContinue(lexer, token.start(), ExitKind.BREAK);
                            // A break is captured by either an enclosing loop or switch opened in-selection.
                            if (jump != null && loopDepth == 0 && switchDepth == 0) {
                                jumps.add(jump);
                            } else if (jump != null && jump.kind() == ExitKind.UNSUPPORTED) {
                                jumps.add(jump);
                            }
                        }
                        case "continue" -> {
                            Jump jump = parseBreakOrContinue(lexer, token.start(), ExitKind.CONTINUE);
                            // A continue is captured only by an enclosing loop (never a switch).
                            if (jump != null && loopDepth == 0) {
                                jumps.add(jump);
                            } else if (jump != null && jump.kind() == ExitKind.UNSUPPORTED) {
                                jumps.add(jump);
                            }
                        }
                        default -> {
                            // Any other identifier resets a pending loop/switch frame only if no '{' followed yet
                            // and the construct turned out to be braceless; handled implicitly by pendingFrame reset
                            // when the next statement boundary is a ';' rather than a '{'. See SEMICOLON handling.
                        }
                    }
                }
                case OPEN_BRACE -> {
                    braceStack.add(pendingFrame);
                    pendingFrame = FrameKind.PLAIN;
                }
                case CLOSE_BRACE -> {
                    if (!braceStack.isEmpty()) {
                        FrameKind closed = braceStack.remove(braceStack.size() - 1);
                        if (closed == FrameKind.LOOP) {
                            loopDepth = Math.max(0, loopDepth - 1);
                        } else if (closed == FrameKind.SWITCH) {
                            switchDepth = Math.max(0, switchDepth - 1);
                        }
                    }
                }
                case SEMICOLON -> {
                    // A ';' inside a loop/switch header's parentheses (e.g. the `for (init; cond; step)` clauses) is
                    // NOT a statement terminator, so it must not resolve the pending frame. Only a top-level ';'
                    // before any '{' marks a braceless single-statement loop (e.g. `while (x) doThing();`); in that
                    // case drop the optimistically-incremented counter and reset the pending frame.
                    if (parenDepth == 0) {
                        if (pendingFrame == FrameKind.LOOP) {
                            loopDepth = Math.max(0, loopDepth - 1);
                        } else if (pendingFrame == FrameKind.SWITCH) {
                            switchDepth = Math.max(0, switchDepth - 1);
                        }
                        pendingFrame = FrameKind.PLAIN;
                    }
                }
                case OTHER -> {
                    if ("(".equals(token.text())) {
                        parenDepth++;
                    } else if (")".equals(token.text())) {
                        parenDepth = Math.max(0, parenDepth - 1);
                    }
                }
                default -> {
                    // Unreachable: all token types handled above.
                }
            }
        }
        return jumps;
    }

    private enum FrameKind {
        PLAIN,
        LOOP,
        SWITCH
    }

    /** Parses the remainder of a {@code return} statement starting just after the keyword. */
    private static Jump parseReturn(Lexer lexer, int keywordStart) {
        String value = lexer.readUntilStatementEnd();
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return new Jump(ExitKind.RETURN_VOID, keywordStart, lexer.position(), null);
        }
        return new Jump(ExitKind.RETURN_VALUE, keywordStart, lexer.position(), trimmed);
    }

    /** Parses a {@code break}/{@code continue}; labeled jumps are UNSUPPORTED (we cannot prove label locality). */
    private static Jump parseBreakOrContinue(Lexer lexer, int keywordStart, ExitKind kind) {
        String value = lexer.readUntilStatementEnd();
        String trimmed = value.strip();
        if (!trimmed.isEmpty()) {
            // `break label;` / `continue label;` — refuse: the label may target an in-selection or out-of-selection
            // construct and we will not guess.
            return new Jump(ExitKind.UNSUPPORTED, keywordStart, lexer.position(), trimmed);
        }
        return new Jump(kind, keywordStart, lexer.position(), null);
    }

    /**
     * Extracts the declared return type from an enclosing method header such as
     * {@code "    private static Money compute(Order o) throws X "}. Returns the token immediately preceding
     * the method-name/parameter list, or {@code null} when it cannot be parsed.
     */
    static String returnTypeOf(String header) {
        if (header == null) {
            return null;
        }
        int paren = header.indexOf('(');
        if (paren < 0) {
            return null;
        }
        String beforeParen = header.substring(0, paren).strip();
        // Drop a trailing generic type-parameter section's contribution by scanning back to the method name token.
        int nameStart = beforeParen.length();
        while (nameStart > 0 && isJavaIdentifierPart(beforeParen.charAt(nameStart - 1))) {
            nameStart--;
        }
        String beforeName = beforeParen.substring(0, nameStart).strip();
        if (beforeName.isEmpty()) {
            return null;
        }
        // The return type is the last whitespace-separated token, but it may carry generics/array brackets which
        // contain spaces (e.g. "Map<String, Integer>"). Find the last top-level (depth-0) space.
        int depth = 0;
        int cut = -1;
        for (int i = beforeName.length() - 1; i >= 0; i--) {
            char c = beforeName.charAt(i);
            if (c == '>') {
                depth++;
            } else if (c == '<') {
                depth--;
            } else if (depth == 0 && Character.isWhitespace(c)) {
                cut = i;
                break;
            }
        }
        String returnType = (cut < 0 ? beforeName : beforeName.substring(cut + 1)).strip();
        return returnType.isEmpty() ? null : returnType;
    }

    private static boolean isJavaIdentifierPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    /**
     * A minimal Java lexer that yields structural tokens (words, braces, semicolons) while skipping string and
     * character literals and {@code //} / {@code /* *}{@code /} comments, so the brace/keyword bookkeeping above is
     * not confused by punctuation inside literals or comments.
     */
    static final class Lexer {
        private final String text;
        private int pos;

        Lexer(String text) {
            this.text = text;
        }

        boolean hasNext() {
            skipTrivia();
            return pos < text.length();
        }

        int position() {
            return pos;
        }

        enum Type {
            WORD,
            OPEN_BRACE,
            CLOSE_BRACE,
            SEMICOLON,
            OTHER
        }

        record Token(Type type, String text, int start) {}

        Token next() {
            skipTrivia();
            int start = pos;
            char c = text.charAt(pos);
            if (Character.isJavaIdentifierStart(c)) {
                while (pos < text.length() && Character.isJavaIdentifierPart(text.charAt(pos))) {
                    pos++;
                }
                return new Token(Type.WORD, text.substring(start, pos), start);
            }
            pos++;
            return switch (c) {
                case '{' -> new Token(Type.OPEN_BRACE, "{", start);
                case '}' -> new Token(Type.CLOSE_BRACE, "}", start);
                case ';' -> new Token(Type.SEMICOLON, ";", start);
                default -> new Token(Type.OTHER, String.valueOf(c), start);
            };
        }

        /**
         * Reads from the current position up to (and consuming) the terminating {@code ;} of the current
         * statement, returning the text in between (the return/break/continue operand). Nested parentheses and
         * braces are balanced so a {@code ;} inside a {@code for(;;)} or lambda body does not terminate early.
         */
        String readUntilStatementEnd() {
            StringBuilder operand = new StringBuilder();
            int parenDepth = 0;
            int braceDepth = 0;
            while (pos < text.length()) {
                if (skipTriviaInto(operand)) {
                    continue;
                }
                if (pos >= text.length()) {
                    break;
                }
                char c = text.charAt(pos);
                if (c == ';' && parenDepth == 0 && braceDepth == 0) {
                    pos++;
                    break;
                }
                switch (c) {
                    case '(' -> parenDepth++;
                    case ')' -> parenDepth = Math.max(0, parenDepth - 1);
                    case '{' -> braceDepth++;
                    case '}' -> {
                        if (braceDepth == 0) {
                            // Defensive: a stray close brace ends the operand without consuming it.
                            return operand.toString();
                        }
                        braceDepth--;
                    }
                    default -> {
                    }
                }
                operand.append(c);
                pos++;
            }
            return operand.toString();
        }

        private void skipTrivia() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                    while (pos < text.length() && text.charAt(pos) != '\n') {
                        pos++;
                    }
                } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '*') {
                    pos += 2;
                    while (pos + 1 < text.length() && !(text.charAt(pos) == '*' && text.charAt(pos + 1) == '/')) {
                        pos++;
                    }
                    pos = Math.min(text.length(), pos + 2);
                } else if (c == '"' || c == '\'') {
                    skipLiteral(c, null);
                } else {
                    return;
                }
            }
        }

        /** Like {@link #skipTrivia()} but appends any skipped string/char literal verbatim into {@code sink}. */
        private boolean skipTriviaInto(StringBuilder sink) {
            int before = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                    while (pos < text.length() && text.charAt(pos) != '\n') {
                        pos++;
                    }
                } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '*') {
                    pos += 2;
                    while (pos + 1 < text.length() && !(text.charAt(pos) == '*' && text.charAt(pos + 1) == '/')) {
                        pos++;
                    }
                    pos = Math.min(text.length(), pos + 2);
                } else if (c == '"' || c == '\'') {
                    skipLiteral(c, sink);
                } else {
                    break;
                }
            }
            return pos != before;
        }

        /** Consumes a string/char literal starting at the opening quote; appends it to {@code sink} when non-null. */
        private void skipLiteral(char quote, StringBuilder sink) {
            int start = pos;
            pos++; // opening quote
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '\\') {
                    pos = Math.min(text.length(), pos + 2);
                    continue;
                }
                pos++;
                if (c == quote) {
                    break;
                }
            }
            if (sink != null) {
                sink.append(text, start, pos);
            }
        }
    }
}
