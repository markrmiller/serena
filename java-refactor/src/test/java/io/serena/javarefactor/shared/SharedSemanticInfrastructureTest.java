package io.serena.javarefactor.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.serena.javarefactor.operations.hierarchy.MemberDescriptor;
import io.serena.javarefactor.operations.hierarchy.TypeDescriptor;
import io.serena.javarefactor.operations.hierarchy.TypeHierarchyIndex;
import io.serena.javarefactor.planners.PlanningContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedSemanticInfrastructureTest {
    @Test
    void hierarchyIndexTracksDirectAndTransitiveRelationships() {
        TypeDescriptor base = type("demo.Base", Set.of(), Set.of("demo.Child"));
        TypeDescriptor mid = type("demo.Mid", Set.of("demo.Base"), Set.of());
        TypeDescriptor child = type("demo.Child", Set.of("demo.Mid"), Set.of());
        TypeHierarchyIndex index = new TypeHierarchyIndex(List.of(base, mid, child));

        assertEquals(Set.of("demo.Mid"), index.directSubtypes("demo.Base"));
        assertEquals(Set.of("demo.Mid", "demo.Child"), index.allSubtypes("demo.Base"));
        assertEquals(Set.of("demo.Mid", "demo.Base"), index.allSupertypes("demo.Child"));
        assertEquals(Set.of("demo.Child"), index.permittedSubtypes("demo.Base"));
    }

    @Test
    void hierarchyIndexReportsUnresolvedAndAmbiguousTypes() {
        TypeHierarchyIndex index = new TypeHierarchyIndex(List.of(type("a.Demo", Set.of(), Set.of()), type("b.Demo", Set.of(), Set.of())));

        assertEquals("ambiguous_type", index.refusalForUnknownOrAmbiguous("Demo").orElseThrow().code());
        assertEquals("unresolved_type", index.refusalForUnknownOrAmbiguous("Missing").orElseThrow().code());
        assertFalse(index.refusalForUnknownOrAmbiguous("a.Demo").isPresent());
    }

    @Test
    void methodBodyModelSummarizesDataFlowAndBoundaries() {
        MethodBodyModel model = MethodBodyModel.fromSnippet("""
                int total = input;
                this.total = total + helper.compute(input);
                Runnable later = () -> input.toString();
                if (total > 10) {
                    throw new java.io.IOException();
                }
                return;
                """);

        assertTrue(model.reads().contains("input"));
        assertTrue(model.writes().contains("total"));
        assertTrue(model.calls().contains("compute"));
        assertTrue(model.referencedTypes().contains("int"));
        assertTrue(model.usesThis());
        assertTrue(model.hasControlFlowExit());
        assertTrue(model.checkedExceptions().contains("java.io.IOException"));
        assertTrue(model.crossesLambdaBoundary());
    }

    @Test
    void methodBodyModelCapturesElementIdentityReadsWritesCallsAndControlFlow() {
        MethodBodyModel model = MethodBodyModel.fromSource(
                """
                import java.util.concurrent.Callable;
                class Sample {
                    int counter;
                    int base() { return 1; }
                    int run(int amount) throws Exception {
                        synchronized (this) {
                            this.counter += amount;
                        }
                        Callable<Integer> task = () -> counter;
                        if (amount < 0) {
                            throw new java.io.IOException("bad");
                        }
                        return base() + super.hashCode();
                    }
                }
                """,
                "run");

        assertEquals("Sample#run(int)", model.methodKey());
        assertTrue(model.elementWrites().contains("Sample#counter"));
        assertTrue(model.elementReads().stream().anyMatch(key -> key.endsWith("#amount")));
        assertTrue(model.elementCalls().contains("Sample#base()"));
        assertTrue(model.referencedTypeKeys().contains("java.util.concurrent.Callable"));
        assertFalse(model.statements().isEmpty());
        assertTrue(model.usesThis());
        assertTrue(model.usesSuper());
        assertTrue(model.hasReturn());
        assertTrue(model.hasThrow());
        assertTrue(model.hasControlFlowExit());
        assertTrue(model.usesSynchronized());
        assertTrue(model.usesLambda());
        assertTrue(model.crossesLambdaBoundary());
        assertTrue(model.checkedExceptions().contains("java.io.IOException"));
    }

    @Test
    void methodBodyModelDistinguishesSameNamedFieldLocalAndParameterByElementIdentity() {
        MethodBodyModel model = MethodBodyModel.fromSource(
                """
                class Demo {
                    int value;
                    int compute(int value) {
                        int local = value;
                        this.value = local;
                        return this.value;
                    }
                }
                """,
                "compute");

        // The field write is keyed precisely to the owner; the parameter read is keyed to the declaration site.
        assertTrue(model.elementWrites().contains("Demo#value"));
        assertTrue(model.elementReads().stream().anyMatch(key -> key.endsWith("#value") && !key.equals("Demo#value")));
        // Blocker 6: reads and writes are independent, so the simple-name "value" parameter read is retained even though
        // a *different* "value" (the field) is written — the read is no longer masked by the write. Element identity is
        // what keeps the two distinct; the pure-external view subtracts the written name when a caller wants inputs only.
        assertTrue(model.reads().contains("value"));
        assertFalse(model.pureExternalReads().contains("value"));
    }

    @Test
    void purityAnalyzerClassifiesUnsafeEvaluationCasesConservatively() {
        ExpressionPurityAnalyzer analyzer = new ExpressionPurityAnalyzer();

        assertEquals(ExpressionPurity.PURE, analyzer.classify("customer.name"));
        assertEquals(ExpressionPurity.UNKNOWN, analyzer.classify("nextValue()"));
        assertEquals(ExpressionPurity.SIDE_EFFECTING, analyzer.classify("counter++"));
        assertEquals(ExpressionPurity.ALLOCATION_ONLY, analyzer.classify("new Widget()"));
        assertEquals(ExpressionPurity.ALLOCATION_ONLY, analyzer.classify("new int[] { 1, 2, 3 }"));
        assertEquals(ExpressionPurity.SIDE_EFFECTING, analyzer.classify("new Widget(counter++)"));
    }

    @Test
    void contextPurityTreatsFinalAndEffectivelyFinalReadsAsStable() {
        ExpressionPurityAnalyzer analyzer = new ExpressionPurityAnalyzer();

        ExpressionPurityAnalyzer.PurityAnalysis finalField =
                analyzer.analyzeReturnExpression("class C { final int f = 1; int m() { return f; } }", "m");
        assertEquals(ExpressionPurity.PURE, finalField.purity());
        assertTrue(finalField.isReorderSafe());
        assertFalse(finalField.readsNonFinalState());

        ExpressionPurityAnalyzer.PurityAnalysis effectivelyFinalLocal =
                analyzer.analyzeReturnExpression("class C { int m(int p) { int x = p; return x; } }", "m");
        assertEquals(ExpressionPurity.PURE, effectivelyFinalLocal.purity());
        assertTrue(effectivelyFinalLocal.isReorderSafe());
    }

    @Test
    void contextPurityFlagsMutableStateReadsAsPureButNotReorderSafe() {
        ExpressionPurityAnalyzer analyzer = new ExpressionPurityAnalyzer();

        ExpressionPurityAnalyzer.PurityAnalysis nonFinalField =
                analyzer.analyzeReturnExpression("class C { int f = 1; int m() { return f; } }", "m");
        assertEquals(ExpressionPurity.PURE, nonFinalField.purity());
        assertTrue(nonFinalField.readsNonFinalState());
        assertFalse(nonFinalField.isReorderSafe());

        ExpressionPurityAnalyzer.PurityAnalysis reassignedLocal =
                analyzer.analyzeReturnExpression("class C { int m(int p) { int x = p; x = x + 1; return x; } }", "m");
        assertEquals(ExpressionPurity.PURE, reassignedLocal.purity());
        assertTrue(reassignedLocal.readsNonFinalState());
        assertFalse(reassignedLocal.isReorderSafe());
    }

    @Test
    void contextPurityTreatsMutatingCallAsImpure() {
        ExpressionPurityAnalyzer analyzer = new ExpressionPurityAnalyzer();

        ExpressionPurityAnalyzer.PurityAnalysis call = analyzer.analyzeReturnExpression(
                "class C { java.util.List<String> list = new java.util.ArrayList<>();"
                        + " boolean m(String s) { return list.add(s); } }",
                "m");
        assertEquals(ExpressionPurity.UNKNOWN, call.purity());
        assertFalse(call.isPure());
        assertFalse(call.isReorderSafe());
    }

    @Test
    void contextPurityClassifiesMethodReferencesByTargetEffects() {
        ExpressionPurityAnalyzer analyzer = new ExpressionPurityAnalyzer();

        ExpressionPurityAnalyzer.PurityAnalysis sideEffecting = analyzer.analyzeReturnExpression(
                "class C { int count; void mutate() { this.count++; } Runnable m() { return this::mutate; } }",
                "m");
        assertEquals(ExpressionPurity.SIDE_EFFECTING, sideEffecting.purity());
        assertFalse(sideEffecting.isReorderSafe());

        ExpressionPurityAnalyzer.PurityAnalysis pureTarget = analyzer.analyzeReturnExpression(
                "class C { final int count = 0; int getCount() { return this.count; }"
                        + " java.util.function.IntSupplier m() { return this::getCount; } }",
                "m");
        assertEquals(ExpressionPurity.ALLOCATION_ONLY, pureTarget.purity());
    }

    @Test
    void contextPurityFlagsEvaluationOrderSensitivityFromSideEffectingArguments() {
        ExpressionPurityAnalyzer analyzer = new ExpressionPurityAnalyzer();

        ExpressionPurityAnalyzer.PurityAnalysis sensitive = analyzer.analyzeReturnExpression(
                "class C { int counter; int compute(int v) { return v; } int m() { return compute(counter++); } }",
                "m");
        assertTrue(sensitive.evaluationOrderSensitive());
        assertFalse(sensitive.isReorderSafe());
    }

    @Test
    void importManagerDetectsConflictsAndPreservesLineEndings() {
        ImportManager manager = new ImportManager("package demo;\r\n\r\nimport a.List;\r\n");

        assertTrue(manager.addImport("java.util.Map").isEmpty());
        assertEquals("import_conflict", manager.addImport("java.util.List").orElseThrow().code());
        assertTrue(manager.renderImportBlock().contains("\r\n"));
    }

    @Test
    void importRewritePlannerPreservesStaticAndWildcardImportsWhileSorting() {
        String source = "package demo;\n\nimport java.util.*;\n\nimport static java.util.Collections.emptyList;\n\nclass Use {}\n";
        ImportRewritePlanner planner = new ImportRewritePlanner(source);

        ImportRewritePlanner.TypeUse listUse = planner.planTypeUsage(Path.of("Use.java"), "java.util.List", "IMPORT");
        ImportRewritePlanner.TypeUse pathUse = planner.planTypeUsage(Path.of("Use.java"), "java.nio.file.Path", "IMPORT");

        assertEquals("List", listUse.renderedType());
        assertTrue(listUse.importEdit().isEmpty());
        assertEquals("Path", pathUse.renderedType());
        assertEquals("import java.nio.file.Path;\n", pathUse.importEdit().orElseThrow().newText());
    }

    @Test
    void importRewritePlannerFallsBackToFullyQualifiedNamesOnSimpleNameConflict() {
        String source = "package demo;\n\nimport java.sql.Date;\n\nclass Use {}\n";
        ImportRewritePlanner planner = new ImportRewritePlanner(source);

        ImportRewritePlanner.TypeUse dateUse = planner.planTypeUsage(Path.of("Use.java"), "java.util.Date", "IMPORT");

        assertEquals("java.util.Date", dateUse.renderedType());
        assertTrue(dateUse.fullyQualified());
        assertTrue(dateUse.conflict());
        assertTrue(dateUse.importEdit().isEmpty());
    }

    @Test
    void importRewritePlannerAvoidsJavaLangAndSamePackageImports() {
        String source = "package demo;\n\nclass Use {}\n";
        ImportRewritePlanner planner = new ImportRewritePlanner(source);

        ImportRewritePlanner.TypeUse stringUse = planner.planTypeUsage(Path.of("Use.java"), "java.lang.String", "IMPORT");
        ImportRewritePlanner.TypeUse siblingUse = planner.planTypeUsage(Path.of("Use.java"), "demo.Sibling", "IMPORT");

        assertEquals("String", stringUse.renderedType());
        assertEquals("Sibling", siblingUse.renderedType());
        assertTrue(stringUse.importEdit().isEmpty());
        assertTrue(siblingUse.importEdit().isEmpty());
    }

    @Test
    void importRewritePlannerRemovesOnlyExactStaleSingleTypeImports() {
        String source = "package demo;\n\nimport demo.Old;\nimport demo.*;\nimport static demo.Old.VALUE;\n\nclass Use {}\n";
        ImportRewritePlanner planner = new ImportRewritePlanner(source);

        assertTrue(planner.planStaleImportRemoval(Path.of("Use.java"), "demo.Old", "IMPORT").isPresent());
        assertTrue(planner.planStaleImportRemoval(Path.of("Use.java"), "demo.Missing", "IMPORT").isEmpty());
    }

    @Test
    void javaStyleProfileInfersLineEndingsIndentAndFinalParameters() {
        JavaStyleProfile style = JavaStyleProfile.infer("class Sample {\r\n\tvoid run(final String value) {\r\n\t\tSystem.out.println(value);\r\n\t}\r\n}\r\n");

        assertEquals("\r\n", style.lineEnding());
        assertEquals("\t", style.indentUnit());
        assertEquals("\t", style.memberIndent());
        assertEquals("\t", style.outerIndentFor("\t\t"));
        assertEquals("final String value", style.parameter("String", "value"));
        assertEquals("\t\treturn value;", style.indentLines("return value;", "\t\t"));
    }


    @Test
    void javaStyleProfileRendersMembersAndInterfacesWithLocalizedStyle() {
        JavaStyleProfile style = JavaStyleProfile.infer("class Sample {\r\n\tvoid run(final String value) {\r\n\t\tSystem.out.println(value);\r\n\t}\r\n}\r\n");

        assertEquals("\r\n\tprivate final List<String> names = null;\r\n", style.renderField("private final", "List<String>", "names", "null"));
        assertEquals("\r\n\tprivate static String label() {\r\n\t\treturn \"x\";\r\n\t}\r\n", style.renderMethod("private static String label()", "return \"x\";"));
        assertEquals(
                "package api;\r\n\r\nimport java.util.List;\r\n\r\npublic interface Named {\r\n\tString name();\r\n}\r\n",
                style.renderInterfaceSource("api", "Named", List.of("import java.util.List;"), List.of("\tString name();")));
    }

    @Test
    void javaStyleProfileInfersNonDefaultFormattingStyle() {
        // Source uses: tabs, static-imports-first, 2 blank lines between members,
        // annotations on own line, and K&R braces — all non-default combinations exercised.
        String source = "package demo;\n"
                + "\n"
                + "import static java.util.Objects.requireNonNull;\n"
                + "import java.util.List;\n"
                + "\n"
                + "class Sample {\n"
                + "\t@Override\n"
                + "\tpublic String toString() {\n"
                + "\t\treturn \"x\";\n"
                + "\t}\n"
                + "\n"
                + "\n"
                + "\t@SuppressWarnings(\"unused\")\n"
                + "\tvoid run(final String value) {\n"
                + "\t\tSystem.out.println(value);\n"
                + "\t}\n"
                + "}\n";

        JavaStyleProfile style = JavaStyleProfile.infer(source);

        assertEquals("\t", style.indentUnit());
        assertTrue(style.finalParameters());
        assertTrue(style.staticImportsFirst());
        assertTrue(style.annotationsOnOwnLine());
        assertTrue(style.openBraceSameLine());
        assertEquals(2, style.blankLinesBetweenMembers());
        // memberSeparator produces: lineEnding * (blankLinesBetweenMembers + 1)
        assertEquals("\n\n\n", style.memberSeparator());
        // renderAnnotationPrefix emits each annotation on its own indented line
        assertEquals("\t@Override\n\t@Deprecated\n",
                style.renderAnnotationPrefix(List.of("@Override", "@Deprecated"), "\t"));
        // renderAnnotatedMethod prepends annotation block before the method
        String method = style.renderAnnotatedMethod(List.of("@Override"), "public String foo()", "return \"x\";");
        assertTrue(method.contains("@Override"), "rendered method must contain @Override");
        assertTrue(method.contains("\tpublic String foo() {"), "rendered method must have header with K&R brace");
        // openBrace helper honours K&R style
        assertEquals(" {", style.openBrace("\t"));
    }

    @Test
    void accessPlannerRefusesUnsafeAccessAndPlansVisibilityWidening() {
        AccessPlanner planner = new AccessPlanner();
        MemberDescriptor sensitivePrivateMember = member("apiToken", Set.of("private"));
        MemberDescriptor privateMember = member("value", Set.of("private"));
        MemberDescriptor packageMember = member("counter", Set.of());

        AccessPlan refused = planner.plan(sensitivePrivateMember, "demo", "other", false);
        AccessPlan protectedPlan = planner.plan(privateMember, "demo", "other", true);
        AccessPlan publicPlan = planner.plan(packageMember, "demo", "other", false);

        assertEquals("security_sensitive_private_widening", refused.refusal().code());
        assertEquals("protected", protectedPlan.requiredVisibility());
        assertEquals("public", publicPlan.requiredVisibility());
        assertTrue(publicPlan.publicApiWidening());
        assertFalse(publicPlan.warnings().isEmpty());
    }

    @Test
    void accessAdjustmentPlannerRewritesModifiersAndPreservesIndentation() {
        AccessAdjustmentPlanner planner = new AccessAdjustmentPlanner();
        AccessPlan publicPlan = AccessPlan.allowed("public", true, List.of("widened"));
        AccessPlan packagePrivatePlan = AccessPlan.allowed("package-private");

        assertEquals("    public static final ", planner.rewriteModifiers("    private static final ", publicPlan));
        assertEquals("    static ", planner.rewriteModifiers("    protected static ", packagePrivatePlan));
        assertTrue(publicPlan.publicApiWidening());
    }

    @Test
    void planningContextProvidesSharedInfrastructureToOperationPlanners() {
        TypeHierarchyIndex index = new TypeHierarchyIndex(List.of(type("demo.Base", Set.of(), Set.of())));
        ImportManager imports = new ImportManager("package demo;\n");
        PlanningContext context = new PlanningContext(index, new ExpressionPurityAnalyzer(), new AccessPlanner(), imports);

        assertTrue(context.hierarchyIndex().type("demo.Base").isPresent());
        assertEquals(ExpressionPurity.PURE, context.purityAnalyzer().classify("value"));
    }

    private static TypeDescriptor type(String qualifiedName, Set<String> supertypes, Set<String> permittedSubtypes) {
        return new TypeDescriptor(
                qualifiedName,
                qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
                supertypes,
                permittedSubtypes,
                List.of(member("value", Set.of("public"))),
                Set.of("public"),
                new SourceLocation(qualifiedName.replace('.', '/') + ".java", 1, 1, 1, 10),
                List.of());
    }

    private static MemberDescriptor member(String name, Set<String> modifiers) {
        return new MemberDescriptor(name, "field", modifiers, new SourceLocation("Demo.java", 1, 1, 1, 10));
    }
}
