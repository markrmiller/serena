package io.serena.javarefactor.protocol;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;
import io.serena.javarefactor.session.*;
import io.serena.javarefactor.operations.change_signature.*;
import io.serena.javarefactor.operations.hierarchy.*;
import io.serena.javarefactor.operations.extract_method.*;
import io.serena.javarefactor.operations.extract_interface.*;
import io.serena.javarefactor.operations.encapsulate_field.*;
import io.serena.javarefactor.shared.ProjectPathResolver;

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
    private final RefactorSessionManager sessionManager = new RefactorSessionManager();
    private final PreviewDiagnosticValidator previewDiagnosticValidator = new PreviewDiagnosticValidator();
    // V3 transformation-workspace registry (refactor-feature-plan-V3.md §1.1). Lazily built on first transformation.*
    // request because it needs projectRoot (only known after initialize) and the injected sidecar callbacks.
    private io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager transformationWorkspaceManager;

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

    // ---- G001: V2 nested session-request normalizer ----
    // The V2 plan sends createSession (and may send preview/apply) as a nested request:
    //   {operation, target:{relativePath,line,column,namePathHint,nameHint,kindHint}, arguments:{newName,parameters,...}}
    // Every planner and the policy/default layers read a single FLAT field map (relativePath, line, newName,
    // newReturnType, parameters, ...). This is the single authoritative place that flattens the nested shape onto that
    // flat map, so a nested request produces byte-identical planner input to the equivalent flat Python-facing request.

    // Source-spelling -> canonical flat field name. Unlisted keys are copied verbatim (camelCase passthrough), so the
    // table only carries spellings whose canonical flat name differs: the documented snake_case forms and the
    // {@code arguments.returnType} alias the ChangeSignaturePlanner reads as {@code newReturnType}.
    private static final Map<String, String> V2_REQUEST_ALIASES = Map.ofEntries(
            Map.entry("relative_path", "relativePath"),
            Map.entry("name_path_hint", "namePathHint"),
            Map.entry("name_hint", "nameHint"),
            Map.entry("kind_hint", "kindHint"),
            Map.entry("new_name", "newName"),
            Map.entry("returnType", "newReturnType"),
            Map.entry("return_type", "newReturnType"),
            Map.entry("new_return_type", "newReturnType"));

    /**
     * Flattens the V2 nested {@code target}/{@code arguments} request objects onto the flat field map, merging
     * {@code target.*} (relativePath/line/column/namePathHint/nameHint/kindHint) and {@code arguments.*} (operation
     * fields) under their canonical flat names. Returns a structured refusal when a nested value and an already-present
     * flat value disagree (an ambiguous request), or {@code null} when there is nothing nested to flatten or the merge
     * is unambiguous.
     */
    private String normalizeV2SessionRequest(Map<String, Object> fields) {
        // nothing to flatten for a flat (Python-facing) request
        Object target = fields.get("target");
        Object arguments = fields.get("arguments");
        boolean nestedTarget = target instanceof Map<?, ?>;
        boolean nestedArguments = arguments instanceof Map<?, ?>;
        if (!nestedTarget && !nestedArguments) {
            return null;
        }

        // flatten nested values onto the flat field map, recording flat/nested disagreements
        List<String> conflicts = new java.util.ArrayList<>();
        if (nestedTarget) {
            flattenNestedRequest(fields, (Map<?, ?>) target, conflicts);
        }
        if (nestedArguments) {
            flattenNestedRequest(fields, (Map<?, ?>) arguments, conflicts);
        }
        // the nested envelopes are consumed; remove them so no downstream layer re-reads a stale nested object
        fields.remove("target");
        fields.remove("arguments");

        // refuse rather than silently pick a side when the caller supplied a field both nested and flat with differing values
        if (!conflicts.isEmpty()) {
            String operation = str(fields, "operation", "unknown");
            return RefactorSessionManager.refusalJson(operation, "ambiguous_v2_request",
                    "V2 request supplies conflicting nested and flat values for: " + String.join(", ", conflicts)
                            + ". Supply each field exactly once.");
        }
        return null;
    }

    // copies each nested entry onto the flat field map under its canonical name; a nested value that disagrees with an
    // already-present flat value is recorded as a conflict rather than silently overwriting either side.
    private static void flattenNestedRequest(Map<String, Object> fields, Map<?, ?> nested, List<String> conflicts) {
        for (Map.Entry<?, ?> entry : nested.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            String canonical = V2_REQUEST_ALIASES.getOrDefault(key, key);
            Object value = entry.getValue();
            if (fields.containsKey(canonical) && !sameRequestValue(fields.get(canonical), value)) {
                conflicts.add(canonical);
                continue;
            }
            fields.put(canonical, value);
        }
    }

    // Equality for the nested-vs-flat conflict check. JSON numbers are parsed as Long (integral) or Double (decimal),
    // but a flat value injected programmatically could be a different Number width (e.g. Integer). Compare numbers by
    // value so width-only differences (6 as Integer vs 6L) are NOT treated as a conflicting request; fall back to
    // Objects.equals for everything else.
    private static boolean sameRequestValue(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            boolean integral = !(a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float);
            return integral ? na.longValue() == nb.longValue() : na.doubleValue() == nb.doubleValue();
        }
        return java.util.Objects.equals(a, b);
    }

    /**
     * G002: layers the V2 plan's public top-level createSession contract fields around the preserved nested envelope.
     * The nested envelope (accepted/mode/session/plan/edit/preview/validation) is kept verbatim for Python
     * compatibility; this only ADDS the documented public fields the V2 plan §1.1 mandates at the top level
     * (sessionId, status, summary, preconditions[], warnings[]) and enriches the preserved {@code preview} object
     * with the compact stat fields (filesChanged/textEdits/fileOperations) WITHOUT touching its real workspaceEdit.
     * Any parsing/shape surprise degrades gracefully to the untouched envelope.
     */
    private String withV2CreateSessionContract(String envelope, RefactorSession session, String planJson) {
        try {
            Map<String, Object> root = new LinkedHashMap<>(Json.parseObject(envelope));
            Map<String, Object> plan = Json.parseObject(planJson);
            int[] stats = planStatCounts(plan);

            root.put("sessionId", session.sessionId());
            root.put("status", "previewReady");
            root.put("summary", v2SessionSummary(session, stats));
            root.put("preconditions", planPreconditions(plan));
            root.put("warnings", planContractWarnings(plan));

            Object previewObj = root.get("preview");
            if (previewObj instanceof Map<?, ?> previewMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> preview = (Map<String, Object>) previewMap;
                preview.putIfAbsent("filesChanged", stats[0]);
                preview.putIfAbsent("textEdits", stats[1]);
                preview.putIfAbsent("fileOperations", stats[2]);
            }
            return Json.write(root);
        } catch (RuntimeException e) {
            return envelope;
        }
    }

    /** Returns {filesChanged, textEdits, fileOperations} counts derived from the accepted plan's stats/changedFiles. */
    private static int[] planStatCounts(Map<String, Object> plan) {
        int textEdits = 0;
        int fileOps = 0;
        int filesChanged = 0;
        if (plan.get("stats") instanceof Map<?, ?> stats) {
            textEdits = intValue(stats.get("editCount"));
            fileOps = intValue(stats.get("fileOperationCount"));
        }
        if (plan.get("changedFiles") instanceof List<?> changed) {
            filesChanged = changed.size();
        }
        return new int[] {filesChanged, textEdits, fileOps};
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * Returns the first positively-valued integer found in {@code map} under any of {@code keys}, or {@code defaultValue}
     * when none of the keys are present or the stored value is not a positive number.
     */
    private static int positiveIntOrDefault(Map<?, ?> map, int defaultValue, String... keys) {
        for (String key : keys) {
            Object raw = map.get(key);
            if (raw instanceof Number n && n.intValue() > 0) {
                return n.intValue();
            }
        }
        return defaultValue;
    }

    /** The plan's workspaceEdit preconditions, surfaced as the top-level contract array (empty when none). */
    private static List<Object> planPreconditions(Map<String, Object> plan) {
        if (plan.get("workspaceEdit") instanceof Map<?, ?> we
                && we.get("preconditions") instanceof List<?> preconditions) {
            return new java.util.ArrayList<>(preconditions);
        }
        return List.of();
    }

    /** The plan's structured warnings (preferred) or plain warnings, surfaced as the top-level contract array. */
    private static List<Object> planContractWarnings(Map<String, Object> plan) {
        if (plan.get("structuredWarnings") instanceof List<?> structured) {
            return new java.util.ArrayList<>(structured);
        }
        if (plan.get("warnings") instanceof List<?> warnings) {
            return new java.util.ArrayList<>(warnings);
        }
        return List.of();
    }

    private static String v2SessionSummary(RefactorSession session, int[] stats) {
        StringBuilder sb = new StringBuilder(session.operation());
        Object relativePath = session.requestFields().get("relativePath");
        if (relativePath != null && !relativePath.toString().isBlank()) {
            sb.append(" on ").append(relativePath);
        }
        sb.append(": ").append(stats[1]).append(stats[1] == 1 ? " edit" : " edits")
                .append(" across ").append(stats[0]).append(stats[0] == 1 ? " file" : " files");
        if (stats[2] > 0) {
            sb.append(", ").append(stats[2]).append(stats[2] == 1 ? " file operation" : " file operations");
        }
        return sb.toString();
    }

    /**
     * Merges the designed initialize inputs into a single configuration JSON string the discovery layer parses
     * uniformly: the structured {@code config} object is overlaid over the parsed legacy {@code configuration} string,
     * then the top-level {@code encoding} and {@code ignoredPatterns} overlay their specific keys. Backward compatible:
     * a request carrying only the legacy {@code configuration} string (or none) resolves to the same effective config.
     */
    private String resolveConfiguration(Map<String, Object> fields) {
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
        expandNestedV2Config(merged);
        sessionManager.configure(merged);
        if (merged.isEmpty()) {
            return legacy == null ? "default" : legacy;
        }
        return Json.write(merged);
    }

    @SuppressWarnings("unchecked")
    private static void expandNestedV2Config(Map<String, Object> merged) {
        Object javaRefactor = merged.get("java_refactor");
        if (!(javaRefactor instanceof Map<?, ?>)) {
            javaRefactor = merged.get("javaRefactor");
        }
        if (!(javaRefactor instanceof Map<?, ?> javaRefactorMap)) {
            return;
        }
        Object model = javaRefactorMap.get("model");
        if (model != null && !merged.containsKey("model")) {
            merged.put("model", model);
        }
        Object v2 = javaRefactorMap.get("v2");
        if (!(v2 instanceof Map<?, ?> v2Map)) {
            return;
        }
        // Surface the global V2 enable flag (snake_case + camelCase) so operationEnabled() can gate dispatch.
        copyIfAbsent(merged, v2Map, "enabled", "enabled");
        copyIfAbsent(merged, v2Map, "generated_sources", "generated_sources");
        copyIfAbsent(merged, v2Map, "generatedSources", "generated_sources");
        copyIfAbsent(merged, v2Map, "lombok", "lombok");
        copyIfAbsent(merged, v2Map, "lombokJar", "lombokJar");
        copyIfAbsent(merged, v2Map, "lombokJars", "lombokJars");
        copyIfAbsent(merged, v2Map, "lombokClasspath", "lombokClasspath");
        copyIfAbsent(merged, v2Map, "sessions", "sessions");
        copyIfAbsent(merged, v2Map, "operation_defaults", "operation_defaults");
        copyIfAbsent(merged, v2Map, "operationDefaults", "operation_defaults");
        copyIfAbsent(merged, v2Map, "access", "access");
        copyIfAbsent(merged, v2Map, "hierarchy", "hierarchy");
        copyIfAbsent(merged, v2Map, "change_signature", "change_signature");
        copyIfAbsent(merged, v2Map, "changeSignature", "change_signature");
        copyIfAbsent(merged, v2Map, "move_member", "move_member");
        copyIfAbsent(merged, v2Map, "moveMember", "move_member");
        copyIfAbsent(merged, v2Map, "extract_method", "extract_method");
        copyIfAbsent(merged, v2Map, "extractMethod", "extract_method");
        copyIfAbsent(merged, v2Map, "extract_interface", "extract_interface");
        copyIfAbsent(merged, v2Map, "extractInterface", "extract_interface");
        copyIfAbsent(merged, v2Map, "encapsulate_field", "encapsulate_field");
        copyIfAbsent(merged, v2Map, "encapsulateField", "encapsulate_field");
        copyIfAbsent(merged, v2Map, "inline_method", "inline_method");
        copyIfAbsent(merged, v2Map, "inlineMethod", "inline_method");
        // G002: introduce_field was accepted as a known key but never surfaced, so operationConfig("introduceField")
        // and operationEnabled() could not see a nested java_refactor.v2.introduce_field block. Copy it up (both
        // spellings) so its `enabled` flag really gates the operation.
        copyIfAbsent(merged, v2Map, "introduce_field", "introduce_field");
        copyIfAbsent(merged, v2Map, "introduceField", "introduce_field");
        // formatting is consumed Python-side (manager._run_external_formatter, design §19), not by the sidecar; it is
        // listed in KNOWN_V2_KEYS only so the sidecar does not warn on it.
        copyIfAbsent(merged, v2Map, "diagnostics", "diagnostics");
        copyIfAbsent(merged, v2Map, "imports", "imports");
        copyIfAbsent(merged, v2Map, "style", "style");
        warnUnknownV2Keys(v2Map);
    }

    // Recognized top-level java_refactor.v2 keys (snake_case and camelCase spellings). Unknown keys are forward-compat
    // warnings — not errors — at the sidecar boundary; Python's typed schema rejects them strictly at config-load.
    private static final java.util.Set<String> KNOWN_V2_KEYS = java.util.Set.of(
            "enabled",
            "generated_sources", "generatedSources",
            "lombok", "lombokJar", "lombokJars", "lombokClasspath",
            "sessions",
            "operation_defaults", "operationDefaults",
            "access", "hierarchy",
            "change_signature", "changeSignature",
            "move_member", "moveMember",
            "extract_method", "extractMethod",
            "extract_interface", "extractInterface",
            "encapsulate_field", "encapsulateField",
            "inline_method", "inlineMethod",
            "introduce_field", "introduceField",
            "formatting",
            "diagnostics", "imports", "style");

    private static void warnUnknownV2Keys(Map<?, ?> v2Map) {
        for (Object key : v2Map.keySet()) {
            if (key instanceof String name && !KNOWN_V2_KEYS.contains(name)) {
                System.err.println("WARNING: ignoring unknown java_refactor.v2 config key '" + name + "'.");
            }
        }
    }

    private static void copyIfAbsent(Map<String, Object> target, Map<?, ?> source, String sourceKey, String targetKey) {
        if (!target.containsKey(targetKey) && source.containsKey(sourceKey)) {
            target.put(targetKey, source.get(sourceKey));
        }
    }

    private Map<String, Object> applyConfiguredDefaults(String operation, Map<String, Object> fields) {
        Map<String, Object> config = effectiveConfigurationMap();
        if (config.isEmpty()) {
            return fields;
        }

        Map<String, Object> effective = new LinkedHashMap<>(fields);
        Map<?, ?> operationDefaults = mapValue(config, "operation_defaults", "operationDefaults");
        copyDefault(effective, operationDefaults, "visibility", "visibility", "defaultVisibility");
        copyDefault(effective, operationDefaults, "allowGenerated", "allowGenerated");
        copyDefault(effective, operationDefaults, "allowLombok", "allowLombok");

        Map<?, ?> generatedSources = mapValue(config, "generated_sources", "generatedSources");
        copyDefault(effective, generatedSources, "allowGenerated", "edit", "allowEdit");
        Map<?, ?> lombok = mapValue(config, "lombok");
        copyDefault(effective, lombok, "allowLombok", "allow", "edit");

        Map<?, ?> access = mapValue(config, "access");
        copyDefault(effective, access, "allowSecuritySensitivePrivateWidening", "allow_security_sensitive_private_widening", "allowSecuritySensitivePrivateWidening");
        copyDefault(effective, access, "allowAccessWidening", "allow_access_widening", "allowAccessWidening");

        Map<?, ?> operationConfig = operationConfig(config, operation);
        copyDefault(effective, operationConfig, "visibility", "visibility", "defaultVisibility");
        for (String[] rule : OPERATION_DEFAULT_RULES.getOrDefault(operation, List.of())) {
            // rule[0] is the exact request field the operation's planner reads; rule[1..] are the design config
            // key(s) (snake_case first, then camelCase/alias spellings) that feed it as an absent-only default.
            copyDefault(effective, operationConfig, rule[0], java.util.Arrays.copyOfRange(rule, 1, rule.length));
        }
        return effective;
    }

    // ---- G001: single authoritative V2 config-normalization table ----
    // Maps each V2 operation to the ordered list of {effective-request-field, design-config-key...} rules the sidecar
    // applies as absent-only defaults via copyDefault. Every entry's target (index 0) is the EXACT field the
    // operation's planner reads, so a design config key provably reaches planner behavior; index 1.. lists the
    // accepted source spellings (snake_case design key first). An explicit per-request field always wins (absent-only).
    // The shared change_signature / hierarchy confirmation rules are reused so introduceParameter mirrors
    // changeSignature and pull/push-down both honor hierarchy.allow_public_api_change.
    private static final List<String[]> CHANGE_SIGNATURE_RULES = List.of(
            new String[] {"confirmPublicApi", "confirm_public_api", "confirmPublicApi", "confirmPublicApiChange", "allow_public_api_change"},
            new String[] {"confirmPublicApiChange", "confirm_public_api_change", "confirmPublicApiChange", "confirmPublicApi", "allow_public_api_change"},
            new String[] {"allowRemovedSideEffectingArguments", "allow_removed_side_effecting_arguments", "allowRemovedSideEffectingArguments"},
            new String[] {"defaultValues", "default_values", "defaultValues"});

    private static final List<String[]> HIERARCHY_CONFIRM_RULES = List.of(
            new String[] {"confirmPublicApi", "confirm_public_api", "confirmPublicApi", "confirmPublicApiChange", "allow_public_api_change"},
            new String[] {"confirmPublicApiChange", "confirm_public_api_change", "confirmPublicApiChange", "confirmPublicApi", "allow_public_api_change"});

    private static final Map<String, List<String[]>> OPERATION_DEFAULT_RULES = buildOperationDefaultRules();

    private static Map<String, List<String[]>> buildOperationDefaultRules() {
        Map<String, List<String[]>> rules = new LinkedHashMap<>();
        rules.put("changeSignature", CHANGE_SIGNATURE_RULES);
        rules.put("introduceParameter", CHANGE_SIGNATURE_RULES);
        rules.put("moveStaticMember", List.<String[]>of(
                new String[] {"allowAccessWidening", "allow_access_widening", "allowAccessWidening"}));
        // moveInstanceMethod (G001): allow_access_widening reaches the gated AccessAdjustmentPlanner (instance moves
        // can require widening too), and rewrite_call_sites_default / leave_delegate_default are the documented
        // move_member defaults the planner reads as rewriteCallSites / keepDelegate (MoveInstanceMethodPlanner §G008).
        rules.put("moveInstanceMethod", List.of(
                new String[] {"allowAccessWidening", "allow_access_widening", "allowAccessWidening"},
                new String[] {"rewriteCallSites", "rewrite_call_sites_default", "rewrite_call_sites", "rewriteCallSites"},
                new String[] {"leaveDelegate", "leave_delegate_default", "leave_delegate", "leaveDelegate", "keepDelegate"},
                new String[] {"keepDelegate", "leave_delegate_default", "keep_delegate", "keepDelegate", "leaveDelegate"}));
        List<String[]> pullUp = new java.util.ArrayList<>();
        pullUp.add(new String[] {"makeAbstract", "make_abstract", "makeAbstract"});
        pullUp.add(new String[] {"leaveDelegate", "leave_delegate", "leaveDelegate", "keepDelegate"});
        pullUp.add(new String[] {"keepDelegate", "keep_delegate", "keepDelegate", "leaveDelegate"});
        pullUp.addAll(HIERARCHY_CONFIRM_RULES);
        rules.put("pullUpMember", List.copyOf(pullUp));
        List<String[]> pushDown = new java.util.ArrayList<>();
        pushDown.add(new String[] {"removeFromSource", "remove_from_source", "removeFromSource"});
        pushDown.addAll(HIERARCHY_CONFIRM_RULES);
        rules.put("pushDownMember", List.copyOf(pushDown));
        // G003: extractMethod has no configurable opt-outs in V2. allow_multiple_outputs / allow_control_flow_exits are
        // intentionally NOT mapped — multi-output and control-flow-preserving extraction are outside the V2 supported
        // surface and the planner hardwires them off, so no config key may re-enable them.
        rules.put("extractMethod", List.<String[]>of());
        rules.put("extractInterface", List.<String[]>of(
                new String[] {"replaceUsages", "replace_usages_default", "replace_usages", "replaceUsages"}));
        // G002: refuse_compound_assignments is intentionally NOT mapped. Compound assignment and increment/decrement on
        // an encapsulated field always produce structured refusals in V2; the planner hardwires the refusal, so no
        // config key may opt out of it.
        rules.put("encapsulateField", List.of(
                new String[] {"updateReferences", "update_references", "updateReferences"},
                new String[] {"rewriteInternalUsages", "rewrite_internal_usages_default", "rewrite_internal_usages", "rewriteInternalUsages"}));
        // inlineMethod (G001 miswire fix): the planner reads `deleteMethod`; the design key
        // delete_inlined_method_default must therefore land on deleteMethod, NOT the never-read deleteInlinedMethod.
        rules.put("inlineMethod", List.of(
                new String[] {"maxCallSites", "max_call_sites", "maxCallSites"},
                new String[] {"deleteMethod", "delete_inlined_method_default", "delete_inlined_method", "deleteMethod", "deleteInlinedMethod"}));
        return Map.copyOf(rules);
    }

    private Map<String, Object> effectiveConfigurationMap() {
        if (configuration == null || configuration.isBlank() || "default".equals(configuration.trim())) {
            return Map.of();
        }
        try {
            Map<String, Object> config = new LinkedHashMap<>(Json.parseObject(configuration));
            expandNestedV2Config(config);
            return config;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /**
     * The non-Java rewrite policy (module-info/resource/reflective handling, §5.4/§5.5) for the v3 package planners,
     * resolved from {@code java_refactor.v3.packages}/{@code java_refactor.v3.resources}. An absent/foreign config
     * yields {@link io.serena.javarefactor.v3.packages.PackageRewritePolicy#defaults()}.
     */
    private io.serena.javarefactor.v3.packages.PackageRewritePolicy packageRewritePolicy() {
        return io.serena.javarefactor.v3.packages.PackageRewritePolicy.fromConfig(effectiveConfigurationMap());
    }

    private static Map<?, ?> operationConfig(Map<String, Object> config, String operation) {
        return switch (operation) {
            case "changeSignature", "introduceParameter" -> mapValue(config, "change_signature", "changeSignature");
            case "moveStaticMember", "moveInstanceMethod" -> mapValue(config, "move_member", "moveMember");
            case "pullUpMember", "pushDownMember" -> mapValue(config, "hierarchy");
            case "extractMethod" -> mapValue(config, "extract_method", "extractMethod");
            case "extractInterface" -> mapValue(config, "extract_interface", "extractInterface");
            case "encapsulateField" -> mapValue(config, "encapsulate_field", "encapsulateField");
            case "inlineMethod" -> mapValue(config, "inline_method", "inlineMethod");
            case "introduceField" -> mapValue(config, "introduce_field", "introduceField");
            case "renamePackage" -> mapValue(config, "rename_package", "renamePackage");
            case "movePackage" -> mapValue(config, "move_package", "movePackage");
            case "moveSourceRoot" -> mapValue(config, "move_source_root", "moveSourceRoot");
            default -> Map.of();
        };
    }

    // V2 operations subject to the java_refactor.v2.enabled gate and beta capability readiness. V1 stable ops
    // (semanticRename/safeDelete/moveTopLevelType/inlineLocalVariable/inlineConstant) are intentionally excluded.
    private static final java.util.Set<String> V2_OPERATIONS = java.util.Set.of(
            "inlineMethod", "changeSignature", "introduceParameter", "moveStaticMember", "moveInstanceMethod",
            "pullUpMember", "pushDownMember", "extractMethod", "extractInterface", "introduceField", "encapsulateField",
            "renamePackage", "movePackage", "moveSourceRoot");

    private static boolean isV2Operation(String operation) {
        return V2_OPERATIONS.contains(operation);
    }

    // Edit serialization formats the sidecar can produce for a session edit. The param is validated/echoed so callers
    // get a deterministic contract and unknown formats are refused.
    //
    // "workspaceEdit" is the historical name. "serenaWorkspaceEdit" (HB-02) is the first-class, self-describing name for
    // exactly the same shape the Serena transactional applier consumes: per-file changes carrying
    // {path, oldSha256, edits[]} preconditions; fileOperations carrying {kind, oldPath/newPath/path, oldSha256, content};
    // a top-level preconditions[]; and derived stats. When this format is requested, the emitted edit object is tagged
    // with an "editFormat" discriminator so the payload is self-identifying for the applier.
    private static final String DEFAULT_EDIT_FORMAT = "workspaceEdit";
    private static final String SERENA_WORKSPACE_EDIT_FORMAT = "serenaWorkspaceEdit";
    private static final java.util.Set<String> SUPPORTED_EDIT_FORMATS =
            java.util.Set.of(DEFAULT_EDIT_FORMAT, SERENA_WORKSPACE_EDIT_FORMAT);

    /**
     * Whether a V2 operation is enabled by the effective configuration. An operation is disabled when the global
     * {@code java_refactor.v2.enabled} flag is false OR the operation's own config section sets {@code enabled: false}.
     * Default is enabled (these ops ship as beta), so an unset flag never disables.
     */
    private boolean operationEnabled(String operation) {
        Map<String, Object> config = effectiveConfigurationMap();
        if (config.isEmpty()) {
            return true;
        }
        if (config.containsKey("enabled") && !boolValue(config.get("enabled"), true)) {
            return false;
        }
        Map<?, ?> operationConfig = operationConfig(config, operation);
        if (operationConfig.containsKey("enabled") && !boolValue(operationConfig.get("enabled"), true)) {
            return false;
        }
        return true;
    }

    /** Structured refusal for a config-disabled operation, matching the canonical refusal envelope shape. */
    private String operationDisabledRefusalJson(String operation) {
        return refusalJson("operation_disabled",
                "Java refactor operation '" + operation + "' is disabled by configuration "
                        + "(java_refactor.v2.enabled or the operation's enabled flag is false).");
    }

    // ── B14: V3 dispatch capability gate (refactor-feature-plan-V3.md §20) ────────────────────────────────────────────
    // The dedicated V3 JSON-RPC methods (transformation.*, deletion.*, classRefactor.*, conversions.*, inlineRefactor.*,
    // recipes.*, resources.*, frameworks.*, impact.facts) reach their planners directly and therefore bypass the
    // preview/apply operationEnabled() gate. Without this gate, a project that disables a V3 op (or all of V3) via
    // java_refactor.v3 config would still have the op run. Each method maps to its §20 config section + per-op enable
    // flag; the global java_refactor.v3.enabled flag overrides every section.

    /** A V3 method's config gate: the v3 sub-section and the per-op boolean flag (null -> the section's own "enabled"). */
    private record V3Gate(String section, String flag) {}

    private static final Map<String, V3Gate> V3_DISPATCH_GATES = Map.ofEntries(
            Map.entry("transformation.createWorkspace", new V3Gate("transformations", null)),
            Map.entry("transformation.preview", new V3Gate("transformations", null)),
            Map.entry("transformation.apply", new V3Gate("transformations", null)),
            Map.entry("transformation.cancel", new V3Gate("transformations", null)),
            Map.entry("transformation.list", new V3Gate("transformations", null)),
            Map.entry("transformation.report", new V3Gate("transformations", null)),
            Map.entry("deletion.propagateSafeDelete", new V3Gate("deletion", "propagate_enabled")),
            Map.entry("deletion.findDeadCode", new V3Gate("deletion", null)),
            Map.entry("classRefactor.extractClass", new V3Gate("class_refactors", "extract_class_enabled")),
            Map.entry("classRefactor.extractSuperclass", new V3Gate("class_refactors", "extract_superclass_enabled")),
            Map.entry("classRefactor.replaceInheritanceWithDelegation",
                    new V3Gate("class_refactors", "replace_inheritance_with_delegation_enabled")),
            Map.entry("conversions.anonymousToLambda", new V3Gate("conversions", "anonymous_to_lambda_enabled")),
            Map.entry("conversions.lambdaToMethodReference",
                    new V3Gate("conversions", "lambda_to_method_reference_enabled")),
            Map.entry("inlineRefactor.deepInlineMethod", new V3Gate("inline", "deep_inline_enabled")),
            Map.entry("recipes.scanMigrationOpportunities", new V3Gate("recipes", null)),
            Map.entry("recipes.applyRecipe", new V3Gate("recipes", null)),
            Map.entry("resources.findReferences", new V3Gate("resources", null)),
            Map.entry("resources.planEdits", new V3Gate("resources", null)),
            Map.entry("frameworks.detect", new V3Gate("frameworks", null)),
            Map.entry("frameworks.findReferences", new V3Gate("frameworks", null)),
            Map.entry("frameworks.participate", new V3Gate("frameworks", null)),
            Map.entry("graph.build", new V3Gate("transformations", null)),
            Map.entry("impact.facts", new V3Gate(null, null)));

    /**
     * Returns a canonical {@code operation_disabled} refusal when {@code method} is a dispatched V3 op disabled by the
     * effective {@code java_refactor.v3} configuration, or {@code null} when the op may proceed. Default/empty/foreign
     * config and an absent v3 block leave every op enabled (these ship enabled by default), matching the V2 gate's
     * "unset never disables" semantics. The global {@code v3.enabled} flag overrides every section; a per-op flag (or the
     * section's own {@code enabled}) disables just that op.
     */
    private String v3DispatchGateRefusal(String method) {
        String configPath = v3DisablingFlag(method);
        return configPath == null ? null : v3DisabledRefusalJson(method, configPath);
    }

    /** True when {@code operation} is one of the dedicated V3 dispatch methods (those in {@link #V3_DISPATCH_GATES}). */
    private static boolean isV3DispatchOperation(String operation) {
        return V3_DISPATCH_GATES.containsKey(operation);
    }

    /** True when a dispatched V3 op is not disabled by the effective {@code java_refactor.v3} configuration. */
    private boolean v3OperationEnabled(String method) {
        return v3DisablingFlag(method) == null;
    }

    /**
     * Returns the dotted config path of the flag that disables a dispatched V3 op (e.g.
     * {@code java_refactor.v3.enabled} or {@code java_refactor.v3.deletion.propagate_enabled}), or {@code null} when the
     * op may proceed. Default/empty/foreign config and an absent v3 block leave every op enabled (these ship enabled by
     * default), matching the V2 gate's "unset never disables" semantics. The global {@code v3.enabled} flag overrides
     * every section; a per-op flag (or the section's own {@code enabled}) disables just that op.
     */
    private String v3DisablingFlag(String method) {
        V3Gate gate = V3_DISPATCH_GATES.get(method);
        if (gate == null) {
            return null;
        }
        Map<String, Object> config = effectiveConfigurationMap();
        if (config.isEmpty()) {
            return null;
        }
        Map<?, ?> v3 = v3ConfigSection(config);
        if (v3.isEmpty()) {
            return null;
        }
        if (v3.containsKey("enabled") && !boolValue(v3.get("enabled"), true)) {
            return "java_refactor.v3.enabled";
        }
        if (gate.section() != null) {
            Object sectionValue = v3.get(gate.section());
            Map<?, ?> section = sectionValue instanceof Map<?, ?> map ? map : Map.of();
            String flag = gate.flag() != null ? gate.flag() : "enabled";
            if (section.containsKey(flag) && !boolValue(section.get(flag), true)) {
                return "java_refactor.v3." + gate.section() + "." + flag;
            }
        }
        return null;
    }

    /** Navigates the effective config to the {@code java_refactor.v3} block (snake_case or camelCase), or empty. */
    private static Map<?, ?> v3ConfigSection(Map<String, Object> config) {
        Object javaRefactor = config.get("java_refactor");
        if (!(javaRefactor instanceof Map<?, ?>)) {
            javaRefactor = config.get("javaRefactor");
        }
        if (!(javaRefactor instanceof Map<?, ?> javaRefactorMap)) {
            return Map.of();
        }
        Object v3 = javaRefactorMap.get("v3");
        return v3 instanceof Map<?, ?> v3Map ? v3Map : Map.of();
    }

    private String v3DisabledRefusalJson(String method, String configPath) {
        return refusalJson("operation_disabled",
                "Java refactor V3 operation '" + method + "' is disabled by configuration (" + configPath + " is false).");
    }

    /**
     * G014: rewrite an accepted V2 direct-apply result so it cannot falsely claim files were mutated. The sidecar only
     * computes the workspace edit for V2 ops; Python's transactional applier performs the actual write. We therefore set
     * {@code applied:false}, {@code mode:"preview"}, and add {@code requiresClientApply:true} while preserving the
     * workspaceEdit and every other field. Non-accepted results (refusals already carry applied:false) pass through
     * unchanged, as do results we cannot parse.
     */
    private String downgradeV2DirectApply(String operationJson) {
        if (!accepted(operationJson)) {
            return operationJson;
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>(Json.parseObject(operationJson));
            result.put("applied", Boolean.FALSE);
            result.put("mode", "preview");
            result.put("requiresClientApply", Boolean.TRUE);
            return Json.write(result);
        } catch (RuntimeException e) {
            return operationJson;
        }
    }

    private static Map<?, ?> mapValue(Map<?, ?> fields, String... keys) {
        for (String key : keys) {
            Object value = fields.get(key);
            if (value instanceof Map<?, ?> map) {
                return map;
            }
        }
        return Map.of();
    }

    private static void copyDefault(Map<String, Object> target, Map<?, ?> source, String targetKey, String... sourceKeys) {
        if (target.containsKey(targetKey)) {
            return;
        }
        for (String sourceKey : sourceKeys) {
            if (source.containsKey(sourceKey)) {
                target.put(targetKey, source.get(sourceKey));
                return;
            }
        }
    }

    private String handleRequest(String id, String method, Map<String, Object> fields) {
        // G001: flatten the V2 nested {operation,target,arguments} request shape onto the flat field map BEFORE defaults,
        // source-policy checks, preview/apply dispatch, and session creation. A flat request passes through untouched.
        String v2NormalizationRefusal = normalizeV2SessionRequest(fields);
        if (v2NormalizationRefusal != null) {
            return response(id, v2NormalizationRefusal);
        }
        // B14: capability-gate every dedicated V3 JSON-RPC method against java_refactor.v3 config before dispatch, so a
        // disabled-by-config V3 op cannot run merely because it bypasses the preview/apply operationEnabled() gate.
        String v3GateRefusal = v3DispatchGateRefusal(method);
        if (v3GateRefusal != null) {
            return response(id, v3GateRefusal);
        }
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
            case "capabilities":
                return response(id, capabilitiesResponseJson());
            case "preview":
            case "apply": {
                String operation = str(fields, "operation", "unknown");
                if (isV2Operation(operation) && !operationEnabled(operation)) {
                    return response(id, operationDisabledRefusalJson(operation));
                }
                fields = applyConfiguredDefaults(operation, fields);
                String policyRefusal = policyRefusalJson(operation, fields);
                if (policyRefusal != null) {
                    return response(id, policyRefusal);
                }
                boolean applyRequested = "apply".equals(method);
                String operationJson = operationJson(operation, fields, applyRequested);
                if (operationJson != null) {
                    // FIX 2: validate the ACTUAL apply-shaped result that will be returned — not a separate apply=false
                    // recomputation — so the object proven to compile is the same object we hand back. The validated
                    // (and only the validated) JSON is then downgraded for the client-apply contract.
                    if (accepted(operationJson)) {
                        JavaProjectModel model = discoverSemanticPlanningModel(fields);
                        operationJson = previewDiagnosticValidator.validate(operation, operationJson, model, applyRequested);
                        // G015: uniform post-plan generated/Lombok gate. The pre-dispatch policyRefusalJson above only
                        // inspected relativePath/targetRelativePath; re-apply the policy to EVERY file the accepted plan
                        // would write (changedFiles + created files), so a multi-file V2 op's other edit targets cannot
                        // escape the generated/Lombok refusal.
                        if (isV2Operation(operation) && accepted(operationJson)) {
                            String postPlanRefusal = postPlanPolicyRefusalJson(model,
                                    java.nio.file.Path.of(projectRoot),
                                    operation,
                                    bool(fields, "allowGenerated", generatedSourcesEditAllowed()),
                                    bool(fields, "allowLombok", false),
                                    operationJson);
                            if (postPlanRefusal != null) {
                                return response(id, postPlanRefusal);
                            }
                        }
                    }
                    // HB-10: never surface an accepted V2 preview/apply whose diagnostic delta was not javac-validated.
                    String unvalidatedV2Refusal = requireValidatedV2Delta(operation, operationJson);
                    if (unvalidatedV2Refusal != null) {
                        return response(id, unvalidatedV2Refusal);
                    }
                    if (applyRequested && isV2Operation(operation) && accepted(operationJson)) {
                        // G014: the sidecar does NOT mutate files for V2 ops — Python's transactional applier does.
                        // A direct apply therefore must not claim applied:true. Downgrade the accepted (validated) apply
                        // result to a client-apply contract: applied:false + requiresClientApply:true, carrying the
                        // workspaceEdit. A refused validation result is returned as-is (never downgraded into an apply).
                        operationJson = downgradeV2DirectApply(operationJson);
                    }
                    // B1/B2: the package-relocation ops (renamePackage, movePackage, moveSourceRoot) can each emit a §6.3
                    // build-file rewrite (and, for resources, resource edits), so they must carry the canonical §1.1
                    // impact + §14.3 risk envelope like every other V3 op that touches non-Java files. The generic
                    // operation path does not augment, so apply it here; augment is a no-op when impact/risk are already
                    // present or the result is not an accepted workspace edit. Augmenting also makes a planner result with
                    // resourceScanIncomplete:true (or a non-empty analysisIncomplete riskFact) flow through classifyRisk
                    // to "needs_review", so the Python apply gate blocks SAFE auto-apply for an incomplete-scan package op.
                    if ("renamePackage".equals(operation)
                            || "movePackage".equals(operation)
                            || "moveSourceRoot".equals(operation)) {
                        operationJson = CanonicalEnvelope.augment(operationJson);
                    }
                    return response(id, operationJson);
                }
                return response(id, refactorResultJson(applyRequested, operation));
            }
            case "createSession":
            case "refactor.createSession":
                return response(id, createSessionJson(fields));
            case "getSessionEdit":
            case "refactor.getSessionEdit":
                return response(id, sessionEditJson(fields, false));
            case "applySession":
            case "refactor.applySession":
                return response(id, sessionEditJson(fields, true));
            case "ackSessionApply":
            case "refactor.ackSessionApply":
                return response(id, ackSessionApplyJson(fields));
            case "cancelSession":
            case "refactor.cancelSession":
                return response(id, cancelSessionJson(fields));
            case "validateEdit":
                return response(id, validateEditJson(fields));
            case "resolveTarget":
                return response(id, semanticAnalysisJson(fields, false));
            case "scanReferences":
                return response(id, semanticAnalysisJson(fields, true));
            case "transformation.createWorkspace":
                return response(id, CanonicalEnvelope.augment(transformationCreateWorkspaceJson(fields)));
            case "transformation.preview":
                return response(id, CanonicalEnvelope.augment(transformationWorkspaceCall(fields, "preview")));
            case "transformation.apply":
                return response(id, CanonicalEnvelope.augment(transformationApplyJson(fields)));
            case "transformation.cancel":
                return response(id, transformationWorkspaceCall(fields, "cancel"));
            case "transformation.list":
                return response(id, transformationListJson());
            case "transformation.report":
                return response(id, CanonicalEnvelope.augmentReadOnly(transformationWorkspaceCall(fields, "report")));
            case "deletion.propagateSafeDelete":
                return response(id, CanonicalEnvelope.augment(propagateSafeDeleteJson(fields)));
            case "deletion.findDeadCode":
                return response(id, CanonicalEnvelope.augmentReadOnly(findDeadCodeJson(fields)));
            case "classRefactor.extractClass":
                return response(id, CanonicalEnvelope.augment(extractClassJson(fields)));
            case "classRefactor.extractSuperclass":
                return response(id, CanonicalEnvelope.augment(extractSuperclassJson(fields)));
            case "classRefactor.replaceInheritanceWithDelegation":
                return response(id, CanonicalEnvelope.augment(replaceInheritanceWithDelegationJson(fields)));
            case "conversions.anonymousToLambda":
                return response(id, CanonicalEnvelope.augment(anonymousToLambdaJson(fields)));
            case "conversions.lambdaToMethodReference":
                return response(id, CanonicalEnvelope.augment(lambdaToMethodReferenceJson(fields)));
            case "inlineRefactor.deepInlineMethod":
                return response(id, CanonicalEnvelope.augment(deepInlineMethodJson(fields)));
            case "recipes.scanMigrationOpportunities":
                return response(id, CanonicalEnvelope.augmentReadOnly(scanMigrationOpportunitiesJson(fields)));
            case "recipes.applyRecipe":
                return response(id, CanonicalEnvelope.augment(applyRecipeJson(fields)));
            case "resources.findReferences":
                return response(id, findResourceReferencesJson(fields));
            case "resources.planEdits":
                return response(id, planResourceEditsJson(fields));
            case "frameworks.detect":
                return response(id, detectFrameworksJson(fields));
            case "frameworks.findReferences":
                return response(id, findFrameworkReferencesJson(fields));
            case "frameworks.participate":
                return response(id, participateFrameworksJson(fields));
            case "graph.build":
                return response(id, CanonicalEnvelope.augmentReadOnly(graphBuildJson(fields)));
            case "graph.buildCount":
                // Diagnostic-only observability hook for the F-GRAPH caching/invalidation test: returns how many times
                // the unified TransformationGraph has been materialized (advances on a cache MISS, stays flat on a HIT).
                // Reads a static counter; carries no model state and mutates nothing.
                return response(id, "{\"accepted\":true,\"builds\":"
                        + io.serena.javarefactor.v3.graph.GraphInvalidation.buildCount() + "}");
            case "graph.incrementalUpdateCount":
                // Diagnostic-only observability hook for the R05 incremental-maintenance test: returns how many times a
                // new revision was served by an INCREMENTAL update (re-extracting only the affected files) rather than a
                // full rebuild. Advances on an incremental cache miss; stays flat on a HIT or a full rebuild. Reads a
                // static counter; carries no model state and mutates nothing.
                return response(id, "{\"accepted\":true,\"incrementalUpdates\":"
                        + io.serena.javarefactor.v3.graph.GraphInvalidation.incrementalUpdateCount() + "}");
            case "impact.facts":
                return response(id, impactFactsJson(fields));
            case "reachabilityGraph.buildCount":
                // Diagnostic-only observability hook for the G-CACHE hit/miss test: returns how many times the
                // ReachabilityGraph has been walked (advances on a cache MISS, stays flat on a HIT). Reads a static
                // counter; carries no model state and mutates nothing.
                return response(id, "{\"accepted\":true,\"builds\":"
                        + io.serena.javarefactor.compiler.ReachabilityGraph.buildInvocationCount() + "}");
            case "shutdown":
                shutdownRequested = true;
                FileManagerPool.INSTANCE.invalidate();
                io.serena.javarefactor.compiler.ReachabilityGraphCache.INSTANCE.invalidate();
                io.serena.javarefactor.v3.graph.GraphInvalidation.INSTANCE.invalidate();
                return response(id, "{\"shutdown\":true}");
            default:
                return error(id, "unsupported method: " + method);
        }
    }

    /** Returns the planner JSON for a supported operation, or null if the operation is not implemented. */
    private String operationJson(String operation, Map<String, Object> fields, boolean apply) {
        return switch (operation) {
            // "rename" is the V2 plan's stable alias of semanticRename (advertised in the capability registry); it must
            // dispatch to the same handler so the advertised capability is truthful rather than an empty over-claim.
            case "semanticRename", "rename" -> semanticRenameJson(fields, apply);
            case "safeDelete" -> safeDeleteJson(fields, apply);
            case "moveTopLevelType" -> moveTopLevelTypeJson(fields, apply);
            case "inlineLocalVariable", "inlineConstant" -> inlineVariableJson(fields, operation, apply);
            case "inlineMethod" -> inlineMethodJson(fields, apply);
            case "changeSignature" -> changeSignatureJson(fields, apply);
            case "introduceParameter" -> introduceParameterJson(fields, apply);
            case "moveStaticMember" -> moveStaticMemberJson(fields, apply);
            case "moveInstanceMethod" -> moveInstanceMethodJson(fields, apply);
            case "pullUpMember" -> pullUpMemberJson(fields, apply);
            case "pushDownMember" -> pushDownMemberJson(fields, apply);
            case "extractMethod" -> extractMethodJson(fields, apply);
            case "extractInterface" -> extractInterfaceJson(fields, apply);
            case "introduceField" -> introduceFieldJson(fields, apply);
            case "encapsulateField" -> encapsulateFieldJson(fields, apply);
            case "renamePackage" -> renamePackageJson(fields, apply);
            case "movePackage" -> movePackageJson(fields, apply);
            case "moveSourceRoot" -> moveSourceRootJson(fields, apply);
            default -> null;
        };
    }

    /** Returns whether the effective initialize config allows editing generated sources by default. */
    private boolean generatedSourcesEditAllowed() {
        try {
            Map<String, Object> config = Json.parseObject(configuration);
            Object nested = config.get("generated_sources");
            if (nested instanceof Map<?, ?> map && map.get("edit") != null) {
                return boolValue(map.get("edit"), false);
            }
            Object camelNested = config.get("generatedSources");
            if (camelNested instanceof Map<?, ?> map && map.get("edit") != null) {
                return boolValue(map.get("edit"), false);
            }
            if (config.containsKey("generated_sources.edit")) {
                return boolValue(config.get("generated_sources.edit"), false);
            }
            if (config.containsKey("generatedSourcesEdit")) {
                return boolValue(config.get("generatedSourcesEdit"), false);
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean boolValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        if (value instanceof String string) {
            return Boolean.parseBoolean(string);
        }
        return defaultValue;
    }

    private String policyRefusalJson(String operation, Map<String, Object> fields) {
        if (projectRoot == null) {
            return null;
        }
        boolean allowGenerated = bool(fields, "allowGenerated", generatedSourcesEditAllowed());
        boolean allowLombok = bool(fields, "allowLombok", false);
        java.nio.file.Path root = java.nio.file.Path.of(projectRoot).toAbsolutePath().normalize();
        List<String> pathKeys = List.of("relativePath", "targetRelativePath");
        JavaProjectModel model = null;
        for (String key : pathKeys) {
            String relativePath = str(fields, key, "");
            if (relativePath.isBlank()) {
                continue;
            }
            try {
                java.nio.file.Path file = ProjectPathResolver.resolveProjectRelative(root, relativePath, key);
                String normalized = relativePath.replace('\\', '/');
                // AUTHORITY: the build model's extracted generated source roots. A target under such a root is a
                // non-editable target (refusal code "non_editable_target"), matching the planner-level editability gate,
                // regardless of how the path or file contents are named. This takes precedence over the path/source-text
                // heuristics below so a build-reported generated root wins even when its path looks ordinary.
                if (!allowGenerated) {
                    if (model == null) {
                        model = discoverModel();
                    }
                    if (GeneratedSourcePolicy.isUnderGeneratedRoot(model.generatedSourceRoots(), file)) {
                        return nonEditableGeneratedRefusalJson(operation,
                                "Refusing edit: operation would modify generated source under a build-model generated "
                                        + "source root (" + normalized + ").");
                    }
                }
                // FALLBACK: path-naming heuristic, used only when the build model does not surface the file as generated.
                if (!allowGenerated && GeneratedSourcePolicy.matchesGeneratedPathHeuristic(relativePath)) {
                    return generatedSourcePolicyRefusalJson(operation, "Generated Java sources are refused unless allowGenerated is true.");
                }
                if (java.nio.file.Files.exists(file)) {
                    // Read with the project's configured source charset (defaulting to UTF-8), not a hardcoded UTF-8:
                    // a non-UTF-8 source (e.g. ISO-8859-1) would otherwise throw MalformedInputException and turn this
                    // advisory Lombok/@Generated text check into a spurious source_policy_check_failed refusal.
                    if (model == null) {
                        model = discoverModel();
                    }
                    String source = java.nio.file.Files.readString(file, SemanticIndex.charsetOf(model));
                    // FALLBACK: Lombok-managed source-text heuristic.
                    if (!allowLombok && GeneratedSourcePolicy.matchesLombokSourceText(source)) {
                        return refusalJson("lombok_managed_source_refused", "Lombok-managed Java sources are refused unless allowLombok is true.");
                    }
                    // FALLBACK: @Generated source-text heuristic.
                    if (!allowGenerated && GeneratedSourcePolicy.matchesGeneratedSourceText(source)) {
                        return generatedSourcePolicyRefusalJson(operation, "@Generated Java sources are refused unless allowGenerated is true.");
                    }
                }
            } catch (ProjectPathResolver.Violation violation) {
                if ("targetRelativePath".equals(key)) {
                    return refusalJson(violation.code(), violation.getMessage());
                }
                return refusalJson("source_policy_check_failed", "Java source policy check failed: " + violation.getClass().getSimpleName());
            } catch (Exception exception) {
                return refusalJson("source_policy_check_failed", "Java source policy check failed: " + exception.getClass().getSimpleName());
            }
        }
        return null;
    }

    /**
     * Authoritative refusal for a target under a build-model generated source root. Uses the {@code non_editable_target}
     * code so the protocol-level gate agrees with the planner-level editability gate ({@code targetOriginRefusal} /
     * {@code detectNonEditableFiles}), which already classifies build-model generated roots as non-editable targets.
     */
    private String nonEditableGeneratedRefusalJson(String operation, String message) {
        if ("safeDelete".equals(operation)) {
            return safeDeletePolicyRefusalJson("non_editable_target", message);
        }
        return refusalJson("non_editable_target", message);
    }

    /**
     * Fallback refusal for the path-/source-text heuristics (a file the build model does not surface as generated). Keeps
     * the {@code generated_source_refused} code; safe delete still reports {@code non_editable_target} for envelope shape.
     */
    private String generatedSourcePolicyRefusalJson(String operation, String message) {
        if ("safeDelete".equals(operation)) {
            return safeDeletePolicyRefusalJson("non_editable_target", message);
        }
        return refusalJson("generated_source_refused", message);
    }

    private String safeDeletePolicyRefusalJson(String code, String message) {
        return "{\"accepted\":false,\"canDelete\":false,\"reason\":" + JsonUtil.quote(message)
                + ",\"references\":[]"
                + ",\"refusal\":{\"code\":" + JsonUtil.quote(code)
                + ",\"message\":" + JsonUtil.quote(message) + "}}";
    }

    /**
     * G015 (hard blocker 16): the uniform post-plan generated/Lombok gate. The pre-dispatch {@link #policyRefusalJson}
     * only inspects the {@code relativePath}/{@code targetRelativePath} request fields, so a multi-file V2 operation's
     * other edit targets — the source file of a move, the supertype and subtype files of a pull-up/push-down, the newly
     * created interface file and every usage site of an extract-interface — currently escape the policy. This gate closes
     * that gap by re-applying {@link GeneratedSourcePolicy} (build-model generated root, path heuristic, {@code @Generated}
     * source text, Lombok source text) to EVERY file the planned preview would write: every entry of the result's
     * top-level {@code changedFiles} (edited files plus created/renamed-to targets) and the {@code content} of every
     * {@code create} file operation (so a file that does not yet exist on disk is still gated by its planned text).
     *
     * <p>Refusals carry the established reason codes: {@code non_editable_target} for a target under a build-model
     * generated root (authority), {@code generated_source_refused} for the path-/@Generated-text fallbacks, and
     * {@code lombok_managed_source_refused} for Lombok-managed sources. Returns {@code null} when no target is refused
     * (or when {@code allowGenerated}/{@code allowLombok} opt past the corresponding check). It is invoked on an accepted,
     * already-validated preview only, so {@code previewJson} is the authoritative apply-shaped edit; the pre-dispatch
     * check is retained as defense in depth.
     *
     * <p>Intentionally {@code static} and dependency-injected (model + root + flags) so the operation-level refusal matrix
     * can exercise it directly for multi-file edit targets without driving a full javac plan per cell.
     */
    static String postPlanPolicyRefusalJson(
            JavaProjectModel model,
            java.nio.file.Path projectRoot,
            String operation,
            boolean allowGenerated,
            boolean allowLombok,
            String previewJson) {
        if (model == null || projectRoot == null || previewJson == null) {
            return null;
        }
        if (allowGenerated && allowLombok) {
            return null;
        }
        Map<String, Object> result;
        try {
            result = Json.parseObject(previewJson);
        } catch (RuntimeException parseFailure) {
            // A non-object/refusal preview never reaches this gate (callers guard on accepted()); be conservative and
            // do not invent a refusal from an unparseable body.
            return null;
        }
        java.nio.file.Path root = projectRoot.toAbsolutePath().normalize();

        // The text planned for each create target, keyed by its project-relative path, so a not-yet-existing created file
        // can be gated by its @Generated/Lombok content rather than only its path.
        Map<String, String> createdContent = new LinkedHashMap<>();
        Object workspaceEdit = result.get("workspaceEdit");
        if (workspaceEdit instanceof Map<?, ?> we && we.get("fileOperations") instanceof List<?> fileOps) {
            for (Object op : fileOps) {
                if (op instanceof Map<?, ?> fileOp && "create".equals(fileOp.get("kind"))
                        && fileOp.get("path") instanceof String createPath) {
                    Object content = fileOp.get("content");
                    createdContent.put(createPath, content instanceof String text ? text : "");
                }
            }
        }

        // Every file whose content the edit writes: edited files plus created/renamed-to targets (ResponseBuilder's
        // top-level changedFiles), unioned with the create-operation targets so a created file is gated even if a planner
        // omitted it from changedFiles.
        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
        if (result.get("changedFiles") instanceof List<?> changedFiles) {
            for (Object entry : changedFiles) {
                if (entry instanceof String relativePath && !relativePath.isBlank()) {
                    targets.add(relativePath);
                }
            }
        }
        targets.addAll(createdContent.keySet());

        for (String relativePath : targets) {
            String refusal = postPlanRefusalForTarget(
                    model, root, operation, allowGenerated, allowLombok, relativePath, createdContent.get(relativePath));
            if (refusal != null) {
                return refusal;
            }
        }
        return null;
    }

    /**
     * Applies {@link GeneratedSourcePolicy} to a single edit target. {@code createdContent} is the planned text when the
     * target is a newly created file (so a file not yet on disk is still gated by its content); it is {@code null} for an
     * edit to an existing file, which is read from disk with the project's configured charset. Returns the refusal JSON or
     * {@code null} when the target is allowed.
     */
    private static String postPlanRefusalForTarget(
            JavaProjectModel model,
            java.nio.file.Path root,
            String operation,
            boolean allowGenerated,
            boolean allowLombok,
            String relativePath,
            String createdContent) {
        java.nio.file.Path file;
        try {
            file = ProjectPathResolver.resolveProjectRelative(root, relativePath, "changedFiles");
        } catch (ProjectPathResolver.Violation violation) {
            // A target that escapes the project root is itself a refusable condition; surface it rather than silently
            // passing the gate.
            return staticRefusalJson(operation, violation.code(), violation.getMessage());
        }
        String normalized = relativePath.replace('\\', '/');

        // AUTHORITY: a target under a build-model generated source root is a non-editable target, regardless of path/text.
        if (!allowGenerated && GeneratedSourcePolicy.isUnderGeneratedRoot(model.generatedSourceRoots(), file)) {
            return staticRefusalJson(operation, "non_editable_target",
                    "Refusing edit: operation would modify generated source under a build-model generated source root ("
                            + normalized + ").");
        }
        // FALLBACK: path-naming heuristic for generated output the build model did not surface (covers created targets
        // placed under a conventional generated layout too).
        if (!allowGenerated && GeneratedSourcePolicy.matchesGeneratedPathHeuristic(relativePath)) {
            return staticRefusalJson(operation, "generated_source_refused",
                    "Generated Java sources are refused unless allowGenerated is true (" + normalized + ").");
        }

        // Source-text checks: planned text for a created file, otherwise the on-disk source of the edited file.
        String source = createdContent;
        if (source == null) {
            try {
                if (java.nio.file.Files.exists(file)) {
                    source = java.nio.file.Files.readString(file, SemanticIndex.charsetOf(model));
                }
            } catch (Exception readFailure) {
                return staticRefusalJson(operation, "source_policy_check_failed",
                        "Java source policy check failed: " + readFailure.getClass().getSimpleName());
            }
        }
        if (source != null) {
            // FALLBACK: Lombok-managed source-text heuristic.
            if (!allowLombok && GeneratedSourcePolicy.matchesLombokSourceText(source)) {
                return staticRefusalJson(operation, "lombok_managed_source_refused",
                        "Lombok-managed Java sources are refused unless allowLombok is true (" + normalized + ").");
            }
            // FALLBACK: @Generated source-text heuristic.
            if (!allowGenerated && GeneratedSourcePolicy.matchesGeneratedSourceText(source)) {
                return staticRefusalJson(operation, "generated_source_refused",
                        "@Generated Java sources are refused unless allowGenerated is true (" + normalized + ").");
            }
        }
        return null;
    }

    /**
     * Static refusal-JSON builder for the post-plan gate, mirroring {@link #refusalJson}/{@link #safeDeletePolicyRefusalJson}
     * so the uniform gate is self-contained (and directly unit-testable). Safe delete keeps its dedicated envelope shape;
     * every other operation uses the canonical refusal envelope.
     */
    private static String staticRefusalJson(String operation, String code, String message) {
        if ("safeDelete".equals(operation)) {
            return "{\"accepted\":false,\"canDelete\":false,\"reason\":" + JsonUtil.quote(message)
                    + ",\"references\":[]"
                    + ",\"refusal\":{\"code\":" + JsonUtil.quote(code)
                    + ",\"message\":" + JsonUtil.quote(message) + "}}";
        }
        return "{"
                + "\"accepted\":false,"
                + "\"refusal\":{\"code\":" + JsonUtil.quote(code) + ",\"message\":" + JsonUtil.quote(message) + "},"
                + "\"diagnostics\":[],"
                + "\"warnings\":[],"
                + "\"stats\":{}"
                + "}";
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
                + "\"capabilities\":" + capabilitiesJson() + ","
                + "\"liveSessions\":" + sessionManager.size() + ","
                + "\"modelCacheSource\":" + JsonUtil.quote(projectModel == null ? null : lastModelCacheSource) + ","
                + "\"projectModel\":" + (projectModel == null ? "null" : projectModel.toJson())
                + "}";
    }

    private String capabilitiesResponseJson() {
        return "{"
                + "\"capabilities\":" + capabilitiesJson() + ","
                // G003: sibling metadata map preserving the richer per-operation {level,status,description}.
                + "\"capabilityDetails\":" + capabilityDetailsJson() + ","
                // G003: top-level javac contract object (runtimeJdk + supportsPreview) per the V2 plan, alongside the
                // existing nested runtime descriptor kept for current consumers.
                + "\"javac\":" + capabilityJavacContractJson() + ","
                + "\"runtime\":" + capabilityRuntimeJson()
                + "}";
    }

    /**
     * Top-level javac contract object mandated by the V2 plan: the JDK runtime version backing the compiler-driven
     * engine and whether javac preview features are enabled for the in-process compiler.
     */
    private String capabilityJavacContractJson() {
        boolean compilerAvailable = javax.tools.ToolProvider.getSystemJavaCompiler() != null;
        return "{"
                + "\"runtimeJdk\":" + JsonUtil.quote(Runtime.version().toString()) + ","
                // A reachable system compiler (a JDK, not a bare JRE) can compile with --enable-preview for its release.
                + "\"supportsPreview\":" + compilerAvailable
                + "}";
    }

    private String capabilityRuntimeJson() {
        String runtimeStatus = initialized ? "running" : "uninitialized";
        return "{"
                + "\"status\":" + JsonUtil.quote(runtimeStatus) + ","
                + "\"jdk\":" + JsonUtil.quote(Runtime.version().toString()) + ","
                + "\"javaHome\":" + JsonUtil.quote(javaHome) + ","
                + "\"javac\":" + capabilityJavacJson() + ","
                + "\"protocolVersion\":" + JsonUtil.quote(PROTOCOL_VERSION)
                + "}";
    }

    /**
     * The in-process javac runtime descriptor for the capabilities response: whether a system Java compiler is
     * reachable (the compiler-backed engine requires a JDK, not a bare JRE) and the runtime version backing it.
     */
    private String capabilityJavacJson() {
        boolean available = javax.tools.ToolProvider.getSystemJavaCompiler() != null;
        return "{"
                + "\"available\":" + available + ","
                + "\"version\":" + JsonUtil.quote(Runtime.version().toString())
                + "}";
    }

    // Per-operation readiness registry (G001). An operation is advertised "supported" only when ready==true AND not
    // config-disabled. Operations with an OPEN V2 hard-requirement blocker MUST stay ready==false so Python (which only
    // uses status=="supported" ops) will not register/use them. A sibling task flips its op to true in one line when its
    // blocker lands. Status precedence: disabled (config) > not-ready (open blocker) > supported.
    //
    // Session-apply contract (G005): refactorSessions and every session-applied V2 op (all the beta ops below) are
    // advertised "supported" ONLY because the session apply path now satisfies the full V2 apply contract, not because
    // any gap was documented away. Specifically: the whole-session AND incremental paths surface/apply the freshly
    // recomputed + revalidated currentPlan rather than the stored create-time preview (Main#sessionEditJson,
    // #incrementalSessionEdit); incremental apply re-resolves the target and compares semantic identity, refusing
    // target_identity_changed (G003); it enforces the FULL project-revision token via ProjectRevision#mismatchFull,
    // exempting only acknowledged-committed paths (G002); and applied units are recorded ONLY on the post-commit
    // ackSessionApply path, never at edit-envelope emission (G001). If any of these guarantees regresses, the
    // session-applied ops must be removed from this set until it is restored.
    private static final java.util.Set<String> READY_OPERATIONS = java.util.Set.of(
            "semanticRename", "safeDelete", "moveTopLevelType", "inlineLocalVariable", "inlineConstant",
            "inlineMethod", "refactorSessions", "moveStaticMember", "moveInstanceMethod", "extractInterface",
            "introduceField", "encapsulateField", "changeSignature", "introduceParameter",
            "pullUpMember", "pushDownMember", "extractMethod", "rename", "renamePackage", "movePackage",
            "moveSourceRoot"
            );

    private static boolean operationReady(String operation) {
        return READY_OPERATIONS.contains(operation);
    }

    /** A single advertised capability: the operation name, its lifecycle level, and a human-readable description. */
    private record CapabilitySpec(String operation, String level, String description) {}

    // G003: single source of truth for the capability registry. {@link #capabilitiesJson} emits the plan's public
    // {op -> level} string map from this list; {@link #capabilityDetailsJson} emits the richer sibling map carrying
    // {level,status,description}. "rename" is listed as a stable alias of semanticRename per the V2 plan's sample
    // registry (Python keys off semanticRename; rename is advertised for plan parity).
    private static final List<CapabilitySpec> CAPABILITY_SPECS = List.of(
            new CapabilitySpec("semanticRename", "stable", "Rename a Java symbol with cross-file references and preview-first edits."),
            new CapabilitySpec("rename", "stable", "Rename a Java symbol with cross-file references and preview-first edits."),
            new CapabilitySpec("safeDelete", "stable", "Refuse deletion when references remain; emit safe delete plans when clear."),
            new CapabilitySpec("moveTopLevelType", "stable", "Move a top-level Java type between packages with import/reference updates."),
            new CapabilitySpec("inlineLocalVariable", "stable", "Inline an effectively-final local variable or constant; refuses unsafe initializers."),
            new CapabilitySpec("inlineConstant", "stable", "Inline a compile-time constant; non-private constants are preview-only."),
            new CapabilitySpec("inlineMethod", "beta", "Inline a private/static single-return or single-throw method with checked-exception propagation; refuses method references, evaluation-order/duplication hazards, and unmodellable bodies (no token fallback)."),
            new CapabilitySpec("refactorSessions", "beta", "Create, validate, apply, and cancel revision-guarded preview sessions with a validated before/after diagnostic delta."),
            new CapabilitySpec("changeSignature", "beta", "Rename and add/remove/reorder/retype parameters; rewrites declaration, override group, and all call sites (constructor/qualified/cross-file) with optional call-site and method-body return conversion; refuses overload ambiguity and method-reference arity changes."),
            new CapabilitySpec("introduceParameter", "beta", "Promote a selected expression to a parameter and thread it through every call site; refuses selections not provably reorder-safe."),
            new CapabilitySpec("moveStaticMember", "beta", "Relocate a static method, or a static field (compile-time constant or non-constant), to another editable type with reference rewrites and import transfer; refuses only static fields whose initialization is entangled with class-initialization order (initializer reads/writes coupled to the source or target type's static state or a source static block), erased-signature collisions, and gates visibility widening behind confirmation."),
            new CapabilitySpec("moveInstanceMethod", "beta", "Relocate an instance method onto a target parameter, target field, or an AST-resolved receiver selection (a simple navigation or a javac-proven reorder-safe pure expression), rewriting call sites or retaining a delegate; refuses side-effecting/unresolved receivers, super/synchronized/source-state/type-variable blockers, and non-delegate method references."),
            new CapabilitySpec("pullUpMember", "beta", "Transfer a member to a direct supertype (concrete/abstract/interface) or make it abstract, with import transfer; refuses collisions, incompatible overrides, source-only body dependencies, and gates public-API widening behind confirmation."),
            new CapabilitySpec("pushDownMember", "beta", "Copy (keep source) or move (remove source) a member into selected direct subtypes with import transfer; refuses target collisions and call sites that would not resolve after removal."),
            new CapabilitySpec("extractMethod", "beta", "Extract a zero-output or single-output complete-statement selection, or a complete expression, into a new method with scope-aware unique names; refuses multi-output selections, control-flow-exit (return/break/continue) selections, and non-extractable selections."),
            new CapabilitySpec("extractInterface", "beta", "Extract public instance methods into a new interface and add implements, preserving covariant/generic signatures with import transfer."),
            new CapabilitySpec("introduceField", "beta", "Extract an initializer to a private final field with javac scope-bound qualification; refuses checked-exception and non-eligible initializers per the field policy."),
            new CapabilitySpec("encapsulateField", "beta", "Generate JavaBean accessors and route direct reads/writes through them; refuses accessor collisions and always refuses compound-assignment and increment/decrement usages."),
            new CapabilitySpec("renamePackage", "beta", "Rename a package across the project: rewrite package declarations, move files to the new package directory under the same source root, and update imports and fully-qualified references; refuses target-package simple-name collisions, non-editable targets, and an unknown source package (subpackages are not renamed)."),
            new CapabilitySpec("movePackage", "beta", "Move a package and (by default) its subpackages to a target package, optionally under a different configured source root: rewrite package declarations, relocate files, and update imports and fully-qualified references; refuses destination simple-name collisions, non-editable or unknown targets, and an unknown source package."),
            new CapabilitySpec("moveSourceRoot", "beta", "Relocate Java source files from one configured source root to another while keeping their package declarations unchanged, optionally restricted to specific packages: emits file moves only, leaving fully-qualified names and imports untouched. When the target is not an already-configured source root and rewriteBuildFiles=true, also emits an additive, parse-verified build-file registration of the target root (Gradle sourceSets srcDir, or Maven build-helper-maven-plugin add-source/add-test-source). Refuses an unknown source or target root, a destination file collision, a non-editable target, and a genuinely unsupported build-file shape (build_file_rewrite_unsupported)."));

    // F1: every dedicated V3 dispatch method (the keys of V3_DISPATCH_GATES) is enumerated in the public capability
    // contract so the advertised surface matches what the sidecar actually dispatches. These ship as "experimental"
    // and report status "preview" until their finding (F2/F4-F13) lands and promotes them into READY_OPERATIONS;
    // capabilityStatus reports "disabled" for any that the effective java_refactor.v3 config gates off. Descriptions
    // state the real, currently-implemented behaviour of each dispatched method.
    private static final List<CapabilitySpec> V3_DISPATCH_CAPABILITY_SPECS = List.of(
            new CapabilitySpec("transformation.createWorkspace", "experimental", "Open a revision-guarded V3 transformation workspace for a canonical refactor operation."),
            new CapabilitySpec("transformation.preview", "experimental", "Compute the preview workspace edit and impact report for a staged V3 transformation."),
            new CapabilitySpec("transformation.apply", "experimental", "Apply a previewed V3 transformation under full project-revision guarding."),
            new CapabilitySpec("transformation.cancel", "experimental", "Discard a staged V3 transformation workspace."),
            new CapabilitySpec("transformation.list", "experimental", "List the live V3 transformation workspaces for the session."),
            new CapabilitySpec("transformation.report", "experimental", "Return the impact report for a staged V3 transformation."),
            new CapabilitySpec("deletion.propagateSafeDelete", "experimental", "Safe-delete a symbol and propagate cleanup (imports, empty packages, resource/bean entries) across the project."),
            new CapabilitySpec("deletion.findDeadCode", "experimental", "Scan for unreferenced project symbols as dead-code candidates."),
            new CapabilitySpec("classRefactor.extractClass", "experimental", "Extract selected members into a new class with their dependency closure and back-references."),
            new CapabilitySpec("classRefactor.extractSuperclass", "experimental", "Extract members into a new superclass and rebase the source type onto it."),
            new CapabilitySpec("classRefactor.replaceInheritanceWithDelegation", "experimental", "Replace a superclass with a delegate field and forwarding methods."),
            new CapabilitySpec("conversions.anonymousToLambda", "experimental", "Convert eligible anonymous classes to lambda expressions."),
            new CapabilitySpec("conversions.lambdaToMethodReference", "experimental", "Convert eligible lambda expressions to method references."),
            new CapabilitySpec("inlineRefactor.deepInlineMethod", "experimental", "Inline a method transitively across its call graph where provably safe."),
            new CapabilitySpec("recipes.scanMigrationOpportunities", "experimental", "Scan the project for declarative migration-recipe opportunities."),
            new CapabilitySpec("recipes.applyRecipe", "experimental", "Apply a declarative migration recipe across the project."),
            new CapabilitySpec("resources.findReferences", "experimental", "Find references to a type or package inside scanned non-Java resources."),
            new CapabilitySpec("resources.planEdits", "experimental", "Plan safe in-place resource rewrites and file renames for moved types/packages."),
            new CapabilitySpec("frameworks.detect", "experimental", "Detect frameworks (Spring/JPA/Jackson/JUnit) participating in the project."),
            new CapabilitySpec("frameworks.findReferences", "experimental", "Find framework-mediated references (annotations/config) to a symbol."),
            new CapabilitySpec("frameworks.participate", "experimental", "Let framework plugins participate in a symbol change: veto safe-deletes, validate metadata, contribute resource edits/warnings, and add reachability roots."),
            new CapabilitySpec("graph.build", "experimental", "Build the cached, revision-keyed transformation graph (build layout, Java symbols, type hierarchy, calls, resource references, tests) for the project."),
            new CapabilitySpec("impact.facts", "experimental", "Return structured impact facts (semantic/resource/test/API/risk) for a planned operation."));

    /** The full advertised capability registry: the V2/V1 specs followed by every dedicated V3 dispatch method. */
    private static final List<CapabilitySpec> ALL_CAPABILITY_SPECS = buildAllCapabilitySpecs();

    private static List<CapabilitySpec> buildAllCapabilitySpecs() {
        java.util.List<CapabilitySpec> all = new java.util.ArrayList<>(CAPABILITY_SPECS);
        all.addAll(V3_DISPATCH_CAPABILITY_SPECS);
        return List.copyOf(all);
    }

    /** Public V2 contract map: operation name -> string lifecycle level (stable/beta/experimental). */
    private String capabilitiesJson() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ALL_CAPABILITY_SPECS.size(); i++) {
            CapabilitySpec spec = ALL_CAPABILITY_SPECS.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append(JsonUtil.quote(spec.operation())).append(":").append(JsonUtil.quote(spec.level()));
        }
        return sb.append("}").toString();
    }

    /** Sibling metadata map: operation name -> {level,status,description}, carrying the richer readiness detail. */
    private String capabilityDetailsJson() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ALL_CAPABILITY_SPECS.size(); i++) {
            CapabilitySpec spec = ALL_CAPABILITY_SPECS.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append(capabilityDetailJson(spec.operation(), spec.level(), spec.description()));
        }
        return sb.append("}").toString();
    }

    /**
     * Resolves the truthful capability status with precedence disabled > not-ready > supported. A V2 op disabled by
     * config reports "disabled"; a dedicated V3 dispatch op disabled by the java_refactor.v3 config also reports
     * "disabled"; an op whose hard requirements are not yet implemented (it is not in READY_OPERATIONS) reports
     * "preview"; otherwise "supported". V1 stable ops are always "supported" (not gated by any enable flag).
     */
    private String capabilityStatus(String operation) {
        if (isV2Operation(operation) && !operationEnabled(operation)) {
            return "disabled";
        }
        if (isV3DispatchOperation(operation) && !v3OperationEnabled(operation)) {
            return "disabled";
        }
        return operationReady(operation) ? "supported" : "preview";
    }

    private String capabilityDetailJson(String operation, String level, String description) {
        return JsonUtil.quote(operation)
                + ":{"
                + "\"level\":" + JsonUtil.quote(level) + ","
                + "\"status\":" + JsonUtil.quote(capabilityStatus(operation)) + ","
                + "\"description\":" + JsonUtil.quote(description)
                + "}";
    }

    private String createSessionJson(Map<String, Object> fields) {
        String operation = str(fields, "operation", "unknown");
        if (!initialized || projectRoot == null) {
            return RefactorSessionManager.refusalJson(
                    operation, "not_initialized", "Sidecar must be initialized before creating a refactor session.");
        }
        if (isV2Operation(operation) && !operationEnabled(operation)) {
            return operationDisabledRefusalJson(operation);
        }
        fields = applyConfiguredDefaults(operation, fields);
        String policyRefusal = policyRefusalJson(operation, fields);
        if (policyRefusal != null) {
            return policyRefusal;
        }
        String previewJson = operationJson(operation, fields, false);
        if (previewJson == null) {
            return refactorResultJson(false, operation);
        }
        if (!accepted(previewJson)) {
            return previewJson;
        }
        try {
            JavaProjectModel model = discoverSemanticPlanningModel(fields);
            previewJson = previewDiagnosticValidator.validate(operation, previewJson, model);
            if (!accepted(previewJson)) {
                return previewJson;
            }
            // HB-10: a V2 session may be created only over a javac-validated preview delta.
            String unvalidatedV2Refusal = requireValidatedV2Delta(operation, previewJson);
            if (unvalidatedV2Refusal != null) {
                return unvalidatedV2Refusal;
            }
            // G015: uniform post-plan generated/Lombok gate on the session path too — a session's preview carries the same
            // multi-file workspaceEdit, so every created/changed target must be re-checked against the policy before a
            // session is created against it (the pre-dispatch policyRefusalJson above only saw relativePath/targetRelativePath).
            if (isV2Operation(operation)) {
                String postPlanRefusal = postPlanPolicyRefusalJson(model,
                        java.nio.file.Path.of(projectRoot),
                        operation,
                        bool(fields, "allowGenerated", generatedSourcesEditAllowed()),
                        bool(fields, "allowLombok", false),
                        previewJson);
                if (postPlanRefusal != null) {
                    return postPlanRefusal;
                }
            }
            if (!RefactorSessionManager.hasStableSemanticTarget(previewJson)) {
                return RefactorSessionManager.refusalJson(
                    operation, "target_identity_missing", "Refactor session preview is missing target.semanticKey.");
            }
            RefactorSession session = sessionManager.createSession(operation, fields, model, previewJson);
            ProjectRevision current = ProjectRevision.capture(model, session.touchedFiles());
            String envelope = RefactorSessionManager.sessionEnvelope(
                    session, previewJson, "preview", RefactorSessionManager.validationJson(session, current));
            // G002: layer the V2 plan's public top-level contract fields around the preserved nested envelope.
            return withV2CreateSessionContract(envelope, session, previewJson);
        } catch (Exception e) {
            // Keep the refusal payload sanitized like every other structured refusal: surface the exception's own
            // message, or its class name when the message is null (e.g. a bare NPE), but never an internal stack frame.
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            return RefactorSessionManager.refusalJson("session_create_failed", detail);
        }
    }

    private String sessionEditJson(Map<String, Object> fields, boolean applyRequested) {
        if (!initialized || projectRoot == null) {
            return RefactorSessionManager.refusalJson(
                    "not_initialized", "Sidecar must be initialized before using a refactor session.");
        }
        String sessionId = str(fields, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return RefactorSessionManager.refusalJson("malformed_session", "sessionId is required.");
        }
        RefactorSession session = sessionManager.get(sessionId);
        if (session == null) {
            return RefactorSessionManager.refusalJson("unknown_session", "No live refactor session for sessionId " + sessionId + ".");
        }
        if (isV2Operation(session.operation()) && !operationEnabled(session.operation())) {
            return operationDisabledRefusalJson(session.operation());
        }
        // G003: getSessionEdit accepts an optional `format`. The sidecar currently serializes a single edit format
        // ("workspaceEdit"); validate the requested format and refuse an unknown one with a structured code so callers
        // get a deterministic contract instead of a silently-ignored parameter.
        String requestedFormat = str(fields, "format");
        if (requestedFormat != null && !requestedFormat.isBlank() && !SUPPORTED_EDIT_FORMATS.contains(requestedFormat)) {
            return refusalJson("unsupported_edit_format",
                    "Unknown session edit format '" + requestedFormat + "'. Supported formats: "
                            + String.join(", ", SUPPORTED_EDIT_FORMATS) + ".");
        }
        String effectiveFormat = requestedFormat == null || requestedFormat.isBlank() ? DEFAULT_EDIT_FORMAT : requestedFormat;
        if (applyRequested) {
            // Optimistic-concurrency guard: when the caller pins an expectedProjectRevision, it must match the revision
            // the session captured at create time before any re-resolution or write is attempted.
            String expectedRevisionMismatch = RefactorSessionManager.expectedRevisionMismatch(
                    session, fields.get("expectedProjectRevision"));
            if (expectedRevisionMismatch != null) {
                return RefactorSessionManager.refusalJson("project_revision_mismatch", expectedRevisionMismatch);
            }
        }
        try {
            JavaProjectModel model = discoverSemanticPlanningModel(session.requestFields());
            ProjectRevision current = ProjectRevision.capture(model, session.touchedFiles());
            // G001: incremental (partial) session apply. A request that names a `selection`, or a session that has
            // already had a subset applied, is handled by filtering the stored validated plan to the selected units and
            // surfacing/applying only that subset — never the whole plan. The whole-session path below is preserved for
            // callers that apply everything at once.
            Object selection = fields.get("selection");
            java.util.Set<String> appliedBefore = sessionManager.appliedUnits(sessionId);
            if (selection instanceof Map<?, ?> || !appliedBefore.isEmpty()) {
                return incrementalSessionEdit(
                        session, sessionId, model, current, applyRequested, effectiveFormat, selection, appliedBefore);
            }
            // Blocker 1 (V2 apply contract): on apply we surface the freshly recomputed + revalidated plan, NEVER the
            // stored create-time preview. The revision/model-hash guards reduce drift but do not prove the stored preview
            // is byte-identical to the revalidated plan (planner nondeterminism, changed defaults/config, capability or
            // version drift, post-plan policy differences), so the plan validated below MUST be the exact plan Python
            // applies. The preview (non-apply) path still surfaces the stored preview unchanged.
            String planToSurface = session.previewJson();
            if (applyRequested) {
                String currentPlan = operationJson(session.operation(), session.requestFields(), false);
                if (currentPlan == null || !accepted(currentPlan)) {
                    return RefactorSessionManager.refusalJson(
                            "target_reresolve_failed",
                            "Could not re-resolve the session target before apply.");
                }
                currentPlan = previewDiagnosticValidator.validate(session.operation(), currentPlan, model, true);
                if (!accepted(currentPlan)) {
                    return currentPlan;
                }
                // HB-10: the re-validated session plan must carry a real javac delta before it is surfaced/applied.
                String unvalidatedV2Refusal = requireValidatedV2Delta(session.operation(), currentPlan);
                if (unvalidatedV2Refusal != null) {
                    return unvalidatedV2Refusal;
                }
                String currentTargetIdentity = RefactorSessionManager.targetIdentity(
                        session.operation(), session.requestFields(), currentPlan);
                if (!session.targetIdentity().equals(currentTargetIdentity)) {
                    return RefactorSessionManager.refusalJson(
                            "target_identity_changed",
                            "The semantic target changed since the session preview was created.");
                }
                planToSurface = currentPlan;
            }
            // Stale-revision guard (design §20): refuse an apply whose project revision drifted since the preview was
            // taken. This is enforced unconditionally by design — the require_revision_match_on_apply config key is
            // accepted for schema compatibility but does NOT gate this check (see RefactorSessionManager
            // #requireRevisionMatchOnApply), since disabling it could only weaken the apply safety contract.
            String mismatch = session.revision().mismatch(current);
            if (applyRequested && mismatch != null) {
                return RefactorSessionManager.refusalJson("stale_project_revision", mismatch);
            }
            String envelope = RefactorSessionManager.sessionEnvelope(
                    session, planToSurface, applyRequested ? "apply" : "preview",
                    RefactorSessionManager.validationJson(session, current));
            // Echo the (validated) edit format so callers can confirm which serialization they received.
            String formatted = withTopLevelStringField(envelope, "format", effectiveFormat);
            if (SERENA_WORKSPACE_EDIT_FORMAT.equals(effectiveFormat)) {
                // Tag the emitted edit object so the Serena transactional applier payload is self-identifying.
                formatted = tagSerenaWorkspaceEdit(formatted);
            }
            return formatted;
        } catch (Exception e) {
            return RefactorSessionManager.refusalJson("session_apply_failed", e.getMessage());
        }
    }

    /**
     * G001: incremental (partial) session get/apply. Filters the session's stored validated plan to the selected units
     * (intersected with the units not yet applied), validates that exact subset overlay via javac, and — on apply —
     * surfaces it for Python to write, records the units as applied, and reports the still-unapplied {@code remaining}
     * subset. A later call applies that remainder. The revision guard ignores files an earlier partial apply already
     * mutated while still requiring the files this subset writes to be unchanged since the preview.
     */
    private String incrementalSessionEdit(
            RefactorSession session, String sessionId, JavaProjectModel model, ProjectRevision current,
            boolean applyRequested, String effectiveFormat, Object selection, java.util.Set<String> appliedBefore) {
        try {
            // G003: on apply, re-resolve the session target and recompute its plan against the CURRENT project before
            // surfacing any subset. The selection is then filtered against this recomputed plan — never the stored
            // create-time preview — so a target whose semantic identity moved (renamed/relocated declaration, changed
            // hierarchy) is refused, and a subset is only applied if it still exists in the current plan. On a non-apply
            // preview we keep surfacing the stored plan (preview must not re-resolve or mutate).
            String planForSelection = session.previewJson();
            if (applyRequested) {
                // G001/G003: re-resolve against the CURRENT project, but tell the planner which output paths a prior
                // acknowledged partial apply already committed to disk so its own in-progress outputs are not mistaken
                // for stale external collisions. The recomputed plan still emits those units (selection filters the
                // applied ones out), so target identity and unit structure remain comparable to the stored preview.
                java.util.Map<String, Object> reresolveFields = new java.util.HashMap<>(session.requestFields());
                reresolveFields.put(
                        "__sessionAppliedOutputPaths",
                        new java.util.ArrayList<>(SessionSelection.pathsFor(session.previewJson(), appliedBefore)));
                String currentPlan = operationJson(session.operation(), reresolveFields, false);
                if (currentPlan == null || !accepted(currentPlan)) {
                    return RefactorSessionManager.refusalJson(
                            "target_reresolve_failed",
                            "Could not re-resolve the session target before incremental apply.");
                }
                currentPlan = previewDiagnosticValidator.validate(session.operation(), currentPlan, model, true);
                if (!accepted(currentPlan)) {
                    return currentPlan;
                }
                String currentPlanV2Refusal = requireValidatedV2Delta(session.operation(), currentPlan);
                if (currentPlanV2Refusal != null) {
                    return currentPlanV2Refusal;
                }
                String currentTargetIdentity = RefactorSessionManager.targetIdentity(
                        session.operation(), session.requestFields(), currentPlan);
                if (!session.targetIdentity().equals(currentTargetIdentity)) {
                    return RefactorSessionManager.refusalJson(
                            "target_identity_changed",
                            "The semantic target changed since the session preview was created.");
                }
                planForSelection = currentPlan;
            }
            SessionSelection.Resolution resolution =
                    SessionSelection.select(planForSelection, selection, appliedBefore);
            if (resolution.refused()) {
                return RefactorSessionManager.refusalJson(resolution.refusalCode(), resolution.refusalMessage());
            }
            String filteredPlan = withWorkspaceEdit(planForSelection, resolution.filteredWorkspaceEditJson());
            // Validate the selected subset overlay against current disk; a subset that does not compile is refused.
            String validated = previewDiagnosticValidator.validate(session.operation(), filteredPlan, model, applyRequested);
            if (!accepted(validated)) {
                return validated;
            }
            String unvalidatedV2Refusal = requireValidatedV2Delta(session.operation(), validated);
            if (unvalidatedV2Refusal != null) {
                return unvalidatedV2Refusal;
            }
            String pendingUnitIdsJson = "[]";
            if (applyRequested) {
                // G002: enforce the FULL project-revision token. Only files an earlier acknowledged, committed partial
                // apply already mutated are exempt from the per-file source-hash check; build-file, classpath,
                // compiler-arg, generated-root/content, and source-root drift are all refused.
                java.util.Set<String> exemptPaths = SessionSelection.pathsFor(planForSelection, appliedBefore);
                String mismatch = session.revision().mismatchFull(current, exemptPaths);
                if (mismatch != null) {
                    return RefactorSessionManager.refusalJson("stale_project_revision", mismatch);
                }
                // G001: do NOT record these units as applied here. They are only SURFACED for application; the session
                // must reflect committed disk state, not edit-envelope emission. Python records them via ackSessionApply
                // strictly after its transactional commit + post-validation succeed.
                pendingUnitIdsJson = unitIdsJson(resolution.selectedUnitIds());
            }
            String envelope = RefactorSessionManager.incrementalSessionEnvelope(
                    session, validated, applyRequested ? "apply" : "preview",
                    RefactorSessionManager.validationJson(session, current),
                    resolution.selectionModelJson(), resolution.remainingJson(), resolution.complete(), effectiveFormat,
                    pendingUnitIdsJson);
            if (SERENA_WORKSPACE_EDIT_FORMAT.equals(effectiveFormat)) {
                envelope = tagSerenaWorkspaceEdit(envelope);
            }
            return envelope;
        } catch (Exception e) {
            return RefactorSessionManager.refusalJson("session_apply_failed", e.getMessage());
        }
    }

    /**
     * G001 post-commit acknowledgement. Python calls this strictly AFTER it has committed an incremental subset to disk
     * (and post-validation passed) to record those plan units as applied. Until this lands, the session reports the
     * units as still unapplied — so a Python staging/commit/post-validation failure leaves session state untouched.
     */
    private String ackSessionApplyJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return RefactorSessionManager.refusalJson(
                    "not_initialized", "Sidecar must be initialized before using a refactor session.");
        }
        String sessionId = str(fields, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return RefactorSessionManager.refusalJson("malformed_session", "sessionId is required.");
        }
        java.util.Set<String> unitIds = new java.util.LinkedHashSet<>();
        Object unitsObj = fields.get("unitIds");
        if (unitsObj instanceof List<?> list) {
            for (Object element : list) {
                if (element != null) {
                    unitIds.add(element.toString());
                }
            }
        }
        return sessionManager.ackSessionApplyJson(sessionId, unitIds);
    }

    /** Serializes a set of session plan-unit ids as a JSON string array (G001 post-commit ack surface). */
    private static String unitIdsJson(java.util.Set<String> unitIds) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (String id : unitIds) {
            if (!first) {
                builder.append(',');
            }
            builder.append(JsonUtil.quote(id));
            first = false;
        }
        return builder.append(']').toString();
    }

    /** Replaces the {@code workspaceEdit} object of a stored plan JSON with a filtered one (G001 incremental apply). */
    private static String withWorkspaceEdit(String planJson, String workspaceEditJson) {
        Map<String, Object> plan = new LinkedHashMap<>(Json.parseObject(planJson));
        plan.put("workspaceEdit", Json.parseObject(workspaceEditJson));
        return Json.write(plan);
    }

    /**
     * Tags the {@code workspaceEdit} object(s) in a session-edit envelope with an
     * {@code editFormat: "serenaWorkspaceEdit"} discriminator so the payload self-identifies as the Serena
     * transactional-applier shape. Handles both a top-level {@code workspaceEdit} and a nested
     * {@code preview.workspaceEdit}. Returns the input unchanged when it cannot be parsed.
     */
    private static String tagSerenaWorkspaceEdit(String json) {
        try {
            Map<String, Object> root = new LinkedHashMap<>(Json.parseObject(json));
            tagWorkspaceEditInPlace(root);
            return Json.write(root);
        } catch (RuntimeException e) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private static void tagWorkspaceEditInPlace(Map<String, Object> object) {
        Object workspaceEdit = object.get("workspaceEdit");
        if (workspaceEdit instanceof Map<?, ?> we) {
            Map<String, Object> mutable = new LinkedHashMap<>((Map<String, Object>) we);
            mutable.put("editFormat", SERENA_WORKSPACE_EDIT_FORMAT);
            object.put("workspaceEdit", mutable);
        }
        Object preview = object.get("preview");
        if (preview instanceof Map<?, ?> pm) {
            Map<String, Object> mutablePreview = new LinkedHashMap<>((Map<String, Object>) pm);
            tagWorkspaceEditInPlace(mutablePreview);
            object.put("preview", mutablePreview);
        }
    }

    /** Adds (or overwrites) a top-level string field on an accepted JSON object, preserving every other field. */
    private static String withTopLevelStringField(String json, String key, String value) {
        try {
            Map<String, Object> object = new LinkedHashMap<>(Json.parseObject(json));
            object.put(key, value);
            return Json.write(object);
        } catch (RuntimeException e) {
            return json;
        }
    }

    private String cancelSessionJson(Map<String, Object> fields) {
        String sessionId = str(fields, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return RefactorSessionManager.refusalJson("malformed_session", "Session protocol requires sessionId.");
        }
        boolean cancelled = sessionManager.cancel(sessionId);
        return "{\"accepted\":true,\"cancelled\":" + cancelled + ",\"sessionId\":" + JsonUtil.quote(sessionId) + "}";
    }

    private boolean accepted(String json) {
        try {
            Object value = Json.parseObject(json).get("accepted");
            return value instanceof Boolean accepted && accepted;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * HB-10: an accepted V2 preview/apply result may be surfaced to the client ONLY when it carries a real javac
     * before/after diagnostic delta. A planner's not-yet-validated placeholder delta is byte-identical (empty) to a
     * clean validated delta, so the only safe discriminator is the {@code diagnosticDeltaValidated} marker that
     * {@link PreviewDiagnosticValidator} flips to true when it replaces the placeholder. Returns a structured refusal
     * JSON when an accepted V2 result lacks that marker (defence-in-depth: an unvalidated delta can never be the default
     * accepted answer even if a future code path forgot to run the validator); returns null when the result is fine.
     */
    private String requireValidatedV2Delta(String operation, String json) {
        if (!isV2Operation(operation) || !accepted(json)) {
            return null;
        }
        try {
            Object marker = Json.parseObject(json).get("diagnosticDeltaValidated");
            if (marker instanceof Boolean validated && validated) {
                return null;
            }
        } catch (RuntimeException ignored) {
            // fall through to the refusal below
        }
        return refusalJson("unvalidated_diagnostic_delta",
                "Refusing to present an accepted V2 preview for '" + operation
                        + "' without a javac-validated before/after diagnostic delta.");
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

    private String changeSignatureJson(Map<String, Object> fields, boolean apply) {
        try {
            return new ChangeSignaturePlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).changeSignature(fields, apply);
        } catch (Exception e) {
            return refusalJson("change_signature_failed", e.getMessage());
        }
    }

    private String introduceParameterJson(Map<String, Object> fields, boolean apply) {
        try {
            return new ChangeSignaturePlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).introduceParameter(fields, apply);
        } catch (Exception e) {
            return refusalJson("introduce_parameter_failed", e.getMessage());
        }
    }


    private String moveStaticMemberJson(Map<String, Object> fields, boolean apply) {
        try {
            return new MoveMemberPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).moveStaticMember(fields, apply);
        } catch (Exception e) {
            return refusalJson("move_static_member_failed", e.getMessage());
        }
    }

    private String moveInstanceMethodJson(Map<String, Object> fields, boolean apply) {
        try {
            return new MoveMemberPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).moveInstanceMethod(fields, apply);
        } catch (Exception e) {
            return refusalJson("move_instance_method_failed", e.getMessage());
        }
    }

    private String pullUpMemberJson(Map<String, Object> fields, boolean apply) {
        try {
            return new PullPushMemberPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).pullUpMember(fields, apply);
        } catch (Exception e) {
            return refusalJson("pull_up_member_failed", e.getMessage());
        }
    }

    private String pushDownMemberJson(Map<String, Object> fields, boolean apply) {
        try {
            return new PullPushMemberPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).pushDownMember(fields, apply);
        } catch (Exception e) {
            return refusalJson("push_down_member_failed", e.getMessage());
        }
    }

    private String extractMethodJson(Map<String, Object> fields, boolean apply) {
        try {
            return new ExtractMethodPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).extractMethod(fields, apply);
        } catch (Exception e) {
            return refusalJson("extract_method_failed", e.getMessage());
        }
    }

    private String inlineMethodJson(Map<String, Object> fields, boolean apply) {
        try {
            return new InlineMethodPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).inlineMethod(fields, apply);
        } catch (Exception e) {
            return refusalJson("inline_method_failed", e.getMessage());
        }
    }

    private String extractInterfaceJson(Map<String, Object> fields, boolean apply) {
        try {
            return new ExtractInterfacePlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).extractInterface(fields, apply);
        } catch (Exception e) {
            return refusalJson("extract_interface_failed", e.getMessage());
        }
    }

    private String introduceFieldJson(Map<String, Object> fields, boolean apply) {
        try {
            return new FieldRefactorPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).introduceField(fields, apply);
        } catch (Exception e) {
            return refusalJson("introduce_field_failed", e.getMessage());
        }
    }

    private String encapsulateFieldJson(Map<String, Object> fields, boolean apply) {
        try {
            // G003: dispatch encapsulate-field through its named planner so the operation→class mapping is explicit.
            return new EncapsulateFieldPlanner(java.nio.file.Path.of(projectRoot), discoverSemanticPlanningModel(fields)).encapsulateField(fields, apply);
        } catch (Exception e) {
            return refusalJson("encapsulate_field_failed", e.getMessage());
        }
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
        JavaProjectModel projectModel = discoverSemanticPlanningModel(fields);
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
        JavaProjectModel projectModel = discoverSemanticPlanningModel(fields);
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
        JavaProjectModel projectModel = discoverSemanticPlanningModel(fields);
        String gateRefusal = modelGateRefusal(projectModel, apply);
        if (gateRefusal != null) {
            return gateRefusal;
        }
        try {
            return new SafeDeletePlanner().plan(
                    projectModel,
                    relativePath,
                    line,
                    column,
                    bool(fields, "allowPublicApi", false),
                    bool(fields, "searchInCommentsAndStrings", false),
                    bool(fields, "searchForTextOccurrences", false),
                    targetHints(fields));
        } catch (Exception e) {
            return refusalJson("safe_delete_failed", e.getMessage());
        }
    }

    private String renamePackageJson(Map<String, Object> fields, boolean apply) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before renaming a package.");
        }
        String oldPackage = str(fields, "oldPackage");
        String newPackage = str(fields, "newPackage");
        if (oldPackage == null || oldPackage.isBlank() || newPackage == null || newPackage.isBlank()) {
            return refusalJson("malformed_rename_package", "renamePackage requires oldPackage and newPackage.");
        }
        JavaProjectModel projectModel = discoverSemanticPlanningModel(fields);
        String gateRefusal = modelGateRefusal(projectModel, apply);
        if (gateRefusal != null) {
            return gateRefusal;
        }
        try {
            return new io.serena.javarefactor.v3.packages.RenamePackagePlanner(
                    java.nio.file.Path.of(projectRoot), projectModel,
                    packageRewritePolicy().withRequestOverrides(fields)).plan(fields, apply);
        } catch (Exception e) {
            return refusalJson("rename_package_failed", e.getMessage());
        }
    }

    private String movePackageJson(Map<String, Object> fields, boolean apply) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before moving a package.");
        }
        String sourcePackage = str(fields, "sourcePackage");
        String targetPackage = str(fields, "targetPackage");
        if (sourcePackage == null || sourcePackage.isBlank() || targetPackage == null || targetPackage.isBlank()) {
            return refusalJson("malformed_move_package", "movePackage requires sourcePackage and targetPackage.");
        }
        JavaProjectModel projectModel = discoverSemanticPlanningModel(fields);
        String gateRefusal = modelGateRefusal(projectModel, apply);
        if (gateRefusal != null) {
            return gateRefusal;
        }
        try {
            return new io.serena.javarefactor.v3.packages.MovePackagePlanner(
                    java.nio.file.Path.of(projectRoot), projectModel,
                    packageRewritePolicy().withRequestOverrides(fields)).plan(fields, apply);
        } catch (Exception e) {
            return refusalJson("move_package_failed", e.getMessage());
        }
    }

    private String moveSourceRootJson(Map<String, Object> fields, boolean apply) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before moving a source root.");
        }
        String sourceRoot = str(fields, "sourceRoot");
        String targetSourceRoot = str(fields, "targetSourceRoot");
        if (sourceRoot == null || sourceRoot.isBlank() || targetSourceRoot == null || targetSourceRoot.isBlank()) {
            return refusalJson("malformed_move_source_root",
                    "moveSourceRoot requires sourceRoot and targetSourceRoot.");
        }
        JavaProjectModel projectModel = discoverSemanticPlanningModel(fields);
        String gateRefusal = modelGateRefusal(projectModel, apply);
        if (gateRefusal != null) {
            return gateRefusal;
        }
        // §6.2 step 5/6: thread the preserve_package_names mode explicitly into the planner. When false, the planner
        // recomputes each moved file's package from the directory mapping and runs the package-rename logic
        // (declarations, imports, FQNs, module-info, resources); when true (default) it keeps declarations untouched.
        fields.put("preservePackageNames", bool(fields, "preservePackageNames", true));
        try {
            return new io.serena.javarefactor.v3.packages.MoveSourceRootPlanner(
                    java.nio.file.Path.of(projectRoot), projectModel,
                    packageRewritePolicy().withRequestOverrides(fields)).plan(fields, apply);
        } catch (Exception e) {
            return refusalJson("move_source_root_failed", e.getMessage());
        }
    }

    // ---- V3 transformation.* protocol (refactor-feature-plan-V3.md §1.1) ----

    /**
     * Lazily builds the transformation-workspace manager, wiring the sidecar-internal callbacks (model discovery, the
     * package-private diagnostic validator, project-revision capture) it cannot reach itself. Returns null when the
     * sidecar is not yet initialized.
     */
    private io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager transformationManager() {
        if (transformationWorkspaceManager != null) {
            return transformationWorkspaceManager;
        }
        if (!initialized || projectRoot == null) {
            return null;
        }
        java.nio.file.Path root = java.nio.file.Path.of(projectRoot);
        // Read java_refactor.v3.transformations config (§20 defaults: maxOpenWorkspaces=8, ttlMinutes=60).
        Map<?, ?> transformationsConfig = Map.of();
        Map<?, ?> v3 = v3ConfigSection(effectiveConfigurationMap());
        if (v3.get("transformations") instanceof Map<?, ?> txConfig) {
            transformationsConfig = txConfig;
        }
        int maxOpen = positiveIntOrDefault(transformationsConfig, 8, "max_open_workspaces", "maxOpenWorkspaces");
        long ttlMinutes = positiveIntOrDefault(transformationsConfig, 60, "ttl_minutes", "ttlMinutes");

        io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.StepPlanner stepPlanner =
                (operation, arguments) -> planTransformationStep(operation, arguments);

        io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.PreviewBuilder previewBuilder =
                (semanticTargetJson, edits, fileOperations, warnings) -> {
                    try {
                        return ResponseBuilder.acceptedResult(
                                root,
                                "transformation",
                                false,
                                semanticTargetJson,
                                edits,
                                fileOperations,
                                warnings,
                                java.util.List.of("Composed transformation workspace; the after-state is javac-validated "
                                        + "before the preview is accepted."),
                                ResponseBuilder.DiagnosticDelta.unvalidated(),
                                false);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                };

        io.serena.javarefactor.v3.transformation.TransformationValidator validator =
                (operation, previewJson) -> {
                    JavaProjectModel model = discoverSemanticPlanningModel(java.util.Map.of());
                    return previewDiagnosticValidator.validate(operation, previewJson, model, false);
                };

        io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.RevisionCapturer revisionCapturer =
                touchedRelativePaths -> {
                    try {
                        JavaProjectModel model = discoverSemanticPlanningModel(java.util.Map.of());
                        // stableToken (not toJson) so the guard compares only drift-sensitive inputs, never the
                        // wall-clock createdAt — otherwise every apply would be rejected as stale.
                        return ProjectRevision.capture(model, touchedRelativePaths).stableToken();
                    } catch (Exception e) {
                        // A capture failure must not silently weaken the clean-revision guard: return null so apply's
                        // own null-check treats the revision as unavailable rather than equal.
                        return null;
                    }
                };

        io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.ModelSupplier modelSupplier =
                () -> {
                    try {
                        return discoverSemanticPlanningModel(java.util.Map.of());
                    } catch (Exception e) {
                        // A discovery failure must yield a structured impact-report refusal, not a crash: return null so
                        // report() emits the impact_report_model_unavailable refusal.
                        return null;
                    }
                };

        transformationWorkspaceManager = new io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager(
                root, maxOpen, ttlMinutes, stepPlanner, previewBuilder, validator, revisionCapturer, modelSupplier);
        return transformationWorkspaceManager;
    }

    /**
     * Runs one named V3 operation planner against {@code arguments}, returning its structured {@link
     * io.serena.javarefactor.v3.transformation.TransformationStep} or the canonical refusal JSON the planner produced
     * (model-gate refusal, malformed input, or a planner precondition refusal).
     */
    @SuppressWarnings("unchecked")
    private io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.StepResult planTransformationStep(
            String operation, Map<String, Object> arguments) {
        JavaProjectModel projectModel = discoverSemanticPlanningModel(arguments);
        String gateRefusal = modelGateRefusal(projectModel, false);
        if (gateRefusal != null) {
            return io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.StepResult.refused(gateRefusal);
        }
        java.nio.file.Path root = java.nio.file.Path.of(projectRoot);
        try {
            io.serena.javarefactor.v3.transformation.TransformationStep step = switch (operation) {
                case "renamePackage" -> new io.serena.javarefactor.v3.packages.RenamePackagePlanner(
                        root, projectModel, packageRewritePolicy().withRequestOverrides(arguments)).planStep(arguments);
                case "movePackage" -> new io.serena.javarefactor.v3.packages.MovePackagePlanner(
                        root, projectModel, packageRewritePolicy().withRequestOverrides(arguments)).planStep(arguments);
                case "moveSourceRoot" -> new io.serena.javarefactor.v3.packages.MoveSourceRootPlanner(
                        root, projectModel, packageRewritePolicy().withRequestOverrides(arguments)).planStep(arguments);
                case "anonymousToLambda" -> new io.serena.javarefactor.v3.conversions.AnonymousToLambdaPlanner(
                        root, projectModel).planStep(arguments);
                case "lambdaToMethodReference" -> new io.serena.javarefactor.v3.conversions.LambdaToMethodReferencePlanner(
                        root, projectModel).planStep(arguments);
                case "deepInlineMethod" -> new io.serena.javarefactor.v3.inline.DeepInlineMethodPlanner(
                        root, projectModel).planStep(injectV3InlineDefaults(arguments));
                case "extractClass" -> new io.serena.javarefactor.v3.classops.ExtractClassPlanner(
                        root, projectModel).planStep(arguments);
                case "extractSuperclass" -> new io.serena.javarefactor.v3.classops.ExtractSuperclassPlanner(
                        root, projectModel).planStep(arguments);
                case "replaceInheritanceWithDelegation" ->
                        new io.serena.javarefactor.v3.classops.ReplaceInheritanceWithDelegationPlanner(
                                root, projectModel).planStep(arguments);
                case "propagateSafeDelete" -> new io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner()
                        .planStep(projectModel, parseDeletionRoots(arguments.get("roots")),
                                new io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner.Options(
                                        bool(arguments, "deletePrivateOnly", true),
                                        bool(arguments, "includeTests", false),
                                        bool(arguments, "includeResources", true),
                                        (int) lng(arguments, "maxCascadeDepth", 5)));
                default -> null;
            };
            if (step == null) {
                return io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.StepResult.refused(
                        refusalJson("unsupported_transformation_operation",
                                "Operation '" + operation + "' is not a supported transformation operation."));
            }
            return io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.StepResult.of(step);
        } catch (io.serena.javarefactor.shared.ProjectPathResolver.Violation violation) {
            // A path-resolution refusal (e.g. relativePath escapes the project root) carries its own canonical code.
            return io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.StepResult.refused(
                    refusalJson(violation.code(), violation.getMessage()));
        } catch (RuntimeException refusalCarrier) {
            // The planners encode precondition refusals via an internal Refusal RuntimeException that planStep does not
            // catch. When it carries a canonical registry code (CodedRefusal), surface that exact code (e.g.
            // package_not_found); otherwise an unexpected runtime fault degrades to a generic <operation>_failed.
            String code = refusalCarrier instanceof io.serena.javarefactor.v3.packages.CodedRefusal coded
                    ? coded.code()
                    : operation + "_failed";
            return io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.StepResult.refused(
                    refusalJson(code, String.valueOf(refusalCarrier.getMessage())));
        } catch (java.io.IOException e) {
            return io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.StepResult.refused(
                    refusalJson(operation + "_failed", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private String transformationCreateWorkspaceJson(Map<String, Object> fields) {
        var manager = transformationManager();
        if (manager == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before creating a transformation workspace.");
        }
        String goal = str(fields, "goal");
        java.util.List<io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.OperationRequest> requests =
                new java.util.ArrayList<>();
        // A single operation is given at top level ({operation, arguments}); a batch is given as operations:[{...}].
        Object operationsList = fields.get("operations");
        if (operationsList instanceof java.util.List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> entry) {
                    requests.add(operationRequestOf((Map<String, Object>) entry));
                }
            }
        } else {
            String operation = str(fields, "operation");
            if (operation == null || operation.isBlank()) {
                return refusalJson("transformation_no_operations",
                        "transformation.createWorkspace requires an operation (or operations[]).");
            }
            // normalizeV2SessionRequest already flattened the single op's nested `arguments` envelope onto the
            // top-level field map (and removed the `arguments` key), so the planner arguments ARE the flat fields.
            // (The operations[] batch path above keeps reading each entry's nested `arguments`, which is not flattened.)
            requests.add(new io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.OperationRequest(
                    operation, fields));
        }
        return manager.createWorkspace(goal, requests, System.currentTimeMillis());
    }

    @SuppressWarnings("unchecked")
    private static io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.OperationRequest operationRequestOf(
            Map<String, Object> entry) {
        String operation = entry.get("operation") instanceof String s ? s : null;
        Map<String, Object> arguments = entry.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : java.util.Map.of();
        return new io.serena.javarefactor.v3.transformation.TransformationWorkspaceManager.OperationRequest(
                operation, arguments);
    }

    private String transformationApplyJson(Map<String, Object> fields) {
        var manager = transformationManager();
        if (manager == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before applying a transformation workspace.");
        }
        String workspaceId = str(fields, "workspaceId");
        if (workspaceId == null || workspaceId.isBlank()) {
            return refusalJson("workspace_not_found", "transformation.apply requires a workspaceId.");
        }
        String expectedRevision = str(fields, "expectedProjectRevision");
        return manager.apply(workspaceId, expectedRevision, System.currentTimeMillis());
    }

    private String transformationWorkspaceCall(Map<String, Object> fields, String kind) {
        var manager = transformationManager();
        if (manager == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before using a transformation workspace.");
        }
        String workspaceId = str(fields, "workspaceId");
        if (workspaceId == null || workspaceId.isBlank()) {
            return refusalJson("workspace_not_found", "transformation." + kind + " requires a workspaceId.");
        }
        long now = System.currentTimeMillis();
        return switch (kind) {
            case "preview" -> manager.preview(workspaceId, now);
            case "cancel" -> manager.cancel(workspaceId, now);
            case "report" -> manager.report(workspaceId, now);
            default -> refusalJson("unsupported_method", "unsupported transformation call: " + kind);
        };
    }

    private String transformationListJson() {
        var manager = transformationManager();
        if (manager == null) {
            return refusalJson("not_initialized", "Sidecar must be initialized before listing transformation workspaces.");
        }
        return manager.list(System.currentTimeMillis());
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
        JavaProjectModel projectModel = discoverSemanticPlanningModel(fields);
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
        JavaProjectModel projectModel = discoverSemanticPlanningModel(fields);
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
     * on-disk source. Returns the overlay's compiler diagnostics; the on-disk {@link #discoverSemanticPlanningModel(fields)} cache is left
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
        // Warnings are kept separate as `compilerWarnings` so DiagnosticDelta can surface new unchecked/rawtype warnings.
        JavacSession.DiagnosticReport diagnostics = session.collectDiagnosticReport(unvalidated, overlay);
        List<String> compilerErrors = diagnostics.errorStrings();
        List<String> compilerWarnings = diagnostics.warningStrings();
        JavaProjectModel validated = unvalidated.withCompilerDiagnostics(compilerErrors);
        List<String> errors = validated.errors();
        // Static-validation layer 7 (§18.1.7) + resolution half of framework validation (§18.3): EXACT class references in
        // resources that this edit leaves dangling (a removed/renamed-away FQN still named by an unrewritten resource).
        // Edit-scoped and exact, so it never false-positives on library or unchanged types. Surfaced as its own field and
        // reflected in `ready` (the op-level not-ready signal). It is deliberately NOT folded into compilerErrors: the
        // apply gate's baseline-vs-staged delta is for hard javac breakage only, whereas a dangling free-text resource
        // reference (e.g. an XML bean class for a deleted type) is a review finding the caller decides how to act on —
        // safe-delete, for one, accepts the deletion and surfaces the reference as a warning rather than blocking.
        List<String> resourceFindings = io.serena.javarefactor.v3.validation.ResourceReferenceValidation.findings(
                unvalidated,
                mapField(fields, "changedFiles"),
                listField(fields, "deletedFiles"),
                listField(fields, "renamedFiles"));
        boolean ready = errors.isEmpty() && resourceFindings.isEmpty();
        StringBuilder json = new StringBuilder();
        json.append("{\"accepted\":true,\"ready\":").append(ready).append(',');
        json.append("\"errors\":").append(JsonUtil.array(errors)).append(',');
        json.append("\"compilerErrors\":").append(JsonUtil.array(compilerErrors)).append(',');
        json.append("\"compilerWarnings\":").append(JsonUtil.array(compilerWarnings)).append(',');
        json.append("\"resourceFindings\":").append(JsonUtil.array(resourceFindings)).append(',');
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

    /**
     * V3 propagating safe delete (refactor-feature-plan-V3.md §7.1–§7.4). Builds the graph-shaped {@code deletePlan}
     * plus a removing {@code workspaceEdit}, then runs it through the authoritative before/after javac validator so a
     * cascade that would not compile is refused. The sidecar never mutates files — Python's transactional applier owns
     * apply — so validation always runs in preview (workspaceEdit-preserving) mode.
     */
    private String propagateSafeDeleteJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before deletion.propagateSafeDelete.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        // B01: absent-only injection of java_refactor.v3.deletion defaults; an explicit request value always wins.
        fields = injectV3DeletionDefaults(fields);
        List<io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner.RootSpec> roots =
                parseDeletionRoots(fields.get("roots"));
        io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner.Options options =
                new io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner.Options(
                        bool(fields, "deletePrivateOnly", true),
                        bool(fields, "includeTests", false),
                        bool(fields, "includeResources", true),
                        (int) lng(fields, "maxCascadeDepth", 5));
        try {
            String planJson = new io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner()
                    .plan(model, roots, options);
            if (!accepted(planJson) || !bool(fields, "validate", true)) {
                return planJson;
            }
            return previewDiagnosticValidator.validate("propagateSafeDelete", planJson, model, false);
        } catch (IOException error) {
            return refusalJson("deletion_failed", "Propagating safe delete failed: " + error.getMessage());
        }
    }

    /** V3 dead-code scan (refactor-feature-plan-V3.md §7.5): produces candidates only and never mutates files. */
    private String findDeadCodeJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before deletion.findDeadCode.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        // B01: absent-only injection of java_refactor.v3.deletion defaults; an explicit request value always wins.
        fields = injectV3DeletionDefaults(fields);
        io.serena.javarefactor.v3.deletion.DeadCodeAnalyzer.Options options =
                new io.serena.javarefactor.v3.deletion.DeadCodeAnalyzer.Options(
                        bool(fields, "includeTests", false),
                        str(fields, "publicApiPolicy", "keep"),
                        str(fields, "scope", "project"));
        try {
            return new io.serena.javarefactor.v3.deletion.DeadCodeAnalyzer().analyze(model, options);
        } catch (IOException error) {
            return refusalJson("dead_code_failed", "Dead-code analysis failed: " + error.getMessage());
        }
    }

    /** V3 extract class (refactor-feature-plan-V3.md §8): pulls selected members into a new collaborator class. */
    private String extractClassJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before classRefactor.extractClass.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        // B01: absent-only injection of java_refactor.v3.class_refactors defaults; explicit request values win.
        fields = injectV3ClassRefactorDefaults(fields);
        String planJson = new io.serena.javarefactor.v3.classops.ExtractClassPlanner(
                java.nio.file.Path.of(projectRoot), model).plan(fields);
        if (!accepted(planJson) || !bool(fields, "validate", true)) {
            return planJson;
        }
        return previewDiagnosticValidator.validate("extractClass", planJson, model, false);
    }

    /** V3 extract superclass (refactor-feature-plan-V3.md §9): hoists common members into a new superclass. */
    private String extractSuperclassJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before classRefactor.extractSuperclass.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        // B01: absent-only injection of java_refactor.v3.class_refactors defaults; explicit request values win.
        fields = injectV3ClassRefactorDefaults(fields);
        String planJson = new io.serena.javarefactor.v3.classops.ExtractSuperclassPlanner(
                java.nio.file.Path.of(projectRoot), model).plan(fields);
        if (!accepted(planJson) || !bool(fields, "validate", true)) {
            return planJson;
        }
        return previewDiagnosticValidator.validate("extractSuperclass", planJson, model, false);
    }

    /** V3 replace inheritance with delegation (refactor-feature-plan-V3.md §10). */
    private String replaceInheritanceWithDelegationJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized",
                    "initialize must be called before classRefactor.replaceInheritanceWithDelegation.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        // B01: absent-only injection of java_refactor.v3.class_refactors defaults; explicit request values win.
        fields = injectV3ClassRefactorDefaults(fields);
        String planJson = new io.serena.javarefactor.v3.classops.ReplaceInheritanceWithDelegationPlanner(
                java.nio.file.Path.of(projectRoot), model).plan(fields);
        if (!accepted(planJson) || !bool(fields, "validate", true)) {
            return planJson;
        }
        return previewDiagnosticValidator.validate("replaceInheritanceWithDelegation", planJson, model, false);
    }

    /** V3 convert anonymous class to lambda (refactor-feature-plan-V3.md §12). */
    private String anonymousToLambdaJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before conversions.anonymousToLambda.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        String planJson = new io.serena.javarefactor.v3.conversions.AnonymousToLambdaPlanner(
                java.nio.file.Path.of(projectRoot), model).plan(fields);
        if (!accepted(planJson) || !bool(fields, "validate", true)) {
            return planJson;
        }
        return previewDiagnosticValidator.validate("convertAnonymousToLambda", planJson, model, false);
    }

    /** V3 convert lambda to method reference (refactor-feature-plan-V3.md §13). */
    private String lambdaToMethodReferenceJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized",
                    "initialize must be called before conversions.lambdaToMethodReference.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        String planJson = new io.serena.javarefactor.v3.conversions.LambdaToMethodReferencePlanner(
                java.nio.file.Path.of(projectRoot), model).plan(fields);
        if (!accepted(planJson) || !bool(fields, "validate", true)) {
            return planJson;
        }
        return previewDiagnosticValidator.validate("convertLambdaToMethodReference", planJson, model, false);
    }

    /** V3 generalized (multi-statement) inline method (refactor-feature-plan-V3.md §11). */
    private String deepInlineMethodJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before inlineRefactor.deepInlineMethod.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        // Inject maxCallSites from java_refactor.v3.inline.max_call_sites as an absent-only default so the planner's
        // intField("maxCallSites", DEFAULT_MAX_CALL_SITES) picks up the configured limit when the caller does not supply
        // an explicit per-request override.
        fields = injectV3InlineDefaults(fields);
        String planJson = new io.serena.javarefactor.v3.inline.DeepInlineMethodPlanner(
                java.nio.file.Path.of(projectRoot), model).plan(fields);
        if (!accepted(planJson) || !bool(fields, "validate", true)) {
            return planJson;
        }
        return previewDiagnosticValidator.validate("deepInlineMethod", planJson, model, false);
    }

    /**
     * Absent-only injection of V3 inline config defaults into the request fields for
     * {@code inlineRefactor.deepInlineMethod}. Reads {@code java_refactor.v3.inline.max_call_sites} and, when the
     * caller did not supply {@code maxCallSites} on the request, adds it so the planner's intField default is the
     * configured value rather than the hard-coded fallback.
     */
    private Map<String, Object> injectV3InlineDefaults(Map<String, Object> fields) {
        if (fields.containsKey("maxCallSites")) {
            return fields;
        }
        Map<String, Object> config = effectiveConfigurationMap();
        if (config.isEmpty()) {
            return fields;
        }
        Map<?, ?> v3 = v3ConfigSection(config);
        Object inlineSection = v3.get("inline");
        Map<?, ?> inline = inlineSection instanceof Map<?, ?> map ? map : Map.of();
        Object maxCallSites = inline.get("max_call_sites");
        if (maxCallSites == null) {
            maxCallSites = inline.get("maxCallSites");
        }
        if (maxCallSites == null) {
            return fields;
        }
        Map<String, Object> effective = new LinkedHashMap<>(fields);
        effective.put("maxCallSites", maxCallSites);
        return effective;
    }

    /**
     * The {@code java_refactor.v3.<section>} sub-map (snake_case or camelCase key), or empty when the config has no V3
     * block or no such section. Mirrors {@link #v3ConfigSection(Map)} but descends one level further to a named section.
     */
    private Map<?, ?> v3Section(String snakeKey, String camelKey) {
        Map<String, Object> config = effectiveConfigurationMap();
        if (config.isEmpty()) {
            return Map.of();
        }
        Map<?, ?> v3 = v3ConfigSection(config);
        Object section = v3.get(snakeKey);
        if (!(section instanceof Map<?, ?>)) {
            section = v3.get(camelKey);
        }
        return section instanceof Map<?, ?> map ? map : Map.of();
    }

    /**
     * Absent-only injection of V3 deletion config defaults ({@code java_refactor.v3.deletion}) into the request fields
     * for {@code deletion.propagateSafeDelete} and {@code deletion.findDeadCode}. An explicit per-request value always
     * wins; only an unset field receives the configured default. Reads {@code include_tests_default},
     * {@code max_cascade_depth}, and {@code public_api_policy} (snake_case or camelCase) so the handlers' hard-coded
     * fallbacks become the configured values rather than fixed constants.
     */
    private Map<String, Object> injectV3DeletionDefaults(Map<String, Object> fields) {
        Map<?, ?> deletion = v3Section("deletion", "deletion");
        if (deletion.isEmpty()) {
            return fields;
        }
        Map<String, Object> effective = new LinkedHashMap<>(fields);
        copyDefault(effective, deletion, "includeTests", "include_tests_default", "includeTestsDefault",
                "include_tests", "includeTests");
        copyDefault(effective, deletion, "maxCascadeDepth", "max_cascade_depth", "maxCascadeDepth");
        copyDefault(effective, deletion, "publicApiPolicy", "public_api_policy", "publicApiPolicy");
        return effective;
    }

    /**
     * Absent-only injection of V3 class-refactor config defaults ({@code java_refactor.v3.class_refactors}) into the
     * request fields for {@code classRefactor.*}. An explicit per-request value always wins. Reads
     * {@code leave_delegates_default} → {@code leaveDelegateMethods} and {@code allow_public_api_change} →
     * {@code confirmPublicApiChange} (snake_case or camelCase), so the configured policy seeds the planners' field reads
     * when the caller did not supply an explicit override.
     */
    private Map<String, Object> injectV3ClassRefactorDefaults(Map<String, Object> fields) {
        Map<?, ?> classRefactors = v3Section("class_refactors", "classRefactors");
        if (classRefactors.isEmpty()) {
            return fields;
        }
        Map<String, Object> effective = new LinkedHashMap<>(fields);
        copyDefault(effective, classRefactors, "leaveDelegateMethods", "leave_delegates_default",
                "leaveDelegatesDefault", "leave_delegate_methods", "leaveDelegateMethods");
        copyDefault(effective, classRefactors, "confirmPublicApiChange", "allow_public_api_change",
                "allowPublicApiChange", "confirm_public_api_change", "confirmPublicApiChange");
        return effective;
    }

    /**
     * Enforces the {@code java_refactor.v3.recipes} source policy at the handler level (the planner does not see config):
     * a built-in recipe ({@code recipeId}) is refused when {@code builtins_enabled=false}, and an inline user-supplied
     * recipe ({@code recipe} object) is refused when {@code allow_user_recipes=false}. Returns a structured refusal JSON
     * when the request's recipe source is disabled, or {@code null} when it may proceed. An absent flag (the shipped
     * default) leaves the source enabled, matching the "unset never disables" semantics of the V3 gates.
     */
    private String recipeSourceRefusal(Map<String, Object> fields) {
        Map<?, ?> recipes = v3Section("recipes", "recipes");
        if (recipes.isEmpty()) {
            return null;
        }
        Object recipeId = fields.get("recipeId");
        boolean usesBuiltin = recipeId != null && !recipeId.toString().isBlank();
        boolean usesInline = !usesBuiltin && fields.get("recipe") instanceof Map<?, ?>;
        if (usesBuiltin && recipes.containsKey("builtins_enabled")
                && !boolValue(recipes.get("builtins_enabled"), true)) {
            return refusalJson("operation_disabled",
                    "Built-in migration recipes are disabled by configuration "
                            + "(java_refactor.v3.recipes.builtins_enabled is false).");
        }
        if (usesInline && recipes.containsKey("allow_user_recipes")
                && !boolValue(recipes.get("allow_user_recipes"), true)) {
            return refusalJson("operation_disabled",
                    "User-supplied migration recipes are disabled by configuration "
                            + "(java_refactor.v3.recipes.allow_user_recipes is false).");
        }
        return null;
    }

    /** V3 scan for API-migration opportunities (refactor-feature-plan-V3.md §14): preview-only, no edits. */
    private String scanMigrationOpportunitiesJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized",
                    "initialize must be called before recipes.scanMigrationOpportunities.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        // B01: enforce java_refactor.v3.recipes source policy (builtins_enabled / allow_user_recipes).
        String recipeRefusal = recipeSourceRefusal(fields);
        if (recipeRefusal != null) {
            return recipeRefusal;
        }
        return new io.serena.javarefactor.v3.recipes.RecipeEngine(java.nio.file.Path.of(projectRoot), model).scan(fields);
    }

    /** V3 apply an API-migration recipe (refactor-feature-plan-V3.md §14): javac-validated workspaceEdit preview. */
    private String applyRecipeJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before recipes.applyRecipe.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        // B01: enforce java_refactor.v3.recipes source policy (builtins_enabled / allow_user_recipes).
        String recipeRefusal = recipeSourceRefusal(fields);
        if (recipeRefusal != null) {
            return recipeRefusal;
        }
        String planJson = new io.serena.javarefactor.v3.recipes.RecipeEngine(
                java.nio.file.Path.of(projectRoot), model).apply(fields);
        if (!accepted(planJson) || !bool(fields, "validate", true)) {
            return planJson;
        }
        return previewDiagnosticValidator.validate("applyRecipe", planJson, model, false);
    }

    /**
     * V3 resource-reference SPI (refactor-feature-plan-V3.md §15): read-only scan for references to a Java type/package
     * in non-Java resource files. No edits are produced, so there is nothing to diagnostic-validate.
     */
    private String findResourceReferencesJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before resources.findReferences.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        return new io.serena.javarefactor.v3.resources.ResourceReferenceScanner(
                java.nio.file.Path.of(projectRoot), model, graphCacheLimits().maxResourceFileBytes())
                .findReferences(fields);
    }

    /**
     * V3 resource SPI (refactor-feature-plan-V3.md §15, "planEdits" half): plans the SAFE in-place resource edits and
     * file renames for a set of moved types/packages, using the same unified {@link
     * io.serena.javarefactor.v3.resources.ResourcePlanner} the package rename/move planners drive internally.
     */
    private String planResourceEditsJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before resources.planEdits.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        return new io.serena.javarefactor.v3.resources.ResourceEditPlanner(
                java.nio.file.Path.of(projectRoot), model, graphCacheLimits().maxResourceFileBytes())
                .planEdits(fields);
    }

    /**
     * V3 framework SPI (refactor-feature-plan-V3.md §16): read-only detection of which frameworks are present, by exact
     * compiler-resolved annotation facts. No edits are produced.
     */
    private String detectFrameworksJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before frameworks.detect.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        return new io.serena.javarefactor.v3.frameworks.FrameworkScanner(
                java.nio.file.Path.of(projectRoot), model).detect(fields);
    }

    /**
     * V3 framework SPI (refactor-feature-plan-V3.md §16): read-only scan for framework-significant references to a target
     * type (its framework-annotated declaration/members, and framework annotations naming it). No edits are produced.
     */
    private String findFrameworkReferencesJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before frameworks.findReferences.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        return new io.serena.javarefactor.v3.frameworks.FrameworkScanner(
                java.nio.file.Path.of(projectRoot), model).findReferences(fields);
    }

    /**
     * V3 framework SPI (refactor-feature-plan-V3.md §16): the transformation-participant half. Given a pending change
     * ({@code changeKind} one of {@code safeDelete}/{@code renameType}/{@code renamePackage}/{@code deadCodeScan}, with
     * an optional {@code target} and {@code newName}), runs every framework plugin's
     * {@code participate(SymbolChange, TransformationContext)} hook and returns the merged contribution: deletion
     * vetoes ({@code blocks}), review-required {@code warnings}, framework-owned {@code resourceEdits} descriptions, and
     * the reachability {@code roots} the frameworks contribute. Read-only: produces no file edits itself (the planner
     * seams fold these contributions into their plans).
     */
    private String participateFrameworksJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before frameworks.participate.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        String changeKind = str(fields, "changeKind", "");
        String target = str(fields, "target", "");
        String newName = str(fields, "newName", "");
        io.serena.javarefactor.v3.frameworks.SymbolChange change = switch (changeKind) {
            case "safeDelete" -> io.serena.javarefactor.v3.frameworks.SymbolChange.safeDelete(target);
            case "renameType" -> io.serena.javarefactor.v3.frameworks.SymbolChange.renameType(target, newName);
            case "renamePackage" -> io.serena.javarefactor.v3.frameworks.SymbolChange.renamePackage(target, newName);
            case "deadCodeScan" -> io.serena.javarefactor.v3.frameworks.SymbolChange.deadCodeScan();
            default -> null;
        };
        if (change == null) {
            return refusalJson("framework_change_unrecognized",
                    "changeKind must be one of safeDelete, renameType, renamePackage, deadCodeScan.");
        }
        try {
            io.serena.javarefactor.v3.frameworks.FrameworkParticipationCoordinator.Result result =
                    new io.serena.javarefactor.v3.frameworks.FrameworkParticipationCoordinator()
                            .participate(model, change);
            StringBuilder blocks = new StringBuilder("[");
            boolean first = true;
            for (io.serena.javarefactor.v3.frameworks.FrameworkParticipation.Block block : result.blocks()) {
                if (!first) {
                    blocks.append(",");
                }
                first = false;
                blocks.append("{\"symbol\":").append(JsonUtil.quote(block.symbol()))
                        .append(",\"reason\":").append(JsonUtil.quote(block.reason())).append("}");
            }
            blocks.append("]");
            // B07: alongside the human-readable resourceEdits descriptions, emit the STRUCTURED framework resource edits
            // so a caller can apply the CONCRETE, parse-verified TextEdits the coordinator proved (Spring <bean class>,
            // exact dotted FQN tokens, JPA persistence.xml/orm.xml <class>) and distinguish them from the manual-review
            // markers that still require human attention. Each entry names its target resource, kind, and disposition; a
            // concrete entry additionally carries the {path, startOffset, endOffset, newText} of the proven edit.
            StringBuilder frameworkResourceEdits = new StringBuilder("[");
            boolean firstEdit = true;
            for (io.serena.javarefactor.v3.frameworks.FrameworkResourceEdit edit : result.resourceEdits()) {
                if (!firstEdit) {
                    frameworkResourceEdits.append(",");
                }
                firstEdit = false;
                frameworkResourceEdits.append("{\"targetResource\":").append(JsonUtil.quote(edit.targetResource()))
                        .append(",\"kind\":").append(JsonUtil.quote(edit.kind().name()))
                        .append(",\"manualReviewRequired\":").append(edit.manualReviewRequired())
                        .append(",\"description\":").append(JsonUtil.quote(edit.description()));
                io.serena.javarefactor.edits.PlannerSupport.TextEdit textEdit = edit.textEdit();
                if (textEdit != null) {
                    frameworkResourceEdits.append(",\"textEdit\":{\"path\":")
                            .append(JsonUtil.quote(PlannerSupport.relative(
                                    java.nio.file.Path.of(projectRoot).toAbsolutePath().normalize(), textEdit.file())))
                            .append(",\"startOffset\":").append(textEdit.startOffset())
                            .append(",\"endOffset\":").append(textEdit.endOffset())
                            .append(",\"newText\":").append(JsonUtil.quote(textEdit.newText()))
                            .append(",\"kind\":").append(JsonUtil.quote(textEdit.kind())).append("}");
                }
                frameworkResourceEdits.append("}");
            }
            frameworkResourceEdits.append("]");
            return "{"
                    + "\"accepted\":true,"
                    + "\"operation\":\"participateFrameworks\","
                    + "\"changeKind\":" + JsonUtil.quote(changeKind) + ","
                    + "\"blocks\":" + blocks + ","
                    + "\"warnings\":" + JsonUtil.array(result.warnings()) + ","
                    + "\"resourceEdits\":" + JsonUtil.array(result.frameworkBoundaryChanges()) + ","
                    + "\"frameworkResourceEdits\":" + frameworkResourceEdits + ","
                    + "\"roots\":" + JsonUtil.array(result.roots()) + ","
                    + "\"stats\":{\"blocks\":" + result.blocks().size()
                    + ",\"warnings\":" + result.warnings().size()
                    + ",\"resourceEdits\":" + result.resourceEdits().size()
                    + ",\"roots\":" + result.roots().size() + "}"
                    + "}";
        } catch (IOException error) {
            return refusalJson("framework_participate_failed",
                    "Framework participation failed: " + error.getMessage());
        }
    }

    /**
     * Stateless impact fact-sheet (refactor-feature-plan-V3.md §G011): given a list of touched project-relative paths,
     * emits source-root classification, the top-level types declared in those files, all incoming semantic references to
     * those types (split by main/test source set), and resource-file references. Never mutates files.
     */
    private String impactFactsJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before impact.facts.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        // Parse touchedPaths: accept a single string or a JSON array of strings.
        Object raw = fields.get("touchedPaths");
        List<String> touchedPaths = new java.util.ArrayList<>();
        if (raw instanceof String s) {
            touchedPaths.add(s);
        } else if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String p) {
                    touchedPaths.add(p);
                }
            }
        }
        try {
            return new io.serena.javarefactor.compiler.ImpactFactsAnalyzer().analyze(model, touchedPaths);
        } catch (java.io.IOException error) {
            return refusalJson("impact_facts_failed", "Impact analysis failed: " + error.getMessage());
        }
    }

    /**
     * Builds (or returns the cached) unified V3 {@link io.serena.javarefactor.v3.graph.TransformationGraph} for the
     * current project revision (refactor-feature-plan-V3.md §1.2/§3). The graph composes the build layout, Java symbols,
     * type hierarchy, call graph, provider-backed resource references, and test graph from the real compiler/build/
     * resource models, and is cached per revision by {@link io.serena.javarefactor.v3.graph.GraphInvalidation} so the
     * impact/delete/report consumers share one materialization. Never mutates files.
     */
    private String graphBuildJson(Map<String, Object> fields) {
        if (!initialized || projectRoot == null) {
            return refusalJson("not_initialized", "initialize must be called before graph.build.");
        }
        JavaProjectModel model = discoverSemanticPlanningModel(fields);
        String gate = modelGateRefusal(model, false);
        if (gate != null) {
            return gate;
        }
        try {
            return io.serena.javarefactor.v3.graph.GraphInvalidation.INSTANCE.get(model, graphCacheLimits()).toJson();
        } catch (java.io.IOException error) {
            return refusalJson("graph_build_failed", "Transformation graph build failed: " + error.getMessage());
        }
    }

    /**
     * Resolves the configurable transformation-graph cache + resource-scan limits from the effective
     * {@code java_refactor.v3.graph} config block (R05 acceptance #2). An absent/empty block yields the defaults.
     */
    private io.serena.javarefactor.v3.graph.GraphCacheLimits graphCacheLimits() {
        Map<?, ?> v3 = v3ConfigSection(effectiveConfigurationMap());
        Object graph = v3.get("graph");
        Map<?, ?> graphConfig = graph instanceof Map<?, ?> map ? map : Map.of();
        return io.serena.javarefactor.v3.graph.GraphCacheLimits.fromGraphConfig(graphConfig);
    }

    private static List<io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner.RootSpec> parseDeletionRoots(
            Object rootsValue) {
        List<io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner.RootSpec> roots =
                new java.util.ArrayList<>();
        if (rootsValue instanceof String symbol) {
            roots.add(io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner.RootSpec.ofSymbol(symbol));
            return roots;
        }
        if (!(rootsValue instanceof List<?> list)) {
            return roots;
        }
        for (Object item : list) {
            if (item instanceof String symbol) {
                roots.add(io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner.RootSpec.ofSymbol(symbol));
            } else if (item instanceof Map<?, ?> map) {
                Object symbol = map.get("symbol");
                if (symbol instanceof String symbolText && !symbolText.isBlank()) {
                    roots.add(io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner.RootSpec
                            .ofSymbol(symbolText));
                    continue;
                }
                Object relativePath = map.get("relativePath");
                Object line = map.get("line");
                Object column = map.get("column");
                if (relativePath instanceof String path && line instanceof Number lineNumber
                        && column instanceof Number columnNumber) {
                    roots.add(io.serena.javarefactor.v3.deletion.PropagatingSafeDeletePlanner.RootSpec
                            .ofPosition(path, lineNumber.intValue(), columnNumber.intValue()));
                }
            }
        }
        return roots;
    }

    private JavaProjectModel discoverSemanticPlanningModel(Map<String, Object> fields) {
        JavaProjectModel projectModel = discoverModel();
        String relativePath = str(fields, "relativePath");
        // Skip the eager planning-index open when discovery/validation already flagged the model unusable (errors are
        // present). The downstream gate (modelGateRefusal) turns that into a structured refusal, so opening a javac index
        // here would only re-trigger the same failure — and a non-IOException one (e.g. an unsupported `--release N` newer
        // than this JDK throws a RuntimeException from javac option handling) would escape as an opaque malformed-request
        // error instead of the intended structured refusal.
        if (relativePath != null && !relativePath.isBlank() && projectModel.errors().isEmpty()) {
            try (SemanticIndex ignored = SemanticIndex.open(projectModel, relativePath)) {
                // Opening javac semantic analysis here makes the extracted build model part of planning, not only post-edit validation.
            } catch (IOException error) {
                throw new IllegalStateException("javac semantic planning failed for " + relativePath + ": " + error.getMessage(), error);
            }
        }
        return projectModel;
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
        if (apply && projectModel.classpathUnproven() && !projectModel.allowIncompleteAnalysis()) {
            // G003: an unproven dependency classpath is a first-class model-incompleteness signal, INDEPENDENT of javac
            // diagnostics. It can leave javac clean on the edited file while corrupting overload resolution / type
            // hierarchy used in semantic planning elsewhere, so apply is refused on the same allow_incomplete_analysis
            // override as the diagnostics gate. Preview stays available (with a model-safety warning).
            return refusalJson("classpath_unproven_apply_refused",
                    "Apply was refused because the build tool could not prove the dependency classpath for source set(s) "
                            + String.join(", ", projectModel.unprovenClasspathSourceSets())
                            + " (e.g. Maven dependency:build-classpath failed to resolve an external dependency, or Gradle "
                            + "could not resolve a source set's compile classpath), so semantic "
                            + "resolution cannot be trusted for a mutating edit even when javac looks clean on the edited "
                            + "file. Preview remains available. Fix the dependency resolution (e.g. build/install the "
                            + "project so its dependencies resolve, or run online), or set "
                            + "java_refactor.allow_incomplete_analysis: true to opt in to applying against an "
                            + "incompletely analyzed project (newly introduced compiler errors are still rejected).");
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
