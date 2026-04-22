package com.kafkaasr.translation.pipeline;

import com.kafkaasr.translation.events.AsrFinalEvent;

public interface TranslationEngine {

    TranslationResult translate(AsrFinalEvent asrFinalEvent, String targetLang);

    record TranslationResult(
            String translatedText,
            String sourceLang,
            String targetLang,
            String engine) {
    }
}
