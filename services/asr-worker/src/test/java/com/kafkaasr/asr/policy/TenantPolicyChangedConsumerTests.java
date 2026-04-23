package com.kafkaasr.asr.policy;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantPolicyChangedConsumerTests {

    @Mock
    private TenantReliabilityPolicyResolver policyResolver;

    private TenantPolicyChangedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TenantPolicyChangedConsumer(
                new ObjectMapper(),
                policyResolver,
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
    }

    @Test
    void ignoresInvalidPayloadWithoutThrowing() {
        consumer.onMessage("{invalid-json");

        verify(policyResolver, never()).invalidateTenant("tenant-a");
    }
}
