package io.serena.javarefactor.edits;

import io.serena.javarefactor.protocol.JsonUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The single central place that turns a planner's ACTUAL planned edit (text {@link PlannerSupport.TextEdit}s plus
 * {@link FileOperation}s) into the one canonical V2 accepted-result JSON shape.
 *
 * <p>Why this exists (G002/G036/G035/G021): every planner used to hand-roll its own {@code stats} object and frequently
 * hard-coded {@code touchedFileCount}/{@code touchedFiles}, so the reported counts could silently disagree with the
 * {@code workspaceEdit} that was actually planned. {@code ResponseBuilder} derives {@code changedFiles},
 * {@code touchedFiles}, {@code touchedFileCount}, {@code editCount}, and {@code fileOperationCount} exclusively from the
 * real {@code changes[]} + {@code fileOperations[]}, so the numbers cannot drift from the edit. It also forbids a fake
 * all-empty diagnostic delta from ever being authoritative on an apply (or any validation-required) result.
 */
public final class ResponseBuilder {
    private ResponseBuilder() {
    }

    /**
     * One structured workspace-edit file operation. Mirrors the V1 wire shapes produced by
     * {@link PlannerSupport#createFileOp}, {@link PlannerSupport#deleteFileOp}, and {@link PlannerSupport#renameFileOp},
     * but kept structured so {@link ResponseBuilder} can both serialize it AND count the files it touches from the same
     * source of truth. The {@code oldSha256} (delete/rename) is computed by the caller via {@link PlannerSupport#sha256}.
     */
    public record FileOperation(String kind, String path, String oldPath, String newPath, String oldSha256, String content) {
        public static FileOperation create(String path, String content) {
            return new FileOperation("create", path, null, null, null, content);
        }

        public static FileOperation delete(String path, String oldSha256) {
            return new FileOperation("delete", path, null, null, oldSha256, null);
        }

        public static FileOperation deleteDirectory(String path) {
            return new FileOperation("deleteDirectory", path, null, null, null, null);
        }

        public static FileOperation rename(String oldPath, String newPath, String oldSha256) {
            return new FileOperation("rename", null, oldPath, newPath, oldSha256, null);
        }

        String toJson() {
            return switch (kind) {
                case "create" -> "{\"kind\":\"create\",\"path\":" + JsonUtil.quote(path)
                        + ",\"content\":" + JsonUtil.quote(content == null ? "" : content) + "}";
                case "delete" -> "{\"kind\":\"delete\",\"path\":" + JsonUtil.quote(path)
                        + ",\"oldSha256\":" + JsonUtil.quote(oldSha256) + "}";
                case "deleteDirectory" -> "{\"kind\":\"deleteDirectory\",\"path\":" + JsonUtil.quote(path) + "}";
                case "rename" -> "{\"kind\":\"rename\",\"oldPath\":" + JsonUtil.quote(oldPath)
                        + ",\"newPath\":" + JsonUtil.quote(newPath) + ",\"oldSha256\":" + JsonUtil.quote(oldSha256) + "}";
                default -> throw new IllegalArgumentException("unknown file operation kind: " + kind);
            };
        }

        /** Paths whose content this operation creates/changes (create path, rename target). */
        private void addContentPaths(Set<String> changed) {
            if ("create".equals(kind) && path != null) {
                changed.add(path);
            } else if ("rename".equals(kind) && newPath != null) {
                changed.add(newPath);
            }
        }

        /** Every project-relative path this operation involves (for the touched-files set). */
        private void addTouchedPaths(Set<String> touched) {
            if (path != null) {
                touched.add(path);
            }
            if (oldPath != null) {
                touched.add(oldPath);
            }
            if (newPath != null) {
                touched.add(newPath);
            }
        }
    }

    /**
     * The diagnostic delta carried in an accepted result. A {@link #unvalidated()} delta is the conservative
     * placeholder a preview may carry BEFORE {@code PreviewDiagnosticValidator} has run; it is explicitly NOT
     * authoritative and {@link #builder} rejects it whenever validation is required (every apply). A {@link #real}
     * delta is the one produced from an actual javac before/after comparison.
     */
    public static final class DiagnosticDelta {
        private final boolean real;
        private final String json;
        private final List<String> diagnostics;

        private DiagnosticDelta(boolean real, String json, List<String> diagnostics) {
            this.real = real;
            this.json = json;
            this.diagnostics = List.copyOf(diagnostics);
        }

