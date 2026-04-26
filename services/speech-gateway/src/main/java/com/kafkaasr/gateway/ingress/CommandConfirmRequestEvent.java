package com.kafkaasr.gateway.ingress;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandConfirmRequestEvent(
        String eventId,
        String eventType,
        String eventVersion,
        String traceId,
        String sessionId,
        String tenantId,
        String roomId,
        String userId,
        String producer,
        long seq,
        long ts,
        String idempotencyKey,
        CommandConfirmRequestPayload payload) {
}
