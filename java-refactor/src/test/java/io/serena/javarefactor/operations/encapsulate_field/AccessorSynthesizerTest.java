package io.serena.javarefactor.operations.encapsulate_field;

import io.serena.javarefactor.shared.JavaStyleProfile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit proof for the extracted {@link AccessorSynthesizer} (plan §3 {@code encapsulate_field/AccessorSynthesizer}):
 * JavaBean getter/setter naming and accessor body rendering, tested directly without the planner or javac.
 */
class AccessorSynthesizerTest {

    @Test
    void booleanPrimitiveGetsIsPrefix() {
        assertEquals("isActive", AccessorSynthesizer.defaultGetterName("boolean", "active"));
    }

    @Test
    void booleanFieldAlreadyPrefixedIsNotDoublePrefixed() {
        // A boolean field named `isEnabled` reuses its name rather than producing the double-prefix `isIsEnabled`.
        assertEquals("isEnabled", AccessorSynthesizer.defaultGetterName("boolean", "isEnabled"));
    }

    @Test
    void wrapperBooleanUsesGetPrefixNotIs() {
        // A wrapper Boolean can be null, so the is-prefix is reserved for the primitive: getEnabled, not isEnabled.
        assertEquals("getEnabled", AccessorSynthesizer.defaultGetterName("Boolean", "enabled"));
    }

    @Test
    void referenceTypeGetsGetPrefix() {
        assertEquals("getName", AccessorSynthesizer.defaultGetterName("String", "name"));
    }

    @Test
    void setterUsesSetPrefix() {
        assertEquals("setCount", AccessorSynthesizer.defaultSetterName("count"));
    }

    @Test
    void instanceAccessorsRenderGetterAndSetterThroughThis() {
        JavaStyleProfile style = JavaStyleProfile.infer("package demo;\npublic class Sample {\n    int count;\n}\n");
        String indent = style.memberIndent();
        String bodyIndent = style.childIndent(indent);

        String accessors = AccessorSynthesizer.renderAccessors(
                style, indent, bodyIndent, false, "int", "getCount", "count", true, "setCount", "this.count");

        assertTrue(accessors.contains("public int getCount() {"), accessors);
        assertTrue(accessors.contains("return count;"), accessors);
        assertTrue(accessors.contains("public void setCount(int value) {"), accessors);
        assertTrue(accessors.contains("this.count = value;"), accessors);
    }

    @Test
    void staticAccessorsRenderStaticModifierAndTypeQualifiedSetterTarget() {
        JavaStyleProfile style = JavaStyleProfile.infer("package demo;\npublic class Sample {\n    static int count;\n}\n");
        String indent = style.memberIndent();
        String bodyIndent = style.childIndent(indent);

        String accessors = AccessorSynthesizer.renderAccessors(
                style, indent, bodyIndent, true, "int", "getCount", "count", true, "setCount", "Sample.count");

        assertTrue(accessors.contains("public static int getCount() {"), accessors);
        assertTrue(accessors.contains("public static void setCount(int value) {"), accessors);
        assertTrue(accessors.contains("Sample.count = value;"), accessors);
    }

    @Test
    void accessorsHonorAllmanBraceStyle() {
        // G001: a source whose braces sit on their own line (Allman) must produce accessors with own-line braces.
        JavaStyleProfile style = JavaStyleProfile.infer(""
                + "package demo;\n"
                + "public class Sample\n"
                + "{\n"
                + "    int count;\n"
                + "    void run()\n"
                + "    {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n");
        String indent = style.memberIndent();
        String bodyIndent = style.childIndent(indent);

        String accessors = AccessorSynthesizer.renderAccessors(
                style, indent, bodyIndent, false, "int", "getCount", "count", true, "setCount", "this.count");

        assertTrue(!accessors.contains("getCount() {"), accessors);
        assertTrue(accessors.contains("getCount()\n    {"), accessors);
        assertTrue(accessors.contains("setCount(int value)\n    {"), accessors);
    }

    @Test
    void setterHonorsFinalParameterStyle() {
        // G001: when the surrounding source declares final parameters, the synthesized setter parameter is final too.
        JavaStyleProfile style = JavaStyleProfile.infer(""
                + "package demo;\n"
                + "public class Sample {\n"
                + "    int count;\n"
                + "    void set(final int v) {\n"
                + "        count = v;\n"
                + "    }\n"
                + "}\n");
        String indent = style.memberIndent();
        String bodyIndent = style.childIndent(indent);

        String accessors = AccessorSynthesizer.renderAccessors(
                style, indent, bodyIndent, false, "int", "getCount", "count", true, "setCount", "this.count");

        assertTrue(accessors.contains("setCount(final int value)"), accessors);
    }

    @Test
    void getterOnlyRenderingOmitsSetter() {
        JavaStyleProfile style = JavaStyleProfile.infer("package demo;\npublic class Sample {\n    int count;\n}\n");
        String indent = style.memberIndent();
        String bodyIndent = style.childIndent(indent);

        String accessors = AccessorSynthesizer.renderAccessors(
                style, indent, bodyIndent, false, "int", "getCount", "count", false, "", "this.count");

        assertTrue(accessors.contains("public int getCount() {"), accessors);
        assertTrue(!accessors.contains("setCount"), accessors);
    }
}
