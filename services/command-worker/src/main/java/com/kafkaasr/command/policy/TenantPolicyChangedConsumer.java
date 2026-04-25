package com.kafkaasr.command.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "command.control-plane.enabled", havingValue = "true", matchIfMissing = true)
public class TenantPolicyChangedConsumer {

    private static final String EVENT_TYPE = "tenant.policy.changed";

    private final ObjectMapper objectMapper;
    private final TenantRoutingPolicyResolver policyResolver;
    private final MeterRegistry meterRegistry;

    public TenantPolicyChangedConsumer(
            ObjectMapper objectMapper,
            TenantRoutingPolicyResolver policyResolver,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.policyResolver = policyResolver;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${command.control-plane.policy-changed-topic:tenant.policy.changed}",
            groupId = "${COMMAND_POLICY_CHANGED_CONSUMER_GROUP_ID:command-policy-changed}")
    public void onMessage(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            if (!EVENT_TYPE.equals(event.path("eventType").asText())) {
                meterRegistry.counter(
                                "command.policy.distribution.consume.total",
                                "result",
                                "ignored",
                                "code",
                                "UNSUPPORTED_EVENT")
                        .increment();
                return;
            }

            String tenantId = extractTenantId(event);
            if (tenantId == null || tenantId.isBlank()) {
                meterRegistry.counter(
                                "command.policy.distribution.consume.total",
                                "result",
                                "error",
                                "code",
                                "INVALID_TENANT")
                        .increment();
                return;
            }

            policyResolver.invalidateTenant(tenantId);
            meterRegistry.counter(
                            "command.policy.distribution.consume.total",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
        } catch (JsonProcessingException exception) {
            meterRegistry.counter(
                            "command.policy.distribution.consume.total",
                            "result",
                            "error",
                            "code",
                            "INVALID_JSON")
                    .increment();
        }
    }

    private String extractTenantId(JsonNode event) {
        String payloadTenantId = event.path("payload").path("tenantId").asText("");
        if (!payloadTenantId.isBlank()) {
            return payloadTenantId;
        }
        String envelopeTenantId = event.path("tenantId").asText("");
        return envelopeTenantId.isBlank() ? null : envelopeTenantId;
    }
}
