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
        properties.setStalledTimeout(Duration.ofSeconds(20));
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

    @Test
    void closesStalledPostFinalSession() {
        SessionState active = new SessionState(
                "sess-stalled-final",
                "tenant-a",
                "zh-CN",
                "en-US",
                "trc-4",
                SessionStatus.ASR_ACTIVE,
                3L,
                NOW_MS - Duration.ofMinutes(2).toMillis(),
                NOW_MS - Duration.ofSeconds(5).toMillis(),
                NOW_MS - Duration.ofSeconds(7).toMillis(),
                NOW_MS - Duration.ofSeconds(25).toMillis(),
                0L,
                0L,
                0L,
                "");
        when(sessionStateRepository.findActiveSessions()).thenReturn(List.of(active));
        when(sessionLifecycleService.stopSession(eq("sess-stalled-final"), any(SessionStopRequest.class)))
                .thenReturn(Mono.just(new SessionStopResponse(
                        "sess-stalled-final",
                        "trc-4",
                        "CLOSED",
                        true,
                        4L,
                        "timeout.stalled.post_final",
                        NOW_MS)));

        orchestrator.scanTimeoutSessions();

        ArgumentCaptor<SessionStopRequest> requestCaptor = ArgumentCaptor.forClass(SessionStopRequest.class);
        verify(sessionLifecycleService).stopSession(eq("sess-stalled-final"), requestCaptor.capture());
        verify(compensationPublisher).publishStalledClose(eq("post_final"), eq("closed"), eq(active), eq(null));
        org.junit.jupiter.api.Assertions.assertEquals("timeout.stalled.post_final", requestCaptor.getValue().reason());
    }

    @Test
    void closesStalledPostTranslationSession() {
        SessionState active = new SessionState(
                "sess-stalled-translation",
                "tenant-a",
                "zh-CN",
                "en-US",
                "trc-5",
                SessionStatus.TRANSLATING,
                7L,
                NOW_MS - Duration.ofMinutes(3).toMillis(),
                NOW_MS - Duration.ofSeconds(4).toMillis(),
                NOW_MS - Duration.ofSeconds(35).toMillis(),
                NOW_MS - Duration.ofSeconds(30).toMillis(),
                NOW_MS - Duration.ofSeconds(25).toMillis(),
                0L,
                0L,
                "");
        when(sessionStateRepository.findActiveSessions()).thenReturn(List.of(active));
        when(sessionLifecycleService.stopSession(eq("sess-stalled-translation"), any(SessionStopRequest.class)))
                .thenReturn(Mono.just(new SessionStopResponse(
                        "sess-stalled-translation",
                        "trc-5",
                        "CLOSED",
                        true,
                        8L,
                        "timeout.stalled.post_translation",
                        NOW_MS)));

        orchestrator.scanTimeoutSessions();

        ArgumentCaptor<SessionStopRequest> requestCaptor = ArgumentCaptor.forClass(SessionStopRequest.class);
        verify(sessionLifecycleService).stopSession(eq("sess-stalled-translation"), requestCaptor.capture());
        verify(compensationPublisher).publishStalledClose(eq("post_translation"), eq("closed"), eq(active), eq(null));
        org.junit.jupiter.api.Assertions.assertEquals("timeout.stalled.post_translation", requestCaptor.getValue().reason());
    }

    @Test
    void publishesStalledCompensationErrorWhenCloseFails() {
        SessionState active = new SessionState(
                "sess-stalled-fail",
                "tenant-a",
                "zh-CN",
                "en-US",
                "trc-6",
                SessionStatus.ASR_ACTIVE,
                5L,
                NOW_MS - Duration.ofMinutes(2).toMillis(),
                NOW_MS - Duration.ofSeconds(2).toMillis(),
                NOW_MS - Duration.ofSeconds(5).toMillis(),
                NOW_MS - Duration.ofSeconds(25).toMillis(),
                0L,
                0L,
                0L,
                "");
        when(sessionStateRepository.findActiveSessions()).thenReturn(List.of(active));
        when(sessionLifecycleService.stopSession(eq("sess-stalled-fail"), any(SessionStopRequest.class)))
                .thenThrow(SessionControlException.internalError("close stalled failed", "sess-stalled-fail"));

        orchestrator.scanTimeoutSessions();

        verify(compensationPublisher)
                .publishStalledClose(eq("post_final"), eq("error"), eq(active), any(SessionControlException.class));
    }
}
