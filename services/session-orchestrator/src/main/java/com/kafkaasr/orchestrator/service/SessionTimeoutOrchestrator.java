package com.kafkaasr.orchestrator.service;

import com.kafkaasr.orchestrator.api.SessionStopRequest;
import com.kafkaasr.orchestrator.api.SessionStopResponse;
import com.kafkaasr.orchestrator.events.SessionCompensationPublisher;
import com.kafkaasr.orchestrator.session.SessionState;
import com.kafkaasr.orchestrator.session.SessionStateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "orchestrator.session-orchestration.enabled", havingValue = "true", matchIfMissing = true)
public class SessionTimeoutOrchestrator {

    private final SessionStateRepository sessionStateRepository;
    private final SessionLifecycleService sessionLifecycleService;
    private final SessionCompensationPublisher compensationPublisher;
    private final SessionOrchestrationProperties orchestrationProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public SessionTimeoutOrchestrator(
            SessionStateRepository sessionStateRepository,
            SessionLifecycleService sessionLifecycleService,
            SessionCompensationPublisher compensationPublisher,
            SessionOrchestrationProperties orchestrationProperties,
            MeterRegistry meterRegistry) {
        this(
                sessionStateRepository,
                sessionLifecycleService,
                compensationPublisher,
                orchestrationProperties,
                meterRegistry,
                Clock.systemUTC());
    }

    SessionTimeoutOrchestrator(
            SessionStateRepository sessionStateRepository,
            SessionLifecycleService sessionLifecycleService,
            SessionCompensationPublisher compensationPublisher,
            SessionOrchestrationProperties orchestrationProperties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.sessionStateRepository = sessionStateRepository;
        this.sessionLifecycleService = sessionLifecycleService;
        this.compensationPublisher = compensationPublisher;
        this.orchestrationProperties = orchestrationProperties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "#{@sessionOrchestrationProperties.scanInterval.toMillis()}")
    public void scanTimeoutSessions() {
        long now = nowMs();
        List<SessionState> activeSessions = sessionStateRepository.findActiveSessions();
        for (SessionState state : activeSessions) {
            TimeoutDecision decision = evaluateTimeout(state, now);
            if (decision == TimeoutDecision.NONE) {
                continue;
            }
            closeTimedOutSession(state, decision);
        }
    }

    private void closeTimedOutSession(SessionState state, TimeoutDecision decision) {
        String reason = decision == TimeoutDecision.HARD
                ? orchestrationProperties.getHardTimeoutReason()
                : orchestrationProperties.getIdleTimeoutReason();

        try {
            SessionStopResponse response = sessionLifecycleService
                    .stopSession(state.sessionId(), new SessionStopRequest(state.traceId(), reason))
                    .block(orchestrationProperties.getCloseTimeout());
            boolean stopped = response != null && response.stopped();
            meterRegistry.counter(
                            "orchestrator.session.timeout.total",
                            "type",
                            decision.metricTag,
                            "result",
                            stopped ? "closed" : "idempotent",
                            "code",
                            "OK")
                    .increment();
            compensationPublisher.publishTimeoutClose(
                    decision.metricTag,
                    stopped ? "closed" : "idempotent",
                    state,
                    null);
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "orchestrator.session.timeout.total",
                            "type",
                            decision.metricTag,
                            "result",
                            "error",
                            "code",
                            normalizeTimeoutError(exception))
                    .increment();
            compensationPublisher.publishTimeoutClose(
                    decision.metricTag,
                    "error",
                    state,
                    exception);
        }
    }

    private TimeoutDecision evaluateTimeout(SessionState state, long nowMs) {
        Duration hardTimeout = orchestrationProperties.getHardTimeout();
        if (!hardTimeout.isZero()
                && !hardTimeout.isNegative()
                && nowMs - state.startedAtMs() >= hardTimeout.toMillis()) {
            return TimeoutDecision.HARD;
        }

        Duration idleTimeout = orchestrationProperties.getIdleTimeout();
        if (!idleTimeout.isZero()
                && !idleTimeout.isNegative()
                && nowMs - state.updatedAtMs() >= idleTimeout.toMillis()) {
            return TimeoutDecision.IDLE;
        }
        return TimeoutDecision.NONE;
    }

    private String normalizeTimeoutError(Throwable throwable) {
        if (throwable instanceof SessionControlException sessionControlException) {
            return sessionControlException.code();
        }
        return "TIMEOUT_CLOSE_FAILED";
    }

    private long nowMs() {
        return Instant.now(clock).toEpochMilli();
    }

    private enum TimeoutDecision {
        NONE("none"),
        IDLE("idle"),
        HARD("hard");

        private final String metricTag;

        TimeoutDecision(String metricTag) {
            this.metricTag = metricTag;
        }
    }
}
