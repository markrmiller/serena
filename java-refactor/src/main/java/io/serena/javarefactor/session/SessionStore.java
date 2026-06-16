package io.serena.javarefactor.session;

import java.time.Duration;

/**
 * Storage + lifecycle policy for preview-first refactor sessions (design §3).
 *
 * <p>Owns the live-session map together with the TTL-eviction and max-open-session capacity policy that
 * {@link RefactorSessionManager} previously inlined. Implementations must preserve the historical semantics:
 * a {@code put} prunes expired entries and then enforces capacity before storing; a {@code get} prunes expired
 * entries and treats an expired entry as absent (removing it); and {@code size} prunes before counting.
 */
public interface SessionStore {

    /**
     * Sets the capacity and TTL policy. Mirrors {@code RefactorSessionManager.configure(...)} pushing its parsed
     * {@code maxLiveSessions} / {@code sessionTtl} down to the store.
     *
     * @param maxLiveSessions maximum number of concurrently live sessions
     * @param sessionTtl time-to-live after which a session is considered expired (measured from its {@code createdAt})
     */
    void configureLimits(int maxLiveSessions, Duration sessionTtl);

    /**
     * Stores a session, first pruning expired entries and then enforcing the capacity policy (same order as the
     * legacy {@code createSession}: {@code pruneExpired(); enforceMaxSessions();} then store).
     */
    void put(RefactorSession session);

    /**
     * Returns the live session for {@code sessionId}, or {@code null} when none is present or the entry has expired.
     * Prunes expired entries first; an expired entry for {@code sessionId} is removed and reported as absent.
     */
    RefactorSession get(String sessionId);

    /** Removes the session, returning {@code true} when an entry was present. */
    boolean remove(String sessionId);

    /** Number of live sessions, after pruning expired entries. */
    int size();
}
