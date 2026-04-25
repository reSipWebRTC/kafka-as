package com.kafkaasr.orchestrator.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.orchestrator.session.SessionState;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
        try {
            kafkaTemplate.send(
                    kafkaProperties.getCompensationTopic(),
                    objectMapper.writeValueAsString(buildLegacyCompensationEvent(timeoutType, outcome, state, failure)));
            kafkaTemplate.send(
                    kafkaProperties.getAuditTopic(),
                    objectMapper.writeValueAsString(buildAuditEvent(timeoutType, outcome, state, failure)));
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize session compensation signal for timeoutType={}", timeoutType, exception);
        }
    }

    private Map<String, Object> buildLegacyCompensationEvent(
            String timeoutType,
            String outcome,
            SessionState state,
            Throwable failure) {
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
        return event;
    }

    private Map<String, Object> buildAuditEvent(
            String timeoutType,
            String outcome,
            SessionState state,
            Throwable failure) {
        String eventId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String tenantId = (state.tenantId() == null || state.tenantId().isBlank()) ? "tenant-unknown" : state.tenantId();
        String sessionId = (state.sessionId() == null || state.sessionId().isBlank())
                ? "governance::" + tenantId
                : state.sessionId();
        String traceId = (state.traceId() == null || state.traceId().isBlank()) ? eventId : state.traceId();
        String idempotencyKey = sessionId + ":timeout:" + timeoutType + ":" + state.lastSeq();

        Map<String, Object> details = new HashMap<>();
        details.put("legacyEventType", "ops.compensation");
        details.put("timeoutType", timeoutType);
        details.put("outcome", outcome);

        Map<String, Object> payload = new HashMap<>();
        payload.put("service", "session-orchestrator");
        payload.put("action", "TIMEOUT_CLOSE_COMPENSATION");
        payload.put("outcome", failure == null ? "SUCCESS" : "FAILED");
        payload.put("resourceType", "session");
        payload.put("resourceId", sessionId);
        payload.put("reasonCode", "TIMEOUT_CLOSE");
        payload.put("occurredAtMs", now);
        payload.put("details", details);
        if (failure != null) {
            payload.put("failureType", failure.getClass().getSimpleName());
            payload.put("failureMessage", failure.getMessage());
        }

        Map<String, Object> event = new HashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "platform.audit");
        event.put("eventVersion", "v1");
        event.put("traceId", traceId);
        event.put("sessionId", sessionId);
        event.put("tenantId", tenantId);
        event.put("producer", "session-orchestrator");
        event.put("seq", Math.max(0L, state.lastSeq()));
        event.put("ts", now);
        event.put("idempotencyKey", idempotencyKey);
        event.put("payload", payload);
        return event;
    }
}
