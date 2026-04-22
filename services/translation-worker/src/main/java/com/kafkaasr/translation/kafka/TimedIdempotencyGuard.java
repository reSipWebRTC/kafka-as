package com.kafkaasr.translation.kafka;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

class TimedIdempotencyGuard {

    private final boolean enabled;
    private final long ttlMs;
    private final Map<String, Long> seenKeys = new ConcurrentHashMap<>();

    TimedIdempotencyGuard(boolean enabled, long ttlMs) {
        this.enabled = enabled;
        this.ttlMs = Math.max(1L, ttlMs);
    }

    boolean isDuplicate(String key) {
        if (!enabled || key == null || key.isBlank()) {
            return false;
        }

        long now = System.currentTimeMillis();
        seenKeys.entrySet().removeIf(entry -> entry.getValue() <= now);
        Long expiry = seenKeys.get(key);
        if (expiry == null) {
            return false;
        }
        if (expiry <= now) {
            seenKeys.remove(key, expiry);
            return false;
        }
        return true;
    }

    void markProcessed(String key) {
        if (!enabled || key == null || key.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        seenKeys.entrySet().removeIf(entry -> entry.getValue() <= now);
        seenKeys.put(key, now + ttlMs);
    }
}
