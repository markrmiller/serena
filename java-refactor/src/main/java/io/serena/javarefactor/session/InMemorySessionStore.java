package io.serena.javarefactor.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link SessionStore}: an in-memory {@link ConcurrentHashMap} guarded by the historical TTL-eviction and
 * max-open-session capacity policy. The eviction order, capacity rule, and TTL comparison are carried over verbatim
 * from {@code RefactorSessionManager} so session lifecycle semantics are unchanged.
 */
public final class InMemorySessionStore implements SessionStore {
    private static final int DEFAULT_MAX_LIVE_SESSIONS = 16;
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(30);

    private final Map<String, RefactorSession> sessions = new ConcurrentHashMap<>();
    private int maxLiveSessions = DEFAULT_MAX_LIVE_SESSIONS;
    private Duration sessionTtl = DEFAULT_SESSION_TTL;

    @Override
    public void configureLimits(int maxLiveSessions, Duration sessionTtl) {
        this.maxLiveSessions = maxLiveSessions;
        this.sessionTtl = sessionTtl;
        pruneExpired();
        enforceMaxSessions();
    }

    @Override
    public void put(RefactorSession session) {
        pruneExpired();
        enforceMaxSessions();
        sessions.put(session.sessionId(), session);
    }

    @Override
    public RefactorSession get(String sessionId) {
        pruneExpired();
        RefactorSession session = sessions.get(sessionId);
        if (session != null && expired(session)) {
            sessions.remove(sessionId);
            return null;
        }
        return session;
    }

    @Override
    public boolean remove(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    @Override
    public int size() {
        pruneExpired();
        return sessions.size();
    }

    private void pruneExpired() {
        sessions.entrySet().removeIf(entry -> expired(entry.getValue()));
    }

    private void enforceMaxSessions() {
        while (sessions.size() >= maxLiveSessions) {
            RefactorSession oldest = sessions.values().stream()
                    .min(Comparator.comparing(RefactorSession::createdAt))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            sessions.remove(oldest.sessionId());
        }
    }

    private boolean expired(RefactorSession session) {
        return session.createdAt().plus(sessionTtl).isBefore(Instant.now());
    }
}
