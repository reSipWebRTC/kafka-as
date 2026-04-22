package com.kafkaasr.control.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
        Boolean enabled) {
}
