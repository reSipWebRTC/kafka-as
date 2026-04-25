package com.kafkaasr.orchestrator.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestrator.session-orchestration")
public class SessionOrchestrationProperties {

    private boolean aggregationEnabled = true;
    private Duration idleTimeout = Duration.ofMinutes(2);
    private Duration hardTimeout = Duration.ofMinutes(30);
    private Duration scanInterval = Duration.ofSeconds(1);
    private Duration closeTimeout = Duration.ofSeconds(3);
    private String idleTimeoutReason = "timeout.idle";
    private String hardTimeoutReason = "timeout.hard";

    public boolean isAggregationEnabled() {
        return aggregationEnabled;
    }

    public void setAggregationEnabled(boolean aggregationEnabled) {
        this.aggregationEnabled = aggregationEnabled;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getHardTimeout() {
        return hardTimeout;
    }

    public void setHardTimeout(Duration hardTimeout) {
        this.hardTimeout = hardTimeout;
    }

    public Duration getScanInterval() {
        return scanInterval;
    }

    public void setScanInterval(Duration scanInterval) {
        this.scanInterval = scanInterval;
    }

    public Duration getCloseTimeout() {
        return closeTimeout;
    }

    public void setCloseTimeout(Duration closeTimeout) {
        this.closeTimeout = closeTimeout;
    }

    public String getIdleTimeoutReason() {
        return idleTimeoutReason;
    }

    public void setIdleTimeoutReason(String idleTimeoutReason) {
        this.idleTimeoutReason = idleTimeoutReason;
    }

    public String getHardTimeoutReason() {
        return hardTimeoutReason;
    }

    public void setHardTimeoutReason(String hardTimeoutReason) {
        this.hardTimeoutReason = hardTimeoutReason;
    }
}
