package com.kafkaasr.tts.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.CommandResultEvent;
import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.events.TtsReadyEvent;
import com.kafkaasr.tts.events.TtsReadyPayload;
import com.kafkaasr.tts.pipeline.TtsRequestPipelineService;
import com.kafkaasr.tts.pipeline.TtsSynthesisException;
import com.kafkaasr.tts.policy.TenantReliabilityPolicy;
import com.kafkaasr.tts.policy.TenantReliabilityPolicyResolver;
import com.kafkaasr.tts.storage.TtsObjectStorageUploader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Base64;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tts.kafka.command-result-enabled", havingValue = "true", matchIfMissing = true)
public class CommandResultConsumer {

    private final ObjectMapper objectMapper;
    private final TtsRequestPipelineService pipelineService;
    private final TtsRequestPublisher ttsRequestPublisher;
    private final TtsChunkPublisher ttsChunkPublisher;
    private final TtsReadyPublisher ttsReadyPublisher;
    private final TtsObjectStorageUploader storageUploader;
    private final TtsCompensationPublisher compensationPublisher;
    private final TtsKafkaProperties kafkaProperties;
    private final TenantReliabilityPolicyResolver reliabilityPolicyResolver;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final MeterRegistry meterRegistry;

    public CommandResultConsumer(
            ObjectMapper objectMapper,
            TtsRequestPipelineService pipelineService,
            TtsRequestPublisher ttsRequestPublisher,
            TtsChunkPublisher ttsChunkPublisher,
            TtsReadyPublisher ttsReadyPublisher,
            TtsObjectStorageUploader storageUploader,
            TtsCompensationPublisher compensationPublisher,
            TtsKafkaProperties kafkaProperties,
            TenantReliabilityPolicyResolver reliabilityPolicyResolver,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.ttsRequestPublisher = ttsRequestPublisher;
        this.ttsChunkPublisher = ttsChunkPublisher;
        this.ttsReadyPublisher = ttsReadyPublisher;
        this.storageUploader = storageUploader;
        this.compensationPublisher = compensationPublisher;
        this.kafkaProperties = kafkaProperties;
        this.reliabilityPolicyResolver = reliabilityPolicyResolver;
        this.idempotencyGuard = new TimedIdempotencyGuard(
                kafkaProperties.isIdempotencyEnabled(),
                kafkaProperties.getIdempotencyTtlMs());
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${tts.kafka.command-result-topic:command.result}",
            groupId = "${TTS_CONSUMER_GROUP_ID:tts-orchestrator}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CommandResultEvent commandResultEvent = parse(payload);
            if (idempotencyGuard.isDuplicate(commandResultEvent.idempotencyKey())) {
                meterRegistry.counter(
                                "tts.pipeline.command.messages.total",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                return;
            }
            TenantReliabilityPolicy reliabilityPolicy = reliabilityPolicyResolver.resolve(commandResultEvent.tenantId());
            processWithRetry(commandResultEvent, payload, reliabilityPolicy);
        } catch (IllegalArgumentException exception) {
            meterRegistry.counter(
                            "tts.pipeline.command.messages.total",
                            "result",
                            "error",
                            "code",
                            "INVALID_PAYLOAD")
                    .increment();
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("tts.pipeline.command.duration"));
        }
    }

    private void processWithRetry(
            CommandResultEvent commandResultEvent,
            String payload,
            TenantReliabilityPolicy reliabilityPolicy) {
        int maxAttempts = Math.max(1, reliabilityPolicy.retryMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processOnce(commandResultEvent);
                return;
            } catch (RuntimeException failure) {
                boolean retryable = isRetryable(failure);
                if (retryable && attempt < maxAttempts) {
                    sleepBackoff(reliabilityPolicy.retryBackoffMs());
                    continue;
                }
                meterRegistry.counter(
                                "tts.pipeline.command.messages.total",
                                "result",
                                "error",
                                "code",
                                normalizeErrorCode(failure))
                        .increment();
                recordFailureAndCompensate(payload, reliabilityPolicy, failure);
                throw wrapForDlq(reliabilityPolicy.dlqTopicSuffix(), failure);
            }
        }
    }

    private void processOnce(CommandResultEvent commandResultEvent) {
        TtsRequestPipelineService.PipelineOutput pipelineOutput = pipelineService.toPipelineEvents(commandResultEvent);
        TtsReadyEvent readyEvent = enrichReadyEventWithStoragePlaybackUrl(pipelineOutput, commandResultEvent);
        ttsRequestPublisher.publish(pipelineOutput.requestEvent()).block();
        ttsChunkPublisher.publish(pipelineOutput.chunkEvent()).block();
        ttsReadyPublisher.publish(readyEvent).block();
        idempotencyGuard.markProcessed(commandResultEvent.idempotencyKey());
        meterRegistry.counter(
                        "tts.pipeline.command.messages.total",
                        "result",
                        "success",
                        "code",
                        "OK")
                .increment();
    }

    private TtsReadyEvent enrichReadyEventWithStoragePlaybackUrl(
            TtsRequestPipelineService.PipelineOutput pipelineOutput,
            CommandResultEvent commandResultEvent) {
        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(pipelineOutput.chunkEvent().payload().audioBase64());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid base64 audio payload for tts.chunk upload", exception);
        }

        TtsObjectStorageUploader.UploadResult uploadResult = storageUploader.upload(
                new TtsObjectStorageUploader.UploadRequest(
                        commandResultEvent.tenantId(),
                        commandResultEvent.sessionId(),
                        commandResultEvent.seq(),
                        pipelineOutput.readyEvent().payload().cacheKey(),
                        pipelineOutput.chunkEvent().payload().codec(),
                        audioBytes,
                        pipelineOutput.readyEvent().payload().playbackUrl()));

        return new TtsReadyEvent(
                pipelineOutput.readyEvent().eventId(),
                pipelineOutput.readyEvent().eventType(),
                pipelineOutput.readyEvent().eventVersion(),
                pipelineOutput.readyEvent().traceId(),
                pipelineOutput.readyEvent().sessionId(),
                pipelineOutput.readyEvent().tenantId(),
                pipelineOutput.readyEvent().roomId(),
                pipelineOutput.readyEvent().producer(),
                pipelineOutput.readyEvent().seq(),
                pipelineOutput.readyEvent().ts(),
                pipelineOutput.readyEvent().idempotencyKey(),
                new TtsReadyPayload(
                        uploadResult.playbackUrl(),
                        pipelineOutput.readyEvent().payload().codec(),
                        pipelineOutput.readyEvent().payload().sampleRate(),
                        pipelineOutput.readyEvent().payload().durationMs(),
                        pipelineOutput.readyEvent().payload().cacheKey()));
    }

    private CommandResultEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, CommandResultEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid command.result payload", exception);
        }
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_PAYLOAD";
        }
        if (throwable instanceof TtsSynthesisException ttsSynthesisException) {
            return ttsSynthesisException.errorCode();
        }
        return "PIPELINE_FAILURE";
    }

    private boolean isRetryable(RuntimeException failure) {
        if (failure instanceof TtsSynthesisException ttsSynthesisException) {
            return ttsSynthesisException.retryable();
        }
        return !(failure instanceof IllegalArgumentException);
    }

    private RuntimeException wrapForDlq(String dlqTopicSuffix, RuntimeException failure) {
        if (failure instanceof TenantAwareDlqException) {
            return failure;
        }
        return new TenantAwareDlqException(dlqTopicSuffix, failure);
    }

    private void sleepBackoff(long backoffMs) {
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for command.result TTS retry backoff", exception);
        }
    }

    private void recordFailureAndCompensate(
            String payload,
            TenantReliabilityPolicy reliabilityPolicy,
            RuntimeException failure) {
        compensationPublisher.publish(
                kafkaProperties.getCommandResultTopic(),
                kafkaProperties.getCommandResultTopic() + reliabilityPolicy.dlqTopicSuffix(),
                payload,
                failure);
    }
}
