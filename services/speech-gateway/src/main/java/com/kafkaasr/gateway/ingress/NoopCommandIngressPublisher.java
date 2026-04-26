package com.kafkaasr.gateway.ingress;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "gateway.kafka.enabled", havingValue = "false")
public class NoopCommandIngressPublisher implements CommandIngressPublisher {

    @Override
    public Mono<Void> publishCommandConfirm(CommandConfirmIngressCommand command) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> publishCommandExecuteResult(CommandExecuteResultIngressCommand command) {
        return Mono.empty();
    }
}
