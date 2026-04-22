package com.kafkaasr.tts.kafka;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.events.TtsRequestEvent;
import com.kafkaasr.tts.events.TtsRequestPayload;
import com.kafkaasr.tts.events.TranslationResultEvent;
import com.kafkaasr.tts.events.TranslationResultPayload;
import com.kafkaasr.tts.pipeline.TtsRequestPipelineService;
import com.kafkaasr.tts.policy.TenantReliabilityPolicy;
import com.kafkaasr.tts.policy.TenantReliabilityPolicyResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class TranslationResultConsumerTests {

    @Mock
    private TtsRequestPipelineService pipelineService;

    @Mock
    private TtsRequestPublisher ttsRequestPublisher;

    @Mock
    private TtsCompensationPublisher compensationPublisher;

    @Mock
    private TenantReliabilityPolicyResolver reliabilityPolicyResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TranslationResultConsumer consumer;
    private TtsKafkaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TtsKafkaProperties();
        properties.setRetryMaxAttempts(2);
        consumer = new TranslationResultConsumer(
                objectMapper,
                pipelineService,
                ttsRequestPublisher,
                compensationPublisher,
                properties,
                reliabilityPolicyResolver,
                new SimpleMeterRegistry());
        lenient().when(reliabilityPolicyResolver.resolve(anyString()))
                .thenReturn(new TenantReliabilityPolicy(2, 1L, ".dlq"));
    }

    @Test
    void routesValidTranslationResultPayloadThroughPipelineAndPublisher() throws Exception {
        TranslationResultEvent input = new TranslationResultEvent(
                "evt-in-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                3L,
                1713744000000L,
                "sess-1:translation.result:3",
                new TranslationResultPayload("你好", "hello", "zh-CN", "en-US", "placeholder"));

        TtsRequestEvent output = new TtsRequestEvent(
                "evt-out-1",
                "tts.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                3L,
                1713744001000L,
                "sess-1:tts.request:3",
                new TtsRequestPayload("hello", "en-US", "voice-a", "tts:v1:abc", true));

        String payload = objectMapper.writeValueAsString(input);
        when(pipelineService.toTtsRequestEvent(any())).thenReturn(output);
        when(ttsRequestPublisher.publish(output)).thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(pipelineService).toTtsRequestEvent(any());
        verify(ttsRequestPublisher).publish(output);
    }

    @Test
    void rejectsMalformedTranslationResultPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{invalid-json"));

        verify(pipelineService, never()).toTtsRequestEvent(any());
        verify(ttsRequestPublisher, never()).publish(any());
    }

    @Test
    void dropsDuplicateEventByIdempotencyKey() throws Exception {
        TranslationResultEvent input = new TranslationResultEvent(
                "evt-in-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                3L,
                1713744000000L,
                "sess-1:translation.result:3",
                new TranslationResultPayload("你好", "hello", "zh-CN", "en-US", "placeholder"));

        TtsRequestEvent output = new TtsRequestEvent(
                "evt-out-1",
                "tts.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                3L,
                1713744001000L,
                "sess-1:tts.request:3",
                new TtsRequestPayload("hello", "en-US", "voice-a", "tts:v1:abc", true));

        String payload = objectMapper.writeValueAsString(input);
        when(pipelineService.toTtsRequestEvent(any())).thenReturn(output);
        when(ttsRequestPublisher.publish(output)).thenReturn(Mono.empty());

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        verify(pipelineService, times(1)).toTtsRequestEvent(any());
        verify(ttsRequestPublisher, times(1)).publish(output);
    }

    @Test
    void emitsCompensationSignalAfterRepeatedFailureThreshold() throws Exception {
        TranslationResultEvent input = new TranslationResultEvent(
                "evt-in-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                3L,
                1713744000000L,
                "sess-1:translation.result:3",
                new TranslationResultPayload("你好", "hello", "zh-CN", "en-US", "placeholder"));
        String payload = objectMapper.writeValueAsString(input);
        when(reliabilityPolicyResolver.resolve("tenant-a"))
                .thenReturn(new TenantReliabilityPolicy(2, 1L, ".tenant-a.dlq"));
        when(pipelineService.toTtsRequestEvent(any())).thenThrow(new IllegalStateException("tts failed"));

        assertThrows(TenantAwareDlqException.class, () -> consumer.onMessage(payload));

        verify(pipelineService, times(2)).toTtsRequestEvent(any());
        verify(compensationPublisher).publish(
                eq("translation.result"),
                eq("translation.result.tenant-a.dlq"),
                eq(payload),
                any(RuntimeException.class));
    }
}
