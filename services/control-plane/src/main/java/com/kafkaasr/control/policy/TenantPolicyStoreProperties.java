package com.kafkaasr.control.policy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "control.policy-store")
public class TenantPolicyStoreProperties {

    private String backend = "redis";
    private String keyPrefix = "control:tenant-policy:";
    private String historyKeyPrefix = "control:tenant-policy:history:";
    private String distributionStatusKeyPrefix = "control:tenant-policy-distribution:status:";
    private String distributionStatusIndexPrefix = "control:tenant-policy-distribution:index:";
    private String jdbcCurrentTable = "control_tenant_policy_current";
    private String jdbcHistoryTable = "control_tenant_policy_history";
    private String jdbcDistributionStatusTable = "control_tenant_policy_distribution_status";
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

    public String getDistributionStatusKeyPrefix() {
        return distributionStatusKeyPrefix;
    }

    public void setDistributionStatusKeyPrefix(String distributionStatusKeyPrefix) {
        this.distributionStatusKeyPrefix = distributionStatusKeyPrefix;
    }

    public String getDistributionStatusIndexPrefix() {
        return distributionStatusIndexPrefix;
    }

    public void setDistributionStatusIndexPrefix(String distributionStatusIndexPrefix) {
        this.distributionStatusIndexPrefix = distributionStatusIndexPrefix;
    }

    public String getJdbcDistributionStatusTable() {
        return jdbcDistributionStatusTable;
    }

    public void setJdbcDistributionStatusTable(String jdbcDistributionStatusTable) {
        this.jdbcDistributionStatusTable = jdbcDistributionStatusTable;
    }

    public int getHistoryMaxEntries() {
        return historyMaxEntries;
    }

    public void setHistoryMaxEntries(int historyMaxEntries) {
        this.historyMaxEntries = historyMaxEntries;
    }
}
