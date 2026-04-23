package com.kafkaasr.tts.pipeline;

import com.kafkaasr.tts.events.TranslationResultEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "tts.synthesis", name = "mode", havingValue = "placeholder", matchIfMissing = true)
public class PlaceholderTtsSynthesisEngine implements TtsSynthesisEngine {

    @Override
    public SynthesisPlan synthesize(TranslationResultEvent sourceEvent, SynthesisInput input) {
        if (input == null) {
            throw new IllegalArgumentException("tts synthesis input must not be null");
        }
        return new SynthesisPlan(
                input.text(),
                input.language(),
                input.voice(),
                input.stream());
    }
}
