package com.kafkaasr.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.orchestrator.session.SessionProgressMarker;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"orchestrator.kafka.enabled", "orchestrator.session-orchestration.aggregation-enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class SessionProgressConsumer {

    private final ObjectMapper objectMapper;
    private final SessionLifecycleService sessionLifecycleService;
    private final MeterRegistry meterRegistry;

    public SessionProgressConsumer(
            ObjectMapper objectMapper,
            SessionLifecycleService sessionLifecycleService,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.sessionLifecycleService = sessionLifecycleService;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${orchestrator.kafka.asr-partial-topic:asr.partial}",
            groupId = "${ORCHESTRATOR_PROGRESS_CONSUMER_GROUP_ID:session-orchestrator-progress}")
    public void onAsrPartial(String payload) {
        onMessage(payload, SessionProgressMarker.ASR_PARTIAL);
    }

    @KafkaListener(
            topics = "${orchestrator.kafka.asr-final-topic:asr.final}",
            groupId = "${ORCHESTRATOR_PROGRESS_CONSUMER_GROUP_ID:session-orchestrator-progress}")
    public void onAsrFinal(String payload) {
        onMessage(payload, SessionProgressMarker.ASR_FINAL);
    }

    @KafkaListener(
            topics = "${orchestrator.kafka.translation-result-topic:translation.result}",
            groupId = "${ORCHESTRATOR_PROGRESS_CONSUMER_GROUP_ID:session-orchestrator-progress}")
    public void onTranslationResult(String payload) {
        onMessage(payload, SessionProgressMarker.TRANSLATION_RESULT);
    }

    @KafkaListener(
            topics = "${orchestrator.kafka.tts-ready-topic:tts.ready}",
            groupId = "${ORCHESTRATOR_PROGRESS_CONSUMER_GROUP_ID:session-orchestrator-progress}")
    public void onTtsReady(String payload) {
        onMessage(payload, SessionProgressMarker.TTS_READY);
    }

    @KafkaListener(
            topics = "${orchestrator.kafka.command-result-topic:command.result}",
            groupId = "${ORCHESTRATOR_PROGRESS_CONSUMER_GROUP_ID:session-orchestrator-progress}")
    public void onCommandResult(String payload) {
        onMessage(payload, SessionProgressMarker.COMMAND_RESULT);
    }

    private void onMessage(String payload, SessionProgressMarker marker) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            String eventType = event.path("eventType").asText("");
            if (!marker.eventType().equals(eventType)) {
                meterRegistry.counter(
                                "orchestrator.session.progress.consume.total",
                                "event",
                                marker.eventType(),
                                "result",
                                "ignored",
                                "code",
                                "UNSUPPORTED_EVENT")
                        .increment();
                return;
            }

            String sessionId = event.path("sessionId").asText("");
            if (sessionId.isBlank()) {
                meterRegistry.counter(
                                "orchestrator.session.progress.consume.total",
                                "event",
                                marker.eventType(),
                                "result",
                                "error",
                                "code",
                                "INVALID_SESSION")
                        .increment();
                return;
            }

            long eventTs = event.path("ts").asLong(0L);
            SessionLifecycleService.ProgressUpdateResult result =
                    sessionLifecycleService.recordProgress(sessionId, marker, eventTs);
            meterRegistry.counter(
                            "orchestrator.session.progress.consume.total",
                            "event",
                            marker.eventType(),
                            "result",
                            normalizeResult(result),
                            "code",
                            "OK")
                    .increment();
        } catch (JsonProcessingException exception) {
            meterRegistry.counter(
                            "orchestrator.session.progress.consume.total",
                            "event",
                            marker.eventType(),
                            "result",
                            "error",
                            "code",
                            "INVALID_JSON")
                    .increment();
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "orchestrator.session.progress.consume.total",
                            "event",
                            marker.eventType(),
                            "result",
                            "error",
                            "code",
                            "PIPELINE_FAILURE")
                    .increment();
        }
    }

    private String normalizeResult(SessionLifecycleService.ProgressUpdateResult result) {
        return switch (result) {
            case UPDATED -> "updated";
            case SESSION_NOT_FOUND -> "ignored";
            case SESSION_CLOSED -> "ignored";
        };
    }
}
