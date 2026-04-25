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
            TimeoutDecision timeoutDecision = evaluateTimeout(state, now);
            if (timeoutDecision != TimeoutDecision.NONE) {
                closeTimedOutSession(state, timeoutDecision);
                continue;
            }

            StalledDecision stalledDecision = evaluateStalled(state, now);
            if (stalledDecision != StalledDecision.NONE) {
                closeStalledSession(state, stalledDecision);
            }
        }
    }

    private void closeTimedOutSession(SessionState state, TimeoutDecision decision) {
        String reason = decision == TimeoutDecision.HARD
                ? orchestrationProperties.getHardTimeoutReason()
                : orchestrationProperties.getIdleTimeoutReason();
        closeSession(
                state,
                reason,
                "orchestrator.session.timeout.total",
                "type",
                decision.metricTag,
                (outcome, targetState, failure) -> compensationPublisher.publishTimeoutClose(
                        decision.metricTag,
                        outcome,
                        targetState,
                        failure));
    }

    private void closeStalledSession(SessionState state, StalledDecision decision) {
        closeSession(
                state,
                stalledReason(decision),
                "orchestrator.session.stalled.total",
                "stage",
                decision.metricTag,
                (outcome, targetState, failure) -> compensationPublisher.publishStalledClose(
                        decision.metricTag,
                        outcome,
                        targetState,
                        failure));
    }

    private void closeSession(
            SessionState state,
            String reason,
            String metricName,
            String metricTagName,
            String metricTagValue,
            CompensationEmit emitCompensation) {
        try {
            SessionStopResponse response = sessionLifecycleService
                    .stopSession(state.sessionId(), new SessionStopRequest(state.traceId(), reason))
                    .block(orchestrationProperties.getCloseTimeout());
            boolean stopped = response != null && response.stopped();
            String outcome = stopped ? "closed" : "idempotent";
            incrementCounter(metricName, metricTagName, metricTagValue, outcome, "OK");
            emitCompensation.publish(outcome, state, null);
        } catch (RuntimeException exception) {
            incrementCounter(metricName, metricTagName, metricTagValue, "error", normalizeTimeoutError(exception));
            emitCompensation.publish("error", state, exception);
        }
    }

    private TimeoutDecision evaluateTimeout(SessionState state, long nowMs) {
        Duration hardTimeout = orchestrationProperties.getHardTimeout();
        if (isEnabled(hardTimeout) && nowMs - state.startedAtMs() >= hardTimeout.toMillis()) {
            return TimeoutDecision.HARD;
        }

        Duration idleTimeout = orchestrationProperties.getIdleTimeout();
        if (isEnabled(idleTimeout) && nowMs - state.updatedAtMs() >= idleTimeout.toMillis()) {
            return TimeoutDecision.IDLE;
        }
        return TimeoutDecision.NONE;
    }

    private StalledDecision evaluateStalled(SessionState state, long nowMs) {
        Duration stalledTimeout = orchestrationProperties.getStalledTimeout();
        if (!isEnabled(stalledTimeout)) {
            return StalledDecision.NONE;
        }

        long thresholdMs = stalledTimeout.toMillis();
        long lastFinalAtMs = state.lastFinalAtMs();
        if (lastFinalAtMs > 0) {
            long downstreamProgressAtMs = max3(
                    state.lastTranslationAtMs(),
                    state.lastCommandResultAtMs(),
                    state.lastTtsReadyAtMs());
            if (downstreamProgressAtMs < lastFinalAtMs && nowMs - lastFinalAtMs >= thresholdMs) {
                return StalledDecision.POST_FINAL;
            }
        }

        long lastTranslationAtMs = state.lastTranslationAtMs();
        if (lastTranslationAtMs > 0
                && state.lastTtsReadyAtMs() < lastTranslationAtMs
                && nowMs - lastTranslationAtMs >= thresholdMs) {
            return StalledDecision.POST_TRANSLATION;
        }

        return StalledDecision.NONE;
    }

    private String stalledReason(StalledDecision decision) {
        String prefix = orchestrationProperties.getStalledTimeoutReasonPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "timeout.stalled";
        }
        return prefix + "." + decision.reasonSuffix;
    }

    private boolean isEnabled(Duration timeout) {
        return !timeout.isZero() && !timeout.isNegative();
    }

    private long max3(long first, long second, long third) {
        return Math.max(first, Math.max(second, third));
    }

    private void incrementCounter(
            String metricName,
            String metricTagName,
            String metricTagValue,
            String result,
            String code) {
        meterRegistry.counter(
                        metricName,
                        metricTagName,
                        metricTagValue,
                        "result",
                        result,
                        "code",
                        code)
                .increment();
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

    private enum StalledDecision {
        NONE("none", "none"),
        POST_FINAL("post_final", "post_final"),
        POST_TRANSLATION("post_translation", "post_translation");

        private final String metricTag;
        private final String reasonSuffix;

        StalledDecision(String metricTag, String reasonSuffix) {
            this.metricTag = metricTag;
            this.reasonSuffix = reasonSuffix;
        }
    }

    @FunctionalInterface
    private interface CompensationEmit {
        void publish(String outcome, SessionState state, Throwable failure);
    }
}
