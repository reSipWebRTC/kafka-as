package com.kafkaasr.asr.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.asr.events.AsrKafkaProperties;
import com.kafkaasr.asr.events.AsrPartialEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "asr.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaAsrPartialPublisher implements AsrPartialPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AsrKafkaProperties kafkaProperties;

    public KafkaAsrPartialPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            AsrKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public Mono<Void> publish(AsrPartialEvent event) {
        String payload = toJson(event);
        return Mono.fromFuture(kafkaTemplate.send(
                        kafkaProperties.getAsrPartialTopic(),
                        event.sessionId(),
                        payload))
                .then();
    }

    private String toJson(AsrPartialEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize asr.partial event", exception);
        }
    }
}
