package com.kafkaasr.orchestrator.api;

public record SessionStopResponse(
        String sessionId,
        String traceId,
        String status,
        boolean stopped,
        long seq,
        String reason,
        long updatedAtMs) {
}
