package com.kafkaasr.command.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kafkaasr.command.events.AsrFinalEvent;
import com.kafkaasr.command.events.AsrFinalPayload;
import com.kafkaasr.command.events.CommandConfirmRequestEvent;
import com.kafkaasr.command.events.CommandConfirmRequestPayload;
import com.kafkaasr.command.events.CommandExecuteResultEvent;
import com.kafkaasr.command.events.CommandExecuteResultPayload;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.metrics.CommandPipelineMetricsRecorder;
import com.kafkaasr.command.state.CommandExecutionContextRepository;
import com.kafkaasr.command.state.InMemoryCommandExecutionContextRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandPipelineServiceTests {

    private CommandPipelineService pipelineService;
    private SimpleMeterRegistry meterRegistry;
    private CommandExecutionContextRepository repository;

    @BeforeEach
    void setUp() {
        CommandKafkaProperties properties = new CommandKafkaProperties();
        properties.setProducerId("command-worker");
        properties.setExecutionMode("CLIENT_BRIDGE");
        properties.setDefaultConfirmRequired(true);
        properties.setDefaultMaxConfirmRounds(2);
        properties.setDispatchExpiresInSec(30);
        properties.setDefaultIntent("CONTROL");
        properties.setDefaultSubIntent("SMART_HOME");
        meterRegistry = new SimpleMeterRegistry();
        repository = new InMemoryCommandExecutionContextRepository();
        pipelineService = new CommandPipelineService(
                properties,
                repository,
                new CommandPipelineMetricsRecorder(meterRegistry));
    }

    @Test
    void mapsAsrFinalToCommandDispatch() {
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

        CommandPipelineOutcome output = pipelineService.fromAsrFinal(input);

        assertNotNull(output.commandDispatchEvent());
        assertNull(output.commandResultEvent());
        assertEquals("command.dispatch", output.commandDispatchEvent().eventType());
        assertEquals("exec:sess-1:9", output.commandDispatchEvent().payload().executionId());
        assertEquals("CLIENT_BRIDGE", output.commandDispatchEvent().payload().executionMode());
        assertEquals(1, output.commandDispatchEvent().payload().confirmRound());
        assertEquals(2, output.commandDispatchEvent().payload().maxConfirmRounds());
    }

    @Test
    void mapsConfirmRejectToCancelledResult() {
        AsrFinalEvent start = new AsrFinalEvent(
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
        pipelineService.fromAsrFinal(start);

        CommandConfirmRequestEvent confirmReject = new CommandConfirmRequestEvent(
                "evt-in-2",
                "command.confirm.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "speech-gateway",
                10L,
                1713744002000L,
                "sess-1:command.confirm.request:10",
                new CommandConfirmRequestPayload(
                        "exec:sess-1:9",
                        "CLIENT_BRIDGE",
                        "cfm-1",
                        false,
                        1,
                        "USER_REJECTED",
                        "trc-1"));

        CommandPipelineOutcome output = pipelineService.fromCommandConfirm(confirmReject);

        assertNull(output.commandDispatchEvent());
        assertNotNull(output.commandResultEvent());
        assertEquals("cancelled", output.commandResultEvent().payload().status());
        assertEquals("COMMAND_REJECTED", output.commandResultEvent().payload().code());
        assertEquals("USER_REJECTED", output.commandResultEvent().payload().rejectReason());
    }

    @Test
    void mapsExecuteOkToTerminalResult() {
        AsrFinalEvent start = new AsrFinalEvent(
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
        pipelineService.fromAsrFinal(start);

        CommandExecuteResultEvent executeResult = new CommandExecuteResultEvent(
                "evt-in-3",
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
                        "exec:sess-1:9",
                        "CLIENT_BRIDGE",
                        "ok",
                        "DEVICE_OK",
                        "已打开客厅灯",
                        false,
                        1,
                        "cfm-1",
                        null,
                        null,
                        "trc-1"));

        CommandPipelineOutcome output = pipelineService.fromCommandExecuteResult(executeResult);

        assertNull(output.commandDispatchEvent());
        assertNotNull(output.commandResultEvent());
        assertEquals("ok", output.commandResultEvent().payload().status());
        assertEquals("DEVICE_OK", output.commandResultEvent().payload().code());
        assertEquals("CLIENT_BRIDGE", output.commandResultEvent().payload().executionMode());
    }

    @Test
    void redispatchesWhenExecuteResultNeedsSecondConfirmRound() {
        AsrFinalEvent start = new AsrFinalEvent(
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
        pipelineService.fromAsrFinal(start);

        CommandExecuteResultEvent confirmRequired = new CommandExecuteResultEvent(
                "evt-in-3",
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
                        "exec:sess-1:9",
                        "CLIENT_BRIDGE",
                        "confirm_required",
                        "POLICY_CONFIRM_REQUIRED",
                        "请确认是否继续执行",
                        false,
                        2,
                        "cfm-2",
                        null,
                        null,
                        "trc-1"));

        CommandPipelineOutcome output = pipelineService.fromCommandExecuteResult(confirmRequired);

        assertNotNull(output.commandDispatchEvent());
        assertNull(output.commandResultEvent());
        assertEquals(2, output.commandDispatchEvent().payload().confirmRound());
        assertEquals("cfm-2", output.commandDispatchEvent().payload().confirmToken());
    }

    @Test
    void ignoresAsrFinalWhenTenantNotInAllowlist() {
        CommandKafkaProperties properties = new CommandKafkaProperties();
        properties.setSmartHomeTenantAllowlist(java.util.List.of("tenant-smart"));
        meterRegistry = new SimpleMeterRegistry();
        repository = new InMemoryCommandExecutionContextRepository();
        pipelineService = new CommandPipelineService(
                properties,
                repository,
                new CommandPipelineMetricsRecorder(meterRegistry));

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

        CommandPipelineOutcome output = pipelineService.fromAsrFinal(input);

        assertNull(output.commandDispatchEvent());
        assertNull(output.commandResultEvent());
    }

    @Test
    void emitsCancelledWhenConfirmRoundExceeded() {
        AsrFinalEvent start = new AsrFinalEvent(
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
        pipelineService.fromAsrFinal(start);

        CommandExecuteResultEvent confirmRequired = new CommandExecuteResultEvent(
                "evt-in-3",
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
                        "exec:sess-1:9",
                        "CLIENT_BRIDGE",
                        "confirm_required",
                        "POLICY_CONFIRM_REQUIRED",
                        "请确认是否继续执行",
                        false,
                        3,
                        "cfm-3",
                        null,
                        null,
                        "trc-1"));

        CommandPipelineOutcome output = pipelineService.fromCommandExecuteResult(confirmRequired);

        assertNull(output.commandDispatchEvent());
        assertNotNull(output.commandResultEvent());
        assertEquals("cancelled", output.commandResultEvent().payload().status());
        assertEquals("COMMAND_CONFIRM_TIMEOUT", output.commandResultEvent().payload().code());
        assertTrue(output.commandResultEvent().payload().confirmRound() >= 3);
    }

    @Test
    void mapsExecuteTimeoutToTerminalTimeoutResult() {
        AsrFinalEvent start = new AsrFinalEvent(
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
        pipelineService.fromAsrFinal(start);

        CommandExecuteResultEvent timeout = new CommandExecuteResultEvent(
                "evt-in-4",
                "command.execute.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "speech-gateway",
                12L,
                1713744003500L,
                "sess-1:command.execute.result:12",
                new CommandExecuteResultPayload(
                        "exec:sess-1:9",
                        "CLIENT_BRIDGE",
                        "timeout",
                        "DEVICE_TIMEOUT",
                        "设备响应超时",
                        true,
                        1,
                        "cfm-1",
                        null,
                        "TIMEOUT",
                        "trc-1"));

        CommandPipelineOutcome output = pipelineService.fromCommandExecuteResult(timeout);

        assertNull(output.commandDispatchEvent());
        assertNotNull(output.commandResultEvent());
        assertEquals("timeout", output.commandResultEvent().payload().status());
        assertEquals("DEVICE_TIMEOUT", output.commandResultEvent().payload().code());
        assertEquals("TIMEOUT", output.commandResultEvent().payload().errorCode());
        assertCounter("command.pipeline.result.total", 1d, "status", "timeout", "outcome", "execution_failed");
    }

    @Test
    void recordsConfirmTimeoutOutcomeMetric() {
        AsrFinalEvent start = new AsrFinalEvent(
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
        pipelineService.fromAsrFinal(start);

        CommandExecuteResultEvent confirmRequired = new CommandExecuteResultEvent(
                "evt-in-3",
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
                        "exec:sess-1:9",
                        "CLIENT_BRIDGE",
                        "confirm_required",
                        "POLICY_CONFIRM_REQUIRED",
                        "请确认是否继续执行",
                        false,
                        3,
                        "cfm-3",
                        null,
                        null,
                        "trc-1"));
        pipelineService.fromCommandExecuteResult(confirmRequired);

        assertCounter("command.pipeline.result.total", 1d, "status", "cancelled", "outcome", "confirm_timeout");
    }

    private void assertCounter(String name, double expected, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        assertNotNull(counter);
        assertEquals(expected, counter.count(), 1e-6);
    }
}
