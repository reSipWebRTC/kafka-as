package com.kafkaasr.gateway.ws.downlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import com.kafkaasr.gateway.ws.downlink.events.AsrPartialEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gateway.downlink.enabled", havingValue = "true", matchIfMissing = true)
public class AsrPartialDownlinkConsumer {

    private final ObjectMapper objectMapper;
    private final GatewayDownlinkPublisher downlinkPublisher;
    private final MeterRegistry meterRegistry;

    public AsrPartialDownlinkConsumer(
            ObjectMapper objectMapper,
            GatewayDownlinkPublisher downlinkPublisher,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.downlinkPublisher = downlinkPublisher;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "#{@gatewayDownlinkProperties.asrPartialTopic}",
            groupId = "#{@gatewayDownlinkProperties.consumerGroupId}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            AsrPartialEvent event = parse(payload);
            String text = event.payload() == null ? "" : event.payload().text();
            downlinkPublisher.publishSubtitlePartial(event.sessionId(), event.seq(), text).block();
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "subtitle.partial",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "subtitle.partial",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("gateway.downlink.messages.duration", "type", "subtitle.partial"));
        }
    }

    private AsrPartialEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, AsrPartialEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid asr.partial payload", exception);
        }
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_PAYLOAD";
        }
        return "PIPELINE_FAILURE";
    }
}
