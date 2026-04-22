package com.kafkaasr.translation.events;

public record TranslationResultPayload(
        String sourceText,
        String translatedText,
        String sourceLang,
        String targetLang,
        String engine) {
}
