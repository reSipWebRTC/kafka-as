package com.kafkaasr.gateway.ingress;

public record CommandConfirmRequestPayload(
        String executionId,
        String confirmToken,
        boolean accept,
        String traceId) {
}
