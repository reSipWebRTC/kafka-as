package com.kafkaasr.tts.policy;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tts.control-plane")
public class TtsControlPlaneProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:8085";

    @NotNull
    private Duration requestTimeout = Duration.ofMillis(800);

    @NotNull
    private Duration policyCacheTtl = Duration.ofSeconds(30);

    private boolean fallbackFailOpen = true;
    private String policyChangedTopic = "tenant.policy.changed";

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

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
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
