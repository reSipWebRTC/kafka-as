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
                version,
                updatedAtMs);
    }
}
