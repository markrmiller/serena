package io.serena.javarefactor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the first-class refactor-session semantic target identity (feature plan Blocker&nbsp;4): the
 * session stores the canonical {@code target.semanticKey} the planner proved, re-resolves it on apply, and refuses when
 * the canonical key moved.
 */
class SessionTargetIdentityTest {

    private static final String FIELD_PREVIEW =
            """
            {
              "accepted": true,
              "operation": "semanticRename",
              "target": {
                "semanticKey": {
                  "kind": "FIELD",
                  "owner": "App",
                  "name": "value",
                  "signature": "int",
                  "canonical": "App#value"
                },
                "span": {"file": "App.java", "startOffset": 24, "endOffset": 29}
              },
              "workspaceEdit": {"changes": {"App.java": [{"newText": "count"}]}}
            }
            """;

    /** Same line/column re-resolved to a DIFFERENT declaration after the file was edited. */
    private static final String MOVED_TARGET_PREVIEW =
            """
            {
              "accepted": true,
              "operation": "semanticRename",
              "target": {
                "semanticKey": {
                  "kind": "FIELD",
                  "owner": "App",
                  "name": "other",
                  "signature": "int",
                  "canonical": "App#other"
                },
                "span": {"file": "App.java", "startOffset": 24, "endOffset": 29}
              },
              "workspaceEdit": {"changes": {"App.java": [{"newText": "count"}]}}
            }
            """;

    @Test
    void parseAnchorsOnDeclaredTargetSemanticKey() {
        SessionTarget target = SessionTarget.parse(FIELD_PREVIEW);

        assertEquals("App#value", target.canonical());
        assertEquals("FIELD", target.kind());
        assertEquals("App", target.owner());
        assertEquals("value", target.name());
    }

    @Test
    void identityStringExposesStableCanonicalEnvelope() {
        SessionTarget target = SessionTarget.parse(FIELD_PREVIEW);

        String identity = target.identityString("semanticRename");

        assertTrue(identity.contains("semanticCanonical=App#value"), identity);
        assertFalse(identity.contains("semanticKey=<missing>"), identity);
        assertFalse(identity.contains("|path="), identity);
    }

    @Test
    void applyHappyPathMatchesReResolvedIdentity() {
        SessionTarget previewed = SessionTarget.parse(FIELD_PREVIEW);
        SessionTarget reResolved = SessionTarget.parse(FIELD_PREVIEW);

        assertTrue(previewed.matches(reResolved));
        assertEquals(
                RefactorSessionManager.targetIdentity("semanticRename", Map.of(), FIELD_PREVIEW),
                previewed.identityString("semanticRename"));
    }

    @Test
    void applyRefusesWhenTargetMovedToDifferentSymbol() {
        SessionTarget previewed = SessionTarget.parse(FIELD_PREVIEW);
        SessionTarget reResolved = SessionTarget.parse(MOVED_TARGET_PREVIEW);

        assertFalse(previewed.matches(reResolved));
        assertFalse(
                previewed
                        .identityString("semanticRename")
                        .equals(RefactorSessionManager.targetIdentity("semanticRename", Map.of(), MOVED_TARGET_PREVIEW)));
    }

    @Test
    void parseReturnsNullAndGateRejectsWhenNoDeclaredTarget() {
        // A reference key buried in the edit tree must NOT be scraped as the target identity.
        String referenceOnly =
                """
                {
                  "accepted": true,
                  "operation": "semanticRename",
                  "workspaceEdit": {
                    "changes": {"App.java": [{"newText": "count", "semanticKey": {"canonical": "App#callSite"}}]}
                  }
                }
                """;

        assertNull(SessionTarget.parseOrNull(referenceOnly));
        assertFalse(RefactorSessionManager.hasStableSemanticTarget(referenceOnly));
        assertTrue(RefactorSessionManager.hasStableSemanticTarget(FIELD_PREVIEW));
    }

    @Test
    void parseHonorsSemanticTargetIdentityEnvelopeFallback() {
        String fieldRefactorShape =
                """
                {
                  "accepted": true,
                  "operation": "encapsulateField",
                  "semanticTarget": {
                    "operation": "encapsulateField",
                    "identity": {"kind": "FIELD", "owner": "FieldSample", "name": "count", "canonical": "FieldSample#count"}
                  },
                  "target": {
                    "semanticKey": {"kind": "FIELD", "owner": "FieldSample", "name": "count", "canonical": "FieldSample#count"}
                  }
                }
                """;

        assertEquals("FieldSample#count", SessionTarget.parse(fieldRefactorShape).canonical());
    }

    @Test
    void constructorRejectsBlankCanonicalKey() {
        assertThrows(IllegalArgumentException.class, () -> new SessionTarget("  ", "FIELD", "App", "value", "int"));
        assertThrows(IllegalArgumentException.class, () -> SessionTarget.parse("{\"accepted\":true}"));
    }

    @Test
    void sessionMetadataCarriesFirstClassTargetAndDerivedIdentity() {
        RefactorSession session = new RefactorSession(
                "sid-1",
                "semanticRename",
                Map.of("relativePath", "App.java"),
                SessionTarget.parse(FIELD_PREVIEW),
                revision(Map.of("App.java", "hash-a")),
                List.of("App.java"),
                FIELD_PREVIEW,
                Instant.parse("2026-06-13T00:00:00Z"));

        String metadata = session.metadataJson();

        assertTrue(metadata.contains("\"semanticTarget\":"), metadata);
        assertTrue(metadata.contains("\"canonical\":\"App#value\""), metadata);
        assertTrue(metadata.contains("semanticCanonical=App#value"), metadata);
        assertTrue(session.targetMatches(SessionTarget.parse(FIELD_PREVIEW)));
        assertFalse(session.targetMatches(SessionTarget.parse(MOVED_TARGET_PREVIEW)));
    }

    @Test
    void staleRevisionMismatchIsReportedPerSource() {
        ProjectRevision previewed = revision(Map.of("App.java", "hash-a"));
        ProjectRevision unchanged = revision(Map.of("App.java", "hash-a"));
        ProjectRevision edited = revision(Map.of("App.java", "hash-b"));

        assertNull(previewed.mismatch(unchanged));
        assertEquals("source file changed: App.java", previewed.mismatch(edited));
    }

    private static ProjectRevision revision(Map<String, String> sourceHashes) {
        return new ProjectRevision(
                "model-hash",
                sourceHashes,
                Instant.parse("2026-06-13T00:00:00Z"),
                Map.of("modelHash", "model-hash"),
                "derived-digest");
    }
}
