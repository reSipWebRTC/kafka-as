package com.kafkaasr.gateway.ws.protocol;

import jakarta.validation.constraints.NotBlank;

public record SessionStopMessage(
        @NotBlank String type,
        @NotBlank String sessionId,
        String traceId,
        String reason) {
}
