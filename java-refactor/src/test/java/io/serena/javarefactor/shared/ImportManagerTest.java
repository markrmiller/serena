package io.serena.javarefactor.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.serena.javarefactor.edits.PlannerSupport;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Focused unit tests for the compilation-unit {@link ImportManager}. */
class ImportManagerTest {

    private static final String HEADER = "package demo;\n\n";

    @Test
    void addsSingleTypeImportForReferencedType() {
        ImportManager manager = new ImportManager(HEADER + "class Use { Map field; }\n");

        assertTrue(manager.addImport("java.util.Map").isEmpty());
        assertTrue(manager.imports().contains("java.util.Map"));
        assertTrue(manager.renderImportBlock().contains("import java.util.Map;"));
    }

    @Test
    void skipsJavaLangAndSamePackageImports() {
        ImportManager manager = new ImportManager(HEADER + "class Use { String name; Sibling s; }\n");

        assertTrue(manager.addImport("java.lang.String").isEmpty());
        assertTrue(manager.addImport("demo.Sibling").isEmpty());

        assertFalse(manager.imports().contains("java.lang.String"));
        assertFalse(manager.imports().contains("demo.Sibling"));
    }

    @Test
    void doesNotSkipJavaLangSubpackageTypes() {
        ImportManager manager = new ImportManager(HEADER + "class Use { Field f; }\n");

        assertTrue(manager.addImport("java.lang.reflect.Field").isEmpty());
        assertTrue(manager.imports().contains("java.lang.reflect.Field"));
    }

    @Test
    void refusesAmbiguousSimpleNameSoCallerCanFallBackToFullyQualified() {
        ImportManager manager = new ImportManager(HEADER + "import java.sql.Date;\n\nclass Use { Date d; }\n");

        StructuredRefusal refusal = manager.addImport("java.util.Date").orElseThrow();

        assertEquals("import_conflict", refusal.code());
        assertFalse(manager.imports().contains("java.util.Date"));
    }

    @Test
    void preservesStaticImports() {
        String source = HEADER
                + "import static java.util.Collections.emptyList;\n\n"
                + "class Use { Object o = emptyList(); }\n";
        ImportManager manager = new ImportManager(source);

        assertTrue(manager.staticImports().contains("java.util.Collections.emptyList"));
        assertEquals(0, manager.removeUnusedImports());
        assertTrue(manager.renderImportBlock().contains("import static java.util.Collections.emptyList;"));
    }

    @Test
    void preservesWildcardImportsAndTreatsCoveredTypeAsAlreadyImported() {
        String source = HEADER + "import java.util.*;\n\nclass Use { List<String> names; }\n";
        ImportManager manager = new ImportManager(source);

        // Wildcard already covers java.util.List, so no single-type import is added.
        assertTrue(manager.addImport("java.util.List").isEmpty());
        assertFalse(manager.imports().contains("java.util.List"));
        // Wildcards are never reported unused nor removed.
        assertEquals(0, manager.removeUnusedImports());
        assertTrue(manager.imports().contains("java.util.*"));
    }

    @Test
    void removesOnlyUnusedSingleTypeImports() {
        String source = HEADER
                + "import java.util.List;\n"
                + "import java.util.Map;\n\n"
                + "class Use { List<String> names; }\n";
        ImportManager manager = new ImportManager(source);

        assertEquals(List.of("java.util.Map"), manager.unusedImports());
        assertEquals(1, manager.removeUnusedImports());
        assertTrue(manager.imports().contains("java.util.List"));
        assertFalse(manager.imports().contains("java.util.Map"));
    }

    @Test
    void declareReferenceRemovedMakesAStillTextuallyPresentImportEligibleForRemoval() {
        // The body still mentions Map (so the used-name scan keeps it), but the refactor that removed the
        // last *real* reference declares it removed; the import then becomes unused and is cleaned.
        String source = HEADER
                + "import java.util.List;\n"
                + "import java.util.Map;\n\n"
                + "class Use { List<String> names; Map m; }\n";
        ImportManager manager = new ImportManager(source);

        assertTrue(manager.unusedImports().isEmpty());
        manager.declareReferenceRemoved("Map");
        assertEquals(List.of("java.util.Map"), manager.unusedImports());
        assertEquals(1, manager.removeUnusedImports());
        assertFalse(manager.imports().contains("java.util.Map"));
        assertTrue(manager.imports().contains("java.util.List"));
    }

