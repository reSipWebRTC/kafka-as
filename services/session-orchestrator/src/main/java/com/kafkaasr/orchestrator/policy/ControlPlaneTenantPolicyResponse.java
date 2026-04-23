package com.kafkaasr.orchestrator.policy;

public record ControlPlaneTenantPolicyResponse(
        String tenantId,
        String sourceLang,
        String targetLang,
        String asrModel,
        String translationModel,
        String ttsVoice,
        int maxConcurrentSessions,
        int rateLimitPerMinute,
        boolean enabled,
        boolean grayEnabled,
        int grayTrafficPercent,
        boolean controlPlaneFallbackFailOpen,
        long controlPlaneFallbackCacheTtlMs,
        int retryMaxAttempts,
        long retryBackoffMs,
        String dlqTopicSuffix,
        long version,
        long updatedAtMs,
        boolean created) {

    TenantPolicy toTenantPolicy() {
        return new TenantPolicy(
                tenantId,
                sourceLang,
                targetLang,
                asrModel,
                translationModel,
                ttsVoice,
                maxConcurrentSessions,
                rateLimitPerMinute,
                enabled,
                grayEnabled,
                grayTrafficPercent,
                controlPlaneFallbackFailOpen,
                controlPlaneFallbackCacheTtlMs,
                retryMaxAttempts,
                retryBackoffMs,
                dlqTopicSuffix,
                version,
                updatedAtMs);
    }
}
