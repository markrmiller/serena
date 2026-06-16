package io.serena.javarefactor.session;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.Json;
import io.serena.javarefactor.protocol.JsonUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns stateful preview sessions for the Java refactor sidecar. */
public final class RefactorSessionManager {
    private static final int DEFAULT_MAX_LIVE_SESSIONS = 16;
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(30);
    // Design §20 default: an apply must match the session's captured project revision. Stored here so the apply path can
    // consult it; enforcement remains driven by the caller-supplied expectedProjectRevision token (see Main.applySession
    // and expectedRevisionMismatch) so existing callers that omit the token are not broken.
    private static final boolean DEFAULT_REQUIRE_REVISION_MATCH_ON_APPLY = true;

    // Session storage and lifecycle policy (TTL eviction + max-open-session capacity) are owned by the store; the
    // manager keeps maxLiveSessions/sessionTtl purely as the parsed config it pushes down via store.configureLimits.
    private final SessionStore store;
    // G001: incremental-apply state. Each session id maps to the set of plan unit ids (edit/file-operation ids, see
    // SessionSelection) that earlier partial applies have already surfaced for application. "Remaining" is every plan
    // unit not in this set; a later partial apply targets that remainder. Kept beside the (immutable) session record so
    // the session itself stays a value type.
    private final Map<String, java.util.Set<String>> appliedUnitsBySession = new java.util.concurrent.ConcurrentHashMap<>();
    private int maxLiveSessions = DEFAULT_MAX_LIVE_SESSIONS;
    private Duration sessionTtl = DEFAULT_SESSION_TTL;
    private boolean requireRevisionMatchOnApply = DEFAULT_REQUIRE_REVISION_MATCH_ON_APPLY;

    public RefactorSessionManager() {
        this(new InMemorySessionStore());
    }

    /** Package-visible constructor allowing tests to inject an alternate {@link SessionStore}. */
    RefactorSessionManager(SessionStore store) {
        this.store = store;
        this.store.configureLimits(maxLiveSessions, sessionTtl);
    }

    /**
     * Creates and stores a session for a planner result.
     *
     * @param operation the planned operation name
     * @param fields flattened request fields used to identify the target
     * @param model project model used by the planner
     * @param previewJson planner preview JSON
     * @return the stored session
     */
    public void configure(Map<String, Object> configuration) {
        maxLiveSessions = DEFAULT_MAX_LIVE_SESSIONS;
        sessionTtl = DEFAULT_SESSION_TTL;
        requireRevisionMatchOnApply = DEFAULT_REQUIRE_REVISION_MATCH_ON_APPLY;
        Object sessionsConfig = configuration == null ? null : configuration.get("sessions");
        if (!(sessionsConfig instanceof Map<?, ?> sessionsFields)) {
            store.configureLimits(maxLiveSessions, sessionTtl);
            return;
        }
        maxLiveSessions = positiveIntField(
                sessionsFields, DEFAULT_MAX_LIVE_SESSIONS, "maxOpenSessions", "max_open_sessions");
        int ttlMinutes = positiveIntField(
                sessionsFields, (int) DEFAULT_SESSION_TTL.toMinutes(), "sessionTtlMinutes", "session_ttl_minutes");
        sessionTtl = Duration.ofMinutes(ttlMinutes);
        // G003: the apply-time stale-revision guard is non-bypassable by design (see requireRevisionMatchOnApply()).
        // Rather than silently accepting a value that cannot affect behavior, refuse an explicit opt-out at config-load.
        if (!boolField(sessionsFields, DEFAULT_REQUIRE_REVISION_MATCH_ON_APPLY,
                "requireRevisionMatchOnApply", "require_revision_match_on_apply")) {
            throw new IllegalArgumentException(
                    "java_refactor.v2.sessions.require_revision_match_on_apply:false is not supported; the apply-time "
                            + "stale-revision guard is non-bypassable. Remove the key or set it to true.");
        }
        requireRevisionMatchOnApply = true;
        store.configureLimits(maxLiveSessions, sessionTtl);
    }

    /**
     * Reports the configured value of the {@code require_revision_match_on_apply} session key (design §20).
     *
     * <p>NOTE: the apply-time stale-revision guard ({@code Main} apply path: {@code session.revision().mismatch(current)})
     * is enforced UNCONDITIONALLY by design — refusing an apply whose project revision drifted since the preview is part
     * of the non-bypassable safety contract, and a value of {@code false} could only ever weaken that guarantee.
     * {@link #configure(Map)} therefore REJECTS an explicit {@code require_revision_match_on_apply:false} at config-load
     * (G003) instead of accepting a no-op; this getter consequently always returns {@code true} for a successfully
     * configured manager and exists to surface the (always-on) value for diagnostics.
     */
    public boolean requireRevisionMatchOnApply() {
        return requireRevisionMatchOnApply;
    }

