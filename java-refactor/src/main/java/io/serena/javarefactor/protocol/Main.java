package io.serena.javarefactor.protocol;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON-lines sidecar entry point for Serena's Java-only refactoring backend.
 *
 * <p>The protocol exposes initialize, status, preview, apply, and shutdown. Preview/apply compute structured
 * workspace-edit results only; Python owns validation and file mutation.</p>
 *
 * <p>Each request line is parsed as real JSON (see {@link Json}). Top-level fields and any nested {@code params}
 * object are flattened into a single field view so handlers can read parameters uniformly regardless of whether a
 * given method sends them at the top level (initialize/status/resolveTarget) or nested (preview/apply).</p>
 */
public final class Main {
    private static final String PROTOCOL_VERSION = "serena-java-refactor/0.1";

    private boolean initialized;
    private boolean shutdownRequested;
    private String projectRoot;
    private String configuration;
    private String javaHome;
    private java.nio.file.Path projectDataDir;
    private String lastModelCacheSource = "fresh";
    // The project-model key of the most recent discovery. When the next discovery yields a different key (build files or
    // classpath jar stamps changed), the pooled StandardJavaFileManagers are invalidated so a rebuilt jar cannot be
    // served from a stale scan. null until the first discovery, where there is nothing pooled to invalidate.
    private String lastModelKey;
    // Wall-clock duration (ms) of the most recent project-model discovery (extraction + validation or cache lookup).
    // -1 until the first discovery. Surfaced as the design's status "lastModelRefreshMs".
    private long lastModelRefreshMs = -1;
    private final Instant startedAt;
    private final ProjectModelCache modelCache = new ProjectModelCache();
    private final ExtractionCache extractionCache = new ExtractionCache();

    private Main() {
        this.initialized = false;
        this.shutdownRequested = false;
        this.projectRoot = null;
        this.configuration = null;
        this.startedAt = Instant.now();
    }

    public static void main(String[] args) throws IOException {
        new Main().run();
    }

    private void run() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        String line;
        while (!shutdownRequested && (line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            String id = "";
            String response;
            try {
                Map<String, Object> fields = flatten(Json.parseObject(line));
                id = str(fields, "id", "");
                response = handleRequest(id, str(fields, "method", ""), fields);
            } catch (RuntimeException e) {
                response = error(id, "malformed request: " + e.getMessage());
            }
            System.out.println(response);
            System.out.flush();
        }
    }

