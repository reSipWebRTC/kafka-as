package com.kafkaasr.asr.kafka;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.asr.events.AsrFinalEvent;
import com.kafkaasr.asr.events.AsrFinalPayload;
import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.events.AudioIngressRawPayload;
import com.kafkaasr.asr.pipeline.AsrPipelineService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AudioIngressConsumerTests {

    @Mock
    private AsrPipelineService pipelineService;

    @Mock
    private AsrFinalPublisher asrFinalPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AudioIngressConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AudioIngressConsumer(objectMapper, pipelineService, asrFinalPublisher, new SimpleMeterRegistry());
    }

    @Test
    void routesValidIngressPayloadThroughPipelineAndPublisher() throws Exception {
        AudioIngressRawEvent ingressEvent = new AudioIngressRawEvent(
                "evt-in-1",
                "audio.ingress.raw",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "speech-gateway",
                1L,
                1713744000000L,
                "sess-1:audio.ingress.raw:1",
                new AudioIngressRawPayload("pcm16le", 16000, 1, "AQID", false));

        AsrFinalEvent finalEvent = new AsrFinalEvent(
                "evt-out-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "asr-worker",
                1L,
                1713744001000L,
                "sess-1:asr.final:1",
                new AsrFinalPayload("hello", "en-US", 0.9d, true));

        String payload = objectMapper.writeValueAsString(ingressEvent);
        when(pipelineService.toAsrFinalEvent(any())).thenReturn(finalEvent);
        when(asrFinalPublisher.publish(finalEvent)).thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(pipelineService).toAsrFinalEvent(any());
        verify(asrFinalPublisher).publish(finalEvent);
    }

    @Test
    void rejectsMalformedIngressPayload() {
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("{invalid-json"));

        verify(pipelineService, never()).toAsrFinalEvent(any());
        verify(asrFinalPublisher, never()).publish(any());
    }
}