        /** The fake, all-empty delta. Allowed only on a not-yet-validated preview; never authoritative. */
        public static DiagnosticDelta unvalidated() {
            return new DiagnosticDelta(false, PlannerSupport.emptyDiagnosticDeltaJson(), List.of());
        }

        /** A real javac-derived delta. {@code json} is the delta object; {@code diagnostics} are the after-state lines. */
        public static DiagnosticDelta real(String json, List<String> diagnostics) {
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("a real diagnostic delta requires a non-empty delta JSON");
            }
            return new DiagnosticDelta(true, json, diagnostics);
        }

        public boolean isReal() {
            return real;
        }

        public String json() {
            return json;
        }

        public List<String> diagnostics() {
            return diagnostics;
        }
    }

    /** Counts derived from the actual planned edit. {@code touchedFiles}/{@code changedFiles} are sorted distinct. */
    public record Stats(int editCount, int fileOperationCount, List<String> changedFiles, List<String> touchedFiles) {
        public int touchedFileCount() {
            return touchedFiles.size();
        }

        public String json() {
            return "{\"editCount\":" + editCount
                    + ",\"fileOperationCount\":" + fileOperationCount
                    + ",\"touchedFileCount\":" + touchedFileCount()
                    + ",\"touchedFiles\":" + JsonUtil.array(touchedFiles) + "}";
        }
    }

    /**
     * Derives stats from the ACTUAL edit: {@code editCount} is the number of text edits, {@code fileOperationCount} the
     * number of file operations, {@code changedFiles} the distinct paths whose content changes (edited files plus
     * created/renamed-to targets), and {@code touchedFiles} every path involved (changed files plus deleted/renamed-from
     * sources). No count is ever supplied by the caller.
     */
    public static Stats deriveStats(Path projectRoot, List<PlannerSupport.TextEdit> edits, List<FileOperation> fileOperations) {
        Set<String> changed = new LinkedHashSet<>();
        for (PlannerSupport.TextEdit edit : edits) {
            changed.add(PlannerSupport.relative(projectRoot, edit.file()));
        }
        for (FileOperation operation : fileOperations) {
            operation.addContentPaths(changed);
        }
        Set<String> touched = new LinkedHashSet<>(changed);
        for (FileOperation operation : fileOperations) {
            operation.addTouchedPaths(touched);
        }
        return new Stats(edits.size(), fileOperations.size(), new ArrayList<>(new TreeSet<>(changed)),
                new ArrayList<>(new TreeSet<>(touched)));
    }

    /**
     * The one canonical refusal-result JSON (Blocker 3). Every refusal path — shared {@link PlannerSupport#refusalJson}
     * and the per-planner builders in move-static, move-instance, and hierarchy support — routes here so a refused result
     * can never disagree with itself. It always emits {@code accepted:false} and {@code applied:false} (nothing is ever
     * applied on a refusal, regardless of the requested mode), the ACTUAL operation, the ACTUAL requested {@code mode}
     * (derived from {@code applyRequested}, not hard-coded to {@code preview}), centrally-derived empty edit/file-op
     * counts (so the counts cannot be hand-rolled or drift), and no stale or misleading fields.
     */
    public static String refusedResult(String operation, boolean applyRequested, String code, String message) {
        return refusedResult(operation, applyRequested, code, message, null, List.of());
    }

    /**
     * Canonical refusal overload that additionally embeds a {@code location} object inside the {@code refusal} (when
     * {@code locationJson} is non-blank) and surfaces {@code warnings}. {@code locationJson} must already be a serialized
     * JSON object (e.g. {@code {"relativePath":...,"line":...}}).
     */
    public static String refusedResult(
            String operation, boolean applyRequested, String code, String message, String locationJson, List<String> warnings) {
        return refusedResult(operation, applyRequested, code, message, locationJson, warnings, java.util.Map.of(), List.of());
    }

    /**
     * Fullest canonical refusal overload (G002). In addition to {@code location} and {@code warnings}, it accepts
     * {@code extraRefusalFields} — extra already-serialized JSON members embedded INSIDE the {@code refusal} object
     * (e.g. {@code "sites": [...]} for usage-narrowing/blocking-site refusals) — and a top-level {@code diagnostics}
     * array (e.g. the real after-state javac diagnostics surfaced on a validation refusal). Every canonical invariant is
     * still enforced exactly as the base overload: {@code accepted:false}, {@code applied:false} (never the incoming
     * apply flag), the ACTUAL requested {@code mode} derived from {@code applyRequested}, centrally-derived empty
     * edit/file-op {@code stats}, an empty {@code workspaceEdit}, the placeholder {@code diagnosticDelta}, and
     * {@code diagnosticDeltaValidated:false}. The extra fields are purely additive and can never weaken those invariants.
     */
    public static String refusedResult(
            String operation,
            boolean applyRequested,
            String code,
            String message,
            String locationJson,
            List<String> warnings,
            java.util.Map<String, String> extraRefusalFields,
            List<String> diagnostics) {
        String op = operation == null || operation.isBlank() ? "unknown" : operation;
        String operationJson = JsonUtil.quote(op);
        // Empty stats derived centrally from an empty edit — a refusal touches nothing, and the counts can never be
        // hand-coded or drift from that fact.
        String statsJson = deriveStats(Path.of(""), List.of(), List.of()).json();
        String locationField = locationJson == null || locationJson.isBlank() ? "" : ",\"location\":" + locationJson;
        StringBuilder extraFields = new StringBuilder();
        if (extraRefusalFields != null) {
            for (java.util.Map.Entry<String, String> entry : extraRefusalFields.entrySet()) {
                String value = entry.getValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                extraFields.append(',').append(JsonUtil.quote(entry.getKey())).append(':').append(value);
            }
        }
        String warningsJson = PlannerSupport.warningsJson(warnings == null ? List.of() : warnings);
        String diagnosticsJson = JsonUtil.array(diagnostics == null ? List.of() : diagnostics);
        return "{\"accepted\":false,\"operation\":" + operationJson
                + ",\"mode\":" + JsonUtil.quote(applyRequested ? "apply" : "preview")
                + ",\"applied\":false"
                + ",\"refusal\":{\"code\":" + JsonUtil.quote(code) + ",\"message\":" + JsonUtil.quote(message)
                + locationField + extraFields + "}"
                + ",\"semanticTarget\":{\"operation\":" + operationJson + "}"
                + ",\"diagnostics\":" + diagnosticsJson + ",\"warnings\":" + warningsJson
                + ",\"diagnosticDelta\":" + PlannerSupport.emptyDiagnosticDeltaJson()
                + ",\"diagnosticDeltaValidated\":false"
                + ",\"stats\":" + statsJson
                + ",\"changedFiles\":[],\"touchedFiles\":[]"
                + ",\"workspaceEdit\":{\"changes\":[],\"fileOperations\":[],\"warnings\":[],\"preconditions\":[],\"stats\":" + statsJson + "}}";
    }

    /**
     * Builds the one canonical accepted-result JSON (G036). {@code stats} (top-level and inside {@code workspaceEdit})
     * and {@code changedFiles} are derived from {@code edits}+{@code fileOperations}; never hard-coded.
     *
     * <p>If {@code validationRequired} is true (always the case on apply, and on any preview that has been routed
     * through the diagnostic validator), a non-{@link DiagnosticDelta#isReal() real} delta is rejected with an
     * {@link IllegalStateException}: a fake all-empty delta must never be the authoritative answer (G035).
     */
    public static String acceptedResult(
            Path projectRoot,
            String operation,
            boolean applied,
            String semanticTargetJson,
            List<PlannerSupport.TextEdit> edits,
            List<FileOperation> fileOperations,
            List<String> warnings,
            List<String> preconditions,
            DiagnosticDelta diagnosticDelta,
            boolean validationRequired) throws IOException {
        return acceptedResult(projectRoot, operation, applied, semanticTargetJson, edits, fileOperations,
                warnings, preconditions, diagnosticDelta, validationRequired, null);
    }

    /**
     * Canonical accepted-result overload for relocation/extraction ops that additionally surface an
     * {@code accessPlans} array inside {@code workspaceEdit} (move, extract method). When {@code accessPlansJson}
     * is null or blank, the output is byte-identical to the base overload.
     */
    public static String acceptedResult(
            Path projectRoot,
            String operation,
            boolean applied,
            String semanticTargetJson,
            List<PlannerSupport.TextEdit> edits,
            List<FileOperation> fileOperations,
            List<String> warnings,
            List<String> preconditions,
            DiagnosticDelta diagnosticDelta,
            boolean validationRequired,
            String accessPlansJson) throws IOException {
        return acceptedResult(projectRoot, operation, applied, semanticTargetJson, edits, fileOperations,
                warnings, preconditions, diagnosticDelta, validationRequired, accessPlansJson, null);
    }

    /**
     * G004: accepted-result overload that additionally surfaces a top-level {@code structuredWarnings} array carrying the
     * design §6.3 schema ({@code {"code":..., "message":...}} objects). The plain {@code warnings} string array is left
     * unchanged, so callers parsing warnings as strings are unaffected — the structured array is purely additive. When
     * {@code structuredWarningsJson} is null or blank the output is byte-identical to the {@code accessPlansJson} overload.
     */
    public static String acceptedResult(
            Path projectRoot,
            String operation,
            boolean applied,
            String semanticTargetJson,
            List<PlannerSupport.TextEdit> edits,
            List<FileOperation> fileOperations,
            List<String> warnings,
            List<String> preconditions,
            DiagnosticDelta diagnosticDelta,
            boolean validationRequired,
            String accessPlansJson,
            String structuredWarningsJson) throws IOException {
        if (validationRequired && !diagnosticDelta.isReal()) {
            throw new IllegalStateException(
                    "a real PreviewDiagnosticValidator delta is required for operation '" + operation
                            + "'; a fake empty diagnostic delta must never be authoritative");
        }
        String changesJson = PlannerSupport.changesJson(projectRoot, edits);
        String fileOperationsJson = fileOperations.stream().map(FileOperation::toJson)
                .collect(Collectors.joining(",", "[", "]"));
        String warningsJson = PlannerSupport.warningsJson(warnings);
        String preconditionsJson = JsonUtil.array(preconditions);
        Stats stats = deriveStats(projectRoot, edits, fileOperations);
        String statsJson = stats.json();
        String changedFilesJson = JsonUtil.array(stats.changedFiles());
        // Every accepted result's semanticTarget must carry the operation (schema consistency, G002). When a planner
        // supplies its own semanticTarget object (e.g. {"semanticKey":...} required by the session target-identity
        // gate), inject "operation" if absent rather than letting it drift out of the canonical shape.
        String semanticTarget;
        if (semanticTargetJson == null || semanticTargetJson.isBlank()) {
            semanticTarget = "{\"operation\":" + JsonUtil.quote(operation) + "}";
        } else if (semanticTargetJson.stripLeading().startsWith("{") && !semanticTargetJson.contains("\"operation\"")) {
            String rest = semanticTargetJson.stripLeading().substring(1).stripLeading();
            String sep = rest.startsWith("}") ? "" : ",";
            semanticTarget = "{\"operation\":" + JsonUtil.quote(operation) + sep + rest;
        } else {
            semanticTarget = semanticTargetJson;
        }

        return "{\"accepted\":true"
                + ",\"operation\":" + JsonUtil.quote(operation)
                + ",\"mode\":" + JsonUtil.quote(applied ? "apply" : "preview")
                + ",\"applied\":" + applied
                + ",\"semanticTarget\":" + semanticTarget
                + ",\"target\":" + semanticTarget
                + ",\"diagnostics\":" + JsonUtil.array(diagnosticDelta.diagnostics())
                + ",\"warnings\":" + warningsJson
                + (structuredWarningsJson == null || structuredWarningsJson.isBlank()
                        ? "" : ",\"structuredWarnings\":" + structuredWarningsJson)
                + ",\"diagnosticDelta\":" + diagnosticDelta.json()
                // HB-10: a machine-checkable marker distinguishing a real javac before/after delta from the
                // not-yet-validated placeholder. An empty real delta (a clean refactor) is byte-identical to the
                // unvalidated placeholder, so the client/manager cannot otherwise tell them apart. Main requires this to
                // be true before surfacing an accepted V2 preview.
                + ",\"diagnosticDeltaValidated\":" + diagnosticDelta.isReal()
                + ",\"stats\":" + statsJson
                + ",\"changedFiles\":" + changedFilesJson
                + ",\"touchedFiles\":" + JsonUtil.array(stats.touchedFiles())
                + ",\"workspaceEdit\":{\"changes\":" + changesJson
                + ",\"fileOperations\":" + fileOperationsJson
                + ",\"warnings\":" + warningsJson
                + ",\"preconditions\":" + preconditionsJson
                + (accessPlansJson == null || accessPlansJson.isBlank()
                        ? "" : ",\"accessPlans\":" + accessPlansJson)
                + ",\"stats\":" + statsJson + "}}";
    }
}
