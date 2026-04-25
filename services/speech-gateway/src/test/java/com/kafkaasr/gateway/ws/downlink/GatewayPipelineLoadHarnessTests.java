package com.kafkaasr.gateway.ws.downlink;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.flow.GatewayAudioFrameFlowController;
import com.kafkaasr.gateway.flow.GatewayFlowControlProperties;
import com.kafkaasr.gateway.ingress.AudioFrameIngressCommand;
import com.kafkaasr.gateway.ingress.AudioIngressPublisher;
import com.kafkaasr.gateway.ingress.CommandConfirmRequestPublisher;
import com.kafkaasr.gateway.session.SessionControlClient;
import com.kafkaasr.gateway.session.SessionStartCommand;
import com.kafkaasr.gateway.session.SessionStopCommand;
import com.kafkaasr.gateway.ws.GatewayClientPerceivedMetrics;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import com.kafkaasr.gateway.ws.GatewaySessionRegistry;
import com.kafkaasr.gateway.ws.protocol.AudioFrameMessageDecoder;
import com.kafkaasr.gateway.ws.protocol.CommandConfirmMessageDecoder;
import com.kafkaasr.gateway.ws.protocol.GatewayMessageRouter;
import com.kafkaasr.gateway.ws.protocol.SessionPingMessageDecoder;
import com.kafkaasr.gateway.ws.protocol.SessionStartMessageDecoder;
import com.kafkaasr.gateway.ws.protocol.SessionStopMessageDecoder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

class GatewayPipelineLoadHarnessTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void runLoadHarnessAndWriteReport() throws IOException {
        int sessionCount = intProperty("gateway.loadtest.sessions", 24);
        int framesPerSession = intProperty("gateway.loadtest.framesPerSession", 180);
        double minSuccessRatio = doubleProperty("gateway.loadtest.minSuccessRatio", 0.999);
        double maxP95LatencyMs = doubleProperty("gateway.loadtest.maxP95LatencyMs", 1500.0);
        Path reportPath = Path.of(System.getProperty(
                "gateway.loadtest.report",
                "build/reports/loadtest/gateway-pipeline-loadtest.json"));
        Files.createDirectories(reportPath.getParent());

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GatewayClientPerceivedMetrics clientPerceivedMetrics = new GatewayClientPerceivedMetrics(meterRegistry);
        GatewaySessionRegistry sessionRegistry = new GatewaySessionRegistry();
        GatewayDownlinkPublisher downlinkPublisher = new GatewayDownlinkPublisher(
                OBJECT_MAPPER,
                sessionRegistry,
                clientPerceivedMetrics);

        GatewayDownlinkProperties downlinkProperties = new GatewayDownlinkProperties();
        downlinkProperties.setRetryMaxAttempts(3);
        TimedIdempotencyGuard idempotencyGuard = new TimedIdempotencyGuard(downlinkProperties);
        GatewayCompensationPublisher compensationPublisher = mock(GatewayCompensationPublisher.class);

        AsrPartialDownlinkConsumer asrPartialConsumer = new AsrPartialDownlinkConsumer(
                OBJECT_MAPPER,
                downlinkPublisher,
                idempotencyGuard,
                compensationPublisher,
                downlinkProperties,
                meterRegistry);
        TranslationResultDownlinkConsumer translationResultConsumer = new TranslationResultDownlinkConsumer(
                OBJECT_MAPPER,
                downlinkPublisher,
                idempotencyGuard,
                compensationPublisher,
                downlinkProperties,
                meterRegistry);
        SessionControlDownlinkConsumer sessionControlConsumer = new SessionControlDownlinkConsumer(
                OBJECT_MAPPER,
                downlinkPublisher,
                idempotencyGuard,
                compensationPublisher,
                downlinkProperties,
                meterRegistry);

        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        GatewayFlowControlProperties flowControlProperties = new GatewayFlowControlProperties();
        flowControlProperties.setAudioFrameRateLimitPerSecond(200000);
        flowControlProperties.setAudioFrameMaxInflight(1024);

        AudioIngressPublisher ingressPublisher = command -> Mono.empty();
        CommandConfirmRequestPublisher commandConfirmRequestPublisher = command -> Mono.empty();
        SessionControlClient sessionControlClient = new SessionControlClient() {
            @Override
            public Mono<Void> startSession(SessionStartCommand command) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> stopSession(SessionStopCommand command) {
                return Mono.empty();
            }
        };

        GatewayMessageRouter router = new GatewayMessageRouter(
                ingressPublisher,
                commandConfirmRequestPublisher,
                new GatewayAudioFrameFlowController(flowControlProperties),
                new AudioFrameMessageDecoder(OBJECT_MAPPER, validator),
                new SessionStartMessageDecoder(OBJECT_MAPPER, validator),
                new SessionPingMessageDecoder(OBJECT_MAPPER, validator),
                new SessionStopMessageDecoder(OBJECT_MAPPER, validator),
                new CommandConfirmMessageDecoder(OBJECT_MAPPER, validator),
                sessionControlClient,
                clientPerceivedMetrics,
                OBJECT_MAPPER,
                meterRegistry);

