package com.kafkaasr.orchestrator.api;

public record SessionStatusResponse(
        String sessionId,
        String tenantId,
        String traceId,
        String sourceLang,
        String targetLang,
        String status,
        long seq,
        long startedAtMs,
        long updatedAtMs,
        String closeReason,
        long lastPartialAtMs,
        long lastFinalAtMs,
        long lastTranslationAtMs,
        long lastTtsReadyAtMs,
        long lastCommandResultAtMs,
        Long asrFinalLatencyMs,
        Long translationLatencyMs,
        Long ttsReadyLatencyMs,
        Long commandResultLatencyMs) {
}
