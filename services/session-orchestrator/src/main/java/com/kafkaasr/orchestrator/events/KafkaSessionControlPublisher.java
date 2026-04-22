package com.kafkaasr.orchestrator.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "orchestrator.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaSessionControlPublisher implements SessionControlPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OrchestratorKafkaProperties kafkaProperties;

    public KafkaSessionControlPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            OrchestratorKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public Mono<Void> publish(SessionControlEvent event) {
        String payload = toJson(event);
        return Mono.fromFuture(kafkaTemplate.send(
                        kafkaProperties.getSessionControlTopic(),
                        event.sessionId(),
                        payload))
                .then();
    }

    private String toJson(SessionControlEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize session.control event", exception);
        }
    }
}
