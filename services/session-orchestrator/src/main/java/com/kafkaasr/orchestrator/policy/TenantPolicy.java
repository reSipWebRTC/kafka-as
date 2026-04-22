package com.kafkaasr.orchestrator.policy;

public record TenantPolicy(
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
        long version,
        long updatedAtMs) {
}
