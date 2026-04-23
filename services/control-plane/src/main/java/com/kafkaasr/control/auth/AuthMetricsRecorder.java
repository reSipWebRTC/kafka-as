package com.kafkaasr.control.auth;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AuthMetricsRecorder {

    private static final String DECISION_TOTAL_METRIC = "controlplane.auth.decision.total";
    private static final String DECISION_DURATION_METRIC = "controlplane.auth.decision.duration";
    private static final String HYBRID_FALLBACK_TOTAL_METRIC = "controlplane.auth.hybrid.fallback.total";
    private static final AuthMetricsRecorder NOOP = new AuthMetricsRecorder(null);

    private final MeterRegistry meterRegistry;

    public AuthMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    static AuthMetricsRecorder noop() {
        return NOOP;
    }

    public void recordDecision(
            String backend,
            ControlPlaneAuthProperties.Mode mode,
            AuthDecision decision,
            long durationNanos) {
        if (meterRegistry == null || decision == null) {
            return;
        }

        String modeTag = modeTagValue(mode);
        String outcomeTag = outcomeTagValue(decision.outcome());
        String reasonTag = normalizeTagValue(decision.reason());

        meterRegistry.counter(
                        DECISION_TOTAL_METRIC,
                        "backend",
                        normalizeTagValue(backend),
                        "mode",
                        modeTag,
                        "outcome",
                        outcomeTag,
                        "reason",
                        reasonTag)
                .increment();
        meterRegistry.timer(
                        DECISION_DURATION_METRIC,
                        "backend",
                        normalizeTagValue(backend),
                        "mode",
                        modeTag,
                        "outcome",
                        outcomeTag)
                .record(Math.max(durationNanos, 0), TimeUnit.NANOSECONDS);
    }

    public void recordHybridFallback(String reason) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(
                        HYBRID_FALLBACK_TOTAL_METRIC,
                        "reason",
                        normalizeTagValue(reason))
                .increment();
    }

    private String modeTagValue(ControlPlaneAuthProperties.Mode mode) {
        if (mode == null) {
            return "static";
        }
        return normalizeTagValue(mode.name());
    }

    private String outcomeTagValue(AuthOutcome outcome) {
        if (outcome == null) {
            return "unknown";
        }
        return normalizeTagValue(outcome.name());
    }

    private String normalizeTagValue(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "unknown";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char current = lower.charAt(i);
            if ((current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '-'
                    || current == '_') {
                normalized.append(current);
                continue;
            }
            normalized.append('_');
        }
        if (normalized.isEmpty()) {
            return "unknown";
        }
        return normalized.toString();
    }
}
