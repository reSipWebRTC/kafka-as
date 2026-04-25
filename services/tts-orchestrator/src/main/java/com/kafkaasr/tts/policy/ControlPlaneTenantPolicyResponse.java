package com.kafkaasr.tts.policy;

public record ControlPlaneTenantPolicyResponse(
        String tenantId,
        boolean controlPlaneFallbackFailOpen,
        long controlPlaneFallbackCacheTtlMs,
        int retryMaxAttempts,
        long retryBackoffMs,
        String dlqTopicSuffix,
        String sessionMode) {
}
