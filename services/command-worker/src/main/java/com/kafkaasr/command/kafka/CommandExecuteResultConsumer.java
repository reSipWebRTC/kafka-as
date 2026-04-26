package com.kafkaasr.command.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.events.CommandExecuteResultEvent;
import com.kafkaasr.command.events.CommandExecuteResultPayload;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.pipeline.CommandPipelineOutcome;
import com.kafkaasr.command.pipeline.CommandPipelineService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "command.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class CommandExecuteResultConsumer {

    private final ObjectMapper objectMapper;
    private final CommandPipelineService pipelineService;
    private final CommandDispatchPublisher commandDispatchPublisher;
    private final CommandResultPublisher commandResultPublisher;
    private final CommandCompensationPublisher compensationPublisher;
    private final CommandKafkaProperties kafkaProperties;
    private final TimedIdempotencyGuard idempotencyGuard;

    public CommandExecuteResultConsumer(
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
            topics = "#{@commandKafkaProperties.commandExecuteResultTopic}",
            groupId = "${COMMAND_CONSUMER_GROUP_ID:command-worker}")
    public void onMessage(String payload) {
        CommandExecuteResultEvent event = parse(payload);
        String processKey = dedupeKey(event);
        if (idempotencyGuard.isDuplicate(processKey)) {
            return;
        }

        processWithRetry(event, payload, processKey);
    }

    private void processWithRetry(CommandExecuteResultEvent event, String rawPayload, String processKey) {
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
                recordFailureAndCompensate(kafkaProperties.getCommandExecuteResultTopic(), rawPayload, failure);
                throw wrapForDlq(kafkaProperties.getDlqTopicSuffix(), failure);
            }
        }
    }

    private void processOnce(CommandExecuteResultEvent event, String processKey) {
        CommandPipelineOutcome outcome = pipelineService.fromCommandExecuteResult(event);
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

    private CommandExecuteResultEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, CommandExecuteResultEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid command.execute.result payload", exception);
        }
    }

    private String dedupeKey(CommandExecuteResultEvent event) {
        if (event.idempotencyKey() != null && !event.idempotencyKey().isBlank()) {
            return event.idempotencyKey();
        }
        CommandExecuteResultPayload payload = event.payload();
        String executionId = payload == null || payload.executionId() == null || payload.executionId().isBlank()
                ? "unknown"
                : payload.executionId();
        int confirmRound = payload == null || payload.confirmRound() == null ? 0 : payload.confirmRound();
        return executionId + ":execute:" + confirmRound + ":" + event.seq();
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
}
