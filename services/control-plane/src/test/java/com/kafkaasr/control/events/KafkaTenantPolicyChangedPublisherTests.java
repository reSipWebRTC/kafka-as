package com.kafkaasr.control.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class KafkaTenantPolicyChangedPublisherTests {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaTenantPolicyChangedPublisher publisher;

    @BeforeEach
    void setUp() {
        ControlKafkaProperties properties = new ControlKafkaProperties();
        properties.setPolicyChangedTopic("tenant.policy.changed");
        properties.setProducerId("control-plane");
        publisher = new KafkaTenantPolicyChangedPublisher(kafkaTemplate, objectMapper, properties);
    }

    @Test
    void publishesSerializedPolicyChangedEventToConfiguredTopic() throws Exception {
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("tenant.policy.changed"), eq("tenant-a"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        TenantPolicyChangedEvent event = new TenantPolicyChangedEvent(
                "evt-1",
                "tenant.policy.changed",
                "v1",
                "trc-1",
                "tenant-policy::tenant-a",
                "tenant-a",
                null,
                "control-plane",
                3L,
                1713744000000L,
                "tenant-a:tenant.policy.changed:3",
                new TenantPolicyChangedPayload(
                        "tenant-a",
                        3L,
                        1713744000000L,
                        "UPDATED",
                        null,
                        null,
                        null,
                        null));

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("tenant.policy.changed"), eq("tenant-a"), payloadCaptor.capture());

        JsonNode jsonNode = objectMapper.readTree(payloadCaptor.getValue());
        assertEquals("tenant.policy.changed", jsonNode.get("eventType").asText());
        assertEquals("tenant-a", jsonNode.get("tenantId").asText());
        assertEquals("UPDATED", jsonNode.get("payload").get("operation").asText());
        assertEquals(3L, jsonNode.get("payload").get("policyVersion").asLong());
    }
}
