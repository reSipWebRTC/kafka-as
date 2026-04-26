package com.kafkaasr.orchestrator.compensation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.orchestrator.api.SessionStopRequest;
import com.kafkaasr.orchestrator.api.SessionStopResponse;
import com.kafkaasr.orchestrator.events.OrchestratorKafkaProperties;
import com.kafkaasr.orchestrator.service.SessionControlException;
import com.kafkaasr.orchestrator.service.SessionLifecycleService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"orchestrator.kafka.enabled", "orchestrator.compensation-saga.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class PlatformCompensationSagaConsumer {

    private static final Set<String> MANUAL_REASONS = Set.of(
            "NON_RETRYABLE",
            "DESERIALIZATION_FAILED",
            "UNKNOWN_EVENT_VERSION");
    private static final Set<String> REPLAY_REASONS = Set.of(
            "MAX_RETRIES_EXCEEDED",
            "PROCESSING_ERROR");
    private static final Set<String> SESSION_CLOSE_SOURCE_TOPICS = Set.of(
            "asr.partial",
            "translation.result",
            "tts.chunk",
            "tts.ready",
            "command.result");

    private final ObjectMapper objectMapper;
    private final CompensationSagaStateRepository stateRepository;
    private final SessionLifecycleService sessionLifecycleService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OrchestratorKafkaProperties kafkaProperties;
    private final CompensationSagaProperties sagaProperties;
    private final MeterRegistry meterRegistry;

    public PlatformCompensationSagaConsumer(
            ObjectMapper objectMapper,
            CompensationSagaStateRepository stateRepository,
            SessionLifecycleService sessionLifecycleService,
            KafkaTemplate<String, String> kafkaTemplate,
            OrchestratorKafkaProperties kafkaProperties,
            CompensationSagaProperties sagaProperties,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.stateRepository = stateRepository;
        this.sessionLifecycleService = sessionLifecycleService;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaProperties = kafkaProperties;
        this.sagaProperties = sagaProperties;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${orchestrator.kafka.platform-dlq-topic:platform.dlq}",
            groupId = "${ORCHESTRATOR_COMPENSATION_SAGA_GROUP_ID:session-orchestrator-compensation-saga}")
    public void onPlatformDlq(String payload) {
        ParsedDlqEvent event;
        try {
            event = parseEvent(payload);
        } catch (JsonProcessingException exception) {
            incrementTotal("none", "error", "INVALID_JSON");
            return;
        } catch (RuntimeException exception) {
            incrementTotal("none", "error", normalizePipelineError(exception));
            return;
        }

        ActionDecision decision = classifyAction(event);
        String actionType = decision.actionType().metricTag;
        if (!stateRepository.begin(event.eventId(), decision.actionType().name())) {
            incrementTotal(actionType, "skipped", "ALREADY_PROCESSED");
            return;
        }

        ActionExecutionResult execution = executeWithRetry(event, decision);
        if (execution.failed()) {
            stateRepository.markFailed(event.eventId(), decision.actionType().name(), execution.code());
            incrementTotal(actionType, "error", execution.code());
        } else {
            stateRepository.markSucceeded(event.eventId(), decision.actionType().name(), execution.code());
            incrementTotal(actionType, "processed", execution.code());
        }

        if (sagaProperties.isPublishAudit()) {
            try {
                publishAudit(event, decision, execution);
            } catch (RuntimeException exception) {
                incrementTotal(actionType, "error", "AUDIT_PUBLISH_FAILED");
            }
        }
    }

    ActionDecision classifyAction(ParsedDlqEvent event) {
        if (MANUAL_REASONS.contains(event.reason())) {
            return new ActionDecision(ActionType.MANUAL, "MANUAL_INTERVENTION_REQUIRED");
        }
        if (REPLAY_REASONS.contains(event.reason()) && SESSION_CLOSE_SOURCE_TOPICS.contains(event.sourceTopic())) {
            if (event.sessionId().isBlank()) {
                return new ActionDecision(ActionType.MANUAL, "SESSION_ID_REQUIRED_FOR_CLOSE");
            }
            return new ActionDecision(ActionType.SESSION_CLOSE, "SESSION_CLOSE_REQUIRED");
        }
        if (REPLAY_REASONS.contains(event.reason())) {
            return new ActionDecision(ActionType.REPLAY, "REPLAY_REQUIRED");
        }
        return new ActionDecision(ActionType.MANUAL, "UNCLASSIFIED_REASON");
    }

    private ActionExecutionResult executeWithRetry(ParsedDlqEvent event, ActionDecision decision) {
        int maxAttempts = Math.max(1, sagaProperties.getMaxAttempts());
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeAction(event, decision);
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (attempt < maxAttempts) {
                    meterRegistry.counter(
                                    "orchestrator.compensation.saga.retry.total",
                                    "action",
                                    decision.actionType().metricTag,
                                    "code",
                                    normalizeActionError(exception))
                            .increment();
                    sleepBackoff(sagaProperties.getRetryBackoff());
                }
            }
        }
        return ActionExecutionResult.failed(
                normalizeActionError(lastFailure),
                lastFailure == null ? "action failed" : lastFailure.getMessage());
    }

    private ActionExecutionResult executeAction(ParsedDlqEvent event, ActionDecision decision) {
        return switch (decision.actionType()) {
            case REPLAY -> executeReplay(event);
            case SESSION_CLOSE -> executeSessionClose(event, decision.reasonCode());
            case MANUAL -> ActionExecutionResult.skipped(decision.reasonCode(), "manual intervention required");
        };
    }

    private ActionExecutionResult executeReplay(ParsedDlqEvent event) {
        String targetTopic = resolveReplayTargetTopic(event.sourceTopic());
        String key = resolveReplayKey(event);
        waitForSend(kafkaTemplate.send(targetTopic, key, event.rawPayload()), sagaProperties.getReplaySendTimeout());
        return ActionExecutionResult.success("REPLAY_OK");
    }

    private ActionExecutionResult executeSessionClose(ParsedDlqEvent event, String reasonCode) {
        SessionStopResponse response = sessionLifecycleService
                .stopSession(
                        event.sessionId(),
                        new SessionStopRequest(event.traceId(), buildCloseReason(event)))
                .block(sagaProperties.getSessionCloseTimeout());
        if (response == null) {
            throw new IllegalStateException("Session close response is null for " + event.sessionId());
        }
        return response.stopped()
                ? ActionExecutionResult.success("SESSION_CLOSED")
                : ActionExecutionResult.success("SESSION_ALREADY_CLOSED");
    }

    private void publishAudit(
            ParsedDlqEvent event,
            ActionDecision decision,
            ActionExecutionResult execution) {
        long now = System.currentTimeMillis();
        String traceId = event.traceId().isBlank() ? UUID.randomUUID().toString() : event.traceId();
        String sessionId = event.sessionId().isBlank() ? "governance::" + event.tenantId() : event.sessionId();
        String idempotencyKey = event.eventId() + ":saga:" + decision.actionType().name();

        Map<String, Object> details = new HashMap<>();
        details.put("eventId", event.eventId());
        details.put("sourceTopic", event.sourceTopic());
        details.put("dlqTopic", event.dlqTopic());
        details.put("dlqReason", event.reason());
        details.put("compensationAction", decision.actionType().metricTag);
        details.put("actionReasonCode", decision.reasonCode());

        Map<String, Object> payload = new HashMap<>();
        payload.put("service", "session-orchestrator");
        payload.put("action", switch (decision.actionType()) {
            case REPLAY -> "DLQ_REPLAY_COMPENSATION";
            case SESSION_CLOSE -> "SESSION_CLOSE_COMPENSATION";
            case MANUAL -> "MANUAL_INTERVENTION_ESCALATION";
        });
        payload.put("outcome", execution.auditOutcome());
        payload.put("resourceType", "session");
        payload.put("resourceId", sessionId);
        payload.put("sourceTopic", event.sourceTopic());
        payload.put("targetTopic", decision.actionType() == ActionType.REPLAY ? resolveReplayTargetTopic(event.sourceTopic()) : "");
        payload.put("reasonCode", decision.reasonCode());
        payload.put("occurredAtMs", now);
        payload.put("details", details);
        if (execution.failed()) {
            payload.put("failureType", execution.code());
            payload.put("failureMessage", execution.message());
        }

        Map<String, Object> audit = new HashMap<>();
        audit.put("eventId", UUID.randomUUID().toString());
        audit.put("eventType", "platform.audit");
        audit.put("eventVersion", "v1");
        audit.put("traceId", traceId);
        audit.put("sessionId", sessionId);
        audit.put("tenantId", event.tenantId());
        audit.put("producer", "session-orchestrator");
        audit.put("seq", 0);
        audit.put("ts", now);
        audit.put("idempotencyKey", idempotencyKey);
        audit.put("payload", payload);

        try {
            waitForSend(
                    kafkaTemplate.send(
                            kafkaProperties.getAuditTopic(),
                            event.tenantId(),
                            objectMapper.writeValueAsString(audit)),
                    sagaProperties.getReplaySendTimeout());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize audit event for " + event.eventId(), exception);
        }
    }

    private void waitForSend(java.util.concurrent.CompletableFuture<?> sendFuture, Duration timeout) {
        long timeoutMs = Math.max(1L, timeout.toMillis());
        try {
            sendFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka publish failed", exception);
        }
    }

    private ParsedDlqEvent parseEvent(String payload) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(payload);
        String eventType = event.path("eventType").asText("");
        if (!"platform.dlq".equals(eventType)) {
            throw new IllegalArgumentException("UNSUPPORTED_EVENT_TYPE");
        }
        String eventId = event.path("eventId").asText("");
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("MISSING_EVENT_ID");
        }
        JsonNode data = event.path("payload");
        if (!data.isObject()) {
            throw new IllegalArgumentException("MISSING_PAYLOAD");
        }
        String sourceTopic = data.path("sourceTopic").asText("");
        String reason = data.path("reason").asText("");
        String rawPayload = data.path("rawPayload").asText("");
        if (sourceTopic.isBlank() || rawPayload.isBlank()) {
            throw new IllegalArgumentException("MISSING_SOURCE_TOPIC_OR_RAW_PAYLOAD");
        }

        JsonNode rawPayloadJson = objectMapper.readTree(rawPayload);
        if (!rawPayloadJson.isObject()) {
            throw new IllegalArgumentException("RAW_PAYLOAD_NOT_OBJECT");
        }
        String tenantId = event.path("tenantId").asText("");
        if (tenantId.isBlank()) {
            tenantId = "tenant-unknown";
        }
        String sessionId = rawPayloadJson.path("sessionId").asText("");
        if (sessionId.isBlank()) {
            sessionId = event.path("sessionId").asText("");
        }

        return new ParsedDlqEvent(
                eventId,
                event.path("traceId").asText(""),
                tenantId,
                sessionId,
                sourceTopic,
                data.path("dlqTopic").asText(""),
                reason,
                rawPayload,
                rawPayloadJson);
    }

    private String resolveReplayTargetTopic(String sourceTopic) {
        String mode = sagaProperties.getReplayOutputMode();
        if ("fixed-topic".equals(mode)) {
            String fixed = sagaProperties.getReplayFixedTopic();
            if (fixed == null || fixed.isBlank()) {
                throw new IllegalStateException("replay output mode fixed-topic requires replayFixedTopic");
            }
            return fixed;
        }
        return sourceTopic;
    }

    private String resolveReplayKey(ParsedDlqEvent event) {
        JsonNode raw = event.rawPayloadJson();
        String configuredField = sagaProperties.getReplayKeyField();
        String key = raw.path(configuredField).asText("");
        if (key.isBlank()) {
            key = raw.path("sessionId").asText("");
        }
        if (key.isBlank()) {
            key = raw.path("tenantId").asText("");
        }
        if (key.isBlank()) {
            key = raw.path("idempotencyKey").asText("");
        }
        if (key.isBlank()) {
            key = event.tenantId();
        }
        return key;
    }

    private String buildCloseReason(ParsedDlqEvent event) {
        String prefix = sagaProperties.getCloseReasonPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "compensation.saga";
        }
        String source = normalizeTopicForReason(event.sourceTopic());
        String reason = event.reason().isBlank() ? "unknown" : event.reason().toLowerCase();
        return prefix + "." + source + "." + reason;
    }

    private String normalizeTopicForReason(String sourceTopic) {
        StringBuilder builder = new StringBuilder();
        for (char ch : sourceTopic.toCharArray()) {
            builder.append(Character.isLetterOrDigit(ch) ? ch : '_');
        }
        String normalized = builder.toString();
        while (normalized.contains("__")) {
            normalized = normalized.replace("__", "_");
        }
        return normalized.replaceAll("^_+|_+$", "").isBlank()
                ? "unknown"
                : normalized.replaceAll("^_+|_+$", "");
    }

    private void sleepBackoff(Duration backoff) {
        long backoffMs = Math.max(0L, backoff.toMillis());
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void incrementTotal(String action, String result, String code) {
        meterRegistry.counter(
                        "orchestrator.compensation.saga.total",
                        "action",
                        action,
                        "result",
                        result,
                        "code",
                        code)
                .increment();
    }

    private String normalizePipelineError(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException illegalArgumentException) {
            return illegalArgumentException.getMessage() == null
                    ? "INVALID_EVENT"
                    : illegalArgumentException.getMessage();
        }
        return "PIPELINE_FAILURE";
    }

    private String normalizeActionError(Throwable throwable) {
        if (throwable instanceof SessionControlException sessionControlException) {
            return sessionControlException.code();
        }
        return "ACTION_FAILED";
    }

    enum ActionType {
        REPLAY("replay"),
        SESSION_CLOSE("session-close"),
        MANUAL("manual");

        private final String metricTag;

        ActionType(String metricTag) {
            this.metricTag = metricTag;
        }
    }

    record ParsedDlqEvent(
            String eventId,
            String traceId,
            String tenantId,
            String sessionId,
            String sourceTopic,
            String dlqTopic,
            String reason,
            String rawPayload,
            JsonNode rawPayloadJson) {
    }

    record ActionDecision(ActionType actionType, String reasonCode) {
    }

    record ActionExecutionResult(
            boolean failed,
            String code,
            String message,
            String auditOutcome) {
        static ActionExecutionResult success(String code) {
            return new ActionExecutionResult(false, code, "", "SUCCESS");
        }

        static ActionExecutionResult skipped(String code, String message) {
            return new ActionExecutionResult(false, code, message, "SKIPPED");
        }

        static ActionExecutionResult failed(String code, String message) {
            return new ActionExecutionResult(true, code, message == null ? "" : message, "FAILED");
        }
    }
}
