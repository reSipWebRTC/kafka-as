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
        long version,
        long updatedAtMs) {
}
