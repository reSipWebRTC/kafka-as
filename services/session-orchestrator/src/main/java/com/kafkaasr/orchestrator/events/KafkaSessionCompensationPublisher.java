package com.kafkaasr.orchestrator.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.orchestrator.session.SessionState;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "orchestrator.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaSessionCompensationPublisher implements SessionCompensationPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaSessionCompensationPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OrchestratorKafkaProperties kafkaProperties;

    public KafkaSessionCompensationPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            OrchestratorKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public void publishTimeoutClose(String timeoutType, String outcome, SessionState state, Throwable failure) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "ops.compensation");
        event.put("eventVersion", "v1");
        event.put("service", "session-orchestrator");
        event.put("sourceTopic", "session.timeout");
        event.put("timeoutType", timeoutType);
        event.put("outcome", outcome);
        event.put("ts", System.currentTimeMillis());
        event.put("sessionId", state.sessionId());
        event.put("tenantId", state.tenantId());
        event.put("traceId", state.traceId());
        event.put("idempotencyKey", state.sessionId() + ":timeout:" + timeoutType + ":" + state.lastSeq());
        if (failure != null) {
            event.put("failureType", failure.getClass().getSimpleName());
            event.put("failureMessage", failure.getMessage());
        }

        try {
            kafkaTemplate.send(kafkaProperties.getCompensationTopic(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize session compensation signal for timeoutType={}", timeoutType, exception);
        }
    }
}