        Map<String, GatewaySessionRegistry.ConnectionContext> contextsBySession = new HashMap<>();
        Map<String, Long> nextSeqBySession = new HashMap<>();
        List<Disposable> subscriptions = new ArrayList<>();
        Map<String, LongAdder> outboundTypeCounts = new ConcurrentHashMap<>();
        LongAdder outboundTotal = new LongAdder();

        for (int index = 0; index < sessionCount; index++) {
            String sessionId = "load-sess-" + index;
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("conn-" + sessionId);
            when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

            GatewaySessionRegistry.ConnectionContext context = sessionRegistry.openConnection(session);
            contextsBySession.put(sessionId, context);
            nextSeqBySession.put(sessionId, 1L);
            subscriptions.add(context.outbound().subscribe(payload -> {
                outboundTotal.increment();
                String type = extractType(payload);
                outboundTypeCounts.computeIfAbsent(type, ignored -> new LongAdder()).increment();
            }));

            router.route(sessionStartMessage(sessionId), boundSession ->
                    sessionRegistry.bindSession(context.connectionId(), boundSession)).block();
        }

        List<Long> frameLatencyMs = new ArrayList<>(sessionCount * framesPerSession);
        long totalFrames = (long) sessionCount * framesPerSession;
        long failedFrames = 0L;
        long testStartNs = System.nanoTime();

        for (int frameIndex = 0; frameIndex < framesPerSession; frameIndex++) {
            for (int sessionIndex = 0; sessionIndex < sessionCount; sessionIndex++) {
                String sessionId = "load-sess-" + sessionIndex;
                long seq = nextSeqBySession.get(sessionId);
                GatewaySessionRegistry.ConnectionContext context = contextsBySession.get(sessionId);
                long frameStartNs = System.nanoTime();
                try {
                    router.route(audioFrameMessage(sessionId, seq), boundSession ->
                            sessionRegistry.bindSession(context.connectionId(), boundSession)).block();
                    asrPartialConsumer.onMessage(asrPartialEvent(sessionId, seq));
                    translationResultConsumer.onMessage(translationResultEvent(sessionId, seq));
                } catch (RuntimeException exception) {
                    failedFrames += 1;
                } finally {
                    frameLatencyMs.add((System.nanoTime() - frameStartNs) / 1_000_000L);
                    nextSeqBySession.put(sessionId, seq + 1);
                }
            }
        }

        for (int sessionIndex = 0; sessionIndex < sessionCount; sessionIndex++) {
            String sessionId = "load-sess-" + sessionIndex;
            long seq = nextSeqBySession.get(sessionId);
            sessionControlConsumer.onMessage(sessionClosedEvent(sessionId, seq));
        }

        for (Disposable subscription : subscriptions) {
            subscription.dispose();
        }

        long elapsedNs = System.nanoTime() - testStartNs;
        long succeededFrames = totalFrames - failedFrames;
        double successRatio = totalFrames == 0 ? 1.0 : (double) succeededFrames / (double) totalFrames;
        double throughputFps = elapsedNs == 0
                ? 0.0
                : ((double) succeededFrames) / ((double) elapsedNs / 1_000_000_000.0);

        frameLatencyMs.sort(Comparator.naturalOrder());
        long latencyP50 = percentile(frameLatencyMs, 0.50);
        long latencyP95 = percentile(frameLatencyMs, 0.95);
        long latencyP99 = percentile(frameLatencyMs, 0.99);
        long latencyMax = frameLatencyMs.isEmpty() ? 0L : frameLatencyMs.get(frameLatencyMs.size() - 1);

