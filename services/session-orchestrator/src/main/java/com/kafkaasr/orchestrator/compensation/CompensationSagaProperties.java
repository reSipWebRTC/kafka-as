package com.kafkaasr.orchestrator.compensation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestrator.compensation-saga")
public class CompensationSagaProperties {

    private boolean enabled = true;
    private int maxAttempts = 3;
    private Duration retryBackoff = Duration.ofMillis(200);
    private String replayOutputMode = "source-topic";
    private String replayFixedTopic = "";
    private String replayKeyField = "sessionId";
    private Duration replaySendTimeout = Duration.ofSeconds(3);
    private Duration sessionCloseTimeout = Duration.ofSeconds(3);
    private String closeReasonPrefix = "compensation.saga";
    private boolean publishAudit = true;
    private String stateKeyPrefix = "orchestrator:compensation:saga:";
    private Duration stateTtl = Duration.ofDays(7);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public String getReplayOutputMode() {
        return replayOutputMode;
    }

    public void setReplayOutputMode(String replayOutputMode) {
        this.replayOutputMode = replayOutputMode;
    }

    public String getReplayFixedTopic() {
        return replayFixedTopic;
    }

    public void setReplayFixedTopic(String replayFixedTopic) {
        this.replayFixedTopic = replayFixedTopic;
    }

    public String getReplayKeyField() {
        return replayKeyField;
    }

    public void setReplayKeyField(String replayKeyField) {
        this.replayKeyField = replayKeyField;
    }

    public Duration getReplaySendTimeout() {
        return replaySendTimeout;
    }

    public void setReplaySendTimeout(Duration replaySendTimeout) {
        this.replaySendTimeout = replaySendTimeout;
    }

    public Duration getSessionCloseTimeout() {
        return sessionCloseTimeout;
    }

    public void setSessionCloseTimeout(Duration sessionCloseTimeout) {
        this.sessionCloseTimeout = sessionCloseTimeout;
    }

    public String getCloseReasonPrefix() {
        return closeReasonPrefix;
    }

    public void setCloseReasonPrefix(String closeReasonPrefix) {
        this.closeReasonPrefix = closeReasonPrefix;
    }

    public boolean isPublishAudit() {
        return publishAudit;
    }

    public void setPublishAudit(boolean publishAudit) {
        this.publishAudit = publishAudit;
    }

    public String getStateKeyPrefix() {
        return stateKeyPrefix;
    }

    public void setStateKeyPrefix(String stateKeyPrefix) {
        this.stateKeyPrefix = stateKeyPrefix;
    }

    public Duration getStateTtl() {
        return stateTtl;
    }

    public void setStateTtl(Duration stateTtl) {
        this.stateTtl = stateTtl;
    }
}
