package io.serena.javarefactor.ast;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;

import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import java.nio.file.Path;

/**
 * Computes the exact source span of the simple-name token to edit for a given AST node.
 *
 * <p>Spans are anchored on {@link SourcePositions} (char offsets into the same {@code source} that
 * {@link IdentifierSpan#fromOffsets} indexes), never on an unbounded text search. This keeps offsets correct for
 * non-BMP identifiers (surrogate pairs) and avoids matching the name inside comments or string/char literals. The
 * declaration kinds (class/method/variable) — for which javac does not expose the name-token offset — use a
 * comment-and-literal-aware forward token scan bounded by child AST positions.
 */
public final class IdentifierSpanFinder {

    public IdentifierSpan find(Path file, CompilationUnitTree unit, SourcePositions positions, Tree tree, Element element, CharSequence source) {
        long span = locate(unit, positions, tree, element, source);
        if (span < 0) {
            return null;
        }
        int start = (int) (span >>> 32);
        int length = (int) (span & 0xFFFFFFFFL);
        return IdentifierSpan.fromOffsets(file, unit, source, start, start + length);
    }

    /**
     * Returns the token span packed as {@code (start << 32) | length}, or {@code -1} if no editable simple-name token
     * applies. Packing avoids allocating a holder for the common path.
     */
    private long locate(CompilationUnitTree unit, SourcePositions positions, Tree tree, Element element, CharSequence source) {
        if (tree instanceof MethodInvocationTree invocation) {
            // The invocation node spans `recv.foo(args)`; the editable identifier is the method-select token, not the
            // receiver. Descend into the method select so `foo.foo()` selects the method `foo`, not the receiver `foo`.
            return locate(unit, positions, invocation.getMethodSelect(), element, source);
        }
        if (tree instanceof IdentifierTree identifier) {
            return identifierToken(unit, positions, identifier, identifier.getName().toString(), source);
        }
        if (tree instanceof MemberSelectTree memberSelect) {
            return trailingToken(unit, positions, memberSelect, memberSelect.getIdentifier().toString(), source);
        }
        if (tree instanceof MemberReferenceTree memberReference) {
            return memberReferenceToken(unit, positions, memberReference, element, source);
        }
        if (tree instanceof NewClassTree newClass) {
            return typeToken(unit, positions, newClass.getIdentifier(), element, source);
        }
        if (tree instanceof ImportTree importTree) {
            return importToken(unit, positions, importTree, source);
        }
        if (tree instanceof ClassTree classTree) {
            return classDeclarationToken(unit, positions, classTree, source);
        }
        if (tree instanceof MethodTree methodTree) {
            return methodDeclarationToken(unit, positions, methodTree, element, source);
        }
        if (tree instanceof com.sun.source.tree.VariableTree variableTree) {
            return variableDeclarationToken(unit, positions, variableTree, source);
        }
        if (tree instanceof ParameterizedTypeTree parameterized) {
            // The raw type span, not the type-argument span.
            return typeToken(unit, positions, parameterized.getType(), element, source);
        }
        return -1;
    }

    /** A bare identifier: token = [start, start + nameLen); validated against the name and identifier boundaries. */
    private long identifierToken(CompilationUnitTree unit, SourcePositions positions, Tree tree, String name, CharSequence source) {
        long treeStart = positions.getStartPosition(unit, tree);
        if (treeStart < 0) {
            return -1;
        }
        return validatedToken(source, (int) treeStart, name);
    }

    /** A trailing-name token (member select / member reference): anchor on the END position and back up by nameLen. */
    private long trailingToken(CompilationUnitTree unit, SourcePositions positions, Tree tree, String name, CharSequence source) {
        long treeEnd = positions.getEndPosition(unit, tree);
        if (treeEnd < 0) {
            return -1;
        }
        int nameLen = name.length();
        int start = (int) treeEnd - nameLen;
        if (start < 0) {
            return -1;
        }
        return validatedToken(source, start, name);
    }

    /**
     * `Type::method` — the name token is trailing, anchor on end. `Type::new` — the editable identifier is the
     * qualifier type's simple name (the {@code new} keyword is not renamed), so descend into the qualifier.
     */
    private long memberReferenceToken(CompilationUnitTree unit, SourcePositions positions, MemberReferenceTree tree, Element element, CharSequence source) {
        Name referenced = tree.getName();
        if (referenced != null && referenced.contentEquals("new")) {
            return typeToken(unit, positions, tree.getQualifierExpression(), element, source);
        }
        String name = referenced != null ? referenced.toString() : simpleName(element);
        return trailingToken(unit, positions, tree, name, source);
    }

