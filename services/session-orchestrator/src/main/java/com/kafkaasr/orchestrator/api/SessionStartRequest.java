package com.kafkaasr.orchestrator.api;

import jakarta.validation.constraints.NotBlank;

public record SessionStartRequest(
        @NotBlank String sessionId,
        @NotBlank String tenantId,
        @NotBlank String sourceLang,
        @NotBlank String targetLang,
        String traceId) {
}
