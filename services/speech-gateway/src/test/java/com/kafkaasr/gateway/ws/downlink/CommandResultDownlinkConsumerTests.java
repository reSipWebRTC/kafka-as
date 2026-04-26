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
class CommandResultDownlinkConsumerTests {

    @Mock
    private GatewayDownlinkPublisher downlinkPublisher;

    @Mock
    private GatewayCompensationPublisher compensationPublisher;

    private CommandResultDownlinkConsumer consumer;
    private GatewayDownlinkProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GatewayDownlinkProperties();
        properties.setRetryMaxAttempts(2);
        consumer = new CommandResultDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                new TimedIdempotencyGuard(properties),
                compensationPublisher,
                properties,
                new SimpleMeterRegistry());
    }

    @Test
    void forwardsCommandResult() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestCommandResultEvent(
                "evt-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                22L,
                1713744002000L,
                "sess-1:command.result:22",
                new TestCommandResultPayload(
                        "exec-1",
                        "ok",
                        "OK",
                        "执行成功",
                        false,
                        "",
                        0)));

        when(downlinkPublisher.publishCommandResult(
                        eq("sess-1"),
                        eq(22L),
                        eq("exec-1"),
                        eq("ok"),
                        eq("OK"),
                        eq("执行成功"),
                        eq(false),
                        eq(""),
                        eq(0)))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(downlinkPublisher).publishCommandResult(
                "sess-1",
                22L,
                "exec-1",
                "ok",
                "OK",
                "执行成功",
                false,
                "",
                0);
    }

    @Test
    void rejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{invalid-json"));

        verify(downlinkPublisher, never()).publishCommandResult(
                anyString(),
                anyLong(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyBoolean(),
                anyString(),
                anyInt());
    }

    private record TestCommandResultEvent(
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
            TestCommandResultPayload payload) {
    }

    private record TestCommandResultPayload(
            String executionId,
            String status,
            String code,
            String replyText,
            boolean retryable,
            String confirmToken,
            int expiresInSec) {
    }
}
