package com.kafkaasr.tts.events;

import jakarta.validation.constraints.Min;
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
    private String commandResultTopic = "command.result";

    @NotBlank
    private String ttsRequestTopic = "tts.request";

    @NotBlank
    private String ttsChunkTopic = "tts.chunk";

    @NotBlank
    private String ttsReadyTopic = "tts.ready";

    @NotBlank
    private String producerId = "tts-orchestrator";

    @NotBlank
    private String defaultVoice = "voice-default";

    private boolean streamEnabled = true;

    @NotBlank
    private String ttsChunkCodec = "audio/pcm";

    @Min(8000)
    private int ttsChunkSampleRate = 16000;

    @NotBlank
    private String ttsReadyPlaybackUrlPrefix = "https://cdn.local/tts";

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

    public String getTranslationResultTopic() {
        return translationResultTopic;
    }

    public void setTranslationResultTopic(String translationResultTopic) {
        this.translationResultTopic = translationResultTopic;
    }

    public String getCommandResultTopic() {
        return commandResultTopic;
    }

    public void setCommandResultTopic(String commandResultTopic) {
        this.commandResultTopic = commandResultTopic;
    }

    public String getTtsRequestTopic() {
        return ttsRequestTopic;
    }

    public void setTtsRequestTopic(String ttsRequestTopic) {
        this.ttsRequestTopic = ttsRequestTopic;
    }

    public String getTtsChunkTopic() {
        return ttsChunkTopic;
    }

    public void setTtsChunkTopic(String ttsChunkTopic) {
        this.ttsChunkTopic = ttsChunkTopic;
    }

    public String getTtsReadyTopic() {
        return ttsReadyTopic;
    }

    public void setTtsReadyTopic(String ttsReadyTopic) {
        this.ttsReadyTopic = ttsReadyTopic;
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

    public String getTtsChunkCodec() {
        return ttsChunkCodec;
    }

    public void setTtsChunkCodec(String ttsChunkCodec) {
        this.ttsChunkCodec = ttsChunkCodec;
    }

    public int getTtsChunkSampleRate() {
        return ttsChunkSampleRate;
    }

    public void setTtsChunkSampleRate(int ttsChunkSampleRate) {
        this.ttsChunkSampleRate = ttsChunkSampleRate;
    }

    public String getTtsReadyPlaybackUrlPrefix() {
        return ttsReadyPlaybackUrlPrefix;
    }

    public void setTtsReadyPlaybackUrlPrefix(String ttsReadyPlaybackUrlPrefix) {
        this.ttsReadyPlaybackUrlPrefix = ttsReadyPlaybackUrlPrefix;
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
