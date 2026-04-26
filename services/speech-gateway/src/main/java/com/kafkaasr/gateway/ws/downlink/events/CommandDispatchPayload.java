package com.kafkaasr.gateway.ws.downlink.events;

public record CommandDispatchPayload(
        String executionId,
        String commandText,
        String intent,
        String subIntent,
        boolean confirmRequired,
        String confirmToken,
        int expiresInSec,
        String traceId) {
}
