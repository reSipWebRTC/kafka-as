package com.kafkaasr.control.policy;

public interface TenantPolicyRepository {

    TenantPolicyState findByTenantId(String tenantId);

    boolean createIfAbsent(TenantPolicyState state);

    void save(TenantPolicyState state);

    void appendHistory(TenantPolicyState state);

    TenantPolicyState findLatestHistory(String tenantId);

    TenantPolicyState findHistoryByVersion(String tenantId, long version);

    void removeLatestHistory(String tenantId);
}
