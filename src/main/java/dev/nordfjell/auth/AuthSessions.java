package dev.nordfjell.auth;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tokens identify connections, not accounts. Safe to check from a database worker. */
final class AuthSessions<T> {
    // Deliberately identity-based: two authentications for the same owner are not equal.
    static final class Session<T> {
        private final UUID playerId;
        private final T owner;

        Session(UUID playerId, T owner) {
            this.playerId = playerId;
            this.owner = owner;
        }

        UUID playerId() { return playerId; }
        T owner() { return owner; }
    }

    private final ConcurrentHashMap<UUID, Session<T>> current = new ConcurrentHashMap<>();

    Session<T> begin(UUID playerId, T owner) {
        Session<T> session = new Session<>(playerId, owner);
        current.put(playerId, session);
        return session;
    }

    Session<T> find(UUID playerId, T owner) {
        Session<T> session = current.get(playerId);
        return session != null && session.owner() == owner ? session : null;
    }

    boolean isCurrent(Session<T> session) {
        return session != null && current.get(session.playerId()) == session;
    }

    boolean end(UUID playerId, T owner) {
        Session<T> session = find(playerId, owner);
        return session != null && current.remove(playerId, session);
    }

    void clear() {
        current.clear();
    }
}
