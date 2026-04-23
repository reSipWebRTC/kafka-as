package com.kafkaasr.control.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "control.kafka.enabled", havingValue = "true")
public class KafkaTenantPolicyChangedPublisher implements TenantPolicyChangedPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ControlKafkaProperties kafkaProperties;

    public KafkaTenantPolicyChangedPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ControlKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public Mono<Void> publish(TenantPolicyChangedEvent event) {
        return Mono.fromFuture(kafkaTemplate.send(
                        kafkaProperties.getPolicyChangedTopic(),
                        event.tenantId(),
                        toJson(event)))
                .then();
    }

    private String toJson(TenantPolicyChangedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize tenant.policy.changed event", exception);
        }
    }
}
