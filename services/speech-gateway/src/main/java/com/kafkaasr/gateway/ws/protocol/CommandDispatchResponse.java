package com.kafkaasr.gateway.ws.protocol;

public record CommandDispatchResponse(
        String type,
        String sessionId,
        long seq,
        String executionId,
        String commandText,
        String intent,
        String subIntent,
        boolean confirmRequired,
        String confirmToken,
        int expiresInSec,
        String traceId) {
}