    /**
     * The simple-name token of a type written in a {@code new}/type position. Descends through
     * ParameterizedType -> AnnotatedType -> MemberSelect/Identifier to the simple-name token and anchors on AST
     * positions, so generics, annotations, and qualifiers do not perturb the offset.
     */
    private long typeToken(CompilationUnitTree unit, SourcePositions positions, Tree typeTree, Element element, CharSequence source) {
        if (typeTree instanceof ParameterizedTypeTree parameterized) {
            return typeToken(unit, positions, parameterized.getType(), element, source);
        }
        if (typeTree instanceof AnnotatedTypeTree annotated) {
            return typeToken(unit, positions, annotated.getUnderlyingType(), element, source);
        }
        if (typeTree instanceof MemberSelectTree memberSelect) {
            return trailingToken(unit, positions, memberSelect, memberSelect.getIdentifier().toString(), source);
        }
        if (typeTree instanceof IdentifierTree identifier) {
            return identifierToken(unit, positions, identifier, identifier.getName().toString(), source);
        }
        return -1;
    }

    /**
     * IMPORT: the imported simple name is the trailing identifier of the qualified name. Anchor on the qualified
     * identifier's end position and back up by the simple-name length (boundary-precise rather than a raw last-match).
     */
    private long importToken(CompilationUnitTree unit, SourcePositions positions, ImportTree tree, CharSequence source) {
        Tree qualified = tree.getQualifiedIdentifier();
        if (qualified instanceof MemberSelectTree memberSelect) {
            return trailingToken(unit, positions, memberSelect, memberSelect.getIdentifier().toString(), source);
        }
        if (qualified instanceof IdentifierTree identifier) {
            return identifierToken(unit, positions, identifier, identifier.getName().toString(), source);
        }
        return -1;
    }

    /**
     * Class/interface/enum/record/annotation declaration. javac does not expose the name-token offset; the name
     * follows modifiers/annotations and the {@code class}/{@code interface}/{@code enum}/{@code record}/{@code @interface}
     * keyword. Compute a lower bound past the modifiers tree and the type keyword, then scan forward (comment- and
     * literal-aware) for the simple-name token.
     */
    private long classDeclarationToken(CompilationUnitTree unit, SourcePositions positions, ClassTree tree, CharSequence source) {
        String name = tree.getSimpleName().toString();
        if (name.isEmpty()) {
            return -1;
        }
        int lower = (int) Math.max(0, positions.getStartPosition(unit, tree));
        long modsEnd = positions.getEndPosition(unit, tree.getModifiers());
        if (modsEnd >= 0 && modsEnd > lower) {
            lower = (int) modsEnd;
        }
        long treeEnd = positions.getEndPosition(unit, tree);
        // Skip the type keyword(s) preceding the name: class / interface / enum / record / @interface.
        int afterKeyword = skipTypeKeyword(source, lower, (int) treeEnd);
        return scanForName(source, afterKeyword, (int) treeEnd, name);
    }

    /**
     * Method or constructor declaration. The name follows modifiers/annotations and (for methods) the return-type
     * tree. Constructors (javac name {@code <init>}) edit the enclosing type's simple name; their lower bound is past
     * the modifiers (there is no return type).
     */
    private long methodDeclarationToken(CompilationUnitTree unit, SourcePositions positions, MethodTree tree, Element element, CharSequence source) {
        boolean constructor = tree.getName().contentEquals("<init>");
        String name = constructor ? enclosingTypeName(element) : tree.getName().toString();
        if (name.isEmpty()) {
            return -1;
        }
        int lower = (int) Math.max(0, positions.getStartPosition(unit, tree));
        long modsEnd = positions.getEndPosition(unit, tree.getModifiers());
        if (modsEnd >= 0 && modsEnd > lower) {
            lower = (int) modsEnd;
        }
        Tree returnType = tree.getReturnType();
        if (returnType != null) {
            long returnEnd = positions.getEndPosition(unit, returnType);
            if (returnEnd >= 0 && returnEnd > lower) {
                lower = (int) returnEnd;
            }
        }
        long treeEnd = positions.getEndPosition(unit, tree);
        return scanForName(source, lower, (int) treeEnd, name);
    }

    /** Variable / field / parameter declaration. The name follows modifiers/annotations and the variable type tree. */
    private long variableDeclarationToken(CompilationUnitTree unit, SourcePositions positions, com.sun.source.tree.VariableTree tree, CharSequence source) {
        String name = tree.getName().toString();
        if (name.isEmpty()) {
            return -1;
        }
        int lower = (int) Math.max(0, positions.getStartPosition(unit, tree));
        ModifiersTree modifiers = tree.getModifiers();
        if (modifiers != null) {
            long modsEnd = positions.getEndPosition(unit, modifiers);
            if (modsEnd >= 0 && modsEnd > lower) {
                lower = (int) modsEnd;
            }
        }
        Tree type = tree.getType();
        if (type != null) {
            long typeEnd = positions.getEndPosition(unit, type);
            if (typeEnd >= 0 && typeEnd > lower) {
                lower = (int) typeEnd;
            }
        }
        long treeEnd = positions.getEndPosition(unit, tree);
        return scanForName(source, lower, (int) treeEnd, name);
    }

