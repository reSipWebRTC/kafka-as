package com.kafkaasr.command.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.events.AsrFinalEvent;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.pipeline.CommandPipelineOutcome;
import com.kafkaasr.command.pipeline.CommandPipelineService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "command.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class AsrFinalConsumer {

    private final ObjectMapper objectMapper;
    private final CommandPipelineService pipelineService;
    private final CommandDispatchPublisher commandDispatchPublisher;
    private final CommandResultPublisher commandResultPublisher;
    private final CommandCompensationPublisher compensationPublisher;
    private final CommandKafkaProperties kafkaProperties;
    private final TimedIdempotencyGuard idempotencyGuard;

    public AsrFinalConsumer(
            ObjectMapper objectMapper,
            CommandPipelineService pipelineService,
            CommandDispatchPublisher commandDispatchPublisher,
            CommandResultPublisher commandResultPublisher,
            CommandCompensationPublisher compensationPublisher,
            CommandKafkaProperties kafkaProperties) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.commandDispatchPublisher = commandDispatchPublisher;
        this.commandResultPublisher = commandResultPublisher;
        this.compensationPublisher = compensationPublisher;
        this.kafkaProperties = kafkaProperties;
        this.idempotencyGuard = new TimedIdempotencyGuard(
                kafkaProperties.isIdempotencyEnabled(),
                kafkaProperties.getIdempotencyTtlMs());
    }

    @KafkaListener(
            topics = "#{@commandKafkaProperties.asrFinalTopic}",
            groupId = "${COMMAND_CONSUMER_GROUP_ID:command-worker}")
    public void onMessage(String payload) {
        AsrFinalEvent event = parse(payload);
        String processKey = firstNonBlank(event.idempotencyKey(), event.sessionId() + ":" + event.seq());
        if (idempotencyGuard.isDuplicate(processKey)) {
            return;
        }

        processWithRetry(event, payload, processKey);
    }

    private void processWithRetry(AsrFinalEvent event, String rawPayload, String processKey) {
        int maxAttempts = Math.max(1, kafkaProperties.getRetryMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processOnce(event, processKey);
                return;
            } catch (RuntimeException failure) {
                boolean retryable = isRetryable(failure);
                if (retryable && attempt < maxAttempts) {
                    sleepBackoff(kafkaProperties.getRetryBackoffMs());
                    continue;
                }
                recordFailureAndCompensate(kafkaProperties.getAsrFinalTopic(), rawPayload, failure);
                throw wrapForDlq(kafkaProperties.getDlqTopicSuffix(), failure);
            }
        }
    }

    private void processOnce(AsrFinalEvent event, String processKey) {
        CommandPipelineOutcome outcome = pipelineService.fromAsrFinal(event);
        publishOutcome(outcome);
        idempotencyGuard.markProcessed(processKey);
    }

    private void publishOutcome(CommandPipelineOutcome outcome) {
        if (outcome.commandDispatchEvent() != null) {
            commandDispatchPublisher.publish(outcome.commandDispatchEvent()).block();
        }
        if (outcome.commandResultEvent() != null) {
            commandResultPublisher.publish(outcome.commandResultEvent()).block();
        }
    }

    private AsrFinalEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, AsrFinalEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid asr.final payload", exception);
        }
    }

    private RuntimeException wrapForDlq(String dlqTopicSuffix, RuntimeException failure) {
        if (failure instanceof TenantAwareDlqException) {
            return failure;
        }
        return new TenantAwareDlqException(dlqTopicSuffix, failure);
    }

    private boolean isRetryable(RuntimeException failure) {
        return !(failure instanceof IllegalArgumentException);
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

    private void recordFailureAndCompensate(String sourceTopic, String rawPayload, RuntimeException failure) {
        compensationPublisher.publish(
                sourceTopic,
                sourceTopic + kafkaProperties.getDlqTopicSuffix(),
                rawPayload,
                failure);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
