package com.kafkaasr.gateway.ws.downlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import com.kafkaasr.gateway.ws.downlink.events.SessionControlEvent;
import com.kafkaasr.gateway.ws.downlink.events.SessionControlPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gateway.downlink.enabled", havingValue = "true", matchIfMissing = true)
public class SessionControlDownlinkConsumer {

    private static final String CLOSED_STATUS = "CLOSED";

    private final ObjectMapper objectMapper;
    private final GatewayDownlinkPublisher downlinkPublisher;
    private final MeterRegistry meterRegistry;

    public SessionControlDownlinkConsumer(
            ObjectMapper objectMapper,
            GatewayDownlinkPublisher downlinkPublisher,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.downlinkPublisher = downlinkPublisher;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "#{@gatewayDownlinkProperties.sessionControlTopic}",
            groupId = "#{@gatewayDownlinkProperties.consumerGroupId}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SessionControlEvent event = parse(payload);
            SessionControlPayload controlPayload = event.payload();
            if (controlPayload != null && CLOSED_STATUS.equals(controlPayload.status())) {
                String reason = controlPayload.reason() == null ? "session.closed" : controlPayload.reason();
                downlinkPublisher.publishSessionClosed(event.sessionId(), reason).block();
            }

            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "session.closed",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "gateway.downlink.messages.total",
                            "type",
                            "session.closed",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("gateway.downlink.messages.duration", "type", "session.closed"));
        }
    }

    private SessionControlEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, SessionControlEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid session.control payload", exception);
        }
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_PAYLOAD";
        }
        return "PIPELINE_FAILURE";
    }
}
