package com.kafkaasr.command.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.events.AsrFinalEvent;
import com.kafkaasr.command.events.CommandConfirmRequestEvent;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.events.CommandResultEvent;
import com.kafkaasr.command.pipeline.CommandPipelineService;
import com.kafkaasr.command.pipeline.SmartHomeClientException;
import com.kafkaasr.command.policy.TenantRoutingPolicy;
import com.kafkaasr.command.policy.TenantRoutingPolicyResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "command.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class CommandIngressConsumer {

    private static final Logger log = LoggerFactory.getLogger(CommandIngressConsumer.class);

    private final ObjectMapper objectMapper;
    private final CommandPipelineService pipelineService;
    private final CommandResultPublisher commandResultPublisher;
    private final CommandCompensationPublisher compensationPublisher;
    private final CommandKafkaProperties kafkaProperties;
    private final TenantRoutingPolicyResolver routingPolicyResolver;
    private final TimedIdempotencyGuard idempotencyGuard;
    private final MeterRegistry meterRegistry;

    public CommandIngressConsumer(
            ObjectMapper objectMapper,
            CommandPipelineService pipelineService,
            CommandResultPublisher commandResultPublisher,
            CommandCompensationPublisher compensationPublisher,
            CommandKafkaProperties kafkaProperties,
            TenantRoutingPolicyResolver routingPolicyResolver,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.commandResultPublisher = commandResultPublisher;
        this.compensationPublisher = compensationPublisher;
        this.kafkaProperties = kafkaProperties;
        this.routingPolicyResolver = routingPolicyResolver;
        this.idempotencyGuard = new TimedIdempotencyGuard(
                kafkaProperties.isIdempotencyEnabled(),
                kafkaProperties.getIdempotencyTtlMs());
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "#{@commandKafkaProperties.asrFinalTopic}",
            groupId = "${COMMAND_ASR_FINAL_CONSUMER_GROUP_ID:command-worker-asr-final}")
    public void onAsrFinalMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            AsrFinalEvent event = parseAsrFinal(payload);
            if (idempotencyGuard.isDuplicate(event.idempotencyKey())) {
                incrementCounter("asr.final", "duplicate", "DUPLICATE");
                return;
            }
            TenantRoutingPolicy policy = routingPolicyResolver.resolve(event.tenantId());
            if (!policy.isSmartHomeMode()) {
                incrementCounter("asr.final", "ignored", "NON_SMART_HOME");
                return;
            }
            processAsrFinalWithRetry(event, payload, policy);
        } catch (IllegalArgumentException exception) {
            incrementCounter("asr.final", "error", "INVALID_PAYLOAD");
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("command.pipeline.duration", "source", "asr.final"));
        }
    }

    @KafkaListener(
            topics = "#{@commandKafkaProperties.commandConfirmRequestTopic}",
            groupId = "${COMMAND_CONFIRM_CONSUMER_GROUP_ID:command-worker-confirm}")
    public void onCommandConfirmMessage(String payload) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            CommandConfirmRequestEvent event = parseCommandConfirm(payload);
            if (idempotencyGuard.isDuplicate(event.idempotencyKey())) {
                incrementCounter("command.confirm.request", "duplicate", "DUPLICATE");
                return;
            }
            TenantRoutingPolicy policy = routingPolicyResolver.resolve(event.tenantId());
            if (!policy.isSmartHomeMode()) {
                incrementCounter("command.confirm.request", "ignored", "NON_SMART_HOME");
                return;
            }
            processConfirmWithRetry(event, payload, policy);
        } catch (IllegalArgumentException exception) {
            incrementCounter("command.confirm.request", "error", "INVALID_PAYLOAD");
            throw exception;
        } finally {
            sample.stop(meterRegistry.timer("command.pipeline.duration", "source", "command.confirm.request"));
        }
    }

    private void processAsrFinalWithRetry(
            AsrFinalEvent event,
            String payload,
            TenantRoutingPolicy policy) {
        int maxAttempts = Math.max(1, policy.retryMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processAsrFinalOnce(event);
                return;
            } catch (RuntimeException failure) {
                boolean retryable = isRetryable(failure);
                if (retryable && attempt < maxAttempts) {
                    sleepBackoff(policy.retryBackoffMs());
                    continue;
                }
                incrementCounter("asr.final", "error", normalizeErrorCode(failure));
                recordFailureAndCompensate(kafkaProperties.getAsrFinalTopic(), payload, policy, failure);
                throw wrapForDlq(policy.dlqTopicSuffix(), failure);
            }
        }
    }

    private void processConfirmWithRetry(
            CommandConfirmRequestEvent event,
            String payload,
            TenantRoutingPolicy policy) {
        int maxAttempts = Math.max(1, policy.retryMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processConfirmOnce(event);
                return;
            } catch (RuntimeException failure) {
                boolean retryable = isRetryable(failure);
                if (retryable && attempt < maxAttempts) {
                    sleepBackoff(policy.retryBackoffMs());
                    continue;
                }
                incrementCounter("command.confirm.request", "error", normalizeErrorCode(failure));
                recordFailureAndCompensate(
                        kafkaProperties.getCommandConfirmRequestTopic(),
                        payload,
                        policy,
                        failure);
                throw wrapForDlq(policy.dlqTopicSuffix(), failure);
            }
        }
    }

    private void processAsrFinalOnce(AsrFinalEvent event) {
        String userId = resolveUserId(event.userId(), event.sessionId());
        CommandResultEvent resultEvent = pipelineService.fromAsrFinal(event, userId);
        commandResultPublisher.publish(resultEvent).block();
        idempotencyGuard.markProcessed(event.idempotencyKey());
        incrementCounter("asr.final", "success", "OK");
        log.debug("Published command.result from asr.final sessionId={} seq={}", event.sessionId(), event.seq());
    }

    private void processConfirmOnce(CommandConfirmRequestEvent event) {
        CommandResultEvent resultEvent = pipelineService.fromCommandConfirmRequest(event);
        commandResultPublisher.publish(resultEvent).block();
        idempotencyGuard.markProcessed(event.idempotencyKey());
        incrementCounter("command.confirm.request", "success", "OK");
        log.debug(
                "Published command.result from command.confirm.request sessionId={} seq={}",
                event.sessionId(),
                event.seq());
    }

    private AsrFinalEvent parseAsrFinal(String payload) {
        try {
            return objectMapper.readValue(payload, AsrFinalEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid asr.final payload", exception);
        }
    }

    private CommandConfirmRequestEvent parseCommandConfirm(String payload) {
        try {
            return objectMapper.readValue(payload, CommandConfirmRequestEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid command.confirm.request payload", exception);
        }
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "INVALID_PAYLOAD";
        }
        if (throwable instanceof SmartHomeClientException smartHomeClientException) {
            return smartHomeClientException.errorCode();
        }
        return "PIPELINE_FAILURE";
    }

    private boolean isRetryable(RuntimeException failure) {
        if (failure instanceof SmartHomeClientException smartHomeClientException) {
            return smartHomeClientException.retryable();
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
            throw new IllegalStateException("Interrupted while waiting for command retry backoff", exception);
        }
    }

    private void recordFailureAndCompensate(
            String sourceTopic,
            String payload,
            TenantRoutingPolicy policy,
            RuntimeException failure) {
        compensationPublisher.publish(
                sourceTopic,
                sourceTopic + policy.dlqTopicSuffix(),
                payload,
                failure);
    }

    private String resolveUserId(String userId, String sessionId) {
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        return "user-" + sessionId;
    }

    private void incrementCounter(String source, String result, String code) {
        meterRegistry.counter(
                        "command.pipeline.messages.total",
                        "source",
                        source,
                        "result",
                        result,
                        "code",
                        code)
                .increment();
    }
}
