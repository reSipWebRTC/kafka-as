package com.kafkaasr.control.policy;

public record TenantPolicyState(
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

    public TenantPolicyState withUpdated(
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
        return new TenantPolicyState(
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
