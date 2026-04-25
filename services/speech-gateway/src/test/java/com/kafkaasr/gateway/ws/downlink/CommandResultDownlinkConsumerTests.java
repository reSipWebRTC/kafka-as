package com.kafkaasr.gateway.ws.downlink;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    void forwardsCommandResultToWebSocketDownlink() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestCommandResultEvent(
                "evt-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                "user-a",
                null,
                "command-worker",
                11L,
                1713744001000L,
                "sess-1:command.result:11",
                new TestCommandResultPayload(
                        "CONFIRM_REQUIRED",
                        "DEVICE_CONFIRM_NEEDED",
                        "Please confirm turning on the light",
                        "",
                        false,
                        "cfm-1",
                        45,
                        "device_control",
                        "light.on")));

        when(downlinkPublisher.publishCommandResult(
                        eq("sess-1"),
                        eq(11L),
                        eq("CONFIRM_REQUIRED"),
                        eq("DEVICE_CONFIRM_NEEDED"),
                        eq("Please confirm turning on the light"),
                        eq(false),
                        eq("cfm-1"),
                        eq(45L)))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(downlinkPublisher).publishCommandResult(
                "sess-1",
                11L,
                "CONFIRM_REQUIRED",
                "DEVICE_CONFIRM_NEEDED",
                "Please confirm turning on the light",
                false,
                "cfm-1",
                45L);
    }

    @Test
    void fallsBackToTtsTextWhenReplyTextMissing() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestCommandResultEvent(
                "evt-2",
                "command.result",
                "v1",
                "trc-2",
                "sess-2",
                "tenant-a",
                "user-a",
                null,
                "command-worker",
                12L,
                1713744001000L,
                "sess-2:command.result:12",
                new TestCommandResultPayload(
                        "SUCCESS",
                        "OK",
                        " ",
                        "Device is turned on",
                        false,
                        null,
                        null,
                        "device_control",
                        "light.on")));

        when(downlinkPublisher.publishCommandResult(
                        eq("sess-2"),
                        eq(12L),
                        eq("SUCCESS"),
                        eq("OK"),
                        eq("Device is turned on"),
                        eq(false),
                        eq(null),
                        eq(null)))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(downlinkPublisher).publishCommandResult(
                "sess-2",
                12L,
                "SUCCESS",
                "OK",
                "Device is turned on",
                false,
                null,
                null);
    }

    @Test
    void dropsDuplicateByIdempotencyKey() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestCommandResultEvent(
                "evt-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                "user-a",
                null,
                "command-worker",
                11L,
                1713744001000L,
                "sess-1:command.result:11",
                new TestCommandResultPayload(
                        "SUCCESS",
                        "OK",
                        "done",
                        "",
                        false,
                        null,
                        null,
                        null,
                        null)));

        when(downlinkPublisher.publishCommandResult(
                        eq("sess-1"),
                        eq(11L),
                        eq("SUCCESS"),
                        eq("OK"),
                        eq("done"),
                        eq(false),
                        eq(null),
                        eq(null)))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        verify(downlinkPublisher, times(1)).publishCommandResult(
                "sess-1",
                11L,
                "SUCCESS",
                "OK",
                "done",
                false,
                null,
                null);
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
                anyBoolean(),
                any(),
                any());
    }

    @Test
    void emitsCompensationAfterRepeatedFailureThreshold() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestCommandResultEvent(
                "evt-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                "user-a",
                null,
                "command-worker",
                11L,
                1713744001000L,
                "sess-1:command.result:11",
                new TestCommandResultPayload(
                        "SUCCESS",
                        "OK",
                        "done",
                        "",
                        false,
                        null,
                        null,
                        null,
                        null)));

        when(downlinkPublisher.publishCommandResult(
                        eq("sess-1"),
                        eq(11L),
                        eq("SUCCESS"),
                        eq("OK"),
                        eq("done"),
                        eq(false),
                        eq(null),
                        eq(null)))
                .thenReturn(Mono.error(new IllegalStateException("downlink failed")));

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));

        verify(compensationPublisher).publish(anyString(), eq(payload), any(RuntimeException.class));
    }

    private record TestCommandResultEvent(
            String eventId,
            String eventType,
            String eventVersion,
            String traceId,
            String sessionId,
            String tenantId,
            String userId,
            String roomId,
            String producer,
            long seq,
            long ts,
            String idempotencyKey,
            TestCommandResultPayload payload) {
    }

    private record TestCommandResultPayload(
            String status,
            String code,
            String replyText,
            String ttsText,
            boolean retryable,
            String confirmToken,
            Integer expiresInSec,
            String intent,
            String subIntent) {
    }
}
