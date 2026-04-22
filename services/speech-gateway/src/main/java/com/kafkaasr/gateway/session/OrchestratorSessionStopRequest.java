package com.kafkaasr.gateway.session;

public record OrchestratorSessionStopRequest(
        String traceId,
        String reason) {
}
