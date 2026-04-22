package com.kafkaasr.gateway.ws.downlink.events;

public record SessionControlEvent(
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
        SessionControlPayload payload) {
}
