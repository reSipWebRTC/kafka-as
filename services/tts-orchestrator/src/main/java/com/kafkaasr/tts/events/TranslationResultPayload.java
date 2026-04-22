package com.kafkaasr.tts.events;

public record TranslationResultPayload(
        String sourceText,
        String translatedText,
        String sourceLang,
        String targetLang,
        String engine) {
}
