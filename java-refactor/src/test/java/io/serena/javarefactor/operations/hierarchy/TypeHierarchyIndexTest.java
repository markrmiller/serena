package io.serena.javarefactor.operations.hierarchy;

import io.serena.javarefactor.shared.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TypeHierarchyIndex} serves true override groups both from precomputed
 * {@code Elements.overrides}-derived groups (the compiler path) and from the erased-signature fallback used when
 * descriptors are built without a compiler.
 */
class TypeHierarchyIndexTest {

    @Test
    void precomputedGroupsResolveBackToDescriptorsByKey() {
        MemberDescriptor baseFoo = method("foo", "demo.Base#foo()", "foo()");
        MemberDescriptor childFoo = method("foo", "demo.Child#foo()", "foo()");
        TypeDescriptor base = type("demo.Base", Set.of(), List.of(baseFoo));
        TypeDescriptor child = type("demo.Child", Set.of("demo.Base"), List.of(childFoo));

        TypeHierarchyIndex index = new TypeHierarchyIndex(
                List.of(base, child),
                List.of(List.of("demo.Base#foo()", "demo.Child#foo()")));

        List<String> group = keys(index.overrideGroup("demo.Child", "foo"));
        assertEquals(List.of("demo.Base#foo()", "demo.Child#foo()"), group.stream().sorted().toList());
        assertTrue(index.participatesInOverride("demo.Base#foo()"));
        assertEquals(2, index.overrideGroupForKey("demo.Base#foo()").size());
    }

    @Test
    void fallbackGroupsByErasedOverrideKeyAcrossHierarchy() {
        // No precomputed groups: grouping must fall back to the erased overrideKey across supertype/subtype closure.
        MemberDescriptor baseRun = method("run", "demo.Base#run()", "run()");
        MemberDescriptor childRun = method("run", "demo.Child#run()", "run()");
        TypeDescriptor base = type("demo.Base", Set.of(), List.of(baseRun));
        TypeDescriptor child = type("demo.Child", Set.of("demo.Base"), List.of(childRun));

        TypeHierarchyIndex index = new TypeHierarchyIndex(List.of(base, child));

        assertEquals(2, index.overrideGroup("demo.Child", "run").size());
        assertFalse(index.participatesInOverride("demo.Base#run()"), "no precomputed group means no recorded participation");
    }

    @Test
    void overloadsWithDifferentErasedParamsDoNotGroupInFallback() {
        MemberDescriptor baseIntBar = method("bar", "demo.Base#bar(int)", "bar(int)");
        MemberDescriptor childStringBar = method("bar", "demo.Child#bar(java.lang.String)", "bar(java.lang.String)");
        TypeDescriptor base = type("demo.Base", Set.of(), List.of(baseIntBar));
        TypeDescriptor child = type("demo.Child", Set.of("demo.Base"), List.of(childStringBar));

        TypeHierarchyIndex index = new TypeHierarchyIndex(List.of(base, child));

        // bar(int) on Base must not group with the differently-erased overload bar(String) on Child.
        assertEquals(1, index.overrideGroup("demo.Base", "bar").size());
    }

    private static List<String> keys(List<MemberDescriptor> members) {
        return members.stream().map(MemberDescriptor::semanticKey).toList();
    }

    private static MemberDescriptor method(String name, String semanticKey, String overrideKey) {
        return new MemberDescriptor(
                name, "method", Set.of("public"), new SourceLocation("Demo.java", 1, 1, 1, 10),
                semanticKey, overrideKey, "void", List.of(), List.of(), List.of(), List.of(), "public");
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
