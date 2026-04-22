package com.kafkaasr.tts.kafka;

import com.kafkaasr.tts.events.TtsReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "tts.kafka.enabled", havingValue = "false")
public class NoopTtsReadyPublisher implements TtsReadyPublisher {

    @Override
    public Mono<Void> publish(TtsReadyEvent event) {
        return Mono.empty();
    }
}
