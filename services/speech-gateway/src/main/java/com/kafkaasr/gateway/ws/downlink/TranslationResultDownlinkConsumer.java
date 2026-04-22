package com.kafkaasr.gateway.ws.downlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import com.kafkaasr.gateway.ws.downlink.events.TranslationResultEvent;
import com.kafkaasr.gateway.ws.downlink.events.TranslationResultPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gateway.downlink.enabled", havingValue = "true", matchIfMissing = true)
public class TranslationResultDownlinkConsumer {

    private final ObjectMapper objectMapper;
    private final GatewayDownlinkPublisher downlinkPublisher;
    private final MeterRegistry meterRegistry;

    public TranslationResultDownlinkConsumer(
            ObjectMapper objectMapper,
            GatewayDownlinkPublisher downlinkPublisher,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.downlinkPublisher = downlinkPublisher;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${gateway.downlink.translation-result-topic:translation.result}",
            groupId = "${gateway.downlink.consumer-group-id:speech-gateway-downlink}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            TranslationResultEvent event = parse(payload);
            String text = resolveFinalText(event.payload());
            downlinkPublisher.publishSubtitleFinal(event.sessionId(), event.seq(), text).block();
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "subtitle.final",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "subtitle.final",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("gateway.downlink.messages.duration", "type", "subtitle.final"));
        }
    }

    private TranslationResultEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, TranslationResultEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid translation.result payload", exception);
        }
    }

    private String resolveFinalText(TranslationResultPayload payload) {
        if (payload == null) {
            return "";
        }
        if (payload.translatedText() != null && !payload.translatedText().isBlank()) {
            return payload.translatedText();
        }
        if (payload.sourceText() != null && !payload.sourceText().isBlank()) {
            return payload.sourceText();
        }
        return "";
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_PAYLOAD";
        }
        return "PIPELINE_FAILURE";
    }
}
