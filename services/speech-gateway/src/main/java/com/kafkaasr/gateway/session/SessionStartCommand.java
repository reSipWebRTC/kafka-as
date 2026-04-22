package com.kafkaasr.gateway.session;

public record SessionStartCommand(
        String sessionId,
        String tenantId,
        String sourceLang,
        String targetLang,
        String traceId) {
}
