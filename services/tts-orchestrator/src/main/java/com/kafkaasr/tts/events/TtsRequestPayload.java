package com.kafkaasr.tts.events;

public record TtsRequestPayload(
        String text,
        String language,
        String voice,
        String cacheKey,
        boolean stream) {
}
