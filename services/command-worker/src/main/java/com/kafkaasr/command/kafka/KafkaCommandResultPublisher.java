package com.kafkaasr.command.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.events.CommandResultEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "command.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaCommandResultPublisher implements CommandResultPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CommandKafkaProperties kafkaProperties;

    public KafkaCommandResultPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            CommandKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public Mono<Void> publish(CommandResultEvent event) {
        String payload = toJson(event);
        return Mono.fromFuture(kafkaTemplate.send(
                        kafkaProperties.getCommandResultTopic(),
                        event.sessionId(),
                        payload))
                .then();
    }

    private String toJson(CommandResultEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize command.result event", exception);
        }
    }
}
