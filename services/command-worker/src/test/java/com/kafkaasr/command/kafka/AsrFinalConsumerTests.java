package com.kafkaasr.command.kafka;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.events.AsrFinalEvent;
import com.kafkaasr.command.events.AsrFinalPayload;
import com.kafkaasr.command.events.CommandDispatchEvent;
import com.kafkaasr.command.events.CommandDispatchPayload;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.pipeline.CommandPipelineOutcome;
import com.kafkaasr.command.pipeline.CommandPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AsrFinalConsumerTests {

    @Mock
    private CommandPipelineService pipelineService;

    @Mock
    private CommandDispatchPublisher commandDispatchPublisher;

    @Mock
    private CommandResultPublisher commandResultPublisher;

    @Mock
    private CommandCompensationPublisher compensationPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AsrFinalConsumer consumer;
    private CommandKafkaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new CommandKafkaProperties();
        properties.setRetryMaxAttempts(2);
        properties.setRetryBackoffMs(0L);
        properties.setDlqTopicSuffix(".tenant-a.dlq");
        consumer = new AsrFinalConsumer(
                objectMapper,
                pipelineService,
                commandDispatchPublisher,
                commandResultPublisher,
                compensationPublisher,
                properties);
    }

    @Test
    void routesAsrFinalToDispatchPublisher() throws Exception {
        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "asr-worker",
                9L,
                1713744001000L,
                "sess-1:asr.final:9",
                new AsrFinalPayload("打开客厅灯", "zh-CN", 0.95f, true));
        CommandDispatchEvent dispatchEvent = new CommandDispatchEvent(
                "evt-out-1",
                "command.dispatch",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                9L,
                1713744001000L,
                "sess-1:command.dispatch:9:r1",
                new CommandDispatchPayload(
                        "exec:sess-1:9",
                        "CLIENT_BRIDGE",
                        "打开客厅灯",
                        "CONTROL",
                        "SMART_HOME",
                        true,
                        1,
                        2,
                        "cfm-1",
                        30,
                        "trc-1"));

        when(pipelineService.fromAsrFinal(any())).thenReturn(CommandPipelineOutcome.dispatch(dispatchEvent));
        when(commandDispatchPublisher.publish(dispatchEvent)).thenReturn(Mono.empty());

        consumer.onMessage(objectMapper.writeValueAsString(input));

        verify(pipelineService).fromAsrFinal(any());
        verify(commandDispatchPublisher).publish(dispatchEvent);
        verify(commandResultPublisher, never()).publish(any());
    }

    @Test
    void emitsCompensationAfterRetryExhausted() throws Exception {
        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "asr-worker",
                9L,
                1713744001000L,
                "sess-1:asr.final:9",
                new AsrFinalPayload("打开客厅灯", "zh-CN", 0.95f, true));
        String payload = objectMapper.writeValueAsString(input);

        when(pipelineService.fromAsrFinal(any())).thenThrow(new IllegalStateException("pipeline failed"));

        assertThrows(TenantAwareDlqException.class, () -> consumer.onMessage(payload));

        verify(pipelineService, times(2)).fromAsrFinal(any());
        verify(compensationPublisher).publish(
                eq("asr.final"),
                eq("asr.final.tenant-a.dlq"),
                eq(payload),
                any(RuntimeException.class));
    }

    @Test
    void dropsDuplicateByIdempotencyKey() throws Exception {
        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "asr-worker",
                9L,
                1713744001000L,
                "sess-1:asr.final:9",
                new AsrFinalPayload("打开客厅灯", "zh-CN", 0.95f, true));

        when(pipelineService.fromAsrFinal(any())).thenReturn(CommandPipelineOutcome.none());

        String payload = objectMapper.writeValueAsString(input);
        consumer.onMessage(payload);
        consumer.onMessage(payload);

        verify(pipelineService, times(1)).fromAsrFinal(any());
        verify(compensationPublisher, never()).publish(anyString(), anyString(), anyString(), any(RuntimeException.class));
    }
}
