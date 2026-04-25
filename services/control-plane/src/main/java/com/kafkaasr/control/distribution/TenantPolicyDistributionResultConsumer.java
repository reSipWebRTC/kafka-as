package com.kafkaasr.control.distribution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "control.kafka.enabled", havingValue = "true")
public class TenantPolicyDistributionResultConsumer {

    private static final String EVENT_TYPE = "tenant.policy.distribution.result";
    private static final Set<String> SUPPORTED_STATUS = Set.of("APPLIED", "FAILED", "IGNORED");

    private final ObjectMapper objectMapper;
    private final TenantPolicyDistributionStatusRepository repository;
    private final MeterRegistry meterRegistry;

    public TenantPolicyDistributionResultConsumer(
            ObjectMapper objectMapper,
            TenantPolicyDistributionStatusRepository repository,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${control.kafka.policy-distribution-result-topic:tenant.policy.distribution.result}",
            groupId = "${CONTROL_POLICY_DISTRIBUTION_RESULT_CONSUMER_GROUP_ID:control-policy-distribution-result}")
    public void onMessage(String rawPayload) {
        try {
            JsonNode event = objectMapper.readTree(rawPayload);
            if (!EVENT_TYPE.equals(event.path("eventType").asText())) {
                meterRegistry.counter(
                                "controlplane.tenant.policy.distribution.result.consume.total",
                                "result",
                                "ignored",
                                "code",
                                "UNSUPPORTED_EVENT")
                        .increment();
                return;
            }

            TenantPolicyDistributionExecutionState state = parseState(event);
            if (state == null) {
                meterRegistry.counter(
                                "controlplane.tenant.policy.distribution.result.consume.total",
                                "result",
                                "error",
                                "code",
                                "INVALID_PAYLOAD")
                        .increment();
                return;
            }

            repository.save(state);
            meterRegistry.counter(
                            "controlplane.tenant.policy.distribution.result.consume.total",
                            "result",
                            "success",
                            "code",
                            "OK",
                            "status",
                            state.status())
                    .increment();
        } catch (JsonProcessingException exception) {
            meterRegistry.counter(
                            "controlplane.tenant.policy.distribution.result.consume.total",
                            "result",
                            "error",
                            "code",
                            "INVALID_JSON")
                    .increment();
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "controlplane.tenant.policy.distribution.result.consume.total",
                            "result",
                            "error",
                            "code",
                            "SAVE_FAILED")
                    .increment();
        }
    }

    private TenantPolicyDistributionExecutionState parseState(JsonNode event) {
        JsonNode payload = event.path("payload");
        String tenantId = firstNonBlank(
                payload.path("tenantId").asText(""),
                event.path("tenantId").asText(""));
        long policyVersion = payload.path("policyVersion").asLong(0L);
        String service = payload.path("service").asText("");
        String region = payload.path("region").asText("local");
        String status = payload.path("status").asText("");
        String reasonCode = payload.path("reasonCode").asText("");
        String reasonMessage = payload.path("reasonMessage").asText("");
        long appliedAtMs = payload.path("appliedAtMs").asLong(0L);
        String sourceEventId = payload.path("sourceEventId").asText("");
        String eventId = event.path("eventId").asText("");
        String producer = event.path("producer").asText("");
        long updatedTsMs = event.path("ts").asLong(appliedAtMs);

        if (tenantId.isBlank()
                || policyVersion <= 0
                || service.isBlank()
                || region.isBlank()
                || !SUPPORTED_STATUS.contains(status)
                || appliedAtMs <= 0
                || sourceEventId.isBlank()
                || eventId.isBlank()
                || producer.isBlank()) {
            return null;
        }

        if ("FAILED".equals(status) && reasonCode.isBlank()) {
            return null;
        }

        return new TenantPolicyDistributionExecutionState(
                tenantId,
                policyVersion,
                service,
                region,
                status,
                reasonCode.isBlank() ? null : reasonCode,
                reasonMessage.isBlank() ? null : reasonMessage,
                appliedAtMs,
                sourceEventId,
                eventId,
                producer,
                updatedTsMs <= 0 ? appliedAtMs : updatedTsMs);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }
}
