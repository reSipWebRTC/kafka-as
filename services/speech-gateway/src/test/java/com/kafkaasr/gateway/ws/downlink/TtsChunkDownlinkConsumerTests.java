package com.kafkaasr.gateway.ws.downlink;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
class TtsChunkDownlinkConsumerTests {

    @Mock
    private GatewayDownlinkPublisher downlinkPublisher;

    @Mock
    private GatewayCompensationPublisher compensationPublisher;

    private TtsChunkDownlinkConsumer consumer;
    private GatewayDownlinkProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GatewayDownlinkProperties();
        properties.setRetryMaxAttempts(2);
        consumer = new TtsChunkDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                new TimedIdempotencyGuard(properties),
                compensationPublisher,
                properties,
                new SimpleMeterRegistry());
    }

    @Test
    void forwardsTtsChunkToWebsocket() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestTtsChunkEvent(
                "evt-1",
                "tts.chunk",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                11L,
                1713744001000L,
                "sess-1:tts.chunk:11",
                new TestTtsChunkPayload("AQID", "audio/wav", 16000, 3, true)));

        when(downlinkPublisher.publishTtsChunk(eq("sess-1"), eq(11L), eq("AQID"), eq("audio/wav"), eq(16000), eq(3), eq(true)))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(downlinkPublisher).publishTtsChunk("sess-1", 11L, "AQID", "audio/wav", 16000, 3, true);
    }

    @Test
    void rejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{invalid-json"));

        verify(downlinkPublisher, never()).publishTtsChunk(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void dropsDuplicateEventByIdempotencyKey() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestTtsChunkEvent(
                "evt-1",
                "tts.chunk",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                11L,
                1713744001000L,
                "sess-1:tts.chunk:11",
                new TestTtsChunkPayload("AQID", "audio/wav", 16000, 3, true)));
        when(downlinkPublisher.publishTtsChunk(eq("sess-1"), eq(11L), eq("AQID"), eq("audio/wav"), eq(16000), eq(3), eq(true)))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        verify(downlinkPublisher, times(1)).publishTtsChunk("sess-1", 11L, "AQID", "audio/wav", 16000, 3, true);
    }

    @Test
    void emitsCompensationSignalAfterRepeatedFailureThreshold() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestTtsChunkEvent(
                "evt-1",
                "tts.chunk",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                11L,
                1713744001000L,
                "sess-1:tts.chunk:11",
                new TestTtsChunkPayload("AQID", "audio/wav", 16000, 3, true)));
        when(downlinkPublisher.publishTtsChunk(eq("sess-1"), eq(11L), eq("AQID"), eq("audio/wav"), eq(16000), eq(3), eq(true)))
                .thenReturn(Mono.error(new IllegalStateException("downlink failed")));

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));

        verify(compensationPublisher).publish(anyString(), eq(payload), any(RuntimeException.class));
    }

    private record TestTtsChunkEvent(
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
            TestTtsChunkPayload payload) {
    }

    private record TestTtsChunkPayload(
            String audioBase64,
            String codec,
            int sampleRate,
            int chunkSeq,
            boolean lastChunk) {
    }
}
