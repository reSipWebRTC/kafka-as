package com.kafkaasr.gateway.ws;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GatewayClientPerceivedMetrics {

    private static final String METRIC_TOTAL = "gateway.client.perceived.total";
    private static final String METRIC_DURATION = "gateway.client.perceived.duration";

    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<String, SessionTimeline> timelines = new ConcurrentHashMap<>();

    @Autowired
    public GatewayClientPerceivedMetrics(MeterRegistry meterRegistry) {
        this(meterRegistry, Clock.systemUTC());
    }

    GatewayClientPerceivedMetrics(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        meterRegistry.gauge("gateway.client.perceived.sessions.tracked", timelines, Map::size);
    }

    public void onSessionStarted(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        timelines.put(sessionId, new SessionTimeline(nowMs()));
        incrementTotal("session.start", "tracked", "OK");
    }

    public void onFirstSubtitleDelivered(String sessionId) {
        recordStage(sessionId, "first_subtitle", Stage.FIRST_SUBTITLE);
    }

    public void onFinalSubtitleDelivered(String sessionId) {
        recordStage(sessionId, "final_subtitle", Stage.FINAL_SUBTITLE);
    }

    public void onTtsReadyDelivered(String sessionId) {
        recordStage(sessionId, "tts_ready", Stage.TTS_READY);
    }

    public void onSessionClosed(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        SessionTimeline removed = timelines.remove(sessionId);
        incrementTotal("session.closed", removed == null ? "ignored" : "cleared", removed == null ? "SESSION_NOT_TRACKED" : "OK");
    }

    private void recordStage(String sessionId, String stage, Stage marker) {
        if (sessionId == null || sessionId.isBlank()) {
            incrementTotal(stage, "ignored", "INVALID_SESSION");
            return;
        }

        SessionTimeline timeline = timelines.get(sessionId);
        if (timeline == null) {
            incrementTotal(stage, "ignored", "SESSION_NOT_TRACKED");
            return;
        }

        long now = nowMs();
        long startedAt = timeline.startedAtMs;
        if (!timeline.markIfFirst(marker)) {
            incrementTotal(stage, "duplicate", "ALREADY_RECORDED");
            return;
        }

        long durationMs = Math.max(0L, now - startedAt);
        Timer.builder(METRIC_DURATION)
                .tag("stage", stage)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        incrementTotal(stage, "recorded", "OK");
    }

    private void incrementTotal(String stage, String result, String code) {
        meterRegistry.counter(
                        METRIC_TOTAL,
                        "stage",
                        stage,
                        "result",
                        result,
                        "code",
                        code)
                .increment();
    }

    private long nowMs() {
        return Instant.now(clock).toEpochMilli();
    }

    private static final class SessionTimeline {

        private final long startedAtMs;
        private boolean firstSubtitleRecorded;
        private boolean finalSubtitleRecorded;
        private boolean ttsReadyRecorded;

        private SessionTimeline(long startedAtMs) {
            this.startedAtMs = startedAtMs;
        }

        private synchronized boolean markIfFirst(Stage marker) {
            return switch (marker) {
                case FIRST_SUBTITLE -> {
                    if (firstSubtitleRecorded) {
                        yield false;
                    }
                    firstSubtitleRecorded = true;
                    yield true;
                }
                case FINAL_SUBTITLE -> {
                    if (finalSubtitleRecorded) {
                        yield false;
                    }
                    finalSubtitleRecorded = true;
                    yield true;
                }
                case TTS_READY -> {
                    if (ttsReadyRecorded) {
                        yield false;
                    }
                    ttsReadyRecorded = true;
                    yield true;
                }
            };
        }
    }

    private enum Stage {
        FIRST_SUBTITLE,
        FINAL_SUBTITLE,
        TTS_READY
    }
}
