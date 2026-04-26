package com.kafkaasr.gateway.ingress;

public record CommandExecuteResultIngressCommand(
        String sessionId,
        long seq,
        String executionId,
        String status,
        String code,
        String replyText,
        boolean retryable,
        String traceId) {
}
