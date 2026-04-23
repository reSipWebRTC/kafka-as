package com.kafkaasr.control.policy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "control.policy-store")
public class TenantPolicyStoreProperties {

    private String keyPrefix = "control:tenant-policy:";
    private String historyKeyPrefix = "control:tenant-policy:history:";
    private Duration ttl = Duration.ofHours(12);
    private int historyMaxEntries = 20;

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public String getHistoryKeyPrefix() {
        return historyKeyPrefix;
    }

    public void setHistoryKeyPrefix(String historyKeyPrefix) {
        this.historyKeyPrefix = historyKeyPrefix;
    }

    public int getHistoryMaxEntries() {
        return historyMaxEntries;
    }

    public void setHistoryMaxEntries(int historyMaxEntries) {
        this.historyMaxEntries = historyMaxEntries;
    }
}
