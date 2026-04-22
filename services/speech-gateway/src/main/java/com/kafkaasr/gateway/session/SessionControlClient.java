package com.kafkaasr.gateway.session;

import reactor.core.publisher.Mono;

public interface SessionControlClient {

    Mono<Void> startSession(SessionStartCommand command);

    Mono<Void> stopSession(SessionStopCommand command);
}
