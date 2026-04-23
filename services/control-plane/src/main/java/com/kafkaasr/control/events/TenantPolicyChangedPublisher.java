package com.kafkaasr.control.events;

import reactor.core.publisher.Mono;

public interface TenantPolicyChangedPublisher {

    Mono<Void> publish(TenantPolicyChangedEvent event);
}
