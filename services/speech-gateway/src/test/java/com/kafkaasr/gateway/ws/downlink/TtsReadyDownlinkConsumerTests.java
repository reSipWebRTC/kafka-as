package com.kafkaasr.gateway.ws.downlink;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class TtsReadyDownlinkConsumerTests {

    @Mock
    private GatewayDownlinkPublisher downlinkPublisher;

    @Mock
    private GatewayCompensationPublisher compensationPublisher;

    private TtsReadyDownlinkConsumer consumer;
    private GatewayDownlinkProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GatewayDownlinkProperties();
        properties.setRetryMaxAttempts(2);
        consumer = new TtsReadyDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                new TimedIdempotencyGuard(properties),
                compensationPublisher,
                properties,
                new SimpleMeterRegistry());
    }

    @Test
    void forwardsTtsReadyToWebsocket() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestTtsReadyEvent(
                "evt-1",
                "tts.ready",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                12L,
                1713744002000L,
                "sess-1:tts.ready:12",
                new TestTtsReadyPayload("https://cdn.local/tts/abc.wav", "audio/wav", 16000, 1800L, "tts_v1_abc")));

        when(downlinkPublisher.publishTtsReady(
                        eq("sess-1"),
                        eq(12L),
                        eq("https://cdn.local/tts/abc.wav"),
                        eq("audio/wav"),
                        eq(16000),
                        eq(1800L),
                        eq("tts_v1_abc")))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(downlinkPublisher).publishTtsReady(
                "sess-1",
                12L,
                "https://cdn.local/tts/abc.wav",
                "audio/wav",
                16000,
                1800L,
                "tts_v1_abc");
    }

    @Test
    void rejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{invalid-json"));

        verify(downlinkPublisher, never()).publishTtsReady(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyLong(), anyString());
    }

    @Test
    void dropsDuplicateEventByIdempotencyKey() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestTtsReadyEvent(
                "evt-1",
                "tts.ready",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                12L,
                1713744002000L,
                "sess-1:tts.ready:12",
                new TestTtsReadyPayload("https://cdn.local/tts/abc.wav", "audio/wav", 16000, 1800L, "tts_v1_abc")));
        when(downlinkPublisher.publishTtsReady(
                        eq("sess-1"),
                        eq(12L),
                        eq("https://cdn.local/tts/abc.wav"),
                        eq("audio/wav"),
                        eq(16000),
                        eq(1800L),
                        eq("tts_v1_abc")))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        verify(downlinkPublisher, times(1)).publishTtsReady(
                "sess-1",
                12L,
                "https://cdn.local/tts/abc.wav",
                "audio/wav",
                16000,
                1800L,
                "tts_v1_abc");
    }

    @Test
    void emitsCompensationSignalAfterRepeatedFailureThreshold() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestTtsReadyEvent(
                "evt-1",
                "tts.ready",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                12L,
                1713744002000L,
                "sess-1:tts.ready:12",
                new TestTtsReadyPayload("https://cdn.local/tts/abc.wav", "audio/wav", 16000, 1800L, "tts_v1_abc")));
        when(downlinkPublisher.publishTtsReady(
                        eq("sess-1"),
                        eq(12L),
                        eq("https://cdn.local/tts/abc.wav"),
                        eq("audio/wav"),
                        eq(16000),
                        eq(1800L),
                        eq("tts_v1_abc")))
                .thenReturn(Mono.error(new IllegalStateException("downlink failed")));

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));

        verify(compensationPublisher).publish(anyString(), eq(payload), any(RuntimeException.class));
    }

    private record TestTtsReadyEvent(
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
            TestTtsReadyPayload payload) {
    }

    private record TestTtsReadyPayload(
            String playbackUrl,
            String codec,
            int sampleRate,
            long durationMs,
            String cacheKey) {
    }
}