    /** Flattens a parsed request: top-level fields, with any nested {@code params} object overlaid on top. */
    private static Map<String, Object> flatten(Map<String, Object> root) {
        Map<String, Object> fields = new LinkedHashMap<>(root);
        if (root.get("params") instanceof Map<?, ?> params) {
            for (Map.Entry<?, ?> entry : params.entrySet()) {
                fields.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return fields;
    }

    /**
     * Merges the designed initialize inputs into a single configuration JSON string the discovery layer parses
     * uniformly: the structured {@code config} object is overlaid over the parsed legacy {@code configuration} string,
     * then the top-level {@code encoding} and {@code ignoredPatterns} overlay their specific keys. Backward compatible:
     * a request carrying only the legacy {@code configuration} string (or none) resolves to the same effective config.
     */
    private static String resolveConfiguration(Map<String, Object> fields) {
        Map<String, Object> merged = new LinkedHashMap<>();
        String legacy = str(fields, "configuration");
        if (legacy != null && !legacy.isBlank() && !"default".equals(legacy.trim())) {
            merged.putAll(Json.parseObject(legacy));
        }
        if (fields.get("config") instanceof Map<?, ?> config) {
            for (Map.Entry<?, ?> entry : config.entrySet()) {
                merged.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        if (fields.get("encoding") instanceof String encoding && !encoding.isBlank()) {
            merged.put("encoding", encoding);
        }
        if (fields.get("ignoredPatterns") instanceof List<?> ignoredPatterns) {
            merged.put("ignoredPatterns", ignoredPatterns);
        }
        if (merged.isEmpty()) {
            return legacy == null ? "default" : legacy;
        }
        return Json.write(merged);
    }

    private String handleRequest(String id, String method, Map<String, Object> fields) {
        switch (method) {
            case "initialize":
                initialized = true;
                projectRoot = str(fields, "projectRoot");
                javaHome = str(fields, "javaHome");
                configuration = resolveConfiguration(fields);
                String dataDir = str(fields, "projectDataDir");
                projectDataDir = dataDir == null ? null : java.nio.file.Path.of(dataDir);
                return response(id, statusJson(bool(fields, "refresh", false)));
            case "status":
                return response(id, statusJson(bool(fields, "refresh", false)));
            case "preview":
            case "apply": {
                String operation = str(fields, "operation", "unknown");
                String operationJson = operationJson(operation, fields, "apply".equals(method));
                if (operationJson != null) {
                    return response(id, operationJson);
                }
                return response(id, refactorResultJson("apply".equals(method), operation));
            }
            case "validateEdit":
                return response(id, validateEditJson(fields));
            case "resolveTarget":
                return response(id, semanticAnalysisJson(fields, false));
            case "scanReferences":
                return response(id, semanticAnalysisJson(fields, true));
            case "shutdown":
                shutdownRequested = true;
                FileManagerPool.INSTANCE.invalidate();
                return response(id, "{\"shutdown\":true}");
            default:
                return error(id, "unsupported method: " + method);
        }
    }

    /** Returns the planner JSON for a supported operation, or null if the operation is not implemented. */
    private String operationJson(String operation, Map<String, Object> fields, boolean apply) {
        return switch (operation) {
            case "semanticRename" -> semanticRenameJson(fields, apply);
            case "safeDelete" -> safeDeleteJson(fields, apply);
            case "moveTopLevelType" -> moveTopLevelTypeJson(fields, apply);
            case "inlineLocalVariable", "inlineConstant" -> inlineVariableJson(fields, operation, apply);
            default -> null;
        };
    }

    private String statusJson(boolean refreshed) {
        JavaProjectModel projectModel = projectRoot == null ? null : discoverModel();
        boolean ready = initialized && projectModel != null && projectModel.errors().isEmpty();
        int semanticErrors = projectModel == null ? 0 : projectModel.errors().size();
        String status = !initialized || projectModel == null ? "unavailable" : (ready ? "ready" : "error");
        String buildTool = projectModel == null ? null : projectModel.discoveryKind();
        int sourceSets = projectModel == null ? 0 : projectModel.sourceSets().size();
        int javaFiles = projectModel == null ? 0 : projectModel.javaFileCount();
        int classpathEntries = projectModel == null ? 0 : projectModel.classpath().size();
        return "{"
                + "\"ready\":" + ready + ","
                // Designed top-level readiness contract (refactor-feature-plan.md §Status). Surfaced alongside the
                // detailed nested projectModel so existing consumers keep working.
                + "\"status\":" + JsonUtil.quote(status) + ","
                + "\"jdk\":" + JsonUtil.quote(Runtime.version().toString()) + ","
                + "\"javaHome\":" + JsonUtil.quote(javaHome) + ","
                + "\"buildTool\":" + JsonUtil.quote(buildTool) + ","
                + "\"sourceSets\":" + sourceSets + ","
                + "\"javaFiles\":" + javaFiles + ","
                + "\"classpathEntries\":" + classpathEntries + ","
                + "\"lastModelRefreshMs\":" + lastModelRefreshMs + ","
                + "\"semanticErrors\":" + semanticErrors + ","
                + "\"protocolVersion\":" + JsonUtil.quote(PROTOCOL_VERSION) + ","
                + "\"projectRoot\":" + JsonUtil.quote(projectRoot) + ","
                + "\"configuration\":" + JsonUtil.quote(configuration) + ","
                + "\"startedAt\":" + JsonUtil.quote(startedAt.toString()) + ","
                + "\"refreshed\":" + refreshed + ","
                + "\"modelCacheSource\":" + JsonUtil.quote(projectModel == null ? null : lastModelCacheSource) + ","
                + "\"projectModel\":" + (projectModel == null ? "null" : projectModel.toJson())
                + "}";
    }

    private String refactorResultJson(boolean applyRequested, String operation) {
        return "{"
                + "\"accepted\":false,"
                + "\"applied\":false,"
                + "\"operation\":" + JsonUtil.quote(operation) + ","
                + "\"mode\":" + JsonUtil.quote(applyRequested ? "apply" : "preview") + ","
                + "\"refusal\":{"
                + "\"code\":\"unsupported_operation\","
                + "\"message\":\"No Java refactoring operation is implemented for this request yet.\""
                + "},"
                + "\"diagnostics\":[],"
                + "\"warnings\":[],"
                + "\"stats\":{\"editCount\":0,\"fileOperationCount\":0,\"touchedFileCount\":0},"
                + "\"workspaceEdit\":{"
                + "\"changes\":[],"
                + "\"fileOperations\":[],"
                + "\"warnings\":[],"
                + "\"preconditions\":[],"
                + "\"stats\":{\"editCount\":0,\"fileOperationCount\":0,\"touchedFileCount\":0}"
                + "}"
                + "}";
    }

    private String semanticAnalysisJson(Map<String, Object> fields, boolean includeReferences) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before semantic analysis.");
        }
        String relativePath = str(fields, "relativePath");
        long line = lng(fields, "line", -1L);
        long column = lng(fields, "column", -1L);
        if (relativePath == null || line < 1 || column < 1) {
            return refusalJson("malformed_target", "Semantic analysis requires relativePath, one-based line, and one-based column.");
        }
        JavaProjectModel projectModel = discoverModel();
        String gateRefusal = modelGateRefusal(projectModel, false);
        if (gateRefusal != null) {
            return gateRefusal;
        }
        try (SemanticIndex index = SemanticIndex.open(projectModel, relativePath)) {
            RefactorAnalysisResult result = index.resolveTarget(relativePath, line, column, str(fields, "nameHint"));
            if (result.target() == null) {
                return refusalJson("target_not_found", "No refactorable Java symbol was found at the requested position.");
            }
            // Target-identity gate: when the caller supplied hints, the resolved element must match them, exactly as
            // the planners require before planning edits.
            String hintMismatch = targetHints(fields).mismatch(result.target());
            if (hintMismatch != null) {
                return refusalJson("target_mismatch", "Refused to analyze an unverified target: " + hintMismatch);
            }
            return "{"
                    + "\"accepted\":true,"
                    + "\"target\":" + result.targetJson(projectModel.projectRoot()) + ","
                    + "\"references\":" + (includeReferences ? result.referencesJson(projectModel.projectRoot()) : "[]") + ","
                    + "\"stats\":{\"referenceCount\":" + (includeReferences ? result.references().size() : 0) + "}"
                    + "}";
        } catch (Exception e) {
            return refusalJson("semantic_analysis_failed", e.getMessage());
        }
    }

    private String semanticRenameJson(Map<String, Object> fields, boolean apply) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before semantic rename.");
        }
        String relativePath = str(fields, "relativePath");
        String newName = str(fields, "newName");
        long line = lng(fields, "line", -1L);
        long column = lng(fields, "column", -1L);
        if (relativePath == null || newName == null || line < 1 || column < 1) {
            return refusalJson("malformed_rename", "Semantic rename requires relativePath, one-based line, one-based column, and newName.");
        }
        JavaProjectModel projectModel = discoverModel();
        String gateRefusal = modelGateRefusal(projectModel, apply);
        if (gateRefusal != null) {
            return gateRefusal;
        }
        try {
            return new SemanticRenamePlanner().plan(
                    projectModel, relativePath, line, column, newName,
                    bool(fields, "includeJavadocs", false), bool(fields, "includeComments", false), targetHints(fields));
        } catch (Exception e) {
            return refusalJson("semantic_rename_failed", e.getMessage());
        }
    }

