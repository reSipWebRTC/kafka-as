package com.kafkaasr.command.policy;

import java.util.Locale;

public record TenantRoutingPolicy(
        int retryMaxAttempts,
        long retryBackoffMs,
        String dlqTopicSuffix,
        String sessionMode) {

    public static final String SESSION_MODE_TRANSLATION = "TRANSLATION";
    public static final String SESSION_MODE_SMART_HOME = "SMART_HOME";

    public TenantRoutingPolicy {
        if (retryMaxAttempts <= 0) {
            throw new IllegalArgumentException("retryMaxAttempts must be > 0");
        }
        if (retryBackoffMs <= 0) {
            throw new IllegalArgumentException("retryBackoffMs must be > 0");
        }
        if (dlqTopicSuffix == null || dlqTopicSuffix.isBlank()) {
            throw new IllegalArgumentException("dlqTopicSuffix must not be blank");
        }
        if (sessionMode == null || sessionMode.isBlank()) {
            sessionMode = SESSION_MODE_TRANSLATION;
        } else {
            sessionMode = sessionMode.trim().toUpperCase(Locale.ROOT);
            if (!SESSION_MODE_TRANSLATION.equals(sessionMode)
                    && !SESSION_MODE_SMART_HOME.equals(sessionMode)) {
                sessionMode = SESSION_MODE_TRANSLATION;
            }
        }
    }

    public boolean isSmartHomeMode() {
        return SESSION_MODE_SMART_HOME.equals(sessionMode);
    }
}
