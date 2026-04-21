package com.kafkaasr.orchestrator.events;

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
class KafkaSessionControlPublisherTests {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaSessionControlPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        OrchestratorKafkaProperties properties = new OrchestratorKafkaProperties();
        properties.setSessionControlTopic("session.control");
        properties.setProducerId("session-orchestrator");
        publisher = new KafkaSessionControlPublisher(kafkaTemplate, objectMapper, properties);
    }

    @Test
    void publishesSerializedEventToConfiguredTopic() throws Exception {
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("session.control"), eq("sess-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        SessionControlEvent event = new SessionControlEvent(
                "evt-1",
                "session.control",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "session-orchestrator",
                3L,
                1710000000000L,
                "sess-1:session.control:3",
                new SessionControlPayload("STOP", "CLOSED", "zh-CN", "en-US", "client.stop"));

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("session.control"), eq("sess-1"), payloadCaptor.capture());

        JsonNode jsonNode = objectMapper.readTree(payloadCaptor.getValue());
        assertEquals("session.control", jsonNode.get("eventType").asText());
        assertEquals("v1", jsonNode.get("eventVersion").asText());
        assertEquals("sess-1", jsonNode.get("sessionId").asText());
        assertEquals("STOP", jsonNode.get("payload").get("action").asText());
        assertEquals("CLOSED", jsonNode.get("payload").get("status").asText());
    }
}
