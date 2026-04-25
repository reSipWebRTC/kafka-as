package com.kafkaasr.command.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.events.CommandKafkaProperties;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class CommandCompensationPublisher {

    private static final Logger log = LoggerFactory.getLogger(CommandCompensationPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CommandKafkaProperties kafkaProperties;

    public CommandCompensationPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            CommandKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
    }

    void publish(String sourceTopic, String dlqTopic, String payload, Throwable failure) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "ops.compensation");
        event.put("eventVersion", "v1");
        event.put("service", "command-worker");
        event.put("sourceTopic", sourceTopic);
        event.put("dlqTopic", dlqTopic);
        event.put("ts", System.currentTimeMillis());
        event.put("failureType", failure.getClass().getSimpleName());
        event.put("failureMessage", failure.getMessage());
        event.put("idempotencyKey", extractField(payload, "idempotencyKey"));
        event.put("sessionId", extractField(payload, "sessionId"));
        event.put("rawPayload", payload);

        try {
            kafkaTemplate.send(kafkaProperties.getCompensationTopic(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize compensation signal for topic={}", sourceTopic, exception);
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
