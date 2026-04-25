package com.kafkaasr.orchestrator.policy;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class TenantPolicyChangedConsumerTests {

    @Mock
    private ControlPlaneTenantPolicyClient tenantPolicyClient;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private TenantPolicyChangedConsumer consumer;

    @BeforeEach
    void setUp() {
        ControlPlaneClientProperties properties = new ControlPlaneClientProperties();
        consumer = new TenantPolicyChangedConsumer(
                new ObjectMapper(),
                tenantPolicyClient,
                properties,
                kafkaTemplate,
                new SimpleMeterRegistry());
    }

    @Test
    void invalidatesTenantPolicyCacheWhenPolicyChangedEventReceived() {
        consumer.onMessage("""
                {
                  "eventType": "tenant.policy.changed",
                  "tenantId": "tenant-a",
                  "payload": {
                    "tenantId": "tenant-a",
                    "policyVersion": 2,
                    "updatedAtMs": 1713744000000,
                    "operation": "UPDATED"
                  }
                }
                """);

        verify(tenantPolicyClient).invalidateTenantPolicy("tenant-a");
        verify(kafkaTemplate).send(
                eq("tenant.policy.distribution.result"),
                eq("tenant-a"),
                anyString());
    }

    @Test
    void ignoresInvalidPayloadWithoutThrowing() {
        consumer.onMessage("{invalid-json");

        verify(tenantPolicyClient, never()).invalidateTenantPolicy("tenant-a");
        verifyNoInteractions(kafkaTemplate);
    }
}
