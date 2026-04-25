package com.kafkaasr.asr.events;

import jakarta.validation.constraints.Min;
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

    @NotBlank
    private String auditTopic = "platform.audit";

    @NotBlank
    private String platformDlqTopic = "platform.dlq";

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

    public String getAuditTopic() {
        return auditTopic;
    }

    public void setAuditTopic(String auditTopic) {
        this.auditTopic = auditTopic;
    }

    public String getPlatformDlqTopic() {
        return platformDlqTopic;
    }

    public void setPlatformDlqTopic(String platformDlqTopic) {
        this.platformDlqTopic = platformDlqTopic;
    }
}
