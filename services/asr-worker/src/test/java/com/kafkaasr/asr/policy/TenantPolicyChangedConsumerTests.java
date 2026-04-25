package com.kafkaasr.asr.policy;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

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
    private TenantReliabilityPolicyResolver policyResolver;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private TenantPolicyChangedConsumer consumer;

    @BeforeEach
    void setUp() {
        AsrControlPlaneProperties properties = new AsrControlPlaneProperties();
        consumer = new TenantPolicyChangedConsumer(
                new ObjectMapper(),
                policyResolver,
                properties,
                kafkaTemplate,
                new SimpleMeterRegistry());
    }

    @Test
    void invalidatesTenantCacheWhenPolicyChangedEventReceived() {
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

        verify(policyResolver).invalidateTenant("tenant-a");
        verify(kafkaTemplate).send(
                eq("tenant.policy.distribution.result"),
                eq("tenant-a"),
                anyString());
    }

    @Test
    void ignoresInvalidPayloadWithoutThrowing() {
        consumer.onMessage("{invalid-json");

        verify(policyResolver, never()).invalidateTenant("tenant-a");
        verifyNoInteractions(kafkaTemplate);
    }
}
