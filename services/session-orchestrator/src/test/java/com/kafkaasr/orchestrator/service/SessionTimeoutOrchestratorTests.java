package com.kafkaasr.orchestrator.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kafkaasr.orchestrator.api.SessionStopRequest;
import com.kafkaasr.orchestrator.api.SessionStopResponse;
import com.kafkaasr.orchestrator.events.SessionCompensationPublisher;
import com.kafkaasr.orchestrator.session.SessionState;
import com.kafkaasr.orchestrator.session.SessionStateRepository;
import com.kafkaasr.orchestrator.session.SessionStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class SessionTimeoutOrchestratorTests {

    private static final long NOW_MS = Instant.parse("2026-04-25T08:00:00Z").toEpochMilli();

    @Mock
    private SessionStateRepository sessionStateRepository;

    @Mock
    private SessionLifecycleService sessionLifecycleService;

    @Mock
    private SessionCompensationPublisher compensationPublisher;

    private SessionOrchestrationProperties properties;
    private SessionTimeoutOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        properties = new SessionOrchestrationProperties();
        properties.setIdleTimeout(Duration.ofSeconds(30));
        properties.setHardTimeout(Duration.ofMinutes(10));
        properties.setCloseTimeout(Duration.ofSeconds(1));

        orchestrator = new SessionTimeoutOrchestrator(
                sessionStateRepository,
                sessionLifecycleService,
                compensationPublisher,
                properties,
                new SimpleMeterRegistry(),
                Clock.fixed(Instant.ofEpochMilli(NOW_MS), ZoneOffset.UTC));
    }

    @Test
    void closesIdleTimedOutSession() {
        SessionState active = new SessionState(
                "sess-idle",
                "tenant-a",
                "zh-CN",
                "en-US",
                "trc-1",
                SessionStatus.STREAMING,
                1L,
                NOW_MS - Duration.ofMinutes(5).toMillis(),
                NOW_MS - Duration.ofSeconds(31).toMillis(),
                0L,
                0L,
                0L,
                0L,
                0L,
                "");
        when(sessionStateRepository.findActiveSessions()).thenReturn(List.of(active));
        when(sessionLifecycleService.stopSession(eq("sess-idle"), any(SessionStopRequest.class)))
                .thenReturn(Mono.just(new SessionStopResponse(
                        "sess-idle",
                        "trc-1",
                        "CLOSED",
                        true,
                        2L,
                        "timeout.idle",
                        NOW_MS)));

        orchestrator.scanTimeoutSessions();

        ArgumentCaptor<SessionStopRequest> requestCaptor = ArgumentCaptor.forClass(SessionStopRequest.class);
        verify(sessionLifecycleService).stopSession(eq("sess-idle"), requestCaptor.capture());
        verify(compensationPublisher).publishTimeoutClose(eq("idle"), eq("closed"), eq(active), eq(null));
        SessionStopRequest request = requestCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("timeout.idle", request.reason());
    }

    @Test
    void hardTimeoutTakesPriorityOverIdleTimeout() {
        SessionState active = new SessionState(
                "sess-hard",
                "tenant-a",
                "zh-CN",
                "en-US",
                "trc-2",
                SessionStatus.STREAMING,
                1L,
                NOW_MS - Duration.ofMinutes(11).toMillis(),
                NOW_MS - Duration.ofSeconds(5).toMillis(),
                0L,
                0L,
                0L,
                0L,
                0L,
                "");
        when(sessionStateRepository.findActiveSessions()).thenReturn(List.of(active));
        when(sessionLifecycleService.stopSession(eq("sess-hard"), any(SessionStopRequest.class)))
                .thenReturn(Mono.just(new SessionStopResponse(
                        "sess-hard",
                        "trc-2",
                        "CLOSED",
                        true,
                        2L,
                        "timeout.hard",
                        NOW_MS)));

        orchestrator.scanTimeoutSessions();

        ArgumentCaptor<SessionStopRequest> requestCaptor = ArgumentCaptor.forClass(SessionStopRequest.class);
        verify(sessionLifecycleService).stopSession(eq("sess-hard"), requestCaptor.capture());
        verify(compensationPublisher).publishTimeoutClose(eq("hard"), eq("closed"), eq(active), eq(null));
        org.junit.jupiter.api.Assertions.assertEquals("timeout.hard", requestCaptor.getValue().reason());
    }

    @Test
    void publishesCompensationErrorWhenCloseFails() {
        SessionState active = new SessionState(
                "sess-fail",
                "tenant-a",
                "zh-CN",
                "en-US",
                "trc-3",
                SessionStatus.STREAMING,
                1L,
                NOW_MS - Duration.ofMinutes(5).toMillis(),
                NOW_MS - Duration.ofSeconds(35).toMillis(),
                0L,
                0L,
                0L,
                0L,
                0L,
                "");
        when(sessionStateRepository.findActiveSessions()).thenReturn(List.of(active));
        when(sessionLifecycleService.stopSession(eq("sess-fail"), any(SessionStopRequest.class)))
                .thenThrow(SessionControlException.internalError("close failed", "sess-fail"));

        orchestrator.scanTimeoutSessions();

        verify(compensationPublisher).publishTimeoutClose(eq("idle"), eq("error"), eq(active), any(SessionControlException.class));
    }
}
