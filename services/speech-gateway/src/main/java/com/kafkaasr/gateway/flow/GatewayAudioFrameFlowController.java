package com.kafkaasr.gateway.flow;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GatewayAudioFrameFlowController {

    private final GatewayFlowControlProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, RateWindow> rateWindowsBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> inflightBySession = new ConcurrentHashMap<>();

    @Autowired
    public GatewayAudioFrameFlowController(GatewayFlowControlProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public GatewayAudioFrameFlowController(GatewayFlowControlProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public FlowDecision acquire(String sessionId) {
        if (!properties.isEnabled() || sessionId == null || sessionId.isBlank()) {
            return FlowDecision.ALLOWED;
        }

        if (isRateLimited(sessionId)) {
            return FlowDecision.RATE_LIMITED;
        }

        if (isBackpressured(sessionId)) {
            return FlowDecision.BACKPRESSURE_DROP;
        }

        return FlowDecision.ALLOWED;
    }

    public void release(String sessionId) {
        if (!properties.isEnabled() || sessionId == null || sessionId.isBlank()) {
            return;
        }

        AtomicInteger inflight = inflightBySession.get(sessionId);
        if (inflight == null) {
            return;
        }

        int remaining = inflight.decrementAndGet();
        if (remaining <= 0) {
            inflightBySession.remove(sessionId, inflight);
        }
    }

    public void reset(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        inflightBySession.remove(sessionId);
        rateWindowsBySession.remove(sessionId);
    }

    private boolean isRateLimited(String sessionId) {
        long nowSecond = Instant.now(clock).getEpochSecond();
        RateWindow updatedWindow = rateWindowsBySession.compute(sessionId, (ignored, existing) -> {
            if (existing == null || existing.second() != nowSecond) {
                return new RateWindow(nowSecond, 1);
            }
            return new RateWindow(nowSecond, existing.count() + 1);
        });
        return updatedWindow.count() > properties.getAudioFrameRateLimitPerSecond();
    }

    private boolean isBackpressured(String sessionId) {
        AtomicInteger inflight = inflightBySession.computeIfAbsent(sessionId, ignored -> new AtomicInteger());
        int current = inflight.incrementAndGet();
        if (current <= properties.getAudioFrameMaxInflight()) {
            return false;
        }

        int remaining = inflight.decrementAndGet();
        if (remaining <= 0) {
            inflightBySession.remove(sessionId, inflight);
        }
        return true;
    }

    public enum FlowDecision {
        ALLOWED,
        RATE_LIMITED,
        BACKPRESSURE_DROP
    }

    private record RateWindow(long second, int count) {
    }
}
