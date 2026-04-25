package com.kafkaasr.gateway.ws.protocol;

import jakarta.validation.constraints.NotBlank;

public record SessionStartMessage(
        @NotBlank String type,
        @NotBlank String sessionId,
        @NotBlank String tenantId,
        @NotBlank String userId,
        @NotBlank String sourceLang,
        @NotBlank String targetLang,
        String traceId) {
}
