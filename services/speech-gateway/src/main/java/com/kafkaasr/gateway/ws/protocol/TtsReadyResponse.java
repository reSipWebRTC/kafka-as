package com.kafkaasr.gateway.ws.protocol;

public record TtsReadyResponse(
        String type,
        String sessionId,
        long seq,
        String playbackUrl,
        String codec,
        int sampleRate,
        long durationMs,
        String cacheKey) {
}
