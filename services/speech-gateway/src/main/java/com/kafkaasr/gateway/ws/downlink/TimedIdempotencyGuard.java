package com.kafkaasr.gateway.ws.downlink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
class TimedIdempotencyGuard {

    private final boolean enabled;
    private final long ttlMs;
    private final Map<String, Long> seenKeys = new ConcurrentHashMap<>();

    TimedIdempotencyGuard(GatewayDownlinkProperties downlinkProperties) {
        this.enabled = downlinkProperties.isIdempotencyEnabled();
        this.ttlMs = Math.max(1L, downlinkProperties.getIdempotencyTtlMs());
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
