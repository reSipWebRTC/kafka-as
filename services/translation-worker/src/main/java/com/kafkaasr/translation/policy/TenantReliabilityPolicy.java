package com.kafkaasr.translation.policy;

public record TenantReliabilityPolicy(
        int retryMaxAttempts,
        long retryBackoffMs,
        String dlqTopicSuffix) {

    public TenantReliabilityPolicy {
        if (retryMaxAttempts <= 0) {
            throw new IllegalArgumentException("retryMaxAttempts must be > 0");
        }
        if (retryBackoffMs <= 0) {
            throw new IllegalArgumentException("retryBackoffMs must be > 0");
        }
        if (dlqTopicSuffix == null || dlqTopicSuffix.isBlank()) {
            throw new IllegalArgumentException("dlqTopicSuffix must not be blank");
        }
    }
}
