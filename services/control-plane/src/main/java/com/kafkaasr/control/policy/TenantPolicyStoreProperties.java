package com.kafkaasr.control.policy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "control.policy-store")
public class TenantPolicyStoreProperties {

    private String backend = "redis";
    private String keyPrefix = "control:tenant-policy:";
    private String historyKeyPrefix = "control:tenant-policy:history:";
    private String jdbcCurrentTable = "control_tenant_policy_current";
    private String jdbcHistoryTable = "control_tenant_policy_history";
    private Duration ttl = Duration.ofHours(12);
    private int historyMaxEntries = 20;

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

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

    public String getJdbcCurrentTable() {
        return jdbcCurrentTable;
    }

    public void setJdbcCurrentTable(String jdbcCurrentTable) {
        this.jdbcCurrentTable = jdbcCurrentTable;
    }

    public String getJdbcHistoryTable() {
        return jdbcHistoryTable;
    }

    public void setJdbcHistoryTable(String jdbcHistoryTable) {
        this.jdbcHistoryTable = jdbcHistoryTable;
    }

    public int getHistoryMaxEntries() {
        return historyMaxEntries;
    }

    public void setHistoryMaxEntries(int historyMaxEntries) {
        this.historyMaxEntries = historyMaxEntries;
    }
}
