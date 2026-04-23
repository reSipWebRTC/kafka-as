package com.kafkaasr.control.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "control.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class NoopTenantPolicyChangedPublisher implements TenantPolicyChangedPublisher {

    @Override
    public Mono<Void> publish(TenantPolicyChangedEvent event) {
        return Mono.empty();
    }
}
