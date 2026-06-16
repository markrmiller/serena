package io.serena.javarefactor.operations.change_signature;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the V2 change-signature dispatcher threads the caller's target-identity hints
 * ({@code nameHint}/{@code kindHint}/{@code arityHint} + one-based {@code line}/{@code column}) into
 * {@code SemanticTargetGate} and refuses unverified or ambiguous targets BEFORE planning any edit — the same rigor V1
 * rename already enforces. Each case runs the real {@link ChangeSignaturePlanner} against a javac-backed temp project.
 */
class ChangeSignaturePlannerTargetIdentityTest {

    private static final String SOURCE = ""
            + "package demo;\n"                                                       // 1
            + "public final class Svc {\n"                                            // 2
            + "    public int compute(int a) { return a; }\n"                          // 3
            + "    public int dup() { return 0; } public int dup(int x) { return x; }\n" // 4
            + "}\n";                                                                    // 5

    @Test
    void refusesWhenNameHintDoesNotMatchResolvedSymbol(@TempDir Path tmp) throws IOException {
        String json = run(tmp, fields(SOURCE, "compute", hints("wrongName", "method", 1)));
        assertTrue(json.contains("\"code\":\"target_mismatch\""), json);
    }

    @Test
    void refusesWhenOverloadArityHintDoesNotMatch(@TempDir Path tmp) throws IOException {
        String json = run(tmp, fields(SOURCE, "compute", hints("compute", "method", 2)));
        assertTrue(json.contains("\"code\":\"target_mismatch\""), json);
    }

    @Test
    void refusesSameLineOverloadAmbiguity(@TempDir Path tmp) throws IOException {
        // Cursor inside the first dup body, two dup overloads on the line, no arity hint -> refuse, do not guess.
        String json = run(tmp, fields(SOURCE, "return 0", hints("dup", "method", -1)));
        assertTrue(json.contains("\"code\":\"ambiguous_member_selection\""), json);
    }

    @Test
    void passesGateForAVerifiedUniqueTarget(@TempDir Path tmp) throws IOException {
        // A precise column on the compute identifier with matching name/arity must clear the identity gate; whatever the
        // downstream planning outcome, it must NOT be a target-identity refusal.
        String json = run(tmp, fields(SOURCE, "compute", hints("compute", "method", 1)));
        assertFalse(json.contains("\"code\":\"target_mismatch\""), json);
        assertFalse(json.contains("\"code\":\"ambiguous_member_selection\""), json);
        assertFalse(json.contains("\"code\":\"target_not_found\""), json);
    }

    private String run(Path tmp, Map<String, Object> fields) throws IOException {
        JavaProjectModel model = singleFileModel(tmp);
        return new ChangeSignaturePlanner(tmp.toAbsolutePath().normalize(), model).changeSignature(fields, false);
    }

    private static Map<String, Object> hints(String nameHint, String kindHint, long arityHint) {
        Map<String, Object> hints = new HashMap<>();
        hints.put("nameHint", nameHint);
        hints.put("kindHint", kindHint);
        hints.put("arityHint", arityHint);
        return hints;
    }

    private static Map<String, Object> fields(String source, String token, Map<String, Object> hints) {
        int[] pos = positionOf(source, token);
        Map<String, Object> fields = new HashMap<>(hints);
        fields.put("relativePath", "src/demo/Svc.java");
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        return fields;
    }

    /** One-based {line, column} of the first occurrence of {@code token} in {@code source}. */
    private static int[] positionOf(String source, String token) {
        int from = source.indexOf(token);
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < from; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, from - lineStart + 1};
    }

    private static JavaProjectModel singleFileModel(Path root) throws IOException {
        Path sourceRoot = root.resolve("src");
        Path pkg = sourceRoot.resolve("demo");
        Files.createDirectories(pkg);
        Path javaFile = pkg.resolve("Svc.java");
        Files.writeString(javaFile, SOURCE, StandardCharsets.UTF_8);

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
