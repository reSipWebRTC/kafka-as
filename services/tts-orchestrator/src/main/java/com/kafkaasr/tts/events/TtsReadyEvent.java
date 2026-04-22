package com.kafkaasr.tts.events;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TtsReadyEvent(
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
        TtsReadyPayload payload) {
}
