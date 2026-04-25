package com.kafkaasr.orchestrator.session;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record SessionState(
        String sessionId,
        String tenantId,
        String sourceLang,
        String targetLang,
        String traceId,
        SessionStatus status,
        long lastSeq,
        long startedAtMs,
        long updatedAtMs,
        long lastPartialAtMs,
        long lastFinalAtMs,
        long lastTranslationAtMs,
        long lastTtsReadyAtMs,
        long lastCommandResultAtMs,
        String closeReason) {

    public SessionState withState(
            SessionStatus nextStatus,
            String nextTraceId,
            long nextSeq,
            long nowMs,
            String nextCloseReason) {
        return new SessionState(
                sessionId,
                tenantId,
                sourceLang,
                targetLang,
                nextTraceId,
                nextStatus,
                nextSeq,
                startedAtMs,
                nowMs,
                lastPartialAtMs,
                lastFinalAtMs,
                lastTranslationAtMs,
                lastTtsReadyAtMs,
                lastCommandResultAtMs,
                nextCloseReason);
    }

    public SessionState withProgress(SessionProgressMarker marker, long eventTsMs) {
        long nextPartialAtMs = lastPartialAtMs;
        long nextFinalAtMs = lastFinalAtMs;
        long nextTranslationAtMs = lastTranslationAtMs;
        long nextTtsReadyAtMs = lastTtsReadyAtMs;
        long nextCommandResultAtMs = lastCommandResultAtMs;
        switch (marker) {
            case ASR_PARTIAL -> nextPartialAtMs = max(lastPartialAtMs, eventTsMs);
            case ASR_FINAL -> nextFinalAtMs = max(lastFinalAtMs, eventTsMs);
            case TRANSLATION_RESULT -> nextTranslationAtMs = max(lastTranslationAtMs, eventTsMs);
            case TTS_READY -> nextTtsReadyAtMs = max(lastTtsReadyAtMs, eventTsMs);
            case COMMAND_RESULT -> nextCommandResultAtMs = max(lastCommandResultAtMs, eventTsMs);
        }

        return new SessionState(
                sessionId,
                tenantId,
                sourceLang,
                targetLang,
                traceId,
                marker.toStatus(),
                lastSeq,
                startedAtMs,
                max(updatedAtMs, eventTsMs),
                nextPartialAtMs,
                nextFinalAtMs,
                nextTranslationAtMs,
                nextTtsReadyAtMs,
                nextCommandResultAtMs,
                closeReason);
    }

    @JsonIgnore
    public boolean isActive() {
        return status != SessionStatus.CLOSED && status != SessionStatus.FAILED;
    }

    private long max(long left, long right) {
        return left >= right ? left : right;
    }
}
