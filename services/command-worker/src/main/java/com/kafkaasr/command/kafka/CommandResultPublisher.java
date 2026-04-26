package com.kafkaasr.command.kafka;

import com.kafkaasr.command.events.CommandResultEvent;
import reactor.core.publisher.Mono;

public interface CommandResultPublisher {

    Mono<Void> publish(CommandResultEvent event);
}
