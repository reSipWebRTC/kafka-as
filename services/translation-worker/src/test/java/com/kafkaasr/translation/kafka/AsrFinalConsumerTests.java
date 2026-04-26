package com.kafkaasr.translation.kafka;

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
import com.kafkaasr.translation.events.AsrFinalEvent;
import com.kafkaasr.translation.events.AsrFinalPayload;
import com.kafkaasr.translation.events.TranslationKafkaProperties;
import com.kafkaasr.translation.events.TranslationRequestEvent;
import com.kafkaasr.translation.events.TranslationRequestPayload;
import com.kafkaasr.translation.pipeline.TranslationEngineException;
import com.kafkaasr.translation.pipeline.TranslationPipelineService;
import com.kafkaasr.translation.policy.TenantReliabilityPolicy;
import com.kafkaasr.translation.policy.TenantReliabilityPolicyResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AsrFinalConsumerTests {

    @Mock
    private TranslationPipelineService pipelineService;

    @Mock
    private TranslationRequestPublisher translationRequestPublisher;

    @Mock
    private TranslationCompensationPublisher compensationPublisher;

    @Mock
    private TenantReliabilityPolicyResolver reliabilityPolicyResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AsrFinalConsumer consumer;
    private TranslationKafkaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TranslationKafkaProperties();
        properties.setRetryMaxAttempts(2);
        consumer = new AsrFinalConsumer(
                objectMapper,
                pipelineService,
                translationRequestPublisher,
                compensationPublisher,
                properties,
                reliabilityPolicyResolver,
                new SimpleMeterRegistry());
        lenient().when(reliabilityPolicyResolver.resolve(anyString()))
                .thenReturn(new TenantReliabilityPolicy(2, 1L, ".dlq"));
    }

    @Test
    void routesValidAsrFinalPayloadThroughPipelineAndPublisher() throws Exception {
        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "asr-worker",
                3L,
                1713744000000L,
                "sess-1:asr.final:3",
                new AsrFinalPayload("你好", "zh-CN", 0.9d, true));

        TranslationRequestEvent output = new TranslationRequestEvent(
                "evt-out-1",
                "translation.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                3L,
                1713744001000L,
                "sess-1:translation.request:3",
                new TranslationRequestPayload("你好", "zh-CN", "en-US"));

        String payload = objectMapper.writeValueAsString(input);
        when(pipelineService.toTranslationRequestEvent(any())).thenReturn(output);
        when(translationRequestPublisher.publish(output)).thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(pipelineService).toTranslationRequestEvent(any());
        verify(translationRequestPublisher).publish(output);
    }

    @Test
    void rejectsMalformedAsrFinalPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{invalid-json"));

        verify(pipelineService, never()).toTranslationRequestEvent(any());
        verify(translationRequestPublisher, never()).publish(any());
    }

    @Test
    void dropsDuplicateEventByIdempotencyKey() throws Exception {
        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "asr-worker",
                3L,
                1713744000000L,
                "sess-1:asr.final:3",
                new AsrFinalPayload("你好", "zh-CN", 0.9d, true));
        TranslationRequestEvent output = new TranslationRequestEvent(
                "evt-out-1",
                "translation.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                3L,
                1713744001000L,
                "sess-1:translation.request:3",
                new TranslationRequestPayload("你好", "zh-CN", "en-US"));

        String payload = objectMapper.writeValueAsString(input);
        when(pipelineService.toTranslationRequestEvent(any())).thenReturn(output);
        when(translationRequestPublisher.publish(output)).thenReturn(Mono.empty());

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        verify(pipelineService, times(1)).toTranslationRequestEvent(any());
        verify(translationRequestPublisher, times(1)).publish(output);
    }

    @Test
    void ignoresAsrFinalWhenTenantSessionModeIsSmartHome() throws Exception {
        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-home",
                null,
                "asr-worker",
                3L,
                1713744000000L,
                "sess-1:asr.final:3",
                new AsrFinalPayload("打开客厅灯", "zh-CN", 0.9d, true));

        String payload = objectMapper.writeValueAsString(input);
        when(reliabilityPolicyResolver.resolve("tenant-home"))
                .thenReturn(new TenantReliabilityPolicy(
                        2,
                        1L,
                        ".dlq",
                        TenantReliabilityPolicy.SESSION_MODE_SMART_HOME));

        consumer.onMessage(payload);

        verify(pipelineService, never()).toTranslationRequestEvent(any());
        verify(translationRequestPublisher, never()).publish(any());
        verify(compensationPublisher, never()).publish(anyString(), anyString(), anyString(), any(RuntimeException.class));
    }

    @Test
    void emitsCompensationSignalAfterRepeatedFailureThreshold() throws Exception {
        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "asr-worker",
                3L,
                1713744000000L,
                "sess-1:asr.final:3",
                new AsrFinalPayload("你好", "zh-CN", 0.9d, true));
        String payload = objectMapper.writeValueAsString(input);
        when(reliabilityPolicyResolver.resolve("tenant-a"))
                .thenReturn(new TenantReliabilityPolicy(2, 1L, ".tenant-a.dlq"));
        when(pipelineService.toTranslationRequestEvent(any())).thenThrow(new IllegalStateException("translation failed"));

        assertThrows(TenantAwareDlqException.class, () -> consumer.onMessage(payload));

        verify(pipelineService, times(2)).toTranslationRequestEvent(any());
        verify(compensationPublisher).publish(
                eq("asr.final"),
                eq("asr.final.tenant-a.dlq"),
                eq(payload),
                any(RuntimeException.class));
    }

    @Test
    void retriesWhenTranslationEngineExceptionIsRetryable() throws Exception {
        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "asr-worker",
                3L,
                1713744000000L,
                "sess-1:asr.final:3",
                new AsrFinalPayload("你好", "zh-CN", 0.9d, true));
        String payload = objectMapper.writeValueAsString(input);
        when(reliabilityPolicyResolver.resolve("tenant-a"))
                .thenReturn(new TenantReliabilityPolicy(2, 1L, ".tenant-a.dlq"));
        when(pipelineService.toTranslationRequestEvent(any())).thenThrow(
                new TranslationEngineException("TRANSLATION_TIMEOUT", "timed out", true));

        assertThrows(TenantAwareDlqException.class, () -> consumer.onMessage(payload));

        verify(pipelineService, times(2)).toTranslationRequestEvent(any());
        verify(compensationPublisher).publish(
                eq("asr.final"),
                eq("asr.final.tenant-a.dlq"),
                eq(payload),
                any(RuntimeException.class));
    }

    @Test
    void doesNotRetryWhenTranslationEngineExceptionIsNotRetryable() throws Exception {
        AsrFinalEvent input = new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "asr-worker",
                3L,
                1713744000000L,
                "sess-1:asr.final:3",
                new AsrFinalPayload("你好", "zh-CN", 0.9d, true));
        String payload = objectMapper.writeValueAsString(input);
        when(reliabilityPolicyResolver.resolve("tenant-a"))
                .thenReturn(new TenantReliabilityPolicy(2, 1L, ".tenant-a.dlq"));
        when(pipelineService.toTranslationRequestEvent(any())).thenThrow(
                new TranslationEngineException("TRANSLATION_INVALID_PAYLOAD", "bad payload", false));

        assertThrows(TenantAwareDlqException.class, () -> consumer.onMessage(payload));

        verify(pipelineService, times(1)).toTranslationRequestEvent(any());
        verify(compensationPublisher).publish(
                eq("asr.final"),
                eq("asr.final.tenant-a.dlq"),
                eq(payload),
                any(RuntimeException.class));
    }
}
