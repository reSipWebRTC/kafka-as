package com.kafkaasr.gateway.ingress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "gateway.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaCommandConfirmRequestPublisher implements CommandConfirmRequestPublisher {

    private static final String EVENT_TYPE = "command.confirm.request";
    private static final String EVENT_VERSION = "v1";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayKafkaProperties properties;
    private final Clock clock;

    public KafkaCommandConfirmRequestPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            GatewayKafkaProperties properties) {
        this(kafkaTemplate, objectMapper, properties, Clock.systemUTC());
    }

    KafkaCommandConfirmRequestPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            GatewayKafkaProperties properties,
            Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<Void> publish(CommandConfirmIngressCommand command) {
        CommandConfirmRequestEvent event = toEvent(command);
        String serialized = toJson(event);
        return Mono.fromFuture(kafkaTemplate.send(
                        properties.getCommandConfirmRequestTopic(),
                        command.sessionId(),
                        serialized))
                .then();
    }

    private CommandConfirmRequestEvent toEvent(CommandConfirmIngressCommand command) {
        long timestamp = Instant.now(clock).toEpochMilli();
        return new CommandConfirmRequestEvent(
                prefixedId("evt"),
                EVENT_TYPE,
                EVENT_VERSION,
                coalesce(command.traceId(), prefixedId("trc")),
                command.sessionId(),
                coalesce(command.tenantId(), properties.getTenantId()),
                nullable(command.userId()),
                null,
                properties.getProducerId(),
                command.seq(),
                timestamp,
                command.sessionId() + ":" + EVENT_TYPE + ":" + command.seq(),
                new CommandConfirmRequestPayload(command.confirmToken(), command.accept()));
    }

    private String toJson(CommandConfirmRequestEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize command confirm request event", exception);
        }
    }

    private String coalesce(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private String nullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
