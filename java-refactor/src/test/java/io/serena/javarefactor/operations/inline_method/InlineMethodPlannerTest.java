package io.serena.javarefactor.operations.inline_method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.serena.javarefactor.operations.inline_method.InlineMethodPlanner.MethodBody;
import io.serena.javarefactor.operations.inline_method.InlineMethodPlanner.MethodBodyKind;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.shared.ExpressionPurityAnalyzer;
import io.serena.javarefactor.shared.MethodBodyModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for the AST-backed inline-method substitution (G019): precedence-correct parenthesization of argument
 * substitutions and javac-backed body classification with its constraint refusals.
 */
class InlineMethodPlannerTest {

    private static final ExpressionPurityAnalyzer PURITY = new ExpressionPurityAnalyzer();

    @Test
    void modelBodyClassifiesSingleReturnExpressionFromTheParsedAst() {
        MethodBodyModel model = MethodBodyModel.fromSource(
                "class T { int f(int a, int b) { return a + b; } }", "f");
        MethodBody body = InlineMethodPlanner.modelBody(model, "f", "int", PURITY);
        assertEquals(MethodBodyKind.RETURN_EXPRESSION, body.kind());
        assertEquals("a + b", body.expression());
    }

    @Test
    void modelBodyClassifiesEffectfulVoidExpressionStatement() {
        MethodBodyModel model = MethodBodyModel.fromSource(
                "class T { void f() { g(); } void g() {} }", "f");
        MethodBody body = InlineMethodPlanner.modelBody(model, "f", "void", PURITY);
        assertEquals(MethodBodyKind.VOID_EXPRESSION_STATEMENT, body.kind());
        assertEquals("g()", body.expression());
    }

    @Test
    void modelBodyRefusesMultiStatementBodyWithMoreThanOneReturn() {
        MethodBodyModel model = MethodBodyModel.fromSource(
                "class T { int f(boolean x) { if (x) return 1; return 2; } }", "f");
        InlineMethodPlanner.Refusal refusal =
                assertThrows(InlineMethodPlanner.Refusal.class, () -> InlineMethodPlanner.modelBody(model, "f", "int", PURITY));
        assertEquals("statement_body_unsupported", refusal.code());
    }

    @Test
    void modelBodyRefusesBodyThatReferencesSuper() {
        MethodBodyModel model = MethodBodyModel.fromSource(
                "class T { int f() { return super.hashCode(); } }", "f");
        InlineMethodPlanner.Refusal refusal =
                assertThrows(InlineMethodPlanner.Refusal.class, () -> InlineMethodPlanner.modelBody(model, "f", "int", PURITY));
        assertEquals("super_body_unsupported", refusal.code());
    }

    @Test
    void modelBodyRefusesRecursiveBody() {
        MethodBodyModel model = MethodBodyModel.fromSource(
                "class T { int f() { return f(); } }", "f");
        InlineMethodPlanner.Refusal refusal =
                assertThrows(InlineMethodPlanner.Refusal.class, () -> InlineMethodPlanner.modelBody(model, "f", "int", PURITY));
        assertEquals("recursive_body_unsupported", refusal.code());
    }

    @Test
    void modelBodyIsAvailableForOverloadFreeMethods() {
        // Guards the primary path: a uniquely-named method is modelled by javac, so the parsed statement list is
        // non-empty.
        MethodBodyModel model = MethodBodyModel.fromSource(
                "class T { int f(int a) { return a + a; } }", "f");
        assertTrue(!model.statements().isEmpty());
    }

    // ── G018 integrated planner matrix (real inlineMethod over a javac-backed temp project) ─────────────────────────

