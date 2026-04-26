package com.kafkaasr.command.pipeline;

import com.kafkaasr.command.events.AsrFinalEvent;
import com.kafkaasr.command.events.AsrFinalPayload;
import com.kafkaasr.command.events.CommandConfirmRequestEvent;
import com.kafkaasr.command.events.CommandConfirmRequestPayload;
import com.kafkaasr.command.events.CommandDispatchEvent;
import com.kafkaasr.command.events.CommandDispatchPayload;
import com.kafkaasr.command.events.CommandExecuteResultEvent;
import com.kafkaasr.command.events.CommandExecuteResultPayload;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.events.CommandResultEvent;
import com.kafkaasr.command.events.CommandResultPayload;
import com.kafkaasr.command.metrics.CommandPipelineMetricsRecorder;
import com.kafkaasr.command.state.CommandExecutionContext;
import com.kafkaasr.command.state.CommandExecutionContextRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CommandPipelineService {

    private static final String COMMAND_DISPATCH_EVENT_TYPE = "command.dispatch";
    private static final String COMMAND_RESULT_EVENT_TYPE = "command.result";
    private static final String EVENT_VERSION = "v1";
    private static final Set<String> ALLOWED_STATUS =
            Set.of("ok", "confirm_required", "failed", "cancelled", "timeout");
    private static final String DISPATCH_SOURCE_ASR_FINAL = "asr.final";
    private static final String DISPATCH_SOURCE_CONFIRM_ROUND = "command.execute.result";

    private final CommandKafkaProperties kafkaProperties;
    private final CommandExecutionContextRepository executionContextRepository;
    private final CommandPipelineMetricsRecorder metricsRecorder;

    public CommandPipelineService(
            CommandKafkaProperties kafkaProperties,
            CommandExecutionContextRepository executionContextRepository,
            CommandPipelineMetricsRecorder metricsRecorder) {
        this.kafkaProperties = kafkaProperties;
        this.executionContextRepository = executionContextRepository;
        this.metricsRecorder = metricsRecorder;
    }

    public CommandPipelineOutcome fromAsrFinal(AsrFinalEvent asrFinalEvent) {
        validateAsrFinal(asrFinalEvent);
        if (!shouldHandleTenant(asrFinalEvent.tenantId())) {
            return CommandPipelineOutcome.none();
        }

        AsrFinalPayload payload = asrFinalEvent.payload();
        String commandText = coalesceText(payload.text());
        if (commandText.isBlank()) {
            return CommandPipelineOutcome.none();
        }

        String executionId = "exec:" + asrFinalEvent.sessionId() + ":" + asrFinalEvent.seq();
        boolean confirmRequired = kafkaProperties.isDefaultConfirmRequired();
        int confirmRound = 1;
        int maxConfirmRounds = Math.max(1, kafkaProperties.getDefaultMaxConfirmRounds());
        String confirmToken = confirmRequired ? prefixedId("cfm") : null;
        String traceId = coalesceTraceId(asrFinalEvent.traceId(), null, null);

        CommandExecutionContext context = new CommandExecutionContext(
                executionId,
                asrFinalEvent.sessionId(),
                asrFinalEvent.tenantId(),
                asrFinalEvent.roomId(),
                asrFinalEvent.userId(),
                traceId,
                asrFinalEvent.ts(),
                commandText,
                kafkaProperties.getDefaultIntent(),
                kafkaProperties.getDefaultSubIntent(),
                confirmRequired,
                confirmRound,
                maxConfirmRounds,
                confirmToken,
                confirmRequired ? CommandExecutionState.WAIT_CONFIRM : CommandExecutionState.EXECUTING);
        executionContextRepository.save(context);

        return CommandPipelineOutcome.dispatch(buildDispatchEvent(context, asrFinalEvent.seq(), DISPATCH_SOURCE_ASR_FINAL));
    }

    public CommandPipelineOutcome fromCommandConfirm(CommandConfirmRequestEvent confirmEvent) {
        validateConfirmEvent(confirmEvent);
        CommandConfirmRequestPayload payload = confirmEvent.payload();
        String executionId = resolveExecutionId(confirmEvent);
        CommandExecutionContext context = executionContextRepository.findByExecutionId(executionId);
        if (context == null) {
            context = syntheticContextFromConfirm(confirmEvent, executionId);
        }

        int confirmRound = normalizeRound(payload.confirmRound(), context.confirmRound());
        String confirmToken = firstNonBlank(payload.confirmToken(), context.confirmToken());
        String traceId = coalesceTraceId(confirmEvent.traceId(), payload.traceId(), context.traceId());

        if (!payload.accept()) {
            executionContextRepository.delete(executionId);
            CommandExecutionContext cancelled = withTraceId(
                    withConfirmToken(
                            withConfirmRound(
                                    withState(context, CommandExecutionState.CANCELLED),
                                    confirmRound),
                            confirmToken),
                    traceId);
            return CommandPipelineOutcome.result(buildResultEvent(
                    cancelled,
                    confirmEvent.seq(),
                    "cancelled",
                    "COMMAND_REJECTED",
                    "命令已取消",
                    false,
                    confirmToken,
                    firstNonBlank(payload.rejectReason(), "USER_REJECTED"),
                    null));
        }

        if (confirmRound > context.maxConfirmRounds()) {
            executionContextRepository.delete(executionId);
            CommandExecutionContext cancelled = withTraceId(
                    withConfirmToken(
                            withConfirmRound(
                                    withState(context, CommandExecutionState.CANCELLED),
                                    confirmRound),
                            confirmToken),
                    traceId);
            return CommandPipelineOutcome.result(buildResultEvent(
                    cancelled,
                    confirmEvent.seq(),
                    "cancelled",
                    "COMMAND_CONFIRM_TIMEOUT",
                    "确认轮次超限，命令已取消",
                    false,
                    confirmToken,
                    firstNonBlank(payload.rejectReason(), "NO_INPUT_TIMEOUT"),
                    null));
        }

        CommandExecutionContext executing = withTraceId(
                withConfirmToken(
                        withConfirmRound(
                                withState(context, CommandExecutionState.EXECUTING),
                                confirmRound),
                        confirmToken),
                traceId);
        executionContextRepository.save(executing);
        return CommandPipelineOutcome.none();
    }

    public CommandPipelineOutcome fromCommandExecuteResult(CommandExecuteResultEvent executeResultEvent) {
        validateExecuteResultEvent(executeResultEvent);
        CommandExecuteResultPayload payload = executeResultEvent.payload();
        String status = normalizeStatus(payload.status());
        String executionId = payload.executionId();
        CommandExecutionContext context = executionContextRepository.findByExecutionId(executionId);
        if (context == null) {
            context = syntheticContextFromExecuteResult(executeResultEvent, executionId);
        }

        String traceId = coalesceTraceId(
                executeResultEvent.traceId(),
                payload.traceId(),
                context.traceId());

        if ("confirm_required".equals(status)) {
            int nextRound = normalizeRound(payload.confirmRound(), context.confirmRound() + 1);
            if (nextRound <= context.confirmRound()) {
                nextRound = context.confirmRound() + 1;
            }

            if (nextRound > context.maxConfirmRounds()) {
                executionContextRepository.delete(executionId);
                CommandExecutionContext cancelled = withTraceId(
                        withConfirmToken(
                                withConfirmRound(
                                        withState(context, CommandExecutionState.CANCELLED),
                                        nextRound),
                                firstNonBlank(payload.confirmToken(), context.confirmToken())),
                        traceId);
                return CommandPipelineOutcome.result(buildResultEvent(
                        cancelled,
                        executeResultEvent.seq(),
                        "cancelled",
                        "COMMAND_CONFIRM_TIMEOUT",
                        "确认轮次超限，命令已取消",
                        false,
                        cancelled.confirmToken(),
                        firstNonBlank(payload.rejectReason(), "NO_INPUT_TIMEOUT"),
                        payload.errorCode()));
            }

            String confirmToken = firstNonBlank(payload.confirmToken(), prefixedId("cfm"));
            CommandExecutionContext waiting = withTraceId(
                    withConfirmToken(
                            withConfirmRound(
                                    withState(context, CommandExecutionState.WAIT_CONFIRM),
                                    nextRound),
                            confirmToken),
                    traceId);
            executionContextRepository.save(waiting);
            return CommandPipelineOutcome.dispatch(buildDispatchEvent(
                    waiting,
                    executeResultEvent.seq(),
                    DISPATCH_SOURCE_CONFIRM_ROUND));
        }

        CommandExecutionState state = switch (status) {
            case "ok" -> CommandExecutionState.SUCCEEDED;
            case "cancelled" -> CommandExecutionState.CANCELLED;
            case "failed", "timeout" -> CommandExecutionState.FAILED;
            default -> CommandExecutionState.FAILED;
        };

        int confirmRound = normalizeRound(payload.confirmRound(), context.confirmRound());
        String confirmToken = firstNonBlank(payload.confirmToken(), context.confirmToken());

        CommandExecutionContext terminal = withTraceId(
                withConfirmToken(
                        withConfirmRound(
                                withState(context, state),
                                confirmRound),
                        confirmToken),
                traceId);
        executionContextRepository.delete(executionId);

        return CommandPipelineOutcome.result(buildResultEvent(
                terminal,
                executeResultEvent.seq(),
                status,
                normalizeCode(payload.code()),
                coalesceText(payload.replyText()),
                payload.retryable(),
                confirmToken,
                payload.rejectReason(),
                payload.errorCode()));
    }

    private CommandDispatchEvent buildDispatchEvent(CommandExecutionContext context, long seq, String dispatchSource) {
        long timestamp = Instant.now().toEpochMilli();
        metricsRecorder.recordDispatch(dispatchSource, context.confirmRound());
        return new CommandDispatchEvent(
                prefixedId("evt"),
                COMMAND_DISPATCH_EVENT_TYPE,
                EVENT_VERSION,
                context.traceId(),
                context.sessionId(),
                context.tenantId(),
                context.roomId(),
                context.userId(),
                kafkaProperties.getProducerId(),
                seq,
                timestamp,
                context.sessionId() + ":" + COMMAND_DISPATCH_EVENT_TYPE + ":" + seq + ":r" + context.confirmRound(),
                new CommandDispatchPayload(
                        context.executionId(),
                        kafkaProperties.getExecutionMode(),
                        context.commandText(),
                        context.intent(),
                        context.subIntent(),
                        context.confirmRequired(),
                        context.confirmRound(),
                        context.maxConfirmRounds(),
                        context.confirmToken(),
                        kafkaProperties.getDispatchExpiresInSec(),
                        context.traceId()));
    }

    private CommandResultEvent buildResultEvent(
            CommandExecutionContext context,
            long seq,
            String status,
            String code,
            String replyText,
            boolean retryable,
            String confirmToken,
            String rejectReason,
            String errorCode) {
        long timestamp = Instant.now().toEpochMilli();
        metricsRecorder.recordResult(status, code);
        metricsRecorder.recordE2eLatency(status, code, context.startedAtEpochMs(), timestamp);
        return new CommandResultEvent(
                prefixedId("evt"),
                COMMAND_RESULT_EVENT_TYPE,
                EVENT_VERSION,
                context.traceId(),
                context.sessionId(),
                context.tenantId(),
                context.roomId(),
                context.userId(),
                kafkaProperties.getProducerId(),
                seq,
                timestamp,
                context.sessionId() + ":" + COMMAND_RESULT_EVENT_TYPE + ":" + seq + ":r" + context.confirmRound(),
                new CommandResultPayload(
                        context.executionId(),
                        kafkaProperties.getExecutionMode(),
                        status,
                        code,
                        replyText,
                        retryable,
                        context.confirmRound(),
                        context.maxConfirmRounds(),
                        confirmToken,
                        rejectReason,
                        errorCode,
                        kafkaProperties.getDispatchExpiresInSec(),
                        context.intent(),
                        context.subIntent()));
    }

    private void validateAsrFinal(AsrFinalEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("asr.final event must not be null");
        }
        if (!"asr.final".equals(event.eventType())) {
            throw new IllegalArgumentException("Unsupported eventType for asr.final consumer: " + event.eventType());
        }
        if (event.sessionId() == null || event.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (event.tenantId() == null || event.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (event.payload() == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }

    private void validateConfirmEvent(CommandConfirmRequestEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("command.confirm.request event must not be null");
        }
        if (event.payload() == null) {
            throw new IllegalArgumentException("command.confirm.request payload is required");
        }
        if (event.sessionId() == null || event.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
    }

    private void validateExecuteResultEvent(CommandExecuteResultEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("command.execute.result event must not be null");
        }
        if (event.payload() == null) {
            throw new IllegalArgumentException("command.execute.result payload is required");
        }
        if (event.sessionId() == null || event.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (event.payload().executionId() == null || event.payload().executionId().isBlank()) {
            throw new IllegalArgumentException("executionId is required");
        }
    }

    private boolean shouldHandleTenant(String tenantId) {
        List<String> allowlist = kafkaProperties.getSmartHomeTenantAllowlist();
        if (allowlist == null || allowlist.isEmpty()) {
            return true;
        }
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        return allowlist.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .anyMatch(entry -> entry.equals(tenantId));
    }

    private String resolveExecutionId(CommandConfirmRequestEvent confirmEvent) {
        CommandConfirmRequestPayload payload = confirmEvent.payload();
        if (payload.executionId() != null && !payload.executionId().isBlank()) {
            return payload.executionId();
        }
        if (payload.confirmToken() != null && !payload.confirmToken().isBlank()) {
            return "confirm:" + payload.confirmToken();
        }
        return "confirm:" + confirmEvent.sessionId() + ":" + confirmEvent.seq();
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        String normalized = status.toLowerCase(Locale.ROOT);
        if (!ALLOWED_STATUS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported command status: " + status);
        }
        return normalized;
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return "UNKNOWN";
        }
        return code;
    }

    private int normalizeRound(Integer round, int fallback) {
        if (round == null || round < 1) {
            return Math.max(1, fallback);
        }
        return round;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private String coalesceText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text;
    }

    private String coalesceTraceId(String envelopeTraceId, String payloadTraceId, String contextTraceId) {
        String candidate = firstNonBlank(envelopeTraceId, firstNonBlank(payloadTraceId, contextTraceId));
        if (candidate == null || candidate.isBlank()) {
            return prefixedId("trc");
        }
        return candidate;
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private CommandExecutionContext syntheticContextFromConfirm(CommandConfirmRequestEvent event, String executionId) {
        int maxRounds = Math.max(1, kafkaProperties.getDefaultMaxConfirmRounds());
        return new CommandExecutionContext(
                executionId,
                event.sessionId(),
                event.tenantId(),
                event.roomId(),
                event.userId(),
                coalesceTraceId(event.traceId(), event.payload().traceId(), null),
                event.ts(),
                "",
                kafkaProperties.getDefaultIntent(),
                kafkaProperties.getDefaultSubIntent(),
                true,
                normalizeRound(event.payload().confirmRound(), 1),
                maxRounds,
                event.payload().confirmToken(),
                CommandExecutionState.WAIT_CONFIRM);
    }

    private CommandExecutionContext syntheticContextFromExecuteResult(CommandExecuteResultEvent event, String executionId) {
        int maxRounds = Math.max(1, kafkaProperties.getDefaultMaxConfirmRounds());
        return new CommandExecutionContext(
                executionId,
                event.sessionId(),
                event.tenantId(),
                event.roomId(),
                event.userId(),
                coalesceTraceId(event.traceId(), event.payload().traceId(), null),
                event.ts(),
                "",
                kafkaProperties.getDefaultIntent(),
                kafkaProperties.getDefaultSubIntent(),
                true,
                normalizeRound(event.payload().confirmRound(), 1),
                maxRounds,
                event.payload().confirmToken(),
                CommandExecutionState.EXECUTING);
    }

    private CommandExecutionContext withState(CommandExecutionContext context, CommandExecutionState state) {
        return new CommandExecutionContext(
                context.executionId(),
                context.sessionId(),
                context.tenantId(),
                context.roomId(),
                context.userId(),
                context.traceId(),
                context.startedAtEpochMs(),
                context.commandText(),
                context.intent(),
                context.subIntent(),
                context.confirmRequired(),
                context.confirmRound(),
                context.maxConfirmRounds(),
                context.confirmToken(),
                state);
    }

    private CommandExecutionContext withConfirmRound(CommandExecutionContext context, int confirmRound) {
        return new CommandExecutionContext(
                context.executionId(),
                context.sessionId(),
                context.tenantId(),
                context.roomId(),
                context.userId(),
                context.traceId(),
                context.startedAtEpochMs(),
                context.commandText(),
                context.intent(),
                context.subIntent(),
                context.confirmRequired(),
                Math.max(1, confirmRound),
                context.maxConfirmRounds(),
                context.confirmToken(),
                context.state());
    }

    private CommandExecutionContext withConfirmToken(CommandExecutionContext context, String confirmToken) {
        return new CommandExecutionContext(
                context.executionId(),
                context.sessionId(),
                context.tenantId(),
                context.roomId(),
                context.userId(),
                context.traceId(),
                context.startedAtEpochMs(),
                context.commandText(),
                context.intent(),
                context.subIntent(),
                context.confirmRequired(),
                context.confirmRound(),
                context.maxConfirmRounds(),
                confirmToken,
                context.state());
    }

    private CommandExecutionContext withTraceId(CommandExecutionContext context, String traceId) {
        return new CommandExecutionContext(
                context.executionId(),
                context.sessionId(),
                context.tenantId(),
                context.roomId(),
                context.userId(),
                traceId,
                context.startedAtEpochMs(),
                context.commandText(),
                context.intent(),
                context.subIntent(),
                context.confirmRequired(),
                context.confirmRound(),
                context.maxConfirmRounds(),
                context.confirmToken(),
                context.state());
    }
}
