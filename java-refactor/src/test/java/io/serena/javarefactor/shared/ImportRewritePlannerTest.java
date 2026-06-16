package io.serena.javarefactor.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.serena.javarefactor.edits.PlannerSupport;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for comprehensive, type-aware import rewriting in {@link ImportRewritePlanner}. */
class ImportRewritePlannerTest {

    private static final Path FILE = Path.of("Use.java");
    private static final String HEADER = "package demo;\n\nclass Use {}\n";

    private static String importedText(ImportRewritePlanner.TypeUse use) {
        StringBuilder text = new StringBuilder();
        for (PlannerSupport.TextEdit edit : use.importEdits()) {
            text.append(edit.newText()).append('\n');
        }
        return text.toString();
    }

    // ── Text fallback: nested type enumeration ──────────────────────────────────

    @Test
    void collectReferencedTypeNamesWalksNestedGenericsArgumentsAndOuterTypes() {
        Set<String> names = ImportRewritePlanner.collectReferencedTypeNames("Map<Foo, List<Bar>>");

        assertTrue(names.containsAll(List.of("Map", "Foo", "List", "Bar")), names.toString());
    }

    @Test
    void collectReferencedTypeNamesIncludesAnnotationAndThrownTypesAndExcludesKeywordsAndPrimitives() {
        Set<String> names =
                ImportRewritePlanner.collectReferencedTypeNames("@Nullable Baz value extends Number throws Qux, int[]");

        assertTrue(names.containsAll(List.of("Nullable", "Baz", "Number", "Qux")), names.toString());
        assertFalse(names.contains("extends"), names.toString());
        assertFalse(names.contains("throws"), names.toString());
        assertFalse(names.contains("int"), names.toString());
    }

    @Test
    void collectReferencedTypeNamesIsNullSafe() {
        assertTrue(ImportRewritePlanner.collectReferencedTypeNames((String) null).isEmpty());
        assertTrue(ImportRewritePlanner.collectReferencedTypeNames(
                        (javax.lang.model.type.TypeMirror) null)
                .isEmpty());
    }

    // ── planTypeUsageDeep: nested generics ──────────────────────────────────────

    @Test
    void planTypeUsageDeepSimplifiesAndImportsEveryNestedType() {
        ImportRewritePlanner planner = new ImportRewritePlanner(HEADER);

        ImportRewritePlanner.TypeUse use = planner.planTypeUsageDeep(
                FILE, "java.util.Map<com.x.Foo, java.util.List<com.y.Bar>>", "K");

        assertEquals("Map<Foo, List<Bar>>", use.renderedType());
        String imports = importedText(use);
        assertTrue(imports.contains("import java.util.Map;"), imports);
        assertTrue(imports.contains("import com.x.Foo;"), imports);
        assertTrue(imports.contains("import java.util.List;"), imports);
        assertTrue(imports.contains("import com.y.Bar;"), imports);
        assertEquals(4, use.importEdits().size(), imports);
        assertFalse(use.conflict());
    }

    @Test
    void planTypeUsageDeepRewritesAnnotationTypes() {
        ImportRewritePlanner planner = new ImportRewritePlanner(HEADER);

        ImportRewritePlanner.TypeUse use = planner.planTypeUsageDeep(FILE, "@com.x.Nullable com.y.Baz", "K");

        assertEquals("@Nullable Baz", use.renderedType());
        String imports = importedText(use);
        assertTrue(imports.contains("import com.x.Nullable;"), imports);
        assertTrue(imports.contains("import com.y.Baz;"), imports);
    }

    @Test
    void planTypeUsageDeepLeavesConflictingSimpleNameFullyQualifiedButImportsTheRest() {
        ImportRewritePlanner planner =
                new ImportRewritePlanner("package demo;\n\nimport a.Foo;\n\nclass Use {}\n");

        ImportRewritePlanner.TypeUse use = planner.planTypeUsageDeep(FILE, "b.Foo<c.Bar>", "K");

        assertEquals("b.Foo<Bar>", use.renderedType());
        assertTrue(use.conflict());
        String imports = importedText(use);
        assertTrue(imports.contains("import c.Bar;"), imports);
        assertFalse(imports.contains("import b.Foo;"), imports);
    }

