package com.kafkaasr.orchestrator.events;

import reactor.core.publisher.Mono;

public interface SessionControlPublisher {

    Mono<Void> publish(SessionControlEvent event);
}