        Map<String, Object> report = new HashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("sessions", sessionCount);
        report.put("framesPerSession", framesPerSession);
        report.put("totalFrames", totalFrames);
        report.put("successfulFrames", succeededFrames);
        report.put("failedFrames", failedFrames);
        report.put("successRatio", successRatio);
        report.put("elapsedMs", elapsedNs / 1_000_000L);
        report.put("throughputFramesPerSecond", round2(throughputFps));
        report.put("frameLatencyMsP50", latencyP50);
        report.put("frameLatencyMsP95", latencyP95);
        report.put("frameLatencyMsP99", latencyP99);
        report.put("frameLatencyMsMax", latencyMax);
        report.put("subtitlePartialCount", outboundTypeCounts.getOrDefault("subtitle.partial", new LongAdder()).sum());
        report.put("subtitleFinalCount", outboundTypeCounts.getOrDefault("subtitle.final", new LongAdder()).sum());
        report.put("sessionClosedCount", outboundTypeCounts.getOrDefault("session.closed", new LongAdder()).sum());
        report.put("outboundTotalCount", outboundTotal.sum());
        report.put("gatewayWsErrorCount", counterValue(
                meterRegistry,
                "gateway.ws.messages.total",
                "type",
                "audio.frame",
                "result",
                "error",
                "code",
                "INTERNAL_ERROR"));
        report.put("downlinkErrorCount", counterValue(
                meterRegistry,
                "gateway.downlink.messages.total",
                "result",
                "error"));
        report.put("sloChecks", Map.of(
                "minSuccessRatio", minSuccessRatio,
                "maxP95LatencyMs", maxP95LatencyMs,
                "successRatioPass", successRatio >= minSuccessRatio,
                "p95LatencyPass", latencyP95 <= maxP95LatencyMs));

        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);

        if (successRatio < minSuccessRatio) {
            throw new AssertionError(
                    "Loadtest successRatio %.6f is below threshold %.6f".formatted(successRatio, minSuccessRatio));
        }
        if (latencyP95 > maxP95LatencyMs) {
            throw new AssertionError(
                    "Loadtest p95 latency %dms exceeds threshold %.2fms".formatted(latencyP95, maxP95LatencyMs));
        }
    }

    private long counterValue(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        if (counter == null) {
            return 0L;
        }
        return (long) counter.count();
    }

    private String extractType(String payload) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(payload);
            return node.path("type").asText("unknown");
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private static long percentile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        if (index < 0) {
            index = 0;
        } else if (index >= sorted.size()) {
            index = sorted.size() - 1;
        }
        return sorted.get(index);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static int intProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }

    private static double doubleProperty(String key, double defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Double.parseDouble(raw.trim());
    }

    private static String sessionStartMessage(String sessionId) {
        return """
                {
                  "type": "session.start",
                  "sessionId": "%s",
                  "tenantId": "tenant-a",
                  "userId": "user-%s",
                  "sourceLang": "zh-CN",
                  "targetLang": "en-US",
                  "traceId": "trc-%s"
                }
                """.formatted(sessionId, sessionId, sessionId);
    }

    private static String audioFrameMessage(String sessionId, long seq) {
        return """
                {
                  "type": "audio.frame",
                  "sessionId": "%s",
                  "seq": %d,
                  "codec": "pcm16le",
                  "sampleRate": 16000,
                  "audioBase64": "AQID"
                }
                """.formatted(sessionId, seq);
    }

    private static String asrPartialEvent(String sessionId, long seq) {
        return """
                {
                  "eventId": "evt-asr-%d",
                  "eventType": "asr.partial",
                  "eventVersion": "v1",
                  "traceId": "trc-%s",
                  "sessionId": "%s",
                  "tenantId": "tenant-a",
                  "roomId": null,
                  "producer": "asr-worker",
                  "seq": %d,
                  "ts": 1713744001000,
                  "idempotencyKey": "%s:asr.partial:%d",
                  "payload": {
                    "text": "partial-%d",
                    "language": "en-US",
                    "confidence": 0.95,
                    "stable": false
                  }
                }
                """.formatted(seq, sessionId, sessionId, seq, sessionId, seq, seq);
    }

    private static String translationResultEvent(String sessionId, long seq) {
        return """
                {
                  "eventId": "evt-trans-%d",
                  "eventType": "translation.result",
                  "eventVersion": "v1",
                  "traceId": "trc-%s",
                  "sessionId": "%s",
                  "tenantId": "tenant-a",
                  "roomId": null,
                  "producer": "translation-worker",
                  "seq": %d,
                  "ts": 1713744002000,
                  "idempotencyKey": "%s:translation.result:%d",
                  "payload": {
                    "sourceText": "partial-%d",
                    "translatedText": "final-%d",
                    "sourceLang": "zh-CN",
                    "targetLang": "en-US",
                    "engine": "placeholder"
                  }
                }
                """.formatted(seq, sessionId, sessionId, seq, sessionId, seq, seq, seq);
    }

    private static String sessionClosedEvent(String sessionId, long seq) {
        return """
                {
                  "eventId": "evt-ctrl-%d",
                  "eventType": "session.control",
                  "eventVersion": "v1",
                  "traceId": "trc-%s",
                  "sessionId": "%s",
                  "tenantId": "tenant-a",
                  "roomId": null,
                  "producer": "session-orchestrator",
                  "seq": %d,
                  "ts": 1713744003000,
                  "idempotencyKey": "%s:session.control:%d",
                  "payload": {
                    "action": "STOP",
                    "status": "CLOSED",
                    "sourceLang": "zh-CN",
                    "targetLang": "en-US",
                    "reason": "loadtest.done"
                  }
                }
                """.formatted(seq, sessionId, sessionId, seq, sessionId, seq);
    }
}