    @Test
    void planTypeUsageDeepLeavesSamePackageOrProjectConflictFullyQualifiedViaResolver() {
        // The edited file imports nothing for "Foo", so the text-only check would happily import b.Foo.
        // A compiler-backed resolver reports that demo (this file's package) already declares a different
        // top-level Foo, so the reference must stay fully qualified and conflict() must be set.
        ImportRewritePlanner planner = new ImportRewritePlanner(HEADER)
                .withConflictResolver((simpleName, candidateFqn) -> simpleName.equals("Foo"));

        ImportRewritePlanner.TypeUse use = planner.planTypeUsageDeep(FILE, "b.Foo<c.Bar>", "K");

        assertEquals("b.Foo<Bar>", use.renderedType());
        assertTrue(use.conflict());
        String imports = importedText(use);
        assertFalse(imports.contains("import b.Foo;"), imports);
        // The non-conflicting nested type is still imported and simplified.
        assertTrue(imports.contains("import c.Bar;"), imports);
    }

    @Test
    void planTypeUsageDeepImportsGenericNestedAndArrayComponentsTogether() {
        ImportRewritePlanner planner = new ImportRewritePlanner(HEADER);

        // Map<Foo, Bar[]>: outer generic + nested type argument + array component must all be imported.
        ImportRewritePlanner.TypeUse use = planner.planTypeUsageDeep(
                FILE, "java.util.Map<com.a.Foo, com.b.Bar[]>", "K");

        assertEquals("Map<Foo, Bar[]>", use.renderedType());
        String imports = importedText(use);
        assertTrue(imports.contains("import java.util.Map;"), imports);
        assertTrue(imports.contains("import com.a.Foo;"), imports);
        assertTrue(imports.contains("import com.b.Bar;"), imports);
        assertEquals(3, use.importEdits().size(), imports);
        assertFalse(use.conflict());
    }

    @Test
    void planTypeUsageDeepResolverDoesNotOverrideExactExistingImport() {
        // The file already imports demo.Foo; a resolver that flags "Foo" must NOT re-flag the very type
        // that import resolves — otherwise an already-imported simple name would be wrongly re-qualified.
        ImportRewritePlanner planner = new ImportRewritePlanner(
                        "package demo;\n\nimport other.Foo;\n\nclass Use {}\n")
                .withConflictResolver((simpleName, candidateFqn) -> simpleName.equals("Foo"));

        ImportRewritePlanner.TypeUse use = planner.planTypeUsageDeep(FILE, "other.Foo", "K");

        assertEquals("Foo", use.renderedType());
        assertFalse(use.conflict());
    }

    @Test
    void planTypeUsageDeepSkipsSamePackageAndJavaLangTypes() {
        ImportRewritePlanner planner = new ImportRewritePlanner(HEADER);

        ImportRewritePlanner.TypeUse use =
                planner.planTypeUsageDeep(FILE, "java.util.List<demo.Sibling>", "K");

        assertEquals("List<Sibling>", use.renderedType());
        String imports = importedText(use);
        assertTrue(imports.contains("import java.util.List;"), imports);
        assertFalse(imports.contains("import demo.Sibling;"), imports);
        assertEquals(1, use.importEdits().size(), imports);
    }

    // ── planTypesForBody: moved/inlined body dependencies ───────────────────────

    @Test
    void planTypesForBodyImportsOnlyTheTypesThatRequireImports() {
        ImportRewritePlanner planner = new ImportRewritePlanner(HEADER);

        List<PlannerSupport.TextEdit> edits = planner.planTypesForBody(
                FILE,
                List.of("com.x.Foo", "com.y.Bar", "demo.Sibling", "java.lang.String", "int", ""),
                "K");

        StringBuilder text = new StringBuilder();
        edits.forEach(edit -> text.append(edit.newText()).append('\n'));
        assertEquals(2, edits.size(), text.toString());
        assertTrue(text.toString().contains("import com.x.Foo;"), text.toString());
        assertTrue(text.toString().contains("import com.y.Bar;"), text.toString());
    }

    @Test
    void planTypesForBodyDeduplicatesAcrossCalls() {
        ImportRewritePlanner planner = new ImportRewritePlanner(HEADER);

        assertEquals(1, planner.planTypesForBody(FILE, List.of("com.x.Foo"), "K").size());
        // Already planned on the first call, so the second call adds nothing.
        assertTrue(planner.planTypesForBody(FILE, List.of("com.x.Foo"), "K").isEmpty());
    }

    // ── Backward-compatible single-outer-type behavior and static handling ──────

    @Test
    void planTypeUsageRemainsOuterTypeOnlyAndExposesEditsList() {
        ImportRewritePlanner planner = new ImportRewritePlanner(HEADER);

        ImportRewritePlanner.TypeUse use =
                planner.planTypeUsage(FILE, "java.util.Map<com.x.Foo, com.y.Bar>", "K");

        // Conservative single-type contract: only the outer raw type is simplified/imported.
        assertEquals("Map<com.x.Foo, com.y.Bar>", use.renderedType());
        assertTrue(use.importEdit().isPresent());
        assertEquals(1, use.importEdits().size());
        assertEquals(use.importEdit().orElseThrow(), use.importEdits().get(0));
    }

