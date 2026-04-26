package com.kafkaasr.command.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CommandPipelineMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public CommandPipelineMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordDispatch(String source, int confirmRound) {
        meterRegistry.counter(
                        "command.pipeline.dispatch.total",
                        "source",
                        normalizeTag(source),
                        "confirmRound",
                        Integer.toString(Math.max(1, confirmRound)))
                .increment();
    }

    public void recordResult(String status, String code) {
        meterRegistry.counter(
                        "command.pipeline.result.total",
                        "status",
                        normalizeTag(status),
                        "outcome",
                        resolveOutcome(status, code))
                .increment();
    }

    public void recordE2eLatency(String status, String code, long startedAtEpochMs, long completedAtEpochMs) {
        if (startedAtEpochMs <= 0 || completedAtEpochMs <= 0) {
            return;
        }
        long latencyMs = Math.max(0L, completedAtEpochMs - startedAtEpochMs);
        Timer.builder("command.pipeline.e2e.duration")
                .tags(
                        "status",
                        normalizeTag(status),
                        "outcome",
                        resolveOutcome(status, code))
                .register(meterRegistry)
                .record(Duration.ofMillis(latencyMs));
    }

    private String resolveOutcome(String status, String code) {
        String normalizedStatus = normalizeTag(status);
        String normalizedCode = normalizeTag(code);
        if ("command_confirm_timeout".equals(normalizedCode)) {
            return "confirm_timeout";
        }
        if ("failed".equals(normalizedStatus) || "timeout".equals(normalizedStatus)) {
            return "execution_failed";
        }
        if ("ok".equals(normalizedStatus)) {
            return "success";
        }
        if ("cancelled".equals(normalizedStatus)) {
            return "cancelled";
        }
        return normalizedStatus;
    }

    private String normalizeTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }
}
