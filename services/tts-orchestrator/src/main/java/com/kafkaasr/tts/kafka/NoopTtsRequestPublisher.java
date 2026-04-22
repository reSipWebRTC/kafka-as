package com.kafkaasr.tts.kafka;

import com.kafkaasr.tts.events.TtsRequestEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "tts.kafka.enabled", havingValue = "false")
public class NoopTtsRequestPublisher implements TtsRequestPublisher {

    @Override
    public Mono<Void> publish(TtsRequestEvent event) {
        return Mono.empty();
    }
}
