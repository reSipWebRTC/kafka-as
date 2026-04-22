package com.kafkaasr.orchestrator.policy;

import reactor.core.publisher.Mono;

public interface TenantPolicyClient {

    Mono<TenantPolicy> getTenantPolicy(String tenantId);
}
