package dev.nordfjell.auth;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpConnectionLimiterTest {
    private final InetAddress address = InetAddress.getLoopbackAddress();

    @Test
    void rejectsConnectionsAboveTheConfiguredLimitAndAllowsAfterRelease() {
        IpConnectionLimiter limiter = new IpConnectionLimiter(30_000);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(limiter.tryReserve(first, address, 1, 1_000));
        assertFalse(limiter.tryReserve(second, address, 1, 1_000));
        limiter.activate(first);
        limiter.release(first);
        assertTrue(limiter.tryReserve(second, address, 1, 1_001));
    }

    @Test
    void expiresConnectionsThatNeverReachTheJoinEvent() {
        IpConnectionLimiter limiter = new IpConnectionLimiter(30_000);

        assertTrue(limiter.tryReserve(UUID.randomUUID(), address, 1, 1_000));
        assertFalse(limiter.tryReserve(UUID.randomUUID(), address, 1, 30_999));
        assertTrue(limiter.tryReserve(UUID.randomUUID(), address, 1, 31_000));
    }

    @Test
    void activeConnectionsDoNotExpire() {
        IpConnectionLimiter limiter = new IpConnectionLimiter(30_000);
        UUID first = UUID.randomUUID();

        assertTrue(limiter.tryReserve(first, address, 1, 1_000));
        limiter.activate(first);
        assertFalse(limiter.tryReserve(UUID.randomUUID(), address, 1, 100_000));
    }

    @Test
    void zeroDisablesTheLimitWithoutCreatingReservations() {
        IpConnectionLimiter limiter = new IpConnectionLimiter(30_000);
        UUID player = UUID.randomUUID();

        assertTrue(limiter.tryReserve(player, address, 0, 1_000));
        assertTrue(limiter.tryReserve(player, address, 0, 1_000));
    }
}
