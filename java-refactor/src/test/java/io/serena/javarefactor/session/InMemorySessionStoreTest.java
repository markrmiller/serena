package io.serena.javarefactor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle coverage for {@link InMemorySessionStore}: the TTL eviction, max-open-session capacity, and
 * configure-limits semantics that {@link RefactorSessionManager} previously inlined are preserved verbatim.
 *
 * <p>Expiry is driven by constructing sessions with an old {@code createdAt} (the field {@code expired} compares
 * against {@code Instant.now()}), never by sleeping.
 */
class InMemorySessionStoreTest {

    private static RefactorSession session(String id, Instant createdAt) {
        return new RefactorSession(
                id,
                "semanticRename",
                Map.of(),
                new SessionTarget("App#" + id, "FIELD", "App", id, "int"),
                new ProjectRevision("model-hash", Map.of(), Instant.now(), Map.of(), "derived-digest"),
                List.of(),
                "{}",
                createdAt);
    }

    @Test
    void putGetRemoveSize() {
        InMemorySessionStore store = new InMemorySessionStore();
        RefactorSession s = session("a", Instant.now());

        store.put(s);
        assertEquals(1, store.size());
        assertSame(s, store.get("a"));

        assertTrue(store.remove("a"));
        assertFalse(store.remove("a"));
        assertNull(store.get("a"));
        assertEquals(0, store.size());
    }

    @Test
    void expiredSessionIsNotReturnedAndIsPruned() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.configureLimits(16, Duration.ofMinutes(30));
        // createdAt one hour ago, well past the 30-minute TTL.
        store.put(session("stale", Instant.now().minus(Duration.ofHours(1))));

        assertNull(store.get("stale"));
        assertEquals(0, store.size());
    }

    @Test
    void liveSessionWithinTtlIsRetained() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.configureLimits(16, Duration.ofMinutes(30));
        store.put(session("fresh", Instant.now().minus(Duration.ofMinutes(5))));

        assertNotNullSession(store.get("fresh"));
        assertEquals(1, store.size());
    }

    @Test
    void capacityEvictsOldestWhenAtLimit() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.configureLimits(2, Duration.ofMinutes(30));

        Instant now = Instant.now();
        store.put(session("oldest", now.minus(Duration.ofMinutes(3))));
        store.put(session("middle", now.minus(Duration.ofMinutes(2))));
        // At capacity (2); enforceMaxSessions evicts the oldest by createdAt before storing the third.
        store.put(session("newest", now.minus(Duration.ofMinutes(1))));

        assertEquals(2, store.size());
        assertNull(store.get("oldest"));
        assertNotNullSession(store.get("middle"));
        assertNotNullSession(store.get("newest"));
    }

    @Test
    void configureLimitsTightensCapacityImmediately() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.configureLimits(16, Duration.ofMinutes(30));

        Instant now = Instant.now();
        store.put(session("s1", now.minus(Duration.ofMinutes(3))));
        store.put(session("s2", now.minus(Duration.ofMinutes(2))));
        store.put(session("s3", now.minus(Duration.ofMinutes(1))));
        assertEquals(3, store.size());

        // Lowering maxLiveSessions to 2 prunes+enforces immediately: capacity rule keeps size strictly below the limit
        // after eviction (while size >= max), evicting oldest-first.
        store.configureLimits(2, Duration.ofMinutes(30));
        assertEquals(1, store.size());
        assertNull(store.get("s1"));
        assertNull(store.get("s2"));
        assertNotNullSession(store.get("s3"));
    }

    @Test
    void configureLimitsTtlChangeExpiresExistingSessions() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.configureLimits(16, Duration.ofMinutes(30));
        store.put(session("ten-min-old", Instant.now().minus(Duration.ofMinutes(10))));
        assertEquals(1, store.size());

        // Shrinking the TTL below the session age expires it on the next prune.
        store.configureLimits(16, Duration.ofMinutes(5));
        assertEquals(0, store.size());
        assertNull(store.get("ten-min-old"));
    }

    private static void assertNotNullSession(RefactorSession session) {
        assertTrue(session != null, "expected a live session");
    }
}
