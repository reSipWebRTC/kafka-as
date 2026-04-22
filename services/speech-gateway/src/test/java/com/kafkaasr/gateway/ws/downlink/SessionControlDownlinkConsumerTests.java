package com.kafkaasr.gateway.ws.downlink;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
class SessionControlDownlinkConsumerTests {

    @Mock
    private GatewayDownlinkPublisher downlinkPublisher;

    @Mock
    private GatewayCompensationPublisher compensationPublisher;

    private SessionControlDownlinkConsumer consumer;
    private GatewayDownlinkProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GatewayDownlinkProperties();
        properties.setRetryMaxAttempts(2);
        consumer = new SessionControlDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                new TimedIdempotencyGuard(properties),
                compensationPublisher,
                properties,
                new SimpleMeterRegistry());
    }

    @Test
    void publishesSessionClosedWhenStatusClosed() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestSessionControlEvent(
                "evt-1",
                "session.control",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "session-orchestrator",
                10L,
                1713744002000L,
                "sess-1:session.control:10",
                new TestSessionControlPayload("STOP", "CLOSED", "zh-CN", "en-US", "client.stop")));

        when(downlinkPublisher.publishSessionClosed(eq("sess-1"), eq("client.stop")))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(downlinkPublisher).publishSessionClosed("sess-1", "client.stop");
    }

    @Test
    void ignoresNonClosedStatus() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestSessionControlEvent(
                "evt-1",
                "session.control",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "session-orchestrator",
                10L,
                1713744002000L,
                "sess-1:session.control:10",
                new TestSessionControlPayload("START", "STREAMING", "zh-CN", "en-US", null)));

        consumer.onMessage(payload);

        verify(downlinkPublisher, never()).publishSessionClosed("sess-1", "client.stop");
    }

    @Test
    void rejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{invalid-json"));
        verify(downlinkPublisher, never()).publishSessionClosed("sess-1", "client.stop");
    }

    @Test
    void dropsDuplicateEventByIdempotencyKey() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestSessionControlEvent(
                "evt-1",
                "session.control",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "session-orchestrator",
                10L,
                1713744002000L,
                "sess-1:session.control:10",
                new TestSessionControlPayload("STOP", "CLOSED", "zh-CN", "en-US", "client.stop")));
        when(downlinkPublisher.publishSessionClosed(eq("sess-1"), eq("client.stop")))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        verify(downlinkPublisher, times(1)).publishSessionClosed("sess-1", "client.stop");
    }

    @Test
    void emitsCompensationSignalAfterRepeatedFailureThreshold() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestSessionControlEvent(
                "evt-1",
                "session.control",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "session-orchestrator",
                10L,
                1713744002000L,
                "sess-1:session.control:10",
                new TestSessionControlPayload("STOP", "CLOSED", "zh-CN", "en-US", "client.stop")));
        when(downlinkPublisher.publishSessionClosed(eq("sess-1"), eq("client.stop")))
                .thenReturn(Mono.error(new IllegalStateException("downlink failed")));

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));

        verify(compensationPublisher).publish(anyString(), eq(payload), any(RuntimeException.class));
    }

    private record TestSessionControlEvent(
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
            TestSessionControlPayload payload) {
    }

    private record TestSessionControlPayload(
            String action,
            String status,
            String sourceLang,
            String targetLang,
            String reason) {
    }
}
