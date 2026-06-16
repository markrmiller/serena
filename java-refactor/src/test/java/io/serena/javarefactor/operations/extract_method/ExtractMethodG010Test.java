package io.serena.javarefactor.operations.extract_method;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for the V2 extract-method engine's hard-blocking G010 scope, exercised through the real
 * {@code javac}-backed data-flow/control-flow analyses.
 *
 * <p>Verified behaviors:
 *
 * <ul>
 *   <li>single-output extraction (regression baseline);</li>
 *   <li>multi-output extraction — always refused in V2; an {@code allow_multiple_outputs} request is ignored (G003);</li>
 *   <li>control-flow extraction (value-return, void-return, break, continue) — always refused in V2; an
 *       {@code allow_control_flow_exits} request is ignored (G003);</li>
 *   <li>jumps captured by a wholly-selected loop are local and extract without a control-flow exit;</li>
 *   <li>checked-exception {@code throws} synthesis and static-modifier inference;</li>
 *   <li>genuinely-impossible cases refused with precise structured reasons (labeled jump, lambda/nested-class
 *       boundary).</li>
 * </ul>
 */
class ExtractMethodG010Test {

    // ── single output (regression baseline) ──────────────────────────────────────────────────────────

    @Test
    void extractsSingleOutputDeclaredInsideAsReturn(@TempDir Path tmp) throws IOException {
        Path source = write(tmp, "Single.java", ""
                + "public class Single {\n"
                + "    int run(int a, int b) {\n"
                + "        int sum = a + b;\n"
                + "        return sum * 2;\n"
                + "    }\n"
                + "}\n");
        String text = read(source);

        String json = extract(tmp, source, "addUp", selectionFor(text, "int sum = a + b;"), false, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("private int addUp(int a, int b)"), json);
        assertTrue(json.contains("int sum = addUp(a, b);"), json);
        assertTrue(json.contains("return a + b;"), json);
    }

    // ── multi output ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void refusesMultiOutputByDefault(@TempDir Path tmp) throws IOException {
        Path source = multiOutputSource(tmp);
        String text = read(source);