    @Test
    void addStaticImportRefusesOnCollidingMemberSimpleName() {
        String source = HEADER + "import static a.B.of;\n\nclass Use { Object o = of(); }\n";
        ImportManager manager = new ImportManager(source);

        // A different static member with the same simple name collides and is refused (caller must qualify).
        StructuredRefusal refusal = manager.addStaticImport("c.D.of").orElseThrow();
        assertEquals("static_import_conflict", refusal.code());
        assertFalse(manager.staticImports().contains("c.D.of"));
        // A distinct member name is accepted.
        assertTrue(manager.addStaticImport("c.D.from").isEmpty());
        assertTrue(manager.staticImports().contains("c.D.from"));
    }

    @Test
    void rendersStyleAwareOrderingWithGroupingAndLineEndings() {
        // CRLF source, static-imports-last (regular precedes static): assert grouping + line ending.
        String source = "package demo;\r\n\r\nimport a.Other;\r\n\r\nclass Use { Other o; }\r\n";
        ImportManager manager = new ImportManager(source);

        manager.addImport("java.util.Map");
        manager.addImport("java.util.List");
        manager.addStaticImport("java.util.Collections.emptyList");

        String rendered = manager.renderImportBlock();
        assertTrue(rendered.contains("\r\n"), rendered);
        // java.* group sorts before the "other" (a.Other) group, alphabetically within the group.
        int listIndex = rendered.indexOf("import java.util.List;");
        int mapIndex = rendered.indexOf("import java.util.Map;");
        int otherIndex = rendered.indexOf("import a.Other;");
        int staticIndex = rendered.indexOf("import static java.util.Collections.emptyList;");
        assertTrue(listIndex >= 0 && mapIndex > listIndex, rendered);
        assertTrue(otherIndex > mapIndex, rendered);
        assertTrue(staticIndex > otherIndex, rendered);
    }

    @Test
    void preservesDeliberateNonCanonicalImportOrderAndAppendsNewImports() {
        // The file's regular imports are NOT in canonical (alphabetical) order: Map precedes List. The
        // manager must not reflow them; it preserves the file order and appends new imports at the end.
        String source = HEADER
                + "import java.util.Map;\n"
                + "import java.util.List;\n\n"
                + "class Use { Map<String, List<String>> m; }\n";
        ImportManager manager = new ImportManager(source);

        assertTrue(manager.addImport("java.util.Set").isEmpty());

        String rendered = manager.renderImportBlock();
        int mapIndex = rendered.indexOf("import java.util.Map;");
        int listIndex = rendered.indexOf("import java.util.List;");
        int setIndex = rendered.indexOf("import java.util.Set;");
        // Deliberate order (Map before List) is preserved; the added import follows the existing block.
        assertTrue(mapIndex >= 0 && listIndex > mapIndex, rendered);
        assertTrue(setIndex > listIndex, rendered);
    }

    @Test
    void preservesNonCanonicalGroupArrangementWithoutRegrouping() {
        // An "other"-group import precedes a java.* import — non-canonical grouping. The manager must
        // preserve that arrangement rather than force java.* to the top.
        String source = HEADER + "import a.Other;\nimport java.util.List;\n\nclass Use { Other o; List<String> l; }\n";
        ImportManager manager = new ImportManager(source);

        String rendered = manager.renderImportBlock();
        int otherIndex = rendered.indexOf("import a.Other;");
        int listIndex = rendered.indexOf("import java.util.List;");
        assertTrue(otherIndex >= 0 && listIndex > otherIndex, rendered);
    }

    @Test
    void sortsNewImportsIntoBlockWhenFileAlreadyCanonical() {
        // The file is already canonical (List before Map), so a newly added import is sorted into place.
        String source = HEADER
                + "import java.util.List;\n"
                + "import java.util.Map;\n\n"
                + "class Use { List<String> a; Map<String, String> b; }\n";
        ImportManager manager = new ImportManager(source);

        assertTrue(manager.addImport("java.util.ArrayList").isEmpty());

        String rendered = manager.renderImportBlock();
        int arrayListIndex = rendered.indexOf("import java.util.ArrayList;");
        int listIndex = rendered.indexOf("import java.util.List;");
        int mapIndex = rendered.indexOf("import java.util.Map;");
        assertTrue(arrayListIndex >= 0 && listIndex > arrayListIndex && mapIndex > listIndex, rendered);
    }

    @Test
    void preservesExistingConflictAndLineEndingContract() {
        // Mirrors the shared-infrastructure regression: a.List blocks java.util.List on simple name.
        ImportManager manager = new ImportManager("package demo;\r\n\r\nimport a.List;\r\n");

        assertTrue(manager.addImport("java.util.Map").isEmpty());
        assertEquals("import_conflict", manager.addImport("java.util.List").orElseThrow().code());
        assertTrue(manager.renderImportBlock().contains("\r\n"));
    }

