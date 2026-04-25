package com.kafkaasr.command.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.events.CommandKafkaProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class CommandPlatformDlqPublisher {

    private static final Logger log = LoggerFactory.getLogger(CommandPlatformDlqPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CommandKafkaProperties kafkaProperties;

    CommandPlatformDlqPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            CommandKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
    }

    void publish(ConsumerRecord<?, ?> record, String dlqTopic, Throwable failure) {
        String rawPayload = record.value() == null ? "" : String.valueOf(record.value());
        String eventId = UUID.randomUUID().toString();
        String tenantId = nonBlankOrDefault(extractField(rawPayload, "tenantId"), "tenant-unknown");
        String sessionId = nonBlankOrDefault(extractField(rawPayload, "sessionId"), "governance::" + tenantId);
        String traceId = nonBlankOrDefault(extractField(rawPayload, "traceId"), eventId);
        String idempotencyKey = nonBlankOrDefault(
                extractField(rawPayload, "idempotencyKey"),
                "platform.dlq:%s:%d:%d".formatted(record.topic(), record.partition(), record.offset()));

        Map<String, Object> payload = new HashMap<>();
        payload.put("service", "command-worker");
        payload.put("sourceTopic", record.topic());
        payload.put("sourcePartition", record.partition());
        payload.put("sourceOffset", record.offset());
        payload.put("sourceTs", Math.max(0L, record.timestamp()));
        payload.put("dlqTopic", dlqTopic);
        payload.put("reason", classifyReason(failure));
        payload.put("failureType", failure.getClass().getSimpleName());
        payload.put("failureMessage", failure.getMessage());
        payload.put("attempt", 1);
        putIfPresent(payload, "originalEventType", extractField(rawPayload, "eventType"));
        putIfPresent(payload, "originalEventVersion", extractField(rawPayload, "eventVersion"));
        payload.put("replayHint", "kafka://%s/%d/%d".formatted(record.topic(), record.partition(), record.offset()));
        payload.put("rawPayload", rawPayload);

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "platform.dlq");
        event.put("eventVersion", "v1");
        event.put("traceId", traceId);
        event.put("sessionId", sessionId);
        event.put("tenantId", tenantId);
        putIfPresent(event, "userId", extractField(rawPayload, "userId"));
        putIfPresent(event, "roomId", extractField(rawPayload, "roomId"));
        event.put("producer", "command-worker");
        event.put("seq", Math.max(0L, record.offset()));
        event.put("ts", System.currentTimeMillis());
        event.put("idempotencyKey", idempotencyKey);
        event.put("payload", payload);

        try {
            kafkaTemplate.send(kafkaProperties.getPlatformDlqTopic(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize platform DLQ event for topic={}", record.topic(), exception);
        }
    }

    private String classifyReason(Throwable failure) {
        if (failure instanceof IllegalArgumentException) {
            return "NON_RETRYABLE";
        }
        Throwable current = failure;
        while (current != null) {
            String typeName = current.getClass().getSimpleName();
            if ("TenantAwareDlqException".equals(typeName)) {
                return "MAX_RETRIES_EXCEEDED";
            }
            if (typeName.contains("Deserialization") || typeName.contains("Serialization")) {
                return "DESERIALIZATION_FAILED";
            }
            current = current.getCause();
        }
        return "PROCESSING_ERROR";
    }

    private String extractField(String payload, String fieldName) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
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

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
