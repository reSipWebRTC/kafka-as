package com.kafkaasr.translation.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.translation.events.TranslationKafkaProperties;
import com.kafkaasr.translation.events.TranslationRequestEvent;
import com.kafkaasr.translation.events.TranslationResultEvent;
import com.kafkaasr.translation.pipeline.TranslationEngineException;
import com.kafkaasr.translation.pipeline.TranslationPipelineService;
import com.kafkaasr.translation.policy.TenantReliabilityPolicy;
import com.kafkaasr.translation.policy.TenantReliabilityPolicyResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "translation.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TranslationRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(TranslationRequestConsumer.class);

    private final ObjectMapper objectMapper;
    private final TranslationPipelineService pipelineService;
    private final TranslationResultPublisher translationResultPublisher;
    private final TranslationCompensationPublisher compensationPublisher;
    private final TranslationKafkaProperties kafkaProperties;
    private final TenantReliabilityPolicyResolver reliabilityPolicyResolver;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final MeterRegistry meterRegistry;

    public TranslationRequestConsumer(
            ObjectMapper objectMapper,
            TranslationPipelineService pipelineService,
            TranslationResultPublisher translationResultPublisher,
            TranslationCompensationPublisher compensationPublisher,
            TranslationKafkaProperties kafkaProperties,
            TenantReliabilityPolicyResolver reliabilityPolicyResolver,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.translationResultPublisher = translationResultPublisher;
        this.compensationPublisher = compensationPublisher;
        this.kafkaProperties = kafkaProperties;
        this.reliabilityPolicyResolver = reliabilityPolicyResolver;
        this.idempotencyGuard = new TimedIdempotencyGuard(
                kafkaProperties.isIdempotencyEnabled(),
                kafkaProperties.getIdempotencyTtlMs());
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "#{@translationKafkaProperties.translationRequestTopic}",
            groupId = "${TRANSLATION_REQUEST_CONSUMER_GROUP_ID:translation-worker}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            TranslationRequestEvent translationRequestEvent = parse(payload);
            if (idempotencyGuard.isDuplicate(translationRequestEvent.idempotencyKey())) {
                meterRegistry.counter(
                                "translation.pipeline.messages.total",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                log.debug(
                        "Dropped duplicated translation.request event idempotencyKey={}",
                        translationRequestEvent.idempotencyKey());
                return;
            }
            TenantReliabilityPolicy reliabilityPolicy = reliabilityPolicyResolver.resolve(translationRequestEvent.tenantId());
            processWithRetry(translationRequestEvent, payload, reliabilityPolicy);
        } catch (IllegalArgumentException exception) {
            meterRegistry.counter(
                            "translation.pipeline.messages.total",
                            "result",
                            "error",
                            "code",
                            "INVALID_PAYLOAD")
                    .increment();
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("translation.pipeline.duration"));
        }
    }

    private void processWithRetry(
            TranslationRequestEvent translationRequestEvent,
            String payload,
            TenantReliabilityPolicy reliabilityPolicy) {
        int maxAttempts = Math.max(1, reliabilityPolicy.retryMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processOnce(translationRequestEvent);
                return;
            } catch (RuntimeException failure) {
                boolean retryable = isRetryable(failure);
                if (retryable && attempt < maxAttempts) {
                    sleepBackoff(reliabilityPolicy.retryBackoffMs());
                    continue;
                }
                meterRegistry.counter(
                                "translation.pipeline.messages.total",
                                "result",
                                "error",
                                "code",
                                normalizeErrorCode(failure))
                        .increment();
                recordFailureAndCompensate(payload, reliabilityPolicy, failure);
                throw wrapForDlq(reliabilityPolicy.dlqTopicSuffix(), failure);
            }
        }
    }

    private void processOnce(TranslationRequestEvent translationRequestEvent) {
        TranslationResultEvent resultEvent = pipelineService.toTranslationResultEvent(translationRequestEvent);
        translationResultPublisher.publish(resultEvent).block();
        idempotencyGuard.markProcessed(translationRequestEvent.idempotencyKey());
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
    }

    private TranslationRequestEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, TranslationRequestEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid translation.request payload", exception);
        }
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_PAYLOAD";
        }
        if (throwable instanceof TranslationEngineException translationEngineException) {
            return translationEngineException.errorCode();
        }
        return "PIPELINE_FAILURE";
    }

    private boolean isRetryable(RuntimeException failure) {
        if (failure instanceof TranslationEngineException translationEngineException) {
            return translationEngineException.retryable();
        }
        return !(failure instanceof IllegalArgumentException);
    }

    private RuntimeException wrapForDlq(String dlqTopicSuffix, RuntimeException failure) {
        if (failure instanceof TenantAwareDlqException) {
            return failure;
        }
        return new TenantAwareDlqException(dlqTopicSuffix, failure);
    }

    private void sleepBackoff(long backoffMs) {
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for translation retry backoff", exception);
        }
    }

    private void recordFailureAndCompensate(
            String payload,
            TenantReliabilityPolicy reliabilityPolicy,
            RuntimeException failure) {
        compensationPublisher.publish(
                kafkaProperties.getTranslationRequestTopic(),
                kafkaProperties.getTranslationRequestTopic() + reliabilityPolicy.dlqTopicSuffix(),
                payload,
                failure);
    }
}
