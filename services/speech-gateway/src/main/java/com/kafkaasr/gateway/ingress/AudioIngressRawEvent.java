package com.kafkaasr.gateway.ingress;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AudioIngressRawEvent(
        String eventId,
        String eventType,
        String eventVersion,
        String traceId,
        String sessionId,
        String tenantId,
        String userId,
        String roomId,
        String producer,
        long seq,
        long ts,
        String idempotencyKey,
        AudioIngressRawPayload payload) {
}
