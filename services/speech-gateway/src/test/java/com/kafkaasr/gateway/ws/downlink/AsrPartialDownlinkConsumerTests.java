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
class AsrPartialDownlinkConsumerTests {

    @Mock
    private GatewayDownlinkPublisher downlinkPublisher;

    private AsrPartialDownlinkConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new AsrPartialDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                new SimpleMeterRegistry());
    }

    @Test
    void forwardsAsrPartialAsSubtitlePartial() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestAsrPartialEvent(
                "evt-1",
                "asr.partial",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "asr-worker",
                9L,
                1713744001000L,
                "sess-1:asr.partial:9",
                new TestAsrPartialPayload("hello", "en-US", 0.99, false)));

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

    private record TestAsrPartialEvent(
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
            TestAsrPartialPayload payload) {
    }

    private record TestAsrPartialPayload(
            String text,
            String language,
            double confidence,
            boolean stable) {
    }
}
