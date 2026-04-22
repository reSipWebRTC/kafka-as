package com.kafkaasr.gateway.session;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "gateway.session-orchestrator.enabled", havingValue = "false")
public class NoopSessionControlClient implements SessionControlClient {

    @Override
    public Mono<Void> startSession(SessionStartCommand command) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> stopSession(SessionStopCommand command) {
        return Mono.empty();
    }
}
