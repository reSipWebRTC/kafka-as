package com.kafkaasr.tts.kafka;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.CommandResultEvent;
import com.kafkaasr.tts.events.CommandResultPayload;
import com.kafkaasr.tts.events.TtsChunkEvent;
import com.kafkaasr.tts.events.TtsChunkPayload;
import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.events.TtsReadyEvent;
import com.kafkaasr.tts.events.TtsReadyPayload;
import com.kafkaasr.tts.events.TtsRequestEvent;
import com.kafkaasr.tts.events.TtsRequestPayload;
import com.kafkaasr.tts.pipeline.TtsRequestPipelineService;
import com.kafkaasr.tts.pipeline.TtsSynthesisException;
import com.kafkaasr.tts.policy.TenantReliabilityPolicy;
import com.kafkaasr.tts.policy.TenantReliabilityPolicyResolver;
import com.kafkaasr.tts.storage.TtsObjectStorageUploader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class CommandResultConsumerTests {

    @Mock
    private TtsRequestPipelineService pipelineService;

    @Mock
    private TtsRequestPublisher ttsRequestPublisher;

    @Mock
    private TtsChunkPublisher ttsChunkPublisher;

    @Mock
    private TtsReadyPublisher ttsReadyPublisher;

    @Mock
    private TtsObjectStorageUploader storageUploader;

    @Mock
    private TtsCompensationPublisher compensationPublisher;

    @Mock
    private TenantReliabilityPolicyResolver reliabilityPolicyResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CommandResultConsumer consumer;
    private TtsKafkaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TtsKafkaProperties();
        properties.setCommandResultTopic("command.result");
        properties.setRetryMaxAttempts(2);
        consumer = new CommandResultConsumer(
                objectMapper,
                pipelineService,
                ttsRequestPublisher,
                ttsChunkPublisher,
                ttsReadyPublisher,
                storageUploader,
                compensationPublisher,
                properties,
                reliabilityPolicyResolver,
                new SimpleMeterRegistry());
        lenient().when(reliabilityPolicyResolver.resolve(anyString()))
                .thenReturn(new TenantReliabilityPolicy(2, 1L, ".dlq"));
        lenient().when(storageUploader.upload(any()))
                .thenReturn(new TtsObjectStorageUploader.UploadResult(
                        "tts/tenant-a/tts_v1_abc.wav",
                        "https://cdn.example.com/tts/tenant-a/tts_v1_abc.wav"));
    }

    @Test
    void routesCommandResultPayloadThroughTtsPipelineAndPublisher() throws Exception {
        CommandResultEvent input = new CommandResultEvent(
                "evt-in-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                5L,
                1713744000000L,
                "sess-1:command.result:5",
                new CommandResultPayload(
                        "exec-1",
                        "CLIENT_BRIDGE",
                        "failed",
                        "DEVICE_OFFLINE",
                        "",
                        false,
                        1,
                        3,
                        "cfm-1",
                        null,
                        "DEVICE_OFFLINE",
                        20,
                        "CONTROL",
                        "power_on"));

        TtsRequestEvent requestEvent = new TtsRequestEvent(
                "evt-out-1",
                "tts.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                5L,
                1713744001000L,
                "sess-1:tts.request:5",
                new TtsRequestPayload("设备离线", "zh-CN", "voice-a", "tts:v1:abc", true));

        TtsChunkEvent chunkEvent = new TtsChunkEvent(
                "evt-out-2",
                "tts.chunk",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                5L,
                1713744001000L,
                "sess-1:tts.chunk:5",
                new TtsChunkPayload("aGVsbG8=", "audio/pcm", 16000, 0, true));

        TtsReadyEvent readyEvent = new TtsReadyEvent(
                "evt-out-3",
                "tts.ready",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                5L,
                1713744001000L,
                "sess-1:tts.ready:5",
                new TtsReadyPayload("https://cdn.local/tts/tts:v1:abc.wav", "audio/pcm", 16000, 400L, "tts:v1:abc"));

        String payload = objectMapper.writeValueAsString(input);
        when(pipelineService.toPipelineEvents(any(CommandResultEvent.class))).thenReturn(
                new TtsRequestPipelineService.PipelineOutput(requestEvent, chunkEvent, readyEvent));
        when(ttsRequestPublisher.publish(requestEvent)).thenReturn(Mono.empty());
        when(ttsChunkPublisher.publish(chunkEvent)).thenReturn(Mono.empty());
        when(ttsReadyPublisher.publish(any())).thenReturn(Mono.empty());

        consumer.onMessage(payload);

        verify(pipelineService).toPipelineEvents(any(CommandResultEvent.class));
        verify(ttsRequestPublisher).publish(requestEvent);
        verify(ttsChunkPublisher).publish(chunkEvent);
        verify(storageUploader).upload(any());
        verify(ttsReadyPublisher).publish(argThat(event ->
                "https://cdn.example.com/tts/tenant-a/tts_v1_abc.wav".equals(event.payload().playbackUrl())));
    }

    @Test
    void dropsDuplicateCommandResultEventByIdempotencyKey() throws Exception {
        CommandResultEvent input = new CommandResultEvent(
                "evt-in-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                5L,
                1713744000000L,
                "sess-1:command.result:5",
                new CommandResultPayload(
                        "exec-1",
                        "CLIENT_BRIDGE",
                        "failed",
                        "DEVICE_OFFLINE",
                        "",
                        false,
                        1,
                        3,
                        "cfm-1",
                        null,
                        "DEVICE_OFFLINE",
                        20,
                        "CONTROL",
                        "power_on"));

        TtsRequestEvent requestEvent = new TtsRequestEvent(
                "evt-out-1",
                "tts.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                5L,
                1713744001000L,
                "sess-1:tts.request:5",
                new TtsRequestPayload("设备离线", "zh-CN", "voice-a", "tts:v1:abc", true));

        TtsChunkEvent chunkEvent = new TtsChunkEvent(
                "evt-out-2",
                "tts.chunk",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                5L,
                1713744001000L,
                "sess-1:tts.chunk:5",
                new TtsChunkPayload("aGVsbG8=", "audio/pcm", 16000, 0, true));

        TtsReadyEvent readyEvent = new TtsReadyEvent(
                "evt-out-3",
                "tts.ready",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                5L,
                1713744001000L,
                "sess-1:tts.ready:5",
                new TtsReadyPayload("https://cdn.local/tts/tts:v1:abc.wav", "audio/pcm", 16000, 400L, "tts:v1:abc"));

        String payload = objectMapper.writeValueAsString(input);
        when(pipelineService.toPipelineEvents(any(CommandResultEvent.class))).thenReturn(
                new TtsRequestPipelineService.PipelineOutput(requestEvent, chunkEvent, readyEvent));
        when(ttsRequestPublisher.publish(requestEvent)).thenReturn(Mono.empty());
        when(ttsChunkPublisher.publish(chunkEvent)).thenReturn(Mono.empty());
        when(ttsReadyPublisher.publish(any())).thenReturn(Mono.empty());

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        verify(pipelineService, times(1)).toPipelineEvents(any(CommandResultEvent.class));
        verify(ttsRequestPublisher, times(1)).publish(requestEvent);
        verify(ttsChunkPublisher, times(1)).publish(chunkEvent);
        verify(storageUploader, times(1)).upload(any());
        verify(ttsReadyPublisher, times(1)).publish(any());
    }

    @Test
    void emitsCompensationAfterRepeatedFailureThreshold() throws Exception {
        CommandResultEvent input = new CommandResultEvent(
                "evt-in-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                5L,
                1713744000000L,
                "sess-1:command.result:5",
                new CommandResultPayload(
                        "exec-1",
                        "CLIENT_BRIDGE",
                        "failed",
                        "INTERNAL_ERROR",
                        "",
                        true,
                        1,
                        3,
                        "cfm-1",
                        null,
                        "INTERNAL_ERROR",
                        20,
                        "CONTROL",
                        "power_on"));
        String payload = objectMapper.writeValueAsString(input);
        when(reliabilityPolicyResolver.resolve("tenant-a"))
                .thenReturn(new TenantReliabilityPolicy(2, 1L, ".tenant-a.dlq"));
        when(pipelineService.toPipelineEvents(any(CommandResultEvent.class)))
                .thenThrow(new IllegalStateException("tts failed"));

        assertThrows(TenantAwareDlqException.class, () -> consumer.onMessage(payload));

        verify(pipelineService, times(2)).toPipelineEvents(any(CommandResultEvent.class));
        verify(storageUploader, never()).upload(any());
        verify(compensationPublisher).publish(
                eq("command.result"),
                eq("command.result.tenant-a.dlq"),
                eq(payload),
                any(RuntimeException.class));
    }

    @Test
    void retriesWhenTtsSynthesisExceptionIsRetryable() throws Exception {
        CommandResultEvent input = new CommandResultEvent(
                "evt-in-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                5L,
                1713744000000L,
                "sess-1:command.result:5",
                new CommandResultPayload(
                        "exec-1",
                        "CLIENT_BRIDGE",
                        "timeout",
                        "UPSTREAM_TIMEOUT",
                        "",
                        true,
                        1,
                        3,
                        "cfm-1",
                        null,
                        "NLU_TIMEOUT",
                        20,
                        "CONTROL",
                        "power_on"));
        String payload = objectMapper.writeValueAsString(input);
        when(reliabilityPolicyResolver.resolve("tenant-a"))
                .thenReturn(new TenantReliabilityPolicy(2, 1L, ".tenant-a.dlq"));
        when(pipelineService.toPipelineEvents(any(CommandResultEvent.class)))
                .thenThrow(new TtsSynthesisException("TTS_TIMEOUT", "timed out", true));

        assertThrows(TenantAwareDlqException.class, () -> consumer.onMessage(payload));

        verify(pipelineService, times(2)).toPipelineEvents(any(CommandResultEvent.class));
        verify(storageUploader, never()).upload(any());
        verify(compensationPublisher).publish(
                eq("command.result"),
                eq("command.result.tenant-a.dlq"),
                eq(payload),
                any(RuntimeException.class));
    }

    @Test
    void doesNotRetryWhenTtsSynthesisExceptionIsNotRetryable() throws Exception {
        CommandResultEvent input = new CommandResultEvent(
                "evt-in-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "command-worker",
                5L,
                1713744000000L,
                "sess-1:command.result:5",
                new CommandResultPayload(
                        "exec-1",
                        "CLIENT_BRIDGE",
                        "failed",
                        "DEVICE_OFFLINE",
                        "",
                        false,
                        1,
                        3,
                        "cfm-1",
                        null,
                        "DEVICE_OFFLINE",
                        20,
                        "CONTROL",
                        "power_on"));
        String payload = objectMapper.writeValueAsString(input);
        when(reliabilityPolicyResolver.resolve("tenant-a"))
                .thenReturn(new TenantReliabilityPolicy(2, 1L, ".tenant-a.dlq"));
        when(pipelineService.toPipelineEvents(any(CommandResultEvent.class)))
                .thenThrow(new TtsSynthesisException("TTS_PROVIDER_REJECTED", "rejected", false));

        assertThrows(TenantAwareDlqException.class, () -> consumer.onMessage(payload));

        verify(pipelineService, times(1)).toPipelineEvents(any(CommandResultEvent.class));
        verify(storageUploader, never()).upload(any());
        verify(compensationPublisher).publish(
                eq("command.result"),
                eq("command.result.tenant-a.dlq"),
                eq(payload),
                any(RuntimeException.class));
    }
}