    // ── Unified type-usage planning entry point (the V2 operation planners' single import subsystem) ───────────────

    private static final Path FILE = Path.of("demo/Use.java");

    @Test
    void planTypeUsageDeepSimplifiesAndPlansSingleTypeImport() {
        ImportManager manager = new ImportManager(HEADER + "class Use { java.util.Map m; }\n");

        ImportManager.TypeUse use = manager.planTypeUsageDeep(FILE, "java.util.Map", "K");

        assertEquals("Map", use.renderedType());
        assertEquals(1, use.importEdits().size());
        assertTrue(use.importEdits().get(0).newText().contains("import java.util.Map;"), use.importEdits().toString());
    }

    @Test
    void planTypeUsageDeepSimplifiesNestedGenericArgumentsAndPlansEachImport() {
        ImportManager manager = new ImportManager(HEADER + "class Use { Object o; }\n");

        ImportManager.TypeUse use = manager.planTypeUsageDeep(FILE, "java.util.Map<java.lang.String, java.util.List<java.time.Instant>>", "K");

        assertEquals("Map<String, List<Instant>>", use.renderedType());
        // java.util.Map, java.util.List and java.time.Instant each get a planned import (java.lang.String does not).
        assertEquals(3, use.importEdits().size(), use.importEdits().toString());
    }

    @Test
    void planTypeUsageDeepLeavesFqnOnExistingSingleTypeImportConflict() {
        // a.Date already claims the simple name Date, so java.util.Date must stay fully qualified with no new import.
        ImportManager manager = new ImportManager(HEADER + "import a.Date;\n\nclass Use { Date local; }\n");

        ImportManager.TypeUse use = manager.planTypeUsageDeep(FILE, "java.util.Date", "K");

        assertEquals("java.util.Date", use.renderedType());
        assertTrue(use.importEdits().isEmpty(), use.importEdits().toString());
    }

    @Test
    void planTypeUsageDeepFallsBackToFqnOnSemanticResolverConflict() {
        // No in-file import claims Helper, but the compiler-backed resolver reports a same-package/project collision:
        // the unified manager must leave the reference fully qualified and plan no import.
        ImportManager manager = new ImportManager(HEADER + "class Use { Object o; }\n")
                .withConflictResolver((simpleName, candidateFqn) -> simpleName.equals("Helper"));

        ImportManager.TypeUse use = manager.planTypeUsageDeep(FILE, "com.other.Helper", "K");

        assertEquals("com.other.Helper", use.renderedType());
        assertTrue(use.importEdits().isEmpty(), use.importEdits().toString());
    }

    @Test
    void planTypeUsageDeepTreatsWildcardCoveredTypeAsAlreadyImported() {
        ImportManager manager = new ImportManager(HEADER + "import java.util.*;\n\nclass Use { Object o; }\n");

        ImportManager.TypeUse use = manager.planTypeUsageDeep(FILE, "java.util.List", "K");

        assertEquals("List", use.renderedType());
        assertTrue(use.importEdits().isEmpty(), use.importEdits().toString());
    }

    @Test
    void planTypesForBodyPlansImportsForEachDependency() {
        ImportManager manager = new ImportManager(HEADER + "class Use { Object o; }\n");

        List<PlannerSupport.TextEdit> edits =
                manager.planTypesForBody(FILE, List.of("java.util.List", "java.lang.String", "demo.Sibling"), "K");

        // java.lang and same-package types are skipped; only java.util.List needs an import.
        assertEquals(1, edits.size(), edits.toString());
        assertTrue(edits.get(0).newText().contains("import java.util.List;"), edits.toString());
    }

    @Test
    void computeImportInsertionPlacesIntoExistingStyledBlock() {
        String source = HEADER + "import java.util.List;\n\nclass Use { List<String> l; }\n";

        ImportManager.ImportInsertion insertion = ImportManager.computeImportInsertion(source, "java.util.Map").orElseThrow();

        assertTrue(insertion.text().contains("import java.util.Map;"), insertion.text());
        // Already-covered types yield no insertion.
        assertTrue(ImportManager.computeImportInsertion(source, "java.util.List").isEmpty());
    }

    @Test
    void computeStaticImportInsertionAppendsAfterExistingStatics() {
        String source = HEADER + "import static a.B.first;\n\nclass Use { Object o = first(); }\n";

        ImportManager.ImportInsertion insertion =
                ImportManager.computeStaticImportInsertion(source, "a.B.second").orElseThrow();

        assertTrue(insertion.text().contains("import static a.B.second;"), insertion.text());
        assertTrue(ImportManager.computeStaticImportInsertion(source, "a.B.first").isEmpty());
    }
}
