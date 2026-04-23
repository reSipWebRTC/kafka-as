package com.kafkaasr.gateway.ws.downlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import com.kafkaasr.gateway.ws.GatewaySessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class DownlinkE2EStabilityTests {

    @Mock
    private GatewayCompensationPublisher compensationPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GatewaySessionRegistry sessionRegistry;
    private AsrPartialDownlinkConsumer asrPartialConsumer;
    private TranslationResultDownlinkConsumer translationResultConsumer;
    private TtsChunkDownlinkConsumer ttsChunkConsumer;
    private TtsReadyDownlinkConsumer ttsReadyConsumer;
    private SessionControlDownlinkConsumer sessionControlConsumer;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        sessionRegistry = new GatewaySessionRegistry();
        GatewayDownlinkPublisher downlinkPublisher = new GatewayDownlinkPublisher(objectMapper, sessionRegistry);

        GatewayDownlinkProperties properties = new GatewayDownlinkProperties();
        properties.setRetryMaxAttempts(3);
        TimedIdempotencyGuard idempotencyGuard = new TimedIdempotencyGuard(properties);
        meterRegistry = new SimpleMeterRegistry();

        asrPartialConsumer = new AsrPartialDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                idempotencyGuard,
                compensationPublisher,
                properties,
                meterRegistry);
        translationResultConsumer = new TranslationResultDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                idempotencyGuard,
                compensationPublisher,
                properties,
                meterRegistry);
        ttsChunkConsumer = new TtsChunkDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                idempotencyGuard,
                compensationPublisher,
                properties,
                meterRegistry);
        ttsReadyConsumer = new TtsReadyDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                idempotencyGuard,
                compensationPublisher,
                properties,
                meterRegistry);
        sessionControlConsumer = new SessionControlDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                idempotencyGuard,
                compensationPublisher,
                properties,
                meterRegistry);
    }

    @Test
    void downlinkPathPublishesPartialFinalAndClosedInOrder() {
        BoundConnection connection = bindSession("sess-1");

        StepVerifier.create(connection.context().outbound().take(5))
                .then(() -> asrPartialConsumer.onMessage(asrPartialEvent("sess-1", 9L, "hello", "idem-p-9")))
                .assertNext(payload -> assertSubtitlePartial(payload, "sess-1", 9L, "hello"))
                .then(() -> translationResultConsumer.onMessage(
                        translationResultEvent("sess-1", 10L, "hello", "bonjour", "idem-f-10")))
                .assertNext(payload -> assertSubtitleFinal(payload, "sess-1", 10L, "bonjour"))
                .then(() -> ttsChunkConsumer.onMessage(
                        ttsChunkEvent("sess-1", 11L, "AQID", "audio/wav", 16000, 1, false, "idem-tts-c-11")))
                .assertNext(payload -> assertTtsChunk(payload, "sess-1", 11L, "AQID", "audio/wav", 16000, 1, false))
                .then(() -> ttsReadyConsumer.onMessage(
                        ttsReadyEvent("sess-1", 12L, "https://cdn.local/tts/sess-1.wav", "audio/wav", 16000, 900L, "tts_v1_sess_1", "idem-tts-r-12")))
                .assertNext(payload -> assertTtsReady(payload, "sess-1", 12L, "https://cdn.local/tts/sess-1.wav", "audio/wav", 16000, 900L, "tts_v1_sess_1"))
                .then(() -> sessionControlConsumer.onMessage(sessionClosedEvent("sess-1", 11L, "client.stop", "idem-c-11")))
                .assertNext(payload -> assertSessionClosed(payload, "sess-1", "client.stop"))
                .verifyComplete();

        verify(connection.session()).close(CloseStatus.NORMAL);
        assertCounter("subtitle.partial", "success", "OK", 1d);
        assertCounter("subtitle.final", "success", "OK", 1d);
        assertCounter("tts.chunk", "success", "OK", 1d);
        assertCounter("tts.ready", "success", "OK", 1d);
        assertCounter("session.closed", "success", "OK", 1d);
    }

    @Test
    void recordsSuccessDuplicateAndErrorCountersForPartialDownlink() {
        BoundConnection connection = bindSession("sess-2");
        String payload = asrPartialEvent("sess-2", 3L, "hola", "idem-p-3");

        StepVerifier.create(connection.context().outbound().take(1))
                .then(() -> asrPartialConsumer.onMessage(payload))
                .then(() -> asrPartialConsumer.onMessage(payload))
                .assertNext(message -> assertSubtitlePartial(message, "sess-2", 3L, "hola"))
                .verifyComplete();

        assertThrows(IllegalArgumentException.class, () -> asrPartialConsumer.onMessage("{invalid-json"));

        assertCounter("subtitle.partial", "success", "OK", 1d);
        assertCounter("subtitle.partial", "duplicate", "DUPLICATE", 1d);
        assertCounter("subtitle.partial", "error", "INVALID_PAYLOAD", 1d);
    }

    @Test
    void dropsLateMessagesAfterSessionClosed() {
        BoundConnection connection = bindSession("sess-3");

        StepVerifier.create(connection.context().outbound())
                .then(() -> sessionControlConsumer.onMessage(sessionClosedEvent("sess-3", 7L, "client.stop", "idem-c-7")))
                .then(() -> translationResultConsumer.onMessage(
                        translationResultEvent("sess-3", 8L, "later", "late", "idem-f-8")))
                .assertNext(payload -> assertSessionClosed(payload, "sess-3", "client.stop"))
                .expectComplete()
                .verify();

        verify(connection.session()).close(CloseStatus.NORMAL);
    }

    private BoundConnection bindSession(String sessionId) {
        WebSocketSession webSocketSession = mock(WebSocketSession.class);
        when(webSocketSession.getId()).thenReturn("conn-" + sessionId);
        lenient().when(webSocketSession.close(any())).thenReturn(Mono.empty());
        GatewaySessionRegistry.ConnectionContext context = sessionRegistry.openConnection(webSocketSession);
        sessionRegistry.bindSession(context.connectionId(), sessionId);
        return new BoundConnection(webSocketSession, context);
    }

    private void assertSubtitlePartial(String payload, String sessionId, long seq, String text) {
        JsonNode json = parse(payload);
        assertEquals("subtitle.partial", json.path("type").asText());
        assertEquals(sessionId, json.path("sessionId").asText());
        assertEquals(seq, json.path("seq").asLong());
        assertEquals(text, json.path("text").asText());
    }

    private void assertSubtitleFinal(String payload, String sessionId, long seq, String text) {
        JsonNode json = parse(payload);
        assertEquals("subtitle.final", json.path("type").asText());
        assertEquals(sessionId, json.path("sessionId").asText());
        assertEquals(seq, json.path("seq").asLong());
        assertEquals(text, json.path("text").asText());
    }

    private void assertSessionClosed(String payload, String sessionId, String reason) {
        JsonNode json = parse(payload);
        assertEquals("session.closed", json.path("type").asText());
        assertEquals(sessionId, json.path("sessionId").asText());
        assertEquals(reason, json.path("reason").asText());
    }

    private void assertTtsChunk(
            String payload,
            String sessionId,
            long seq,
            String audioBase64,
            String codec,
            int sampleRate,
            int chunkSeq,
            boolean lastChunk) {
        JsonNode json = parse(payload);
        assertEquals("tts.chunk", json.path("type").asText());
        assertEquals(sessionId, json.path("sessionId").asText());
        assertEquals(seq, json.path("seq").asLong());
        assertEquals(audioBase64, json.path("audioBase64").asText());
        assertEquals(codec, json.path("codec").asText());
        assertEquals(sampleRate, json.path("sampleRate").asInt());
        assertEquals(chunkSeq, json.path("chunkSeq").asInt());
        assertEquals(lastChunk, json.path("lastChunk").asBoolean());
    }

    private void assertTtsReady(
            String payload,
            String sessionId,
            long seq,
            String playbackUrl,
            String codec,
            int sampleRate,
            long durationMs,
            String cacheKey) {
        JsonNode json = parse(payload);
        assertEquals("tts.ready", json.path("type").asText());
        assertEquals(sessionId, json.path("sessionId").asText());
        assertEquals(seq, json.path("seq").asLong());
        assertEquals(playbackUrl, json.path("playbackUrl").asText());
        assertEquals(codec, json.path("codec").asText());
        assertEquals(sampleRate, json.path("sampleRate").asInt());
        assertEquals(durationMs, json.path("durationMs").asLong());
        assertEquals(cacheKey, json.path("cacheKey").asText());
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw new AssertionError("Failed to parse websocket payload: " + payload, exception);
        }
    }

    private void assertCounter(String type, String result, String code, double expected) {
        Counter counter = meterRegistry.find("gateway.downlink.messages.total")
                .tags("type", type, "result", result, "code", code)
                .counter();
        assertNotNull(counter, "Expected counter not found for type=%s result=%s code=%s"
                .formatted(type, result, code));
        assertEquals(expected, counter.count(), 1e-6);
    }

    private String asrPartialEvent(String sessionId, long seq, String text, String idempotencyKey) {
        return """
                {
                  "eventId": "evt-asr-%d",
                  "eventType": "asr.partial",
                  "eventVersion": "v1",
                  "traceId": "trc-1",
                  "sessionId": "%s",
                  "tenantId": "tenant-a",
                  "roomId": null,
                  "producer": "asr-worker",
                  "seq": %d,
                  "ts": 1713744001000,
                  "idempotencyKey": "%s",
                  "payload": {
                    "text": "%s",
                    "language": "en-US",
                    "confidence": 0.99,
                    "stable": false
                  }
                }
                """.formatted(seq, sessionId, seq, idempotencyKey, text);
    }

    private String translationResultEvent(
            String sessionId,
            long seq,
            String sourceText,
            String translatedText,
            String idempotencyKey) {
        return """
                {
                  "eventId": "evt-trans-%d",
                  "eventType": "translation.result",
                  "eventVersion": "v1",
                  "traceId": "trc-1",
                  "sessionId": "%s",
                  "tenantId": "tenant-a",
                  "roomId": null,
                  "producer": "translation-worker",
                  "seq": %d,
                  "ts": 1713744002000,
                  "idempotencyKey": "%s",
                  "payload": {
                    "sourceText": "%s",
                    "translatedText": "%s",
                    "sourceLang": "en-US",
                    "targetLang": "fr-FR",
                    "engine": "placeholder"
                  }
                }
                """.formatted(seq, sessionId, seq, idempotencyKey, sourceText, translatedText);
    }

    private String sessionClosedEvent(String sessionId, long seq, String reason, String idempotencyKey) {
        return """
                {
                  "eventId": "evt-ctrl-%d",
                  "eventType": "session.control",
                  "eventVersion": "v1",
                  "traceId": "trc-1",
                  "sessionId": "%s",
                  "tenantId": "tenant-a",
                  "roomId": null,
                  "producer": "session-orchestrator",
                  "seq": %d,
                  "ts": 1713744003000,
                  "idempotencyKey": "%s",
                  "payload": {
                    "action": "STOP",
                    "status": "CLOSED",
                    "sourceLang": "zh-CN",
                    "targetLang": "en-US",
                    "reason": "%s"
                  }
                }
                """.formatted(seq, sessionId, seq, idempotencyKey, reason);
    }

    private String ttsChunkEvent(
            String sessionId,
            long seq,
            String audioBase64,
            String codec,
            int sampleRate,
            int chunkSeq,
            boolean lastChunk,
            String idempotencyKey) {
        return """
                {
                  "eventId": "evt-tts-c-%d",
                  "eventType": "tts.chunk",
                  "eventVersion": "v1",
                  "traceId": "trc-1",
                  "sessionId": "%s",
                  "tenantId": "tenant-a",
                  "roomId": null,
                  "producer": "tts-orchestrator",
                  "seq": %d,
                  "ts": 1713744002500,
                  "idempotencyKey": "%s",
                  "payload": {
                    "audioBase64": "%s",
                    "codec": "%s",
                    "sampleRate": %d,
                    "chunkSeq": %d,
                    "lastChunk": %s
                  }
                }
                """.formatted(seq, sessionId, seq, idempotencyKey, audioBase64, codec, sampleRate, chunkSeq, lastChunk);
    }

    private String ttsReadyEvent(
            String sessionId,
            long seq,
            String playbackUrl,
            String codec,
            int sampleRate,
            long durationMs,
            String cacheKey,
            String idempotencyKey) {
        return """
                {
                  "eventId": "evt-tts-r-%d",
                  "eventType": "tts.ready",
                  "eventVersion": "v1",
                  "traceId": "trc-1",
                  "sessionId": "%s",
                  "tenantId": "tenant-a",
                  "roomId": null,
                  "producer": "tts-orchestrator",
                  "seq": %d,
                  "ts": 1713744002600,
                  "idempotencyKey": "%s",
                  "payload": {
                    "playbackUrl": "%s",
                    "codec": "%s",
                    "sampleRate": %d,
                    "durationMs": %d,
                    "cacheKey": "%s"
                  }
                }
                """.formatted(seq, sessionId, seq, idempotencyKey, playbackUrl, codec, sampleRate, durationMs, cacheKey);
    }

    private record BoundConnection(
            WebSocketSession session,
            GatewaySessionRegistry.ConnectionContext context) {
    }
}
