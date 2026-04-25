package com.kafkaasr.gateway.ws.protocol;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PlaybackMetricMessage(
        @NotBlank String type,
        @NotBlank String sessionId,
        @NotNull @Min(0) Long seq,
        @NotBlank String stage,
        @NotBlank String source,
        @Min(0) Long durationMs,
        @Min(0) Integer stallCount,
        String reason,
        String traceId) {
}

