package com.kafkaasr.tts.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.events.CommandResultEvent;
import com.kafkaasr.tts.events.CommandResultPayload;
import com.kafkaasr.tts.events.TranslationResultEvent;
import com.kafkaasr.tts.events.TranslationResultPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TtsRequestPipelineServiceTests {

    private static final CommandResultSpeechTemplateRenderer COMMAND_RENDERER =
            new CommandResultSpeechTemplateRenderer();

    @Test
    void mapsTranslationResultToTtsPipelineEvents() {
        TtsKafkaProperties properties = new TtsKafkaProperties();
        properties.setProducerId("tts-orchestrator");
        properties.setDefaultVoice("en-US-neural-a");
        properties.setStreamEnabled(true);
        properties.setTtsChunkCodec("audio/pcm");
        properties.setTtsChunkSampleRate(16000);
        properties.setTtsReadyPlaybackUrlPrefix("https://cdn.local/tts");

        VoicePolicy voicePolicy = (event, language, defaultVoice) -> defaultVoice;
        TtsSynthesisEngine synthesisEngine = (event, input) -> new TtsSynthesisEngine.SynthesisPlan(
                input.text(),
                input.language(),
                input.voice(),
                input.stream());
        TtsRequestPipelineService service = new TtsRequestPipelineService(
                voicePolicy,
                synthesisEngine,
                COMMAND_RENDERER,
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

        TtsRequestPipelineService.PipelineOutput out = service.toPipelineEvents(input);

        assertTrue(out.requestEvent().eventId().startsWith("evt_"));
        assertEquals("tts.request", out.requestEvent().eventType());
        assertEquals("v1", out.requestEvent().eventVersion());
        assertEquals("trc-1", out.requestEvent().traceId());
        assertEquals("sess-1", out.requestEvent().sessionId());
        assertEquals("tenant-a", out.requestEvent().tenantId());
        assertEquals("tts-orchestrator", out.requestEvent().producer());
        assertEquals(9L, out.requestEvent().seq());
        assertEquals(Instant.parse("2026-04-22T00:00:00Z").toEpochMilli(), out.requestEvent().ts());
        assertEquals("sess-1:tts.request:9", out.requestEvent().idempotencyKey());

        assertEquals("hello", out.requestEvent().payload().text());
        assertEquals("en-US", out.requestEvent().payload().language());
        assertEquals("en-US-neural-a", out.requestEvent().payload().voice());
        assertTrue(out.requestEvent().payload().stream());
        assertTrue(out.requestEvent().payload().cacheKey().startsWith("tts:v1:"));

        assertEquals("tts.chunk", out.chunkEvent().eventType());
        assertEquals("sess-1:tts.chunk:9", out.chunkEvent().idempotencyKey());
        assertEquals("audio/pcm", out.chunkEvent().payload().codec());
        assertEquals(16000, out.chunkEvent().payload().sampleRate());
        assertEquals(0, out.chunkEvent().payload().chunkSeq());
        assertTrue(out.chunkEvent().payload().lastChunk());
        assertTrue(out.chunkEvent().payload().audioBase64().length() > 0);

        assertEquals("tts.ready", out.readyEvent().eventType());
        assertEquals("sess-1:tts.ready:9", out.readyEvent().idempotencyKey());
        assertEquals("audio/pcm", out.readyEvent().payload().codec());
        assertEquals(16000, out.readyEvent().payload().sampleRate());
        assertTrue(out.readyEvent().payload().durationMs() >= 250L);
        assertEquals(
                "https://cdn.local/tts/" + out.requestEvent().payload().cacheKey() + ".wav",
                out.readyEvent().payload().playbackUrl());
        assertEquals(out.requestEvent().payload().cacheKey(), out.readyEvent().payload().cacheKey());
    }

    @Test
    void cacheKeyIsDeterministicForSameVoiceLanguageAndText() {
        TtsKafkaProperties properties = new TtsKafkaProperties();
        properties.setDefaultVoice("voice-default");

        TtsRequestPipelineService service = new TtsRequestPipelineService(
                (event, language, defaultVoice) -> "voice-A",
                (event, input) -> new TtsSynthesisEngine.SynthesisPlan(
                        input.text(),
                        input.language(),
                        input.voice(),
                        input.stream()),
                COMMAND_RENDERER,
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

        TtsRequestPipelineService.PipelineOutput out1 = service.toPipelineEvents(input);
        TtsRequestPipelineService.PipelineOutput out2 = service.toPipelineEvents(input);

        assertEquals(out1.requestEvent().payload().cacheKey(), out2.requestEvent().payload().cacheKey());
    }

    @Test
    void rejectsUnexpectedEventType() {
        TtsRequestPipelineService service = new TtsRequestPipelineService(
                (event, language, defaultVoice) -> "voice-default",
                (event, input) -> new TtsSynthesisEngine.SynthesisPlan(
                        input.text(),
                        input.language(),
                        input.voice(),
                        input.stream()),
                COMMAND_RENDERER,
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

    @Test
    void usesSynthesisEngineOverridesForFinalPayload() {
        TtsKafkaProperties properties = new TtsKafkaProperties();
        properties.setProducerId("tts-orchestrator");
        properties.setDefaultVoice("en-US-neural-a");
        properties.setStreamEnabled(true);

        TtsRequestPipelineService service = new TtsRequestPipelineService(
                (event, language, defaultVoice) -> defaultVoice,
                (event, input) -> new TtsSynthesisEngine.SynthesisPlan(
                        "HELLO",
                        "en-GB",
                        "en-GB-neural-b",
                        false),
                COMMAND_RENDERER,
                properties,
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        TranslationResultEvent input = new TranslationResultEvent(
                "evt-in-3",
                "translation.result",
                "v1",
                "trc-3",
                "sess-3",
                "tenant-a",
                null,
                "translation-worker",
                11L,
                1713744000000L,
                "sess-3:translation.result:11",
                new TranslationResultPayload("你好", "hello", "zh-CN", "en-US", "placeholder"));

        TtsRequestPipelineService.PipelineOutput out = service.toPipelineEvents(input);

        assertEquals("HELLO", out.requestEvent().payload().text());
        assertEquals("en-GB", out.requestEvent().payload().language());
        assertEquals("en-GB-neural-b", out.requestEvent().payload().voice());
        assertEquals(false, out.requestEvent().payload().stream());
    }

    @Test
    void mapsCommandResultToTtsPipelineEventsWithTemplateFallback() {
        TtsKafkaProperties properties = new TtsKafkaProperties();
        properties.setProducerId("tts-orchestrator");
        properties.setDefaultVoice("zh-CN-standard-A");
        properties.setCommandResultDefaultLanguage("zh-CN");
        properties.setTtsReadyPlaybackUrlPrefix("https://cdn.local/tts");

        TtsRequestPipelineService service = new TtsRequestPipelineService(
                (event, language, defaultVoice) -> defaultVoice,
                (event, input) -> new TtsSynthesisEngine.SynthesisPlan(
                        input.text(),
                        input.language(),
                        input.voice(),
                        input.stream()),
                COMMAND_RENDERER,
                properties,
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        CommandResultEvent input = new CommandResultEvent(
                "evt-cmd-1",
                "command.result",
                "v1",
                "trc-cmd-1",
                "sess-cmd-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                12L,
                1713744000000L,
                "sess-cmd-1:command.result:12",
                new CommandResultPayload(
                        "exec-1",
                        "CLIENT_BRIDGE",
                        "cancelled",
                        "COMMAND_CONFIRM_TIMEOUT",
                        "",
                        false,
                        2,
                        3,
                        "cfm-1",
                        "NO_INPUT_TIMEOUT",
                        null,
                        30,
                        "CONTROL",
                        "power_off"));

        TtsRequestPipelineService.PipelineOutput out = service.toPipelineEvents(input);

        assertEquals("tts.request", out.requestEvent().eventType());
        assertEquals("sess-cmd-1", out.requestEvent().sessionId());
        assertEquals("tenant-a", out.requestEvent().tenantId());
        assertEquals(12L, out.requestEvent().seq());
        assertEquals("zh-CN", out.requestEvent().payload().language());
        assertEquals("zh-CN-standard-A", out.requestEvent().payload().voice());
        assertEquals("确认超时，操作已取消。", out.requestEvent().payload().text());
        assertEquals("tts.ready", out.readyEvent().eventType());
        assertEquals(
                "https://cdn.local/tts/" + out.requestEvent().payload().cacheKey() + ".wav",
                out.readyEvent().payload().playbackUrl());
    }
}
