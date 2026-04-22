package com.kafkaasr.translation.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.translation.events.TranslationKafkaProperties;
import com.kafkaasr.translation.events.TranslationResultEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "translation.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTranslationResultPublisher implements TranslationResultPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TranslationKafkaProperties kafkaProperties;

    public KafkaTranslationResultPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            TranslationKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public Mono<Void> publish(TranslationResultEvent event) {
        String payload = toJson(event);
        return Mono.fromFuture(kafkaTemplate.send(
                        kafkaProperties.getTranslationResultTopic(),
                        event.sessionId(),
                        payload))
                .then();
    }

    private String toJson(TranslationResultEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize translation.result event", exception);
        }
    }
}
