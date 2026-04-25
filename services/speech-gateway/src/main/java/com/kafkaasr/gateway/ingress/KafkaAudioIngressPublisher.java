package com.kafkaasr.gateway.ingress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "gateway.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaAudioIngressPublisher implements AudioIngressPublisher {

    private static final String EVENT_TYPE = "audio.ingress.raw";
    private static final String EVENT_VERSION = "v1";
    private static final Set<String> SUPPORTED_CODECS = Set.of("pcm16le", "opus", "aac");

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayKafkaProperties properties;
    private final Clock clock;

    public KafkaAudioIngressPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            GatewayKafkaProperties properties) {
        this(kafkaTemplate, objectMapper, properties, Clock.systemUTC());
    }

    KafkaAudioIngressPublisher(
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
    public Mono<Void> publishRawFrame(AudioFrameIngressCommand command) {
        AudioIngressRawEvent event = toEvent(command);
        String payload = toJson(event);
        return Mono.fromFuture(kafkaTemplate.send(
                        properties.getAudioIngressTopic(),
                        command.sessionId(),
                        payload))
                .then();
    }

    private AudioIngressRawEvent toEvent(AudioFrameIngressCommand command) {
        long timestamp = Instant.now(clock).toEpochMilli();
        String traceId = coalesce(command.traceId(), prefixedId("trc"));
        String tenantId = coalesce(command.tenantId(), properties.getTenantId());
        String userId = nullable(command.userId());
        return new AudioIngressRawEvent(
                prefixedId("evt"),
                EVENT_TYPE,
                EVENT_VERSION,
                traceId,
                command.sessionId(),
                tenantId,
                userId,
                null,
                properties.getProducerId(),
                command.seq(),
                timestamp,
                command.sessionId() + ":" + EVENT_TYPE + ":" + command.seq(),
                new AudioIngressRawPayload(
                        normalizeCodec(command.codec()),
                        command.sampleRate(),
                        properties.getChannels(),
                        Base64.getEncoder().encodeToString(command.audioBytes()),
                        false));
    }

    private String toJson(AudioIngressRawEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize audio ingress event", exception);
        }
    }

    private String normalizeCodec(String codec) {
        String normalizedCodec = codec.toLowerCase();
        return SUPPORTED_CODECS.contains(normalizedCodec) ? normalizedCodec : "other";
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
