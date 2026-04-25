package com.kafkaasr.control.distribution;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantPolicyDistributionExecutionState(
        String tenantId,
        long policyVersion,
        String service,
        String region,
        String status,
        String reasonCode,
        String reasonMessage,
        long appliedAtMs,
        String sourceEventId,
        String eventId,
        String producer,
        long updatedTsMs) {
}
