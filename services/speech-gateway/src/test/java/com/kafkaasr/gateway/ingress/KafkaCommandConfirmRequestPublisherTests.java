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
class KafkaCommandConfirmRequestPublisherTests {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-24T00:00:00Z");

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaCommandConfirmRequestPublisher publisher;

    @BeforeEach
    void setUp() {
        GatewayKafkaProperties properties = new GatewayKafkaProperties();
        properties.setCommandConfirmRequestTopic("command.confirm.request");
        properties.setTenantId("tenant-a");
        properties.setProducerId("speech-gateway");

        publisher = new KafkaCommandConfirmRequestPublisher(
                kafkaTemplate,
                objectMapper,
                properties,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void publishesSchemaCompatibleCommandConfirmEvent() throws Exception {
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("command.confirm.request"), eq("sess-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        StepVerifier.create(publisher.publish(new CommandConfirmIngressCommand(
                        "sess-1",
                        15L,
                        "trc-15",
                        "tenant-home",
                        "user-home",
                        "cfm-001",
                        true)))
                .verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("command.confirm.request"), eq("sess-1"), payloadCaptor.capture());

        JsonNode event = objectMapper.readTree(payloadCaptor.getValue());
        JsonNode payload = event.get("payload");

        assertTrue(event.get("eventId").asText().startsWith("evt_"));
        assertEquals("command.confirm.request", event.get("eventType").asText());
        assertEquals("v1", event.get("eventVersion").asText());
        assertEquals("trc-15", event.get("traceId").asText());
        assertEquals("sess-1", event.get("sessionId").asText());
        assertEquals("tenant-home", event.get("tenantId").asText());
        assertEquals("user-home", event.get("userId").asText());
        assertEquals("speech-gateway", event.get("producer").asText());
        assertEquals(15L, event.get("seq").asLong());
        assertEquals(FIXED_INSTANT.toEpochMilli(), event.get("ts").asLong());
        assertEquals("sess-1:command.confirm.request:15", event.get("idempotencyKey").asText());
        assertFalse(event.has("roomId"));

        assertEquals("cfm-001", payload.get("confirmToken").asText());
        assertTrue(payload.get("accept").asBoolean());
    }
}
