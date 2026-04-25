package com.kafkaasr.control.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantPolicyDistributionStatusItem(
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