    public RefactorSession createSession(String operation, Map<String, Object> fields, JavaProjectModel model, String previewJson)
            throws IOException {
        List<String> touchedFiles = touchedFiles(previewJson, fields);
        ProjectRevision revision = ProjectRevision.capture(model, touchedFiles);
        RefactorSession session = new RefactorSession(
                UUID.randomUUID().toString(),
                operation,
                fields,
                SessionTarget.parse(previewJson),
                revision,
                touchedFiles,
                previewJson,
                Instant.now());
        store.put(session);
        return session;
    }

    /** Returns the session, or {@code null} when no live session has that id. */
    public RefactorSession get(String sessionId) {
        return store.get(sessionId);
    }

    /** Cancels and removes the session. */
    public boolean cancel(String sessionId) {
        appliedUnitsBySession.remove(sessionId);
        return store.remove(sessionId);
    }

    /** Plan unit ids earlier partial applies of this session have already surfaced for application (G001). */
    public java.util.Set<String> appliedUnits(String sessionId) {
        java.util.Set<String> applied = appliedUnitsBySession.get(sessionId);
        return applied == null ? java.util.Set.of() : java.util.Set.copyOf(applied);
    }

    /** Records that {@code unitIds} were surfaced for application by a partial apply of the session (G001). */
    public void recordApplied(String sessionId, java.util.Set<String> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) {
            return;
        }
        appliedUnitsBySession
                .computeIfAbsent(sessionId, key -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .addAll(unitIds);
    }

    /** Number of live sessions. */
    public int size() {
        return store.size();
    }

    /** Extracts changed/created/deleted/renamed files from a planner preview. */
    public static List<String> touchedFiles(String previewJson, Map<String, Object> fields) {
        LinkedHashSet<String> touched = new LinkedHashSet<>();
        try {
            Map<String, Object> result = Json.parseObject(previewJson);
            Object edit = result.get("workspaceEdit");
            if (edit instanceof Map<?, ?> workspaceEdit) {
                collectChanges(workspaceEdit.get("changes"), touched);
                collectFileOperations(workspaceEdit.get("documentChanges"), touched);
                collectFileOperations(workspaceEdit.get("fileOperations"), touched);
            }
        } catch (RuntimeException ignored) {
            // Fall back to the request path below.
        }
        if (touched.isEmpty()) {
            addIfPresent(touched, stringField(fields, "path"));
            addIfPresent(touched, stringField(fields, "relativePath"));
            addIfPresent(touched, stringField(fields, "sourcePath"));
            addIfPresent(touched, stringField(fields, "targetPath"));
        }
        return List.copyOf(touched);
    }

    public static boolean hasStableSemanticTarget(String previewJson) {
        return SessionTarget.parseOrNull(previewJson) != null;
    }

    /**
     * The canonical-keyed identity string for a planner preview, used by apply-time re-resolution: apply re-runs the
     * planner and compares this against the session's stored identity, refusing when the canonical key moved.
     */
    public static String targetIdentity(String operation, Map<String, Object> fields, String previewJson) {
        return SessionTarget.parse(previewJson).identityString(operation);
    }

    /**
     * Session response envelope shared by create/get/apply session protocol methods.
     *
     * <p>The {@code plan}, {@code preview}, and top-level {@code edit} fields are all derived from the single
     * {@code planJson} passed in — Python applies {@code preview.workspaceEdit}, so the top-level {@code edit} is kept
     * byte-consistent with it by extracting it from the same plan rather than from a separately-stored create-time edit.
     * This is what makes the apply path honor Blocker 1: when {@code Main} passes the revalidated {@code currentPlan},
     * every surfaced edit reflects that revalidated plan, not the stored preview.
     */
    public static String sessionEnvelope(RefactorSession session, String planJson, String mode, String validationJson) {
        return "{"
                + "\"accepted\":true,"
                + "\"mode\":" + JsonUtil.quote(mode) + ","
                + "\"session\":" + session.metadataJson() + ","
                + "\"plan\":" + planJson + ","
                + "\"edit\":" + editJson(planJson) + ","
                + "\"preview\":" + planJson + ","
                + "\"validation\":" + validationJson
                + "}";
    }

