package io.serena.javarefactor.shared;

import io.serena.javarefactor.ast.ResolvedTarget;
import io.serena.javarefactor.ast.TargetHints;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the V2 semantic-target identity gate ({@link SemanticTargetGate}) refuses the lossy-position failure modes
 * Blocker&nbsp;3 enumerates — name-path mismatch, overload-arity mismatch, same-line overload ambiguity, and a stale
 * position that no longer resolves to the named symbol — and resolves cleanly when a precise column or arity pins the
 * target. Each case drives a REAL javac-backed {@link SemanticIndex} over a temp fixture, so the assertions exercise
 * the same resolution path the sidecar uses in production.
 */
class SemanticTargetGateTest {

    private static final String SOURCE = ""
            + "package demo;\n"                                                       // line 1
            + "public final class Over {\n"                                            // line 2
            + "    public int single(int a) { return a; }\n"                           // line 3
            + "    public int dup() { return 0; } public int dup(int x) { return x; }\n" // line 4
            + "}\n";                                                                    // line 5

    @Test
    void resolvesNamedTargetWithPreciseColumn(@TempDir Path tmp) throws IOException {
        try (SemanticIndex index = openIndex(tmp)) {
            Pos at = Pos.of(SOURCE, "single");
            ResolvedTarget target = SemanticTargetGate.require(
                    index, "src/demo/Over.java", at.line, at.column, new TargetHints("single", "method", 1));
            assertNotNull(target);
            assertEquals("single", target.element().getSimpleName().toString());
        }
    }

    @Test
    void refusesNamePathMismatch(@TempDir Path tmp) throws IOException {
        try (SemanticIndex index = openIndex(tmp)) {
            Pos at = Pos.of(SOURCE, "single");
            SemanticTargetGate.Refused refused = assertThrows(SemanticTargetGate.Refused.class, () ->
                    SemanticTargetGate.require(
                            index, "src/demo/Over.java", at.line, at.column, new TargetHints("notTheName", "method", 1)));
            assertEquals("target_mismatch", refused.code());
        }
    }

    @Test
    void refusesOverloadArityMismatch(@TempDir Path tmp) throws IOException {
        try (SemanticIndex index = openIndex(tmp)) {
            Pos at = Pos.of(SOURCE, "single");
            SemanticTargetGate.Refused refused = assertThrows(SemanticTargetGate.Refused.class, () ->
                    SemanticTargetGate.require(
                            index, "src/demo/Over.java", at.line, at.column, new TargetHints("single", "method", 2)));
            assertEquals("target_mismatch", refused.code());
        }
    }

    @Test
    void refusesSameLineOverloadAmbiguityWithoutArityOrPreciseColumn(@TempDir Path tmp) throws IOException {
        try (SemanticIndex index = openIndex(tmp)) {
            // The cursor sits inside the first dup's body (the `return 0`), so name-hint resolution attaches to a dup
            // overload without a precise identifier column, and no arity hint is given. Two dup overloads share line 4,
            // so the gate must refuse rather than let the planner's line-based selection guess the narrowest.
            Pos inBody = Pos.of(SOURCE, "return 0");
            SemanticTargetGate.Refused refused = assertThrows(SemanticTargetGate.Refused.class, () ->
                    SemanticTargetGate.require(
                            index, "src/demo/Over.java", inBody.line, inBody.column, new TargetHints("dup", "method", -1)));
            assertEquals("ambiguous_member_selection", refused.code());
        }
    }

    @Test
    void resolvesSameLineOverloadWithPreciseColumn(@TempDir Path tmp) throws IOException {
        try (SemanticIndex index = openIndex(tmp)) {
            // A precise column inside the two-arg dup identifier pins the overload unambiguously.
            Pos secondDup = Pos.ofOccurrence(SOURCE, "dup", 2);
            ResolvedTarget target = SemanticTargetGate.require(
                    index, "src/demo/Over.java", secondDup.line, secondDup.column, new TargetHints("dup", "method", 1));
            assertNotNull(target);
            assertEquals("dup", target.element().getSimpleName().toString());
        }
    }

