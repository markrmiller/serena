package io.serena.javarefactor.shared;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight local Java style profile inferred from the edited source file.
 *
 * <p>Inference covers: line endings, indentation (tabs vs spaces + width), member indentation,
 * final-parameter style, blank lines between members, import ordering (static-first vs last),
 * annotation placement (own line vs same line), and brace style (K&R vs Allman).
 *
 * <p>Code generators call {@link #infer(String)} once per compilation unit, then use the accessor
 * methods and rendering helpers to emit text that matches the surrounding source style.
 */
public record JavaStyleProfile(
        String lineEnding,
        String indentUnit,
        String memberIndent,
        boolean finalParameters,
        int blankLinesBetweenMembers,
        boolean staticImportsFirst,
        boolean annotationsOnOwnLine,
        boolean openBraceSameLine) {

    // ── Existing inference patterns ──────────────────────────────────────────
    private static final Pattern INDENTED_CODE =
            Pattern.compile("(?m)^([ \\t]+)\\S");
    private static final Pattern MEMBER_LINE =
            Pattern.compile("(?m)^([ \\t]+)(?:public|protected|private|static|final|abstract|@)[^\\n]*(?:[;{]|$)");
    private static final Pattern FINAL_PARAMETER =
            Pattern.compile("\\([^)]*\\bfinal\\s+[A-Za-z_$][A-Za-z0-9_$.<>\\[\\]?]*\\s+[A-Za-z_$]");

    // ── New inference patterns ───────────────────────────────────────────────
    /** Matches blank lines between a closing brace and the next class member start. */
    private static final Pattern BLANK_LINES_BETWEEN_MEMBERS =
            Pattern.compile("\\}[ \\t]*\\r?\\n((?:[ \\t]*\\r?\\n)+)[ \\t]+(?:@|public|protected|private|static|final|abstract|\\w)");

    /** Matches an annotation on its own line, followed by a member declaration line. */
    private static final Pattern ANNOTATION_OWN_LINE =
            Pattern.compile("(?m)^[ \\t]*@\\w[^\\n]*\\r?\\n[ \\t]+(?:@|public|protected|private|static|final|abstract|void|int|boolean|long|double|float|byte|char|short|\\w)");

    /** Matches an annotation on the same line as access/return modifier (same-line placement). */
    private static final Pattern ANNOTATION_SAME_LINE =
            Pattern.compile("@\\w+(?:\\([^)]*\\))?[ \\t]+(?:public|protected|private|static|final|abstract)");

    /** Matches K&R style: opening brace on the same line as the method/block header. */
    private static final Pattern OPEN_BRACE_SAME_LINE_PATTERN =
            Pattern.compile("\\)[ \\t]*\\{");

    /** Matches Allman style: opening brace on a new line. */
    private static final Pattern OPEN_BRACE_NEW_LINE_PATTERN =
            Pattern.compile("\\)[ \\t]*\\r?\\n[ \\t]*\\{");

    /** Matches a static import declaration. */
    private static final Pattern STATIC_IMPORT =
            Pattern.compile("(?m)^import static ");

    /** Matches a regular (non-static) import declaration. */
    private static final Pattern REGULAR_IMPORT =
            Pattern.compile("(?m)^import (?!static )");

    // ────────────────────────────────────────────────────────────────────────

    /**
     * Infers all style properties from the given source text.
     *
     * <p>Properties inferred:
     * <ul>
     *   <li>Line endings (LF vs CRLF)
     *   <li>Indent unit (tab or N spaces, where N ≥ 2)
     *   <li>Member indentation (one-level indent for class members)
     *   <li>Final-parameter style
     *   <li>Blank lines between members (default 1)
     *   <li>Whether static imports precede regular imports (default false)
     *   <li>Whether annotations are on their own line (default true)
     *   <li>Whether opening braces are on the same line as the header (default true, K&R)
     * </ul>
     */
    public static JavaStyleProfile infer(String source) {
        String lineEnding = source.contains("\r\n") ? "\r\n" : "\n";
        String indentUnit = inferIndentUnit(source);
        String memberIndent = inferMemberIndent(source, indentUnit);
        boolean finalParameters = FINAL_PARAMETER.matcher(source).find();
        int blankLinesBetweenMembers = inferBlankLinesBetweenMembers(source);
        boolean staticImportsFirst = inferStaticImportsFirst(source);
        boolean annotationsOnOwnLine = inferAnnotationsOnOwnLine(source);
        boolean openBraceSameLine = inferOpenBraceSameLine(source);
        return new JavaStyleProfile(lineEnding, indentUnit, memberIndent, finalParameters,
                blankLinesBetweenMembers, staticImportsFirst, annotationsOnOwnLine, openBraceSameLine);
    }

    // ── Existing rendering helpers (unchanged) ───────────────────────────────

    /** Returns the class-member indentation one level outside a selected statement indentation. */
    public String outerIndentFor(String innerIndent) {
        if (innerIndent.startsWith(memberIndent) && innerIndent.length() > memberIndent.length()) {
            return memberIndent;
        }
        if (innerIndent.endsWith(indentUnit)) {
            return innerIndent.substring(0, innerIndent.length() - indentUnit.length());
        }
        return memberIndent;
    }

    /** Returns one indentation level inside the supplied indentation. */
    public String childIndent(String indent) {
        return indent + indentUnit;
    }

    /** Normalizes inserted text to the source file's line ending. */
    public String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace("\n", lineEnding);
    }

    /** Indents each non-blank line of text with the supplied indentation and source line ending. */
    public String indentLines(String text, String indent) {
        String normalized = text.replace("\r\n", "\n").stripTrailing();
        String[] lines = normalized.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                builder.append(lineEnding);
            }
            if (!lines[index].isBlank()) {
                builder.append(indent);
            }
            builder.append(lines[index].stripTrailing());
        }
        return builder.toString();
    }

    /** Returns the parameter declaration, honoring local final-parameter style when requested by nearby code. */
    public String parameter(String type, String name) {
        return (finalParameters ? "final " : "") + type + " " + name;
    }

    public String renderField(String modifiers, String type, String name, String initializer) {
        String suffix = initializer == null || initializer.isBlank() ? ";" : " = " + initializer + ";";
        return normalizeLineEndings("\n" + memberIndent + modifiers + " " + type + " " + name + suffix + "\n");
    }

    public String renderConstructor(String className, String body) {
        return renderMemberBlock("public " + className + "()", body);
    }

    public String renderMethod(String header, String body) {
        return renderMemberBlock(header, body);
    }

    public String renderInterfaceSource(String packageName, String interfaceName, List<String> imports, List<String> memberDeclarations) {
        String packageLine = packageName.isBlank() ? "" : "package " + packageName + ";\n\n";
        String importBlock = imports.isEmpty() ? "" : String.join("\n", imports) + "\n\n";
        String body = memberDeclarations.isEmpty() ? "" : "\n" + String.join("\n", memberDeclarations) + "\n";
        return normalizeLineEndings(packageLine + importBlock + "public interface " + interfaceName + openBrace("") + body + "}\n");
    }

    // ── New rendering helpers ────────────────────────────────────────────────

    /**
     * Returns the blank-line separator to insert between consecutive class members, using the
     * inferred blank-line count. The returned string ends with the file's line ending so it is
     * ready to be concatenated directly before the next member's indentation.
     *
     * <p>Example: with 1 blank line and LF endings this returns {@code "\n\n"} (end of previous
     * line + one blank line). Generators that produce multi-member output should call this between
     * each rendered member rather than hard-coding a single newline.
     */
    public String memberSeparator() {
        // One lineEnding ends the current line; the rest are the blank lines.
        return lineEnding.repeat(blankLinesBetweenMembers + 1);
    }

    /**
     * Renders a block of annotation tokens before a member, honoring the inferred annotation
     * placement style.
     *
     * <p>When {@link #annotationsOnOwnLine()} is {@code true} (the common Java style), each
     * annotation is emitted on its own indented line followed by the file's line ending. When
     * {@code false} (same-line style), the annotations are joined with spaces and returned with a
     * trailing space so they can be prepended directly to the member header.
     *
     * @param annotations annotation tokens, e.g. {@code List.of("@Override", "@SuppressWarnings(\"unused\")")}
     * @param indent      the indentation string for the member (used only in own-line mode)
     * @return the prefix string to prepend before the member header; empty string if no annotations
     */
    public String renderAnnotationPrefix(List<String> annotations, String indent) {
        if (annotations.isEmpty()) {
            return "";
        }
        if (annotationsOnOwnLine) {
            StringBuilder sb = new StringBuilder();
            for (String annotation : annotations) {
                sb.append(indent).append(annotation).append(lineEnding);
            }
            return sb.toString();
        } else {
            // Same-line style: annotations space-separated, trailing space before member header
            return String.join(" ", annotations) + " ";
        }
    }

    /**
     * Renders a complete annotated method, honoring annotation placement and brace style.
     *
     * <p>This is a convenience overload of {@link #renderMethod(String, String)} that prepends the
     * annotation block using {@link #renderAnnotationPrefix(List, String)}.
     *
     * @param annotations annotation tokens (may be empty)
     * @param header      method header, e.g. {@code "public String toString()"}
     * @param body        method body lines (without surrounding braces)
     */
    public String renderAnnotatedMethod(List<String> annotations, String header, String body) {
        if (annotations.isEmpty()) {
            return renderMemberBlock(header, body);
        }
        String annotationPrefix = renderAnnotationPrefix(annotations, memberIndent);
        if (annotationsOnOwnLine) {
            // renderMemberBlock always starts with lineEnding; skip exactly that prefix so the
            // annotation block can be prepended without double-newline before the member indent.
            String block = renderMemberBlock(header, body);
            String blockWithoutLeadingNewline = block.substring(lineEnding.length());
            return normalizeLineEndings("\n") + annotationPrefix + blockWithoutLeadingNewline;
        } else {
            // same-line: annotation is part of the header
            return renderMemberBlock(annotationPrefix + header, body);
        }
    }

    /**
     * Returns the opening-brace fragment for a method or block header, honoring brace style.
     *
     * <p>In K&R style (the Java default, {@link #openBraceSameLine()} == {@code true}) this
     * returns {@code " {"} to be appended to the header. In Allman style it returns a line ending
     * followed by the indent and {@code "{"}.
     *
     * @param indent the indentation of the surrounding member (used only in Allman style)
     */
    public String openBrace(String indent) {
        return openBraceSameLine ? " {" : lineEnding + indent + "{";
    }

    // ── Private implementation ───────────────────────────────────────────────

    private String renderMemberBlock(String header, String body) {
        return normalizeLineEndings("\n" + memberIndent + header + openBrace(memberIndent) + "\n"
                + indentLines(body, childIndent(memberIndent)) + "\n"
                + memberIndent + "}\n");
    }

    private static String inferIndentUnit(String source) {
        Matcher matcher = INDENTED_CODE.matcher(source);
        int minSpaces = Integer.MAX_VALUE;
        while (matcher.find()) {
            String indent = matcher.group(1);
            if (indent.startsWith("\t")) {
                return "\t";
            }
            int spaces = indent.length();
            if (spaces > 0) {
                minSpaces = Math.min(minSpaces, spaces);
            }
        }
        if (minSpaces == Integer.MAX_VALUE) {
            return "    ";
        }
        int unit = Math.max(2, Math.min(8, minSpaces));
        return " ".repeat(unit);
    }

    private static String inferMemberIndent(String source, String indentUnit) {
        Matcher matcher = MEMBER_LINE.matcher(source);
        while (matcher.find()) {
            String indent = matcher.group(1);
            if (!indent.isBlank()) {
                return indent;
            }
        }
        return indentUnit;
    }

    private static int inferBlankLinesBetweenMembers(String source) {
        Matcher matcher = BLANK_LINES_BETWEEN_MEMBERS.matcher(source);
        if (matcher.find()) {
            String blankBlock = matcher.group(1);
            // Count the newline characters in the captured blank block; each LF = one blank line.
            long count = blankBlock.chars().filter(c -> c == '\n').count();
            return (int) Math.max(1, count);
        }
        return 1; // default: one blank line between members
    }

    private static boolean inferStaticImportsFirst(String source) {
        Matcher staticMatcher = STATIC_IMPORT.matcher(source);
        Matcher regularMatcher = REGULAR_IMPORT.matcher(source);
        boolean hasStatic = staticMatcher.find();
        boolean hasRegular = regularMatcher.find();
        if (!hasStatic) {
            return false; // no static imports — convention doesn't apply
        }
        if (!hasRegular) {
            return true; // only static imports
        }
        return staticMatcher.start() < regularMatcher.start();
    }

    private static boolean inferAnnotationsOnOwnLine(String source) {
        if (ANNOTATION_OWN_LINE.matcher(source).find()) {
            return true;
        }
        if (ANNOTATION_SAME_LINE.matcher(source).find()) {
            return false;
        }
        return true; // default: own line (standard Java convention)
    }

    private static boolean inferOpenBraceSameLine(String source) {
        boolean hasSameLine = OPEN_BRACE_SAME_LINE_PATTERN.matcher(source).find();
        boolean hasNewLine = OPEN_BRACE_NEW_LINE_PATTERN.matcher(source).find();
        if (hasSameLine && !hasNewLine) {
            return true;
        }
        if (!hasSameLine && hasNewLine) {
            return false;
        }
        return true; // default: K&R (standard Java convention)
    }
}