    /**
     * Incremental (selection-based) session envelope (G001). Extends {@link #sessionEnvelope} with the incremental-apply
     * surface: {@code incremental:true}, whether the session is now {@code complete} (every plan unit applied), the
     * {@code selectionModel} (every plan unit with its stable id and applied flag), and the {@code remaining} unapplied
     * subset. The {@code plan}/{@code edit}/{@code preview} all carry the validated FILTERED plan (only the selected
     * units), so Python applies exactly the surfaced subset.
     */
    public static String incrementalSessionEnvelope(
            RefactorSession session, String planJson, String mode, String validationJson,
            String selectionModelJson, String remainingJson, boolean complete, String format,
            String pendingUnitIdsJson) {
        return "{"
                + "\"accepted\":true,"
                + "\"mode\":" + JsonUtil.quote(mode) + ","
                + "\"incremental\":true,"
                + "\"complete\":" + complete + ","
                + "\"format\":" + JsonUtil.quote(format) + ","
                + "\"session\":" + session.metadataJson() + ","
                + "\"plan\":" + planJson + ","
                + "\"edit\":" + editJson(planJson) + ","
                + "\"preview\":" + planJson + ","
                + "\"selectionModel\":" + selectionModelJson + ","
                + "\"remaining\":" + remainingJson + ","
                // G001: the unit ids this apply SURFACED but has not yet recorded as applied. Python echoes them back via
                // ackSessionApply strictly after its transactional commit succeeds; only then does session state advance.
                + "\"pendingUnitIds\":" + pendingUnitIdsJson + ","
                + "\"validation\":" + validationJson
                + "}";
    }

    /**
     * Post-commit acknowledgement envelope (G001). Records {@code unitIds} as applied for {@code sessionId} — strictly
     * after the client has committed them to disk — and returns the session's now-authoritative incremental state
     * (which units are applied, whether the session is {@code complete}, and the still-unapplied {@code remaining}
     * subset) derived from the recorded set rather than from any in-flight edit envelope.
     */
    public String ackSessionApplyJson(String sessionId, java.util.Set<String> unitIds) {
        RefactorSession session = get(sessionId);
        if (session == null) {
            return refusalJson("unknown_session", "No live refactor session for sessionId " + sessionId + ".");
        }
        recordApplied(sessionId, unitIds);
        java.util.Set<String> applied = appliedUnits(sessionId);
        SessionSelection.Resolution model = SessionSelection.describeApplied(session.previewJson(), applied);
        boolean complete = !model.refused() && model.complete();
        String remainingJson = model.refused() ? "[]" : model.remainingJson();
        StringBuilder appliedJson = new StringBuilder("[");
        boolean first = true;
        for (String id : applied) {
            if (!first) {
                appliedJson.append(',');
            }
            appliedJson.append(JsonUtil.quote(id));
            first = false;
        }
        appliedJson.append(']');
        return "{"
                + "\"accepted\":true,"
                + "\"acked\":true,"
                + "\"sessionId\":" + JsonUtil.quote(sessionId) + ","
                + "\"applied\":" + appliedJson + ","
                + "\"complete\":" + complete + ","
                + "\"remaining\":" + remainingJson
                + "}";
    }

    /** Refusal envelope for session protocol failures. */
    public static String refusalJson(String code, String message) {
        return "{"
                + "\"accepted\":false,"
                + "\"applied\":false,"
                + "\"refusal\":{"
                + "\"code\":" + JsonUtil.quote(code) + ","
                + "\"message\":" + JsonUtil.quote(message)
                + "}}";
    }

    public static String refusalJson(String operation, String code, String message) {
        return "{"
                + "\"accepted\":false,"
                + "\"applied\":false,"
                + "\"operation\":" + JsonUtil.quote(operation) + ","
                + "\"mode\":\"preview\","
                + "\"semanticTarget\":{\"operation\":" + JsonUtil.quote(operation) + "},"
                + "\"refusal\":{"
                + "\"code\":" + JsonUtil.quote(code) + ","
                + "\"message\":" + JsonUtil.quote(message)
                + "}}";
    }

