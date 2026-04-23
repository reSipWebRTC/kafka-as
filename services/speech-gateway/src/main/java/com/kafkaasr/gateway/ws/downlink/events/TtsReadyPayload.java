package com.kafkaasr.gateway.ws.downlink.events;

public record TtsReadyPayload(
        String playbackUrl,
        String codec,
        int sampleRate,
        long durationMs,
        String cacheKey) {
}
