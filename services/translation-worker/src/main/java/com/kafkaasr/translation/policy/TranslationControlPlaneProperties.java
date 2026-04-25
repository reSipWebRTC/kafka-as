package com.kafkaasr.translation.policy;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "translation.control-plane")
public class TranslationControlPlaneProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:8085";

    @NotNull
    private Duration requestTimeout = Duration.ofMillis(800);

    @NotNull
    private Duration policyCacheTtl = Duration.ofSeconds(30);

    private boolean fallbackFailOpen = true;
    private String policyChangedTopic = "tenant.policy.changed";
    private String policyDistributionResultTopic = "tenant.policy.distribution.result";
    private String distributionRegion = "local";
    private String distributionProducerId = "translation-worker";

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

    public String getPolicyDistributionResultTopic() {
        return policyDistributionResultTopic;
    }

    public void setPolicyDistributionResultTopic(String policyDistributionResultTopic) {
        this.policyDistributionResultTopic = policyDistributionResultTopic;
    }

    public String getDistributionRegion() {
        return distributionRegion;
    }

    public void setDistributionRegion(String distributionRegion) {
        this.distributionRegion = distributionRegion;
    }

    public String getDistributionProducerId() {
        return distributionProducerId;
    }

    public void setDistributionProducerId(String distributionProducerId) {
        this.distributionProducerId = distributionProducerId;
    }
}
