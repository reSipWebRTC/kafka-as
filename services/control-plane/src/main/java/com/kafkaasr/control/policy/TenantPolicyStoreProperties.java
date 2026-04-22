package com.kafkaasr.control.policy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "control.policy-store")
public class TenantPolicyStoreProperties {

    private String keyPrefix = "control:tenant-policy:";
    private Duration ttl = Duration.ofHours(12);

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
}
