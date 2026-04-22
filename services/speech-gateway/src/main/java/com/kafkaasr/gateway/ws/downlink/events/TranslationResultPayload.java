package com.kafkaasr.gateway.ws.downlink.events;

public record TranslationResultPayload(
        String sourceText,
        String translatedText,
        String sourceLang,
        String targetLang,
        String engine) {
}
