package com.kafkaasr.gateway.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class KafkaAudioIngressPublisherTests {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-21T00:00:00Z");

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaAudioIngressPublisher publisher;

    @BeforeEach
    void setUp() {
        GatewayKafkaProperties properties = new GatewayKafkaProperties();
        properties.setAudioIngressTopic("audio.ingress.raw");
        properties.setTenantId("tenant-a");
        properties.setProducerId("speech-gateway");
        properties.setChannels(1);

        publisher = new KafkaAudioIngressPublisher(
                kafkaTemplate,
                objectMapper,
                properties,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void publishesSchemaCompatibleAudioIngressEvent() throws Exception {
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("audio.ingress.raw"), eq("sess-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        StepVerifier.create(publisher.publishRawFrame(new AudioFrameIngressCommand(
                        "sess-1",
                        7L,
                        "flac",
                        16000,
                        new byte[] {1, 2, 3})))
                .verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("audio.ingress.raw"), eq("sess-1"), payloadCaptor.capture());

        JsonNode event = objectMapper.readTree(payloadCaptor.getValue());
        JsonNode payload = event.get("payload");

        assertTrue(event.get("eventId").asText().startsWith("evt_"));
        assertTrue(event.get("traceId").asText().startsWith("trc_"));
        assertEquals("audio.ingress.raw", event.get("eventType").asText());
        assertEquals("v1", event.get("eventVersion").asText());
        assertEquals("sess-1", event.get("sessionId").asText());
        assertEquals("tenant-a", event.get("tenantId").asText());
        assertEquals("speech-gateway", event.get("producer").asText());
        assertEquals(7L, event.get("seq").asLong());
        assertEquals(FIXED_INSTANT.toEpochMilli(), event.get("ts").asLong());
        assertEquals("sess-1:audio.ingress.raw:7", event.get("idempotencyKey").asText());
        assertFalse(event.has("roomId"));

        assertEquals("other", payload.get("audioCodec").asText());
        assertEquals(16000, payload.get("sampleRate").asInt());
        assertEquals(1, payload.get("channels").asInt());
        assertEquals("AQID", payload.get("audioBase64").asText());
        assertFalse(payload.get("endOfStream").asBoolean());
    }
}
