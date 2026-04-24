package com.kafkaasr.command.pipeline;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "command.smarthome")
public class SmartHomeClientProperties {

    private boolean enabled = true;

    @NotBlank
    private String baseUrl = "http://127.0.0.1:8000";

    @NotBlank
    private String commandPath = "/api/v1/command";

    @NotBlank
    private String confirmPath = "/api/v1/confirm";

    @Min(1)
    private long timeoutMs = 1500L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCommandPath() {
        return commandPath;
    }

    public void setCommandPath(String commandPath) {
        this.commandPath = commandPath;
    }

    public String getConfirmPath() {
        return confirmPath;
    }

    public void setConfirmPath(String confirmPath) {
        this.confirmPath = confirmPath;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
