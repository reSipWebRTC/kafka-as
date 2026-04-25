package com.kafkaasr.tts.pipeline;

import com.kafkaasr.tts.events.TtsChunkEvent;
import com.kafkaasr.tts.events.TtsChunkPayload;
import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.events.TtsReadyEvent;
import com.kafkaasr.tts.events.TtsReadyPayload;
import com.kafkaasr.tts.events.TtsRequestEvent;
import com.kafkaasr.tts.events.TtsRequestPayload;
import com.kafkaasr.tts.events.CommandResultEvent;
import com.kafkaasr.tts.events.TranslationResultEvent;
import com.kafkaasr.tts.events.TranslationResultPayload;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TtsRequestPipelineService {

    private static final String INPUT_TRANSLATION_EVENT_TYPE = "translation.result";
    private static final String INPUT_COMMAND_RESULT_EVENT_TYPE = "command.result";
    private static final String OUTPUT_REQUEST_EVENT_TYPE = "tts.request";
    private static final String OUTPUT_CHUNK_EVENT_TYPE = "tts.chunk";
    private static final String OUTPUT_READY_EVENT_TYPE = "tts.ready";
    private static final String OUTPUT_EVENT_VERSION = "v1";
    private static final String FALLBACK_LANGUAGE = "und";
    private static final String FALLBACK_TEXT = "[empty]";

    private final VoicePolicy voicePolicy;
    private final TtsSynthesisEngine synthesisEngine;
    private final TtsKafkaProperties kafkaProperties;
    private final Clock clock;

    @Autowired
    public TtsRequestPipelineService(
            VoicePolicy voicePolicy,
            TtsSynthesisEngine synthesisEngine,
            TtsKafkaProperties kafkaProperties) {
        this(voicePolicy, synthesisEngine, kafkaProperties, Clock.systemUTC());
    }

    TtsRequestPipelineService(
            VoicePolicy voicePolicy,
            TtsSynthesisEngine synthesisEngine,
            TtsKafkaProperties kafkaProperties,
            Clock clock) {
        this.voicePolicy = voicePolicy;
        this.synthesisEngine = synthesisEngine;
        this.kafkaProperties = kafkaProperties;
        this.clock = clock;
    }

    public TtsRequestEvent toTtsRequestEvent(TranslationResultEvent translationResultEvent) {
        return toPipelineEvents(translationResultEvent).requestEvent();
    }

    public PipelineOutput toPipelineEvents(TranslationResultEvent translationResultEvent) {
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
        boolean stream = kafkaProperties.isStreamEnabled();

        TtsSynthesisEngine.SynthesisInput synthesisInput = new TtsSynthesisEngine.SynthesisInput(
                text,
                language,
                voice,
                stream);
        TtsSynthesisEngine.SynthesisPlan synthesisPlan = synthesisEngine.synthesize(translationResultEvent, synthesisInput);
        if (synthesisPlan == null) {
            synthesisPlan = new TtsSynthesisEngine.SynthesisPlan(
                    synthesisInput.text(),
                    synthesisInput.language(),
                    synthesisInput.voice(),
                    synthesisInput.stream());
        }

        String finalizedText = normalizeText(synthesisPlan.text(), synthesisInput.text());
        String finalizedLanguage = normalizeLanguage(firstNonBlank(synthesisPlan.language(), synthesisInput.language()));
        String finalizedVoice = normalizeVoice(firstNonBlank(synthesisPlan.voice(), synthesisInput.voice()));
        boolean finalizedStream = synthesisPlan.stream();

        String cacheKey = buildCacheKey(finalizedLanguage, finalizedVoice, finalizedText);

        TtsRequestEvent requestEvent = new TtsRequestEvent(
                prefixedId("evt"),
                OUTPUT_REQUEST_EVENT_TYPE,
                OUTPUT_EVENT_VERSION,
                translationResultEvent.traceId(),
                translationResultEvent.sessionId(),
                translationResultEvent.tenantId(),
                translationResultEvent.roomId(),
                kafkaProperties.getProducerId(),
                translationResultEvent.seq(),
                timestamp,
                buildIdempotencyKey(
                        translationResultEvent.sessionId(),
                        OUTPUT_REQUEST_EVENT_TYPE,
                        translationResultEvent.seq()),
                new TtsRequestPayload(
                        finalizedText,
                        finalizedLanguage,
                        finalizedVoice,
                        cacheKey,
                        finalizedStream));

        TtsChunkEvent chunkEvent = new TtsChunkEvent(
                prefixedId("evt"),
                OUTPUT_CHUNK_EVENT_TYPE,
                OUTPUT_EVENT_VERSION,
                translationResultEvent.traceId(),
                translationResultEvent.sessionId(),
                translationResultEvent.tenantId(),
                translationResultEvent.roomId(),
                kafkaProperties.getProducerId(),
                translationResultEvent.seq(),
                timestamp,
                buildIdempotencyKey(
                        translationResultEvent.sessionId(),
                        OUTPUT_CHUNK_EVENT_TYPE,
                        translationResultEvent.seq()),
                new TtsChunkPayload(
                        toChunkAudioBase64(finalizedText),
                        kafkaProperties.getTtsChunkCodec(),
                        kafkaProperties.getTtsChunkSampleRate(),
                        0,
                        true));

        TtsReadyEvent readyEvent = new TtsReadyEvent(
                prefixedId("evt"),
                OUTPUT_READY_EVENT_TYPE,
                OUTPUT_EVENT_VERSION,
                translationResultEvent.traceId(),
                translationResultEvent.sessionId(),
                translationResultEvent.tenantId(),
                translationResultEvent.roomId(),
                kafkaProperties.getProducerId(),
                translationResultEvent.seq(),
                timestamp,
                buildIdempotencyKey(
                        translationResultEvent.sessionId(),
                        OUTPUT_READY_EVENT_TYPE,
                        translationResultEvent.seq()),
                new TtsReadyPayload(
                        buildPlaybackUrl(cacheKey),
                        kafkaProperties.getTtsChunkCodec(),
                        kafkaProperties.getTtsChunkSampleRate(),
                        estimateDurationMs(finalizedText),
                        cacheKey));

        return new PipelineOutput(requestEvent, chunkEvent, readyEvent);
    }

    public PipelineOutput toPipelineEvents(CommandResultEvent commandResultEvent) {
        validateCommandResultEvent(commandResultEvent);
        TranslationResultEvent bridgeTranslationResult = toBridgeTranslationResult(commandResultEvent);
        return toPipelineEvents(bridgeTranslationResult);
    }

    private void validateTranslationResultEvent(TranslationResultEvent translationResultEvent) {
        if (translationResultEvent == null) {
            throw new IllegalArgumentException("translation.result event must not be null");
        }
        if (!INPUT_TRANSLATION_EVENT_TYPE.equals(translationResultEvent.eventType())) {
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

    private void validateCommandResultEvent(CommandResultEvent commandResultEvent) {
        if (commandResultEvent == null) {
            throw new IllegalArgumentException("command.result event must not be null");
        }
        if (!INPUT_COMMAND_RESULT_EVENT_TYPE.equals(commandResultEvent.eventType())) {
            throw new IllegalArgumentException("Unsupported command.result eventType: " + commandResultEvent.eventType());
        }
        if (commandResultEvent.sessionId() == null || commandResultEvent.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (commandResultEvent.traceId() == null || commandResultEvent.traceId().isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        if (commandResultEvent.tenantId() == null || commandResultEvent.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (commandResultEvent.payload() == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }

    private TranslationResultEvent toBridgeTranslationResult(CommandResultEvent commandResultEvent) {
        String ttsText = commandResultEvent.payload().ttsText();
        String replyText = commandResultEvent.payload().replyText();
        String normalizedText = normalizeText(ttsText, replyText);
        return new TranslationResultEvent(
                commandResultEvent.eventId(),
                INPUT_TRANSLATION_EVENT_TYPE,
                commandResultEvent.eventVersion(),
                commandResultEvent.traceId(),
                commandResultEvent.sessionId(),
                commandResultEvent.tenantId(),
                commandResultEvent.roomId(),
                commandResultEvent.producer(),
                commandResultEvent.seq(),
                commandResultEvent.ts(),
                commandResultEvent.idempotencyKey(),
                new TranslationResultPayload(
                        replyText,
                        normalizedText,
                        FALLBACK_LANGUAGE,
                        FALLBACK_LANGUAGE,
                        "command-worker"));
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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

    private String toChunkAudioBase64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private String buildPlaybackUrl(String cacheKey) {
        String prefix = kafkaProperties.getTtsReadyPlaybackUrlPrefix();
        if (prefix.endsWith("/")) {
            return prefix + cacheKey + ".wav";
        }
        return prefix + "/" + cacheKey + ".wav";
    }

    private long estimateDurationMs(String text) {
        return Math.max(250L, text.length() * 80L);
    }

    private String buildIdempotencyKey(String sessionId, String eventType, long seq) {
        return sessionId + ":" + eventType + ":" + seq;
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    public record PipelineOutput(
            TtsRequestEvent requestEvent,
            TtsChunkEvent chunkEvent,
            TtsReadyEvent readyEvent) {
    }
}
