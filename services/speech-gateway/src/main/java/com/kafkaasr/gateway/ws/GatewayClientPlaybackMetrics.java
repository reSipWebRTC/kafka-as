package com.kafkaasr.gateway.ws;

import com.kafkaasr.gateway.ws.protocol.PlaybackMetricMessage;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GatewayClientPlaybackMetrics {

    private static final String METRIC_TOTAL = "gateway.client.playback.total";
    private static final String METRIC_DURATION = "gateway.client.playback.duration";
    private static final String METRIC_STALL_COUNT = "gateway.client.playback.stall.count";

    private final MeterRegistry meterRegistry;

    public GatewayClientPlaybackMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void onPlaybackMetric(PlaybackMetricMessage request) {
        if (request == null) {
            return;
        }

        String stage = normalizeStage(request.stage());
        String source = normalizeSource(request.source());
        String reason = normalizeReason(request.reason(), stage);

        if (stage.equals("invalid")) {
            incrementTotal("invalid", source, reason, "ignored", "INVALID_STAGE");
            return;
        }
        if (source.equals("invalid")) {
            incrementTotal(stage, "unknown", reason, "ignored", "INVALID_SOURCE");
            return;
        }

        incrementTotal(stage, source, reason, "recorded", "OK");
        if (request.durationMs() != null) {
            Timer.builder(METRIC_DURATION)
                    .tag("stage", stage)
                    .tag("source", source)
                    .register(meterRegistry)
                    .record(Math.max(0L, request.durationMs()), TimeUnit.MILLISECONDS);
        }
        if (request.stallCount() != null) {
            DistributionSummary.builder(METRIC_STALL_COUNT)
                    .tag("source", source)
                    .register(meterRegistry)
                    .record(Math.max(0, request.stallCount()));
        }
    }

    private void incrementTotal(String stage, String source, String reason, String result, String code) {
        meterRegistry.counter(
                        METRIC_TOTAL,
                        "stage",
                        stage,
                        "source",
                        source,
                        "reason",
                        reason,
                        "result",
                        result,
                        "code",
                        code)
                .increment();
    }

    private String normalizeStage(String stage) {
        String normalized = normalizeTagValue(stage);
        return switch (normalized) {
            case "start", "stall", "stall.begin", "stall.end", "complete", "fallback" -> normalized;
            default -> "invalid";
        };
    }

    private String normalizeSource(String source) {
        String normalized = normalizeTagValue(source);
        return switch (normalized) {
            case "remote", "local" -> normalized;
            default -> "invalid";
        };
    }

    private String normalizeReason(String reason, String stage) {
        String normalized = normalizeTagValue(reason);
        if (normalized.equals("unknown")) {
            return switch (stage) {
                case "start", "complete" -> "none";
                case "stall", "stall.begin", "stall.end" -> "buffering";
                default -> "unknown";
            };
        }
        return switch (normalized) {
            case "none",
                    "tts_ready",
                    "buffering",
                    "completed",
                    "tts_ready_timeout",
                    "remote_error",
                    "remote_init_failed",
                    "remote_playback_failed",
                    "gateway_disconnected",
                    "gateway_failure",
                    "session_closed",
                    "manual_stop",
                    "unknown" -> normalized;
            default -> "other";
        };
    }

    private String normalizeTagValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char current = lower.charAt(i);
            if ((current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '-'
                    || current == '_'
                    || current == '.') {
                normalized.append(current);
            } else {
                normalized.append('_');
            }
        }
        if (normalized.isEmpty()) {
            return "unknown";
        }
        return normalized.toString();
    }
}
