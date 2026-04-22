package com.kafkaasr.asr.events;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "asr.kafka")
public class AsrKafkaProperties {

    private boolean enabled = true;

    @NotBlank
    private String audioIngressTopic = "audio.ingress.raw";

    @NotBlank
    private String asrPartialTopic = "asr.partial";

    @NotBlank
    private String asrFinalTopic = "asr.final";

    @NotBlank
    private String producerId = "asr-worker";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAudioIngressTopic() {
        return audioIngressTopic;
    }

    public void setAudioIngressTopic(String audioIngressTopic) {
        this.audioIngressTopic = audioIngressTopic;
    }

    public String getAsrPartialTopic() {
        return asrPartialTopic;
    }

    public void setAsrPartialTopic(String asrPartialTopic) {
        this.asrPartialTopic = asrPartialTopic;
    }

    public String getAsrFinalTopic() {
        return asrFinalTopic;
    }

    public void setAsrFinalTopic(String asrFinalTopic) {
        this.asrFinalTopic = asrFinalTopic;
    }

    public String getProducerId() {
        return producerId;
    }

    public void setProducerId(String producerId) {
        this.producerId = producerId;
    }
}