    @Test
    void refusesStalePositionThatResolvesToADifferentSymbol(@TempDir Path tmp) throws IOException {
        // Models the apply-time re-resolution refusal: the caller's stored hints describe the two-arg dup, but the
        // position now lands on the no-arg dup (e.g. an edit shifted the line) — the arity hint proves the divergence.
        try (SemanticIndex index = openIndex(tmp)) {
            Pos firstDup = Pos.ofOccurrence(SOURCE, "dup", 1);
            SemanticTargetGate.Refused refused = assertThrows(SemanticTargetGate.Refused.class, () ->
                    SemanticTargetGate.require(
                            index, "src/demo/Over.java", firstDup.line, firstDup.column, new TargetHints("dup", "method", 1)));
            assertEquals("target_mismatch", refused.code());
        }
    }

    @Test
    void skipsGateForNameOnlyEscapeHatch(@TempDir Path tmp) throws IOException {
        try (SemanticIndex index = openIndex(tmp)) {
            // No positional target (line/column absent): the gate returns null and name-only selection stays on its path.
            assertNull(SemanticTargetGate.require(index, "src/demo/Over.java", -1, -1, new TargetHints("dup", "method", -1)));
        }
    }

    @Test
    void declarationsOnLineSurfacesEverySameLineDeclaration(@TempDir Path tmp) throws IOException {
        try (SemanticIndex index = openIndex(tmp)) {
            assertEquals(2, index.declarationsOnLine("src/demo/Over.java", 4).size(), "both dup overloads share line 4");
            List<ResolvedTarget> line3 = index.declarationsOnLine("src/demo/Over.java", 3);
            assertEquals(1, line3.size(), "only single is declared on line 3");
            assertEquals("single", line3.get(0).element().getSimpleName().toString());
        }
    }

    @Test
    void confirmSelectionRefusesWhenSelectedElementDiffersFromVerifiedTarget(@TempDir Path tmp) throws IOException {
        try (SemanticIndex index = openIndex(tmp)) {
            Pos single = Pos.of(SOURCE, "single");
            ResolvedTarget verified = SemanticTargetGate.require(
                    index, "src/demo/Over.java", single.line, single.column, new TargetHints("single", "method", 1));
            // Cross-check against a DIFFERENT element (the two-arg dup) must refuse target_mismatch.
            ResolvedTarget other = index.declarationsOnLine("src/demo/Over.java", 4).stream()
                    .filter(t -> t.element().getSimpleName().contentEquals("dup"))
                    .reduce((first, second) -> second)
                    .orElseThrow();
            SemanticTargetGate.Refused refused = assertThrows(SemanticTargetGate.Refused.class, () ->
                    SemanticTargetGate.confirmSelection(verified, other.element()));
            assertEquals("target_mismatch", refused.code());
            // And accepts the matching element.
            SemanticTargetGate.confirmSelection(verified, verified.element());
        }
    }

    private SemanticIndex openIndex(Path root) throws IOException {
        return SemanticIndex.open(singleFileModel(root), "src/demo/Over.java");
    }

    private static JavaProjectModel singleFileModel(Path root) throws IOException {
        Path sourceRoot = root.resolve("src");
        Path pkg = sourceRoot.resolve("demo");
        Files.createDirectories(pkg);
        Path javaFile = pkg.resolve("Over.java");
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

    /** One-based (line, column) of a token occurrence inside {@link #SOURCE}, computed from the raw text. */
    private record Pos(int line, int column) {
        static Pos of(String source, String token) {
            return ofOccurrence(source, token, 1);
        }

        static Pos ofOccurrence(String source, String token, int occurrence) {
            int from = -1;
            for (int i = 0; i < occurrence; i++) {
                from = source.indexOf(token, from + 1);
                assertTrue(from >= 0, "token '" + token + "' occurrence " + occurrence + " not found");
            }
            int line = 1;
            int lineStart = 0;
            for (int i = 0; i < from; i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                    lineStart = i + 1;
                }
            }
            return new Pos(line, from - lineStart + 1);
        }
    }
}
