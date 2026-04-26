package com.kafkaasr.command.events;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "command.kafka")
public class CommandKafkaProperties {

    private boolean enabled = true;

    @NotBlank
    private String asrFinalTopic = "asr.final";

    @NotBlank
    private String commandConfirmRequestTopic = "command.confirm.request";

    @NotBlank
    private String commandExecuteResultTopic = "command.execute.result";

    @NotBlank
    private String commandDispatchTopic = "command.dispatch";

    @NotBlank
    private String commandResultTopic = "command.result";

    @NotBlank
    private String producerId = "command-worker";

    private boolean idempotencyEnabled = true;

    @Min(1)
    private long idempotencyTtlMs = 300000L;

    @Min(1)
    private int retryMaxAttempts = 3;

    @Min(0)
    private long retryBackoffMs = 200L;

    @NotBlank
    private String dlqTopicSuffix = ".dlq";

    @NotBlank
    private String compensationTopic = "platform.compensation";

    @NotBlank
    private String executionMode = "CLIENT_BRIDGE";

    private boolean defaultConfirmRequired = true;

    @Min(1)
    private int defaultMaxConfirmRounds = 2;

    @Min(1)
    private int dispatchExpiresInSec = 30;

    @NotBlank
    private String defaultIntent = "CONTROL";

    @NotBlank
    private String defaultSubIntent = "SMART_HOME";

    private List<String> smartHomeTenantAllowlist = new ArrayList<>();

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

    public String getCommandConfirmRequestTopic() {
        return commandConfirmRequestTopic;
    }

    public void setCommandConfirmRequestTopic(String commandConfirmRequestTopic) {
        this.commandConfirmRequestTopic = commandConfirmRequestTopic;
    }

    public String getCommandExecuteResultTopic() {
        return commandExecuteResultTopic;
    }

    public void setCommandExecuteResultTopic(String commandExecuteResultTopic) {
        this.commandExecuteResultTopic = commandExecuteResultTopic;
    }

    public String getCommandDispatchTopic() {
        return commandDispatchTopic;
    }

    public void setCommandDispatchTopic(String commandDispatchTopic) {
        this.commandDispatchTopic = commandDispatchTopic;
    }

    public String getCommandResultTopic() {
        return commandResultTopic;
    }

    public void setCommandResultTopic(String commandResultTopic) {
        this.commandResultTopic = commandResultTopic;
    }

    public String getProducerId() {
        return producerId;
    }

    public void setProducerId(String producerId) {
        this.producerId = producerId;
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

    public String getCompensationTopic() {
        return compensationTopic;
    }

    public void setCompensationTopic(String compensationTopic) {
        this.compensationTopic = compensationTopic;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public boolean isDefaultConfirmRequired() {
        return defaultConfirmRequired;
    }

    public void setDefaultConfirmRequired(boolean defaultConfirmRequired) {
        this.defaultConfirmRequired = defaultConfirmRequired;
    }

    public int getDefaultMaxConfirmRounds() {
        return defaultMaxConfirmRounds;
    }

    public void setDefaultMaxConfirmRounds(int defaultMaxConfirmRounds) {
        this.defaultMaxConfirmRounds = defaultMaxConfirmRounds;
    }

    public int getDispatchExpiresInSec() {
        return dispatchExpiresInSec;
    }

    public void setDispatchExpiresInSec(int dispatchExpiresInSec) {
        this.dispatchExpiresInSec = dispatchExpiresInSec;
    }

    public String getDefaultIntent() {
        return defaultIntent;
    }

    public void setDefaultIntent(String defaultIntent) {
        this.defaultIntent = defaultIntent;
    }

    public String getDefaultSubIntent() {
        return defaultSubIntent;
    }

    public void setDefaultSubIntent(String defaultSubIntent) {
        this.defaultSubIntent = defaultSubIntent;
    }

    public List<String> getSmartHomeTenantAllowlist() {
        return smartHomeTenantAllowlist;
    }

    public void setSmartHomeTenantAllowlist(List<String> smartHomeTenantAllowlist) {
        this.smartHomeTenantAllowlist = smartHomeTenantAllowlist == null
                ? new ArrayList<>()
                : new ArrayList<>(smartHomeTenantAllowlist);
    }
}
