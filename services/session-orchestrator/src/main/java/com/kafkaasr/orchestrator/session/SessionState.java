package com.kafkaasr.orchestrator.session;

public record SessionState(
        String sessionId,
        String tenantId,
        String sourceLang,
        String targetLang,
        String traceId,
        SessionStatus status,
        long lastSeq,
        long startedAtMs,
        long updatedAtMs) {

    public SessionState withState(SessionStatus nextStatus, String nextTraceId, long nextSeq, long nowMs) {
        return new SessionState(
                sessionId,
                tenantId,
                sourceLang,
                targetLang,
                nextTraceId,
                nextStatus,
                nextSeq,
                startedAtMs,
                nowMs);
    }
}
