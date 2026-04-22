package com.kafkaasr.tts.events;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tts.kafka")
public class TtsKafkaProperties {

    private boolean enabled = true;

    @NotBlank
    private String translationResultTopic = "translation.result";

    @NotBlank
    private String ttsRequestTopic = "tts.request";

    @NotBlank
    private String producerId = "tts-orchestrator";

    @NotBlank
    private String defaultVoice = "voice-default";

    private boolean streamEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTranslationResultTopic() {
        return translationResultTopic;
    }

    public void setTranslationResultTopic(String translationResultTopic) {
        this.translationResultTopic = translationResultTopic;
    }

    public String getTtsRequestTopic() {
        return ttsRequestTopic;
    }

    public void setTtsRequestTopic(String ttsRequestTopic) {
        this.ttsRequestTopic = ttsRequestTopic;
    }

    public String getProducerId() {
        return producerId;
    }

    public void setProducerId(String producerId) {
        this.producerId = producerId;
    }

    public String getDefaultVoice() {
        return defaultVoice;
    }

    public void setDefaultVoice(String defaultVoice) {
        this.defaultVoice = defaultVoice;
    }

    public boolean isStreamEnabled() {
        return streamEnabled;
    }

    public void setStreamEnabled(boolean streamEnabled) {
        this.streamEnabled = streamEnabled;
    }
}
