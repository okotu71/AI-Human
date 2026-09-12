package com.okotu.aihuman.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple per-player cooldown. Not persisted: resets on every restart, which is fine
 * since it only needs to prevent spam within the current play session.
 */
public class RateLimiter {

    private final ConcurrentHashMap<UUID, Long> lastRequestAt = new ConcurrentHashMap<>();
    private final long cooldownMs;

    public RateLimiter(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    /**
     * @return true if the player can send a new request now (and records the timestamp),
     *         false if they're still on cooldown.
     */
    public boolean tryAcquire(UUID playerUuid) {
        long now = System.currentTimeMillis();
        long[] result = new long[1];
        lastRequestAt.compute(playerUuid, (uuid, last) -> {
            if (last == null || now - last >= cooldownMs) {
                result[0] = now;
                return now;
            }
            result[0] = last;
            return last;
        });
        return result[0] == now;
    }

    public long remainingCooldownMs(UUID playerUuid) {
        Long last = lastRequestAt.get(playerUuid);
        if (last == null) return 0;
        long elapsed = System.currentTimeMillis() - last;
        return Math.max(0, cooldownMs - elapsed);
    }
}
