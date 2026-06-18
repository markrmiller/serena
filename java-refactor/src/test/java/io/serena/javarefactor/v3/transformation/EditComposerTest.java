package io.serena.javarefactor.v3.transformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.serena.javarefactor.edits.PlannerSupport.TextEdit;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic unit coverage for {@link EditComposer} — the transformation-workspace composition core
 * (refactor-feature-plan-V3.md §1.1/§3). These tests pin the exact contract blocker #1 hinges on: a workspace composes
 * multiple operations' edits to the SAME file as long as their ranges do not truly overlap, and refuses only genuine
 * conflicts (overlapping ranges, duplicate rename sources, colliding creates, rename-away-then-recreate) with the
 * canonical {@code workspace_edit_conflict} signal. No JVM sidecar, project, or javac is needed — the semantics are
 * verified directly so a regression cannot hide behind an unrun live test.
 */
class EditComposerTest {

    private static final Path ROOT = Path.of("/repo");

    private final EditComposer composer = new EditComposer(ROOT);

    private static TextEdit edit(String relative, long start, long end, String text) {
        return new TextEdit(ROOT.resolve(relative), start, end, text, "replace");
    }

    private static TransformationStep step(String operation, List<TextEdit> edits, List<FileOperation> ops) {
        return new TransformationStep(operation, edits, ops, List.of(), "{\"kind\":\"" + operation + "\"}");
    }

    @Test
    void composesSameFileNonOverlappingEditsFromDifferentSteps() {
        // The whole point of a workspace: one op edits the top of A.java, another edits lower down — they coexist.
        TransformationStep a = step("renamePackage", List.of(edit("A.java", 0, 5, "X")), List.of());
        TransformationStep b = step("movePackage", List.of(edit("A.java", 10, 15, "Y")), List.of());

        EditComposer.ComposedEdit composed = composer.compose(List.of(a, b));

        assertEquals(2, composed.edits().size());
        assertEquals(0, composed.edits().get(0).startOffset(), "edits are sorted by (file, startOffset)");
        assertEquals(10, composed.edits().get(1).startOffset());
    }

    @Test
    void allowsAdjacentAndZeroWidthBoundaryEdits() {
        // [0,5) replace, a zero-width insert exactly at offset 5, then [5,10) replace: touching, never overlapping.
        TransformationStep a = step("a", List.of(edit("A.java", 0, 5, "X")), List.of());
        TransformationStep b = step("b", List.of(edit("A.java", 5, 5, "INS"), edit("A.java", 5, 10, "Y")), List.of());

        EditComposer.ComposedEdit composed = composer.compose(List.of(a, b));

        assertEquals(3, composed.edits().size());
    }

    @Test
    void refusesTrulyOverlappingSameFileEdits() {
        TransformationStep a = step("a", List.of(edit("A.java", 0, 10, "X")), List.of());
        TransformationStep b = step("b", List.of(edit("A.java", 5, 15, "Y")), List.of());

        EditComposer.ComposeConflict conflict =
                assertThrows(EditComposer.ComposeConflict.class, () -> composer.compose(List.of(a, b)));
        assertTrue(conflict.getMessage().contains("overlapping"), conflict.getMessage());
        assertTrue(conflict.getMessage().contains("A.java"), conflict.getMessage());
    }

    @Test
    void composesDisjointFileEdits() {
        TransformationStep a = step("a", List.of(edit("A.java", 0, 10, "X")), List.of());
        TransformationStep b = step("b", List.of(edit("B.java", 0, 10, "Y")), List.of());

        assertEquals(2, composer.compose(List.of(a, b)).edits().size());
    }

    @Test
    void refusesDuplicateRenameSource() {
        TransformationStep a = step("a", List.of(), List.of(FileOperation.rename("old/A.java", "new1/A.java", null)));
        TransformationStep b = step("b", List.of(), List.of(FileOperation.rename("old/A.java", "new2/A.java", null)));

        EditComposer.ComposeConflict conflict =
                assertThrows(EditComposer.ComposeConflict.class, () -> composer.compose(List.of(a, b)));
        assertTrue(conflict.getMessage().contains("renamed by more than one"), conflict.getMessage());
    }

    @Test
    void refusesCollidingCreate() {
        TransformationStep a = step("a", List.of(), List.of(FileOperation.create("x/A.java", "a")));
        TransformationStep b = step("b", List.of(), List.of(FileOperation.create("x/A.java", "b")));

        EditComposer.ComposeConflict conflict =
                assertThrows(EditComposer.ComposeConflict.class, () -> composer.compose(List.of(a, b)));
        assertTrue(conflict.getMessage().contains("produce the file"), conflict.getMessage());
    }

    @Test
    void refusesRenameAwayThenRecreateSamePath() {
        TransformationStep a = step("a", List.of(), List.of(FileOperation.rename("p/A.java", "q/A.java", null)));
        TransformationStep b = step("b", List.of(), List.of(FileOperation.create("p/A.java", "new")));

        EditComposer.ComposeConflict conflict =
                assertThrows(EditComposer.ComposeConflict.class, () -> composer.compose(List.of(a, b)));
        assertTrue(conflict.getMessage().contains("re-created"), conflict.getMessage());
    }

    @Test
    void mergesWarningsAndWrapsSemanticTargets() {
        TransformationStep a =
                new TransformationStep("a", List.of(edit("A.java", 0, 1, "X")), List.of(), List.of("w1"), "{\"t\":1}");
        TransformationStep b =
                new TransformationStep("b", List.of(edit("B.java", 0, 1, "Y")), List.of(), List.of("w2"), "{\"t\":2}");

        EditComposer.ComposedEdit composed = composer.compose(List.of(a, b));

        assertEquals(List.of("w1", "w2"), composed.warnings());
        assertTrue(composed.semanticTargetJson().contains("\"kind\":\"transformation\""), composed.semanticTargetJson());
        assertTrue(composed.semanticTargetJson().contains("\"operation\":\"a\""), composed.semanticTargetJson());
        assertTrue(composed.semanticTargetJson().contains("\"operation\":\"b\""), composed.semanticTargetJson());
    }
}
