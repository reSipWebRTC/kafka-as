package com.kafkaasr.gateway.ingress;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "gateway.kafka.enabled", havingValue = "false")
public class NoopCommandConfirmRequestPublisher implements CommandConfirmRequestPublisher {

    @Override
    public Mono<Void> publish(CommandConfirmIngressCommand command) {
        return Mono.empty();
    }
}
