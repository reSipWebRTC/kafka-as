package com.kafkaasr.command.pipeline;

import com.kafkaasr.command.events.AsrFinalEvent;
import com.kafkaasr.command.events.CommandConfirmRequestEvent;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.events.CommandResultEvent;
import com.kafkaasr.command.events.CommandResultPayload;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CommandPipelineService {

    private static final String INPUT_ASR_FINAL_EVENT_TYPE = "asr.final";
    private static final String INPUT_COMMAND_CONFIRM_EVENT_TYPE = "command.confirm.request";
    private static final String OUTPUT_EVENT_TYPE = "command.result";
    private static final String OUTPUT_EVENT_VERSION = "v1";

    private final SmartHomeClient smartHomeClient;
    private final CommandKafkaProperties kafkaProperties;
    private final Clock clock;

    @Autowired
    public CommandPipelineService(
            SmartHomeClient smartHomeClient,
            CommandKafkaProperties kafkaProperties) {
        this(smartHomeClient, kafkaProperties, Clock.systemUTC());
    }

    CommandPipelineService(
            SmartHomeClient smartHomeClient,
            CommandKafkaProperties kafkaProperties,
            Clock clock) {
        this.smartHomeClient = smartHomeClient;
        this.kafkaProperties = kafkaProperties;
        this.clock = clock;
    }

    public CommandResultEvent fromAsrFinal(AsrFinalEvent event, String resolvedUserId) {
        validateAsrFinal(event);
        String userId = resolveUserId(resolvedUserId, event.sessionId());
        String text = event.payload().text() == null ? "" : event.payload().text();
        SmartHomeApiResponse response = smartHomeClient.executeCommand(
                event.sessionId(),
                userId,
                text,
                event.traceId());
        return toCommandResult(
                event.traceId(),
                event.sessionId(),
                event.tenantId(),
                userId,
                event.roomId(),
                event.seq(),
                INPUT_ASR_FINAL_EVENT_TYPE,
                response);
    }

    public CommandResultEvent fromCommandConfirmRequest(CommandConfirmRequestEvent event) {
        validateCommandConfirm(event);
        String userId = resolveUserId(event.userId(), event.sessionId());
        SmartHomeApiResponse response = smartHomeClient.submitConfirm(
                event.payload().confirmToken(),
                event.payload().accept(),
                event.traceId());
        return toCommandResult(
                event.traceId(),
                event.sessionId(),
                event.tenantId(),
                userId,
                event.roomId(),
                event.seq(),
                INPUT_COMMAND_CONFIRM_EVENT_TYPE,
                response);
    }

    private CommandResultEvent toCommandResult(
            String fallbackTraceId,
            String sessionId,
            String tenantId,
            String userId,
            String roomId,
            long seq,
            String sourceEventType,
            SmartHomeApiResponse response) {
        long ts = Instant.now(clock).toEpochMilli();
        String traceId = firstNonBlank(response.traceId(), fallbackTraceId, prefixedId("trc"));
        String normalizedCode = response.normalizedCode();
        String status = response.normalizedStatus();
        String replyText = response.replyText() == null ? "" : response.replyText();

        return new CommandResultEvent(
                prefixedId("evt"),
                OUTPUT_EVENT_TYPE,
                OUTPUT_EVENT_VERSION,
                traceId,
                sessionId,
                tenantId,
                userId,
                roomId,
                kafkaProperties.getProducerId(),
                seq,
                ts,
                sessionId + ":" + OUTPUT_EVENT_TYPE + ":" + sourceEventType + ":" + seq,
                new CommandResultPayload(
                        status,
                        normalizedCode,
                        replyText,
                        replyText,
                        response.retryable(),
                        response.confirmToken(),
                        response.expiresInSec(),
                        response.intent(),
                        response.subIntent()));
    }

    private void validateAsrFinal(AsrFinalEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("asr.final event must not be null");
        }
        if (!INPUT_ASR_FINAL_EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException("Unsupported asr.final eventType: " + event.eventType());
        }
        if (event.sessionId() == null || event.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (event.traceId() == null || event.traceId().isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        if (event.tenantId() == null || event.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (event.payload() == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }

    private void validateCommandConfirm(CommandConfirmRequestEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("command.confirm.request event must not be null");
        }
        if (!INPUT_COMMAND_CONFIRM_EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException("Unsupported command.confirm.request eventType: " + event.eventType());
        }
        if (event.sessionId() == null || event.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (event.traceId() == null || event.traceId().isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        if (event.tenantId() == null || event.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (event.payload() == null || event.payload().confirmToken() == null || event.payload().confirmToken().isBlank()) {
            throw new IllegalArgumentException("confirmToken is required");
        }
    }

    private String resolveUserId(String userId, String sessionId) {
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        return "user-" + sessionId;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
