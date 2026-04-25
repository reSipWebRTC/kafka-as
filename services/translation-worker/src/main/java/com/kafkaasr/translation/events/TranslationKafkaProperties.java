package com.kafkaasr.translation.events;

import jakarta.validation.constraints.Min;
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
    private String translationRequestTopic = "translation.request";

    @NotBlank
    private String translationResultTopic = "translation.result";

    @NotBlank
    private String producerId = "translation-worker";

    @NotBlank
    private String defaultTargetLang = "en-US";

    @NotBlank
    private String engineName = "placeholder";

    @Min(1)
    private int retryMaxAttempts = 3;

    @Min(1)
    private long retryBackoffMs = 200L;

    @NotBlank
    private String dlqTopicSuffix = ".dlq";

    private boolean idempotencyEnabled = true;

    @Min(1)
    private long idempotencyTtlMs = 300000L;

    @NotBlank
    private String compensationTopic = "platform.compensation";

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

    public String getTranslationRequestTopic() {
        return translationRequestTopic;
    }

    public void setTranslationRequestTopic(String translationRequestTopic) {
        this.translationRequestTopic = translationRequestTopic;
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

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public String getDlqTopicSuffix() {
        return dlqTopicSuffix;
    }

    public void setDlqTopicSuffix(String dlqTopicSuffix) {
        this.dlqTopicSuffix = dlqTopicSuffix;
    }

    public boolean isIdempotencyEnabled() {
        return idempotencyEnabled;
    }

    public void setIdempotencyEnabled(boolean idempotencyEnabled) {
        this.idempotencyEnabled = idempotencyEnabled;
    }

    public long getIdempotencyTtlMs() {
        return idempotencyTtlMs;
    }

    public void setIdempotencyTtlMs(long idempotencyTtlMs) {
        this.idempotencyTtlMs = idempotencyTtlMs;
    }

    public String getCompensationTopic() {
        return compensationTopic;
    }

    public void setCompensationTopic(String compensationTopic) {
        this.compensationTopic = compensationTopic;
    }
}
