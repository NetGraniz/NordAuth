package dev.nordfjell.auth;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AuthSessionsTest {
    @Test
    void reconnectInvalidatesBothSuccessfulAndFailedOldRequests() {
        AuthSessions<Object> sessions = new AuthSessions<>();
        UUID id = UUID.randomUUID();
        Object oldPlayer = new Object();
        Object newPlayer = new Object();
        var oldSession = sessions.begin(id, oldPlayer);
        var newSession = sessions.begin(id, newPlayer);
        assertFalse(sessions.isCurrent(oldSession));
        assertTrue(sessions.isCurrent(newSession));
        assertNull(sessions.find(id, oldPlayer));
        assertFalse(sessions.end(id, oldPlayer));
        assertTrue(sessions.isCurrent(newSession));
    }

    @Test
    void restartingAuthenticationEvenForSameOwnerInvalidatesPreviousToken() {
        AuthSessions<Object> sessions = new AuthSessions<>();
        UUID id = UUID.randomUUID();
        Object player = new Object();
        var first = sessions.begin(id, player);
        var second = sessions.begin(id, player);
        assertFalse(sessions.isCurrent(first));
        assertTrue(sessions.isCurrent(second));
    }

    @Test
    void disconnectAndShutdownInvalidateAllWork() {
        AuthSessions<Object> sessions = new AuthSessions<>();
        UUID id = UUID.randomUUID();
        Object player = new Object();
        var first = sessions.begin(id, player);
        assertTrue(sessions.end(id, player));
        assertFalse(sessions.isCurrent(first));
        assertFalse(sessions.end(id, player));
        var second = sessions.begin(id, player);
        sessions.clear();
        assertFalse(sessions.isCurrent(second));
    }
}
