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
        publishCompensation(
                "session.timeout",
                "timeout",
                timeoutType,
                "TIMEOUT_CLOSE_COMPENSATION",
                "TIMEOUT_CLOSE",
                outcome,
                state,
                failure);
    }

    @Override
    public void publishStalledClose(String stalledStage, String outcome, SessionState state, Throwable failure) {
        publishCompensation(
                "session.stalled",
                "stalled",
                stalledStage,
                "STALLED_CLOSE_COMPENSATION",
                "SESSION_STALLED",
                outcome,
                state,
                failure);
    }

    private void publishCompensation(
            String sourceTopic,
            String reasonType,
            String reasonValue,
            String action,
            String reasonCode,
            String outcome,
            SessionState state,
            Throwable failure) {
        try {
            kafkaTemplate.send(
                    kafkaProperties.getCompensationTopic(),
                    objectMapper.writeValueAsString(
                            buildLegacyCompensationEvent(sourceTopic, reasonType, reasonValue, outcome, state, failure)));
            kafkaTemplate.send(
                    kafkaProperties.getAuditTopic(),
                    objectMapper.writeValueAsString(
                            buildAuditEvent(
                                    sourceTopic,
                                    reasonType,
                                    reasonValue,
                                    action,
                                    reasonCode,
                                    outcome,
                                    state,
                                    failure)));
        } catch (JsonProcessingException exception) {
            log.warn(
                    "Failed to serialize session compensation signal for sourceTopic={}, reasonType={}, reasonValue={}",
                    sourceTopic,
                    reasonType,
                    reasonValue,
                    exception);
        }
    }

    private Map<String, Object> buildLegacyCompensationEvent(
            String sourceTopic,
            String reasonType,
            String reasonValue,
            String outcome,
            SessionState state,
            Throwable failure) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "ops.compensation");
        event.put("eventVersion", "v1");
        event.put("service", "session-orchestrator");
        event.put("sourceTopic", sourceTopic);
        if ("timeout".equals(reasonType)) {
            event.put("timeoutType", reasonValue);
        } else {
            event.put("stalledStage", reasonValue);
        }
        event.put("outcome", outcome);
        event.put("ts", System.currentTimeMillis());
        event.put("sessionId", state.sessionId());
        event.put("tenantId", state.tenantId());
        event.put("traceId", state.traceId());
        event.put("idempotencyKey", state.sessionId() + ":" + reasonType + ":" + reasonValue + ":" + state.lastSeq());
        if (failure != null) {
            event.put("failureType", failure.getClass().getSimpleName());
            event.put("failureMessage", failure.getMessage());
        }
        return event;
    }

    private Map<String, Object> buildAuditEvent(
            String sourceTopic,
            String reasonType,
            String reasonValue,
            String action,
            String reasonCode,
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
        String idempotencyKey = sessionId + ":" + reasonType + ":" + reasonValue + ":" + state.lastSeq();

        Map<String, Object> details = new HashMap<>();
        details.put("legacyEventType", "ops.compensation");
        if ("timeout".equals(reasonType)) {
            details.put("timeoutType", reasonValue);
        } else {
            details.put("stalledStage", reasonValue);
        }
        details.put("outcome", outcome);

        Map<String, Object> payload = new HashMap<>();
        payload.put("service", "session-orchestrator");
        payload.put("action", action);
        payload.put("outcome", failure == null ? "SUCCESS" : "FAILED");
        payload.put("resourceType", "session");
        payload.put("resourceId", sessionId);
        payload.put("sourceTopic", sourceTopic);
        payload.put("reasonCode", reasonCode);
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
