package com.kafkaasr.orchestrator.api;

public record SessionStopRequest(
        String traceId,
        String reason) {

    public static SessionStopRequest empty() {
        return new SessionStopRequest(null, null);
    }
}
