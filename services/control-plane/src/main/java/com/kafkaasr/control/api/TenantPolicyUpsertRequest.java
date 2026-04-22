package com.kafkaasr.control.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TenantPolicyUpsertRequest(
        @NotBlank(message = "sourceLang must not be blank")
        String sourceLang,

        @NotBlank(message = "targetLang must not be blank")
        String targetLang,

        @NotBlank(message = "asrModel must not be blank")
        String asrModel,

        @NotBlank(message = "translationModel must not be blank")
        String translationModel,

        @NotBlank(message = "ttsVoice must not be blank")
        String ttsVoice,

        @Min(value = 1, message = "maxConcurrentSessions must be greater than 0")
        int maxConcurrentSessions,

        @Min(value = 1, message = "rateLimitPerMinute must be greater than 0")
        int rateLimitPerMinute,

        @NotNull(message = "enabled must not be null")
        Boolean enabled,

        Boolean grayEnabled,

        @Min(value = 0, message = "grayTrafficPercent must be >= 0")
        @Max(value = 100, message = "grayTrafficPercent must be <= 100")
        Integer grayTrafficPercent,

        Boolean controlPlaneFallbackFailOpen,

        @Min(value = 1, message = "controlPlaneFallbackCacheTtlMs must be > 0")
        Long controlPlaneFallbackCacheTtlMs,

        @Min(value = 1, message = "retryMaxAttempts must be > 0")
        Integer retryMaxAttempts,

        @Min(value = 1, message = "retryBackoffMs must be > 0")
        Long retryBackoffMs,

        @Pattern(regexp = "^\\..+$", message = "dlqTopicSuffix must start with '.'")
        String dlqTopicSuffix) {
}
