package com.kafkaasr.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kafkaasr.control.api.TenantPolicyUpsertRequest;
import com.kafkaasr.control.policy.TenantPolicyRepository;
import com.kafkaasr.control.policy.TenantPolicyState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class TenantPolicyServiceTests {

    private TenantPolicyService service;

    @BeforeEach
    void setUp() {
        service = new TenantPolicyService(
                new InMemoryTenantPolicyRepository(),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    @Test
    void upsertCreatesPolicyWhenMissing() {
        TenantPolicyUpsertRequest request = new TenantPolicyUpsertRequest(
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                100,
                1200,
                true);

        StepVerifier.create(service.upsertTenantPolicy("tenant-a", request))
                .assertNext(response -> {
                    assertEquals("tenant-a", response.tenantId());
                    assertEquals(1L, response.version());
                    assertEquals(true, response.created());
                    assertEquals("funasr-v1", response.asrModel());
                })
                .verifyComplete();
    }

    @Test
    void upsertUpdatesExistingPolicyAndIncrementsVersion() {
        TenantPolicyUpsertRequest first = new TenantPolicyUpsertRequest(
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                100,
                1200,
                true);
        TenantPolicyUpsertRequest second = new TenantPolicyUpsertRequest(
                "zh-CN",
                "ja-JP",
                "funasr-v2",
                "mt-v2",
                "ja-JP-neural-a",
                50,
                800,
                true);

        StepVerifier.create(service.upsertTenantPolicy("tenant-b", first))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(service.upsertTenantPolicy("tenant-b", second))
                .assertNext(response -> {
                    assertEquals(2L, response.version());
                    assertEquals(false, response.created());
                    assertEquals("ja-JP", response.targetLang());
                    assertEquals("funasr-v2", response.asrModel());
                })
                .verifyComplete();
    }

    @Test
    void getMissingPolicyReturnsDomainError() {
        StepVerifier.create(service.getTenantPolicy("missing-tenant"))
                .expectErrorSatisfies(error -> {
                    ControlPlaneException exception = (ControlPlaneException) error;
                    assertEquals("TENANT_POLICY_NOT_FOUND", exception.code());
                })
                .verify();
    }

    private static final class InMemoryTenantPolicyRepository implements TenantPolicyRepository {

        private final Map<String, TenantPolicyState> states = new HashMap<>();

        @Override
        public TenantPolicyState findByTenantId(String tenantId) {
            return states.get(tenantId);
        }

        @Override
        public boolean createIfAbsent(TenantPolicyState state) {
            return states.putIfAbsent(state.tenantId(), state) == null;
        }

        @Override
        public void save(TenantPolicyState state) {
            states.put(state.tenantId(), state);
        }
    }
}