    @Test
    void inlinesReceiverAndArgumentsAtAnExplicitReceiverCallSite(@TempDir Path tmp) throws IOException {
        // Matrix case 1: an `obj.scaled(factor)` call substitutes the explicit receiver into the body's `this.base`
        // member access and the argument into the parameter, producing `(other.base * arg)`.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int base = 2;\n"
                + "    private int scaled(int factor) {\n"
                + "        return this.base * factor;\n"
                + "    }\n"
                + "    int use(Sample other, int arg) {\n"
                + "        return other.scaled(arg);\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields("scaled");

        String json = run(tmp, source).inlineMethod(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        // Receiver `other` substituted for `this`, argument `arg` substituted for the parameter, whole result wrapped.
        assertTrue(json.contains("(other.base * arg)"), json);
        assertTrue(json.contains("INLINE_METHOD_CALL"), json);
    }

    @Test
    void parenthesizesSubstitutedArgumentToPreservePrecedenceAtCallSite(@TempDir Path tmp) throws IOException {
        // Matrix case 3: the argument `a + b` flows into the body template `value * 2`; it must be parenthesized so the
        // inlined expression keeps its meaning, yielding `((a + b) * 2)`.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private static int twice(int value) {\n"
                + "        return value * 2;\n"
                + "    }\n"
                + "    static int use(int a, int b) {\n"
                + "        return twice(a + b);\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields("twice");

        String json = run(tmp, source).inlineMethod(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("((a + b) * 2)"), json);
    }

    @Test
    void refusesInliningWhenAnArgumentWithSideEffectsWouldBeEvaluatedMoreThanOnce(@TempDir Path tmp) throws IOException {
        // Matrix case 2: the parameter `value` is used twice in the body, and the call passes a side-effecting argument
        // `next()`. Substituting it would change how many times the side effect runs (twice instead of once), so the
        // inline is refused. A non-reorder-safe argument is rejected by the per-argument evaluation-order gate
        // (`unsafe_argument`) before the duplicate-use gate is reached; either way the duplicated-side-effect inline is
        // never emitted. This asserts the refusal, not the specific gate that fires first.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private int n = 0;\n"
                + "    private static int twice(int value) {\n"
                + "        return value + value;\n"
                + "    }\n"
                + "    int next() {\n"
                + "        return ++n;\n"
                + "    }\n"
                + "    int use() {\n"
                + "        return twice(next());\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields("twice");

        String json = run(tmp, source).inlineMethod(fields, false);
        assertFalse(json.contains("\"accepted\":true"), json);
        // The side-effecting argument is refused: the duplicated-evaluation inline is never produced.
        assertTrue(json.contains("\"code\":\"unsafe_argument\"") || json.contains("\"code\":\"unsafe_argument_reuse\""), json);
        assertFalse(json.contains("(next() + next())"), json);
    }

    @Test
    void deletesTheMethodWhenDeleteRequestedAndNoReferencesRemain(@TempDir Path tmp) throws IOException {
        // Matrix case 4 (delete branch): every resolved call site is rewritten and deleteMethod is requested, so the
        // declaration is removed via an INLINE_METHOD_DELETE edit.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private static int twice(int value) {\n"
                + "        return value * 2;\n"
                + "    }\n"
                + "    static int use(int a) {\n"
                + "        return twice(a);\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields("twice");
        fields.put("deleteMethod", true);

        String json = run(tmp, source).inlineMethod(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("INLINE_METHOD_DELETE"), json);
    }

    @Test
    void keepsTheMethodWhenDeleteRequestedButAMethodReferenceRemains(@TempDir Path tmp) throws IOException {
        // Matrix case 4 (keep branch): a method-reference usage `Sample::twice` cannot be rewritten as an expression
        // substitution, so deletion would dangle a resolved reference. The inline is refused rather than deleting.
        String source = ""
                + "package demo;\n"
                + "import java.util.function.IntUnaryOperator;\n"
                + "public class Sample {\n"
                + "    private static int twice(int value) {\n"
                + "        return value * 2;\n"
                + "    }\n"
                + "    static IntUnaryOperator op() {\n"
                + "        return Sample::twice;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields("twice");
        fields.put("deleteMethod", true);

        String json = run(tmp, source).inlineMethod(fields, false);
        assertFalse(json.contains("INLINE_METHOD_DELETE"), json);
        assertTrue(json.contains("\"code\":\"method_reference_unsupported\""), json);
    }

    @Test
    void refusesWhenCallSiteCountExceedsMaxCallSites(@TempDir Path tmp) throws IOException {
        // G001: `twice` is called at two sites; with max_call_sites mapped to 1 (maxCallSites field) the inline blast
        // radius exceeds the cap and the planner refuses rather than silently rewriting both.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private static int twice(int value) {\n"
                + "        return value * 2;\n"
                + "    }\n"
                + "    static int use(int a, int b) {\n"
                + "        return twice(a) + twice(b);\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields("twice");
        fields.put("maxCallSites", 1);

        String json = run(tmp, source).inlineMethod(fields, false);
        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("max_call_sites_exceeded"), json);
    }

