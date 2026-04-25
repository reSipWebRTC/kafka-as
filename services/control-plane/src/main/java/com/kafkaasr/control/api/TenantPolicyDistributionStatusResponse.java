package com.kafkaasr.control.api;

import java.util.List;

public record TenantPolicyDistributionStatusResponse(
        String tenantId,
        long policyVersion,
        boolean overallPass,
        String overallStatus,
        int resultCount,
        long queriedAtMs,
        List<TenantPolicyDistributionStatusItem> results) {
}
