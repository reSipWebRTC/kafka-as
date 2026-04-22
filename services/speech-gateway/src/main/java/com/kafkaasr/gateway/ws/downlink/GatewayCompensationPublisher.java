package com.kafkaasr.gateway.ws.downlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class GatewayCompensationPublisher {

    private static final Logger log = LoggerFactory.getLogger(GatewayCompensationPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayDownlinkProperties downlinkProperties;

    public GatewayCompensationPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            GatewayDownlinkProperties downlinkProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.downlinkProperties = downlinkProperties;
    }

    void publish(String sourceTopic, String payload, Throwable failure) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "ops.compensation");
        event.put("eventVersion", "v1");
        event.put("service", "speech-gateway");
        event.put("sourceTopic", sourceTopic);
        event.put("ts", System.currentTimeMillis());
        event.put("failureType", failure.getClass().getSimpleName());
        event.put("failureMessage", failure.getMessage());
        event.put("idempotencyKey", extractField(payload, "idempotencyKey"));
        event.put("sessionId", extractField(payload, "sessionId"));
        event.put("rawPayload", payload);

        try {
            kafkaTemplate.send(downlinkProperties.getCompensationTopic(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize gateway compensation signal for topic={}", sourceTopic, exception);
        }
    }

    private String extractField(String payload, String fieldName) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode field = root.get(fieldName);
            if (field == null || field.isNull()) {
                return "";
            }
            return field.asText("");
        } catch (JsonProcessingException exception) {
            return "";
        }
    }
}
