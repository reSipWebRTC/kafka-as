package com.kafkaasr.control.distribution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.control.policy.TenantPolicyStoreProperties;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "control.policy-store.backend", havingValue = "jdbc")
public class JdbcTenantPolicyDistributionStatusRepository implements TenantPolicyDistributionStatusRepository {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String table;

    public JdbcTenantPolicyDistributionStatusRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TenantPolicyStoreProperties storeProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.table = normalizeSqlIdentifier(
                storeProperties.getJdbcDistributionStatusTable(),
                "control.policy-store.jdbc-distribution-status-table");
        initializeSchema();
    }

    @Override
    public void save(TenantPolicyDistributionExecutionState state) {
        long nowMs = System.currentTimeMillis();
        String updateSql = "UPDATE " + table + " SET payload_json = ?, status = ?, reason_code = ?, reason_message = ?,"
                + " applied_at_ms = ?, source_event_id = ?, updated_ts_ms = ?"
                + " WHERE tenant_id = ? AND policy_version = ? AND service = ? AND region = ?";
        int updated = jdbcTemplate.update(
                updateSql,
                serialize(state),
                state.status(),
                state.reasonCode(),
                state.reasonMessage(),
                state.appliedAtMs(),
                state.sourceEventId(),
                nowMs,
                state.tenantId(),
                state.policyVersion(),
                state.service(),
                state.region());
        if (updated > 0) {
            return;
        }

        String insertSql = "INSERT INTO " + table
                + " (tenant_id, policy_version, service, region, status, reason_code, reason_message,"
                + " applied_at_ms, source_event_id, updated_ts_ms, payload_json)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            jdbcTemplate.update(
                    insertSql,
                    state.tenantId(),
                    state.policyVersion(),
                    state.service(),
                    state.region(),
                    state.status(),
                    state.reasonCode(),
                    state.reasonMessage(),
                    state.appliedAtMs(),
                    state.sourceEventId(),
                    nowMs,
                    serialize(state));
        } catch (DuplicateKeyException ignored) {
            jdbcTemplate.update(
                    updateSql,
                    serialize(state),
                    state.status(),
                    state.reasonCode(),
                    state.reasonMessage(),
                    state.appliedAtMs(),
                    state.sourceEventId(),
                    nowMs,
                    state.tenantId(),
                    state.policyVersion(),
                    state.service(),
                    state.region());
        }
    }

    @Override
    public List<TenantPolicyDistributionExecutionState> findByTenantAndPolicyVersion(String tenantId, long policyVersion) {
        String sql = "SELECT payload_json FROM " + table
                + " WHERE tenant_id = ? AND policy_version = ?"
                + " ORDER BY service ASC, region ASC";
        return jdbcTemplate.query(
                sql,
                (resultSet, rowNum) -> deserialize(resultSet.getString(1), tenantId),
                tenantId,
                policyVersion);
    }

    private void initializeSchema() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "tenant_id VARCHAR(255) NOT NULL,"
                + "policy_version BIGINT NOT NULL,"
                + "service VARCHAR(255) NOT NULL,"
                + "region VARCHAR(255) NOT NULL,"
                + "status VARCHAR(32) NOT NULL,"
                + "reason_code VARCHAR(255),"
                + "reason_message CLOB,"
                + "applied_at_ms BIGINT NOT NULL,"
                + "source_event_id VARCHAR(255) NOT NULL,"
                + "updated_ts_ms BIGINT NOT NULL,"
                + "payload_json CLOB NOT NULL,"
                + "PRIMARY KEY (tenant_id, policy_version, service, region)"
                + ")");
    }

    private String normalizeSqlIdentifier(String rawIdentifier, String propertyName) {
        if (rawIdentifier == null || rawIdentifier.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
        if (!SQL_IDENTIFIER.matcher(rawIdentifier).matches()) {
            throw new IllegalStateException(propertyName + " contains invalid SQL identifier: " + rawIdentifier);
        }
        return rawIdentifier;
    }

    private String serialize(TenantPolicyDistributionExecutionState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize distribution status for "
                            + state.tenantId()
                            + " version="
                            + state.policyVersion(),
                    exception);
        }
    }

    private TenantPolicyDistributionExecutionState deserialize(String payload, String tenantId) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, TenantPolicyDistributionExecutionState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to deserialize distribution status for " + tenantId,
                    exception);
        }
    }
}
