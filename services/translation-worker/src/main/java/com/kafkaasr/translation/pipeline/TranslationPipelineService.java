package com.kafkaasr.translation.pipeline;

import com.kafkaasr.translation.events.AsrFinalEvent;
import com.kafkaasr.translation.events.TranslationKafkaProperties;
import com.kafkaasr.translation.events.TranslationResultEvent;
import com.kafkaasr.translation.events.TranslationResultPayload;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TranslationPipelineService {

    private static final String INPUT_EVENT_TYPE = "asr.final";
    private static final String OUTPUT_EVENT_TYPE = "translation.result";
    private static final String OUTPUT_EVENT_VERSION = "v1";
    private static final String FALLBACK_LANGUAGE = "und";

    private final TranslationEngine translationEngine;
    private final TranslationKafkaProperties kafkaProperties;
    private final Clock clock;

    @Autowired
    public TranslationPipelineService(
            TranslationEngine translationEngine,
            TranslationKafkaProperties kafkaProperties) {
        this(translationEngine, kafkaProperties, Clock.systemUTC());
    }

    TranslationPipelineService(
            TranslationEngine translationEngine,
            TranslationKafkaProperties kafkaProperties,
            Clock clock) {
        this.translationEngine = translationEngine;
        this.kafkaProperties = kafkaProperties;
        this.clock = clock;
    }

    public TranslationResultEvent toTranslationResultEvent(AsrFinalEvent asrFinalEvent) {
        validateAsrFinalEvent(asrFinalEvent);

        String targetLang = normalizeLanguage(kafkaProperties.getDefaultTargetLang());
        TranslationEngine.TranslationResult result = translationEngine.translate(asrFinalEvent, targetLang);
        long timestamp = Instant.now(clock).toEpochMilli();

        String sourceText = coalesceText(asrFinalEvent.payload().text());
        String sourceLang = normalizeLanguage(firstNonBlank(result.sourceLang(), asrFinalEvent.payload().language(), FALLBACK_LANGUAGE));
        String translatedText = coalesceText(result.translatedText());
        String normalizedTargetLang = normalizeLanguage(firstNonBlank(result.targetLang(), targetLang));
        String engine = firstNonBlank(result.engine(), kafkaProperties.getEngineName());

        return new TranslationResultEvent(
                prefixedId("evt"),
                OUTPUT_EVENT_TYPE,
                OUTPUT_EVENT_VERSION,
                asrFinalEvent.traceId(),
                asrFinalEvent.sessionId(),
                asrFinalEvent.tenantId(),
                asrFinalEvent.roomId(),
                kafkaProperties.getProducerId(),
                asrFinalEvent.seq(),
                timestamp,
                asrFinalEvent.sessionId() + ":" + OUTPUT_EVENT_TYPE + ":" + asrFinalEvent.seq(),
                new TranslationResultPayload(
                        sourceText,
                        translatedText,
                        sourceLang,
                        normalizedTargetLang,
                        engine));
    }

    private void validateAsrFinalEvent(AsrFinalEvent asrFinalEvent) {
        if (asrFinalEvent == null) {
            throw new IllegalArgumentException("asr.final event must not be null");
        }
        if (!INPUT_EVENT_TYPE.equals(asrFinalEvent.eventType())) {
            throw new IllegalArgumentException("Unsupported asr.final eventType: " + asrFinalEvent.eventType());
        }
        if (asrFinalEvent.sessionId() == null || asrFinalEvent.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (asrFinalEvent.traceId() == null || asrFinalEvent.traceId().isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        if (asrFinalEvent.tenantId() == null || asrFinalEvent.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (asrFinalEvent.payload() == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }

    private String coalesceText(String text) {
        return text == null ? "" : text;
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank() || language.length() < 2) {
            return FALLBACK_LANGUAGE;
        }
        return language;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
