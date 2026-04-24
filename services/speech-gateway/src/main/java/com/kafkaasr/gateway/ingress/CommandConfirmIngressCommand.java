package com.kafkaasr.gateway.ingress;

public record CommandConfirmIngressCommand(
        String sessionId,
        long seq,
        String traceId,
        String tenantId,
        String userId,
        String confirmToken,
        boolean accept) {
}
