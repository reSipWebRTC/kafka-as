package com.kafkaasr.gateway.ingress;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "gateway.kafka.enabled", havingValue = "false")
public class NoopAudioIngressPublisher implements AudioIngressPublisher {

    @Override
    public Mono<Void> publishRawFrame(AudioFrameIngressCommand command) {
        // Explicit fallback for local runs where Kafka publishing is disabled.
        return Mono.empty();
    }
}
