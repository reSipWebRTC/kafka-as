package com.kafkaasr.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kafkaasr.orchestrator.api.SessionStartRequest;
import com.kafkaasr.orchestrator.api.SessionStopRequest;
import com.kafkaasr.orchestrator.events.OrchestratorKafkaProperties;
import com.kafkaasr.orchestrator.events.SessionControlEvent;
import com.kafkaasr.orchestrator.events.SessionControlPublisher;
import com.kafkaasr.orchestrator.policy.TenantPolicy;
import com.kafkaasr.orchestrator.policy.TenantPolicyClient;
import com.kafkaasr.orchestrator.policy.TenantPolicyClientException;
import com.kafkaasr.orchestrator.session.SessionProgressMarker;
import com.kafkaasr.orchestrator.session.SessionState;
import com.kafkaasr.orchestrator.session.SessionStateRepository;
import com.kafkaasr.orchestrator.session.SessionStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SessionLifecycleServiceTests {

    private RecordingPublisher publisher;
    private SessionStateRepository stateRepository;
    private TenantPolicyClient tenantPolicyClient;
    private SessionLifecycleService sessionLifecycleService;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        stateRepository = new InMemorySessionStateRepository();
        tenantPolicyClient = new DelegatingTenantPolicyClient(tenantId -> Mono.just(defaultPolicy(tenantId)));

        OrchestratorKafkaProperties properties = new OrchestratorKafkaProperties();
        properties.setProducerId("session-orchestrator");
        properties.setSessionControlTopic("session.control");

        sessionLifecycleService = new SessionLifecycleService(
                publisher,
                properties,
                tenantPolicyClient,
                stateRepository,
                Clock.fixed(Instant.parse("2026-04-21T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    @Test
    void startCreatesSessionAndPublishesControlEvent() {
        StepVerifier.create(sessionLifecycleService.startSession(new SessionStartRequest(
                        "sess-1",
                        "tenant-a",
                        "zh-CN",
                        "en-US",
                        "trc-1")))
                .assertNext(response -> {
                    assertTrue(response.created());
                    assertEquals("STREAMING", response.status());
                    assertEquals(1L, response.seq());
                    assertEquals("trc-1", response.traceId());
                })
                .verifyComplete();

        assertEquals(1, publisher.events().size());
        SessionControlEvent event = publisher.events().getFirst();
        assertEquals("session.control", event.eventType());
        assertEquals(1L, event.seq());
        assertEquals("START", event.payload().action());
        assertEquals("STREAMING", event.payload().status());
    }

    @Test
    void repeatedStartIsIdempotentAndDoesNotRepublish() {
        SessionStartRequest request = new SessionStartRequest("sess-2", "tenant-a", "zh-CN", "en-US", "");

        StepVerifier.create(sessionLifecycleService.startSession(request))
                .assertNext(response -> assertTrue(response.created()))
                .verifyComplete();

        StepVerifier.create(sessionLifecycleService.startSession(request))
                .assertNext(response -> assertFalse(response.created()))
                .verifyComplete();

        assertEquals(1, publisher.events().size());
    }

    @Test
    void stopClosesSessionAndPublishesOnce() {
        SessionStartRequest request = new SessionStartRequest("sess-3", "tenant-a", "zh-CN", "en-US", "trc-1");
        StepVerifier.create(sessionLifecycleService.startSession(request))
                .assertNext(response -> assertTrue(response.created()))
                .verifyComplete();

        StepVerifier.create(sessionLifecycleService.stopSession(
                        "sess-3",
                        new SessionStopRequest("trc-2", "client.stop")))
                .assertNext(response -> {
                    assertTrue(response.stopped());
                    assertEquals("CLOSED", response.status());
                    assertEquals(2L, response.seq());
                    assertEquals("trc-2", response.traceId());
                })
                .verifyComplete();

        StepVerifier.create(sessionLifecycleService.stopSession(
                        "sess-3",
                        SessionStopRequest.empty()))
                .assertNext(response -> assertFalse(response.stopped()))
                .verifyComplete();

        assertEquals(2, publisher.events().size());
        SessionControlEvent stopEvent = publisher.events().get(1);
        assertEquals("STOP", stopEvent.payload().action());
        assertEquals(2L, stopEvent.seq());
    }

    @Test
    void stopMissingSessionReturnsSessionNotFound() {
        SessionControlException exception = assertThrows(
                SessionControlException.class,
                () -> sessionLifecycleService.stopSession("missing", SessionStopRequest.empty()));
        assertEquals("SESSION_NOT_FOUND", exception.code());
    }

    @Test
    void startFailsWhenTenantPolicyDisabled() {
        tenantPolicyClient = new DelegatingTenantPolicyClient(tenantId -> Mono.just(new TenantPolicy(
                tenantId,
                "zh-CN",
                "en-US",
                "asr-v1",
                "trans-v1",
                "voice-a",
                3,
                300,
                false,
                false,
                0,
                false,
                30000L,
                3,
                200L,
                ".dlq",
                1L,
                1000L)));
        sessionLifecycleService = new SessionLifecycleService(
                publisher,
                kafkaProperties(),
                tenantPolicyClient,
                stateRepository,
                Clock.fixed(Instant.parse("2026-04-21T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());

        StepVerifier.create(sessionLifecycleService.startSession(new SessionStartRequest(
                        "sess-disabled",
                        "tenant-a",
                        "zh-CN",
                        "en-US",
                        "trc-disabled")))
                .expectErrorSatisfies(error -> {
                    SessionControlException exception = (SessionControlException) error;
                    assertEquals("INVALID_MESSAGE", exception.code());
                })
                .verify();
    }

    @Test
    void startFailsWhenLanguageNotAllowedByPolicy() {
        StepVerifier.create(sessionLifecycleService.startSession(new SessionStartRequest(
                        "sess-lang",
                        "tenant-a",
                        "ja-JP",
                        "en-US",
                        "trc-lang")))
                .expectErrorSatisfies(error -> {
                    SessionControlException exception = (SessionControlException) error;
                    assertEquals("INVALID_MESSAGE", exception.code());
                })
                .verify();
    }

    @Test
    void startFailsWhenConcurrentSessionLimitExceeded() {
        stateRepository.createIfAbsent(new SessionState(
                "sess-existing",
                "tenant-a",
                "zh-CN",
                "en-US",
                "trc-existing",
                SessionStatus.STREAMING,
                1L,
                1000L,
                1000L,
                0L,
                0L,
                0L,
                0L,
                0L,
                ""));
        tenantPolicyClient = new DelegatingTenantPolicyClient(tenantId -> Mono.just(new TenantPolicy(
                tenantId,
                "zh-CN",
                "en-US",
                "asr-v1",
                "trans-v1",
                "voice-a",
                1,
                300,
                true,
                false,
                0,
                false,
                30000L,
                3,
                200L,
                ".dlq",
                1L,
                1000L)));
        sessionLifecycleService = new SessionLifecycleService(
                publisher,
                kafkaProperties(),
                tenantPolicyClient,
                stateRepository,
                Clock.fixed(Instant.parse("2026-04-21T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());

        StepVerifier.create(sessionLifecycleService.startSession(new SessionStartRequest(
                        "sess-limit",
                        "tenant-a",
                        "zh-CN",
                        "en-US",
                        "trc-limit")))
                .expectErrorSatisfies(error -> {
                    SessionControlException exception = (SessionControlException) error;
                    assertEquals("RATE_LIMITED", exception.code());
                })
                .verify();
    }

    @Test
    void recordProgressUpdatesAggregationTimestamps() {
        StepVerifier.create(sessionLifecycleService.startSession(new SessionStartRequest(
                        "sess-progress",
                        "tenant-a",
                        "zh-CN",
                        "en-US",
                        "trc-progress")))
                .assertNext(response -> assertTrue(response.created()))
                .verifyComplete();

        SessionLifecycleService.ProgressUpdateResult result = sessionLifecycleService.recordProgress(
                "sess-progress",
                SessionProgressMarker.TTS_READY,
                1710000000123L);

        assertEquals(SessionLifecycleService.ProgressUpdateResult.UPDATED, result);
        SessionState updated = stateRepository.findBySessionId("sess-progress");
        assertEquals(SessionStatus.TTS_ACTIVE, updated.status());
        assertEquals(1710000000123L, updated.lastTtsReadyAtMs());
    }

    @Test
    void getSessionReturnsAggregatedTimestampsAndLatencies() {
        StepVerifier.create(sessionLifecycleService.startSession(new SessionStartRequest(
                        "sess-status",
                        "tenant-a",
                        "zh-CN",
                        "en-US",
                        "trc-status")))
                .assertNext(response -> assertTrue(response.created()))
                .verifyComplete();

        SessionState started = stateRepository.findBySessionId("sess-status");
        long asrFinalAt = started.startedAtMs() + 100L;
        long translationAt = started.startedAtMs() + 200L;
        sessionLifecycleService.recordProgress("sess-status", SessionProgressMarker.ASR_FINAL, asrFinalAt);
        sessionLifecycleService.recordProgress("sess-status", SessionProgressMarker.TRANSLATION_RESULT, translationAt);

        StepVerifier.create(sessionLifecycleService.getSession("sess-status"))
                .assertNext(response -> {
                    assertEquals("sess-status", response.sessionId());
                    assertEquals("tenant-a", response.tenantId());
                    assertEquals("TRANSLATING", response.status());
                    assertEquals(asrFinalAt, response.lastFinalAtMs());
                    assertEquals(translationAt, response.lastTranslationAtMs());
                    assertEquals(100L, response.asrFinalLatencyMs());
                    assertEquals(200L, response.translationLatencyMs());
                    assertNull(response.ttsReadyLatencyMs());
                })
                .verifyComplete();
    }

    @Test
    void recordProgressIgnoresMissingSession() {
        SessionLifecycleService.ProgressUpdateResult result = sessionLifecycleService.recordProgress(
                "sess-missing",
                SessionProgressMarker.ASR_PARTIAL,
                1710000000456L);

        assertEquals(SessionLifecycleService.ProgressUpdateResult.SESSION_NOT_FOUND, result);
    }

    @Test
    void getSessionMissingReturnsSessionNotFound() {
        StepVerifier.create(sessionLifecycleService.getSession("missing-session"))
                .expectErrorSatisfies(error -> {
                    SessionControlException exception = (SessionControlException) error;
                    assertEquals("SESSION_NOT_FOUND", exception.code());
                })
                .verify();
    }

    @Test
    void startFailsWhenPolicyServiceUnavailable() {
        tenantPolicyClient = new DelegatingTenantPolicyClient(tenantId -> Mono.error(
                TenantPolicyClientException.unavailable("control-plane unavailable", null)));
        sessionLifecycleService = new SessionLifecycleService(
                publisher,
                kafkaProperties(),
                tenantPolicyClient,
                stateRepository,
                Clock.fixed(Instant.parse("2026-04-21T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());

        StepVerifier.create(sessionLifecycleService.startSession(new SessionStartRequest(
                        "sess-cp-down",
                        "tenant-a",
                        "zh-CN",
                        "en-US",
                        "trc-cp-down")))
                .expectErrorSatisfies(error -> {
                    SessionControlException exception = (SessionControlException) error;
                    assertEquals("INTERNAL_ERROR", exception.code());
                })
                .verify();
    }

    private static OrchestratorKafkaProperties kafkaProperties() {
        OrchestratorKafkaProperties properties = new OrchestratorKafkaProperties();
        properties.setProducerId("session-orchestrator");
        properties.setSessionControlTopic("session.control");
        return properties;
    }

    private static TenantPolicy defaultPolicy(String tenantId) {
        return new TenantPolicy(
                tenantId,
                "zh-CN",
                "en-US",
                "asr-v1",
                "trans-v1",
                "voice-a",
                3,
                300,
                true,
                false,
                0,
                false,
                30000L,
                3,
                200L,
                ".dlq",
                1L,
                1000L);
    }

    private static final class RecordingPublisher implements SessionControlPublisher {

        private final List<SessionControlEvent> events = new ArrayList<>();

        @Override
        public Mono<Void> publish(SessionControlEvent event) {
            events.add(event);
            return Mono.empty();
        }

        List<SessionControlEvent> events() {
            return events;
        }
    }

    private static final class InMemorySessionStateRepository implements SessionStateRepository {

        private final Map<String, SessionState> states = new HashMap<>();

        @Override
        public SessionState findBySessionId(String sessionId) {
            return states.get(sessionId);
        }

        @Override
        public boolean createIfAbsent(SessionState state) {
            return states.putIfAbsent(state.sessionId(), state) == null;
        }

        @Override
        public void save(SessionState state) {
            states.put(state.sessionId(), state);
        }

        @Override
        public long countActiveSessionsByTenantId(String tenantId) {
            return states.values().stream()
                    .filter(state -> state.tenantId().equals(tenantId))
                    .filter(SessionState::isActive)
                    .count();
        }

        @Override
        public List<SessionState> findActiveSessions() {
            return states.values().stream()
                    .filter(SessionState::isActive)
                    .toList();
        }
    }

    private record DelegatingTenantPolicyClient(
            Function<String, Mono<TenantPolicy>> behavior) implements TenantPolicyClient {

        @Override
        public Mono<TenantPolicy> getTenantPolicy(String tenantId) {
            return behavior.apply(tenantId);
        }
    }
}
