package com.kafkaasr.control.events;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantPolicyChangedEvent(
        String eventId,
        String eventType,
        String eventVersion,
        String traceId,
        String sessionId,
        String tenantId,
        String roomId,
        String producer,
        long seq,
        long ts,
        String idempotencyKey,
        TenantPolicyChangedPayload payload) {
}
