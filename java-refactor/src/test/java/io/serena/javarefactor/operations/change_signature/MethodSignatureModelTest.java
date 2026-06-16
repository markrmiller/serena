package io.serena.javarefactor.operations.change_signature;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the {@link MethodSignatureModel} helper unit extracted in G006: qualified-aware type equivalence,
 * desired-to-current parameter mapping, and annotation/{@code final} prefix splitting. These are pure functions that do
 * not require a compiled project, so they are exercised directly.
 */
class MethodSignatureModelTest {

    @Test
    void typeEquivalenceTreatsSimpleAndQualifiedNamesAsEqualWhenOneIsUnqualified() {
        assertTrue(MethodSignatureModel.typeEquivalent("List", "java.util.List"));
        assertTrue(MethodSignatureModel.typeEquivalent("java.util.List", "List"));
        assertTrue(MethodSignatureModel.typeEquivalent("int", "int"));
    }

    @Test
    void typeEquivalenceDoesNotConflateDistinctFullyQualifiedNames() {
        assertFalse(MethodSignatureModel.typeEquivalent("a.Foo", "b.Foo"));
        assertFalse(MethodSignatureModel.typeEquivalent("java.util.List", "other.List"));
    }

    @Test
    void qualifiedTypeDetectionIgnoresGenericsAndArrays() {
        assertTrue(MethodSignatureModel.isQualifiedType("java.util.List<String>"));
        assertTrue(MethodSignatureModel.isQualifiedType("java.lang.String[]"));
        assertFalse(MethodSignatureModel.isQualifiedType("List<String>"));
        assertFalse(MethodSignatureModel.isQualifiedType("String[]"));
    }

    @Test
    void simpleTypeNameStripsPackageAndGenerics() {
        assertEquals("List", MethodSignatureModel.simpleTypeName("java.util.List<String>"));
        assertEquals("Foo", MethodSignatureModel.simpleTypeName("Foo"));
    }

    @Test
    void oldIndexPrefersExplicitMappingThenNameThenPosition() {
        MethodMatch declaration = declaration(
                new ParameterSpec("int", "a", null),
                new ParameterSpec("int", "b", null));
        List<ParameterSpec> desired = List.of(
                new ParameterSpec("int", "b", null, 1),
                new ParameterSpec("int", "a", null, 0));
        // Explicit oldIndex wins regardless of position.
        assertEquals(1, MethodSignatureModel.oldIndex(desired.get(0), declaration, 0, desired));
        assertEquals(0, MethodSignatureModel.oldIndex(desired.get(1), declaration, 1, desired));

        // Name match when no explicit oldIndex.
        List<ParameterSpec> byName = List.of(new ParameterSpec("int", "b", null), new ParameterSpec("int", "a", null));
        assertEquals(1, MethodSignatureModel.oldIndex(byName.get(0), declaration, 0, byName));

        // A newly added parameter (no name match, arity grew) maps to -1.
        List<ParameterSpec> added = List.of(
                new ParameterSpec("int", "a", null),
                new ParameterSpec("int", "b", null),
                new ParameterSpec("int", "c", "0"));
        assertEquals(-1, MethodSignatureModel.oldIndex(added.get(2), declaration, 2, added));
    }

    @Test
    void splitParameterPrefixSeparatesAnnotationsAndFinalFromCoreType() {
        MethodSignatureModel.ParameterPrefix split = MethodSignatureModel.splitParameterPrefix("@NonNull final String");
        assertEquals("@NonNull final ", split.prefix());
        assertEquals("String", split.coreType());

        MethodSignatureModel.ParameterPrefix annotated = MethodSignatureModel.splitParameterPrefix("@Size(min = 1) java.util.List<String>");
        assertEquals("@Size(min = 1) ", annotated.prefix());
        assertEquals("java.util.List<String>", annotated.coreType());

        MethodSignatureModel.ParameterPrefix plain = MethodSignatureModel.splitParameterPrefix("int");
        assertEquals("", plain.prefix());
        assertEquals("int", plain.coreType());
    }

    private static MethodMatch declaration(ParameterSpec... parameters) {
        // The pure helpers under test only read name/prefix/oldIndex from the parameters and never touch the semantic
        // method, so a null-semantic MethodMatch is sufficient for this unit's surface.
        return new MethodMatch(0, 0, 0, "", "void", "m", List.of(parameters), null, false);
    }
}
