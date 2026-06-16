package io.serena.javarefactor.operations.hierarchy;

import io.serena.javarefactor.shared.SourceLocation;
import java.util.List;
import java.util.Set;

/**
 * A Java type summary used by V2 hierarchy-aware refactor planners.
 *
 * <p>{@code directSupertypes} is the union of the (optional) {@code superclass} and the implemented
 * {@code interfaces}; it is retained because most graph walks (subtype/supertype closure) only care about the union.
 * The split fields let interface-aware refactors (extract interface, pull up to interface) distinguish a class parent
 * from interface parents without re-deriving it. {@code permittedSubtypes} is populated for {@code sealed} types.
 */
public record TypeDescriptor(
        String qualifiedName,
        String simpleName,
        Set<String> directSupertypes,
        Set<String> permittedSubtypes,
        List<MemberDescriptor> members,
        Set<String> modifiers,
        SourceLocation location,
        List<TypeDescriptor> nestedTypes,
        String superclass,
        Set<String> interfaces) {

    public TypeDescriptor {
        directSupertypes = Set.copyOf(directSupertypes);
        permittedSubtypes = Set.copyOf(permittedSubtypes);
        members = List.copyOf(members);
        modifiers = Set.copyOf(modifiers);
        nestedTypes = List.copyOf(nestedTypes);
        interfaces = Set.copyOf(interfaces);
        superclass = superclass == null ? "" : superclass;
    }

    /**
     * Backward-compatible constructor for callers that only supply the union of supertypes. {@code superclass} is left
     * empty and {@code interfaces} defaults to the supplied supertype union.
     */
    public TypeDescriptor(
            String qualifiedName,
            String simpleName,
            Set<String> directSupertypes,
            Set<String> permittedSubtypes,
            List<MemberDescriptor> members,
            Set<String> modifiers,
            SourceLocation location,
            List<TypeDescriptor> nestedTypes) {
        this(qualifiedName, simpleName, directSupertypes, permittedSubtypes, members, modifiers, location, nestedTypes, "", Set.copyOf(directSupertypes));
    }

    public boolean hasModifier(String modifier) {
        return modifiers.contains(modifier);
    }
}
