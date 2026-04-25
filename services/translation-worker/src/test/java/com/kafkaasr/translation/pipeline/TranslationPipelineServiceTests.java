package com.kafkaasr.translation.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kafkaasr.translation.events.AsrFinalEvent;
import com.kafkaasr.translation.events.AsrFinalPayload;
import com.kafkaasr.translation.events.TranslationKafkaProperties;
import com.kafkaasr.translation.events.TranslationRequestEvent;
import com.kafkaasr.translation.events.TranslationRequestPayload;
import com.kafkaasr.translation.events.TranslationResultEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TranslationPipelineServiceTests {

    @Test
    void mapsAsrFinalEventToTranslationRequestEvent() {
        TranslationKafkaProperties properties = new TranslationKafkaProperties();
        properties.setProducerId("translation-worker");
        properties.setDefaultTargetLang("en-US");

        TranslationEngine engine = event ->
                new TranslationEngine.TranslationResult("hello", "zh-CN", "en-US", "placeholder");

        TranslationPipelineService service = new TranslationPipelineService(
                engine,
                properties,
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "asr-worker",
                7L,
                1713744000000L,
                "sess-1:asr.final:7",
                new AsrFinalPayload("你好", "zh-CN", 0.9d, true));

        TranslationRequestEvent out = service.toTranslationRequestEvent(input);

        assertTrue(out.eventId().startsWith("evt_"));
        assertEquals("translation.request", out.eventType());
        assertEquals("v1", out.eventVersion());
        assertEquals("trc-1", out.traceId());
        assertEquals("sess-1", out.sessionId());
        assertEquals("tenant-a", out.tenantId());
        assertEquals("translation-worker", out.producer());
        assertEquals(7L, out.seq());
        assertEquals(Instant.parse("2026-04-22T00:00:00Z").toEpochMilli(), out.ts());
        assertEquals("sess-1:translation.request:7", out.idempotencyKey());
        assertEquals("你好", out.payload().sourceText());
        assertEquals("zh-CN", out.payload().sourceLang());
        assertEquals("en-US", out.payload().targetLang());
    }

    @Test
    void mapsTranslationRequestEventToTranslationResultEvent() {
        TranslationKafkaProperties properties = new TranslationKafkaProperties();
        properties.setProducerId("translation-worker");
        properties.setEngineName("openai-default");

        TranslationEngine engine = event ->
                new TranslationEngine.TranslationResult("hello", "zh-CN", "en-US", "placeholder");

        TranslationPipelineService service = new TranslationPipelineService(
                engine,
                properties,
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        TranslationRequestEvent input = new TranslationRequestEvent(
                "evt-req-1",
                "translation.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                7L,
                1713744000000L,
                "sess-1:translation.request:7",
                new TranslationRequestPayload("你好", "zh-CN", "en-US"));

        TranslationResultEvent out = service.toTranslationResultEvent(input);

        assertTrue(out.eventId().startsWith("evt_"));
        assertEquals("translation.result", out.eventType());
        assertEquals("v1", out.eventVersion());
        assertEquals("trc-1", out.traceId());
        assertEquals("sess-1", out.sessionId());
        assertEquals("tenant-a", out.tenantId());
        assertEquals("translation-worker", out.producer());
        assertEquals(7L, out.seq());
        assertEquals(Instant.parse("2026-04-22T00:00:00Z").toEpochMilli(), out.ts());
        assertEquals("sess-1:translation.result:7", out.idempotencyKey());
        assertEquals("你好", out.payload().sourceText());
        assertEquals("hello", out.payload().translatedText());
        assertEquals("zh-CN", out.payload().sourceLang());
        assertEquals("en-US", out.payload().targetLang());
        assertEquals("placeholder", out.payload().engine());
    }

    @Test
    void rejectsUnexpectedAsrFinalEventType() {
        TranslationPipelineService service = new TranslationPipelineService(
                event -> new TranslationEngine.TranslationResult("x", "zh-CN", "en-US", "placeholder"),
                new TranslationKafkaProperties(),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        AsrFinalEvent invalid = new AsrFinalEvent(
                "evt-in-2",
                "audio.ingress.raw",
                "v1",
                "trc-2",
                "sess-2",
                "tenant-a",
                null,
                "speech-gateway",
                1L,
                1713744000000L,
                "sess-2:audio.ingress.raw:1",
                new AsrFinalPayload("你好", "zh-CN", 0.8d, true));

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> service.toTranslationRequestEvent(invalid));
        assertTrue(exception.getMessage().contains("Unsupported asr.final eventType"));
    }

    @Test
    void rejectsUnexpectedTranslationRequestEventType() {
        TranslationPipelineService service = new TranslationPipelineService(
                event -> new TranslationEngine.TranslationResult("x", "zh-CN", "en-US", "placeholder"),
                new TranslationKafkaProperties(),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        TranslationRequestEvent invalid = new TranslationRequestEvent(
                "evt-req-2",
                "translation.result",
                "v1",
                "trc-2",
                "sess-2",
                "tenant-a",
                null,
                "translation-worker",
                1L,
                1713744000000L,
                "sess-2:translation.result:1",
                new TranslationRequestPayload("你好", "zh-CN", "en-US"));

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> service.toTranslationResultEvent(invalid));
        assertTrue(exception.getMessage().contains("Unsupported translation.request eventType"));
    }
}
