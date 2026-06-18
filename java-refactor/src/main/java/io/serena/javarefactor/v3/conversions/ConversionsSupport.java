package io.serena.javarefactor.v3.conversions;

import io.serena.javarefactor.compiler.ConversionResult;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.PlannerSupport.TextEdit;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.v3.transformation.TransformationStep;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Small shared helpers for the V3 conversion planners (refactor-feature-plan-V3.md §12/§13): field parsing and the
 * accepted-result envelope. Kept separate so each planner reads as a straight transcription of its spec section.
 */
final class ConversionsSupport {

    private ConversionsSupport() {
    }

    static String requireString(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ConversionsRefusal("missing_field", key + " is required.");
        }
        return text;
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

    /** The single in-file text edit a conversion produces; shared by the standalone serializer and the workspace step. */
    static TextEdit edit(Path file, ConversionResult result, String editKind) {
        return new TextEdit(file, result.start(), result.end(), result.replacement(), editKind);
    }

    /**
     * The structured workspace contribution for a conversion (refactor-feature-plan-V3.md §3): the single in-file text
     * replacement, no file operations, no warnings. Built from the SAME {@link ConversionResult} the standalone
     * {@link #acceptedJson} serializes, so a composed step and the standalone endpoint carry an identical edit.
     */
    static TransformationStep toStep(String operation, Path file, ConversionResult result, String editKind) {
        return new TransformationStep(
                operation, List.of(edit(file, result, editKind)), List.of(), List.of(),
                "{\"operation\":" + JsonUtil.quote(operation) + "}");
    }

    /** The accepted-result envelope: a single in-file text replacement, mirroring the classops planners' shape. */
    static String acceptedJson(Path projectRoot, String operation, Path file, ConversionResult result, String editKind)
            throws IOException {
        TextEdit edit = edit(file, result, editKind);
        return "{"
                + "\"accepted\":true,"
                + "\"operation\":" + JsonUtil.quote(operation) + ","
                + "\"workspaceEdit\":{"
                + "\"changes\":" + PlannerSupport.changesJson(projectRoot, List.of(edit)) + ","
                + "\"fileOperations\":[]"
                + "},"
                + "\"warnings\":" + PlannerSupport.warningsJson(List.of()) + ","
                + "\"stats\":{\"replacements\":1}"
                + "}";
    }
}
