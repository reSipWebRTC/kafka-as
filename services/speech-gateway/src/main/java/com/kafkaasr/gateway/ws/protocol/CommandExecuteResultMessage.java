package com.kafkaasr.gateway.ws.protocol;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CommandExecuteResultMessage(
        @NotBlank String type,
        @NotBlank String sessionId,
        @Min(0) long seq,
        @NotBlank String executionId,
        @NotBlank String status,
        @NotBlank String code,
        String replyText,
        boolean retryable,
        String traceId) {
}
