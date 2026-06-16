package io.serena.javarefactor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.serena.javarefactor.protocol.Json;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RefactorSessionManagerTest {

    @Test
    void touchedFilesCollectsWorkspaceEditShapesInStableOrder() {
        String preview = """
                {
                  "workspaceEdit": {
                    "changes": {
                      "src/Edited.java": [{"range": {}, "newText": ""}],
                      "file:///repo/src/UriEdited.java": [{"range": {}, "newText": ""}]
                    },
                    "documentChanges": [
                      {"textDocument": {"uri": "src/TextDocument.java"}, "edits": [{"path": "src/NestedEdit.java"}]},
                      {"kind": "create", "uri": "src/Created.java"},
                      {"kind": "rename", "oldUri": "src/OldName.java", "newUri": "src/NewName.java"},
                      {"kind": "delete", "uri": "src/Deleted.java"}
                    ],
                    "fileOperations": [
                      {"kind": "rename", "oldPath": "src/OldPath.java", "newPath": "src/NewPath.java"},
                      {"kind": "create", "targetPath": "src/TargetCreated.java"},
                      {"kind": "delete", "sourcePath": "src/SourceDeleted.java"}
                    ]
                  }
                }
                """;

        assertEquals(
                List.of(
                        "src/Edited.java",
                        "/repo/src/UriEdited.java",
                        "src/TextDocument.java",
                        "src/NestedEdit.java",
                        "src/Created.java",
                        "src/OldName.java",
                        "src/NewName.java",
                        "src/Deleted.java",
                        "src/OldPath.java",
                        "src/NewPath.java",
                        "src/TargetCreated.java",
                        "src/SourceDeleted.java"),
                RefactorSessionManager.touchedFiles(preview, Map.of("relativePath", "fallback/OnlyIfNoPreviewPath.java")));
    }

    @Test
    void touchedFilesFallsBackToRequestFieldsWhenPreviewHasNoPaths() {
        assertEquals(
                List.of("src/Fallback.java"),
                RefactorSessionManager.touchedFiles("{\"workspaceEdit\":{\"changes\":[]}}", Map.of("relativePath", "src/Fallback.java")));
    }

    @Test
    void configureUsesNestedSessionPolicyKeys() throws Exception {
        RefactorSessionManager manager = new RefactorSessionManager();

        manager.configure(Map.of(
                "sessions", Map.of(
                        "max_open_sessions", 7,
                        "session_ttl_minutes", 3)));

        assertEquals(7, intField(manager, "maxLiveSessions"));
        assertEquals(Duration.ofMinutes(3), durationField(manager, "sessionTtl"));
    }

    @Test
    void configureRejectsRevisionMatchOptOut() {
        // G003: the apply-time stale-revision guard is non-bypassable, so configuring it off is refused at config-load
        // (both spellings) rather than accepted as a no-op.
        RefactorSessionManager manager = new RefactorSessionManager();
        IllegalArgumentException snake = assertThrows(IllegalArgumentException.class, () -> manager.configure(Map.of(
                "sessions", Map.of("require_revision_match_on_apply", false))));
        assertTrue(snake.getMessage().contains("require_revision_match_on_apply:false is not supported"));
        assertThrows(IllegalArgumentException.class, () -> manager.configure(Map.of(
                "sessions", Map.of("requireRevisionMatchOnApply", false))));
    }

    @Test
    void configureAcceptsRevisionMatchTrue() {
        RefactorSessionManager manager = new RefactorSessionManager();
        manager.configure(Map.of("sessions", Map.of("require_revision_match_on_apply", true)));
        assertTrue(manager.requireRevisionMatchOnApply());
    }

    @Test
    void applyEnvelopeSurfacesSuppliedRevalidatedPlanNotStoredPreview() {
        // Blocker 1 regression: on apply the sidecar passes the freshly recomputed + revalidated currentPlan as the
        // surfaced plan. This proves the envelope honors that plan for EVERY edit field Python can apply — plan,
        // preview, and the top-level edit are all derived from the supplied plan, never from the session's stored
        // create-time preview. If the apply path ever reverts to surfacing session.previewJson(), this fails because the
        // applied workspace edit would carry the stored "Stored.java" change instead of the revalidated one.
        String storedPreview = """
                {"accepted":true,"operation":"semanticRename",
                 "workspaceEdit":{"changes":{"Stored.java":[{"newText":"stored"}]}}}
                """;
        String revalidatedPlan = """
                {"accepted":true,"operation":"semanticRename",
                 "workspaceEdit":{"changes":{"Revalidated.java":[{"newText":"revalidated"}]}}}
                """;
        RefactorSession session = new RefactorSession(
                "sid-1",
                "semanticRename",
                Map.of("relativePath", "App.java"),
                new SessionTarget("App#value", "FIELD", "App", "value", "int"),
                new ProjectRevision("model-hash", Map.of(), Instant.EPOCH, Map.of(), "derived-digest"),
                List.of("App.java"),
                storedPreview,
                Instant.EPOCH);

        String envelope = RefactorSessionManager.sessionEnvelope(session, revalidatedPlan, "apply", "{}");

        assertFalse(
                envelope.contains("Stored.java"),
                "apply envelope must not surface the stored create-time preview edit: " + envelope);
        assertTrue(
                envelope.contains("Revalidated.java"),
                "apply envelope must surface the supplied revalidated plan edit: " + envelope);

        Map<String, Object> root = Json.parseObject(envelope);
        assertEquals("apply", root.get("mode"));
        // plan and preview both carry the supplied revalidated plan's workspace edit (Python reads preview.workspaceEdit).
        assertTrue(changes(root.get("plan")).containsKey("Revalidated.java"), envelope);
        assertTrue(changes(root.get("preview")).containsKey("Revalidated.java"), envelope);
        assertFalse(changes(root.get("preview")).containsKey("Stored.java"), envelope);
        // The top-level edit (workspaceEdit) is derived from the SAME supplied plan, so it cannot drift from preview.
        Map<?, ?> editChanges = (Map<?, ?>) ((Map<?, ?>) root.get("edit")).get("changes");
        assertTrue(editChanges.containsKey("Revalidated.java"), envelope);
        assertFalse(editChanges.containsKey("Stored.java"), envelope);
    }

    private static Map<?, ?> changes(Object planNode) {
        Map<?, ?> workspaceEdit = (Map<?, ?>) ((Map<?, ?>) planNode).get("workspaceEdit");
        return (Map<?, ?>) workspaceEdit.get("changes");
    }

    private static int intField(RefactorSessionManager manager, String name) throws Exception {
        Field field = RefactorSessionManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(manager);
    }

    private static Duration durationField(RefactorSessionManager manager, String name) throws Exception {
        Field field = RefactorSessionManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Duration) field.get(manager);
    }
}