    private String safeDeleteJson(Map<String, Object> fields, boolean apply) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before safe delete.");
        }
        String relativePath = str(fields, "relativePath");
        long line = lng(fields, "line", -1L);
        long column = lng(fields, "column", -1L);
        if (relativePath == null || line < 1 || column < 1) {
            return refusalJson("malformed_safe_delete", "Safe delete requires relativePath, one-based line, and one-based column.");
        }
        JavaProjectModel projectModel = discoverModel();
        String gateRefusal = modelGateRefusal(projectModel, apply);
        if (gateRefusal != null) {
            return gateRefusal;
        }
        try {
            return new SafeDeletePlanner().plan(
                    projectModel, relativePath, line, column, bool(fields, "allowPublicApi", false), targetHints(fields));
        } catch (Exception e) {
            return refusalJson("safe_delete_failed", e.getMessage());
        }
    }

    private String moveTopLevelTypeJson(Map<String, Object> fields, boolean apply) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before moving a top-level type.");
        }
        String relativePath = str(fields, "relativePath");
        String targetPackage = str(fields, "targetPackage");
        String targetDirectory = str(fields, "targetDirectory");
        long line = lng(fields, "line", -1L);
        long column = lng(fields, "column", -1L);
        if (relativePath == null || line < 1 || column < 1) {
            return refusalJson("malformed_move", "Move top-level type requires relativePath, line, and column.");
        }
        if ((targetPackage == null) == (targetDirectory == null)) {
            return refusalJson("malformed_move", "Move top-level type requires exactly one of targetPackage or targetDirectory.");
        }
        JavaProjectModel projectModel = discoverModel();
        String gateRefusal = modelGateRefusal(projectModel, apply);
        if (gateRefusal != null) {
            return gateRefusal;
        }
        try {
            return new MoveTopLevelTypePlanner().plan(
                    projectModel, relativePath, line, column, targetPackage, targetDirectory, targetHints(fields));
        } catch (Exception e) {
            return refusalJson("move_failed", e.getMessage());
        }
    }

    private String inlineVariableJson(Map<String, Object> fields, String operation, boolean apply) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before inline.");
        }
        String relativePath = str(fields, "relativePath");
        long line = lng(fields, "line", -1L);
        long column = lng(fields, "column", -1L);
        if (relativePath == null || line < 1 || column < 1) {
            return refusalJson("malformed_inline", "Inline requires relativePath, line, and column.");
        }
        JavaProjectModel projectModel = discoverModel();
        String gateRefusal = modelGateRefusal(projectModel, apply);
        if (gateRefusal != null) {
            return gateRefusal;
        }
        try {
            return new InlineVariablePlanner().plan(projectModel, relativePath, line, column,
                    "inlineConstant".equals(operation), apply, bool(fields, "allowPublicApi", false), targetHints(fields));
        } catch (Exception e) {
            return refusalJson("inline_failed", e.getMessage());
        }
    }

    /**
     * Validates a staged (post-edit) overlay against the project model WITHOUT touching disk. {@code changedFiles} maps
     * project-relative paths to new full content, {@code deletedFiles} lists paths to exclude, and {@code renamedFiles}
     * pairs {@code oldPath}/{@code newPath} (the new path's content is expected in {@code changedFiles}). The project
     * model is discovered (reusing the extraction cache) and javac runs per source set with the overlay substituted for
     * on-disk source. Returns the overlay's compiler diagnostics; the on-disk {@link #discoverModel()} cache is left
     * untouched so the post-apply guard still validates the real workspace.
     */
    private String validateEditJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before edit validation.");
        }
        JavaProjectModel unvalidated = new ProjectModelDiscoverer(extractionCache)
                .buildUnvalidatedModel(java.nio.file.Path.of(projectRoot), configuration);
        if (!unvalidated.errors().isEmpty()) {
            return refusalJson("project_model_errors", String.join("\n", unvalidated.errors()));
        }
        FileOverlay overlay;
        try {
            overlay = FileOverlay.fromProtocol(
                    java.nio.file.Path.of(projectRoot),
                    mapField(fields, "changedFiles"),
                    listField(fields, "deletedFiles"),
                    listField(fields, "renamedFiles"));
        } catch (RuntimeException e) {
            return refusalJson("malformed_overlay", e.getMessage());
        }
        JavacSession session = new JavacSession();
        // The real, unsuppressed javac errors for the overlay. Reported as `compilerErrors` so the Python apply gate can
        // diff staged-vs-baseline errors even when allowIncompleteAnalysis routes them into `warnings` for presentation.
        List<String> compilerErrors = session.collectDiagnostics(unvalidated, overlay);
        JavaProjectModel validated = unvalidated.withCompilerDiagnostics(compilerErrors);
        List<String> errors = validated.errors();
        StringBuilder json = new StringBuilder();
        json.append("{\"accepted\":true,\"ready\":").append(errors.isEmpty()).append(',');
        json.append("\"errors\":").append(JsonUtil.array(errors)).append(',');
        json.append("\"compilerErrors\":").append(JsonUtil.array(compilerErrors)).append(',');
        json.append("\"warnings\":").append(JsonUtil.array(validated.warnings())).append('}');
        return json.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Map<String, Object> fields, String key) {
        return fields.get(key) instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listField(Map<String, Object> fields, String key) {
        return fields.get(key) instanceof List<?> value ? (List<Object>) value : List.of();
    }

    private JavaProjectModel discoverModel() {
        long start = System.nanoTime();
        try {
            return discoverModelTimed();
        } finally {
            lastModelRefreshMs = (System.nanoTime() - start) / 1_000_000L;
        }
    }

    private JavaProjectModel discoverModelTimed() {
        JavaProjectModel unvalidated = new ProjectModelDiscoverer(extractionCache).buildUnvalidatedModel(java.nio.file.Path.of(projectRoot), configuration);
        if (!unvalidated.errors().isEmpty()) {
            return unvalidated;
        }
        String key = ProjectModelCache.keyFor(unvalidated, configuration);
        // A changed key means the build files / classpath jar stamps changed, so the pooled file managers hold a stale
        // jar/file-system scan. Drop them before this discovery's validation/indexing reuses the pool.
        if (lastModelKey != null && !lastModelKey.equals(key)) {
            FileManagerPool.INSTANCE.invalidate();
        }
        lastModelKey = key;
        JavaProjectModel cached = modelCache.get(key);
        if (cached != null) {
            lastModelCacheSource = "memory";
            return cached;
        }
        // In-process miss: a restarted sidecar can still reuse a prior validation persisted under Serena's project-data
        // directory, keyed by the same content-sensitive key, instead of re-running javac.
        JavaProjectModel persisted = ProjectModelCache.loadPersistent(projectDataDir, key, unvalidated);
        if (persisted != null) {
            modelCache.put(key, persisted);
            lastModelCacheSource = "persistent";
            return persisted;
        }
        JavaProjectModel validated = new JavacSession().validate(unvalidated);
        modelCache.put(key, validated);
        ProjectModelCache.storePersistent(projectDataDir, key, validated);
        lastModelCacheSource = "fresh";
        return validated;
    }

    /**
     * Shared project-model gate implementing the V1 incomplete-analysis contract (refactor-feature-plan.md §Incomplete
     * project behavior): hard discovery/extraction errors refuse every operation; a model whose only problems are
     * unresolved compiler diagnostics keeps PREVIEW available (warning-only — the planners surface the caveat via
     * {@code PlannerSupport.modelSafetyWarnings}) but refuses APPLY unless {@code allowIncompleteAnalysis} was
     * configured. Returns the refusal JSON, or null when the operation may proceed.
     */
    private String modelGateRefusal(JavaProjectModel projectModel, boolean apply) {
        if (projectModel == null) {
            return refusalJson("project_model_errors", "Project model could not be discovered.");
        }
        if (!projectModel.hardErrors().isEmpty()) {
            return refusalJson("project_model_errors", String.join("\n", projectModel.hardErrors()));
        }
        if (apply && projectModel.analysisIncomplete() && !projectModel.allowIncompleteAnalysis()) {
            return refusalJson("incomplete_analysis_apply_refused",
                    "Apply was refused because project analysis is incomplete (javac reported unresolved diagnostics, "
                            + "e.g. a broken or partial classpath), so semantic resolution cannot be trusted for a "
                            + "mutating edit. Preview remains available. Fix the diagnostics, or set "
                            + "java_refactor.allow_incomplete_analysis: true to opt in to applying against an "
                            + "incompletely analyzed project (newly introduced compiler errors are still rejected).\n"
                            + String.join("\n", projectModel.compilerDiagnostics()));
        }
        return null;
    }

    private String refusalJson(String code, String message) {
        return "{"
                + "\"accepted\":false,"
                + "\"refusal\":{\"code\":" + JsonUtil.quote(code) + ",\"message\":" + JsonUtil.quote(message) + "},"
                + "\"diagnostics\":[],"
                + "\"warnings\":[],"
                + "\"stats\":{}"
                + "}";
    }

    /**
     * The caller-supplied target-identity hints (name/kind/arity) for an operation request. Absent hints verify
     * nothing, so position-only clients keep working; present hints make planners refuse a position that resolved to a
     * different element than the one the caller named.
     */
    private static TargetHints targetHints(Map<String, Object> fields) {
        return new TargetHints(str(fields, "nameHint"), str(fields, "kindHint"), lng(fields, "arityHint", -1L));
    }

    private static String str(Map<String, Object> fields, String key) {
        return fields.get(key) instanceof String value ? value : null;
    }

    private static String str(Map<String, Object> fields, String key, String defaultValue) {
        String value = str(fields, key);
        return value == null ? defaultValue : value;
    }

    private static long lng(Map<String, Object> fields, String key, long defaultValue) {
        return fields.get(key) instanceof Number value ? value.longValue() : defaultValue;
    }

    private static boolean bool(Map<String, Object> fields, String key, boolean defaultValue) {
        return fields.get(key) instanceof Boolean value ? value : defaultValue;
    }

    private static String response(String id, String resultJson) {
        return "{\"id\":" + JsonUtil.quote(id) + ",\"result\":" + resultJson + "}";
    }

    private static String error(String id, String message) {
        return "{\"id\":" + JsonUtil.quote(id) + ",\"error\":" + JsonUtil.quote(message) + "}";
    }
}
