package com.kafkaasr.orchestrator.service;

import com.kafkaasr.orchestrator.api.SessionStartRequest;
import com.kafkaasr.orchestrator.api.SessionStartResponse;
import com.kafkaasr.orchestrator.api.SessionStopRequest;
import com.kafkaasr.orchestrator.api.SessionStopResponse;
import com.kafkaasr.orchestrator.events.OrchestratorKafkaProperties;
import com.kafkaasr.orchestrator.events.SessionControlEvent;
import com.kafkaasr.orchestrator.events.SessionControlPayload;
import com.kafkaasr.orchestrator.events.SessionControlPublisher;
import com.kafkaasr.orchestrator.session.SessionState;
import com.kafkaasr.orchestrator.session.SessionStateRepository;
import com.kafkaasr.orchestrator.session.SessionStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SessionLifecycleService {

    private static final String EVENT_TYPE = "session.control";
    private static final String EVENT_VERSION = "v1";
    private static final String START_ACTION = "START";
    private static final String STOP_ACTION = "STOP";

    private final SessionControlPublisher sessionControlPublisher;
    private final OrchestratorKafkaProperties kafkaProperties;
    private final SessionStateRepository sessionStateRepository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    @Autowired
    public SessionLifecycleService(
            SessionControlPublisher sessionControlPublisher,
            OrchestratorKafkaProperties kafkaProperties,
            SessionStateRepository sessionStateRepository,
            MeterRegistry meterRegistry) {
        this(sessionControlPublisher, kafkaProperties, sessionStateRepository, Clock.systemUTC(), meterRegistry);
    }

    SessionLifecycleService(
            SessionControlPublisher sessionControlPublisher,
            OrchestratorKafkaProperties kafkaProperties,
            SessionStateRepository sessionStateRepository,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.sessionControlPublisher = sessionControlPublisher;
        this.kafkaProperties = kafkaProperties;
        this.sessionStateRepository = sessionStateRepository;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public Mono<SessionStartResponse> startSession(SessionStartRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            StartResult result;
            SessionState current = sessionStateRepository.findBySessionId(request.sessionId());
            if (current == null) {
                StartResult created = createSession(request);
                if (sessionStateRepository.createIfAbsent(created.state())) {
                    result = created;
                } else {
                    SessionState existing = sessionStateRepository.findBySessionId(request.sessionId());
                    if (existing == null) {
                        throw new IllegalStateException("Session state lost during start race for " + request.sessionId());
                    }
                    result = handleRepeatedStart(request, existing);
                }
            } else {
                result = handleRepeatedStart(request, current);
            }

            Mono<Void> publish = result.event() == null
                    ? Mono.empty()
                    : sessionControlPublisher.publish(result.event());
            return publish.thenReturn(result.response())
                    .doOnSuccess(response -> meterRegistry.counter(
                                    "orchestrator.session.start.total",
                                    "result",
                                    response.created() ? "created" : "idempotent",
                                    "code",
                                    "OK")
                            .increment())
                    .doOnError(exception -> meterRegistry.counter(
                                    "orchestrator.session.start.total",
                                    "result",
                                    "error",
                                    "code",
                                    normalizeErrorCode(exception))
                            .increment())
                    .doFinally(signalType -> sample.stop(meterRegistry.timer("orchestrator.session.start.duration")));
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "orchestrator.session.start.total",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            sample.stop(meterRegistry.timer("orchestrator.session.start.duration"));
            throw exception;
        }
    }

    public Mono<SessionStopResponse> stopSession(String sessionId, SessionStopRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SessionStopRequest normalizedRequest = request == null ? SessionStopRequest.empty() : request;

            StopResult result;
            SessionState current = sessionStateRepository.findBySessionId(sessionId);
            if (current == null) {
                throw SessionControlException.sessionNotFound(sessionId);
            }

            if (current.status() == SessionStatus.CLOSED) {
                result = alreadyStopped(current, normalizedRequest);
            } else {
                result = closeSession(current, normalizedRequest);
                sessionStateRepository.save(result.state());
            }

            Mono<Void> publish = result.event() == null
                    ? Mono.empty()
                    : sessionControlPublisher.publish(result.event());
            return publish.thenReturn(result.response())
                    .doOnSuccess(response -> meterRegistry.counter(
                                    "orchestrator.session.stop.total",
                                    "result",
                                    response.stopped() ? "stopped" : "idempotent",
                                    "code",
                                    "OK")
                            .increment())
                    .doOnError(exception -> meterRegistry.counter(
                                    "orchestrator.session.stop.total",
                                    "result",
                                    "error",
                                    "code",
                                    normalizeErrorCode(exception))
                            .increment())
                    .doFinally(signalType -> sample.stop(meterRegistry.timer("orchestrator.session.stop.duration")));
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "orchestrator.session.stop.total",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            sample.stop(meterRegistry.timer("orchestrator.session.stop.duration"));
            throw exception;
        }
    }

    private StartResult createSession(SessionStartRequest request) {
        long now = nowMs();
        String traceId = coalesce(request.traceId(), prefixedId("trc"));
        SessionState state = new SessionState(
                request.sessionId(),
                request.tenantId(),
                request.sourceLang(),
                request.targetLang(),
                traceId,
                SessionStatus.STREAMING,
                1L,
                now,
                now);

        SessionControlEvent event = toEvent(state, START_ACTION, null);
        SessionStartResponse response = new SessionStartResponse(
                state.sessionId(),
                state.traceId(),
                state.status().name(),
                true,
                state.lastSeq(),
                state.startedAtMs());
        return new StartResult(state, response, event);
    }

    private StartResult handleRepeatedStart(SessionStartRequest request, SessionState current) {
        if (current.status() == SessionStatus.CLOSED) {
            throw SessionControlException.conflict(
                    "INVALID_MESSAGE",
                    "Session is already closed and cannot be restarted",
                    current.sessionId());
        }

        if (!current.tenantId().equals(request.tenantId())
                || !current.sourceLang().equals(request.sourceLang())
                || !current.targetLang().equals(request.targetLang())) {
            throw SessionControlException.invalidMessage(
                    "Session metadata mismatch for repeated start",
                    current.sessionId());
        }

        SessionStartResponse response = new SessionStartResponse(
                current.sessionId(),
                current.traceId(),
                current.status().name(),
                false,
                current.lastSeq(),
                current.startedAtMs());
        return new StartResult(current, response, null);
    }

    private StopResult closeSession(SessionState current, SessionStopRequest request) {
        long now = nowMs();
        long nextSeq = current.lastSeq() + 1;
        String traceId = coalesce(request.traceId(), current.traceId(), prefixedId("trc"));
        SessionState closed = current.withState(SessionStatus.CLOSED, traceId, nextSeq, now);

        String reason = coalesce(request.reason(), "client.stop");
        SessionControlEvent event = toEvent(closed, STOP_ACTION, reason);
        SessionStopResponse response = new SessionStopResponse(
                closed.sessionId(),
                closed.traceId(),
                closed.status().name(),
                true,
                closed.lastSeq(),
                reason,
                closed.updatedAtMs());
        return new StopResult(closed, response, event);
    }

    private StopResult alreadyStopped(SessionState current, SessionStopRequest request) {
        String traceId = coalesce(request.traceId(), current.traceId(), prefixedId("trc"));
        SessionState materialized = current.withState(
                SessionStatus.CLOSED,
                traceId,
                current.lastSeq(),
                current.updatedAtMs());

        SessionStopResponse response = new SessionStopResponse(
                materialized.sessionId(),
                materialized.traceId(),
                materialized.status().name(),
                false,
                materialized.lastSeq(),
                coalesce(request.reason(), "already.closed"),
                materialized.updatedAtMs());
        return new StopResult(materialized, response, null);
    }

    private SessionControlEvent toEvent(SessionState state, String action, String reason) {
        return new SessionControlEvent(
                prefixedId("evt"),
                EVENT_TYPE,
                EVENT_VERSION,
                state.traceId(),
                state.sessionId(),
                state.tenantId(),
                null,
                kafkaProperties.getProducerId(),
                state.lastSeq(),
                nowMs(),
                state.sessionId() + ":" + EVENT_TYPE + ":" + state.lastSeq(),
                new SessionControlPayload(
                        action,
                        state.status().name(),
                        state.sourceLang(),
                        state.targetLang(),
                        reason));
    }

    private long nowMs() {
        return Instant.now(clock).toEpochMilli();
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String coalesce(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof SessionControlException exception) {
            return exception.code();
        }
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_MESSAGE";
        }
        return "INTERNAL_ERROR";
    }

    private record StartResult(SessionState state, SessionStartResponse response, SessionControlEvent event) {
    }

    private record StopResult(SessionState state, SessionStopResponse response, SessionControlEvent event) {
    }
}
