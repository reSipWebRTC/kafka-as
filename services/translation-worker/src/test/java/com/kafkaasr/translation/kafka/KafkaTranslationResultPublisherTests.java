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
import com.kafkaasr.translation.events.TranslationResultEvent;
import com.kafkaasr.translation.events.TranslationResultPayload;
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
class KafkaTranslationResultPublisherTests {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaTranslationResultPublisher publisher;

    @BeforeEach
    void setUp() {
        TranslationKafkaProperties properties = new TranslationKafkaProperties();
        properties.setTranslationResultTopic("translation.result");
        properties.setProducerId("translation-worker");
        publisher = new KafkaTranslationResultPublisher(kafkaTemplate, objectMapper, properties);
    }

    @Test
    void publishesSerializedEventToConfiguredTopic() throws Exception {
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("translation.result"), eq("sess-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        TranslationResultEvent event = new TranslationResultEvent(
                "evt-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                null,
                "translation-worker",
                5L,
                1713744000000L,
                "sess-1:translation.result:5",
                new TranslationResultPayload("你好", "hello", "zh-CN", "en-US", "placeholder"));

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("translation.result"), eq("sess-1"), payloadCaptor.capture());

        JsonNode jsonNode = objectMapper.readTree(payloadCaptor.getValue());
        assertEquals("translation.result", jsonNode.get("eventType").asText());
        assertEquals("sess-1", jsonNode.get("sessionId").asText());
        assertEquals("hello", jsonNode.get("payload").get("translatedText").asText());
        assertEquals("en-US", jsonNode.get("payload").get("targetLang").asText());
    }
}
