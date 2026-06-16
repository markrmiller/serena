package io.serena.javarefactor.operations.hierarchy;

import io.serena.javarefactor.shared.SourceLocation;
import java.util.List;
import java.util.Set;

/**
 * A fully-modelled member (method, constructor, or field) used by V2 hierarchy-aware refactor planners.
 *
 * <p>Beyond the raw {@code name}/{@code kind}/{@code modifiers}/{@code location} carried by earlier revisions, a
 * descriptor now records everything an override-equivalence or signature-change decision needs:
 * <ul>
 *   <li>{@code semanticKey} — the cross-compiler-task-stable canonical key (see
 *       {@code io.serena.javarefactor.ast.SemanticKey#canonical()}), e.g. {@code com.acme.Foo#bar(java.lang.String)}.
 *       This is the identity used to look a member up inside an override group.</li>
 *   <li>{@code overrideKey} — the JLS override identity (method name + erased parameter types), e.g.
 *       {@code bar(java.lang.String,int)}. Two methods declared in subtype-related types belong to the same override
 *       group iff their {@code overrideKey}s match (covariant returns and generic substitution collapse to the same
 *       erased signature; an overload with different parameters does not).</li>
 *   <li>{@code returnType} — declared return type for methods, declared type for fields.</li>
 *   <li>{@code parameters} — ordered parameter model (declared + erased types and names).</li>
 *   <li>{@code thrownTypes} — declared checked/unchecked throws clause.</li>
 *   <li>{@code annotations} — annotation type names declared on the member.</li>
 *   <li>{@code typeParameters} — generic type-parameter declarations (e.g. {@code <T>}).</li>
 *   <li>{@code visibility} — normalized access level: {@code public}/{@code protected}/{@code private}/{@code package}.</li>
 * </ul>
 */
public record MemberDescriptor(
        String name,
        String kind,
        Set<String> modifiers,
        SourceLocation location,
        String semanticKey,
        String overrideKey,
        String returnType,
        List<ParameterModel> parameters,
        List<String> thrownTypes,
        List<String> annotations,
        List<String> typeParameters,
        String visibility) {

    /** A single formal parameter: its source name plus its declared and erased types. */
    public record ParameterModel(String name, String type, String erasedType) {}

    public MemberDescriptor {
        modifiers = Set.copyOf(modifiers);
        parameters = List.copyOf(parameters);
        thrownTypes = List.copyOf(thrownTypes);
        annotations = List.copyOf(annotations);
        typeParameters = List.copyOf(typeParameters);
    }

    /**
     * Backward-compatible constructor used by call sites that only know name/kind/modifiers/location. The richer
     * fields default to empty; {@code visibility} is derived from {@code modifiers}.
     */
    public MemberDescriptor(String name, String kind, Set<String> modifiers, SourceLocation location) {
        this(name, kind, modifiers, location, "", "", "", List.of(), List.of(), List.of(), List.of(), visibilityOf(modifiers));
    }

    public boolean hasModifier(String modifier) {
        return modifiers.contains(modifier);
    }

    /** True for methods/constructors that can participate in overriding (instance, non-private). */
    public boolean isMethod() {
        return "method".equals(kind);
    }

    /** Normalizes the JLS access level implied by a modifier set. */
    public static String visibilityOf(Set<String> modifiers) {
        if (modifiers.contains("public")) {
            return "public";
        }
        if (modifiers.contains("protected")) {
            return "protected";
        }
        if (modifiers.contains("private")) {
            return "private";
        }
        return "package";
    }
}
