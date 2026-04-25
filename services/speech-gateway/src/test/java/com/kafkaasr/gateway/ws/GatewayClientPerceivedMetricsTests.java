package com.kafkaasr.gateway.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class GatewayClientPerceivedMetricsTests {

    @Test
    void recordsFirstEventsOncePerSession() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-25T08:00:00Z"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GatewayClientPerceivedMetrics metrics = new GatewayClientPerceivedMetrics(meterRegistry, clock);

        metrics.onSessionStarted("sess-1");

        clock.advanceSeconds(1);
        metrics.onFirstSubtitleDelivered("sess-1");
        clock.advanceSeconds(2);
        metrics.onFirstSubtitleDelivered("sess-1");
        clock.advanceSeconds(1);
        metrics.onFinalSubtitleDelivered("sess-1");
        clock.advanceSeconds(1);
        metrics.onTtsReadyDelivered("sess-1");

        Timer firstSubtitleTimer = meterRegistry.find("gateway.client.perceived.duration")
                .tag("stage", "first_subtitle")
                .timer();
        Timer finalSubtitleTimer = meterRegistry.find("gateway.client.perceived.duration")
                .tag("stage", "final_subtitle")
                .timer();
        Timer ttsReadyTimer = meterRegistry.find("gateway.client.perceived.duration")
                .tag("stage", "tts_ready")
                .timer();

        assertNotNull(firstSubtitleTimer);
        assertNotNull(finalSubtitleTimer);
        assertNotNull(ttsReadyTimer);
        assertEquals(1L, firstSubtitleTimer.count());
        assertEquals(1L, finalSubtitleTimer.count());
        assertEquals(1L, ttsReadyTimer.count());

        Counter duplicateCounter = meterRegistry.find("gateway.client.perceived.total")
                .tags("stage", "first_subtitle", "result", "duplicate", "code", "ALREADY_RECORDED")
                .counter();
        assertNotNull(duplicateCounter);
        assertEquals(1.0, duplicateCounter.count(), 1e-9);
    }

    @Test
    void clearsSessionOnClosed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-25T08:00:00Z"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GatewayClientPerceivedMetrics metrics = new GatewayClientPerceivedMetrics(meterRegistry, clock);

        metrics.onSessionStarted("sess-2");
        metrics.onSessionClosed("sess-2");
        metrics.onFinalSubtitleDelivered("sess-2");

        Counter ignoredCounter = meterRegistry.find("gateway.client.perceived.total")
                .tags("stage", "final_subtitle", "result", "ignored", "code", "SESSION_NOT_TRACKED")
                .counter();
        assertNotNull(ignoredCounter);
        assertEquals(1.0, ignoredCounter.count(), 1e-9);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }
}
