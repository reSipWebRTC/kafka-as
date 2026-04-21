package com.kafkaasr.orchestrator.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "orchestrator.kafka.enabled", havingValue = "false")
public class NoopSessionControlPublisher implements SessionControlPublisher {

    @Override
    public Mono<Void> publish(SessionControlEvent event) {
        return Mono.empty();
    }
}
