package com.kafkaasr.translation.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.translation.events.TranslationKafkaProperties;
import com.kafkaasr.translation.events.TranslationRequestEvent;
import com.kafkaasr.translation.events.TranslationRequestPayload;
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
class KafkaTranslationRequestPublisherTests {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaTranslationRequestPublisher publisher;

    @BeforeEach
    void setUp() {
        TranslationKafkaProperties properties = new TranslationKafkaProperties();
        properties.setTranslationRequestTopic("translation.request");
        properties.setProducerId("translation-worker");
        publisher = new KafkaTranslationRequestPublisher(kafkaTemplate, objectMapper, properties);
    }

    @Test
    void publishesSerializedEventToConfiguredTopic() throws Exception {
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("translation.request"), eq("sess-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        TranslationRequestEvent event = new TranslationRequestEvent(
                "evt-1",
                "translation.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                5L,
                1713744000000L,
                "sess-1:translation.request:5",
                new TranslationRequestPayload("你好", "zh-CN", "en-US"));

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("translation.request"), eq("sess-1"), payloadCaptor.capture());

        JsonNode jsonNode = objectMapper.readTree(payloadCaptor.getValue());
        assertEquals("translation.request", jsonNode.get("eventType").asText());
        assertEquals("sess-1", jsonNode.get("sessionId").asText());
        assertEquals("你好", jsonNode.get("payload").get("sourceText").asText());
        assertEquals("en-US", jsonNode.get("payload").get("targetLang").asText());
    }
}
