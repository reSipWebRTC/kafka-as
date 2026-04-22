package com.kafkaasr.tts.pipeline;

import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.events.TtsRequestEvent;
import com.kafkaasr.tts.events.TtsRequestPayload;
import com.kafkaasr.tts.events.TranslationResultEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TtsRequestPipelineService {

    private static final String INPUT_EVENT_TYPE = "translation.result";
    private static final String OUTPUT_EVENT_TYPE = "tts.request";
    private static final String OUTPUT_EVENT_VERSION = "v1";
    private static final String FALLBACK_LANGUAGE = "und";
    private static final String FALLBACK_TEXT = "[empty]";

    private final VoicePolicy voicePolicy;
    private final TtsKafkaProperties kafkaProperties;
    private final Clock clock;

    @Autowired
    public TtsRequestPipelineService(
            VoicePolicy voicePolicy,
            TtsKafkaProperties kafkaProperties) {
        this(voicePolicy, kafkaProperties, Clock.systemUTC());
    }

    TtsRequestPipelineService(
            VoicePolicy voicePolicy,
            TtsKafkaProperties kafkaProperties,
            Clock clock) {
        this.voicePolicy = voicePolicy;
        this.kafkaProperties = kafkaProperties;
        this.clock = clock;
    }

    public TtsRequestEvent toTtsRequestEvent(TranslationResultEvent translationResultEvent) {
        validateTranslationResultEvent(translationResultEvent);

        long timestamp = Instant.now(clock).toEpochMilli();
        String language = normalizeLanguage(translationResultEvent.payload().targetLang());
        String text = normalizeText(
                translationResultEvent.payload().translatedText(),
                translationResultEvent.payload().sourceText());

        String voice = normalizeVoice(voicePolicy.resolveVoice(
                translationResultEvent,
                language,
                kafkaProperties.getDefaultVoice()));
        String cacheKey = buildCacheKey(language, voice, text);

        return new TtsRequestEvent(
                prefixedId("evt"),
                OUTPUT_EVENT_TYPE,
                OUTPUT_EVENT_VERSION,
                translationResultEvent.traceId(),
                translationResultEvent.sessionId(),
                translationResultEvent.tenantId(),
                translationResultEvent.roomId(),
                kafkaProperties.getProducerId(),
                translationResultEvent.seq(),
                timestamp,
                translationResultEvent.sessionId() + ":" + OUTPUT_EVENT_TYPE + ":" + translationResultEvent.seq(),
                new TtsRequestPayload(
                        text,
                        language,
                        voice,
                        cacheKey,
                        kafkaProperties.isStreamEnabled()));
    }

    private void validateTranslationResultEvent(TranslationResultEvent translationResultEvent) {
        if (translationResultEvent == null) {
            throw new IllegalArgumentException("translation.result event must not be null");
        }
        if (!INPUT_EVENT_TYPE.equals(translationResultEvent.eventType())) {
            throw new IllegalArgumentException("Unsupported translation.result eventType: " + translationResultEvent.eventType());
        }
        if (translationResultEvent.sessionId() == null || translationResultEvent.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (translationResultEvent.traceId() == null || translationResultEvent.traceId().isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        if (translationResultEvent.tenantId() == null || translationResultEvent.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (translationResultEvent.payload() == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }

    private String normalizeText(String translatedText, String sourceText) {
        if (translatedText != null && !translatedText.isBlank()) {
            return translatedText;
        }
        if (sourceText != null && !sourceText.isBlank()) {
            return sourceText;
        }
        return FALLBACK_TEXT;
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank() || language.length() < 2) {
            return FALLBACK_LANGUAGE;
        }
        return language;
    }

    private String normalizeVoice(String voice) {
        if (voice == null || voice.isBlank()) {
            return "voice-default";
        }
        return voice;
    }

    private String buildCacheKey(String language, String voice, String text) {
        String raw = language + "|" + voice + "|" + text;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return "tts:v1:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable for cache key generation", exception);
        }
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
