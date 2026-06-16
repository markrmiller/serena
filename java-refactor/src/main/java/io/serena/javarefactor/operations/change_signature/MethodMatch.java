package io.serena.javarefactor.operations.change_signature;

import io.serena.javarefactor.compiler.SemanticIndex;
import java.util.List;

/**
 * The resolved declaration the change-signature / introduce-parameter rewrite targets: source offsets of its header
 * ({@code start}/{@code nameStart}/{@code headerEnd}), the leading {@code modifiers} text, the {@code returnType} (empty
 * for constructors), the {@code name}, the current {@code parameters}, the backing javac {@link SemanticIndex.SemanticMethod},
 * and whether it is a {@code constructor}.
 */
public record MethodMatch(
        int start, int nameStart, int headerEnd, String modifiers, String returnType, String name,
        List<ParameterSpec> parameters, SemanticIndex.SemanticMethod semantic, boolean constructor) {
    /** End offset (exclusive) of the method body, or the source length when the body range is unavailable. */
    public int bodyEnd(String source) {
        return semantic.bodyRange() == null ? source.length() : semantic.bodyRange().end() - 1;
    }
}
