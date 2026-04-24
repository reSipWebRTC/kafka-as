package com.kafkaasr.gateway.ws.downlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import com.kafkaasr.gateway.ws.downlink.events.CommandResultEvent;
import com.kafkaasr.gateway.ws.downlink.events.CommandResultPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gateway.downlink.enabled", havingValue = "true", matchIfMissing = true)
public class CommandResultDownlinkConsumer {

    private final ObjectMapper objectMapper;
    private final GatewayDownlinkPublisher downlinkPublisher;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final GatewayCompensationPublisher compensationPublisher;
    private final GatewayDownlinkProperties downlinkProperties;
    private final MeterRegistry meterRegistry;
    private final Map<String, Integer> failureAttempts = new ConcurrentHashMap<>();

    public CommandResultDownlinkConsumer(
            ObjectMapper objectMapper,
            GatewayDownlinkPublisher downlinkPublisher,
            TimedIdempotencyGuard idempotencyGuard,
            GatewayCompensationPublisher compensationPublisher,
            GatewayDownlinkProperties downlinkProperties,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.downlinkPublisher = downlinkPublisher;
        this.idempotencyGuard = idempotencyGuard;
        this.compensationPublisher = compensationPublisher;
        this.downlinkProperties = downlinkProperties;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "#{@gatewayDownlinkProperties.commandResultTopic}",
            groupId = "#{@gatewayDownlinkProperties.consumerGroupId}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String failureKey = "raw:" + Integer.toHexString(payload.hashCode());
        try {
            CommandResultEvent event = parse(payload);
            failureKey = resolveFailureKey(event.idempotencyKey(), payload);
            if (idempotencyGuard.isDuplicate(event.idempotencyKey())) {
                meterRegistry.counter(
                                "gateway.downlink.messages.total",
                                "type",
                                "command.result",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                return;
            }
            CommandResultPayload downlinkPayload = event.payload();
            downlinkPublisher.publishCommandResult(
                            event.sessionId(),
                            event.seq(),
                            resolveStatus(downlinkPayload),
                            resolveCode(downlinkPayload),
                            resolveReplyText(downlinkPayload),
                            downlinkPayload != null && downlinkPayload.retryable(),
                            downlinkPayload == null ? null : downlinkPayload.confirmToken(),
                            downlinkPayload == null ? null : downlinkPayload.expiresInSec() == null
                                    ? null
                                    : Long.valueOf(downlinkPayload.expiresInSec()))
                    .block();
            idempotencyGuard.markProcessed(event.idempotencyKey());
            failureAttempts.remove(failureKey);
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "command.result",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "command.result",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            recordFailureAndCompensate(failureKey, payload, exception);
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("gateway.downlink.messages.duration", "type", "command.result"));
        }
    }

    private CommandResultEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, CommandResultEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid command.result payload", exception);
        }
    }

    private String resolveStatus(CommandResultPayload payload) {
        if (payload == null || payload.status() == null) {
            return "";
        }
        return payload.status();
    }

    private String resolveCode(CommandResultPayload payload) {
        if (payload == null || payload.code() == null) {
            return "";
        }
        return payload.code();
    }

    private String resolveReplyText(CommandResultPayload payload) {
        if (payload == null) {
            return "";
        }
        if (payload.replyText() != null && !payload.replyText().isBlank()) {
            return payload.replyText();
        }
        if (payload.ttsText() != null && !payload.ttsText().isBlank()) {
            return payload.ttsText();
        }
        return "";
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_PAYLOAD";
        }
        return "PIPELINE_FAILURE";
    }

    private String resolveFailureKey(String idempotencyKey, String payload) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey;
        }
        return "raw:" + Integer.toHexString(payload.hashCode());
    }

    private void recordFailureAndCompensate(String failureKey, String payload, RuntimeException failure) {
        int attempts = failureAttempts.merge(failureKey, 1, Integer::sum);
        if (attempts < downlinkProperties.getRetryMaxAttempts()) {
            return;
        }
        failureAttempts.remove(failureKey);
        compensationPublisher.publish(
                downlinkProperties.getCommandResultTopic(),
                payload,
                failure);
    }
}
