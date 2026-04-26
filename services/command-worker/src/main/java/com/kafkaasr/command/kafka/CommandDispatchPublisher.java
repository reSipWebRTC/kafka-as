package com.kafkaasr.command.kafka;

import com.kafkaasr.command.events.CommandDispatchEvent;
import reactor.core.publisher.Mono;

public interface CommandDispatchPublisher {

    Mono<Void> publish(CommandDispatchEvent event);
}
