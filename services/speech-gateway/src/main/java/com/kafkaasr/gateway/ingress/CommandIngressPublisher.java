package com.kafkaasr.gateway.ingress;

import reactor.core.publisher.Mono;

public interface CommandIngressPublisher {

    Mono<Void> publishCommandConfirm(CommandConfirmIngressCommand command);

    Mono<Void> publishCommandExecuteResult(CommandExecuteResultIngressCommand command);
}
