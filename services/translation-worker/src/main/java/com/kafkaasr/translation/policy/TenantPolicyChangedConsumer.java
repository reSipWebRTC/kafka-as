package com.kafkaasr.translation.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "translation.control-plane.enabled", havingValue = "true", matchIfMissing = true)
public class TenantPolicyChangedConsumer {

    private static final String EVENT_TYPE = "tenant.policy.changed";
    private static final String RESULT_EVENT_TYPE = "tenant.policy.distribution.result";
    private static final String EVENT_VERSION = "v1";
    private static final String SYNTHETIC_SESSION_PREFIX = "tenant-policy-distribution::";
    private static final String STATUS_APPLIED = "APPLIED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_IGNORED = "IGNORED";
    private static final Set<String> SUPPORTED_OPERATIONS = Set.of(
            "CREATED",
            "UPDATED",
            "ROLLED_BACK",
            "ROLLED_BACK_TO_VERSION");

    private final ObjectMapper objectMapper;
    private final TenantReliabilityPolicyResolver policyResolver;
    private final TranslationControlPlaneProperties controlPlaneProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public TenantPolicyChangedConsumer(
            ObjectMapper objectMapper,
            TenantReliabilityPolicyResolver policyResolver,
            TranslationControlPlaneProperties controlPlaneProperties,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.policyResolver = policyResolver;
        this.controlPlaneProperties = controlPlaneProperties;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${translation.control-plane.policy-changed-topic:tenant.policy.changed}",
            groupId = "${TRANSLATION_POLICY_CHANGED_CONSUMER_GROUP_ID:translation-policy-changed}")
    public void onMessage(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            DistributionContext context = extractContext(event);
            if (!EVENT_TYPE.equals(event.path("eventType").asText())) {
                meterRegistry.counter(
                                "translation.policy.distribution.consume.total",
                                "result",
                                "ignored",
                                "code",
                                "UNSUPPORTED_EVENT")
                        .increment();
                publishDistributionResult(context, STATUS_IGNORED, "UNSUPPORTED_EVENT", "unsupported event type");
                return;
            }

            if (!isValidContext(context)) {
                meterRegistry.counter(
                                "translation.policy.distribution.consume.total",
                                "result",
                                "error",
                                "code",
                                "INVALID_CONTEXT")
                        .increment();
                return;
            }

            try {
                policyResolver.invalidateTenant(context.tenantId());
                meterRegistry.counter(
                                "translation.policy.distribution.consume.total",
                                "result",
                                "success",
                                "code",
                                "OK")
                        .increment();
                publishDistributionResult(context, STATUS_APPLIED, null, null);
            } catch (RuntimeException exception) {
                meterRegistry.counter(
                                "translation.policy.distribution.consume.total",
                                "result",
                                "error",
                                "code",
                                "APPLY_FAILED")
                        .increment();
                publishDistributionResult(context, STATUS_FAILED, "APPLY_FAILED", exception.getMessage());
            }
        } catch (JsonProcessingException exception) {
            meterRegistry.counter(
                            "translation.policy.distribution.consume.total",
                            "result",
                            "error",
                            "code",
                            "INVALID_JSON")
                    .increment();
        }
    }

    private DistributionContext extractContext(JsonNode event) {
        String payloadTenantId = event.path("payload").path("tenantId").asText("");
        String envelopeTenantId = event.path("tenantId").asText("");
        String tenantId = payloadTenantId.isBlank() ? envelopeTenantId : payloadTenantId;
        String operation = event.path("payload").path("operation").asText("");
        long policyVersion = event.path("payload").path("policyVersion").asLong(0L);
        String sourceEventId = nonBlankOrDefault(event.path("eventId").asText(""), prefixedId("src"));
        String traceId = nonBlankOrDefault(event.path("traceId").asText(""), prefixedId("trc"));
        String sessionId = nonBlankOrDefault(event.path("sessionId").asText(""), SYNTHETIC_SESSION_PREFIX + tenantId);
        return new DistributionContext(
                tenantId,
                policyVersion,
                operation,
                sourceEventId,
                traceId,
                sessionId);
    }

    private boolean isValidContext(DistributionContext context) {
        return context.tenantId() != null
                && !context.tenantId().isBlank()
                && context.policyVersion() > 0
                && SUPPORTED_OPERATIONS.contains(context.operation());
    }

    private void publishDistributionResult(
            DistributionContext context,
            String status,
            String reasonCode,
            String reasonMessage) {
        if (!isValidContext(context)) {
            meterRegistry.counter(
                            "translation.policy.distribution.execute.total",
                            "result",
                            "skipped",
                            "code",
                            "INVALID_CONTEXT")
                    .increment();
            return;
        }

        long nowMs = System.currentTimeMillis();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("tenantId", context.tenantId());
        payload.put("policyVersion", context.policyVersion());
        payload.put("operation", context.operation());
        payload.put("service", "translation-worker");
        payload.put("region", controlPlaneProperties.getDistributionRegion());
        payload.put("status", status);
        if (reasonCode != null && !reasonCode.isBlank()) {
            payload.put("reasonCode", reasonCode);
        }
        if (reasonMessage != null && !reasonMessage.isBlank()) {
            payload.put("reasonMessage", reasonMessage);
        }
        payload.put("appliedAtMs", nowMs);
        payload.put("sourceEventId", context.sourceEventId());

        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventId", prefixedId("evt"));
        event.put("eventType", RESULT_EVENT_TYPE);
        event.put("eventVersion", EVENT_VERSION);
        event.put("traceId", context.traceId());
        event.put("sessionId", context.sessionId());
        event.put("tenantId", context.tenantId());
        event.put("producer", controlPlaneProperties.getDistributionProducerId());
        event.put("seq", context.policyVersion());
        event.put("ts", nowMs);
        event.put(
                "idempotencyKey",
                context.tenantId()
                        + ":"
                        + RESULT_EVENT_TYPE
                        + ":translation-worker:"
                        + context.policyVersion()
                        + ":"
                        + context.sourceEventId());
        event.set("payload", payload);

        try {
            kafkaTemplate.send(
                    controlPlaneProperties.getPolicyDistributionResultTopic(),
                    context.tenantId(),
                    objectMapper.writeValueAsString(event));
            meterRegistry.counter(
                            "translation.policy.distribution.execute.total",
                            "result",
                            normalizeResultTag(status),
                            "code",
                            reasonCode == null || reasonCode.isBlank() ? "OK" : reasonCode)
                    .increment();
        } catch (Exception exception) {
            meterRegistry.counter(
                            "translation.policy.distribution.execute.total",
                            "result",
                            "error",
                            "code",
                            "PUBLISH_FAILED")
                    .increment();
        }
    }

    private String normalizeResultTag(String status) {
        return switch (status) {
            case STATUS_APPLIED -> "applied";
            case STATUS_FAILED -> "failed";
            case STATUS_IGNORED -> "ignored";
            default -> "unknown";
        };
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String nonBlankOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private record DistributionContext(
            String tenantId,
            long policyVersion,
            String operation,
            String sourceEventId,
            String traceId,
            String sessionId) {
    }
}
