package com.kafkaasr.gateway.ingress;

public record CommandConfirmIngressCommand(
        String sessionId,
        long seq,
        String confirmToken,
        boolean accept,
        String traceId,
        String executionId) {
}
