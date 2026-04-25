package com.kafkaasr.control.distribution;

import java.util.List;

public interface TenantPolicyDistributionStatusRepository {

    void save(TenantPolicyDistributionExecutionState state);

    List<TenantPolicyDistributionExecutionState> findByTenantAndPolicyVersion(String tenantId, long policyVersion);
}
