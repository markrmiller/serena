package io.serena.javarefactor.protocol;

import io.serena.javarefactor.compiler.DiagnosticInfo;
import io.serena.javarefactor.compiler.FileOverlay;
import io.serena.javarefactor.compiler.JavacSession;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.project.JavaProjectModel;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PreviewDiagnosticValidator {
    private final JavacSession javac = new JavacSession();

    /** Validates a preview-mode plan (compiler-error/validation refusals report {@code mode:"preview"}). */
    String validate(String operation, String previewJson, JavaProjectModel model) {
        return validate(operation, previewJson, model, false);
    }

    /**
     * G002: validates a plan, threading {@code applyRequested} so a compiler-error or validation-failure refusal reports
     * the ACTUAL requested mode ({@code apply} vs {@code preview}) instead of a hard-coded {@code "preview"}. The accepted
     * shape is unchanged — it preserves the plan's own {@code mode} — and the refusal shape is unchanged except for that
     * accurate mode (it still carries the real javac delta and deliberately omits {@code workspaceEdit} so nothing can be
     * committed from a refusal).
     */
    String validate(String operation, String previewJson, JavaProjectModel model, boolean applyRequested) {
        try {
            Map<String, Object> preview = Json.parseObject(previewJson);
            Charset charset = SemanticIndex.charsetOf(model);
            JavacSession.DiagnosticReport before = javac.collectDiagnosticReport(model, emptyOverlay(model));
            JavacSession.DiagnosticReport after = javac.collectDiagnosticReport(model, overlayFromPreview(model.projectRoot(), preview, charset));
            DiagnosticDelta delta = DiagnosticDelta.from(before, after);
            if (!delta.newErrors().isEmpty()) {
                return refusalJson(operation, delta, applyRequested, "new_compiler_errors",
                        "Refactor would introduce javac ERROR diagnostics.");
            }
            // G002: under complete-analysis (the default), an accepted preview/apply must leave the after-state with NO
            // javac errors — not merely no NEW errors. Pre-existing errors that the refactor neither introduced nor
            // resolved are tolerated only when java_refactor.allow_incomplete_analysis is explicitly opted in. The
            // refusal is deliberately distinct from new_compiler_errors so callers can tell "the edit broke compilation"
            // apart from "the project already does not compile and you have not opted into incomplete analysis".
            //
            // This complete-analysis gate applies ONLY when the model is itself complete (discovery recorded no
            // diagnostics). When model.analysisIncomplete() is true the project is already under the V1
            // incomplete-analysis contract: PREVIEW must stay available warning-only, and APPLY is refused upstream by
            // Main.modelGateRefusal (incomplete_analysis_apply_refused) — not here. Firing this gate for an incomplete
            // model would wrongly turn that warning-only preview into a refusal.
            if (!model.allowIncompleteAnalysis() && !model.analysisIncomplete() && !delta.afterErrors().isEmpty()) {
                return refusalJson(operation, delta, applyRequested, "preexisting_compiler_errors_not_allowed",
                        "Refactor leaves pre-existing javac ERROR diagnostics; complete-analysis mode requires the "
                                + "after-state to compile cleanly. Set java_refactor.allow_incomplete_analysis: true to "
                                + "tolerate unchanged pre-existing errors.");
            }
            return acceptedJson(preview, delta);
        } catch (IOException | RuntimeException error) {
            return validationFailureJson(operation, error.getMessage(), applyRequested);
        }
    }

    private FileOverlay emptyOverlay(JavaProjectModel model) {
        return FileOverlay.fromProtocol(model.projectRoot(), Map.of(), List.of(), List.of());
    }

    private FileOverlay overlayFromPreview(Path projectRoot, Map<String, Object> preview, Charset charset) throws IOException {
        Object workspaceEditValue = preview.get("workspaceEdit");
        if (!(workspaceEditValue instanceof Map<?, ?> workspaceEdit)) {
            return FileOverlay.fromProtocol(projectRoot, Map.of(), List.of(), List.of());
        }

        Map<String, Object> changedFiles = changedFiles(projectRoot, workspaceEdit, charset);
        List<Object> deletedFiles = new ArrayList<>();
        List<Object> renamedFiles = new ArrayList<>();
        collectFileOperations(projectRoot, workspaceEdit, changedFiles, deletedFiles, renamedFiles, charset);
        return FileOverlay.fromProtocol(projectRoot, changedFiles, deletedFiles, renamedFiles);
    }

    private Map<String, Object> changedFiles(Path projectRoot, Map<?, ?> workspaceEdit, Charset charset) throws IOException {
        Map<String, Object> changedFiles = new LinkedHashMap<>();
        Object changesValue = workspaceEdit.get("changes");
        if (!(changesValue instanceof List<?> changes)) {
            return changedFiles;
        }

        for (Object changeValue : changes) {
            if (!(changeValue instanceof Map<?, ?> change)) {
                continue;
            }
            Object pathValue = change.get("path");
            Object editsValue = change.get("edits");
            if (!(pathValue instanceof String relativePath) || !(editsValue instanceof List<?> edits)) {
                continue;
            }
            String content = String.valueOf(
                    changedFiles.getOrDefault(relativePath, Files.readString(projectRoot.resolve(relativePath), charset)));
            List<Map<?, ?>> ordered = new ArrayList<>();
            for (Object editValue : edits) {
                if (editValue instanceof Map<?, ?> edit) {
                    ordered.add(edit);
                }
            }
            ordered.sort(Comparator.comparingLong((Map<?, ?> edit) -> longField(edit, "startOffset")).reversed());
            StringBuilder builder = new StringBuilder(content);
            for (Map<?, ?> edit : ordered) {
                int start = Math.toIntExact(longField(edit, "startOffset"));
                int end = Math.toIntExact(longField(edit, "endOffset"));
                Object newText = edit.get("newText");
                builder.replace(start, end, newText == null ? "" : String.valueOf(newText));
            }
            changedFiles.put(relativePath, builder.toString());
        }
        return changedFiles;
    }

    private void collectFileOperations(
            Path projectRoot,
            Map<?, ?> workspaceEdit,
            Map<String, Object> changedFiles,
            List<Object> deletedFiles,
            List<Object> renamedFiles,
            Charset charset) throws IOException {
        Object fileOperationsValue = workspaceEdit.get("fileOperations");
        if (!(fileOperationsValue instanceof List<?> fileOperations)) {
            return;
        }

        for (Object operationValue : fileOperations) {
            if (!(operationValue instanceof Map<?, ?> operation)) {
                continue;
            }
            Object kindValue = operation.get("kind");
            if (!(kindValue instanceof String kind)) {
                continue;
            }
            if ("delete".equals(kind)) {
                Object pathValue = operation.get("path");
                if (pathValue instanceof String path) {
                    deletedFiles.add(path);
                }
            } else if ("create".equals(kind)) {
                Object pathValue = operation.get("path");
                if (pathValue instanceof String path) {
                    Object content = operation.containsKey("content") ? operation.get("content") : "";
                    changedFiles.put(path, String.valueOf(content));
                }
            } else if ("rename".equals(kind)) {
                Object oldPathValue = operation.get("oldPath");
                Object newPathValue = operation.get("newPath");
                if (oldPathValue instanceof String oldPath && newPathValue instanceof String newPath) {
                    Map<String, Object> pair = new LinkedHashMap<>();
                    pair.put("oldPath", oldPath);
                    pair.put("newPath", newPath);
                    renamedFiles.add(pair);
                    changedFiles.putIfAbsent(newPath, changedFiles.getOrDefault(
                            oldPath, Files.readString(projectRoot.resolve(oldPath), charset)));
                }
            }
        }
    }

    private long longField(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException(name + " is required.");
    }

    private String acceptedJson(Map<String, Object> preview, DiagnosticDelta delta) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : preview.entrySet()) {
            fields.put(entry.getKey(), Json.write(entry.getValue()));
        }
        // Route the authoritative delta through ResponseBuilder.DiagnosticDelta.real so the only diagnosticDelta that
        // ever reaches an accepted result is a real javac-derived one (the fake placeholder is replaced here, G035).
        ResponseBuilder.DiagnosticDelta real = delta.asResponseDelta();
        fields.put("diagnostics", JsonUtil.array(real.diagnostics()));
        fields.put("diagnosticDelta", real.json());
        // HB-10: this is the authoritative javac before/after delta, so the validated marker flips to true here. It is
        // the ONLY place an accepted result's marker becomes true; a planner's placeholder result carries false.
        fields.put("diagnosticDeltaValidated", "true");
        return JsonUtil.object(fields);
    }

    private String refusalJson(String operation, DiagnosticDelta delta, boolean applyRequested, String code, String message) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("accepted", "false");
        fields.put("applied", "false");
        fields.put("operation", JsonUtil.quote(operation));
        // G002: report the ACTUAL requested mode rather than a hard-coded "preview" so a direct apply=true that is
        // refused for introducing compiler errors cannot misreport itself as a preview.
        fields.put("mode", JsonUtil.quote(applyRequested ? "apply" : "preview"));
        LinkedHashMap<String, String> refusal = new LinkedHashMap<>();
        refusal.put("code", JsonUtil.quote(code));
        refusal.put("message", JsonUtil.quote(message));
        fields.put("refusal", JsonUtil.object(refusal));
        fields.put("diagnostics", JsonUtil.array(DiagnosticInfo.displays(delta.afterDiagnostics())));
        fields.put("diagnosticDelta", delta.toJson());
        return JsonUtil.object(fields);
    }

    private String validationFailureJson(String operation, String message, boolean applyRequested) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("accepted", "false");
        fields.put("applied", "false");
        fields.put("operation", JsonUtil.quote(operation));
        // G002: accurate requested mode on a validation-failure refusal as well.
        fields.put("mode", JsonUtil.quote(applyRequested ? "apply" : "preview"));
        fields.put("error", "{\"code\":\"diagnostic_validation_failed\",\"message\":" + JsonUtil.quote(message) + "}");
        return JsonUtil.object(fields);
    }

    private static List<DiagnosticInfo> afterDiagnostics(JavacSession.DiagnosticReport report) {
        List<DiagnosticInfo> diagnostics = new ArrayList<>(report.errors());
        diagnostics.addAll(report.warnings());
        return diagnostics;
    }

    /**
     * Multiset difference using structural {@link DiagnosticInfo#identity()} (G003). Returns the elements of {@code left}
     * not matched, one-for-one, by an equal-identity element of {@code right}. Identity is location-independent, so a
     * diagnostic that merely shifts position is treated as the same diagnostic — preserving the prior regex-normalized
     * semantics without parsing formatted text.
     */
    private static List<DiagnosticInfo> difference(List<DiagnosticInfo> left, List<DiagnosticInfo> right) {
        Map<String, Integer> rightCounts = counts(right);
        List<DiagnosticInfo> result = new ArrayList<>();
        for (DiagnosticInfo diagnostic : left) {
            String key = diagnostic.identity();
            int count = rightCounts.getOrDefault(key, 0);
            if (count > 0) {
                rightCounts.put(key, count - 1);
            } else {
                result.add(diagnostic);
            }
        }
        return result;
    }

    private static List<DiagnosticInfo> unchanged(List<DiagnosticInfo> before, List<DiagnosticInfo> after) {
        Map<String, Integer> beforeCounts = counts(before);
        List<DiagnosticInfo> result = new ArrayList<>();
        for (DiagnosticInfo diagnostic : after) {
            String key = diagnostic.identity();
            int count = beforeCounts.getOrDefault(key, 0);
            if (count > 0) {
                result.add(diagnostic);
                beforeCounts.put(key, count - 1);
            }
        }
        return result;
    }

    private static Map<String, Integer> counts(List<DiagnosticInfo> diagnostics) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (DiagnosticInfo diagnostic : diagnostics) {
            result.merge(diagnostic.identity(), 1, Integer::sum);
        }
        return result;
    }

    private record DiagnosticDelta(
            List<DiagnosticInfo> beforeErrors,
            List<DiagnosticInfo> beforeWarnings,
            List<DiagnosticInfo> afterErrors,
            List<DiagnosticInfo> afterWarnings,
            List<DiagnosticInfo> newErrors,
            List<DiagnosticInfo> resolvedErrors,
            List<DiagnosticInfo> unchangedErrors,
            List<DiagnosticInfo> newWarnings,
            List<DiagnosticInfo> resolvedWarnings,
            List<DiagnosticInfo> unchangedWarnings) {
        static DiagnosticDelta from(JavacSession.DiagnosticReport before, JavacSession.DiagnosticReport after) {
            return new DiagnosticDelta(
                    before.errors(),
                    before.warnings(),
                    after.errors(),
                    after.warnings(),
                    difference(after.errors(), before.errors()),
                    difference(before.errors(), after.errors()),
                    unchanged(before.errors(), after.errors()),
                    difference(after.warnings(), before.warnings()),
                    difference(before.warnings(), after.warnings()),
                    unchanged(before.warnings(), after.warnings()));
        }

        List<DiagnosticInfo> afterDiagnostics() {
            return PreviewDiagnosticValidator.afterDiagnostics(new JavacSession.DiagnosticReport(afterErrors, afterWarnings));
        }

        /** Exposes this javac-derived delta as the authoritative {@link ResponseBuilder.DiagnosticDelta} (G035). */
        ResponseBuilder.DiagnosticDelta asResponseDelta() {
            return ResponseBuilder.DiagnosticDelta.real(toJson(), DiagnosticInfo.displays(afterDiagnostics()));
        }

        String toJson() {
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            fields.put("before", diagnosticsJson(beforeErrors, beforeWarnings));
            fields.put("after", diagnosticsJson(afterErrors, afterWarnings));
            fields.put("newErrors", DiagnosticInfo.arrayJson(newErrors));
            fields.put("resolvedErrors", DiagnosticInfo.arrayJson(resolvedErrors));
            fields.put("unchangedErrors", DiagnosticInfo.arrayJson(unchangedErrors));
            fields.put("newWarnings", DiagnosticInfo.arrayJson(newWarnings));
            fields.put("resolvedWarnings", DiagnosticInfo.arrayJson(resolvedWarnings));
            fields.put("unchangedWarnings", DiagnosticInfo.arrayJson(unchangedWarnings));
            return JsonUtil.object(fields);
        }

        private static String diagnosticsJson(List<DiagnosticInfo> errors, List<DiagnosticInfo> warnings) {
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            fields.put("errors", DiagnosticInfo.arrayJson(errors));
            fields.put("warnings", DiagnosticInfo.arrayJson(warnings));
            return JsonUtil.object(fields);
        }
    }
}
