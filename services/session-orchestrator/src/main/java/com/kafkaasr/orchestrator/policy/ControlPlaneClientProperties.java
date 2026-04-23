package com.kafkaasr.orchestrator.policy;

import java.time.Duration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "orchestrator.control-plane")
public class ControlPlaneClientProperties {

    private String baseUrl = "http://localhost:8085";

    @NotNull
    private Duration requestTimeout = Duration.ofSeconds(2);

    @Min(1)
    private int circuitBreakerFailureThreshold = 3;

    @NotNull
    private Duration circuitBreakerOpenDuration = Duration.ofSeconds(5);

    @NotNull
    private Duration policyCacheTtl = Duration.ofSeconds(30);

    private boolean fallbackFailOpen = false;
    private String policyChangedTopic = "tenant.policy.changed";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getCircuitBreakerFailureThreshold() {
        return circuitBreakerFailureThreshold;
    }

    public void setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold) {
        this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
    }

    public Duration getCircuitBreakerOpenDuration() {
        return circuitBreakerOpenDuration;
    }

    public void setCircuitBreakerOpenDuration(Duration circuitBreakerOpenDuration) {
        this.circuitBreakerOpenDuration = circuitBreakerOpenDuration;
    }

    public Duration getPolicyCacheTtl() {
        return policyCacheTtl;
    }

    public void setPolicyCacheTtl(Duration policyCacheTtl) {
        this.policyCacheTtl = policyCacheTtl;
    }

    public boolean isFallbackFailOpen() {
        return fallbackFailOpen;
    }

    public void setFallbackFailOpen(boolean fallbackFailOpen) {
        this.fallbackFailOpen = fallbackFailOpen;
    }

    public String getPolicyChangedTopic() {
        return policyChangedTopic;
    }

    public void setPolicyChangedTopic(String policyChangedTopic) {
        this.policyChangedTopic = policyChangedTopic;
    }
}
