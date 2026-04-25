package com.kafkaasr.translation.pipeline;

import com.kafkaasr.translation.events.TranslationRequestEvent;
import com.kafkaasr.translation.events.TranslationRequestPayload;
import org.springframework.stereotype.Component;

@Component
public class PlaceholderTranslationEngine implements TranslationEngine {

    @Override
    public TranslationResult translate(TranslationRequestEvent translationRequestEvent) {
        TranslationRequestPayload payload = translationRequestEvent.payload();
        if (payload == null) {
            throw new IllegalArgumentException(
                    "Missing translation.request payload for session " + translationRequestEvent.sessionId());
        }

        String sourceText = payload.sourceText() == null ? "" : payload.sourceText();
        String sourceLang = payload.sourceLang() == null || payload.sourceLang().isBlank() ? "und" : payload.sourceLang();
        String targetLang = payload.targetLang() == null || payload.targetLang().isBlank() ? "und" : payload.targetLang();

        String translatedText;
        if (sourceLang.equalsIgnoreCase(targetLang)) {
            translatedText = sourceText;
        } else {
            translatedText = "[" + targetLang + "] " + sourceText;
        }
        return new TranslationResult(translatedText, sourceLang, targetLang, "placeholder");
    }
}
