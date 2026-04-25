package com.kafkaasr.gateway.ingress;

import reactor.core.publisher.Mono;

public interface CommandConfirmRequestPublisher {

    Mono<Void> publish(CommandConfirmIngressCommand command);
}
