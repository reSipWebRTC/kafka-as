package com.kafkaasr.gateway.ws.downlink;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
class AsrFinalDownlinkConsumerTests {

    @Mock
    private GatewayDownlinkPublisher downlinkPublisher;

    private AsrFinalDownlinkConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new AsrFinalDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                new SimpleMeterRegistry());
    }

    @Test
    void forwardsAsrFinalAsSubtitlePartial() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestAsrFinalEvent(
                "evt-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "asr-worker",
                9L,
                1713744001000L,
                "sess-1:asr.final:9",
                new TestAsrFinalPayload("hello", "en-US", 0.99, true)));

        when(downlinkPublisher.publishSubtitlePartial(eq("sess-1"), eq(9L), eq("hello")))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(downlinkPublisher).publishSubtitlePartial("sess-1", 9L, "hello");
    }

    @Test
    void rejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{invalid-json"));

        verify(downlinkPublisher, never()).publishSubtitlePartial(eq("sess-1"), anyLong(), eq("hello"));
    }

    private record TestAsrFinalEvent(
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
            TestAsrFinalPayload payload) {
    }

    private record TestAsrFinalPayload(
            String text,
            String language,
            double confidence,
            boolean stable) {
    }
}
