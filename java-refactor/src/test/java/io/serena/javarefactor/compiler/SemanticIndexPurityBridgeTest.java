package io.serena.javarefactor.compiler;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Directly exercises the G004 purity bridge on {@link SemanticIndex}
 * ({@link SemanticIndex#isExpressionReorderSafe} and {@link SemanticIndex#isCallArgumentReorderSafe}). The bridge is the
 * single green-light planners consult before reordering, duplicating, hoisting, or dropping an AST-resolved expression:
 * it resolves the expression's real javac {@link com.sun.source.util.TreePath} and returns the canonical
 * {@link io.serena.javarefactor.shared.ExpressionPurityAnalyzer#isReorderSafe} verdict. Unlike the coarse
 * {@code classify(String)} screen it replaced, it distinguishes a non-final field read (unsafe) from a final/effectively
 * final read (safe) and refuses unresolvable inputs.
 */
class SemanticIndexPurityBridgeTest {

    private static final String SOURCE = ""
            + "package demo;\n"
            + "final class Demo {\n"
            + "    final int FINAL_FIELD = 1;\n"
            + "    int mutableField = 2;\n"
            + "    int compute(int p) {\n"
            + "        return p + FINAL_FIELD;\n"
            + "    }\n"
            + "    int helper() { return 0; }\n"
            + "    int callLiteral() { return compute(7); }\n"
            + "    int callFinalField() { return compute(FINAL_FIELD); }\n"
            + "    int callParam(int q) { return compute(q); }\n"
            + "    int callMutableField() { return compute(mutableField); }\n"
            + "    int callMethod() { return compute(helper()); }\n"
            + "}\n";

    @Test
    void reorderSafeOnlyForStableSideEffectFreeArguments(@TempDir Path tmp) throws IOException {
        JavaProjectModel model = singleFileModel(tmp, SOURCE, "src/demo/Demo.java");
        try (SemanticIndex index = SemanticIndex.open(model, "src/demo/Demo.java")) {
            Map<String, SemanticIndex.SemanticCallSite> byFirstArgument = new LinkedHashMap<>();
            for (SemanticIndex.SemanticCallSite site : index.methodInvocationsNamed("compute")) {
                byFirstArgument.put(site.arguments().get(0).text().strip(), site);
            }

            // A literal, a final field read, and an effectively-final parameter read are all provably reorder-safe.
            assertReorderSafe(index, byFirstArgument, "7", true);
            assertReorderSafe(index, byFirstArgument, "FINAL_FIELD", true);
            assertReorderSafe(index, byFirstArgument, "q", true);

            // A non-final field read and an unresolved-effects method call are NOT reorder-safe — the coarse
            // classify(String) screen would have green-lit the structurally-pure field read.
            assertReorderSafe(index, byFirstArgument, "mutableField", false);
            assertReorderSafe(index, byFirstArgument, "helper()", false);
        }
    }

    @Test
    void refusesUnresolvableOrOutOfBoundsInputs(@TempDir Path tmp) throws IOException {
        JavaProjectModel model = singleFileModel(tmp, SOURCE, "src/demo/Demo.java");
        try (SemanticIndex index = SemanticIndex.open(model, "src/demo/Demo.java")) {
            SemanticIndex.SemanticCallSite site = index.methodInvocationsNamed("compute").get(0);
            // Out-of-bounds argument index, null call site, and null range all refuse rather than throw.
            assertFalse(index.isCallArgumentReorderSafe(site, 5));
            assertFalse(index.isCallArgumentReorderSafe(site, -1));
            assertFalse(index.isCallArgumentReorderSafe(null, 0));
            assertFalse(index.isExpressionReorderSafe(site.file(), null));

            // A range that does not line up with any expression node refuses.
            SemanticIndex.SourceRange bogus = new SemanticIndex.SourceRange(site.file(), 0, 1);
            assertFalse(index.isExpressionReorderSafe(site.file(), bogus));
        }
    }

    private static void assertReorderSafe(
            SemanticIndex index,
            Map<String, SemanticIndex.SemanticCallSite> byFirstArgument,
            String argumentText,
            boolean expected) {
        SemanticIndex.SemanticCallSite site = byFirstArgument.get(argumentText);
        assertNotNull(site, "missing call site for argument " + argumentText);
        // Both the call-site bridge and the range bridge must agree, resolving the same argument AST node.
        boolean viaCallSite = index.isCallArgumentReorderSafe(site, 0);
        boolean viaRange = index.isExpressionReorderSafe(site.arguments().get(0).range().file(), site.arguments().get(0).range());
        if (expected) {
            assertTrue(viaCallSite, "expected reorder-safe via call site: " + argumentText);
            assertTrue(viaRange, "expected reorder-safe via range: " + argumentText);
        } else {
            assertFalse(viaCallSite, "expected NOT reorder-safe via call site: " + argumentText);
            assertFalse(viaRange, "expected NOT reorder-safe via range: " + argumentText);
        }
    }

    private static JavaProjectModel singleFileModel(Path root, String source, String relativePath) throws IOException {
        Path sourceRoot = root.resolve("src");
        Path javaFile = root.resolve(relativePath);
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, source, StandardCharsets.UTF_8);

        List<Path> sourceFiles = new ArrayList<>();
        sourceFiles.add(javaFile);
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                sourceFiles,
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
