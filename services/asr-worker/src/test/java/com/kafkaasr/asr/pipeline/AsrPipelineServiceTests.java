package com.kafkaasr.asr.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kafkaasr.asr.events.AsrFinalEvent;
import com.kafkaasr.asr.events.AsrKafkaProperties;
import com.kafkaasr.asr.events.AsrPartialEvent;
import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.events.AudioIngressRawPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AsrPipelineServiceTests {

    @Test
    void mapsIngressEventToAsrPartialAndAsrFinalEvents() {
        AsrKafkaProperties properties = new AsrKafkaProperties();
        properties.setProducerId("asr-worker");

        AsrInferenceEngine inferenceEngine = event ->
                new AsrInferenceEngine.AsrInferenceResult("hello world", "en-US", 0.91d, true);

        AsrPipelineService service = new AsrPipelineService(
                inferenceEngine,
                properties,
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        AudioIngressRawEvent ingressEvent = new AudioIngressRawEvent(
                "evt-in-1",
                "audio.ingress.raw",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "speech-gateway",
                42L,
                1713744000000L,
                "sess-1:audio.ingress.raw:42",
                new AudioIngressRawPayload("pcm16le", 16000, 1, "AQID", true));

        AsrPipelineService.AsrPipelineEvents events = service.toAsrEvents(ingressEvent);
        AsrPartialEvent partialEvent = events.partialEvent();
        AsrFinalEvent finalEvent = events.finalEvent();

        assertTrue(partialEvent.eventId().startsWith("evt_"));
        assertEquals("asr.partial", partialEvent.eventType());
        assertEquals("v1", partialEvent.eventVersion());
        assertEquals("trc-1", partialEvent.traceId());
        assertEquals("sess-1", partialEvent.sessionId());
        assertEquals("tenant-a", partialEvent.tenantId());
        assertEquals("asr-worker", partialEvent.producer());
        assertEquals(42L, partialEvent.seq());
        assertEquals(Instant.parse("2026-04-22T00:00:00Z").toEpochMilli(), partialEvent.ts());
        assertEquals("sess-1:asr.partial:42", partialEvent.idempotencyKey());
        assertEquals("hello world", partialEvent.payload().text());
        assertEquals("en-US", partialEvent.payload().language());
        assertEquals(0.91d, partialEvent.payload().confidence());
        assertFalse(partialEvent.payload().stable());

        assertTrue(finalEvent.eventId().startsWith("evt_"));
        assertEquals("asr.final", finalEvent.eventType());
        assertEquals("v1", finalEvent.eventVersion());
        assertEquals("trc-1", finalEvent.traceId());
        assertEquals("sess-1", finalEvent.sessionId());
        assertEquals("tenant-a", finalEvent.tenantId());
        assertEquals("asr-worker", finalEvent.producer());
        assertEquals(42L, finalEvent.seq());
        assertEquals(Instant.parse("2026-04-22T00:00:00Z").toEpochMilli(), finalEvent.ts());
        assertEquals("sess-1:asr.final:42", finalEvent.idempotencyKey());
        assertEquals("hello world", finalEvent.payload().text());
        assertEquals("en-US", finalEvent.payload().language());
        assertEquals(0.91d, finalEvent.payload().confidence());
        assertTrue(finalEvent.payload().stable());
    }

    @Test
    void rejectsUnexpectedEventType() {
        AsrPipelineService service = new AsrPipelineService(
                event -> new AsrInferenceEngine.AsrInferenceResult("x", "en", 0.5d, false),
                new AsrKafkaProperties(),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        AudioIngressRawEvent invalid = new AudioIngressRawEvent(
                "evt-in-2",
                "session.control",
                "v1",
                "trc-2",
                "sess-2",
                "tenant-a",
                null,
                "session-orchestrator",
                1L,
                1713744000000L,
                "sess-2:session.control:1",
                new AudioIngressRawPayload("pcm16le", 16000, 1, "AQID", false));

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> service.toAsrEvents(invalid));
        assertTrue(exception.getMessage().contains("Unsupported ingress eventType"));
    }

    @Test
    void normalizesInvalidInferenceValues() {
        AsrPipelineService service = new AsrPipelineService(
                event -> new AsrInferenceEngine.AsrInferenceResult(" ", "x", 3.0d, false),
                new AsrKafkaProperties(),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        AudioIngressRawEvent ingressEvent = new AudioIngressRawEvent(
                "evt-in-3",
                "audio.ingress.raw",
                "v1",
                "trc-3",
                "sess-3",
                "tenant-a",
                null,
                "speech-gateway",
                5L,
                1713744000000L,
                "sess-3:audio.ingress.raw:5",
                new AudioIngressRawPayload("pcm16le", 16000, 1, "AQID", false));

        AsrPipelineService.AsrPipelineEvents events = service.toAsrEvents(ingressEvent);
        AsrPartialEvent partialEvent = events.partialEvent();
        AsrFinalEvent finalEvent = events.finalEvent();

        assertEquals("", partialEvent.payload().text());
        assertEquals("und", partialEvent.payload().language());
        assertEquals(1.0d, partialEvent.payload().confidence());
        assertFalse(partialEvent.payload().stable());

        assertEquals("", finalEvent.payload().text());
        assertEquals("und", finalEvent.payload().language());
        assertEquals(1.0d, finalEvent.payload().confidence());
        assertFalse(finalEvent.payload().stable());
    }
}
