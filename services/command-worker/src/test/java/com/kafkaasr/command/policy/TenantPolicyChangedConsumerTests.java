package com.kafkaasr.command.policy;

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
    private TenantRoutingPolicyResolver policyResolver;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private TenantPolicyChangedConsumer consumer;

    @BeforeEach
    void setUp() {
        CommandControlPlaneProperties properties = new CommandControlPlaneProperties();
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
