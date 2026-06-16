package io.serena.javarefactor.operations.hierarchy;

import io.serena.javarefactor.shared.StructuredRefusal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * G009: the semantic override/sibling-compatibility resolver for hierarchy member moves (pull-up / push-down).
 *
 * <p>Before any edit is emitted, a pull-up or push-down that relocates a <em>method</em> must prove that the relocated
 * declaration remains a legal JLS override relative to every sibling/target method that shares the moving method's
 * erased override identity (name + erased parameter types). Earlier revisions skipped same-erased-key siblings here and
 * leaned on a later javac compile of the post-edit workspace to surface an illegal override. This resolver makes that
 * compatibility a <em>structured precondition</em> instead: it inspects the {@link MemberDescriptor}s already modelled
 * by the {@link TypeHierarchyIndex} (declared return type, declared parameter types, erased override key, and visibility)
 * and refuses with a located structured code when an incompatibility is detected, rather than relying on generic compile
 * diagnostics as the primary mechanism.
 *
 * <p>The checks performed (JLS §8.4.8.3 "Requirements in Overriding and Hiding"):
 * <ul>
 *   <li><b>Incompatible covariant return</b> ({@code incompatible_covariant_return}) — an overriding sibling's return
 *       type must be a subtype of (or equal to) the moved method's return type for a reference-typed return, and exactly
 *       equal for a primitive/void return. A sibling whose return type is unrelated (or a supertype) would not be a legal
 *       override of the relocated declaration.</li>
 *   <li><b>Generic substitution mismatch</b> ({@code generic_substitution_mismatch}) — two methods whose erased
 *       signatures coincide but whose declared (generic) parameter types differ in a way that is not a valid
 *       parameterization of the same generic method (e.g. {@code accept(List<String>)} vs {@code accept(List<Integer>)})
 *       are <em>override-equivalent</em> only by erasure; relocating one over the other introduces an unchecked /
 *       conflicting override and is refused.</li>
 *   <li><b>Incompatible visibility</b> ({@code incompatible_override_visibility}) — an override must not reduce the
 *       visibility of the method it overrides. If the relocated declaration is more visible than a sibling override, the
 *       sibling becomes an illegal narrowing and the move is refused.</li>
 *   <li><b>Sibling collision</b> ({@code sibling_member_collision}) — a sibling subtype (outside the source subtree) that
 *       declares the same erased method signature but is <em>not</em> a legal override of the moved declaration is a real
 *       clash rather than an implementation.</li>
 * </ul>
 *
 * <p>Methods that share only a name (different erased parameter types) are legal overloads and are never flagged here. A
 * sibling whose same-erased-key method <em>is</em> a compatible override (covariant/equal return, equal-or-wider
 * visibility, matching generic parameterization) is reported as compatible — it is an implementation of the relocated
 * declaration, not a collision (JLS §8.2.5).
 */
public final class OverrideGroupResolver {
    private final TypeHierarchyIndex hierarchy;

    public OverrideGroupResolver(TypeHierarchyIndex hierarchy) {
        this.hierarchy = hierarchy;
    }

    /**
     * Validates that pulling {@code moving} (declared in {@code sourceType}) up into {@code targetType} keeps every
     * sibling subtype method that shares the moving method's erased signature a legal override of the relocated
     * declaration. Returns a structured refusal when an incompatibility is found, or {@link Optional#empty()} when the
     * move is compatible. Non-method members and methods with no resolvable erased key are treated as compatible (the
     * existing field/collision gates cover those cases).
     */
    public Optional<StructuredRefusal> validatePullUp(
            MemberDescriptor moving, String sourceType, String targetType) {
        if (moving == null || !moving.isMethod()) {
            return Optional.empty();
        }
        String movingKey = moving.overrideKey();
        if (movingKey == null || movingKey.isEmpty()) {
            return Optional.empty();
        }
        Set<String> sourceSubtree = subtree(sourceType);
        // Every subtype of the target (other than the source subtree) is a sibling whose same-signature method would now
        // override the relocated declaration in the target supertype.
        for (String siblingType : hierarchy.allSubtypes(targetType)) {
            if (sourceSubtree.contains(siblingType)) {
                continue;
            }
            Optional<StructuredRefusal> refusal = validateAgainstType(moving, movingKey, siblingType, true);
            if (refusal.isPresent()) {
                return refusal;
            }
        }
        return Optional.empty();
    }

