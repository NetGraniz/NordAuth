package dev.nordfjell.auth;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class IpConnectionLimiter {
    private record Reservation(String address, long expiresAtMillis, boolean active) {
        Reservation activate() {
            return new Reservation(address, Long.MAX_VALUE, true);
        }
    }

    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final long pendingLifetimeMillis;

    IpConnectionLimiter(long pendingLifetimeMillis) {
        if (pendingLifetimeMillis < 1) throw new IllegalArgumentException("pendingLifetimeMillis must be positive");
        this.pendingLifetimeMillis = pendingLifetimeMillis;
    }

    synchronized boolean tryReserve(UUID playerId, InetAddress address, int maximumPlayers, long nowMillis) {
        purgeExpired(nowMillis);
        if (maximumPlayers == 0) return true;
        if (reservations.containsKey(playerId)) return false;

        String key = address.getHostAddress();
        if (counts.getOrDefault(key, 0) >= maximumPlayers) return false;

        reservations.put(playerId, new Reservation(key, nowMillis + pendingLifetimeMillis, false));
        counts.merge(key, 1, Integer::sum);
        return true;
    }

    synchronized void activate(UUID playerId) {
        Reservation reservation = reservations.get(playerId);
        if (reservation != null && !reservation.active()) {
            reservations.put(playerId, reservation.activate());
        }
    }

    synchronized void addActive(UUID playerId, InetAddress address) {
        if (reservations.containsKey(playerId)) return;
        String key = address.getHostAddress();
        reservations.put(playerId, new Reservation(key, Long.MAX_VALUE, true));
        counts.merge(key, 1, Integer::sum);
    }

    synchronized void release(UUID playerId) {
        Reservation reservation = reservations.remove(playerId);
        if (reservation != null) decrement(reservation.address());
    }

    synchronized void clear() {
        reservations.clear();
        counts.clear();
    }

    private void purgeExpired(long nowMillis) {
        Iterator<Map.Entry<UUID, Reservation>> iterator = reservations.entrySet().iterator();
        while (iterator.hasNext()) {
            Reservation reservation = iterator.next().getValue();
            if (!reservation.active() && reservation.expiresAtMillis() <= nowMillis) {
                iterator.remove();
                decrement(reservation.address());
            }
        }
    }

    private void decrement(String address) {
        counts.computeIfPresent(address, (ignored, count) -> count == 1 ? null : count - 1);
    }
}
