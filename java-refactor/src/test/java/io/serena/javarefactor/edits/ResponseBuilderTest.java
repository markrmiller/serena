package io.serena.javarefactor.edits;

import io.serena.javarefactor.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseBuilderTest {
    @TempDir
    private Path projectRoot;

    @Test
    void statsAndChangedFilesDeriveFromTheActualWorkspaceEdit() throws IOException {
        // N = 2 edited files (3 text edits total) + M = 3 file operations (create, delete, rename).
        Path a = write("A.java", "class A {}\n");
        Path b = write("B.java", "class B {}\n");
        Path d = write("D.java", "class D {}\n");
        Path e = write("E.java", "class E {}\n");

        List<PlannerSupport.TextEdit> edits = List.of(
                new PlannerSupport.TextEdit(a, 0, 1, "C", "REFERENCE"),
                new PlannerSupport.TextEdit(a, 7, 8, "C", "REFERENCE"),
                new PlannerSupport.TextEdit(b, 0, 1, "C", "REFERENCE"));
        List<ResponseBuilder.FileOperation> ops = List.of(
                ResponseBuilder.FileOperation.create("C.java", "class C {}\n"),
                ResponseBuilder.FileOperation.delete("D.java", PlannerSupport.sha256(d)),
                ResponseBuilder.FileOperation.rename("E.java", "F.java", PlannerSupport.sha256(e)));

        ResponseBuilder.Stats stats = ResponseBuilder.deriveStats(projectRoot, edits, ops);
        assertEquals(3, stats.editCount());
        assertEquals(3, stats.fileOperationCount());
        // changed = edited (A, B) + created (C) + rename target (F); touched also adds deleted (D) + rename source (E).
        assertEquals(List.of("A.java", "B.java", "C.java", "F.java"), stats.changedFiles());
        assertEquals(List.of("A.java", "B.java", "C.java", "D.java", "E.java", "F.java"), stats.touchedFiles());
        assertEquals(6, stats.touchedFileCount());

        String json = ResponseBuilder.acceptedResult(projectRoot, "moveStaticMember", false,
                "", edits, ops, List.of(), List.of("precondition"),
                ResponseBuilder.DiagnosticDelta.real("{\"newErrors\":[]}", List.of()), false);
        Map<String, Object> result = Json.parseObject(json);
        assertEquals(true, result.get("accepted"));
        assertEquals("preview", result.get("mode"));
        assertEquals(List.of("A.java", "B.java", "C.java", "F.java"), result.get("changedFiles"));
        assertStats((Map<?, ?>) result.get("stats"));
        Map<?, ?> workspaceEdit = (Map<?, ?>) result.get("workspaceEdit");
        assertStats((Map<?, ?>) workspaceEdit.get("stats"));
        // file operations are serialized from the same structured source the counts derive from
        assertEquals(3, ((List<?>) workspaceEdit.get("fileOperations")).size());
    }

    @Test
    void validationRequiredRejectsTheFakeEmptyDeltaButAcceptsARealOne() throws IOException {
        Path a = write("A.java", "class A {}\n");
        List<PlannerSupport.TextEdit> edits = List.of(new PlannerSupport.TextEdit(a, 0, 1, "B", "REFERENCE"));

        // Apply / validation-required path must never consume the fake empty delta.
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () ->
                ResponseBuilder.acceptedResult(projectRoot, "rename", true, "", edits, List.of(),
                        List.of(), List.of(), ResponseBuilder.DiagnosticDelta.unvalidated(), true));
        assertTrue(rejected.getMessage().contains("real PreviewDiagnosticValidator delta"));

        // A real javac-derived delta passes and is carried through verbatim.
        String json = ResponseBuilder.acceptedResult(projectRoot, "rename", true, "", edits, List.of(),
                List.of(), List.of(), ResponseBuilder.DiagnosticDelta.real("{\"newErrors\":[]}", List.of("note")), true);
        Map<String, Object> result = Json.parseObject(json);
        assertEquals(true, result.get("accepted"));
        assertEquals("apply", result.get("mode"));
        assertEquals(true, result.get("applied"));
        assertEquals(List.of("note"), result.get("diagnostics"));
    }

    @Test
    void previewWithoutValidationMayCarryTheNonAuthoritativePlaceholder() throws IOException {
        Path a = write("A.java", "class A {}\n");
        List<PlannerSupport.TextEdit> edits = List.of(new PlannerSupport.TextEdit(a, 0, 1, "B", "REFERENCE"));

        String json = ResponseBuilder.acceptedResult(projectRoot, "rename", false, "", edits, List.of(),
                List.of(), List.of(), ResponseBuilder.DiagnosticDelta.unvalidated(), false);
        assertEquals(true, Json.parseObject(json).get("accepted"));
    }

    /** HB-10: the serialized diagnosticDeltaValidated marker distinguishes a placeholder delta from a real javac one. */
    @Test
    void diagnosticDeltaValidatedMarkerReflectsWhetherTheDeltaIsReal() throws IOException {
        Path a = write("A.java", "class A {}\n");
        List<PlannerSupport.TextEdit> edits = List.of(new PlannerSupport.TextEdit(a, 0, 1, "B", "REFERENCE"));

        String unvalidated = ResponseBuilder.acceptedResult(projectRoot, "rename", false, "", edits, List.of(),
                List.of(), List.of(), ResponseBuilder.DiagnosticDelta.unvalidated(), false);
        assertEquals(false, Json.parseObject(unvalidated).get("diagnosticDeltaValidated"));

        // A real delta with NO diagnostic changes is byte-identical in its delta body to the placeholder, so only the
        // marker tells them apart — exactly why HB-10 needs it.
        String validated = ResponseBuilder.acceptedResult(projectRoot, "rename", false, "", edits, List.of(),
                List.of(), List.of(), ResponseBuilder.DiagnosticDelta.real("{\"newErrors\":[]}", List.of()), false);
        assertEquals(true, Json.parseObject(validated).get("diagnosticDeltaValidated"));
    }

    private void assertStats(Map<?, ?> stats) {
        assertEquals(3L, ((Number) stats.get("editCount")).longValue());
        assertEquals(3L, ((Number) stats.get("fileOperationCount")).longValue());
        assertEquals(6L, ((Number) stats.get("touchedFileCount")).longValue());
        assertEquals(List.of("A.java", "B.java", "C.java", "D.java", "E.java", "F.java"), stats.get("touchedFiles"));
    }

    private Path write(String name, String content) throws IOException {
        Path file = projectRoot.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
