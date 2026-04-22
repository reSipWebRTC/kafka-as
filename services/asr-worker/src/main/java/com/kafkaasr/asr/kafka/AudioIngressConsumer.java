package com.kafkaasr.asr.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.asr.events.AsrFinalEvent;
import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.pipeline.AsrPipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "asr.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class AudioIngressConsumer {

    private static final Logger log = LoggerFactory.getLogger(AudioIngressConsumer.class);

    private final ObjectMapper objectMapper;
    private final AsrPipelineService pipelineService;
    private final AsrFinalPublisher asrFinalPublisher;

    public AudioIngressConsumer(
            ObjectMapper objectMapper,
            AsrPipelineService pipelineService,
            AsrFinalPublisher asrFinalPublisher) {
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.asrFinalPublisher = asrFinalPublisher;
    }

    @KafkaListener(
            topics = "#{@asrKafkaProperties.audioIngressTopic}",
            groupId = "${ASR_CONSUMER_GROUP_ID:asr-worker}")
    public void onMessage(String payload) {
        AudioIngressRawEvent ingressEvent = parse(payload);
        AsrFinalEvent finalEvent = pipelineService.toAsrFinalEvent(ingressEvent);

        asrFinalPublisher.publish(finalEvent).block();
        log.debug(
                "Published asr.final event sessionId={} seq={}",
                finalEvent.sessionId(),
                finalEvent.seq());
    }

    private AudioIngressRawEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, AudioIngressRawEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid audio.ingress.raw payload", exception);
        }
    }
}
