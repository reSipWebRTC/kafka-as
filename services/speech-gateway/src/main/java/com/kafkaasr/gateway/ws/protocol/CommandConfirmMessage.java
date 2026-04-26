package com.kafkaasr.gateway.ws.protocol;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CommandConfirmMessage(
        @NotBlank String type,
        @NotBlank String sessionId,
        @Min(0) long seq,
        @NotBlank String confirmToken,
        boolean accept,
        String traceId,
        String executionId) {
}
