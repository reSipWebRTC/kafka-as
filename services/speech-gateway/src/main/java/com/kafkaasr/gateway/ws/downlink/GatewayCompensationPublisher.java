package com.kafkaasr.gateway.ws.downlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
        try {
            kafkaTemplate.send(
                    downlinkProperties.getCompensationTopic(),
                    objectMapper.writeValueAsString(buildLegacyCompensationEvent(sourceTopic, payload, failure)));
            kafkaTemplate.send(
                    downlinkProperties.getAuditTopic(),
                    objectMapper.writeValueAsString(buildAuditEvent(sourceTopic, payload, failure)));
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize gateway compensation signal for topic={}", sourceTopic, exception);
        }
    }

    private Map<String, Object> buildLegacyCompensationEvent(String sourceTopic, String payload, Throwable failure) {
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
        return event;
    }

    private Map<String, Object> buildAuditEvent(String sourceTopic, String payload, Throwable failure) {
        String eventId = UUID.randomUUID().toString();
        String tenantId = nonBlankOrDefault(extractField(payload, "tenantId"), "tenant-unknown");
        String sessionId = nonBlankOrDefault(extractField(payload, "sessionId"), "governance::" + tenantId);
        String traceId = nonBlankOrDefault(extractField(payload, "traceId"), eventId);
        String idempotencyKey = nonBlankOrDefault(extractField(payload, "idempotencyKey"), eventId);
        long now = System.currentTimeMillis();

        Map<String, Object> details = new HashMap<>();
        details.put("legacyEventType", "ops.compensation");
        details.put("rawPayload", payload);

        Map<String, Object> payloadNode = new HashMap<>();
        payloadNode.put("service", "speech-gateway");
        payloadNode.put("action", "COMPENSATION_SIGNAL");
        payloadNode.put("outcome", "FAILED");
        payloadNode.put("resourceType", "kafka.topic");
        payloadNode.put("resourceId", sourceTopic);
        payloadNode.put("sourceTopic", sourceTopic);
        payloadNode.put("failureType", failure.getClass().getSimpleName());
        payloadNode.put("failureMessage", failure.getMessage());
        payloadNode.put("reasonCode", "DOWNLINK_FAILURE");
        payloadNode.put("occurredAtMs", now);
        payloadNode.put("details", details);

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "platform.audit");
        event.put("eventVersion", "v1");
        event.put("traceId", traceId);
        event.put("sessionId", sessionId);
        event.put("tenantId", tenantId);
        putIfPresent(event, "userId", extractField(payload, "userId"));
        putIfPresent(event, "roomId", extractField(payload, "roomId"));
        event.put("producer", "speech-gateway");
        event.put("seq", parseLong(extractField(payload, "seq")));
        event.put("ts", now);
        event.put("idempotencyKey", idempotencyKey);
        event.put("payload", payloadNode);
        return event;
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

    private String nonBlankOrDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
