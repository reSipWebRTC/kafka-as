package com.kafkaasr.orchestrator.api;

public record SessionStartResponse(
        String sessionId,
        String traceId,
        String status,
        boolean created,
        long seq,
        long startedAtMs) {
}
