package com.kafkaasr.translation.pipeline;

import com.kafkaasr.translation.events.AsrFinalEvent;
import com.kafkaasr.translation.events.AsrFinalPayload;
import org.springframework.stereotype.Component;

@Component
public class PlaceholderTranslationEngine implements TranslationEngine {

    @Override
    public TranslationResult translate(AsrFinalEvent asrFinalEvent, String targetLang) {
        AsrFinalPayload payload = asrFinalEvent.payload();
        if (payload == null) {
            throw new IllegalArgumentException("Missing asr.final payload for session " + asrFinalEvent.sessionId());
        }

        String sourceText = payload.text() == null ? "" : payload.text();
        String sourceLang = payload.language() == null || payload.language().isBlank() ? "und" : payload.language();

        String translatedText;
        if (sourceLang.equalsIgnoreCase(targetLang)) {
            translatedText = sourceText;
        } else {
            translatedText = "[" + targetLang + "] " + sourceText;
        }
        return new TranslationResult(translatedText, sourceLang, targetLang, "placeholder");
    }
}
