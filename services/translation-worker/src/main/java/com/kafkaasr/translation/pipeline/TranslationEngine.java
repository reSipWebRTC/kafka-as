package com.kafkaasr.translation.pipeline;

import com.kafkaasr.translation.events.TranslationRequestEvent;

public interface TranslationEngine {

    TranslationResult translate(TranslationRequestEvent translationRequestEvent);

    record TranslationResult(
            String translatedText,
            String sourceLang,
            String targetLang,
            String engine) {
    }
}
