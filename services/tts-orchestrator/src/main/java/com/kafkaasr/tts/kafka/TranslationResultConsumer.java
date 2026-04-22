package com.kafkaasr.tts.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.events.TtsRequestEvent;
import com.kafkaasr.tts.events.TranslationResultEvent;
import com.kafkaasr.tts.pipeline.TtsRequestPipelineService;
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
@ConditionalOnProperty(name = "tts.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TranslationResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(TranslationResultConsumer.class);

    private final ObjectMapper objectMapper;
    private final TtsRequestPipelineService pipelineService;
    private final TtsRequestPublisher ttsRequestPublisher;
    private final TtsCompensationPublisher compensationPublisher;
    private final TtsKafkaProperties kafkaProperties;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final MeterRegistry meterRegistry;
    private final Map<String, Integer> failureAttempts = new ConcurrentHashMap<>();

    public TranslationResultConsumer(
            ObjectMapper objectMapper,
            TtsRequestPipelineService pipelineService,
            TtsRequestPublisher ttsRequestPublisher,
            TtsCompensationPublisher compensationPublisher,
            TtsKafkaProperties kafkaProperties,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.ttsRequestPublisher = ttsRequestPublisher;
        this.compensationPublisher = compensationPublisher;
        this.kafkaProperties = kafkaProperties;
        this.idempotencyGuard = new TimedIdempotencyGuard(
                kafkaProperties.isIdempotencyEnabled(),
                kafkaProperties.getIdempotencyTtlMs());
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${tts.kafka.translation-result-topic:translation.result}",
            groupId = "${TTS_CONSUMER_GROUP_ID:tts-orchestrator}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String failureKey = "raw:" + Integer.toHexString(payload.hashCode());
        try {
            TranslationResultEvent translationResultEvent = parse(payload);
            failureKey = resolveFailureKey(translationResultEvent.idempotencyKey(), payload);
            if (idempotencyGuard.isDuplicate(translationResultEvent.idempotencyKey())) {
                meterRegistry.counter(
                                "tts.pipeline.messages.total",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                log.debug("Dropped duplicated translation.result event idempotencyKey={}",
                        translationResultEvent.idempotencyKey());
                return;
            }
            TtsRequestEvent requestEvent = pipelineService.toTtsRequestEvent(translationResultEvent);

            ttsRequestPublisher.publish(requestEvent).block();
            idempotencyGuard.markProcessed(translationResultEvent.idempotencyKey());
            failureAttempts.remove(failureKey);
            meterRegistry.counter(
                            "tts.pipeline.messages.total",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
            log.debug(
                    "Published tts.request event sessionId={} seq={}",
                    requestEvent.sessionId(),
                    requestEvent.seq());
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "tts.pipeline.messages.total",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            recordFailureAndCompensate(failureKey, payload, exception);
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("tts.pipeline.duration"));
        }
    }

    private TranslationResultEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, TranslationResultEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid translation.result payload", exception);
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
                kafkaProperties.getTranslationResultTopic(),
                payload,
                failure);
    }
}
