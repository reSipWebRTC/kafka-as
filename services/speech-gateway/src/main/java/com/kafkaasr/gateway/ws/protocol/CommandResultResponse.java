package com.kafkaasr.gateway.ws.protocol;

public record CommandResultResponse(
        String type,
        String sessionId,
        long seq,
        String executionId,
        String status,
        String code,
        String replyText,
        boolean retryable,
        String confirmToken,
        int expiresInSec) {
}