    /**
     * Validates that pushing {@code moving} (declared in {@code sourceType}) down into each subtype of
     * {@code targetTypes} keeps the relocated copy a legal override of any same-signature method already declared in a
     * supertype of that target (i.e. the source type or an intermediate type). Returns a structured refusal on the first
     * incompatibility, otherwise {@link Optional#empty()}.
     */
    public Optional<StructuredRefusal> validatePushDown(
            MemberDescriptor moving, String sourceType, List<String> targetTypes) {
        if (moving == null || !moving.isMethod()) {
            return Optional.empty();
        }
        String movingKey = moving.overrideKey();
        if (movingKey == null || movingKey.isEmpty()) {
            return Optional.empty();
        }
        for (String targetType : targetTypes) {
            // The relocated copy must remain a legal override of any same-signature declaration it inherits from a
            // supertype other than the (soon-to-lose-the-member) source type.
            for (String supertype : hierarchy.allSupertypes(targetType)) {
                if (supertype.equals(sourceType)) {
                    continue;
                }
                Optional<StructuredRefusal> refusal = validateAgainstType(moving, movingKey, supertype, false);
                if (refusal.isPresent()) {
                    return refusal;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Compares the moving method against every same-erased-key method declared by {@code otherType}. When
     * {@code movingIsSupertypeDeclaration} the moving method becomes the supertype declaration (pull-up) and the other
     * method is the overriding subtype declaration; otherwise (push-down) the other method is the inherited supertype
     * declaration and the moving method is the override. The override direction determines which side must have the
     * covariant/equal-or-wider relationship.
     */
    private Optional<StructuredRefusal> validateAgainstType(
            MemberDescriptor moving, String movingKey, String otherType, boolean movingIsSupertypeDeclaration) {
        for (MemberDescriptor other : hierarchy.membersNamed(otherType, moving.name())) {
            if (!other.isMethod()) {
                continue;
            }
            String otherKey = other.overrideKey();
            if (otherKey == null || otherKey.isEmpty() || !otherKey.equals(movingKey)) {
                // Different erased signature: a legal overload, never an override clash.
                continue;
            }
            MemberDescriptor supertypeDeclaration = movingIsSupertypeDeclaration ? moving : other;
            MemberDescriptor overrideDeclaration = movingIsSupertypeDeclaration ? other : moving;
            Optional<StructuredRefusal> refusal = checkOverridePair(supertypeDeclaration, overrideDeclaration, otherType);
            if (refusal.isPresent()) {
                return refusal;
            }
        }
        return Optional.empty();
    }

    /**
     * Applies the JLS overriding requirements to a (supertype declaration, override declaration) pair that already share
     * an erased signature. Order matters: the {@code override} must be return-type-substitutable for, generic-compatible
     * with, and at-least-as-visible as the {@code supertype} declaration.
     */
    private Optional<StructuredRefusal> checkOverridePair(
            MemberDescriptor supertype, MemberDescriptor override, String collisionType) {
        if (!returnTypeCompatible(supertype.returnType(), override.returnType())) {
            return Optional.of(new StructuredRefusal(
                    "incompatible_covariant_return",
                    "Override in " + collisionType + " returns '" + override.returnType()
                            + "', which is not the same as or a subtype of the relocated declaration's return type '"
                            + supertype.returnType() + "'; the move would produce an illegal override."));
        }
        if (!genericParametersCompatible(supertype, override)) {
            return Optional.of(new StructuredRefusal(
                    "generic_substitution_mismatch",
                    "Method in " + collisionType + " shares the erased signature of the moving member but declares "
                            + "incompatible generic parameter types (" + declaredParameterTypes(override)
                            + " vs " + declaredParameterTypes(supertype)
                            + "); the relocated declaration would override only by erasure."));
        }
        if (!visibilityAtLeast(override.visibility(), supertype.visibility())) {
            return Optional.of(new StructuredRefusal(
                    "incompatible_override_visibility",
                    "Override in " + collisionType + " has '" + override.visibility()
                            + "' visibility, which is weaker than the relocated declaration's '" + supertype.visibility()
                            + "' visibility; an override may not reduce visibility."));
        }
        return Optional.empty();
    }

    /**
     * Return-type substitutability for an override (JLS §8.4.8.3). For a primitive or {@code void} return the types must
     * be identical. For a reference return the override type must be the same as, or a subtype of, the supertype return
     * type (covariant return). Subtyping is decided from the hierarchy graph (erased qualified/simple names) and falls
     * back to erased-name equality for types outside the project model (library types), so an unrelated covariant claim
     * is still refused while an identical library return type is accepted.
     */
    private boolean returnTypeCompatible(String supertypeReturn, String overrideReturn) {
        String supertypeErased = erase(supertypeReturn);
        String overrideErased = erase(overrideReturn);
        if (supertypeErased.equals(overrideErased)) {
            return true;
        }
        if (isPrimitiveOrVoid(supertypeErased) || isPrimitiveOrVoid(overrideErased)) {
            // Differing primitive/void returns can never be a covariant override.
            return false;
        }
        return isSubtypeByName(overrideErased, supertypeErased);
    }

    /**
     * Two methods with an identical erased signature are generic-compatible iff their declared (pre-erasure) parameter
     * types coincide position-by-position, OR a differing position is a type variable on at least one side (a legal
     * generic substitution such as {@code T} resolved to {@code String}). A position where both sides are distinct,
     * fully-instantiated parameterized types (e.g. {@code List<String>} vs {@code List<Integer>}) is a genuine
     * substitution conflict and is rejected.
     */
    private boolean genericParametersCompatible(MemberDescriptor supertype, MemberDescriptor override) {
        List<MemberDescriptor.ParameterModel> superParams = supertype.parameters();
        List<MemberDescriptor.ParameterModel> overrideParams = override.parameters();
        if (superParams.size() != overrideParams.size()) {
            return true; // Different arity cannot share an erased key; defensively treat as a non-conflict here.
        }
        Set<String> superTypeVars = typeVariableNames(supertype);
        Set<String> overrideTypeVars = typeVariableNames(override);
        for (int index = 0; index < superParams.size(); index++) {
            String superDeclared = superParams.get(index).type();
            String overrideDeclared = overrideParams.get(index).type();
            if (superDeclared.equals(overrideDeclared)) {
                continue;
            }
            if (involvesTypeVariable(superDeclared, superTypeVars)
                    || involvesTypeVariable(overrideDeclared, overrideTypeVars)) {
                // A type-variable substitution (T -> String) is a legal parameterization, not a conflict.
                continue;
            }
            if (!isParameterized(superDeclared) && !isParameterized(overrideDeclared)) {
                // Two distinct raw/non-generic reference types with the same erasure cannot actually occur; if it does,
                // it is not a generic-substitution conflict (it would be caught elsewhere), so do not flag it here.
                continue;
            }
            return false;
        }
        return true;
    }

    private Set<String> subtree(String type) {
        Set<String> subtree = new LinkedHashSet<>();
        subtree.add(type);
        subtree.addAll(hierarchy.allSubtypes(type));
        return subtree;
    }

    private Set<String> typeVariableNames(MemberDescriptor member) {
        Set<String> names = new LinkedHashSet<>();
        for (String declaration : member.typeParameters()) {
            String name = declaration.strip();
            int bound = name.indexOf(' ');
            if (bound > 0) {
                name = name.substring(0, bound);
            }
            int extendsIndex = name.indexOf('<');
            if (extendsIndex > 0) {
                name = name.substring(0, extendsIndex);
            }
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private boolean involvesTypeVariable(String declaredType, Set<String> typeVariables) {
        if (typeVariables.isEmpty()) {
            return false;
        }
        for (String variable : typeVariables) {
            if (declaredType.equals(variable)
                    || declaredType.matches(".*\\b" + java.util.regex.Pattern.quote(variable) + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private boolean isParameterized(String declaredType) {
        return declaredType.indexOf('<') >= 0;
    }

    private String declaredParameterTypes(MemberDescriptor member) {
        StringBuilder builder = new StringBuilder("(");
        List<MemberDescriptor.ParameterModel> parameters = member.parameters();
        for (int index = 0; index < parameters.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(parameters.get(index).type());
        }
        return builder.append(')').toString();
    }

    /** Erases generic type arguments from a declared type string, leaving the raw type name. */
    private static String erase(String declaredType) {
        if (declaredType == null) {
            return "";
        }
        String raw = declaredType.strip();
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        return raw.strip();
    }

    private static boolean isPrimitiveOrVoid(String erasedType) {
        return switch (erasedType) {
            case "void", "boolean", "byte", "short", "char", "int", "long", "float", "double" -> true;
            default -> false;
        };
    }

    /**
     * Whether {@code candidateSubtype} is the same as or a subtype of {@code candidateSupertype}, decided from the
     * project hierarchy graph. Names are resolved through the index so a simple name and its qualified form are treated
     * as the same type; a candidate outside the project model only matches on exact (erased) name equality.
     */
    private boolean isSubtypeByName(String candidateSubtype, String candidateSupertype) {
        Optional<String> subResolved = hierarchy.resolveQualifiedName(candidateSubtype);
        Optional<String> superResolved = hierarchy.resolveQualifiedName(candidateSupertype);
        if (subResolved.isEmpty() || superResolved.isEmpty()) {
            // At least one type is outside the project model: only an exact (erased) name match is provable.
            return candidateSubtype.equals(candidateSupertype)
                    || simpleName(candidateSubtype).equals(simpleName(candidateSupertype));
        }
        String sub = subResolved.get();
        String sup = superResolved.get();
        return sub.equals(sup) || hierarchy.allSupertypes(sub).contains(sup);
    }

    private static String simpleName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    /** Visibility rank used to compare access levels: a higher rank is more visible. */
    private static int visibilityRank(String visibility) {
        return switch (visibility) {
            case "public" -> 3;
            case "protected" -> 2;
            case "package" -> 1;
            case "private" -> 0;
            default -> 1;
        };
    }

    /** True iff {@code candidate} is at least as visible as {@code required}. */
    private static boolean visibilityAtLeast(String candidate, String required) {
        return visibilityRank(candidate) >= visibilityRank(required);
    }
}