    @Test
    void computeStaticImportInsertionAppendsAfterExistingStaticImportAndDeduplicates() {
        String source = "package demo;\n\nimport static a.B.c;\nimport d.E;\n\nclass Use {}\n";

        ImportRewritePlanner.ImportInsertion insertion =
                ImportRewritePlanner.computeStaticImportInsertion(source, "x.Y.z").orElseThrow();
        assertTrue(insertion.text().contains("import static x.Y.z;"), insertion.text());
        // It is placed right after the existing static import line.
        assertEquals(source.indexOf("import d.E;"), insertion.offset());

        // Already statically imported (single member) → no insertion.
        assertTrue(ImportRewritePlanner.computeStaticImportInsertion(source, "a.B.c").isEmpty());
        // Covered by a static wildcard → no insertion.
        String wildcard = "package demo;\n\nimport static a.B.*;\n\nclass Use {}\n";
        assertTrue(ImportRewritePlanner.computeStaticImportInsertion(wildcard, "a.B.c").isEmpty());
    }

    @Test
    void planStaleImportRemovalPreservesStaticImportsAndRemovesRegularOnes() {
        ImportRewritePlanner planner = new ImportRewritePlanner(
                "package demo;\n\nimport static a.B.c;\nimport d.E;\n\nclass Use {}\n");

        assertTrue(planner.planStaleImportRemoval(FILE, "a.B.c", "K").isEmpty());
        assertTrue(planner.planStaleImportRemoval(FILE, "d.E", "K").isPresent());
    }

    // ── computeImportInsertion: layout inference for contradicting blocks (G004) ─

    private static String applyInsertion(String source, String newFqn) {
        ImportRewritePlanner.ImportInsertion insertion =
                ImportRewritePlanner.computeImportInsertion(source, newFqn).orElseThrow();
        return source.substring(0, insertion.offset()) + insertion.text() + source.substring(insertion.offset());
    }

    @Test
    void computeImportInsertionKeepsCanonicalLayoutForConventionalBlock() {
        // A conventional java/javax/other ascending block is left on the default path: the new java import joins the
        // java group in alphabetical order, with no behavior change from before G004.
        String source = "package demo;\n\nimport java.util.List;\nimport org.x.X;\n\nclass Use {}\n";

        String result = applyInsertion(source, "java.util.Map");

        assertTrue(result.contains("import java.util.List;\nimport java.util.Map;\nimport org.x.X;"), result);
    }

    @Test
    void computeImportInsertionHonorsSingleUngroupedBlockWithoutSplittingJava() {
        // The file mixes java and non-java imports in one alphabetical block with no group separation. Inference must
        // keep the single block — inserting alphabetically WITHOUT introducing the hard-coded java/javax blank split.
        String source = "package demo;\n\nimport com.a.A;\nimport java.util.List;\nimport org.z.Z;\n\nclass Use {}\n";

        String result = applyInsertion(source, "com.b.B");

        assertTrue(result.contains("import com.a.A;\nimport com.b.B;\nimport java.util.List;\nimport org.z.Z;"), result);
    }

    @Test
    void computeImportInsertionHonorsDescendingOrder() {
        // The existing block is in descending alphabetical order; the new import must preserve that direction rather
        // than being forced ascending by the hard-coded comparator.
        String source = "package demo;\n\nimport org.z.Z;\nimport com.a.A;\n\nclass Use {}\n";

        String result = applyInsertion(source, "net.m.M");

        assertTrue(result.contains("import org.z.Z;\nimport net.m.M;\nimport com.a.A;"), result);
    }

    @Test
    void computeImportInsertionHonorsJavaLastGroupOrdering() {
        // The file deliberately places the java.* group LAST, after a non-java group. A new java import must join the
        // existing java group where it actually sits, not be hoisted to the top per the hard-coded policy.
        String source = "package demo;\n\nimport org.a.A;\n\nimport java.util.List;\n\nclass Use {}\n";

        String javaImport = applyInsertion(source, "java.util.Map");
        assertTrue(javaImport.contains("import java.util.List;\nimport java.util.Map;"), javaImport);
        assertTrue(javaImport.indexOf("import org.a.A;") < javaImport.indexOf("import java.util.List;"), javaImport);

        String otherImport = applyInsertion(source, "org.b.B");
        assertTrue(otherImport.contains("import org.a.A;\nimport org.b.B;"), otherImport);
        assertTrue(otherImport.indexOf("import org.b.B;") < otherImport.indexOf("import java.util.List;"), otherImport);
    }
}
