package io.serena.javarefactor.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Direct unit coverage for {@link MethodBodyModel#fromSourceAtBody(String, String, int)} (G014): the overload-safe,
 * position-anchored binding that disambiguates overloaded methods by the source offset of the selected body's opening
 * brace, where the legacy name-only {@link MethodBodyModel#fromSource(String, String)} returns {@link MethodBodyModel}
 * empty for any overloaded name.
 */
class MethodBodyModelTest {

    private static final String OVERLOADED =
            "class T {\n"
                    + "    int scale(int v) { return v * 2; }\n"
                    + "    int scale(int v, int w) { return v * w; }\n"
                    + "}\n";

    /** Offset of the body's opening brace for the n-th (1-based) occurrence of `scale(` in the source. */
    private static int bodyBraceOffset(String source, int occurrence) {
        int idx = -1;
        for (int i = 0; i < occurrence; i++) {
            idx = source.indexOf("scale(", idx + 1);
        }
        return source.indexOf('{', idx);
    }

    @Test
    void nameOnlyFromSourceReturnsEmptyForAnOverloadedName() {
        // Establishes the gap fromSourceAtBody closes: the legacy entry point cannot model an overloaded name at all.
        MethodBodyModel byName = MethodBodyModel.fromSource(OVERLOADED, "scale");
        assertTrue(byName.statements().isEmpty());
    }

    @Test
    void bindsTheSingleArgumentOverloadByItsBodyPosition() {
        MethodBodyModel model = MethodBodyModel.fromSourceAtBody(OVERLOADED, "scale", bodyBraceOffset(OVERLOADED, 1));
        assertEquals(1, model.statements().size());
        assertEquals("return v * 2;", model.statements().get(0).toString().trim());
    }

    @Test
    void bindsTheTwoArgumentOverloadByItsBodyPosition() {
        MethodBodyModel model = MethodBodyModel.fromSourceAtBody(OVERLOADED, "scale", bodyBraceOffset(OVERLOADED, 2));
        assertEquals(1, model.statements().size());
        assertEquals("return v * w;", model.statements().get(0).toString().trim());
    }

    @Test
    void returnsEmptyWhenNoMethodBodyOpensAtTheOffset() {
        MethodBodyModel model = MethodBodyModel.fromSourceAtBody(OVERLOADED, "scale", 0);
        assertTrue(model.statements().isEmpty());
    }
}
