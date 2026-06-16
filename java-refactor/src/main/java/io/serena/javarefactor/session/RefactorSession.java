package io.serena.javarefactor.session;

import io.serena.javarefactor.protocol.JsonUtil;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A preview-first Java refactor session whose edit can be revalidated before Python applies it. */
public record RefactorSession(
        String sessionId,
        String operation,
        Map<String, Object> requestFields,
        SessionTarget target,
        ProjectRevision revision,
        List<String> touchedFiles,
        String planJson,
        Instant createdAt) {
    public RefactorSession {
        // A request field carried over the JSON protocol may be present with a null value (an unset optional the Python
        // tool forwards explicitly, e.g. introduceParameter's selectedExpression/parameterType). Map.copyOf rejects null
        // values with an NPE, so snapshot into a null-tolerant unmodifiable map that simply drops those absent entries —
        // a null value carries no targeting information the session needs.
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        requestFields.forEach((key, value) -> {
            if (value != null) {
                snapshot.put(key, value);
            }
        });
        requestFields = java.util.Collections.unmodifiableMap(snapshot);
        touchedFiles = List.copyOf(touchedFiles);
    }

    /** Compatibility accessor for callers that still refer to the preview payload. */
    public String previewJson() {
        return planJson;
    }

    /**
     * Canonical-keyed identity string for the stored semantic target, consumed by the apply-time re-resolution guard
     * and the session envelope. Derived from the first-class {@link SessionTarget} so the comparison always runs
     * against the verified canonical key.
     */
    public String targetIdentity() {
        return target.identityString(operation);
    }

    /** True iff a freshly re-resolved target identity is the SAME semantic element as the one stored at preview time. */
    public boolean targetMatches(SessionTarget current) {
        return target.matches(current);
    }

    /** Session metadata as JSON, excluding the full plan/edit payloads. */
    public String metadataJson() {
        return "{"
                + "\"sessionId\":" + JsonUtil.quote(sessionId) + ","
                + "\"operation\":" + JsonUtil.quote(operation) + ","
                + "\"targetIdentity\":" + JsonUtil.quote(targetIdentity()) + ","
                + "\"semanticTarget\":" + target.toJson() + ","
                + "\"createdAt\":" + JsonUtil.quote(createdAt.toString()) + ","
                + "\"touchedFiles\":" + JsonUtil.array(touchedFiles) + ","
                + "\"projectRevision\":" + revision.toJson()
                + "}";
    }
}
