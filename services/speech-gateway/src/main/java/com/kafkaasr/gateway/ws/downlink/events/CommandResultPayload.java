package com.kafkaasr.gateway.ws.downlink.events;

public record CommandResultPayload(
        String executionId,
        String status,
        String code,
        String replyText,
        boolean retryable,
        String confirmToken,
        int expiresInSec) {
}
