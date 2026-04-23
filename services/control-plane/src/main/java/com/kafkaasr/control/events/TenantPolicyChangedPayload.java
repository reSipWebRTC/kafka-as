package com.kafkaasr.control.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantPolicyChangedPayload(
        String tenantId,
        long policyVersion,
        long updatedAtMs,
        String operation,
        List<String> changedFields) {
}
