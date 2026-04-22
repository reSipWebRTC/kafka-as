package com.kafkaasr.tts.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.events.TtsRequestEvent;
import com.kafkaasr.tts.events.TtsRequestPayload;
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
class KafkaTtsRequestPublisherTests {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaTtsRequestPublisher publisher;

    @BeforeEach
    void setUp() {
        TtsKafkaProperties properties = new TtsKafkaProperties();
        properties.setTtsRequestTopic("tts.request");
        properties.setProducerId("tts-orchestrator");
        publisher = new KafkaTtsRequestPublisher(kafkaTemplate, objectMapper, properties);
    }

    @Test
    void publishesSerializedEventToConfiguredTopic() throws Exception {
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("tts.request"), eq("sess-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        TtsRequestEvent event = new TtsRequestEvent(
                "evt-1",
                "tts.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                5L,
                1713744000000L,
                "sess-1:tts.request:5",
                new TtsRequestPayload("hello", "en-US", "voice-a", "tts:v1:abc", true));

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("tts.request"), eq("sess-1"), payloadCaptor.capture());

        JsonNode jsonNode = objectMapper.readTree(payloadCaptor.getValue());
        assertEquals("tts.request", jsonNode.get("eventType").asText());
        assertEquals("sess-1", jsonNode.get("sessionId").asText());
        assertEquals("hello", jsonNode.get("payload").get("text").asText());
        assertEquals("voice-a", jsonNode.get("payload").get("voice").asText());
    }
}
