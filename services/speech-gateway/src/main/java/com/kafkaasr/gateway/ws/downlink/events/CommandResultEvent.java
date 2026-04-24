package com.kafkaasr.gateway.ws.downlink.events;

public record CommandResultEvent(
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
        CommandResultPayload payload) {
}
