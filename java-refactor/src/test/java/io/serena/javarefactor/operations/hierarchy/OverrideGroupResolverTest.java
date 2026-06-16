package io.serena.javarefactor.operations.hierarchy;

import io.serena.javarefactor.shared.SourceLocation;
import io.serena.javarefactor.shared.StructuredRefusal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the G009 semantic override/sibling-compatibility resolver. Each case drives the resolver against a
 * hand-built {@link TypeHierarchyIndex} so the structured compatibility decision (covariant return, generic
 * substitution, visibility, sibling collision) is proven in isolation from the javac-backed planner.
 */
class OverrideGroupResolverTest {

    @Test
    void pullUpAcceptsCovariantReturnSiblingOverride() {
        // Base <- Source, Base <- Sibling. Sibling overrides make() returning a subtype (String) of the moved Object
        // return: a legal covariant override, so the pull-up is compatible.
        MemberDescriptor moving = method("make", "Object", "make()", "public");
        MemberDescriptor sibling = method("make", "String", "make()", "public");
        TypeHierarchyIndex index = index(
                type("demo.Base", Set.of(), List.of()),
                type("demo.Source", Set.of("demo.Base"), List.of(moving)),
                type("demo.Sibling", Set.of("demo.Base"), List.of(sibling)),
                type("java.lang.String", Set.of("java.lang.Object"), List.of()),
                type("java.lang.Object", Set.of(), List.of()));

        Optional<StructuredRefusal> refusal = new OverrideGroupResolver(index)
                .validatePullUp(moving, "demo.Source", "demo.Base");
        assertTrue(refusal.isEmpty(), () -> "expected compatible covariant override, got " + refusal);
    }

    @Test
    void pullUpRefusesIncompatibleCovariantReturn() {
        // The sibling override returns Number, which is NOT a subtype of the moved String return: an illegal override.
        MemberDescriptor moving = method("make", "String", "make()", "public");
        MemberDescriptor sibling = method("make", "Number", "make()", "public");
        TypeHierarchyIndex index = index(
                type("demo.Base", Set.of(), List.of()),
                type("demo.Source", Set.of("demo.Base"), List.of(moving)),
                type("demo.Sibling", Set.of("demo.Base"), List.of(sibling)),
                type("String", Set.of(), List.of()),
                type("Number", Set.of(), List.of()));

        Optional<StructuredRefusal> refusal = new OverrideGroupResolver(index)
                .validatePullUp(moving, "demo.Source", "demo.Base");
        assertTrue(refusal.isPresent());
        assertEquals("incompatible_covariant_return", refusal.get().code());
    }

    @Test
    void pullUpRefusesGenericSubstitutionMismatch() {
        // Both declare accept(List) by erasure, but the declared generic parameter types are List<String> vs
        // List<Integer>: a conflicting parameterization that overrides only by erasure.
        MemberDescriptor moving = methodWithParam("accept", "void", "accept(java.util.List)", "java.util.List<java.lang.String>", "public");
        MemberDescriptor sibling = methodWithParam("accept", "void", "accept(java.util.List)", "java.util.List<java.lang.Integer>", "public");
        TypeHierarchyIndex index = index(
                type("demo.Base", Set.of(), List.of()),
                type("demo.Source", Set.of("demo.Base"), List.of(moving)),
                type("demo.Sibling", Set.of("demo.Base"), List.of(sibling)));

        Optional<StructuredRefusal> refusal = new OverrideGroupResolver(index)
                .validatePullUp(moving, "demo.Source", "demo.Base");
        assertTrue(refusal.isPresent());
        assertEquals("generic_substitution_mismatch", refusal.get().code());
    }

    @Test
    void pullUpAcceptsGenericSubstitutionThroughTypeVariable() {
        // The moved declaration is generic in T (accept(T)); the sibling resolves T to String. A type-variable
        // substitution is a legal parameterization, not a conflict.
        MemberDescriptor moving = methodWithParamAndTypeVars(
                "accept", "void", "accept(java.lang.Object)", "T", "public", List.of("T"));
        MemberDescriptor sibling = methodWithParam("accept", "void", "accept(java.lang.Object)", "java.lang.String", "public");
        TypeHierarchyIndex index = index(
                type("demo.Base", Set.of(), List.of()),
                type("demo.Source", Set.of("demo.Base"), List.of(moving)),
                type("demo.Sibling", Set.of("demo.Base"), List.of(sibling)));

        Optional<StructuredRefusal> refusal = new OverrideGroupResolver(index)
                .validatePullUp(moving, "demo.Source", "demo.Base");
        assertTrue(refusal.isEmpty(), () -> "type-variable substitution must be compatible, got " + refusal);
    }

    @Test
    void pullUpRefusesOverrideThatNarrowsVisibility() {
        // The moved declaration is public; the sibling override is protected — an override may not reduce visibility.
        MemberDescriptor moving = method("run", "void", "run()", "public");
        MemberDescriptor sibling = method("run", "void", "run()", "protected");
        TypeHierarchyIndex index = index(
                type("demo.Base", Set.of(), List.of()),
                type("demo.Source", Set.of("demo.Base"), List.of(moving)),
                type("demo.Sibling", Set.of("demo.Base"), List.of(sibling)));

        Optional<StructuredRefusal> refusal = new OverrideGroupResolver(index)
                .validatePullUp(moving, "demo.Source", "demo.Base");
        assertTrue(refusal.isPresent());
        assertEquals("incompatible_override_visibility", refusal.get().code());
    }

