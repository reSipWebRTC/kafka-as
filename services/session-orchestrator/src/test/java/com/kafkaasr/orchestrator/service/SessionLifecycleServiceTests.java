package com.kafkaasr.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kafkaasr.orchestrator.api.SessionStartRequest;
import com.kafkaasr.orchestrator.api.SessionStopRequest;
import com.kafkaasr.orchestrator.events.OrchestratorKafkaProperties;
import com.kafkaasr.orchestrator.events.SessionControlEvent;
import com.kafkaasr.orchestrator.events.SessionControlPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SessionLifecycleServiceTests {

    private RecordingPublisher publisher;
    private SessionLifecycleService sessionLifecycleService;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();

        OrchestratorKafkaProperties properties = new OrchestratorKafkaProperties();
        properties.setProducerId("session-orchestrator");
        properties.setSessionControlTopic("session.control");

        sessionLifecycleService = new SessionLifecycleService(
                publisher,
                properties,
                Clock.fixed(Instant.parse("2026-04-21T00:00:00Z"), ZoneOffset.UTC));
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
}
