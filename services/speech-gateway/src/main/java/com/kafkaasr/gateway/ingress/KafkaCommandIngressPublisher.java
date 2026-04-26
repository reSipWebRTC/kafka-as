package com.kafkaasr.gateway.ingress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "gateway.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaCommandIngressPublisher implements CommandIngressPublisher {

    private static final String EVENT_VERSION = "v1";
    private static final String COMMAND_CONFIRM_REQUEST_EVENT_TYPE = "command.confirm.request";
    private static final String COMMAND_EXECUTE_RESULT_EVENT_TYPE = "command.execute.result";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayKafkaProperties properties;
    private final Clock clock;

    public KafkaCommandIngressPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            GatewayKafkaProperties properties) {
        this(kafkaTemplate, objectMapper, properties, Clock.systemUTC());
    }

    KafkaCommandIngressPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            GatewayKafkaProperties properties,
            Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<Void> publishCommandConfirm(CommandConfirmIngressCommand command) {
        CommandConfirmRequestEvent event = toConfirmEvent(command);
        String payload = toJson(event);
        return Mono.fromFuture(kafkaTemplate.send(
                        properties.getCommandConfirmRequestTopic(),
                        command.sessionId(),
                        payload))
                .then();
    }

    @Override
    public Mono<Void> publishCommandExecuteResult(CommandExecuteResultIngressCommand command) {
        CommandExecuteResultEvent event = toExecuteResultEvent(command);
        String payload = toJson(event);
        return Mono.fromFuture(kafkaTemplate.send(
                        properties.getCommandExecuteResultTopic(),
                        command.sessionId(),
                        payload))
                .then();
    }

    private CommandConfirmRequestEvent toConfirmEvent(CommandConfirmIngressCommand command) {
        String traceId = coalesceTraceId(command.traceId());
        long timestamp = Instant.now(clock).toEpochMilli();
        String executionId = coalesceExecutionId(command.executionId(), command.confirmToken(), command.sessionId(), command.seq());
        return new CommandConfirmRequestEvent(
                prefixedId("evt"),
                COMMAND_CONFIRM_REQUEST_EVENT_TYPE,
                EVENT_VERSION,
                traceId,
                command.sessionId(),
                properties.getTenantId(),
                null,
                null,
                properties.getProducerId(),
                command.seq(),
                timestamp,
                command.sessionId() + ":" + COMMAND_CONFIRM_REQUEST_EVENT_TYPE + ":" + command.seq(),
                new CommandConfirmRequestPayload(
                        executionId,
                        command.confirmToken(),
                        command.accept(),
                        traceId));
    }

    private CommandExecuteResultEvent toExecuteResultEvent(CommandExecuteResultIngressCommand command) {
        String traceId = coalesceTraceId(command.traceId());
        long timestamp = Instant.now(clock).toEpochMilli();
        return new CommandExecuteResultEvent(
                prefixedId("evt"),
                COMMAND_EXECUTE_RESULT_EVENT_TYPE,
                EVENT_VERSION,
                traceId,
                command.sessionId(),
                properties.getTenantId(),
                null,
                null,
                properties.getProducerId(),
                command.seq(),
                timestamp,
                command.sessionId() + ":" + COMMAND_EXECUTE_RESULT_EVENT_TYPE + ":" + command.seq(),
                new CommandExecuteResultPayload(
                        command.executionId(),
                        command.status(),
                        command.code(),
                        coalesceText(command.replyText()),
                        command.retryable(),
                        null,
                        traceId));
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize command ingress event", exception);
        }
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String coalesceTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return prefixedId("trc");
        }
        return traceId;
    }

    private String coalesceExecutionId(String executionId, String confirmToken, String sessionId, long seq) {
        if (executionId != null && !executionId.isBlank()) {
            return executionId;
        }
        if (confirmToken != null && !confirmToken.isBlank()) {
            return "confirm:" + confirmToken;
        }
        return sessionId + ":" + seq;
    }

    private String coalesceText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value;
    }
}
