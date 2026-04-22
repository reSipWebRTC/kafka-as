package com.kafkaasr.tts.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.TtsRequestEvent;
import com.kafkaasr.tts.events.TranslationResultEvent;
import com.kafkaasr.tts.pipeline.TtsRequestPipelineService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meterRegistry;

    public TranslationResultConsumer(
            ObjectMapper objectMapper,
            TtsRequestPipelineService pipelineService,
            TtsRequestPublisher ttsRequestPublisher,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.ttsRequestPublisher = ttsRequestPublisher;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${tts.kafka.translation-result-topic:translation.result}",
            groupId = "${TTS_CONSUMER_GROUP_ID:tts-orchestrator}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            TranslationResultEvent translationResultEvent = parse(payload);
            TtsRequestEvent requestEvent = pipelineService.toTtsRequestEvent(translationResultEvent);

            ttsRequestPublisher.publish(requestEvent).block();
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
}
