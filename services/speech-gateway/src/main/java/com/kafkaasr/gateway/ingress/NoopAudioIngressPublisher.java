package com.kafkaasr.gateway.ingress;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class NoopAudioIngressPublisher implements AudioIngressPublisher {

    @Override
    public Mono<Void> publishRawFrame(AudioFrameIngressCommand command) {
        // Placeholder until Kafka publishing and envelope mapping are wired in.
        return Mono.empty();
    }
}
