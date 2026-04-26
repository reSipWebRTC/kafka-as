package com.kafkaasr.command.kafka;

import com.kafkaasr.command.events.CommandDispatchEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "command.kafka.enabled", havingValue = "false")
public class NoopCommandDispatchPublisher implements CommandDispatchPublisher {

    @Override
    public Mono<Void> publish(CommandDispatchEvent event) {
        return Mono.empty();
    }
}
