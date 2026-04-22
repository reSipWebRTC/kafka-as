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
import com.kafkaasr.tts.events.TtsReadyEvent;
import com.kafkaasr.tts.events.TtsReadyPayload;
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
class KafkaTtsReadyPublisherTests {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaTtsReadyPublisher publisher;

    @BeforeEach
    void setUp() {
        TtsKafkaProperties properties = new TtsKafkaProperties();
        properties.setTtsReadyTopic("tts.ready");
        publisher = new KafkaTtsReadyPublisher(kafkaTemplate, objectMapper, properties);
    }

    @Test
    void publishesSerializedEventToConfiguredTopic() throws Exception {
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("tts.ready"), eq("sess-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        TtsReadyEvent event = new TtsReadyEvent(
                "evt-1",
                "tts.ready",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "tts-orchestrator",
                5L,
                1713744000000L,
                "sess-1:tts.ready:5",
                new TtsReadyPayload(
                        "https://cdn.local/tts/tts:v1:abc.wav",
                        "audio/pcm",
                        16000,
                        520L,
                        "tts:v1:abc"));

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("tts.ready"), eq("sess-1"), payloadCaptor.capture());

        JsonNode jsonNode = objectMapper.readTree(payloadCaptor.getValue());
        assertEquals("tts.ready", jsonNode.get("eventType").asText());
        assertEquals("sess-1", jsonNode.get("sessionId").asText());
        assertEquals("https://cdn.local/tts/tts:v1:abc.wav", jsonNode.get("payload").get("playbackUrl").asText());
        assertEquals("tts:v1:abc", jsonNode.get("payload").get("cacheKey").asText());
    }
}
