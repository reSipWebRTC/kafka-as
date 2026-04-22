package com.kafkaasr.asr.pipeline;

import com.kafkaasr.asr.events.AsrFinalEvent;
import com.kafkaasr.asr.events.AsrFinalPayload;
import com.kafkaasr.asr.events.AsrKafkaProperties;
import com.kafkaasr.asr.events.AsrPartialEvent;
import com.kafkaasr.asr.events.AsrPartialPayload;
import com.kafkaasr.asr.events.AudioIngressRawEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AsrPipelineService {

    private static final String INPUT_EVENT_TYPE = "audio.ingress.raw";
    private static final String OUTPUT_EVENT_TYPE_PARTIAL = "asr.partial";
    private static final String OUTPUT_EVENT_TYPE_FINAL = "asr.final";
    private static final String OUTPUT_EVENT_VERSION = "v1";
    private static final String FALLBACK_LANGUAGE = "und";

    private final AsrInferenceEngine inferenceEngine;
    private final AsrKafkaProperties kafkaProperties;
    private final Clock clock;

    @Autowired
    public AsrPipelineService(
            AsrInferenceEngine inferenceEngine,
            AsrKafkaProperties kafkaProperties) {
        this(inferenceEngine, kafkaProperties, Clock.systemUTC());
    }

    AsrPipelineService(
            AsrInferenceEngine inferenceEngine,
            AsrKafkaProperties kafkaProperties,
            Clock clock) {
        this.inferenceEngine = inferenceEngine;
        this.kafkaProperties = kafkaProperties;
        this.clock = clock;
    }

    public record AsrPipelineEvents(
            AsrPartialEvent partialEvent,
            AsrFinalEvent finalEvent) {
    }

    public AsrPipelineEvents toAsrEvents(AudioIngressRawEvent ingressEvent) {
        validateIngressEvent(ingressEvent);

        AsrInferenceEngine.AsrInferenceResult inferenceResult = inferenceEngine.infer(ingressEvent);
        long timestamp = Instant.now(clock).toEpochMilli();

        return new AsrPipelineEvents(
                toAsrPartialEvent(ingressEvent, inferenceResult, timestamp),
                toAsrFinalEvent(ingressEvent, inferenceResult, timestamp));
    }

    public AsrPartialEvent toAsrPartialEvent(AudioIngressRawEvent ingressEvent) {
        return toAsrEvents(ingressEvent).partialEvent();
    }

    public AsrFinalEvent toAsrFinalEvent(AudioIngressRawEvent ingressEvent) {
        return toAsrEvents(ingressEvent).finalEvent();
    }

    private AsrPartialEvent toAsrPartialEvent(
            AudioIngressRawEvent ingressEvent,
            AsrInferenceEngine.AsrInferenceResult inferenceResult,
            long timestamp) {
        return new AsrPartialEvent(
                prefixedId("evt"),
                OUTPUT_EVENT_TYPE_PARTIAL,
                OUTPUT_EVENT_VERSION,
                ingressEvent.traceId(),
                ingressEvent.sessionId(),
                ingressEvent.tenantId(),
                ingressEvent.roomId(),
                kafkaProperties.getProducerId(),
                ingressEvent.seq(),
                timestamp,
                idempotencyKey(ingressEvent, OUTPUT_EVENT_TYPE_PARTIAL),
                new AsrPartialPayload(
                        coalesceText(inferenceResult.text()),
                        normalizeLanguage(inferenceResult.language()),
                        normalizeConfidence(inferenceResult.confidence()),
                        false));
    }

    private AsrFinalEvent toAsrFinalEvent(
            AudioIngressRawEvent ingressEvent,
            AsrInferenceEngine.AsrInferenceResult inferenceResult,
            long timestamp) {
        return new AsrFinalEvent(
                prefixedId("evt"),
                OUTPUT_EVENT_TYPE_FINAL,
                OUTPUT_EVENT_VERSION,
                ingressEvent.traceId(),
                ingressEvent.sessionId(),
                ingressEvent.tenantId(),
                ingressEvent.roomId(),
                kafkaProperties.getProducerId(),
                ingressEvent.seq(),
                timestamp,
                idempotencyKey(ingressEvent, OUTPUT_EVENT_TYPE_FINAL),
                new AsrFinalPayload(
                        coalesceText(inferenceResult.text()),
                        normalizeLanguage(inferenceResult.language()),
                        normalizeConfidence(inferenceResult.confidence()),
                        inferenceResult.stable()));
    }

    private String idempotencyKey(AudioIngressRawEvent ingressEvent, String eventType) {
        return ingressEvent.sessionId() + ":" + eventType + ":" + ingressEvent.seq();
    }

    private void validateIngressEvent(AudioIngressRawEvent ingressEvent) {
        if (ingressEvent == null) {
            throw new IllegalArgumentException("Ingress event must not be null");
        }
        if (!INPUT_EVENT_TYPE.equals(ingressEvent.eventType())) {
            throw new IllegalArgumentException("Unsupported ingress eventType: " + ingressEvent.eventType());
        }
        if (ingressEvent.sessionId() == null || ingressEvent.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (ingressEvent.traceId() == null || ingressEvent.traceId().isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        if (ingressEvent.tenantId() == null || ingressEvent.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (ingressEvent.payload() == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }

    private String coalesceText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text;
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank() || language.length() < 2) {
            return FALLBACK_LANGUAGE;
        }
        return language;
    }

    private double normalizeConfidence(double confidence) {
        if (confidence < 0d) {
            return 0d;
        }
        if (confidence > 1d) {
            return 1d;
        }
        return confidence;
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
