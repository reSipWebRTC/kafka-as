package com.kafkaasr.asr.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.asr.events.AsrKafkaProperties;
import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.pipeline.AsrEngineException;
import com.kafkaasr.asr.pipeline.AsrPipelineService;
import com.kafkaasr.asr.policy.TenantReliabilityPolicy;
import com.kafkaasr.asr.policy.TenantReliabilityPolicyResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "asr.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class AudioIngressConsumer {

    private static final Logger log = LoggerFactory.getLogger(AudioIngressConsumer.class);

    private final ObjectMapper objectMapper;
    private final AsrPipelineService pipelineService;
    private final AsrPartialPublisher asrPartialPublisher;
    private final AsrFinalPublisher asrFinalPublisher;
    private final AsrCompensationPublisher compensationPublisher;
    private final AsrKafkaProperties kafkaProperties;
    private final TenantReliabilityPolicyResolver reliabilityPolicyResolver;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final MeterRegistry meterRegistry;

    public AudioIngressConsumer(
            ObjectMapper objectMapper,
            AsrPipelineService pipelineService,
            AsrPartialPublisher asrPartialPublisher,
            AsrFinalPublisher asrFinalPublisher,
            AsrCompensationPublisher compensationPublisher,
            AsrKafkaProperties kafkaProperties,
            TenantReliabilityPolicyResolver reliabilityPolicyResolver,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.asrPartialPublisher = asrPartialPublisher;
        this.asrFinalPublisher = asrFinalPublisher;
        this.compensationPublisher = compensationPublisher;
        this.kafkaProperties = kafkaProperties;
        this.reliabilityPolicyResolver = reliabilityPolicyResolver;
        this.idempotencyGuard = new TimedIdempotencyGuard(
                kafkaProperties.isIdempotencyEnabled(),
                kafkaProperties.getIdempotencyTtlMs());
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "#{@asrKafkaProperties.audioIngressTopic}",
            groupId = "${ASR_CONSUMER_GROUP_ID:asr-worker}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            AudioIngressRawEvent ingressEvent = parse(payload);
            if (idempotencyGuard.isDuplicate(ingressEvent.idempotencyKey())) {
                meterRegistry.counter(
                                "asr.pipeline.messages.total",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                log.debug("Dropped duplicated audio.ingress.raw event idempotencyKey={}", ingressEvent.idempotencyKey());
                return;
            }
            TenantReliabilityPolicy reliabilityPolicy = reliabilityPolicyResolver.resolve(ingressEvent.tenantId());
            processWithRetry(ingressEvent, payload, reliabilityPolicy);
        } catch (IllegalArgumentException exception) {
            meterRegistry.counter(
                            "asr.pipeline.messages.total",
                            "result",
                            "error",
                            "code",
                            "INVALID_PAYLOAD")
                    .increment();
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("asr.pipeline.duration"));
        }
    }

    private void processWithRetry(
            AudioIngressRawEvent ingressEvent,
            String payload,
            TenantReliabilityPolicy reliabilityPolicy) {
        int maxAttempts = Math.max(1, reliabilityPolicy.retryMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processOnce(ingressEvent);
                return;
            } catch (RuntimeException failure) {
                boolean retryable = isRetryable(failure);
                if (retryable && attempt < maxAttempts) {
                    sleepBackoff(reliabilityPolicy.retryBackoffMs());
                    continue;
                }
                meterRegistry.counter(
                                "asr.pipeline.messages.total",
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

    private void processOnce(AudioIngressRawEvent ingressEvent) {
        AsrPipelineService.AsrPipelineEvents pipelineEvents = pipelineService.toAsrEvents(ingressEvent);
        if (pipelineEvents.partialEvent() != null) {
            asrPartialPublisher.publish(pipelineEvents.partialEvent()).block();
        }
        if (pipelineEvents.finalEvent() != null) {
            asrFinalPublisher.publish(pipelineEvents.finalEvent()).block();
        }
        idempotencyGuard.markProcessed(ingressEvent.idempotencyKey());
        meterRegistry.counter(
                        "asr.pipeline.messages.total",
                        "result",
                        "success",
                        "code",
                        "OK")
                .increment();
        log.debug(
                "Published ASR events sessionId={} seq={} partial={} final={}",
                ingressEvent.sessionId(),
                ingressEvent.seq(),
                pipelineEvents.partialEvent() != null,
                pipelineEvents.finalEvent() != null);
    }

    private AudioIngressRawEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, AudioIngressRawEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid audio.ingress.raw payload", exception);
        }
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_PAYLOAD";
        }
        if (throwable instanceof AsrEngineException asrEngineException) {
            return asrEngineException.errorCode();
        }
        return "PIPELINE_FAILURE";
    }

    private boolean isRetryable(RuntimeException failure) {
        if (failure instanceof AsrEngineException asrEngineException) {
            return asrEngineException.retryable();
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
            throw new IllegalStateException("Interrupted while waiting for ASR retry backoff", exception);
        }
    }

    private void recordFailureAndCompensate(
            String payload,
            TenantReliabilityPolicy reliabilityPolicy,
            RuntimeException failure) {
        compensationPublisher.publish(
                kafkaProperties.getAudioIngressTopic(),
                kafkaProperties.getAudioIngressTopic() + reliabilityPolicy.dlqTopicSuffix(),
                payload,
                failure);
    }
}
