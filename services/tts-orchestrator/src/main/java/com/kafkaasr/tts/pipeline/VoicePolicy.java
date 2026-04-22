package com.kafkaasr.tts.pipeline;

import com.kafkaasr.tts.events.TranslationResultEvent;

public interface VoicePolicy {

    String resolveVoice(TranslationResultEvent event, String language, String defaultVoice);
}
