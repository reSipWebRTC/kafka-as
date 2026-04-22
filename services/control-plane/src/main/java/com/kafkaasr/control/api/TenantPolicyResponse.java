package com.kafkaasr.control.api;

public record TenantPolicyResponse(
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
}
