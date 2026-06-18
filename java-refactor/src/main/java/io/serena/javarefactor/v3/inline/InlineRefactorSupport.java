package io.serena.javarefactor.v3.inline;

import io.serena.javarefactor.compiler.DeepInlineResult;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.PlannerSupport.TextEdit;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.v3.transformation.TransformationStep;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small shared helpers for the V3 inline-method planner (refactor-feature-plan-V3.md §11): field parsing and the
 * accepted-result envelope. A block inline can produce several edits (one per call site, plus an optional method
 * deletion), so the envelope carries the full edit list rather than a single replacement.
 */
final class InlineRefactorSupport {

    private InlineRefactorSupport() {
    }

    static String requireString(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InlineRefactorRefusal("missing_field", key + " is required.");
        }
        return text;
    }

    static String optionalString(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** Reads a 1-based line/column field; returns {@code fallback} when absent. JSON numbers arrive as {@link Number}. */
    static int intField(Map<String, Object> fields, String key, int fallback) {
        Object value = fields.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static boolean boolField(Map<String, Object> fields, String key, boolean fallback) {
        Object value = fields.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    /** The inline's text edits (one per call site, plus optional method deletion); shared by the serializer and step. */
    static List<TextEdit> edits(Path file, DeepInlineResult result, String editKind) {
        List<TextEdit> edits = new ArrayList<>();
        for (DeepInlineResult.Edit edit : result.edits()) {
            edits.add(new TextEdit(file, edit.start(), edit.end(), edit.replacement(), editKind));
        }
        return edits;
    }

    /**
     * The structured workspace contribution for the inline (refactor-feature-plan-V3.md §3): the per-call-site text
     * edits, the planner's warnings, and no file operations. Built from the SAME {@link DeepInlineResult} the standalone
     * {@link #acceptedJson} serializes, so a composed step and the standalone endpoint carry identical edits.
     */
    static TransformationStep toStep(String operation, Path file, DeepInlineResult result, String editKind) {
        return new TransformationStep(
                operation, edits(file, result, editKind), List.of(), result.warnings(),
                "{\"operation\":" + JsonUtil.quote(operation) + "}");
    }

    /** The accepted-result envelope: the inline's text edits, mirroring the classops/conversion planners' shape. */
    static String acceptedJson(Path projectRoot, String operation, Path file, DeepInlineResult result, String editKind)
            throws IOException {
        List<TextEdit> edits = edits(file, result, editKind);
        return "{"
                + "\"accepted\":true,"
                + "\"operation\":" + JsonUtil.quote(operation) + ","
                + "\"workspaceEdit\":{"
                + "\"changes\":" + PlannerSupport.changesJson(projectRoot, edits) + ","
                + "\"fileOperations\":[]"
                + "},"
                + "\"warnings\":" + PlannerSupport.warningsJson(result.warnings()) + ","
                + "\"stats\":{\"replacements\":" + result.edits().size() + "}"
                + "}";
    }
}
