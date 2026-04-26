package com.kafkaasr.orchestrator.compensation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.orchestrator.api.SessionStopRequest;
import com.kafkaasr.orchestrator.api.SessionStopResponse;
import com.kafkaasr.orchestrator.events.OrchestratorKafkaProperties;
import com.kafkaasr.orchestrator.service.SessionLifecycleService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class PlatformCompensationSagaConsumerTests {

    @Mock
    private CompensationSagaStateRepository stateRepository;

    @Mock
    private SessionLifecycleService sessionLifecycleService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private PlatformCompensationSagaConsumer consumer;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = org.mockito.Mockito.mock(SendResult.class);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        OrchestratorKafkaProperties kafkaProperties = new OrchestratorKafkaProperties();
        kafkaProperties.setAuditTopic("platform.audit");

        CompensationSagaProperties sagaProperties = new CompensationSagaProperties();
        sagaProperties.setReplaySendTimeout(java.time.Duration.ofSeconds(1));
        sagaProperties.setSessionCloseTimeout(java.time.Duration.ofSeconds(1));
        sagaProperties.setRetryBackoff(java.time.Duration.ZERO);
        sagaProperties.setMaxAttempts(2);

        consumer = new PlatformCompensationSagaConsumer(
                new ObjectMapper(),
                stateRepository,
                sessionLifecycleService,
                kafkaTemplate,
                kafkaProperties,
                sagaProperties,
                new SimpleMeterRegistry());
    }

    @Test
    void replaysProcessingErrorAndPublishesAudit() {
        when(stateRepository.begin("evt-replay", "REPLAY")).thenReturn(true);

        consumer.onPlatformDlq("""
                {
                  "eventId": "evt-replay",
                  "eventType": "platform.dlq",
                  "eventVersion": "v1",
                  "traceId": "trc-replay",
                  "tenantId": "tenant-a",
                  "payload": {
                    "service": "asr-worker",
                    "sourceTopic": "audio.ingress.raw",
                    "dlqTopic": "audio.ingress.raw.dlq",
                    "reason": "PROCESSING_ERROR",
                    "rawPayload": "{\\"sessionId\\":\\"sess-replay\\",\\"tenantId\\":\\"tenant-a\\"}"
                  }
                }
                """);

        verify(kafkaTemplate).send(eq("audio.ingress.raw"), eq("sess-replay"), anyString());
        verify(kafkaTemplate).send(eq("platform.audit"), eq("tenant-a"), anyString());
        verify(stateRepository).markSucceeded("evt-replay", "REPLAY", "REPLAY_OK");
        verify(sessionLifecycleService, never()).stopSession(anyString(), any(SessionStopRequest.class));
    }

    @Test
    void closesSessionForDownlinkProcessingError() {
        when(stateRepository.begin("evt-close", "SESSION_CLOSE")).thenReturn(true);
        when(sessionLifecycleService.stopSession(eq("sess-close"), any(SessionStopRequest.class)))
                .thenReturn(Mono.just(new SessionStopResponse(
                        "sess-close",
                        "trc-close",
                        "CLOSED",
                        true,
                        2L,
                        "compensation.saga.tts_ready.processing_error",
                        1710000000000L)));

        consumer.onPlatformDlq("""
                {
                  "eventId": "evt-close",
                  "eventType": "platform.dlq",
                  "eventVersion": "v1",
                  "traceId": "trc-close",
                  "tenantId": "tenant-a",
                  "payload": {
                    "service": "speech-gateway",
                    "sourceTopic": "tts.ready",
                    "dlqTopic": "tts.ready.dlq",
                    "reason": "PROCESSING_ERROR",
                    "rawPayload": "{\\"sessionId\\":\\"sess-close\\",\\"tenantId\\":\\"tenant-a\\"}"
                  }
                }
                """);

        verify(sessionLifecycleService).stopSession(eq("sess-close"), any(SessionStopRequest.class));
        verify(stateRepository).markSucceeded("evt-close", "SESSION_CLOSE", "SESSION_CLOSED");
        verify(kafkaTemplate, never()).send(eq("tts.ready"), anyString(), anyString());
    }

    @Test
    void routesNonRetryableToManual() {
        when(stateRepository.begin("evt-manual", "MANUAL")).thenReturn(true);

        consumer.onPlatformDlq("""
                {
                  "eventId": "evt-manual",
                  "eventType": "platform.dlq",
                  "eventVersion": "v1",
                  "traceId": "trc-manual",
                  "tenantId": "tenant-a",
                  "payload": {
                    "service": "translation-worker",
                    "sourceTopic": "translation.request",
                    "dlqTopic": "translation.request.dlq",
                    "reason": "NON_RETRYABLE",
                    "rawPayload": "{\\"sessionId\\":\\"sess-manual\\",\\"tenantId\\":\\"tenant-a\\"}"
                  }
                }
                """);

        verify(stateRepository).markSucceeded("evt-manual", "MANUAL", "MANUAL_INTERVENTION_REQUIRED");
        verify(sessionLifecycleService, never()).stopSession(anyString(), any(SessionStopRequest.class));
        verify(kafkaTemplate, never()).send(eq("translation.request"), anyString(), anyString());
    }

    @Test
    void skipsWhenAlreadyProcessed() {
        when(stateRepository.begin("evt-done", "REPLAY")).thenReturn(false);

        consumer.onPlatformDlq("""
                {
                  "eventId": "evt-done",
                  "eventType": "platform.dlq",
                  "eventVersion": "v1",
                  "traceId": "trc-done",
                  "tenantId": "tenant-a",
                  "payload": {
                    "service": "asr-worker",
                    "sourceTopic": "audio.ingress.raw",
                    "dlqTopic": "audio.ingress.raw.dlq",
                    "reason": "PROCESSING_ERROR",
                    "rawPayload": "{\\"sessionId\\":\\"sess-done\\",\\"tenantId\\":\\"tenant-a\\"}"
                  }
                }
                """);

        verify(stateRepository, never()).markSucceeded(anyString(), anyString(), anyString());
        verify(stateRepository, never()).markFailed(anyString(), anyString(), anyString());
        verify(sessionLifecycleService, never()).stopSession(anyString(), any(SessionStopRequest.class));
    }

    @Test
    void marksFailedWhenReplayPublishFails() {
        when(stateRepository.begin("evt-fail", "REPLAY")).thenReturn(true);
        when(kafkaTemplate.send(eq("audio.ingress.raw"), eq("sess-fail"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("publish failed")));

        consumer.onPlatformDlq("""
                {
                  "eventId": "evt-fail",
                  "eventType": "platform.dlq",
                  "eventVersion": "v1",
                  "traceId": "trc-fail",
                  "tenantId": "tenant-a",
                  "payload": {
                    "service": "asr-worker",
                    "sourceTopic": "audio.ingress.raw",
                    "dlqTopic": "audio.ingress.raw.dlq",
                    "reason": "PROCESSING_ERROR",
                    "rawPayload": "{\\"sessionId\\":\\"sess-fail\\",\\"tenantId\\":\\"tenant-a\\"}"
                  }
                }
                """);

        verify(stateRepository).markFailed("evt-fail", "REPLAY", "ACTION_FAILED");
    }
}