    @Test
    void acceptsMultipleCallSitesWhenWithinMaxCallSites(@TempDir Path tmp) throws IOException {
        // The same two-call-site inline is admitted when the cap permits it (default 100, here explicit 2).
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private static int twice(int value) {\n"
                + "        return value * 2;\n"
                + "    }\n"
                + "    static int use(int a, int b) {\n"
                + "        return twice(a) + twice(b);\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields("twice");
        fields.put("maxCallSites", 2);

        String json = run(tmp, source).inlineMethod(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
    }

    // ── G014: overload-safe body binding + cross-file resolvability ─────────────────────────────────────────────────

    @Test
    void bindsTheBodyOfTheSelectedOverloadNotAnotherOverloadOfTheSameName(@TempDir Path tmp) throws IOException {
        // Two `scale` overloads share a name but have different bodies. Selecting the (int) overload by its declaration
        // line must inline THAT overload's body (`v * 2`), never the (int,int) overload's body (`v * w`). The legacy
        // name-only MethodBodyModel.fromSource would return empty for the overloaded name and fall through to a text
        // match against the wrong body; position-anchored binding inlines the correct one.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private static int scale(int v) {\n"          // line 3, body returns v * 2
                + "        return v * 2;\n"
                + "    }\n"
                + "    private static int scale(int v, int w) {\n"
                + "        return v * w;\n"
                + "    }\n"
                + "    static int use(int a) {\n"
                + "        return scale(a);\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields("scale");
        fields.put("line", 3); // select the single-argument overload by its declaration line

        String json = run(tmp, source).inlineMethod(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        // The single-arg overload's body `v * 2` is inlined with the argument substituted: `(a * 2)`.
        assertTrue(json.contains("(a * 2)"), json);
        // The other overload's body shape `v * w` must never appear.
        assertFalse(json.contains("* w"), json);
    }

    @Test
    void inlinesTheTwoArgumentOverloadWhenItIsTheSelectedOne(@TempDir Path tmp) throws IOException {
        // The complement of the previous test: selecting the (int,int) overload inlines `v * w`, proving the binding
        // tracks the selected executable rather than always picking the first declaration.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private static int scale(int v) {\n"
                + "        return v * 2;\n"
                + "    }\n"
                + "    private static int scale(int v, int w) {\n"   // line 6, body returns v * w
                + "        return v * w;\n"
                + "    }\n"
                + "    static int use(int a, int b) {\n"
                + "        return scale(a, b);\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields("scale");
        fields.put("line", 6); // select the two-argument overload by its declaration line

        String json = run(tmp, source).inlineMethod(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("(a * b)"), json);
    }

    @Test
    void refusesToReadAnUnreadableForeignCallSiteFileWhenProvingCrossFileResolvability(@TempDir Path tmp) throws IOException {
        // G014: a foreign call-site file that cannot be read offers no proof the inlined body resolves there. The planner
        // refuses with a structured reason rather than ignoring the read failure. Driven directly through the package
        // seam: a non-existent foreign path forces SourceText.read to throw, which must surface as a Refusal.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private static int twice(int value) {\n"
                + "        return value * 2;\n"
                + "    }\n"
                + "}\n";
        InlineMethodPlanner planner = run(tmp, source);
        Path missingForeign = tmp.resolve("src/demo/DoesNotExist.java");

        InlineMethodPlanner.Refusal refusal = assertThrows(
                InlineMethodPlanner.Refusal.class,
                () -> planner.planForeignImportEdits(List.of(missingForeign), List.of("demo.Helper")));
        assertEquals("cross_site_resolvability_unproven", refusal.code());
    }

    @Test
    void plansNoImportEditsForAForeignFileWhenTheBodyReferencesNoTypes(@TempDir Path tmp) throws IOException {
        // When the body references no types, a readable foreign file yields no import edits (and crucially does not
        // refuse): readability is proven, there is simply nothing to import.
        String foreignSource = ""
                + "package demo;\n"
                + "public class Caller {\n"
                + "    int go() { return 0; }\n"
                + "}\n";
        Path foreign = tmp.resolve("src/demo/Caller.java");
        Files.createDirectories(foreign.getParent());
        Files.writeString(foreign, foreignSource, StandardCharsets.UTF_8);

        InlineMethodPlanner planner = run(tmp, "package demo;\npublic class Sample {}\n");
        assertTrue(planner.planForeignImportEdits(List.of(foreign), List.of()).isEmpty());
    }

    // ── HB-9: single-throw inlining + no token-substitution fallback ────────────────────────────────────────────────

    @Test
    void modelBodyClassifiesSingleThrowStatement() {
        MethodBodyModel model = MethodBodyModel.fromSource(
                "class T { void f(String m) { throw new RuntimeException(m); } }", "f");
        MethodBody body = InlineMethodPlanner.modelBody(model, "f", "void", PURITY);
        assertEquals(MethodBodyKind.THROW_STATEMENT, body.kind());
        assertEquals("new RuntimeException(m)", body.expression());
    }

    @Test
    void inlinesSingleThrowBodyAtStatementSiteWithCheckedExceptionHandled(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private void fail(String msg) throws java.io.IOException {\n"
                + "        throw new java.io.IOException(msg);\n"
                + "    }\n"
                + "    void use(String reason) {\n"
                + "        try { fail(reason); } catch (java.io.IOException e) { }\n"
                + "    }\n"
                + "}\n";
        String json = run(tmp, source).inlineMethod(baseFields("fail"), false);
        assertTrue(json.contains("\"accepted\":true"), json);
        // The void call statement `fail(reason);` becomes the throw statement `throw new ...IOException(reason);`.
        assertTrue(json.contains("throw new"), json);
        assertTrue(json.contains("IOException(reason)"), json);
    }

    @Test
    void refusesSingleThrowInlineAtExpressionPositionCallSite(@TempDir Path tmp) throws IOException {
        // `boom` always throws but is declared `int`, so it may be called in an expression position; a throw cannot be
        // spliced into an expression, so the inline is refused rather than producing invalid code.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private int boom(int x) {\n"
                + "        throw new IllegalStateException(\"bad \" + x);\n"
                + "    }\n"
                + "    int use(int a) {\n"
                + "        return boom(a) + 1;\n"
                + "    }\n"
                + "}\n";
        String json = run(tmp, source).inlineMethod(baseFields("boom"), false);
        assertTrue(json.contains("\"code\":\"void_call_context_unsupported\""), json);
    }

    @Test
    void refusesUnmodellableBodyInsteadOfDegradingToTokenSubstitution(@TempDir Path tmp) throws IOException {
        // The body returns an anonymous class capturing the parameter; the AST substitution cannot model the nested
        // class, so the engine must REFUSE (HB-9) rather than fall back to a token-level rewrite that could corrupt it.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    private Object box(int seed) {\n"
                + "        return new Object() { int v = seed; };\n"
                + "    }\n"
                + "    int use(int a) {\n"
                + "        return box(a).hashCode();\n"
                + "    }\n"
                + "}\n";
        String json = run(tmp, source).inlineMethod(baseFields("box"), false);
        assertTrue(json.contains("\"code\":\"inline_body_unmodellable\""), json);
        assertFalse(json.contains("\"accepted\":true"), json);
    }

    // ── Harness ──────────────────────────────────────────────────────────────

    private static Map<String, Object> baseFields(String methodName) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/demo/Sample.java");
        fields.put("methodName", methodName);
        return fields;
    }

    private InlineMethodPlanner run(Path tmp, String source) throws IOException {
        JavaProjectModel model = singleFileModel(tmp, source);
        return new InlineMethodPlanner(tmp.toAbsolutePath().normalize(), model);
    }

    private static JavaProjectModel singleFileModel(Path root, String source) throws IOException {
        Path sourceRoot = root.resolve("src");
        Path pkg = sourceRoot.resolve("demo");
        Files.createDirectories(pkg);
        Path javaFile = pkg.resolve("Sample.java");
        Files.writeString(javaFile, source, StandardCharsets.UTF_8);
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                List.of(javaFile),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "17",
                null,
                null,
                "UTF-8",
                false,
                "none",
                List.of(),
                false,
                List.of("-source", "17", "-target", "17", "-encoding", "UTF-8"),
                List.of(),
                List.of());
        return new JavaProjectModel(
                root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }
}
