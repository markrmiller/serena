package io.serena.javarefactor.session;

import io.serena.javarefactor.protocol.Json;
import io.serena.javarefactor.protocol.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First-class semantic target identity for a refactor session (feature plan Blocker&nbsp;4).
 *
 * <p>A session captures its target as the canonical {@code SemanticKey} the planner emitted under the preview's
 * {@code target.semanticKey} — the same key the {@code SemanticTargetGate} resolver proves — rather than by scraping
 * the first {@code semanticKey} found anywhere in the edit tree (which could be a call-site reference, not the symbol
 * being refactored). The {@link #canonical()} key is offset-independent for types, methods, and fields, so it is the
 * stable apply-time re-resolution axis: on apply the planner is re-run (re-resolving the caller's position through
 * javac), and the session refuses with {@code target_identity_changed} when the re-resolved canonical key no longer
 * equals the previewed one.
 */
public record SessionTarget(String canonical, String kind, String owner, String name, String signature) {

    public SessionTarget {
        if (canonical == null || canonical.isBlank()) {
            throw new IllegalArgumentException("Refactor session target requires a non-blank semantic canonical key.");
        }
        kind = kind == null ? "" : kind;
        owner = owner == null ? "" : owner;
        name = name == null ? "" : name;
        signature = signature == null ? "" : signature;
    }

    /** Parses the first-class target identity from a planner preview, or {@code null} when none is present. */
    public static SessionTarget parseOrNull(String previewJson) {
        try {
            return fromKeyValue(locateSemanticKey(Json.parseObject(previewJson)));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Parses the first-class target identity, refusing when the preview carries no stable semantic target. */
    public static SessionTarget parse(String previewJson) {
        SessionTarget target = parseOrNull(previewJson);
        if (target == null) {
            throw new IllegalArgumentException("Refactor session preview is missing target.semanticKey.");
        }
        return target;
    }

    /**
     * Stable identity string retained for the session envelope and the apply re-resolution guard. The canonical key is
     * the only comparison axis; the {@code semanticCanonical=}/{@code semanticKey=} framing is kept for compatibility
     * with existing session-envelope assertions.
     */
    public String identityString(String operation) {
        return operation + "|semanticCanonical=" + canonical + "|semanticKey=" + canonical;
    }

    /** True iff {@code other} resolves to the same canonical semantic key. */
    public boolean matches(SessionTarget other) {
        return other != null && canonical.equals(other.canonical);
    }

    /** First-class serialization for session metadata payloads. */
    public String toJson() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("canonical", JsonUtil.quote(canonical));
        fields.put("kind", JsonUtil.quote(kind));
        fields.put("owner", JsonUtil.quote(owner));
        fields.put("name", JsonUtil.quote(name));
        fields.put("signature", JsonUtil.quote(signature));
        return JsonUtil.object(fields);
    }

    /**
     * Anchored lookup of the previewed target's semantic key: only the preview's own declared target is honored, never
     * an arbitrary nested reference key elsewhere in the edit tree. Falls back to the {@code semanticTarget} envelope
     * some planners emit, which carries the identical key object.
     */
    private static Object locateSemanticKey(Map<String, Object> preview) {
        Object target = preview.get("target");
        if (target instanceof Map<?, ?> targetMap) {
            Object key = targetMap.get("semanticKey");
            if (key != null) {
                return key;
            }
        }
        Object semanticTarget = preview.get("semanticTarget");
        if (semanticTarget instanceof Map<?, ?> semanticTargetMap) {
            Object identity = semanticTargetMap.get("identity");
            if (identity != null) {
                return identity;
            }
            Object key = semanticTargetMap.get("semanticKey");
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    private static SessionTarget fromKeyValue(Object semanticKey) {
        if (semanticKey instanceof Map<?, ?> keyMap) {
            String canonical = stringField(keyMap, "canonical");
            if (canonical == null || canonical.isBlank()) {
                return null;
            }
            return new SessionTarget(
                    canonical,
                    stringField(keyMap, "kind"),
                    stringField(keyMap, "owner"),
                    stringField(keyMap, "name"),
                    stringField(keyMap, "signature"));
        }
        if (semanticKey == null) {
            return null;
        }
        String canonical = semanticKey.toString();
        return canonical.isBlank() ? null : new SessionTarget(canonical, "", "", "", "");
    }

    private static String stringField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }
}
