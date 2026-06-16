package io.serena.javarefactor.operations.change_signature;

import io.serena.javarefactor.compiler.SemanticIndex;
import java.util.List;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * G006 architecture unit: the parsed/normalized signature shape and parameter plan. Encapsulates everything about a
 * method's signature that does not require call-site or override-group traversal: mapping desired parameters back to
 * current parameters ({@link #oldIndex}), javac-resolved declared types ({@link #resolvedReturnType} /
 * {@link #resolvedParamType}), qualified-aware type equivalence ({@link #typeEquivalent}), annotation/{@code final}
 * prefix handling, and rendering the declaration header and parameter list. Pure with respect to the project model: it
 * only inspects the supplied {@link MethodMatch} / {@link ParameterSpec} data and javac {@link TypeMirror}s already
 * resolved on the declaration, so it is unit-testable in isolation.
 */
public final class MethodSignatureModel {

    private MethodSignatureModel() {
    }

    public static int parameterIndex(List<ParameterSpec> parameters, String name) {
        for (int index = 0; index < parameters.size(); index++) {
            if (parameters.get(index).name().equals(name)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Maps a desired parameter back to the 0-based index of the current parameter it retains, or {@code -1} when it is a
     * newly added parameter. An explicit {@code oldIndex} wins; otherwise the parameter is matched by name; otherwise,
     * when the arity is unchanged, the positional index is used.
     */
    public static int oldIndex(ParameterSpec parameter, MethodMatch declaration, int desiredIndex, List<ParameterSpec> desired) {
        if (parameter.oldIndex() != null) {
            return parameter.oldIndex();
        }
        int byName = parameterIndex(declaration.parameters(), parameter.name());
        if (byName >= 0) {
            return byName;
        }
        if (desired.size() == declaration.parameters().size() && desiredIndex >= 0 && desiredIndex < declaration.parameters().size()) {
            return desiredIndex;
        }
        return -1;
    }

    /**
     * G008: type equivalence that does not conflate distinct fully-qualified types. When both sides are fully-qualified
     * and differ (e.g. {@code a.Foo} vs {@code b.Foo}) they are NOT equivalent. Simple-name equivalence is only used when
     * at least one side is unqualified (an unqualified name relies on an import resolving to the qualified one).
     */
    public static boolean typeEquivalent(String left, String right) {
        String normalizedLeft = left == null ? "" : left.trim();
        String normalizedRight = right == null ? "" : right.trim();
        if (normalizedLeft.equals(normalizedRight)) {
            return true;
        }
        if (isQualifiedType(normalizedLeft) && isQualifiedType(normalizedRight)) {
            return false;
        }
        return simpleTypeName(normalizedLeft).equals(simpleTypeName(normalizedRight));
    }

    /** Whether {@code type}'s raw (generics- and array-stripped) name is package-qualified. */
    public static boolean isQualifiedType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        int generic = type.indexOf('<');
        String raw = generic >= 0 ? type.substring(0, generic) : type;
        int array = raw.indexOf('[');
        if (array >= 0) {
            raw = raw.substring(0, array);
        }
        return raw.contains(".");
    }

    public static String simpleTypeName(String type) {
        int generic = type.indexOf('<');
        String raw = generic >= 0 ? type.substring(0, generic) : type;
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    /** The selected declaration's return type, preferring the resolved javac TypeMirror (fully qualified) over source text. */
    public static String resolvedReturnType(MethodMatch declaration) {
        ExecutableType memberType = declaration.semantic().memberType();
        if (memberType != null) {
            String resolved = safeTypeString(memberType.getReturnType());
            if (resolved != null) {
                return resolved;
            }
        }
        return declaration.returnType();
    }

    /** The declaration's {@code index}-th parameter type, preferring the resolved javac TypeMirror over source text. */
    public static String resolvedParamType(MethodMatch declaration, int index) {
        ExecutableType memberType = declaration.semantic().memberType();
        if (memberType != null) {
            List<? extends TypeMirror> parameterTypes = memberType.getParameterTypes();
            if (index >= 0 && index < parameterTypes.size()) {
                String resolved = safeTypeString(parameterTypes.get(index));
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return index >= 0 && index < declaration.parameters().size() ? declaration.parameters().get(index).type() : null;
    }

    private static String safeTypeString(TypeMirror type) {
        if (type == null) {
            return null;
        }
        TypeKind kind = type.getKind();
        if (kind == TypeKind.NONE || kind == TypeKind.NULL || kind == TypeKind.VOID || kind == TypeKind.ERROR) {
            return null;
        }
        return type.toString();
    }

    /**
     * The resolved (fully-qualified) parameter types for the desired plan, in order, used to evaluate method-reference
     * conformance under the new signature. A retained parameter contributes the spelling the caller supplied for it (the
     * desired type), so the SAM check sees exactly the type the rewritten declaration will carry.
     */
    public static List<String> desiredParameterTypes(List<ParameterSpec> desired) {
        return desired.stream().map(ParameterSpec::type).toList();
    }

    // --- rendering -------------------------------------------------------------------------------------------------

    public static String renderSignature(MethodMatch declaration, String newName, String returnType, List<ParameterSpec> desired, TypeRenderer imports, String source) {
        String suffix = signatureSuffix(source, declaration);
        if (declaration.constructor()) {
            return declaration.modifiers() + declaration.name() + "(" + renderParameters(desired, declaration, imports) + ")" + suffix;
        }
        return declaration.modifiers() + imports.simpleType(returnType) + " " + newName + "(" + renderParameters(desired, declaration, imports) + ")" + suffix;
    }

    private static String signatureSuffix(String source, MethodMatch declaration) {
        int closeParen = source.lastIndexOf(')', declaration.headerEnd());
        if (closeParen < declaration.start()) {
            return "";
        }
        String suffix = source.substring(closeParen + 1, declaration.headerEnd()).trim();
        return suffix.isEmpty() ? "" : " " + suffix;
    }

    public static String renderParameters(List<ParameterSpec> parameters, MethodMatch declaration, TypeRenderer imports) {
        java.util.List<String> rendered = new java.util.ArrayList<>();
        for (int desiredIndex = 0; desiredIndex < parameters.size(); desiredIndex++) {
            ParameterSpec parameter = parameters.get(desiredIndex);
            String prefix = parameterPrefixFor(parameter, declaration, desiredIndex, parameters);
            rendered.add(prefix + imports.simpleType(parameter.type()) + " " + parameter.name());
        }
        return String.join(", ", rendered);
    }

    /**
     * Resolves the leading annotation/modifier prefix that must travel with a rendered parameter. A desired parameter
     * that already carries its own prefix keeps it; otherwise the prefix is inherited from the old parameter it maps to
     * so annotations survive add/remove/reorder/retype.
     */
    public static String parameterPrefixFor(ParameterSpec parameter, MethodMatch declaration, int desiredIndex, List<ParameterSpec> desired) {
        if (parameter.prefix() != null && !parameter.prefix().isBlank()) {
            return parameter.prefix();
        }
        int oldIndex = oldIndex(parameter, declaration, desiredIndex, desired);
        if (oldIndex >= 0 && oldIndex < declaration.parameters().size()) {
            String inherited = declaration.parameters().get(oldIndex).prefix();
            return inherited == null ? "" : inherited;
        }
        return "";
    }

    /**
     * Splits a parameter's declared text (annotations + modifiers + type, with the trailing name already stripped) into
     * a leading annotation/{@code final} {@code prefix} and the bare {@code coreType}. Robust to annotation arguments
     * ({@code @Size(min = 1)}); never throws.
     */
    public static ParameterPrefix splitParameterPrefix(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return new ParameterPrefix("", rawType == null ? "" : rawType);
        }
        StringBuilder prefix = new StringBuilder();
        int index = 0;
        int length = rawType.length();
        while (index < length) {
            while (index < length && Character.isWhitespace(rawType.charAt(index))) {
                index++;
            }
            if (index >= length) {
                break;
            }
            char current = rawType.charAt(index);
            if (current == '@') {
                int tokenStart = index;
                index++;
                while (index < length && (Character.isJavaIdentifierPart(rawType.charAt(index)) || rawType.charAt(index) == '.')) {
                    index++;
                }
                int lookahead = index;
                while (lookahead < length && Character.isWhitespace(rawType.charAt(lookahead))) {
                    lookahead++;
                }
                if (lookahead < length && rawType.charAt(lookahead) == '(') {
                    int depth = 0;
                    index = lookahead;
                    do {
                        char delimiter = rawType.charAt(index);
                        if (delimiter == '(') {
                            depth++;
                        } else if (delimiter == ')') {
                            depth--;
                        }
                        index++;
                    } while (index < length && depth > 0);
                }
                appendToken(prefix, rawType.substring(tokenStart, index));
            } else if (Character.isJavaIdentifierStart(current)) {
                int tokenStart = index;
                while (index < length && Character.isJavaIdentifierPart(rawType.charAt(index))) {
                    index++;
                }
                String word = rawType.substring(tokenStart, index);
                if (word.equals("final")) {
                    appendToken(prefix, "final");
                } else {
                    index = tokenStart;
                    break;
                }
            } else {
                break;
            }
        }
        String coreType = rawType.substring(Math.min(index, length)).trim();
        if (coreType.isEmpty()) {
            return new ParameterPrefix("", rawType.trim());
        }
        return new ParameterPrefix(prefix.length() == 0 ? "" : prefix + " ", coreType);
    }

    private static void appendToken(StringBuilder prefix, String token) {
        if (prefix.length() > 0) {
            prefix.append(' ');
        }
        prefix.append(token.trim());
    }

    public record ParameterPrefix(String prefix, String coreType) {
    }

    /** Renders a source type spelling into its (possibly import-simplified) declaration form. */
    public interface TypeRenderer {
        String simpleType(String type);
    }
}