    /** The simple name of the type that encloses a constructor element, falling back to the element's own name. */
    private static String enclosingTypeName(Element element) {
        Element enclosing = element == null ? null : element.getEnclosingElement();
        if (enclosing != null && !enclosing.getSimpleName().isEmpty()) {
            return enclosing.getSimpleName().toString();
        }
        return simpleName(element);
    }

    private static String simpleName(Element element) {
        return element == null ? "" : element.getSimpleName().toString();
    }

    /**
     * Validates that {@code source[start, start+nameLen)} equals {@code name} and that both sides are identifier
     * boundaries, returning the packed token span or {@code -1}. {@code name.length()} counts UTF-16 code units, which
     * matches both {@code source.charAt} indexing and {@code SourcePositions} char offsets, so surrogate pairs in a
     * non-BMP identifier stay aligned.
     */
    private static long validatedToken(CharSequence source, int start, String name) {
        int nameLen = name.length();
        if (start < 0 || start + nameLen > source.length()) {
            return -1;
        }
        if (!matchesAt(source, start, name)) {
            return -1;
        }
        if (!isIdentifierBoundary(source, start - 1) || !isIdentifierBoundary(source, start + nameLen)) {
            return -1;
        }
        return pack(start, nameLen);
    }

    /**
     * Forward scan from {@code lower} to {@code end} for the {@code name} token, skipping {@code //} line comments,
     * {@code /* *}{@code /} block comments, and {@code "..."}/{@code '...'} literals (so the name is never matched
     * inside them) and honoring identifier boundaries. Surrogate pairs are not split: the match is the exact name
     * string with boundary checks, and the cursor advances by whole code points while skipping.
     */
    private static long scanForName(CharSequence source, int lower, int end, String name) {
        int boundedEnd = Math.min(source.length(), end);
        int i = Math.max(0, lower);
        int nameLen = name.length();
        while (i <= boundedEnd - nameLen) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < boundedEnd) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    i = skipLineComment(source, i + 2, boundedEnd);
                    continue;
                }
                if (next == '*') {
                    i = skipBlockComment(source, i + 2, boundedEnd);
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                i = skipLiteral(source, i + 1, boundedEnd, c);
                continue;
            }
            if (c == name.charAt(0) && matchesAt(source, i, name)
                    && isIdentifierBoundary(source, i - 1) && isIdentifierBoundary(source, i + nameLen)) {
                return pack(i, nameLen);
            }
            i += Character.charCount(Character.codePointAt(source, i));
        }
        return -1;
    }

    /** Skip leading type keyword(s) preceding the declared name (class/interface/enum/record/@interface). */
    private static int skipTypeKeyword(CharSequence source, int from, int end) {
        int boundedEnd = Math.min(source.length(), end);
        int i = Math.max(0, from);
        while (i < boundedEnd) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < boundedEnd) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    i = skipLineComment(source, i + 2, boundedEnd);
                    continue;
                }
                if (next == '*') {
                    i = skipBlockComment(source, i + 2, boundedEnd);
                    continue;
                }
            }
            if (Character.isWhitespace(c) || c == '@') {
                i++;
                continue;
            }
            // First non-comment/non-whitespace token is the type keyword; skip it (and any '@interface' '@').
            int wordEnd = i;
            while (wordEnd < boundedEnd && (Character.isLetterOrDigit(source.charAt(wordEnd)))) {
                wordEnd++;
            }
            return wordEnd > i ? wordEnd : i + 1;
        }
        return i;
    }

    private static int skipLineComment(CharSequence source, int from, int end) {
        int i = from;
        while (i < end && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(CharSequence source, int from, int end) {
        int i = from;
        while (i + 1 < end) {
            if (source.charAt(i) == '*' && source.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return end;
    }

    /** Skip a string ({@code "}) or char ({@code '}) literal body and its closing quote, honoring backslash escapes. */
    private static int skipLiteral(CharSequence source, int from, int end, char quote) {
        int i = from;
        while (i < end) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return end;
    }

    private static boolean matchesAt(CharSequence source, int start, String name) {
        int nameLen = name.length();
        if (start + nameLen > source.length()) {
            return false;
        }
        for (int k = 0; k < nameLen; k++) {
            if (source.charAt(start + k) != name.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierBoundary(CharSequence source, int index) {
        if (index < 0 || index >= source.length()) {
            return true;
        }
        // Index may land on a low surrogate when checking the char just past a non-BMP identifier; treat the full code
        // point so a surrogate pair is not mistaken for a boundary.
        int codePoint = Character.codePointAt(source, index);
        return !Character.isJavaIdentifierPart(codePoint);
    }

    private static long pack(int start, int length) {
        return ((long) start << 32) | (length & 0xFFFFFFFFL);
    }
}
