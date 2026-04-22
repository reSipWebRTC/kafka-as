package com.kafkaasr.gateway.session;

public record OrchestratorSessionErrorResponse(
        String code,
        String message,
        String sessionId) {
}
