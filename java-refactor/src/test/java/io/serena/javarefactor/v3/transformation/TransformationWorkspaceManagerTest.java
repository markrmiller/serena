package io.serena.javarefactor.v3.transformation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.serena.javarefactor.protocol.Json;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TransformationWorkspaceManagerTest {

    @Test
    void addOperationValidationRefusalDoesNotReplaceExistingWorkspace(@TempDir Path root) {
        AtomicInteger validations = new AtomicInteger();
        TransformationWorkspaceManager manager = new TransformationWorkspaceManager(
                root,
                4,
                60,
                (operation, arguments) -> TransformationWorkspaceManager.StepResult.of(
                        new TransformationStep(operation, List.of(), List.of(), List.of(), "{\"operation\":\"" + operation + "\"}")),
                (semanticTargetJson, edits, fileOperations, warnings, riskFactsJson) -> "{\"accepted\":true,\"workspaceEdit\":{\"changes\":[],\"fileOperations\":[]}}",
                (operation, previewJson) -> validations.incrementAndGet() == 1
                        ? previewJson
                        : "{\"accepted\":false,\"refusal\":{\"code\":\"javac_validation_failed\",\"message\":\"boom\"}}",
                touched -> "rev-" + touched.size(),
                () -> null);

        Map<String, Object> created = Json.parseObject(manager.createWorkspace(
                "goal", List.of(new TransformationWorkspaceManager.OperationRequest("first", Map.of())), 0L));
        String workspaceId = String.valueOf(created.get("workspaceId"));

        Map<String, Object> refused = Json.parseObject(manager.addOperation(
                workspaceId, new TransformationWorkspaceManager.OperationRequest("second", Map.of()), 1L));
        Map<String, Object> preview = Json.parseObject(manager.preview(workspaceId, 2L));

        assertEquals(Boolean.FALSE, refused.get("accepted"));
        assertEquals("javac_validation_failed", ((Map<?, ?>) refused.get("refusal")).get("code"));
        assertEquals(Boolean.TRUE, preview.get("accepted"));
        assertEquals("previewReady", preview.get("workspaceStatus"));
    }

    @Test
    void workspacePreviewPassesUnionedRiskFactsToValidation(@TempDir Path root) {
        AtomicReference<String> riskFactsSeen = new AtomicReference<>();
        TransformationWorkspaceManager manager = new TransformationWorkspaceManager(
                root,
                4,
                60,
                (operation, arguments) -> TransformationWorkspaceManager.StepResult.of(
                        new TransformationStep(
                                operation,
                                List.of(),
                                List.of(),
                                List.of(),
                                "{\"operation\":\"" + operation + "\"}",
                                "{\"publicApiChanges\":[\"" + operation + " public API\"],\"analysisIncomplete\":[\"shared incomplete scan\"]}")),
                (semanticTargetJson, edits, fileOperations, warnings, riskFactsJson) -> {
                    riskFactsSeen.set(riskFactsJson);
                    return "{\"accepted\":true,\"workspaceEdit\":{\"changes\":[],\"fileOperations\":[]},\"riskFacts\":" + riskFactsJson + "}";
                },
                (operation, previewJson) -> previewJson,
                touched -> "rev-" + touched.size(),
                () -> null);

        Map<String, Object> created = Json.parseObject(manager.createWorkspace(
                "goal",
                List.of(
                        new TransformationWorkspaceManager.OperationRequest("first", Map.of()),
                        new TransformationWorkspaceManager.OperationRequest("second", Map.of())),
                0L));

        assertEquals(Boolean.TRUE, created.get("accepted"));
        Map<String, Object> riskFacts = Json.parseObject(riskFactsSeen.get());
        assertEquals(List.of("first public API", "second public API"), riskFacts.get("publicApiChanges"));
        assertEquals(List.of("shared incomplete scan"), riskFacts.get("analysisIncomplete"));
    }

    @Test
    void applyPreparesWithoutTerminalizingWorkspace(@TempDir Path root) {
        TransformationWorkspaceManager manager = new TransformationWorkspaceManager(
                root,
                4,
                60,
                (operation, arguments) -> TransformationWorkspaceManager.StepResult.of(
                        new TransformationStep(operation, List.of(), List.of(), List.of(), "{}")),
                (semanticTargetJson, edits, fileOperations, warnings, riskFactsJson) -> "{\"accepted\":true,\"workspaceEdit\":{\"changes\":[],\"fileOperations\":[]}}",
                (operation, previewJson) -> previewJson,
                touched -> "rev",
                () -> null);
        Map<String, Object> created = Json.parseObject(manager.createWorkspace(
                "goal", List.of(new TransformationWorkspaceManager.OperationRequest("first", Map.of())), 0L));
        String workspaceId = String.valueOf(created.get("workspaceId"));

        Map<String, Object> first = Json.parseObject(manager.apply(workspaceId, null, 1L));
        Map<String, Object> second = Json.parseObject(manager.apply(workspaceId, null, 2L));

        assertEquals(Boolean.TRUE, first.get("accepted"));
        assertEquals(Boolean.TRUE, second.get("accepted"));
        assertEquals("previewReady", first.get("workspaceStatus"));
        assertEquals("previewReady", second.get("workspaceStatus"));
    }
}
