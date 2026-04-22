package com.kafkaasr.asr.kafka;

import com.kafkaasr.asr.events.AsrFinalEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "asr.kafka.enabled", havingValue = "false")
public class NoopAsrFinalPublisher implements AsrFinalPublisher {

    @Override
    public Mono<Void> publish(AsrFinalEvent event) {
        return Mono.empty();
    }
}
