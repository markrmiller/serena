package io.serena.javarefactor.operations.hierarchy;

import io.serena.javarefactor.shared.SourceLocation;
import io.serena.javarefactor.shared.StructuredRefusal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared hierarchy index used by V2 Java refactor planners. */
public final class TypeHierarchyIndex {
    private final Map<String, TypeDescriptor> typesByName = new LinkedHashMap<>();
    private final Map<String, Set<String>> typesBySimpleName = new LinkedHashMap<>();
    private final Map<String, Set<String>> subtypesByName = new LinkedHashMap<>();
    // semanticKey -> the full set of semanticKeys in its true override group (from Elements.overrides). A member that
    // is not in any multi-member group has no entry here.
    private final Map<String, Set<String>> overrideGroupByKey = new LinkedHashMap<>();
    // semanticKey -> its owning MemberDescriptor, so a group of keys can be resolved back to descriptors.
    private final Map<String, MemberDescriptor> memberByKey = new LinkedHashMap<>();

    public TypeHierarchyIndex(Collection<TypeDescriptor> descriptors) {
        this(descriptors, List.of());
    }

    /**
     * @param descriptors    the project's type descriptors
     * @param overrideGroups precomputed true override groups (each a set of member {@code semanticKey}s), typically
     *                       produced by {@link OverrideGroupComputer} from {@code Elements.overrides}. When empty, an
     *                       erased-signature fallback is used (see {@link #overrideGroup}).
     */
    public TypeHierarchyIndex(Collection<TypeDescriptor> descriptors, Collection<? extends Collection<String>> overrideGroups) {
        for (TypeDescriptor descriptor : descriptors) {
            indexType(descriptor);
        }
        for (TypeDescriptor descriptor : typesByName.values()) {
            for (String supertype : descriptor.directSupertypes()) {
                subtypesByName.computeIfAbsent(supertype, ignored -> new LinkedHashSet<>()).add(descriptor.qualifiedName());
            }
            for (MemberDescriptor member : descriptor.members()) {
                if (member.semanticKey() != null && !member.semanticKey().isEmpty()) {
                    memberByKey.putIfAbsent(member.semanticKey(), member);
                }
            }
        }
        for (Collection<String> group : overrideGroups) {
            Set<String> shared = new LinkedHashSet<>(group);
            for (String key : group) {
                overrideGroupByKey.put(key, shared);
            }
        }
    }

    public Optional<TypeDescriptor> type(String qualifiedName) {
        return Optional.ofNullable(typesByName.get(qualifiedName));
    }

    public Optional<TypeDescriptor> resolveType(String simpleOrQualifiedName) {
        return resolveQualifiedName(simpleOrQualifiedName).flatMap(this::type);
    }

    public Optional<String> resolveQualifiedName(String simpleOrQualifiedName) {
        if (typesByName.containsKey(simpleOrQualifiedName)) {
            return Optional.of(simpleOrQualifiedName);
        }
        Set<String> matches = typesBySimpleName.getOrDefault(simpleOrQualifiedName, Set.of());
        if (matches.size() == 1) {
            return Optional.of(matches.iterator().next());
        }
        return Optional.empty();
    }

    public Set<String> directSupertypes(String qualifiedName) {
        return type(qualifiedName).map(TypeDescriptor::directSupertypes).orElse(Set.of());
    }

    public Set<String> directSupertypesOf(String simpleOrQualifiedName) {
        return resolveQualifiedName(simpleOrQualifiedName).map(this::directSupertypes).orElse(Set.of());
    }

