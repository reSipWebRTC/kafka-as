package com.kafkaasr.gateway.ws.downlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import com.kafkaasr.gateway.ws.downlink.events.TtsReadyEvent;
import com.kafkaasr.gateway.ws.downlink.events.TtsReadyPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gateway.downlink.enabled", havingValue = "true", matchIfMissing = true)
public class TtsReadyDownlinkConsumer {

    private final ObjectMapper objectMapper;
    private final GatewayDownlinkPublisher downlinkPublisher;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final GatewayCompensationPublisher compensationPublisher;
    private final GatewayDownlinkProperties downlinkProperties;
    private final MeterRegistry meterRegistry;
    private final Map<String, Integer> failureAttempts = new ConcurrentHashMap<>();

    public TtsReadyDownlinkConsumer(
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
            topics = "#{@gatewayDownlinkProperties.ttsReadyTopic}",
            groupId = "#{@gatewayDownlinkProperties.consumerGroupId}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String failureKey = "raw:" + Integer.toHexString(payload.hashCode());
        try {
            TtsReadyEvent event = parse(payload);
            failureKey = resolveFailureKey(event.idempotencyKey(), payload);
            if (idempotencyGuard.isDuplicate(event.idempotencyKey())) {
                meterRegistry.counter(
                                "gateway.downlink.messages.total",
                                "type",
                                "tts.ready",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                return;
            }
            TtsReadyPayload readyPayload = event.payload();
            downlinkPublisher.publishTtsReady(
                            event.sessionId(),
                            event.seq(),
                            readyPayload == null ? "" : readyPayload.playbackUrl(),
                            readyPayload == null ? "" : readyPayload.codec(),
                            readyPayload == null ? 0 : readyPayload.sampleRate(),
                            readyPayload == null ? 0L : readyPayload.durationMs(),
                            readyPayload == null ? "" : readyPayload.cacheKey())
                    .block();
            idempotencyGuard.markProcessed(event.idempotencyKey());
            failureAttempts.remove(failureKey);
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "tts.ready",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "tts.ready",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            recordFailureAndCompensate(failureKey, payload, exception);
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("gateway.downlink.messages.duration", "type", "tts.ready"));
        }
    }

    private TtsReadyEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, TtsReadyEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid tts.ready payload", exception);
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
                downlinkProperties.getTtsReadyTopic(),
                payload,
                failure);
    }
}
