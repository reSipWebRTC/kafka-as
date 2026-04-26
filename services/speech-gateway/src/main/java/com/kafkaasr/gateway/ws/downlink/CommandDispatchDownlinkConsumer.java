package com.kafkaasr.gateway.ws.downlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import com.kafkaasr.gateway.ws.downlink.events.CommandDispatchEvent;
import com.kafkaasr.gateway.ws.downlink.events.CommandDispatchPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gateway.downlink.enabled", havingValue = "true", matchIfMissing = true)
public class CommandDispatchDownlinkConsumer {

    private final ObjectMapper objectMapper;
    private final GatewayDownlinkPublisher downlinkPublisher;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final GatewayCompensationPublisher compensationPublisher;
    private final GatewayDownlinkProperties downlinkProperties;
    private final MeterRegistry meterRegistry;
    private final Map<String, Integer> failureAttempts = new ConcurrentHashMap<>();

    public CommandDispatchDownlinkConsumer(
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
            topics = "#{@gatewayDownlinkProperties.commandDispatchTopic}",
            groupId = "#{@gatewayDownlinkProperties.consumerGroupId}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String failureKey = "raw:" + Integer.toHexString(payload.hashCode());
        try {
            CommandDispatchEvent event = parse(payload);
            failureKey = resolveFailureKey(event.idempotencyKey(), payload);
            if (idempotencyGuard.isDuplicate(event.idempotencyKey())) {
                meterRegistry.counter(
                                "gateway.downlink.messages.total",
                                "type",
                                "command.dispatch",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                return;
            }

            CommandDispatchPayload dispatchPayload = event.payload();
            downlinkPublisher.publishCommandDispatch(
                            event.sessionId(),
                            event.seq(),
                            dispatchPayload == null ? "" : dispatchPayload.executionId(),
                            dispatchPayload == null ? "" : dispatchPayload.commandText(),
                            dispatchPayload == null ? "" : dispatchPayload.intent(),
                            dispatchPayload == null ? "" : dispatchPayload.subIntent(),
                            dispatchPayload != null && dispatchPayload.confirmRequired(),
                            dispatchPayload == null ? "" : dispatchPayload.confirmToken(),
                            dispatchPayload == null ? 0 : dispatchPayload.expiresInSec(),
                            dispatchPayload == null ? "" : dispatchPayload.traceId())
                    .block();
            idempotencyGuard.markProcessed(event.idempotencyKey());
            failureAttempts.remove(failureKey);
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "command.dispatch",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "command.dispatch",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            recordFailureAndCompensate(failureKey, payload, exception);
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("gateway.downlink.messages.duration", "type", "command.dispatch"));
        }
    }

    private CommandDispatchEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, CommandDispatchEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid command.dispatch payload", exception);
        }
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
                downlinkProperties.getCommandDispatchTopic(),
                payload,
                failure);
    }
}
