package com.kafkaasr.gateway.ingress;

public record CommandExecuteResultPayload(
        String executionId,
        String status,
        String code,
        String replyText,
        boolean retryable,
        String confirmToken,
        String traceId) {
}
