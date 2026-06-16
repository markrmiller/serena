package io.serena.javarefactor.operations.encapsulate_field;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Planner-level proof that the real {@link FieldRefactorPlanner} implements G017 (semantic introduce field) and G018
 * (semantic encapsulate field) against a javac-backed temp project: it refuses initialization-order-unsafe inline
 * introductions, accepts compile-time-constant selections, rewrites reads/writes through generated accessors, refuses
 * compound/increment usages, and refuses generated / Lombok-managed declaring sources unless the caller opts in.
 *
 * <p>Each case runs the planner directly (no protocol layer) so the planner's own semantic gates — not Main's
 * pre-dispatch policy gate — are what is exercised.
 */
class FieldRefactorPlannerTest {

    // ── G017: introduce field ────────────────────────────────────────────────

    @Test
    void introduceFieldRefusesInitializationOrderChange(@TempDir Path tmp) throws IOException {
        // `base + 1` is pure but NOT a compile-time constant and is read inside a method body; hoisting it to an inline
        // instance-field initializer would move a runtime expression out of its method's evaluation order.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int base = 5;\n"
                + "    int compute() {\n"
                + "        return base + 1;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "derived");
        fields.put("selection", selectionFor(source, "base + 1"));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"code\":\"initialization_order_unsupported\""), json);
    }

    @Test
    void introduceFieldImportsEveryComponentOfANestedGenericFieldTypeAndRendersSimple(@TempDir Path tmp)
            throws IOException {
        // G002: the introduced field's declared type is a nested generic; deep import planning must import Map, List,
        // and Integer (java.lang.Integer is implicitly visible) and render the declaration with simple names.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    Object label() {\n"
                + "        return \"value\";\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "cache");
        fields.put("fieldType", "java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>");
        fields.put("selection", selectionFor(source, "\"value\""));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        // Both java.util components are imported (java.lang.* needs no import).
        assertTrue(json.contains("import java.util.Map;"), json);
        assertTrue(json.contains("import java.util.List;"), json);
        // The field renders with simple names, not the fully-qualified nested generic.
        assertTrue(json.contains("Map<String, List<Integer>> cache"), json);
        assertFalse(json.contains("java.util.Map<java.lang.String"), json);
    }

    @Test
    void introduceFieldAcceptsCompileTimeConstantSelection(@TempDir Path tmp) throws IOException {
        // A string literal is a compile-time constant with no instance dependency; the inline final field is safe.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    String label() {\n"
                + "        return \"value\";\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "labelText");
        fields.put("selection", selectionFor(source, "\"value\""));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("private final String labelText"), json);
    }

    // ── G018: encapsulate field ──────────────────────────────────────────────

    @Test
    void encapsulateFieldRewritesReadsAndWritesThroughAccessors(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    int read() {\n"
                + "        return count;\n"
                + "    }\n"
                + "    void write(int value) {\n"
                + "        count = value;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        // G001: internal-usage rewriting is opt-in (default false); this test proves the rewrite, so enable it.
        fields.put("rewriteInternalUsages", true);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("private int count = 1;"), json);
        assertTrue(json.contains("getCount()"), json);
        assertTrue(json.contains("setCount(value)"), json);
        assertTrue(json.contains("public int getCount()"), json);
        assertTrue(json.contains("public void setCount(int value)"), json);
    }

    @Test
    void encapsulateFieldRefusesCompoundAssignment(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    void bump(int value) {\n"
                + "        count += value;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"compound_field_usage\""), json);
    }

    @Test
    void encapsulateFieldRefusesGeneratedDeclaringSource(@TempDir Path tmp) throws IOException {
        String source = simpleCountSource();
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");

        // The declaring source root is reported as a build-model generated root and the caller did not opt in.
        String json = run(tmp, source, true).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"non_editable_target\""), json);
    }

    @Test
    void encapsulateFieldAllowsGeneratedDeclaringSourceWithOptIn(@TempDir Path tmp) throws IOException {
        String source = simpleCountSource();
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("allowGenerated", true);

        String json = run(tmp, source, true).encapsulateField(fields, false);
        assertFalse(json.contains("\"code\":\"non_editable_target\""), json);
        assertTrue(json.contains("\"accepted\":true"), json);
    }

    @Test
    void encapsulateFieldRefusesLombokManagedSource(@TempDir Path tmp) throws IOException {
        // Lombok-managed declaring source is refused at the planner gate (before compilation) unless allowLombok is set.
        String source = ""
                + "package demo;\n"
                + "import lombok.Getter;\n"
                + "@Getter\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"lombok_managed_source_refused\""), json);
    }

    @Test
    void encapsulateFieldPreservesFieldAnnotations(@TempDir Path tmp) throws IOException {
        // An annotated field is made private with its annotation kept in place (the visibility keyword is inserted after
        // the annotation block, never in front of it), and accessors are generated.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    @Deprecated\n"
                + "    int count = 1;\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        // The @Deprecated annotation is preserved and the inserted `private ` lands after it, before `int`.
        assertTrue(json.contains("@Deprecated"), json);
        assertTrue(json.contains("private int count = 1;"), json);
        // The visibility keyword is inserted after the annotation block, never in front of it.
        assertFalse(json.contains("private @Deprecated"), json);
        assertTrue(json.contains("public int getCount()"), json);
    }

    @Test
    void encapsulateFieldGeneratesIsAccessorForBooleanField(@TempDir Path tmp) throws IOException {
        // With no explicit getterName, a boolean field's default getter must be `isActive()`, not `getActive()`.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    boolean active = true;\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "active");
        // No getterName / setterName: exercise the default boolean-getter naming.

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("public boolean isActive()"), json);
        assertFalse(json.contains("getActive()"), json);
        assertTrue(json.contains("public void setActive(boolean value)"), json);
    }

    @Test
    void encapsulateFieldRefusesAccessorCollisionWithLocation(@TempDir Path tmp) throws IOException {
        // A no-arg `getCount()` already exists, conflicting with the generated getter's signature. The planner refuses
        // with a precise location and the accessor_collision code.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    int getCount() {\n"
                + "        return count;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"accessor_collision\""), json);
        // The refusal carries the colliding declaration's relativePath:line:column location.
        assertTrue(json.contains("src/demo/Sample.java:4:"), json);
    }

    @Test
    void encapsulateFieldAllowsDistinctOverloadThatIsNotAnAccessorCollision(@TempDir Path tmp) throws IOException {
        // A method named `getCount` that takes an argument is a distinct overload, not a collision with the no-arg
        // generated getter, so encapsulation proceeds.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    int getCount(int bias) {\n"
                + "        return count + bias;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("accessor_collision"), json);
        assertTrue(json.contains("public int getCount()"), json);
    }

    // ── G027: safe inline-init proof ─────────────────────────────────────────

    @Test
    void introduceFieldAcceptsSideEffectFreeAllocationInline(@TempDir Path tmp) throws IOException {
        // `new int[]{1, 2, 3}` is a side-effect-free fresh allocation read in an instance method: it depends on no
        // instance state, captures no locals, and throws nothing checked, so hoisting it to an inline final field
        // initializer is order-equivalent and must be accepted (not refused as initialization_order_unsupported).
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int[] make() {\n"
                + "        return new int[]{1, 2, 3};\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "numbers");
        fields.put("selection", selectionFor(source, "new int[]{1, 2, 3}"));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("private final int[] numbers"), json);
        assertFalse(json.contains("initialization_order_unsupported"), json);
    }

    @Test
    void introduceFieldRefusesAllocationReadingNonFinalStateInline(@TempDir Path tmp) throws IOException {
        // G004: `new int[]{ Sample.seed }` is ALLOCATION_ONLY under the coarse classify() screen (which the old gate used
        // to green-light it), but its real AST node reads the NON-FINAL static field `seed`, so the canonical javac
        // TreePath verdict (SemanticIndex.isExpressionReorderSafe) proves it is NOT reorder-safe: hoisting it to an inline
        // field initializer could observe a different value than at its original position. It must be refused.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    static int seed = 5;\n"
                + "    int[] make() {\n"
                + "        return new int[]{ Sample.seed };\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "numbers");
        fields.put("selection", selectionFor(source, "new int[]{ Sample.seed }"));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"code\":\"initialization_order_unsupported\""), json);
    }

    @Test
    void introduceFieldRefusesDetachedNonConstantInitializerText(@TempDir Path tmp) throws IOException {
        // A detached initializer string has no resolvable symbols, so its reorder safety cannot be proven; only a
        // literal constant is accepted without a semantic selection. `other + 1` is neither, so it is refused.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int other = 7;\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "derived");
        fields.put("initializer", "other + 1");

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"code\":\"initialization_order_unsupported\""), json);
    }

    // ── G028: scope-correct replacement qualification ────────────────────────

    @Test
    void introduceFieldQualifiesReplacementWhenLocalShadowsField(@TempDir Path tmp) throws IOException {
        // The enclosing method has a parameter named `labelText`; an unqualified replacement would bind to that
        // parameter, so the selected expression must be replaced with `this.labelText` to reach the new field.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    String build(String labelText) {\n"
                + "        return wrap(\"value\");\n"
                + "    }\n"
                + "    String wrap(String s) {\n"
                + "        return s;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "labelText");
        fields.put("selection", selectionFor(source, "\"value\""));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"newText\":\"this.labelText\""), json);
    }

    @Test
    void introduceFieldUsesUnqualifiedReplacementWhenNoShadow(@TempDir Path tmp) throws IOException {
        // No same-named local/parameter is in scope, so the proven-safe unqualified reference is used.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    String label() {\n"
                + "        return \"value\";\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "labelText");
        fields.put("selection", selectionFor(source, "\"value\""));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"newText\":\"labelText\""), json);
        assertFalse(json.contains("this.labelText"), json);
    }

    // ── HB-7: javac scope-binding chooses qualified vs unqualified replacement ──────────────────────────────────────

    /** An implicitly-typed lambda parameter shadows the field at the selection; the old `<type> name` regex missed it. */
    @Test
    void introduceFieldQualifiesWhenImplicitLambdaParameterShadows(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int m() {\n"
                + "        java.util.function.Function<String, Integer> f = labelText -> wrap(\"value\");\n"
                + "        return f.apply(\"x\");\n"
                + "    }\n"
                + "    int wrap(String s) { return s.length(); }\n"
                + "}\n";
        assertQualifiedReplacement(tmp, source);
    }

    /** A pattern-binding variable in scope at the selection forces qualification. */
    @Test
    void introduceFieldQualifiesWhenPatternBindingShadows(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int m(Object o) {\n"
                + "        if (o instanceof String labelText) {\n"
                + "            return wrap(\"value\") + labelText.length();\n"
                + "        }\n"
                + "        return 0;\n"
                + "    }\n"
                + "    int wrap(String s) { return s.length(); }\n"
                + "}\n";
        assertQualifiedReplacement(tmp, source);
    }

    /** A try-with-resources variable in scope at the selection forces qualification. */
    @Test
    void introduceFieldQualifiesWhenResourceVariableShadows(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int m() throws Exception {\n"
                + "        try (java.io.StringReader labelText = new java.io.StringReader(\"x\")) {\n"
                + "            return wrap(\"value\") + labelText.read();\n"
                + "        }\n"
                + "    }\n"
                + "    int wrap(String s) { return s.length(); }\n"
                + "}\n";
        assertQualifiedReplacement(tmp, source);
    }

    /** A catch parameter in scope at the selection forces qualification. */
    @Test
    void introduceFieldQualifiesWhenCatchParameterShadows(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int m() {\n"
                + "        try { risky(); } catch (Exception labelText) { return wrap(\"value\") + labelText.hashCode(); }\n"
                + "        return 0;\n"
                + "    }\n"
                + "    void risky() throws Exception { }\n"
                + "    int wrap(String s) { return s.length(); }\n"
                + "}\n";
        assertQualifiedReplacement(tmp, source);
    }

    /** A local declared in a SIBLING block is not in scope at the selection; no qualification (old text scan over-qualified). */
    @Test
    void introduceFieldDoesNotQualifyForOutOfScopeSiblingBlockLocal(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int m() {\n"
                + "        { String labelText = \"x\"; System.out.println(labelText); }\n"
                + "        return wrap(\"value\");\n"
                + "    }\n"
                + "    int wrap(String s) { return s.length(); }\n"
                + "}\n";
        assertUnqualifiedReplacement(tmp, source);
    }

    /** Declaration-like text in a comment is not a binding; the old regex over-qualified, the scope check does not. */
    @Test
    void introduceFieldDoesNotQualifyForDeclarationLikeComment(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int label() {\n"
                + "        // a declaration-shaped comment: String labelText = compute();\n"
                + "        return wrap(\"value\");\n"
                + "    }\n"
                + "    int wrap(String s) { return s.length(); }\n"
                + "}\n";
        assertUnqualifiedReplacement(tmp, source);
    }

    /** Declaration-like text inside a string literal is not a binding either. */
    @Test
    void introduceFieldDoesNotQualifyForDeclarationLikeStringLiteral(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int label() {\n"
                + "        String doc = \"String labelText = x;\";\n"
                + "        return wrap(\"value\") + doc.length();\n"
                + "    }\n"
                + "    int wrap(String s) { return s.length(); }\n"
                + "}\n";
        assertUnqualifiedReplacement(tmp, source);
    }

    private void assertQualifiedReplacement(Path tmp, String source) throws IOException {
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "labelText");
        fields.put("selection", selectionFor(source, "\"value\""));
        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"newText\":\"this.labelText\""), json);
    }

    private void assertUnqualifiedReplacement(Path tmp, String source) throws IOException {
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "labelText");
        fields.put("selection", selectionFor(source, "\"value\""));
        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"newText\":\"labelText\""), json);
        assertFalse(json.contains("this.labelText"), json);
    }

    // ── G012: introduce-field additional coverage ───────────────────────────

    @Test
    void introduceFieldUsesExplicitDeepGenericFieldTypeOverInferredType(@TempDir Path tmp) throws IOException {
        // The selected expression is a String literal (inferred type String), but an explicit deep generic fieldType is
        // requested: the explicit type wins, every java.util component is imported, and the declaration renders simple.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    Object seed() {\n"
                + "        return \"value\";\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "buckets");
        fields.put("fieldType", "java.util.Map<java.lang.String, java.util.Set<java.lang.Long>>");
        fields.put("selection", selectionFor(source, "\"value\""));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("import java.util.Map;"), json);
        assertTrue(json.contains("import java.util.Set;"), json);
        // Explicit type used, rendered with simple names; the inferred String type is NOT used as the field type.
        assertTrue(json.contains("Map<String, Set<Long>> buckets"), json);
        assertFalse(json.contains("java.util.Map<java.lang.String"), json);
        assertFalse(json.contains("String buckets"), json);
    }

    @Test
    void introduceFieldUsesDetachedInitializerTextWithExplicitType(@TempDir Path tmp) throws IOException {
        // No semantic selection: a detached literal-constant initializer string plus an explicit fieldType. The literal
        // constant is the one detached-text case accepted for inline (non-constructor) initialization.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int unrelated = 0;\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "limit");
        fields.put("fieldType", "int");
        fields.put("initializer", "42");

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("private final int limit = 42;"), json);
    }

    @Test
    void introduceFieldCreatesStaticFinalConstantFromLiteralSelection(@TempDir Path tmp) throws IOException {
        // constant=true with a compile-time-constant numeric selection yields a `private static final` declaration.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int factor() {\n"
                + "        return 1000;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "SCALE");
        fields.put("constant", true);
        fields.put("selection", selectionFor(source, "1000"));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("private static final int SCALE = 1000;"), json);
    }

    @Test
    void introduceFieldFansOutConstructorAssignmentAcrossAllTerminalConstructors(@TempDir Path tmp) throws IOException {
        // Two terminal constructors and an explicit constructorStrategy=allTerminal: the constructor-initialized field is
        // declared once and the assignment is emitted into BOTH terminal constructors (constructor fan-out).
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    Sample() {\n"
                + "    }\n"
                + "    Sample(int unused) {\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "tag");
        fields.put("fieldType", "String");
        fields.put("initializer", "\"x\"");
        fields.put("initializeInConstructor", true);
        fields.put("constructorStrategy", "allTerminal");

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        // Field declared with no initializer (constructor-assigned), and two fan-out assignments are emitted.
        assertTrue(json.contains("private final String tag;"), json);
        assertEquals(2, countOccurrences(json, "INTRODUCE_FIELD_CONSTRUCTOR_ASSIGNMENT"), json);
        assertTrue(json.contains("this.tag = "), json);
    }

    @Test
    void introduceFieldRefusesConstructorFanOutWithoutStrategyWhenMultipleConstructors(@TempDir Path tmp)
            throws IOException {
        // Multiple constructors require an explicit constructorStrategy; without it the operation is refused (structured),
        // not silently applied to one constructor.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    Sample() {\n"
                + "    }\n"
                + "    Sample(int unused) {\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "tag");
        fields.put("fieldType", "String");
        fields.put("initializer", "\"x\"");
        fields.put("initializeInConstructor", true);

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"code\":\"constructor_strategy_required\""), json);
    }

    @Test
    void introduceFieldRefusesLocalVariableCapture(@TempDir Path tmp) throws IOException {
        // The selected expression reads the method parameter `base`; a field initializer has no access to that local, so
        // the capture is refused with a structured local_variable_capture code naming the captured input.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int scale(int base) {\n"
                + "        return base + 2;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "scaled");
        fields.put("selection", selectionFor(source, "base + 2"));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"code\":\"local_variable_capture\""), json);
        assertTrue(json.contains("base"), json);
    }

    @Test
    void introduceFieldRefusesCheckedExceptionInitializer(@TempDir Path tmp) throws IOException {
        // `new java.io.FileReader("x")` is a side-effect-free fresh allocation (ALLOCATION_ONLY, so it passes the purity
        // gate) but its constructor can throw the checked FileNotFoundException, which a field initializer cannot declare.
        // The refusal must be the structured checked_exception_initializer code, not a silent acceptance.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    java.io.FileReader open() throws java.io.IOException {\n"
                + "        return new java.io.FileReader(\"x\");\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "reader");
        fields.put("selection", selectionFor(source, "new java.io.FileReader(\"x\")"));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"code\":\"checked_exception_initializer\""), json);
    }

    @Test
    void introduceFieldHonorsConfiguredVisibilityDefault(@TempDir Path tmp) throws IOException {
        // The effective visibility default (operation_defaults.visibility, merged into fields["visibility"] by Main) must
        // be honored rather than silently dropped: a protected default renders `protected final`, not `private final`.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    String label() {\n"
                + "        return \"value\";\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "labelText");
        fields.put("selection", selectionFor(source, "\"value\""));
        fields.put("visibility", "protected");

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("protected final String labelText"), json);
        assertFalse(json.contains("private final String labelText"), json);
    }

    @Test
    void introduceFieldRendersPackagePrivateVisibilityWithoutAccessKeyword(@TempDir Path tmp) throws IOException {
        // A package-private visibility default has no Java access keyword: the field renders `final Type name` with no
        // leading access modifier (and no stray double space).
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    String label() {\n"
                + "        return \"value\";\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "labelText");
        fields.put("selection", selectionFor(source, "\"value\""));
        fields.put("visibility", "package");

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("final String labelText"), json);
        // The rendered declaration carries no access keyword: no `private final`/`protected final`/`public final`. The
        // member-indented `final String labelText = "value";` appears with the inferred 4-space indent and no extra
        // space where the access modifier would have been (an empty modifier joined with a trailing space would have
        // produced a 5-space indent before `final`).
        assertFalse(json.contains("private final String labelText"), json);
        assertFalse(json.contains("protected final String labelText"), json);
        assertFalse(json.contains("public final String labelText"), json);
        assertTrue(json.contains("\\n    final String labelText = \\\"value\\\";\\n"), json);
        assertFalse(json.contains("\\n     final String labelText"), json);
    }

    @Test
    void introduceFieldRefusesUnrecognizedVisibilityDefault(@TempDir Path tmp) throws IOException {
        // An unrecognized configured visibility value is refused (structured invalid_visibility), never silently dropped
        // back to private.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    String label() {\n"
                + "        return \"value\";\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "labelText");
        fields.put("selection", selectionFor(source, "\"value\""));
        fields.put("visibility", "internal");

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"code\":\"invalid_visibility\""), json);
    }

    @Test
    void introduceFieldPreservesCrlfStyleInSynthesizedDeclaration(@TempDir Path tmp) throws IOException {
        // Style preservation: a CRLF source must yield a CRLF-normalized field declaration so the synthesized member
        // matches the surrounding line endings rather than introducing bare LF.
        String source = ""
                + "package demo;\r\n"
                + "public class Sample {\r\n"
                + "    String label() {\r\n"
                + "        return \"value\";\r\n"
                + "    }\r\n"
                + "}\r\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "labelText");
        fields.put("selection", selectionFor(source, "\"value\""));

        String json = run(tmp, source, false).introduceField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        // The declaration's newText carries an escaped CRLF (\r\n -> "\\r\\n" in JSON), proving CRLF preservation.
        assertTrue(json.contains("\\r\\n"), json);
    }

    // ── G029: static field encapsulation ─────────────────────────────────────

    @Test
    void encapsulateFieldSupportsStaticFieldWithStaticAccessors(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    static int count = 1;\n"
                + "    static int read() {\n"
                + "        return count;\n"
                + "    }\n"
                + "    static void write(int value) {\n"
                + "        count = value;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        // G001: internal-usage rewriting is opt-in (default false); this test proves the static-accessor rewrite.
        fields.put("rewriteInternalUsages", true);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("private static int count = 1;"), json);
        assertTrue(json.contains("public static int getCount()"), json);
        assertTrue(json.contains("public static void setCount(int value)"), json);
        assertTrue(json.contains("Sample.count = value;"), json);
        assertTrue(json.contains("ENCAPSULATE_FIELD_READ"), json);
        assertTrue(json.contains("setCount(value)"), json);
    }

    @Test
    void encapsulateFieldRefusesStaticFinalConstant(@TempDir Path tmp) throws IOException {
        // A static final constant has no encapsulation semantics (no setter, compile-time-inlined reads) and remains
        // the one static case that is refused.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    static final int COUNT = 1;\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "COUNT");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"static_field_unsupported\""), json);
    }

    // ── G030: volatile / concurrency opt-in ──────────────────────────────────

    @Test
    void encapsulateFieldRefusesVolatileWithoutOptIn(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    volatile int count = 1;\n"
                + "    int read() {\n"
                + "        return count;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"concurrency_sensitive_field\""), json);
    }

    @Test
    void encapsulateFieldEncapsulatesVolatileWithOptIn(@TempDir Path tmp) throws IOException {
        // With the explicit opt-in the field keeps its volatile modifier and is encapsulated; a single volatile
        // read/write through the accessors preserves the field's volatile semantics.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    volatile int count = 1;\n"
                + "    int read() {\n"
                + "        return count;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("allowVolatile", true);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("private volatile int count = 1;"), json);
        assertFalse(json.contains("concurrency_sensitive_field"), json);
    }

    // ── G031: same-class access policy (distinct from updateReferences) ───────

    @Test
    void encapsulateFieldLeavesSameClassAccessByDefault(@TempDir Path tmp) throws IOException {
        // G001: the rewriteInternalUsages default is false (parity with the Python config default). With no opt-in,
        // same-class direct field access is LEFT as-is — only the field visibility change + accessor generation are
        // emitted (editCount=2), and no read/write rewrites are produced.
        String source = simpleReadWriteSource();
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("ENCAPSULATE_FIELD_READ"), json);
        assertFalse(json.contains("ENCAPSULATE_FIELD_WRITE"), json);
        assertTrue(json.contains("\"editCount\":2,"), json);
    }

    @Test
    void encapsulateFieldRewritesSameClassAccessWhenInternalRewriteEnabled(@TempDir Path tmp) throws IOException {
        // With rewriteInternalUsages=true (mapped from encapsulate_field.rewrite_internal_usages_default) the same-class
        // reads and writes are routed through the generated accessors.
        String source = simpleReadWriteSource();
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("rewriteInternalUsages", true);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("ENCAPSULATE_FIELD_READ"), json);
        assertTrue(json.contains("ENCAPSULATE_FIELD_WRITE"), json);
        assertTrue(json.contains("\"editCount\":4,"), json);
    }

    @Test
    void encapsulateFieldKeepsSameClassDirectAccessWhenInternalRewriteDisabled(@TempDir Path tmp) throws IOException {
        // rewriteInternalUsages=false keeps same-class direct field access as-is (no read/write rewrites) while the
        // field is still made private and accessors are still generated — a control distinct from updateReferences.
        String source = simpleReadWriteSource();
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("rewriteInternalUsages", false);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("ENCAPSULATE_FIELD_READ"), json);
        assertFalse(json.contains("ENCAPSULATE_FIELD_WRITE"), json);
        assertTrue(json.contains("public int getCount()"), json);
        assertTrue(json.contains("\"editCount\":2,"), json);
    }

    // ── G002: compound/increment are always refused in V2 (opt-out is ignored) ───

    @Test
    void encapsulateFieldRefusesCompoundAssignmentEvenWhenOptOutRequested(@TempDir Path tmp) throws IOException {
        // G002: compound assignment always produces a structured refusal in V2. An explicit
        // refuseCompoundAssignments=false request is IGNORED — there is no expression-preserving compound rewrite and no
        // ENCAPSULATE_FIELD_COMPOUND edit is ever emitted.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    void bump(int value) {\n"
                + "        count += value;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("rewriteInternalUsages", true);
        fields.put("refuseCompoundAssignments", false);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"compound_field_usage\""), json);
        assertFalse(json.contains("ENCAPSULATE_FIELD_COMPOUND"), json);
        assertFalse(json.contains("setCount(getCount()"), json);
    }

    @Test
    void encapsulateFieldRefusesCompoundAssignmentByDefaultWhenConfigAbsent(@TempDir Path tmp) throws IOException {
        // G013: with no refuseCompoundAssignments key the policy default is TRUE — the compound assignment is refused,
        // exactly as before, proving the absent-key default flows through.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    void bump(int value) {\n"
                + "        count += value;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("rewriteInternalUsages", true);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"compound_field_usage\""), json);
    }

    @Test
    void encapsulateFieldRefusesCompoundAssignmentWhenConfigExplicitlyTrue(@TempDir Path tmp) throws IOException {
        // G013: an explicit refuseCompoundAssignments=true keeps the refusal (parity with the config default true).
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    void bump(int value) {\n"
                + "        count += value;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("rewriteInternalUsages", true);
        fields.put("refuseCompoundAssignments", true);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"compound_field_usage\""), json);
    }

    @Test
    void encapsulateFieldRefusesIncrementEvenWhenOptOutRequested(@TempDir Path tmp) throws IOException {
        // G002: a statement-position postfix increment `count++` is always refused with compound_field_usage; the
        // refuseCompoundAssignments=false opt-out is ignored and no setCount rewrite is emitted.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    void tick() {\n"
                + "        count++;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("rewriteInternalUsages", true);
        fields.put("refuseCompoundAssignments", false);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"compound_field_usage\""), json);
        assertFalse(json.contains("setCount(getCount()"), json);
    }

    @Test
    void encapsulateFieldRefusesPrefixDecrementEvenWhenOptOutRequested(@TempDir Path tmp) throws IOException {
        // G002: a prefix decrement `--count` at statement position is always refused with compound_field_usage; the
        // opt-out is ignored.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    void drop() {\n"
                + "        --count;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("rewriteInternalUsages", true);
        fields.put("refuseCompoundAssignments", false);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"compound_field_usage\""), json);
        assertFalse(json.contains("setCount(getCount()"), json);
    }

    @Test
    void encapsulateFieldRefusesPrefixIncrementEvenWhenOptOutRequested(@TempDir Path tmp) throws IOException {
        // G002: a prefix increment `++count` at statement position is always refused; the opt-out is ignored.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    void bump() {\n"
                + "        ++count;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("rewriteInternalUsages", true);
        fields.put("refuseCompoundAssignments", false);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"compound_field_usage\""), json);
        assertFalse(json.contains("setCount(getCount()"), json);
    }

    @Test
    void encapsulateFieldRefusesPostfixDecrementEvenWhenOptOutRequested(@TempDir Path tmp) throws IOException {
        // G002: a postfix decrement `count--` at statement position is always refused; the opt-out is ignored.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    void drop() {\n"
                + "        count--;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("rewriteInternalUsages", true);
        fields.put("refuseCompoundAssignments", false);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"compound_field_usage\""), json);
        assertFalse(json.contains("setCount(getCount()"), json);
    }

    @Test
    void encapsulateFieldRefusesCompoundWhoseValueIsUsedEvenWhenOptOutRequested(@TempDir Path tmp) throws IOException {
        // G002: a compound assignment whose result value escapes (`int x = (count += value);`) is refused as
        // compound_field_usage in V2 regardless of the (ignored) refuseCompoundAssignments=false opt-out.
        String source = ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    int bump(int value) {\n"
                + "        int x = (count += value);\n"
                + "        return x;\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setterName", "setCount");
        fields.put("rewriteInternalUsages", true);
        fields.put("refuseCompoundAssignments", false);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"compound_field_usage\""), json);
    }

    // ── G013: records / enums refusal (declaring-type kind) ──────────────────

    @Test
    void encapsulateFieldRefusesRecordComponent(@TempDir Path tmp) throws IOException {
        // A record component has no encapsulation semantics in V2 (the canonical accessor is generated); it is refused.
        String source = ""
                + "package demo;\n"
                + "public record Sample(int count) {\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"record_component_unsupported\""), json);
    }

    @Test
    void encapsulateFieldRefusesEnumConstant(@TempDir Path tmp) throws IOException {
        // An enum's primary type is an enum; encapsulating a field selected on it is refused as enum-unsupported.
        String source = ""
                + "package demo;\n"
                + "public enum Sample {\n"
                + "    A, B;\n"
                + "    int count = 1;\n"
                + "}\n";
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"code\":\"enum_constants_unsupported\""), json);
    }

    // ── G013: setter-disabled accessor generation ────────────────────────────

    @Test
    void encapsulateFieldOmitsSetterWhenSetterFalse(@TempDir Path tmp) throws IOException {
        // setter=false generates only the getter; no setter method is emitted and the field is still made private.
        String source = simpleCountSource();
        Map<String, Object> fields = baseFields();
        fields.put("fieldName", "count");
        fields.put("getterName", "getCount");
        fields.put("setter", false);

        String json = run(tmp, source, false).encapsulateField(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("public int getCount()"), json);
        assertFalse(json.contains("void setCount"), json);
        assertTrue(json.contains("private int count = 1;"), json);
    }

    // ── Harness ──────────────────────────────────────────────────────────────

    private static String simpleReadWriteSource() {
        return ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "    int read() {\n"
                + "        return count;\n"
                + "    }\n"
                + "    void write(int value) {\n"
                + "        count = value;\n"
                + "    }\n"
                + "}\n";
    }


    private static String simpleCountSource() {
        return ""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count = 1;\n"
                + "}\n";
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    private static Map<String, Object> baseFields() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/demo/Sample.java");
        return fields;
    }

    private FieldRefactorPlanner run(Path tmp, String source, boolean markGenerated) throws IOException {
        JavaProjectModel model = singleFileModel(tmp, source, markGenerated);
        return new FieldRefactorPlanner(tmp.toAbsolutePath().normalize(), model);
    }

    /** One-based selection range ({@code startLine/startColumn/endLine/endColumn}) covering {@code snippet}. */
    private static Map<String, Object> selectionFor(String source, String snippet) {
        int start = source.indexOf(snippet);
        int end = start + snippet.length();
        int[] startPos = lineColumn(source, start);
        int[] endPos = lineColumn(source, end);
        Map<String, Object> selection = new HashMap<>();
        selection.put("startLine", startPos[0]);
        selection.put("startColumn", startPos[1]);
        selection.put("endLine", endPos[0]);
        selection.put("endColumn", endPos[1]);
        return selection;
    }

    private static int[] lineColumn(String source, int offset) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, offset - lineStart + 1};
    }

    private static JavaProjectModel singleFileModel(Path root, String source, boolean markGenerated) throws IOException {
        Path sourceRoot = root.resolve("src");
        Path pkg = sourceRoot.resolve("demo");
        Files.createDirectories(pkg);
        Path javaFile = pkg.resolve("Sample.java");
        Files.writeString(javaFile, source, StandardCharsets.UTF_8);

        List<Path> generatedRoots = markGenerated ? List.of(sourceRoot) : List.of();
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                List.of(javaFile),
                List.of(),
                List.of(),
                List.of(),
                generatedRoots,
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
