package com.kafkaasr.asr.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.asr.events.AsrKafkaProperties;
import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.pipeline.AsrPipelineService;
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
@ConditionalOnProperty(name = "asr.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class AudioIngressConsumer {

    private static final Logger log = LoggerFactory.getLogger(AudioIngressConsumer.class);

    private final ObjectMapper objectMapper;
    private final AsrPipelineService pipelineService;
    private final AsrPartialPublisher asrPartialPublisher;
    private final AsrFinalPublisher asrFinalPublisher;
    private final AsrCompensationPublisher compensationPublisher;
    private final AsrKafkaProperties kafkaProperties;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final MeterRegistry meterRegistry;
    private final Map<String, Integer> failureAttempts = new ConcurrentHashMap<>();

    public AudioIngressConsumer(
            ObjectMapper objectMapper,
            AsrPipelineService pipelineService,
            AsrPartialPublisher asrPartialPublisher,
            AsrFinalPublisher asrFinalPublisher,
            AsrCompensationPublisher compensationPublisher,
            AsrKafkaProperties kafkaProperties,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.asrPartialPublisher = asrPartialPublisher;
        this.asrFinalPublisher = asrFinalPublisher;
        this.compensationPublisher = compensationPublisher;
        this.kafkaProperties = kafkaProperties;
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
        String failureKey = "raw:" + Integer.toHexString(payload.hashCode());
        try {
            AudioIngressRawEvent ingressEvent = parse(payload);
            failureKey = resolveFailureKey(ingressEvent.idempotencyKey(), payload);
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
            AsrPipelineService.AsrPipelineEvents pipelineEvents = pipelineService.toAsrEvents(ingressEvent);

            asrPartialPublisher.publish(pipelineEvents.partialEvent()).block();
            asrFinalPublisher.publish(pipelineEvents.finalEvent()).block();
            idempotencyGuard.markProcessed(ingressEvent.idempotencyKey());
            failureAttempts.remove(failureKey);
            meterRegistry.counter(
                            "asr.pipeline.messages.total",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
            log.debug(
                    "Published asr.partial and asr.final events sessionId={} seq={}",
                    pipelineEvents.finalEvent().sessionId(),
                    pipelineEvents.finalEvent().seq());
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "asr.pipeline.messages.total",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            recordFailureAndCompensate(failureKey, payload, exception);
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("asr.pipeline.duration"));
        }
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
                kafkaProperties.getAudioIngressTopic(),
                payload,
                failure);
    }
}
