package com.kafkaasr.tts.pipeline;

import com.kafkaasr.tts.events.TranslationResultEvent;
import org.springframework.stereotype.Component;

@Component
public class PlaceholderVoicePolicy implements VoicePolicy {

    @Override
    public String resolveVoice(TranslationResultEvent event, String language, String defaultVoice) {
        if (defaultVoice != null && !defaultVoice.isBlank()) {
            return defaultVoice;
        }
        if (language == null) {
            return "voice-default";
        }
        if (language.startsWith("zh")) {
            return "zh-CN-standard-A";
        }
        if (language.startsWith("en")) {
            return "en-US-standard-A";
        }
        return "voice-default";
    }
}
