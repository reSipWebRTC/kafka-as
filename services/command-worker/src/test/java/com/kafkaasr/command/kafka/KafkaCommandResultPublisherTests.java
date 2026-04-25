package com.kafkaasr.command.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.events.CommandResultEvent;
import com.kafkaasr.command.events.CommandResultPayload;
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
class KafkaCommandResultPublisherTests {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaCommandResultPublisher publisher;

    @BeforeEach
    void setUp() {
        CommandKafkaProperties properties = new CommandKafkaProperties();
        properties.setCommandResultTopic("command.result");
        properties.setProducerId("command-worker");
        publisher = new KafkaCommandResultPublisher(kafkaTemplate, objectMapper, properties);
    }

    @Test
    void publishesSerializedEventToConfiguredTopic() throws Exception {
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(eq("command.result"), eq("sess-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        CommandResultEvent event = new CommandResultEvent(
                "evt-1",
                "command.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                "user-a",
                null,
                "command-worker",
                5L,
                1713744000000L,
                "sess-1:command.result:asr.final:5",
                new CommandResultPayload(
                        "ok",
                        "OK",
                        "Done",
                        "Done",
                        false,
                        null,
                        null,
                        "CONTROL",
                        "switch.on"));

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("command.result"), eq("sess-1"), payloadCaptor.capture());

        JsonNode jsonNode = objectMapper.readTree(payloadCaptor.getValue());
        assertEquals("command.result", jsonNode.get("eventType").asText());
        assertEquals("sess-1", jsonNode.get("sessionId").asText());
        assertEquals("OK", jsonNode.get("payload").get("code").asText());
        assertEquals("Done", jsonNode.get("payload").get("replyText").asText());
    }
}