    @Test
    void pullUpAcceptsOverrideThatWidensVisibility() {
        // The sibling override is public, wider than the moved protected declaration: legal (widening is allowed).
        MemberDescriptor moving = method("run", "void", "run()", "protected");
        MemberDescriptor sibling = method("run", "void", "run()", "public");
        TypeHierarchyIndex index = index(
                type("demo.Base", Set.of(), List.of()),
                type("demo.Source", Set.of("demo.Base"), List.of(moving)),
                type("demo.Sibling", Set.of("demo.Base"), List.of(sibling)));

        Optional<StructuredRefusal> refusal = new OverrideGroupResolver(index)
                .validatePullUp(moving, "demo.Source", "demo.Base");
        assertTrue(refusal.isEmpty(), () -> "widening visibility must be compatible, got " + refusal);
    }

    @Test
    void pullUpIgnoresDifferentErasedSignatureOverloads() {
        // The sibling's same-named method has a different erased signature: a legal overload, never an override clash.
        MemberDescriptor moving = methodWithParam("label", "void", "label(int)", "int", "public");
        MemberDescriptor sibling = methodWithParam("label", "void", "label(java.lang.String)", "java.lang.String", "public");
        TypeHierarchyIndex index = index(
                type("demo.Base", Set.of(), List.of()),
                type("demo.Source", Set.of("demo.Base"), List.of(moving)),
                type("demo.Sibling", Set.of("demo.Base"), List.of(sibling)));

        Optional<StructuredRefusal> refusal = new OverrideGroupResolver(index)
                .validatePullUp(moving, "demo.Source", "demo.Base");
        assertTrue(refusal.isEmpty(), () -> "overloads must not be flagged, got " + refusal);
    }

    @Test
    void pushDownRefusesIncompatibleCovariantReturnAgainstIntermediateSupertype() {
        // Source declares make():String; an intermediate type Mid (between Source and Target) also declares
        // make():Number. Pushing make():String down into Target makes it override Mid's make():Number, but String is
        // not a supertype of Number — wait, here Target inherits Mid.make():Number, and the pushed String copy would be
        // an override returning a narrower type which is fine; to force incompatibility the moved return must NOT be a
        // subtype of the inherited return. Mid returns String, pushed copy returns Number -> illegal.
        MemberDescriptor moving = method("make", "Number", "make()", "public");
        MemberDescriptor mid = method("make", "String", "make()", "public");
        TypeHierarchyIndex index = index(
                type("demo.Source", Set.of(), List.of(moving)),
                type("demo.Mid", Set.of("demo.Source"), List.of(mid)),
                type("demo.Target", Set.of("demo.Mid"), List.of()),
                type("String", Set.of(), List.of()),
                type("Number", Set.of(), List.of()));

        Optional<StructuredRefusal> refusal = new OverrideGroupResolver(index)
                .validatePushDown(moving, "demo.Source", List.of("demo.Target"));
        assertTrue(refusal.isPresent());
        assertEquals("incompatible_covariant_return", refusal.get().code());
    }

    @Test
    void nonMethodMemberIsAlwaysCompatible() {
        MemberDescriptor field = new MemberDescriptor("count", "field", Set.of("public"),
                new SourceLocation("Demo.java", 1, 1, 1, 5));
        TypeHierarchyIndex index = index(
                type("demo.Base", Set.of(), List.of()),
                type("demo.Source", Set.of("demo.Base"), List.of(field)));

        assertTrue(new OverrideGroupResolver(index).validatePullUp(field, "demo.Source", "demo.Base").isEmpty());
    }

    // --- harness -----------------------------------------------------------------------------------------------------

    private static TypeHierarchyIndex index(TypeDescriptor... types) {
        return new TypeHierarchyIndex(List.of(types));
    }

    private static MemberDescriptor method(String name, String returnType, String overrideKey, String visibility) {
        return new MemberDescriptor(
                name, "method", Set.of(visibility), new SourceLocation("Demo.java", 1, 1, 1, 10),
                "demo#" + overrideKey, overrideKey, returnType, List.of(), List.of(), List.of(), List.of(), visibility);
    }

    private static MemberDescriptor methodWithParam(
            String name, String returnType, String overrideKey, String declaredParamType, String visibility) {
        return methodWithParamAndTypeVars(name, returnType, overrideKey, declaredParamType, visibility, List.of());
    }

    private static MemberDescriptor methodWithParamAndTypeVars(
            String name, String returnType, String overrideKey, String declaredParamType, String visibility,
            List<String> typeParameters) {
        MemberDescriptor.ParameterModel parameter =
                new MemberDescriptor.ParameterModel("arg", declaredParamType, eraseForKey(declaredParamType));
        return new MemberDescriptor(
                name, "method", Set.of(visibility), new SourceLocation("Demo.java", 1, 1, 1, 10),
                "demo#" + overrideKey, overrideKey, returnType, List.of(parameter), List.of(), List.of(),
                typeParameters, visibility);
    }

    private static String eraseForKey(String declaredType) {
        int generic = declaredType.indexOf('<');
        return generic < 0 ? declaredType : declaredType.substring(0, generic);
    }

    private static TypeDescriptor type(String qualifiedName, Set<String> supertypes, List<MemberDescriptor> members) {
        return new TypeDescriptor(
                qualifiedName,
                qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
                supertypes,
                Set.of(),
                members,
                Set.of("public"),
                new SourceLocation(qualifiedName.replace('.', '/') + ".java", 1, 1, 1, 10),
                List.of());
    }
}
