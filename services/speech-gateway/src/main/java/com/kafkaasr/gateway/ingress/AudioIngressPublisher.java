package com.kafkaasr.gateway.ingress;

import reactor.core.publisher.Mono;

public interface AudioIngressPublisher {

    Mono<Void> publishRawFrame(AudioFrameIngressCommand command);
}
