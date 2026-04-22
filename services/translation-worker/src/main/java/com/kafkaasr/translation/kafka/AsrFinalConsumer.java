package com.kafkaasr.translation.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.translation.events.AsrFinalEvent;
import com.kafkaasr.translation.events.TranslationResultEvent;
import com.kafkaasr.translation.pipeline.TranslationPipelineService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meterRegistry;

    public AsrFinalConsumer(
            ObjectMapper objectMapper,
            TranslationPipelineService pipelineService,
            TranslationResultPublisher translationResultPublisher,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.translationResultPublisher = translationResultPublisher;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "#{@translationKafkaProperties.asrFinalTopic}",
            groupId = "${TRANSLATION_CONSUMER_GROUP_ID:translation-worker}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            AsrFinalEvent asrFinalEvent = parse(payload);
            TranslationResultEvent resultEvent = pipelineService.toTranslationResultEvent(asrFinalEvent);

            translationResultPublisher.publish(resultEvent).block();
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
}
