package com.kafkaasr.gateway.session;

public record OrchestratorSessionStartRequest(
        String sessionId,
        String tenantId,
        String sourceLang,
        String targetLang,
        String traceId) {
}
