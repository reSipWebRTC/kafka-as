package com.kafkaasr.command.kafka;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.events.CommandExecuteResultEvent;
import com.kafkaasr.command.events.CommandExecuteResultPayload;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.events.CommandResultEvent;
import com.kafkaasr.command.events.CommandResultPayload;
import com.kafkaasr.command.pipeline.CommandPipelineOutcome;
import com.kafkaasr.command.pipeline.CommandPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class CommandExecuteResultConsumerTests {

    @Mock
    private CommandPipelineService pipelineService;

    @Mock
    private CommandDispatchPublisher commandDispatchPublisher;

    @Mock
    private CommandResultPublisher commandResultPublisher;

    @Mock
    private CommandCompensationPublisher compensationPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CommandExecuteResultConsumer consumer;
    private CommandKafkaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new CommandKafkaProperties();
        properties.setRetryMaxAttempts(2);
        properties.setRetryBackoffMs(0L);
        properties.setDlqTopicSuffix(".tenant-a.dlq");
        consumer = new CommandExecuteResultConsumer(
                objectMapper,
                pipelineService,
                commandDispatchPublisher,
                commandResultPublisher,
                compensationPublisher,
                properties);
    }

    @Test
    void publishesCommandResultWhenExecutionCompletes() throws Exception {
        CommandExecuteResultEvent input = new CommandExecuteResultEvent(
                "evt-in-1",
                "command.execute.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "speech-gateway",
                11L,
                1713744003000L,
                "sess-1:command.execute.result:11",
                new CommandExecuteResultPayload(
                        "exec-1",
                        "CLIENT_BRIDGE",
                        "ok",
                        "DEVICE_OK",
                        "已完成",
                        false,
                        1,
                        "cfm-1",
                        null,
                        null,
                        "trc-1"));

        CommandResultEvent resultEvent = new CommandResultEvent(
                "evt-out-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                11L,
                1713744003100L,
                "sess-1:command.result:11:r1",
                new CommandResultPayload(
                        "exec-1",
                        "CLIENT_BRIDGE",
                        "ok",
                        "DEVICE_OK",
                        "已完成",
                        false,
                        1,
                        2,
                        "cfm-1",
                        null,
                        null,
                        30,
                        "CONTROL",
                        "SMART_HOME"));

        when(pipelineService.fromCommandExecuteResult(any())).thenReturn(CommandPipelineOutcome.result(resultEvent));
        when(commandResultPublisher.publish(resultEvent)).thenReturn(Mono.empty());

        consumer.onMessage(objectMapper.writeValueAsString(input));

        verify(pipelineService).fromCommandExecuteResult(any());
        verify(commandResultPublisher).publish(resultEvent);
        verify(commandDispatchPublisher, never()).publish(any());
    }

    @Test
    void emitsCompensationWhenPipelineKeepsFailing() throws Exception {
        CommandExecuteResultEvent input = new CommandExecuteResultEvent(
                "evt-in-1",
                "command.execute.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "speech-gateway",
                11L,
                1713744003000L,
                "sess-1:command.execute.result:11",
                new CommandExecuteResultPayload(
                        "exec-1",
                        "CLIENT_BRIDGE",
                        "ok",
                        "DEVICE_OK",
                        "已完成",
                        false,
                        1,
                        "cfm-1",
                        null,
                        null,
                        "trc-1"));
        String payload = objectMapper.writeValueAsString(input);

        when(pipelineService.fromCommandExecuteResult(any())).thenThrow(new IllegalStateException("pipeline failed"));

        assertThrows(TenantAwareDlqException.class, () -> consumer.onMessage(payload));

        verify(pipelineService, times(2)).fromCommandExecuteResult(any());
        verify(compensationPublisher).publish(
                eq("command.execute.result"),
                eq("command.execute.result.tenant-a.dlq"),
                eq(payload),
                any(RuntimeException.class));
    }
}