    private static void collectChanges(Object value, LinkedHashSet<String> touched) {
        if (value instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                if (key instanceof String path) {
                    addIfPresent(touched, path);
                }
            }
            for (Object child : map.values()) {
                collectNestedPaths(child, touched);
            }
        } else {
            collectNestedPaths(value, touched);
        }
    }

    private static void collectNestedPaths(Object value, LinkedHashSet<String> touched) {
        if (value instanceof Map<?, ?> map) {
            collectPathFields(map, touched);
            for (Object child : map.values()) {
                collectNestedPaths(child, touched);
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                collectNestedPaths(child, touched);
            }
        }
    }

    private static void collectFileOperations(Object value, LinkedHashSet<String> touched) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    collectPathFields(map, touched);
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            collectPathFields(map, touched);
        }
    }

    private static void collectPathFields(Map<?, ?> map, LinkedHashSet<String> touched) {
        for (String key : List.of("path", "oldPath", "newPath", "sourcePath", "targetPath", "uri", "oldUri", "newUri")) {
            Object value = map.get(key);
            if (value instanceof String path) {
                addIfPresent(touched, path);
            }
        }
        Object textDocument = map.get("textDocument");
        if (textDocument instanceof Map<?, ?> textDocumentMap) {
            collectPathFields(textDocumentMap, touched);
        }
        Object edits = map.get("edits");
        if (edits instanceof List<?> editsList) {
            for (Object edit : editsList) {
                if (edit instanceof Map<?, ?> editMap) {
                    collectPathFields(editMap, touched);
                }
            }
        }
    }

    private static void addIfPresent(LinkedHashSet<String> touched, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        String path = rawPath;
        if (path.startsWith("file:")) {
            try {
                path = Path.of(new URI(path)).toString();
            } catch (IllegalArgumentException | URISyntaxException ignored) {
                // Keep the original URI-ish path when it cannot be decoded.
            }
        }
        touched.add(path);
    }

    private static String stringField(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? "" : value.toString();
    }

    private static String editJson(String previewJson) {
        try {
            Object edit = Json.parseObject(previewJson).get("workspaceEdit");
            return edit == null ? "null" : jsonValue(edit);
        } catch (RuntimeException ignored) {
            return "null";
        }
    }

    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return JsonUtil.quote(string);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, String> fields = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> fields.put(String.valueOf(entry.getKey()), jsonValue(entry.getValue())));
            return JsonUtil.object(fields);
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                values.add(jsonValue(item));
            }
            return "[" + String.join(",", values) + "]";
        }
        return JsonUtil.quote(value.toString());
    }

    /**
     * Optimistic-concurrency guard for applySession: when the caller pins an {@code expectedProjectRevision}, it must
     * match the revision the session captured at create time. The expected value may be the session's
     * {@code modelHash} string, or the session-metadata {@code projectRevision} object carrying a {@code modelHash}
     * field. Returns a human-readable mismatch reason, or {@code null} when the guard is absent or matches.
     */
    public static String expectedRevisionMismatch(RefactorSession session, Object expected) {
        String expectedHash = expectedModelHash(expected);
        if (expectedHash == null || expectedHash.isBlank()) {
            return null;
        }
        String actual = session.revision().modelHash();
        if (!expectedHash.equals(actual)) {
            return "expected project revision " + expectedHash
                    + " but the session was created against project revision " + actual;
        }
        return null;
    }

    private static String expectedModelHash(Object expected) {
        if (expected instanceof String text) {
            return text.trim();
        }
        if (expected instanceof Map<?, ?> map) {
            Object hash = map.get("modelHash");
            return hash == null ? null : hash.toString().trim();
        }
        return null;
    }

    /** Session validation report against a freshly captured revision. */
    public static String validationJson(RefactorSession session, ProjectRevision currentRevision) {
        String mismatch = session.revision().mismatch(currentRevision);
        List<String> errors = new ArrayList<>();
        if (mismatch != null) {
            errors.add(mismatch);
        }
        return "{"
                + "\"ready\":" + (mismatch == null) + ","
                + "\"errors\":" + JsonUtil.array(errors) + ","
                + "\"expectedRevision\":" + session.revision().toJson() + ","
                + "\"currentRevision\":" + currentRevision.toJson()
                + "}";
    }

    private static boolean boolField(Map<?, ?> fields, boolean defaultValue, String... keys) {
        for (String key : keys) {
            Object value = fields.get(key);
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value instanceof String text) {
                String trimmed = text.trim();
                if ("true".equalsIgnoreCase(trimmed)) {
                    return true;
                }
                if ("false".equalsIgnoreCase(trimmed)) {
                    return false;
                }
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static int positiveIntField(Map<?, ?> fields, int defaultValue, String... keys) {
        for (String key : keys) {
            Object value = fields.get(key);
            if (value instanceof Number number) {
                int parsed = number.intValue();
                return parsed > 0 ? parsed : defaultValue;
            }
            if (value instanceof String text) {
                try {
                    int parsed = Integer.parseInt(text.trim());
                    return parsed > 0 ? parsed : defaultValue;
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }
}