    public Set<String> allSupertypes(String qualifiedName) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(directSupertypes(qualifiedName));
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (result.add(next)) {
                queue.addAll(directSupertypes(next));
            }
        }
        return result;
    }

    public Set<String> directSubtypes(String qualifiedName) {
        return subtypesByName.getOrDefault(qualifiedName, Set.of());
    }

    public Set<String> directSubtypesOf(String simpleOrQualifiedName) {
        return resolveQualifiedName(simpleOrQualifiedName).map(this::directSubtypes).orElse(Set.of());
    }

    public Set<String> allSubtypes(String qualifiedName) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(directSubtypes(qualifiedName));
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (result.add(next)) {
                queue.addAll(directSubtypes(next));
            }
        }
        return result;
    }

    public boolean isDirectSupertype(String subtypeName, String supertypeName) {
        Optional<String> subtype = resolveQualifiedName(subtypeName);
        Optional<String> supertype = resolveQualifiedName(supertypeName);
        return subtype.isPresent() && supertype.isPresent() && directSupertypes(subtype.get()).contains(supertype.get());
    }

    public Set<String> permittedSubtypes(String qualifiedName) {
        return type(qualifiedName).map(TypeDescriptor::permittedSubtypes).orElse(Set.of());
    }

    public List<MemberDescriptor> members(String qualifiedName) {
        return type(qualifiedName).map(TypeDescriptor::members).orElse(List.of());
    }

    public Optional<SourceLocation> sourceLocation(String simpleOrQualifiedName) {
        return resolveType(simpleOrQualifiedName).map(TypeDescriptor::location);
    }

    public List<MemberDescriptor> membersNamed(String simpleOrQualifiedName, String memberName) {
        return resolveType(simpleOrQualifiedName)
                .map(type -> type.members().stream().filter(member -> member.name().equals(memberName)).toList())
                .orElse(List.of());
    }

    /**
     * The true override group of every method named {@code memberName} declared on {@code simpleOrQualifiedName}: the
     * set of methods connected to it through the JLS "overrides" relation.
     *
     * <p>When precomputed groups are present (the compiler path, via {@link OverrideGroupComputer}), grouping uses
     * {@code Elements.overrides} identity, so an overload is excluded while a covariant-return or generic-substitution
     * override is included. When no precomputed groups exist (descriptors built without a compiler), it falls back to
     * matching the erased {@link MemberDescriptor#overrideKey()} across the type's supertype/subtype closure, which is
     * the same name+erased-parameter-types identity.
     */
    public List<MemberDescriptor> overrideGroup(String simpleOrQualifiedName, String memberName) {
        Optional<String> owner = resolveQualifiedName(simpleOrQualifiedName);
        if (owner.isEmpty()) {
            return List.of();
        }
        List<MemberDescriptor> declared = new ArrayList<>();
        for (MemberDescriptor member : members(owner.get())) {
            if (member.name().equals(memberName) && "method".equals(member.kind())) {
                declared.add(member);
            }
        }
        if (declared.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<MemberDescriptor> result = new LinkedHashSet<>();
        for (MemberDescriptor seed : declared) {
            result.addAll(overrideGroupOf(seed, owner.get()));
        }
        return new ArrayList<>(result);
    }

    /**
     * The true override group containing the member with the given {@code semanticKey}, including the member itself.
     * Returns a singleton (or empty) when the member participates in no multi-member group.
     */
    public List<MemberDescriptor> overrideGroupForKey(String semanticKey) {
        Set<String> group = overrideGroupByKey.get(semanticKey);
        if (group == null) {
            MemberDescriptor self = memberByKey.get(semanticKey);
            return self == null ? List.of() : List.of(self);
        }
        List<MemberDescriptor> result = new ArrayList<>();
        for (String key : group) {
            MemberDescriptor member = memberByKey.get(key);
            if (member != null) {
                result.add(member);
            }
        }
        return result;
    }

    /** True iff the member identified by {@code semanticKey} overrides or is overridden by another project method. */
    public boolean participatesInOverride(String semanticKey) {
        return overrideGroupByKey.containsKey(semanticKey);
    }

    private List<MemberDescriptor> overrideGroupOf(MemberDescriptor seed, String ownerQualifiedName) {
        if (seed.semanticKey() != null && overrideGroupByKey.containsKey(seed.semanticKey())) {
            return overrideGroupForKey(seed.semanticKey());
        }
        // Fallback: no compiler-derived groups. Group by erased override identity across the supertype/subtype closure.
        String key = fallbackOverrideKey(seed);
        LinkedHashSet<String> relatedTypes = new LinkedHashSet<>();
        relatedTypes.add(ownerQualifiedName);
        relatedTypes.addAll(allSupertypes(ownerQualifiedName));
        relatedTypes.addAll(allSubtypes(ownerQualifiedName));
        List<MemberDescriptor> result = new ArrayList<>();
        for (String relatedType : relatedTypes) {
            for (MemberDescriptor member : members(relatedType)) {
                if ("method".equals(member.kind()) && fallbackOverrideKey(member).equals(key)) {
                    result.add(member);
                }
            }
        }
        return result;
    }

    private static String fallbackOverrideKey(MemberDescriptor member) {
        if (member.overrideKey() != null && !member.overrideKey().isEmpty()) {
            return member.overrideKey();
        }
        return member.name() + "/" + member.parameters().size();
    }

    public Optional<StructuredRefusal> refusalForUnknownOrAmbiguous(String simpleOrQualifiedName) {
        if (typesByName.containsKey(simpleOrQualifiedName)) {
            return Optional.empty();
        }
        Set<String> matches = typesBySimpleName.getOrDefault(simpleOrQualifiedName, Set.of());
        if (matches.isEmpty()) {
            return Optional.of(new StructuredRefusal("unresolved_type", "Unknown type: " + simpleOrQualifiedName));
        }
        if (matches.size() > 1) {
            return Optional.of(new StructuredRefusal("ambiguous_type", "Type name is ambiguous: " + simpleOrQualifiedName));
        }
        return Optional.empty();
    }

    private void indexType(TypeDescriptor descriptor) {
        typesByName.put(descriptor.qualifiedName(), descriptor);
        typesBySimpleName.computeIfAbsent(descriptor.simpleName(), ignored -> new LinkedHashSet<>()).add(descriptor.qualifiedName());
        for (TypeDescriptor nested : descriptor.nestedTypes()) {
            indexType(nested);
        }
    }
}
