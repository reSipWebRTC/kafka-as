package com.kafkaasr.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kafkaasr.control.api.TenantPolicyUpsertRequest;
import com.kafkaasr.control.events.ControlKafkaProperties;
import com.kafkaasr.control.events.TenantPolicyChangedEvent;
import com.kafkaasr.control.events.TenantPolicyChangedPublisher;
import com.kafkaasr.control.policy.TenantPolicyRepository;
import com.kafkaasr.control.policy.TenantPolicyState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TenantPolicyServiceTests {

    private RecordingPolicyChangedPublisher policyChangedPublisher;
    private TenantPolicyService service;

    @BeforeEach
    void setUp() {
        policyChangedPublisher = new RecordingPolicyChangedPublisher();

        ControlKafkaProperties kafkaProperties = new ControlKafkaProperties();
        kafkaProperties.setProducerId("control-plane");
        kafkaProperties.setPolicyChangedTopic("tenant.policy.changed");

        service = new TenantPolicyService(
                new InMemoryTenantPolicyRepository(),
                policyChangedPublisher,
                kafkaProperties,
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
                true,
                true,
                10,
                true,
                45000L,
                4,
                350L,
                ".tenant-a.dlq");

        StepVerifier.create(service.upsertTenantPolicy("tenant-a", request))
                .assertNext(response -> {
                    assertEquals("tenant-a", response.tenantId());
                    assertEquals(1L, response.version());
                    assertEquals(true, response.created());
                    assertEquals("funasr-v1", response.asrModel());
                    assertEquals(true, response.grayEnabled());
                    assertEquals(10, response.grayTrafficPercent());
                    assertEquals(true, response.controlPlaneFallbackFailOpen());
                    assertEquals(45000L, response.controlPlaneFallbackCacheTtlMs());
                    assertEquals(4, response.retryMaxAttempts());
                    assertEquals(350L, response.retryBackoffMs());
                    assertEquals(".tenant-a.dlq", response.dlqTopicSuffix());
                })
                .verifyComplete();

        assertEquals(1, policyChangedPublisher.events().size());
        TenantPolicyChangedEvent event = policyChangedPublisher.events().getFirst();
        assertEquals("tenant.policy.changed", event.eventType());
        assertEquals("tenant-a", event.tenantId());
        assertEquals(1L, event.seq());
        assertEquals("CREATED", event.payload().operation());
        assertEquals(1L, event.payload().policyVersion());
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
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        TenantPolicyUpsertRequest second = new TenantPolicyUpsertRequest(
                "zh-CN",
                "ja-JP",
                "funasr-v2",
                "mt-v2",
                "ja-JP-neural-a",
                50,
                800,
                true,
                true,
                25,
                false,
                60000L,
                5,
                500L,
                ".tenant-b.dlq");

        StepVerifier.create(service.upsertTenantPolicy("tenant-b", first))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(service.upsertTenantPolicy("tenant-b", second))
                .assertNext(response -> {
                    assertEquals(2L, response.version());
                    assertEquals(false, response.created());
                    assertEquals("ja-JP", response.targetLang());
                    assertEquals("funasr-v2", response.asrModel());
                    assertEquals(true, response.grayEnabled());
                    assertEquals(25, response.grayTrafficPercent());
                    assertEquals(false, response.controlPlaneFallbackFailOpen());
                    assertEquals(60000L, response.controlPlaneFallbackCacheTtlMs());
                    assertEquals(5, response.retryMaxAttempts());
                    assertEquals(500L, response.retryBackoffMs());
                    assertEquals(".tenant-b.dlq", response.dlqTopicSuffix());
                })
                .verifyComplete();

        assertEquals(2, policyChangedPublisher.events().size());
        TenantPolicyChangedEvent secondEvent = policyChangedPublisher.events().get(1);
        assertEquals("UPDATED", secondEvent.payload().operation());
        assertEquals(2L, secondEvent.payload().policyVersion());
    }

    @Test
    void upsertUsesCompatibilityDefaultsWhenNewFieldsMissing() {
        TenantPolicyUpsertRequest request = new TenantPolicyUpsertRequest(
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                100,
                1200,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        StepVerifier.create(service.upsertTenantPolicy("tenant-defaults", request))
                .assertNext(response -> {
                    assertEquals(false, response.grayEnabled());
                    assertEquals(0, response.grayTrafficPercent());
                    assertEquals(false, response.controlPlaneFallbackFailOpen());
                    assertEquals(30000L, response.controlPlaneFallbackCacheTtlMs());
                    assertEquals(3, response.retryMaxAttempts());
                    assertEquals(200L, response.retryBackoffMs());
                    assertEquals(".dlq", response.dlqTopicSuffix());
                })
                .verifyComplete();

        assertEquals(1, policyChangedPublisher.events().size());
    }

    @Test
    void upsertKeepsHttpSuccessWhenPolicyChangedPublishFails() {
        policyChangedPublisher.setFailPublishing(true);
        TenantPolicyUpsertRequest request = new TenantPolicyUpsertRequest(
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                100,
                1200,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        StepVerifier.create(service.upsertTenantPolicy("tenant-publish-fail", request))
                .assertNext(response -> {
                    assertEquals("tenant-publish-fail", response.tenantId());
                    assertEquals(1L, response.version());
                })
                .verifyComplete();

        assertTrue(policyChangedPublisher.events().isEmpty());
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

    @Test
    void rollbackRevertsToPreviousPolicyAndIncrementsVersion() {
        TenantPolicyUpsertRequest first = new TenantPolicyUpsertRequest(
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                100,
                1200,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        TenantPolicyUpsertRequest second = new TenantPolicyUpsertRequest(
                "zh-CN",
                "ja-JP",
                "funasr-v2",
                "mt-v2",
                "ja-JP-neural-a",
                50,
                800,
                true,
                true,
                25,
                false,
                60000L,
                5,
                500L,
                ".tenant-rollback.dlq");

        StepVerifier.create(service.upsertTenantPolicy("tenant-rollback", first))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(service.upsertTenantPolicy("tenant-rollback", second))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(service.rollbackTenantPolicy("tenant-rollback"))
                .assertNext(response -> {
                    assertEquals("tenant-rollback", response.tenantId());
                    assertEquals(3L, response.version());
                    assertEquals(false, response.created());
                    assertEquals("en-US", response.targetLang());
                    assertEquals("funasr-v1", response.asrModel());
                    assertEquals(100, response.maxConcurrentSessions());
                    assertEquals(".dlq", response.dlqTopicSuffix());
                })
                .verifyComplete();

        assertEquals(3, policyChangedPublisher.events().size());
        TenantPolicyChangedEvent rollbackEvent = policyChangedPublisher.events().get(2);
        assertEquals("ROLLED_BACK", rollbackEvent.payload().operation());
        assertEquals(3L, rollbackEvent.payload().policyVersion());
    }

    @Test
    void rollbackWithoutPreviousVersionReturnsDomainError() {
        TenantPolicyUpsertRequest request = new TenantPolicyUpsertRequest(
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                100,
                1200,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        StepVerifier.create(service.upsertTenantPolicy("tenant-no-history", request))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(service.rollbackTenantPolicy("tenant-no-history"))
                .expectErrorSatisfies(error -> {
                    ControlPlaneException exception = (ControlPlaneException) error;
                    assertEquals("TENANT_POLICY_ROLLBACK_NOT_AVAILABLE", exception.code());
                })
                .verify();
    }

    @Test
    void rollbackMissingPolicyReturnsDomainError() {
        StepVerifier.create(service.rollbackTenantPolicy("missing-tenant"))
                .expectErrorSatisfies(error -> {
                    ControlPlaneException exception = (ControlPlaneException) error;
                    assertEquals("TENANT_POLICY_NOT_FOUND", exception.code());
                })
                .verify();
    }

    @Test
    void rollbackKeepsHttpSuccessWhenPolicyChangedPublishFails() {
        TenantPolicyUpsertRequest first = new TenantPolicyUpsertRequest(
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                100,
                1200,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        TenantPolicyUpsertRequest second = new TenantPolicyUpsertRequest(
                "zh-CN",
                "ja-JP",
                "funasr-v2",
                "mt-v2",
                "ja-JP-neural-a",
                50,
                800,
                true,
                true,
                25,
                false,
                60000L,
                5,
                500L,
                ".tenant-rollback-fail.dlq");

        StepVerifier.create(service.upsertTenantPolicy("tenant-rollback-fail", first))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(service.upsertTenantPolicy("tenant-rollback-fail", second))
                .expectNextCount(1)
                .verifyComplete();

        policyChangedPublisher.setFailPublishing(true);
        StepVerifier.create(service.rollbackTenantPolicy("tenant-rollback-fail"))
                .assertNext(response -> {
                    assertEquals("tenant-rollback-fail", response.tenantId());
                    assertEquals(3L, response.version());
                })
                .verifyComplete();
        assertEquals(2, policyChangedPublisher.events().size());
    }

    private static final class InMemoryTenantPolicyRepository implements TenantPolicyRepository {

        private final Map<String, TenantPolicyState> states = new HashMap<>();
        private final Map<String, Deque<TenantPolicyState>> histories = new HashMap<>();

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

        @Override
        public void appendHistory(TenantPolicyState state) {
            histories.computeIfAbsent(state.tenantId(), ignored -> new ArrayDeque<>()).addLast(state);
        }

        @Override
        public TenantPolicyState findLatestHistory(String tenantId) {
            Deque<TenantPolicyState> history = histories.get(tenantId);
            return history == null ? null : history.peekLast();
        }

        @Override
        public void removeLatestHistory(String tenantId) {
            Deque<TenantPolicyState> history = histories.get(tenantId);
            if (history != null) {
                history.pollLast();
            }
        }
    }

    private static final class RecordingPolicyChangedPublisher implements TenantPolicyChangedPublisher {

        private final List<TenantPolicyChangedEvent> events = new ArrayList<>();
        private boolean failPublishing;

        @Override
        public Mono<Void> publish(TenantPolicyChangedEvent event) {
            if (failPublishing) {
                return Mono.error(new IllegalStateException("kafka unavailable"));
            }
            events.add(event);
            return Mono.empty();
        }

        public List<TenantPolicyChangedEvent> events() {
            return events;
        }

        public void setFailPublishing(boolean failPublishing) {
            this.failPublishing = failPublishing;
        }
    }
}
