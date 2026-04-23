package com.kafkaasr.tts.events;

public record TtsReadyPayload(
        String playbackUrl,
        String codec,
        int sampleRate,
        long durationMs,
        String cacheKey) {
}
