package com.kafkaasr.tts.pipeline;

import com.kafkaasr.tts.events.TranslationResultEvent;

public interface TtsSynthesisEngine {

    SynthesisPlan synthesize(TranslationResultEvent sourceEvent, SynthesisInput input);

    record SynthesisInput(
            String text,
            String language,
            String voice,
            boolean stream) {
    }

    record SynthesisPlan(
            String text,
            String language,
            String voice,
            boolean stream) {
    }
}
