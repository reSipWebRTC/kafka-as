package com.kafkaasr.asr.kafka;

import com.kafkaasr.asr.events.AsrPartialEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "asr.kafka.enabled", havingValue = "false")
public class NoopAsrPartialPublisher implements AsrPartialPublisher {

    @Override
    public Mono<Void> publish(AsrPartialEvent event) {
        return Mono.empty();
    }
}
