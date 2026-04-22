package com.kafkaasr.gateway.ws.downlink;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.GatewayDownlinkPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class TranslationResultDownlinkConsumerTests {

    @Mock
    private GatewayDownlinkPublisher downlinkPublisher;

    @Mock
    private GatewayCompensationPublisher compensationPublisher;

    private TranslationResultDownlinkConsumer consumer;
    private GatewayDownlinkProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GatewayDownlinkProperties();
        properties.setRetryMaxAttempts(2);
        consumer = new TranslationResultDownlinkConsumer(
                objectMapper,
                downlinkPublisher,
                new TimedIdempotencyGuard(properties),
                compensationPublisher,
                properties,
                new SimpleMeterRegistry());
    }

    @Test
    void forwardsTranslationResultAsSubtitleFinal() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestTranslationResultEvent(
                "evt-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                9L,
                1713744001000L,
                "sess-1:translation.result:9",
                new TestTranslationResultPayload("hello", "bonjour", "en-US", "fr-FR", "placeholder")));

        when(downlinkPublisher.publishSubtitleFinal(eq("sess-1"), eq(9L), eq("bonjour")))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(downlinkPublisher).publishSubtitleFinal("sess-1", 9L, "bonjour");
    }

    @Test
    void fallsBackToSourceTextWhenTranslatedTextBlank() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestTranslationResultEvent(
                "evt-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                10L,
                1713744002000L,
                "sess-1:translation.result:10",
                new TestTranslationResultPayload("hello", " ", "en-US", "fr-FR", "placeholder")));

        when(downlinkPublisher.publishSubtitleFinal(eq("sess-1"), eq(10L), eq("hello")))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(downlinkPublisher).publishSubtitleFinal("sess-1", 10L, "hello");
    }

    @Test
    void rejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{invalid-json"));

        verify(downlinkPublisher, never()).publishSubtitleFinal("sess-1", 9L, "bonjour");
    }

    @Test
    void dropsDuplicateEventByIdempotencyKey() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestTranslationResultEvent(
                "evt-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                9L,
                1713744001000L,
                "sess-1:translation.result:9",
                new TestTranslationResultPayload("hello", "bonjour", "en-US", "fr-FR", "placeholder")));
        when(downlinkPublisher.publishSubtitleFinal(eq("sess-1"), eq(9L), eq("bonjour")))
                .thenReturn(Mono.empty());

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        verify(downlinkPublisher, times(1)).publishSubtitleFinal("sess-1", 9L, "bonjour");
    }

    @Test
    void emitsCompensationSignalAfterRepeatedFailureThreshold() throws Exception {
        String payload = objectMapper.writeValueAsString(new TestTranslationResultEvent(
                "evt-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                9L,
                1713744001000L,
                "sess-1:translation.result:9",
                new TestTranslationResultPayload("hello", "bonjour", "en-US", "fr-FR", "placeholder")));
        when(downlinkPublisher.publishSubtitleFinal(eq("sess-1"), eq(9L), eq("bonjour")))
                .thenReturn(Mono.error(new IllegalStateException("downlink failed")));

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(payload));

        verify(compensationPublisher).publish(anyString(), eq(payload), any(RuntimeException.class));
    }

    private record TestTranslationResultEvent(
            String eventId,
            String eventType,
            String eventVersion,
            String traceId,
            String sessionId,
            String tenantId,
            String roomId,
            String producer,
            long seq,
            long ts,
            String idempotencyKey,
            TestTranslationResultPayload payload) {
    }

    private record TestTranslationResultPayload(
            String sourceText,
            String translatedText,
            String sourceLang,
            String targetLang,
            String engine) {
    }
}
