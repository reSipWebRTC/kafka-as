package com.kafkaasr.gateway.ws.downlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import com.kafkaasr.gateway.ws.downlink.events.TranslationResultEvent;
import com.kafkaasr.gateway.ws.downlink.events.TranslationResultPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gateway.downlink.enabled", havingValue = "true", matchIfMissing = true)
public class TranslationResultDownlinkConsumer {

    private final ObjectMapper objectMapper;
    private final GatewayDownlinkPublisher downlinkPublisher;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final GatewayCompensationPublisher compensationPublisher;
    private final GatewayDownlinkProperties downlinkProperties;
    private final MeterRegistry meterRegistry;
    private final Map<String, Integer> failureAttempts = new ConcurrentHashMap<>();

    public TranslationResultDownlinkConsumer(
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
            topics = "#{@gatewayDownlinkProperties.translationResultTopic}",
            groupId = "#{@gatewayDownlinkProperties.consumerGroupId}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String failureKey = "raw:" + Integer.toHexString(payload.hashCode());
        try {
            TranslationResultEvent event = parse(payload);
            failureKey = resolveFailureKey(event.idempotencyKey(), payload);
            if (idempotencyGuard.isDuplicate(event.idempotencyKey())) {
                meterRegistry.counter(
                                "gateway.downlink.messages.total",
                                "type",
                                "subtitle.final",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                return;
            }
            String text = resolveFinalText(event.payload());
            downlinkPublisher.publishSubtitleFinal(event.sessionId(), event.seq(), text).block();
            idempotencyGuard.markProcessed(event.idempotencyKey());
            failureAttempts.remove(failureKey);
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "subtitle.final",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "subtitle.final",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            recordFailureAndCompensate(failureKey, payload, exception);
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("gateway.downlink.messages.duration", "type", "subtitle.final"));
        }
    }

    private TranslationResultEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, TranslationResultEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid translation.result payload", exception);
        }
    }

    private String resolveFinalText(TranslationResultPayload payload) {
        if (payload == null) {
            return "";
        }
        if (payload.translatedText() != null && !payload.translatedText().isBlank()) {
            return payload.translatedText();
        }
        if (payload.sourceText() != null && !payload.sourceText().isBlank()) {
            return payload.sourceText();
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
                downlinkProperties.getTranslationResultTopic(),
                payload,
                failure);
    }
}
