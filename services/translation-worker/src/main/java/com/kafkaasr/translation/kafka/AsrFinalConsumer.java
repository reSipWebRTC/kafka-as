package com.kafkaasr.translation.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.translation.events.AsrFinalEvent;
import com.kafkaasr.translation.events.TranslationResultEvent;
import com.kafkaasr.translation.events.TranslationKafkaProperties;
import com.kafkaasr.translation.pipeline.TranslationPipelineService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "translation.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class AsrFinalConsumer {

    private static final Logger log = LoggerFactory.getLogger(AsrFinalConsumer.class);

    private final ObjectMapper objectMapper;
    private final TranslationPipelineService pipelineService;
    private final TranslationResultPublisher translationResultPublisher;
    private final TranslationCompensationPublisher compensationPublisher;
    private final TranslationKafkaProperties kafkaProperties;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final MeterRegistry meterRegistry;
    private final Map<String, Integer> failureAttempts = new ConcurrentHashMap<>();

    public AsrFinalConsumer(
            ObjectMapper objectMapper,
            TranslationPipelineService pipelineService,
            TranslationResultPublisher translationResultPublisher,
            TranslationCompensationPublisher compensationPublisher,
            TranslationKafkaProperties kafkaProperties,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.translationResultPublisher = translationResultPublisher;
        this.compensationPublisher = compensationPublisher;
        this.kafkaProperties = kafkaProperties;
        this.idempotencyGuard = new TimedIdempotencyGuard(
                kafkaProperties.isIdempotencyEnabled(),
                kafkaProperties.getIdempotencyTtlMs());
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "#{@translationKafkaProperties.asrFinalTopic}",
            groupId = "${TRANSLATION_CONSUMER_GROUP_ID:translation-worker}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String failureKey = "raw:" + Integer.toHexString(payload.hashCode());
        try {
            AsrFinalEvent asrFinalEvent = parse(payload);
            failureKey = resolveFailureKey(asrFinalEvent.idempotencyKey(), payload);
            if (idempotencyGuard.isDuplicate(asrFinalEvent.idempotencyKey())) {
                meterRegistry.counter(
                                "translation.pipeline.messages.total",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                log.debug("Dropped duplicated asr.final event idempotencyKey={}", asrFinalEvent.idempotencyKey());
                return;
            }
            TranslationResultEvent resultEvent = pipelineService.toTranslationResultEvent(asrFinalEvent);

            translationResultPublisher.publish(resultEvent).block();
            idempotencyGuard.markProcessed(asrFinalEvent.idempotencyKey());
            failureAttempts.remove(failureKey);
            meterRegistry.counter(
                            "translation.pipeline.messages.total",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
            log.debug(
                    "Published translation.result event sessionId={} seq={}",
                    resultEvent.sessionId(),
                    resultEvent.seq());
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "translation.pipeline.messages.total",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            recordFailureAndCompensate(failureKey, payload, exception);
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("translation.pipeline.duration"));
        }
    }

    private AsrFinalEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, AsrFinalEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid asr.final payload", exception);
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
        if (attempts < kafkaProperties.getRetryMaxAttempts()) {
            return;
        }
        failureAttempts.remove(failureKey);
        compensationPublisher.publish(
                kafkaProperties.getAsrFinalTopic(),
                payload,
                failure);
    }
}
