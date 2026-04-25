package com.kafkaasr.control.distribution;

import static org.mockito.ArgumentMatchers.any;
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
class TenantPolicyDistributionResultConsumerTests {

    @Mock
    private TenantPolicyDistributionStatusRepository repository;

    private TenantPolicyDistributionResultConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TenantPolicyDistributionResultConsumer(
                new ObjectMapper(),
                repository,
                new SimpleMeterRegistry());
    }

    @Test
    void savesValidDistributionResultEvent() {
        consumer.onMessage("""
                {
                  "eventId": "evt-res-1",
                  "eventType": "tenant.policy.distribution.result",
                  "eventVersion": "v1",
                  "traceId": "trc-1",
                  "sessionId": "tenant-policy-distribution::tenant-a",
                  "tenantId": "tenant-a",
                  "producer": "asr-worker",
                  "seq": 3,
                  "ts": 1713745000000,
                  "idempotencyKey": "tenant-a:key",
                  "payload": {
                    "tenantId": "tenant-a",
                    "policyVersion": 3,
                    "operation": "UPDATED",
                    "service": "asr-worker",
                    "region": "local",
                    "status": "APPLIED",
                    "appliedAtMs": 1713745000000,
                    "sourceEventId": "evt-src-1"
                  }
                }
                """);

        verify(repository).save(any(TenantPolicyDistributionExecutionState.class));
    }

    @Test
    void ignoresUnsupportedEventType() {
        consumer.onMessage("""
                {
                  "eventType": "tenant.policy.changed",
                  "tenantId": "tenant-a",
                  "payload": {
                    "tenantId": "tenant-a"
                  }
                }
                """);

        verify(repository, never()).save(any(TenantPolicyDistributionExecutionState.class));
    }
}
