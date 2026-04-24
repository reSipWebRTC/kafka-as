package com.kafkaasr.command.kafka;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.events.AsrFinalEvent;
import com.kafkaasr.command.events.AsrFinalPayload;
import com.kafkaasr.command.events.CommandConfirmRequestEvent;
import com.kafkaasr.command.events.CommandConfirmRequestPayload;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.events.CommandResultEvent;
import com.kafkaasr.command.events.CommandResultPayload;
import com.kafkaasr.command.pipeline.CommandPipelineService;
import com.kafkaasr.command.pipeline.SmartHomeClientException;
import com.kafkaasr.command.policy.TenantRoutingPolicy;
import com.kafkaasr.command.policy.TenantRoutingPolicyResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class CommandIngressConsumerTests {

    @Mock
    private CommandPipelineService pipelineService;

    @Mock
    private CommandResultPublisher commandResultPublisher;

    @Mock
    private CommandCompensationPublisher compensationPublisher;

    @Mock
    private TenantRoutingPolicyResolver routingPolicyResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CommandIngressConsumer consumer;

    @BeforeEach
    void setUp() {
        CommandKafkaProperties properties = new CommandKafkaProperties();
        properties.setRetryMaxAttempts(2);
        consumer = new CommandIngressConsumer(
                objectMapper,
                pipelineService,
                commandResultPublisher,
                compensationPublisher,
                properties,
                routingPolicyResolver,
                new SimpleMeterRegistry());
        lenient().when(routingPolicyResolver.resolve(anyString()))
                .thenReturn(new TenantRoutingPolicy(2, 1L, ".dlq", "SMART_HOME"));
    }

    @Test
    void routesAsrFinalThroughPipelineAndPublisher() throws Exception {
        AsrFinalEvent input = asrFinalEvent("sess-1", "tenant-a", "user-a", "sess-1:asr.final:1", 1L);
        CommandResultEvent output = commandResultEvent("sess-1", "tenant-a", "user-a", 1L, "sess-1:command.result:asr.final:1");
        String payload = objectMapper.writeValueAsString(input);

        when(pipelineService.fromAsrFinal(any(), anyString())).thenReturn(output);
        when(commandResultPublisher.publish(output)).thenReturn(Mono.empty());

        consumer.onAsrFinalMessage(payload);

        verify(pipelineService).fromAsrFinal(any(), eq("user-a"));
        verify(commandResultPublisher).publish(output);
    }

    @Test
    void routesConfirmRequestThroughPipelineAndPublisher() throws Exception {
        CommandConfirmRequestEvent input = confirmEvent("sess-2", "tenant-home", "user-home", "sess-2:command.confirm.request:9", 9L);
        CommandResultEvent output = commandResultEvent(
                "sess-2",
                "tenant-home",
                "user-home",
                9L,
                "sess-2:command.result:command.confirm.request:9");
        String payload = objectMapper.writeValueAsString(input);

        when(pipelineService.fromCommandConfirmRequest(any())).thenReturn(output);
        when(commandResultPublisher.publish(output)).thenReturn(Mono.empty());

        consumer.onCommandConfirmMessage(payload);

        verify(pipelineService).fromCommandConfirmRequest(any());
        verify(commandResultPublisher).publish(output);
    }

    @Test
    void ignoresAsrFinalWhenTenantSessionModeIsNotSmartHome() throws Exception {
        AsrFinalEvent input = asrFinalEvent("sess-3", "tenant-a", "user-a", "sess-3:asr.final:3", 3L);
        String payload = objectMapper.writeValueAsString(input);
        when(routingPolicyResolver.resolve("tenant-a"))
                .thenReturn(new TenantRoutingPolicy(2, 1L, ".dlq", "TRANSLATION"));

        consumer.onAsrFinalMessage(payload);

        verify(pipelineService, never()).fromAsrFinal(any(), anyString());
        verify(commandResultPublisher, never()).publish(any());
    }

    @Test
    void rejectsMalformedAsrPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onAsrFinalMessage("{invalid-json"));
        verify(pipelineService, never()).fromAsrFinal(any(), anyString());
        verify(commandResultPublisher, never()).publish(any());
    }

    @Test
    void dropsDuplicateAsrEventByIdempotencyKey() throws Exception {
        AsrFinalEvent input = asrFinalEvent("sess-1", "tenant-a", "user-a", "sess-1:asr.final:1", 1L);
        CommandResultEvent output = commandResultEvent("sess-1", "tenant-a", "user-a", 1L, "sess-1:command.result:asr.final:1");
        String payload = objectMapper.writeValueAsString(input);

        when(pipelineService.fromAsrFinal(any(), anyString())).thenReturn(output);
        when(commandResultPublisher.publish(output)).thenReturn(Mono.empty());

        consumer.onAsrFinalMessage(payload);
        consumer.onAsrFinalMessage(payload);

        verify(pipelineService, times(1)).fromAsrFinal(any(), anyString());
        verify(commandResultPublisher, times(1)).publish(output);
    }

    @Test
    void emitsCompensationAfterRetryThreshold() throws Exception {
        AsrFinalEvent input = asrFinalEvent("sess-4", "tenant-a", "user-a", "sess-4:asr.final:4", 4L);
        String payload = objectMapper.writeValueAsString(input);

        when(routingPolicyResolver.resolve("tenant-a"))
                .thenReturn(new TenantRoutingPolicy(2, 1L, ".tenant-a.dlq", "SMART_HOME"));
        when(pipelineService.fromAsrFinal(any(), anyString()))
                .thenThrow(new SmartHomeClientException("UPSTREAM_TIMEOUT", "timeout", true));

        assertThrows(TenantAwareDlqException.class, () -> consumer.onAsrFinalMessage(payload));

        verify(pipelineService, times(2)).fromAsrFinal(any(), anyString());
        verify(compensationPublisher).publish(
                eq("asr.final"),
                eq("asr.final.tenant-a.dlq"),
                eq(payload),
                any(RuntimeException.class));
    }

    private AsrFinalEvent asrFinalEvent(
            String sessionId,
            String tenantId,
            String userId,
            String idempotencyKey,
            long seq) {
        return new AsrFinalEvent(
                "evt-in-" + seq,
                "asr.final",
                "v1",
                "trc-" + seq,
                sessionId,
                tenantId,
                userId,
                null,
                "asr-worker",
                seq,
                1713744000000L + seq,
                idempotencyKey,
                new AsrFinalPayload("turn on light", "en-US", 0.9d, true));
    }

    private CommandConfirmRequestEvent confirmEvent(
            String sessionId,
            String tenantId,
            String userId,
            String idempotencyKey,
            long seq) {
        return new CommandConfirmRequestEvent(
                "evt-confirm-" + seq,
                "command.confirm.request",
                "v1",
                "trc-confirm-" + seq,
                sessionId,
                tenantId,
                userId,
                null,
                "speech-gateway",
                seq,
                1713745000000L + seq,
                idempotencyKey,
                new CommandConfirmRequestPayload("cfm-" + seq, true));
    }

    private CommandResultEvent commandResultEvent(
            String sessionId,
            String tenantId,
            String userId,
            long seq,
            String idempotencyKey) {
        return new CommandResultEvent(
                "evt-result-" + seq,
                "command.result",
                "v1",
                "trc-result-" + seq,
                sessionId,
                tenantId,
                userId,
                null,
                "command-worker",
                seq,
                1713746000000L + seq,
                idempotencyKey,
                new CommandResultPayload(
                        "ok",
                        "OK",
                        "Done",
                        "Done",
                        false,
                        null,
                        null,
                        "CONTROL",
                        "switch.on"));
    }
}
