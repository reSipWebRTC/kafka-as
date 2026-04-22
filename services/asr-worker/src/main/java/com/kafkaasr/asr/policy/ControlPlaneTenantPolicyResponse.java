package com.kafkaasr.asr.policy;

public record ControlPlaneTenantPolicyResponse(
        String tenantId,
        boolean controlPlaneFallbackFailOpen,
        long controlPlaneFallbackCacheTtlMs,
        int retryMaxAttempts,
        long retryBackoffMs,
        String dlqTopicSuffix) {
}
