package com.kafkaasr.gateway.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.kafkaasr.gateway.ws.protocol.PlaybackMetricMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class GatewayClientPlaybackMetricsTests {

    @Test
    void recordsStartStallBeginStallEndAndCompleteMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GatewayClientPlaybackMetrics metrics = new GatewayClientPlaybackMetrics(meterRegistry);

        metrics.onPlaybackMetric(new PlaybackMetricMessage(
                "playback.metric",
                "sess-1",
                8L,
                "start",
                "remote",
                420L,
                null,
                "tts_ready",
                "trc-1"));
        metrics.onPlaybackMetric(new PlaybackMetricMessage(
                "playback.metric",
                "sess-1",
                8L,
                "stall",
                "remote",
                180L,
                null,
                "buffering",
                "trc-1"));
        metrics.onPlaybackMetric(new PlaybackMetricMessage(
                "playback.metric",
                "sess-1",
                8L,
                "stall.begin",
                "remote",
                null,
                null,
                "buffering",
                "trc-1"));
        metrics.onPlaybackMetric(new PlaybackMetricMessage(
                "playback.metric",
                "sess-1",
                8L,
                "stall.end",
                "remote",
                180L,
                null,
                "buffering",
                "trc-1"));
        metrics.onPlaybackMetric(new PlaybackMetricMessage(
                "playback.metric",
                "sess-1",
                8L,
                "complete",
                "remote",
                180L,
                1,
                "completed",
                "trc-1"));

        Timer startTimer = meterRegistry.find("gateway.client.playback.duration")
                .tags("stage", "start", "source", "remote")
                .timer();
        Timer stallEndTimer = meterRegistry.find("gateway.client.playback.duration")
                .tags("stage", "stall.end", "source", "remote")
                .timer();
        DistributionSummary stallCountSummary = meterRegistry.find("gateway.client.playback.stall.count")
                .tags("source", "remote")
                .summary();
        Counter stallBeginCounter = meterRegistry.find("gateway.client.playback.total")
                .tags(
                        "stage", "stall.begin",
                        "source", "remote",
                        "reason", "buffering",
                        "result", "recorded",
                        "code", "OK")
                .counter();
        Counter totalCounter = meterRegistry.find("gateway.client.playback.total")
                .tags(
                        "stage", "start",
                        "source", "remote",
                        "reason", "tts_ready",
                        "result", "recorded",
                        "code", "OK")
                .counter();

        assertNotNull(startTimer);
        assertNotNull(stallEndTimer);
        assertNotNull(stallCountSummary);
        assertNotNull(stallBeginCounter);
        assertNotNull(totalCounter);
        assertEquals(1L, startTimer.count());
        assertEquals(1L, stallEndTimer.count());
        assertEquals(1L, stallCountSummary.count());
        assertEquals(1.0, stallCountSummary.totalAmount(), 1e-9);
        assertEquals(1.0, stallBeginCounter.count(), 1e-9);
        assertEquals(1.0, totalCounter.count(), 1e-9);
    }

    @Test
    void normalizesUnknownReasonToOther() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GatewayClientPlaybackMetrics metrics = new GatewayClientPlaybackMetrics(meterRegistry);

        metrics.onPlaybackMetric(new PlaybackMetricMessage(
                "playback.metric",
                "sess-2",
                9L,
                "fallback",
                "local",
                null,
                null,
                "something-new",
                "trc-2"));

        Counter fallbackCounter = meterRegistry.find("gateway.client.playback.total")
                .tags(
                        "stage", "fallback",
                        "source", "local",
                        "reason", "other",
                        "result", "recorded",
                        "code", "OK")
                .counter();
        assertNotNull(fallbackCounter);
        assertEquals(1.0, fallbackCounter.count(), 1e-9);
    }

    @Test
    void keepsLegacyStallStageCompatible() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GatewayClientPlaybackMetrics metrics = new GatewayClientPlaybackMetrics(meterRegistry);

        metrics.onPlaybackMetric(new PlaybackMetricMessage(
                "playback.metric",
                "sess-3",
                10L,
                "stall",
                "remote",
                120L,
                null,
                "buffering",
                "trc-3"));

        Timer timer = meterRegistry.find("gateway.client.playback.duration")
                .tags("stage", "stall", "source", "remote")
                .timer();
        assertNotNull(timer);
        assertEquals(1L, timer.count());
    }
}
