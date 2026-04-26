package com.kafkaasr.gateway.ws.downlink;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
class CommandDispatchDownlinkConsumerTests {

    @Mock
    private GatewayDownlinkPublisher downlinkPublisher;

    @Mock
    private GatewayCompensationPublisher compensationPublisher;

    private CommandDispatchDownlinkConsumer consumer;
    private GatewayDownlinkProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GatewayDownlinkProperties();
        properties.setRetryMaxAttempts(2);
        consumer = new CommandDispatchDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                new TimedIdempotencyGuard(properties),
                compensationPublisher,
                properties,
                new SimpleMeterRegistry());
    }

    @Test
    void forwardsCommandDispatch() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestCommandDispatchEvent(
                "evt-1",
                "command.dispatch",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                21L,
                1713744001000L,
                "sess-1:command.dispatch:21",
                new TestCommandDispatchPayload(
                        "exec-1",
                        "打开客厅灯",
                        "CONTROL",
                        "LIGHT_ON",
                        true,
                        "cfm-1",
                        30,
                        "trc-1")));

        when(downlinkPublisher.publishCommandDispatch(
                        eq("sess-1"),
                        eq(21L),
                        eq("exec-1"),
                        eq("打开客厅灯"),
                        eq("CONTROL"),
                        eq("LIGHT_ON"),
                        eq(true),
                        eq("cfm-1"),
                        eq(30),
                        eq("trc-1")))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(downlinkPublisher).publishCommandDispatch(
                "sess-1",
                21L,
                "exec-1",
                "打开客厅灯",
                "CONTROL",
                "LIGHT_ON",
                true,
                "cfm-1",
                30,
                "trc-1");
    }

    @Test
    void rejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{invalid-json"));

        verify(downlinkPublisher, never()).publishCommandDispatch(
                anyString(),
                anyLong(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyBoolean(),
                anyString(),
                anyInt(),
                anyString());
    }

    private record TestCommandDispatchEvent(
            String eventId,
            String eventType,
            String eventVersion,
            String traceId,
            String sessionId,
            String tenantId,
            String roomId,
            String userId,
            String producer,
            long seq,
            long ts,
            String idempotencyKey,
            TestCommandDispatchPayload payload) {
    }

    private record TestCommandDispatchPayload(
            String executionId,
            String commandText,
            String intent,
            String subIntent,
            boolean confirmRequired,
            String confirmToken,
            int expiresInSec,
            String traceId) {
    }
}
