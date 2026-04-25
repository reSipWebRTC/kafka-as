package com.kafkaasr.tts.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.CommandResultEvent;
import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.events.TtsReadyEvent;
import com.kafkaasr.tts.events.TtsReadyPayload;
import com.kafkaasr.tts.events.TranslationResultEvent;
import com.kafkaasr.tts.pipeline.TtsRequestPipelineService;
import com.kafkaasr.tts.pipeline.TtsSynthesisException;
import com.kafkaasr.tts.policy.TenantReliabilityPolicy;
import com.kafkaasr.tts.policy.TenantReliabilityPolicyResolver;
import com.kafkaasr.tts.storage.TtsObjectStorageUploader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tts.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TranslationResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(TranslationResultConsumer.class);

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

    public TranslationResultConsumer(
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
            topics = "${tts.kafka.translation-result-topic:translation.result}",
            groupId = "${TTS_CONSUMER_GROUP_ID:tts-orchestrator}")
    public void onMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            TranslationResultEvent translationResultEvent = parseTranslationResult(payload);
            if (idempotencyGuard.isDuplicate(translationResultEvent.idempotencyKey())) {
                meterRegistry.counter(
                                "tts.pipeline.messages.total",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                log.debug("Dropped duplicated translation.result event idempotencyKey={}",
                        translationResultEvent.idempotencyKey());
                return;
            }
            TenantReliabilityPolicy reliabilityPolicy = reliabilityPolicyResolver.resolve(translationResultEvent.tenantId());
            if (reliabilityPolicy.isSmartHomeMode()) {
                meterRegistry.counter(
                                "tts.pipeline.messages.total",
                                "result",
                                "ignored",
                                "code",
                                "NON_TRANSLATION_MODE")
                        .increment();
                log.debug("Ignored translation.result for SMART_HOME tenant tenantId={}", translationResultEvent.tenantId());
                return;
            }
            processWithRetry(
                    () -> processTranslationOnce(translationResultEvent),
                    kafkaProperties.getTranslationResultTopic(),
                    payload,
                    reliabilityPolicy);
        } catch (IllegalArgumentException exception) {
            meterRegistry.counter(
                            "tts.pipeline.messages.total",
                            "result",
                            "error",
                            "code",
                            "INVALID_PAYLOAD")
                    .increment();
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("tts.pipeline.duration"));
        }
    }

    @KafkaListener(
            topics = "${tts.kafka.command-result-topic:command.result}",
            groupId = "${TTS_COMMAND_RESULT_CONSUMER_GROUP_ID:tts-orchestrator-command-result}")
    public void onCommandResultMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CommandResultEvent commandResultEvent = parseCommandResult(payload);
            if (idempotencyGuard.isDuplicate(commandResultEvent.idempotencyKey())) {
                meterRegistry.counter(
                                "tts.pipeline.messages.total",
                                "result",
                                "duplicate",
                                "code",
                                "DUPLICATE")
                        .increment();
                log.debug("Dropped duplicated command.result event idempotencyKey={}",
                        commandResultEvent.idempotencyKey());
                return;
            }
            TenantReliabilityPolicy reliabilityPolicy = reliabilityPolicyResolver.resolve(commandResultEvent.tenantId());
            if (!reliabilityPolicy.isSmartHomeMode()) {
                meterRegistry.counter(
                                "tts.pipeline.messages.total",
                                "result",
                                "ignored",
                                "code",
                                "NON_SMART_HOME")
                        .increment();
                log.debug("Ignored command.result for non-SMART_HOME tenant tenantId={}", commandResultEvent.tenantId());
                return;
            }
            processWithRetry(
                    () -> processCommandResultOnce(commandResultEvent),
                    kafkaProperties.getCommandResultTopic(),
                    payload,
                    reliabilityPolicy);
        } catch (IllegalArgumentException exception) {
            meterRegistry.counter(
                            "tts.pipeline.messages.total",
                            "result",
                            "error",
                            "code",
                            "INVALID_PAYLOAD")
                    .increment();
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("tts.pipeline.duration"));
        }
    }

    private void processWithRetry(
            Runnable processOnceAction,
            String sourceTopic,
            String payload,
            TenantReliabilityPolicy reliabilityPolicy) {
        int maxAttempts = Math.max(1, reliabilityPolicy.retryMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processOnceAction.run();
                return;
            } catch (RuntimeException failure) {
                boolean retryable = isRetryable(failure);
                if (retryable && attempt < maxAttempts) {
                    sleepBackoff(reliabilityPolicy.retryBackoffMs());
                    continue;
                }
                meterRegistry.counter(
                                "tts.pipeline.messages.total",
                                "result",
                                "error",
                                "code",
                                normalizeErrorCode(failure))
                        .increment();
                recordFailureAndCompensate(sourceTopic, payload, reliabilityPolicy, failure);
                throw wrapForDlq(reliabilityPolicy.dlqTopicSuffix(), failure);
            }
        }
    }

    private void processTranslationOnce(TranslationResultEvent translationResultEvent) {
        TtsRequestPipelineService.PipelineOutput pipelineOutput = pipelineService.toPipelineEvents(translationResultEvent);
        TtsReadyEvent readyEvent = enrichReadyEventWithStoragePlaybackUrl(
                pipelineOutput,
                translationResultEvent.tenantId(),
                translationResultEvent.sessionId(),
                translationResultEvent.seq());
        ttsRequestPublisher.publish(pipelineOutput.requestEvent()).block();
        ttsChunkPublisher.publish(pipelineOutput.chunkEvent()).block();
        ttsReadyPublisher.publish(readyEvent).block();
        idempotencyGuard.markProcessed(translationResultEvent.idempotencyKey());
        meterRegistry.counter(
                        "tts.pipeline.messages.total",
                        "result",
                        "success",
                        "code",
                        "OK")
                .increment();
        log.debug(
                "Published tts.request/tts.chunk/tts.ready events sessionId={} seq={}",
                pipelineOutput.requestEvent().sessionId(),
                pipelineOutput.requestEvent().seq());
    }

    private void processCommandResultOnce(CommandResultEvent commandResultEvent) {
        TtsRequestPipelineService.PipelineOutput pipelineOutput = pipelineService.toPipelineEvents(commandResultEvent);
        TtsReadyEvent readyEvent = enrichReadyEventWithStoragePlaybackUrl(
                pipelineOutput,
                commandResultEvent.tenantId(),
                commandResultEvent.sessionId(),
                commandResultEvent.seq());
        ttsRequestPublisher.publish(pipelineOutput.requestEvent()).block();
        ttsChunkPublisher.publish(pipelineOutput.chunkEvent()).block();
        ttsReadyPublisher.publish(readyEvent).block();
        idempotencyGuard.markProcessed(commandResultEvent.idempotencyKey());
        meterRegistry.counter(
                        "tts.pipeline.messages.total",
                        "result",
                        "success",
                        "code",
                        "OK")
                .increment();
        log.debug(
                "Published tts.request/tts.chunk/tts.ready events from command.result sessionId={} seq={}",
                pipelineOutput.requestEvent().sessionId(),
                pipelineOutput.requestEvent().seq());
    }

    private TtsReadyEvent enrichReadyEventWithStoragePlaybackUrl(
            TtsRequestPipelineService.PipelineOutput pipelineOutput,
            String tenantId,
            String sessionId,
            long seq) {
        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(pipelineOutput.chunkEvent().payload().audioBase64());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid base64 audio payload for tts.chunk upload", exception);
        }

        TtsObjectStorageUploader.UploadResult uploadResult = storageUploader.upload(
                new TtsObjectStorageUploader.UploadRequest(
                        tenantId,
                        sessionId,
                        seq,
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

    private TranslationResultEvent parseTranslationResult(String payload) {
        try {
            return objectMapper.readValue(payload, TranslationResultEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid translation.result payload", exception);
        }
    }

    private CommandResultEvent parseCommandResult(String payload) {
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
            throw new IllegalStateException("Interrupted while waiting for TTS retry backoff", exception);
        }
    }

    private void recordFailureAndCompensate(
            String sourceTopic,
            String payload,
            TenantReliabilityPolicy reliabilityPolicy,
            RuntimeException failure) {
        compensationPublisher.publish(
                sourceTopic,
                sourceTopic + reliabilityPolicy.dlqTopicSuffix(),
                payload,
                failure);
    }
}
