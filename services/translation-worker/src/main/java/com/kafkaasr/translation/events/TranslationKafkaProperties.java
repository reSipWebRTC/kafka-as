package com.kafkaasr.translation.events;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "translation.kafka")
public class TranslationKafkaProperties {

    private boolean enabled = true;

    @NotBlank
    private String asrFinalTopic = "asr.final";

    @NotBlank
    private String translationResultTopic = "translation.result";

    @NotBlank
    private String producerId = "translation-worker";

    @NotBlank
    private String defaultTargetLang = "en-US";

    @NotBlank
    private String engineName = "placeholder";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAsrFinalTopic() {
        return asrFinalTopic;
    }

    public void setAsrFinalTopic(String asrFinalTopic) {
        this.asrFinalTopic = asrFinalTopic;
    }

    public String getTranslationResultTopic() {
        return translationResultTopic;
    }

    public void setTranslationResultTopic(String translationResultTopic) {
        this.translationResultTopic = translationResultTopic;
    }

    public String getProducerId() {
        return producerId;
    }

    public void setProducerId(String producerId) {
        this.producerId = producerId;
    }

    public String getDefaultTargetLang() {
        return defaultTargetLang;
    }

    public void setDefaultTargetLang(String defaultTargetLang) {
        this.defaultTargetLang = defaultTargetLang;
    }

    public String getEngineName() {
        return engineName;
    }

    public void setEngineName(String engineName) {
        this.engineName = engineName;
    }
}
