package com.kafkaasr.tts.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.events.TtsRequestEvent;
import com.kafkaasr.tts.events.TranslationResultEvent;
import com.kafkaasr.tts.events.TranslationResultPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TtsRequestPipelineServiceTests {

    @Test
    void mapsTranslationResultToTtsRequestEvent() {
        TtsKafkaProperties properties = new TtsKafkaProperties();
        properties.setProducerId("tts-orchestrator");
        properties.setDefaultVoice("en-US-neural-a");
        properties.setStreamEnabled(true);

        VoicePolicy voicePolicy = (event, language, defaultVoice) -> defaultVoice;
        TtsRequestPipelineService service = new TtsRequestPipelineService(
                voicePolicy,
                properties,
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        TranslationResultEvent input = new TranslationResultEvent(
                "evt-in-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                9L,
                1713744000000L,
                "sess-1:translation.result:9",
                new TranslationResultPayload("你好", "hello", "zh-CN", "en-US", "placeholder"));

        TtsRequestEvent out = service.toTtsRequestEvent(input);

        assertTrue(out.eventId().startsWith("evt_"));
        assertEquals("tts.request", out.eventType());
        assertEquals("v1", out.eventVersion());
        assertEquals("trc-1", out.traceId());
        assertEquals("sess-1", out.sessionId());
        assertEquals("tenant-a", out.tenantId());
        assertEquals("tts-orchestrator", out.producer());
        assertEquals(9L, out.seq());
        assertEquals(Instant.parse("2026-04-22T00:00:00Z").toEpochMilli(), out.ts());
        assertEquals("sess-1:tts.request:9", out.idempotencyKey());

        assertEquals("hello", out.payload().text());
        assertEquals("en-US", out.payload().language());
        assertEquals("en-US-neural-a", out.payload().voice());
        assertTrue(out.payload().stream());
        assertTrue(out.payload().cacheKey().startsWith("tts:v1:"));
    }

    @Test
    void cacheKeyIsDeterministicForSameVoiceLanguageAndText() {
        TtsKafkaProperties properties = new TtsKafkaProperties();
        properties.setDefaultVoice("voice-default");

        TtsRequestPipelineService service = new TtsRequestPipelineService(
                (event, language, defaultVoice) -> "voice-A",
                properties,
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        TranslationResultEvent input = new TranslationResultEvent(
                "evt-in-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                1L,
                1713744000000L,
                "sess-1:translation.result:1",
                new TranslationResultPayload("你好", "hello", "zh-CN", "en-US", "placeholder"));

        TtsRequestEvent out1 = service.toTtsRequestEvent(input);
        TtsRequestEvent out2 = service.toTtsRequestEvent(input);

        assertEquals(out1.payload().cacheKey(), out2.payload().cacheKey());
    }

    @Test
    void rejectsUnexpectedEventType() {
        TtsRequestPipelineService service = new TtsRequestPipelineService(
                (event, language, defaultVoice) -> "voice-default",
                new TtsKafkaProperties(),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        TranslationResultEvent invalid = new TranslationResultEvent(
                "evt-in-2",
                "asr.final",
                "v1",
                "trc-2",
                "sess-2",
                "tenant-a",
                null,
                "translation-worker",
                3L,
                1713744000000L,
                "sess-2:asr.final:3",
                new TranslationResultPayload("你好", "hello", "zh-CN", "en-US", "placeholder"));

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> service.toTtsRequestEvent(invalid));
        assertTrue(exception.getMessage().contains("Unsupported translation.result eventType"));
    }
}
