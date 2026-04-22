package com.kafkaasr.control.policy;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryTenantPolicyRepository implements TenantPolicyRepository {

    private final ConcurrentHashMap<String, TenantPolicyState> policies = new ConcurrentHashMap<>();

    @Override
    public TenantPolicyState findByTenantId(String tenantId) {
        return policies.get(tenantId);
    }

    @Override
    public boolean createIfAbsent(TenantPolicyState state) {
        return policies.putIfAbsent(state.tenantId(), state) == null;
    }

    @Override
    public void save(TenantPolicyState state) {
        policies.put(state.tenantId(), state);
    }
}
