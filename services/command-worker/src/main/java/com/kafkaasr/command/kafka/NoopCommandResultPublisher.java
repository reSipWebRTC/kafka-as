package com.kafkaasr.command.kafka;

import com.kafkaasr.command.events.CommandResultEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "command.kafka.enabled", havingValue = "false")
public class NoopCommandResultPublisher implements CommandResultPublisher {

    @Override
    public Mono<Void> publish(CommandResultEvent event) {
        return Mono.empty();
    }
}