        String json = extract(tmp, source, "compute",
                selectionFor(text, "int lo = a;\n        int hi = b;"), false, false);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"multiple_outputs_unsupported\""), json);
        assertTrue(json.contains("lo, hi"), json);
    }

    @Test
    void refusesMultiOutputEvenWhenAllowRequested(@TempDir Path tmp) throws IOException {
        // G003: multi-output extraction is outside the V2 supported surface. An allow_multiple_outputs=true request is
        // IGNORED — the selection is refused and no synthesized record holder is emitted.
        Path source = multiOutputSource(tmp);
        String text = read(source);

        String json = extract(tmp, source, "compute",
                selectionFor(text, "int lo = a;\n        int hi = b;"), true, false);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"multiple_outputs_unsupported\""), json);
        assertFalse(json.contains("record ComputeResult"), json);
    }

    private static Path multiOutputSource(Path tmp) throws IOException {
        // lo and hi are both declared in the selection and read after it => two outputs.
        return write(tmp, "Multi.java", ""
                + "public class Multi {\n"
                + "    int run(int a, int b) {\n"
                + "        int lo = a;\n"
                + "        int hi = b;\n"
                + "        return lo + hi;\n"
                + "    }\n"
                + "}\n");
    }

    // ── control flow: value return ───────────────────────────────────────────────────────────────────

    @Test
    void refusesControlFlowExitByDefault(@TempDir Path tmp) throws IOException {
        Path source = valueReturnSource(tmp);
        String text = read(source);

        String json = extract(tmp, source, "guard",
                selectionFor(text, "if (a < 0) {\n            return 0;\n        }"), false, false);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"control_flow_unsupported\""), json);
    }

    @Test
    void refusesValueReturnEvenWhenAllowRequested(@TempDir Path tmp) throws IOException {
        // G003: control-flow-preserving extraction is outside the V2 supported surface. An allow_control_flow_exits=true
        // request is IGNORED — the value-return selection is refused and no synthesized signal record is emitted.
        Path source = valueReturnSource(tmp);
        String text = read(source);

        String json = extract(tmp, source, "guard",
                selectionFor(text, "if (a < 0) {\n            return 0;\n        }"), false, true);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"control_flow_unsupported\""), json);
        assertFalse(json.contains("record GuardSignal"), json);
    }

    private static Path valueReturnSource(Path tmp) throws IOException {
        return write(tmp, "Guarded.java", ""
                + "public class Guarded {\n"
                + "    int run(int a) {\n"
                + "        if (a < 0) {\n"
                + "            return 0;\n"
                + "        }\n"
                + "        return a + 1;\n"
                + "    }\n"
                + "}\n");
    }

    // ── control flow: void return / break / continue ─────────────────────────────────────────────────

    @Test
    void refusesVoidReturnEvenWhenAllowRequested(@TempDir Path tmp) throws IOException {
        // G003: a void-return control-flow exit is refused in V2 even with allow_control_flow_exits=true (ignored).
        Path source = write(tmp, "VoidGuard.java", ""
                + "public class VoidGuard {\n"
                + "    void run(int a) {\n"
                + "        if (a < 0) {\n"
                + "            return;\n"
                + "        }\n"
                + "        use(a);\n"
                + "    }\n"
                + "    void use(int x) {}\n"
                + "}\n");
        String text = read(source);

        String json = extract(tmp, source, "guard",
                selectionFor(text, "if (a < 0) {\n            return;\n        }"), false, true);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"control_flow_unsupported\""), json);
    }

    @Test
    void refusesBreakEvenWhenAllowRequested(@TempDir Path tmp) throws IOException {
        // G003: a break that escapes an out-of-selection loop is refused in V2 even with allow_control_flow_exits=true.
        Path source = write(tmp, "Breaker.java", ""
                + "public class Breaker {\n"
                + "    void run(int[] xs, int k) {\n"
                + "        for (int i = 0; i < xs.length; i++) {\n"
                + "            if (xs[i] == k) {\n"
                + "                break;\n"
                + "            }\n"
                + "            work(xs[i]);\n"
                + "        }\n"
                + "    }\n"
                + "    void work(int x) {}\n"
                + "}\n");
        String text = read(source);

        // Select the guard that breaks the (out-of-selection) loop.
        String json = extract(tmp, source, "shouldStop",
                selectionFor(text, "if (xs[i] == k) {\n                break;\n            }"), false, true);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"control_flow_unsupported\""), json);
    }

    @Test
    void refusesContinueEvenWhenAllowRequested(@TempDir Path tmp) throws IOException {
        // G003: a continue that escapes an out-of-selection loop is refused in V2 even with allow_control_flow_exits=true.
        Path source = write(tmp, "Skipper.java", ""
                + "public class Skipper {\n"
                + "    void run(int[] xs) {\n"
                + "        for (int i = 0; i < xs.length; i++) {\n"
                + "            if (xs[i] < 0) {\n"
                + "                continue;\n"
                + "            }\n"
                + "            work(xs[i]);\n"
                + "        }\n"
                + "    }\n"
                + "    void work(int x) {}\n"
                + "}\n");
        String text = read(source);

        String json = extract(tmp, source, "shouldSkip",
                selectionFor(text, "if (xs[i] < 0) {\n                continue;\n            }"), false, true);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"control_flow_unsupported\""), json);
    }

    @Test
    void extractsWhollySelectedLoopWithLocalBreakWithoutPolicy(@TempDir Path tmp) throws IOException {
        // The break is captured by the for-loop that is entirely inside the selection: it is local, so no
        // control-flow policy is needed and the helper is a plain void method.
        Path source = write(tmp, "LocalBreak.java", ""
                + "public class LocalBreak {\n"
                + "    int run(int[] xs, int k) {\n"
                + "        int found = -1;\n"
                + "        for (int i = 0; i < xs.length; i++) {\n"
                + "            if (xs[i] == k) {\n"
                + "                found = i;\n"
                + "                break;\n"
                + "            }\n"
                + "        }\n"
                + "        return found;\n"
                + "    }\n"
                + "}\n");
        String text = read(source);

        String json = extract(tmp, source, "scan",
                selectionFor(text, "for (int i = 0; i < xs.length; i++) {\n            if (xs[i] == k) {\n                found = i;\n                break;\n            }\n        }"),
                false, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("control_flow_unsupported"), json);
    }

    // ── checked exceptions + static inference ────────────────────────────────────────────────────────

    @Test
    void synthesizesThrowsForCheckedExceptions(@TempDir Path tmp) throws IOException {
        Path source = write(tmp, "Thrower.java", ""
                + "import java.io.IOException;\n"
                + "public class Thrower {\n"
                + "    void run() throws IOException {\n"
                + "        risky();\n"
                + "    }\n"
                + "    void risky() throws IOException {}\n"
                + "}\n");
        String text = read(source);

        String json = extract(tmp, source, "doRisky", selectionFor(text, "risky();"), false, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("throws") && json.contains("IOException"), json);
    }

    @Test
    void infersStaticHelperFromStaticEnclosingMethod(@TempDir Path tmp) throws IOException {
        Path source = write(tmp, "StaticHost.java", ""
                + "public class StaticHost {\n"
                + "    static int run(int a, int b) {\n"
                + "        int sum = a + b;\n"
                + "        return sum;\n"
                + "    }\n"
                + "}\n");
        String text = read(source);

        String json = extract(tmp, source, "addUp", selectionFor(text, "int sum = a + b;"), false, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("private static int addUp(int a, int b)"), json);
    }

    // ── genuinely-impossible refusals ────────────────────────────────────────────────────────────────

    @Test
    void refusesControlFlowExitCombinedWithOutputs(@TempDir Path tmp) throws IOException {
        // G003: the selection both writes an output read later (acc) and escapes via a return. Both opt-out policies are
        // requested but ignored in V2, so the control-flow exit alone makes the selection unsupported.
        Path source = write(tmp, "Both.java", ""
                + "public class Both {\n"
                + "    int run(int a) {\n"
                + "        int acc = a;\n"
                + "        if (a < 0) {\n"
                + "            return -1;\n"
                + "        }\n"
                + "        return acc;\n"
                + "    }\n"
                + "}\n");
        String text = read(source);

        String json = extract(tmp, source, "step",
                selectionFor(text, "int acc = a;\n        if (a < 0) {\n            return -1;\n        }"), true, true);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"control_flow_unsupported\""), json);
    }

    @Test
    void refusesLabeledBreak(@TempDir Path tmp) throws IOException {
        Path source = write(tmp, "Labeled.java", ""
                + "public class Labeled {\n"
                + "    void run(int[][] grid, int k) {\n"
                + "        outer:\n"
                + "        for (int[] row : grid) {\n"
                + "            for (int v : row) {\n"
                + "                if (v == k) {\n"
                + "                    break outer;\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
        String text = read(source);

        String json = extract(tmp, source, "find",
                selectionFor(text, "if (v == k) {\n                    break outer;\n                }"), false, true);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("control_flow_unsupported"), json);
    }

    @Test
    void refusesLambdaBoundaryCrossing(@TempDir Path tmp) throws IOException {
        Path source = write(tmp, "Lambdas.java", ""
                + "import java.util.List;\n"
                + "public class Lambdas {\n"
                + "    void run(List<Integer> xs) {\n"
                + "        xs.forEach(x -> {\n"
                + "            System.out.println(x);\n"
                + "        });\n"
                + "    }\n"
                + "}\n");
        String text = read(source);

        // Select across the lambda body boundary (from the forEach call into the lambda's statement).
        String json = extract(tmp, source, "show",
                selectionFor(text, "xs.forEach(x -> {\n            System.out.println(x);"), false, false);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("lambda_boundary_unsupported") || json.contains("selection_not_extractable")
                || json.contains("SELECTION_NOT_STATEMENT_ALIGNED"), json);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private static String extract(
            Path tmp, Path source, String newMethodName, Map<String, Object> selection,
            boolean allowMultipleOutputs, boolean allowControlFlowExits) throws IOException {
        JavaProjectModel model = model(tmp, source.getParent(), List.of(source));
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", tmp.relativize(source).toString().replace('\\', '/'));
        fields.put("newMethodName", newMethodName);
        fields.put("selection", selection);
        fields.put("allowMultipleOutputs", allowMultipleOutputs);
        fields.put("allowControlFlowExits", allowControlFlowExits);
        return new ExtractMethodPlanner(tmp.toAbsolutePath().normalize(), model).extractMethod(fields, false);
    }

    private static String read(Path source) throws IOException {
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private static Path write(Path tmp, String name, String text) throws IOException {
        Path sourceRoot = tmp.resolve("src");
        Files.createDirectories(sourceRoot);
        Path source = sourceRoot.resolve(name);
        Files.writeString(source, text, StandardCharsets.UTF_8);
        return source;
    }

    private static Map<String, Object> selectionFor(String source, String snippet) {
        int index = source.indexOf(snippet);
        if (index < 0) {
            throw new IllegalArgumentException("snippet not found: " + snippet);
        }
        int[] start = lineColumn(source, index);
        int[] end = lineColumn(source, index + snippet.length());
        Map<String, Object> selection = new HashMap<>();
        selection.put("startLine", start[0]);
        selection.put("startColumn", start[1]);
        selection.put("endLine", end[0]);
        selection.put("endColumn", end[1]);
        return selection;
    }

    private static int[] lineColumn(String source, int offset) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new int[] {line, column};
    }

    private static JavaProjectModel model(Path root, Path sourceRoot, List<Path> javaFiles) {
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                new ArrayList<>(javaFiles),
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
