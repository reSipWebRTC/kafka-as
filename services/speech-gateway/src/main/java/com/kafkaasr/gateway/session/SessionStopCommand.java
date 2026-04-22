package com.kafkaasr.gateway.session;

public record SessionStopCommand(
        String sessionId,
        String traceId,
        String reason) {
}
