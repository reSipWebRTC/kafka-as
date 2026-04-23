package com.kafkaasr.tts.kafka;

import com.kafkaasr.tts.events.TtsChunkEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "tts.kafka.enabled", havingValue = "false")
public class NoopTtsChunkPublisher implements TtsChunkPublisher {

    @Override
    public Mono<Void> publish(TtsChunkEvent event) {
        return Mono.empty();
    }
}
