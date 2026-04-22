package com.kafkaasr.gateway.ws.downlink;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.downlink")
public class GatewayDownlinkProperties {

    private boolean enabled = true;

    @NotBlank
    private String consumerGroupId = "speech-gateway-downlink";

    @NotBlank
    private String asrPartialTopic = "asr.partial";

    @NotBlank
    private String translationResultTopic = "translation.result";

    @NotBlank
    private String sessionControlTopic = "session.control";

    @Min(1)
    private int retryMaxAttempts = 3;

    @Min(1)
    private long retryBackoffMs = 200L;

    @NotBlank
    private String dlqTopicSuffix = ".dlq";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getConsumerGroupId() {
        return consumerGroupId;
    }

    public void setConsumerGroupId(String consumerGroupId) {
        this.consumerGroupId = consumerGroupId;
    }

    public String getAsrPartialTopic() {
        return asrPartialTopic;
    }

    public void setAsrPartialTopic(String asrPartialTopic) {
        this.asrPartialTopic = asrPartialTopic;
    }

    public String getTranslationResultTopic() {
        return translationResultTopic;
    }

    public void setTranslationResultTopic(String translationResultTopic) {
        this.translationResultTopic = translationResultTopic;
    }

    public String getSessionControlTopic() {
        return sessionControlTopic;
    }

    public void setSessionControlTopic(String sessionControlTopic) {
        this.sessionControlTopic = sessionControlTopic;
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
}
