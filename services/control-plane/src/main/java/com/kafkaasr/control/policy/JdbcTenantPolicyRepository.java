package com.kafkaasr.control.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "control.policy-store.backend", havingValue = "jdbc")
public class JdbcTenantPolicyRepository implements TenantPolicyRepository {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TenantPolicyStoreProperties storeProperties;
    private final String currentTable;
    private final String historyTable;

    public JdbcTenantPolicyRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TenantPolicyStoreProperties storeProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.storeProperties = storeProperties;
        this.currentTable = normalizeSqlIdentifier(
                storeProperties.getJdbcCurrentTable(),
                "control.policy-store.jdbc-current-table");
        this.historyTable = normalizeSqlIdentifier(
                storeProperties.getJdbcHistoryTable(),
                "control.policy-store.jdbc-history-table");
        initializeSchema();
    }

    @Override
    public TenantPolicyState findByTenantId(String tenantId) {
        String sql = "SELECT payload_json FROM " + currentTable + " WHERE tenant_id = ?";
        String payload = jdbcTemplate.query(
                sql,
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                tenantId);
        return deserialize(payload, tenantId);
    }

    @Override
    public boolean createIfAbsent(TenantPolicyState state) {
        String sql = "INSERT INTO " + currentTable
                + " (tenant_id, payload_json, version, updated_at_ms) VALUES (?, ?, ?, ?)";
        try {
            return jdbcTemplate.update(
                            sql,
                            state.tenantId(),
                            serialize(state),
                            state.version(),
                            state.updatedAtMs())
                    > 0;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    public void save(TenantPolicyState state) {
        String updateSql = "UPDATE " + currentTable + " SET payload_json = ?, version = ?, updated_at_ms = ?"
                + " WHERE tenant_id = ?";
        int updated = jdbcTemplate.update(
                updateSql,
                serialize(state),
                state.version(),
                state.updatedAtMs(),
                state.tenantId());
        if (updated > 0) {
            return;
        }

        String insertSql = "INSERT INTO " + currentTable
                + " (tenant_id, payload_json, version, updated_at_ms) VALUES (?, ?, ?, ?)";
        try {
            jdbcTemplate.update(
                    insertSql,
                    state.tenantId(),
                    serialize(state),
                    state.version(),
                    state.updatedAtMs());
        } catch (DuplicateKeyException ignored) {
            jdbcTemplate.update(
                    updateSql,
                    serialize(state),
                    state.version(),
                    state.updatedAtMs(),
                    state.tenantId());
        }
    }

    @Override
    public void appendHistory(TenantPolicyState state) {
        String insertSql = "INSERT INTO " + historyTable
                + " (tenant_id, policy_version, payload_json, updated_at_ms, created_at_ms) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(
                insertSql,
                state.tenantId(),
                state.version(),
                serialize(state),
                state.updatedAtMs(),
                System.currentTimeMillis());

        if (storeProperties.getHistoryMaxEntries() > 0) {
            trimHistory(state.tenantId(), storeProperties.getHistoryMaxEntries());
        }
    }

    @Override
    public TenantPolicyState findLatestHistory(String tenantId) {
        String sql = "SELECT payload_json FROM " + historyTable
                + " WHERE tenant_id = ? ORDER BY policy_version DESC LIMIT 1";
        String payload = jdbcTemplate.query(
                sql,
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                tenantId);
        return deserialize(payload, tenantId);
    }

    @Override
    public TenantPolicyState findHistoryByVersion(String tenantId, long version) {
        String sql = "SELECT payload_json FROM " + historyTable
                + " WHERE tenant_id = ? AND policy_version = ?";
        String payload = jdbcTemplate.query(
                sql,
                resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                tenantId,
                version);
        return deserialize(payload, tenantId);
    }

    @Override
    public void removeLatestHistory(String tenantId) {
        String latestVersionSql = "SELECT policy_version FROM " + historyTable
                + " WHERE tenant_id = ? ORDER BY policy_version DESC LIMIT 1";
        Long version = jdbcTemplate.query(
                latestVersionSql,
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
                tenantId);
        if (version == null) {
            return;
        }
        String deleteSql = "DELETE FROM " + historyTable + " WHERE tenant_id = ? AND policy_version = ?";
        jdbcTemplate.update(deleteSql, tenantId, version);
    }

    private void trimHistory(String tenantId, int maxEntries) {
        String sql = "DELETE FROM " + historyTable
                + " WHERE tenant_id = ?"
                + " AND policy_version NOT IN ("
                + "   SELECT policy_version FROM ("
                + "     SELECT policy_version FROM " + historyTable
                + " WHERE tenant_id = ? ORDER BY policy_version DESC LIMIT ?"
                + "   ) retained"
                + " )";
        jdbcTemplate.update(sql, tenantId, tenantId, maxEntries);
    }

    private void initializeSchema() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + currentTable + " ("
                + "tenant_id VARCHAR(255) PRIMARY KEY,"
                + "payload_json CLOB NOT NULL,"
                + "version BIGINT NOT NULL,"
                + "updated_at_ms BIGINT NOT NULL"
                + ")");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + historyTable + " ("
                + "tenant_id VARCHAR(255) NOT NULL,"
                + "policy_version BIGINT NOT NULL,"
                + "payload_json CLOB NOT NULL,"
                + "updated_at_ms BIGINT NOT NULL,"
                + "created_at_ms BIGINT NOT NULL,"
                + "PRIMARY KEY (tenant_id, policy_version)"
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

    private String serialize(TenantPolicyState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize tenant policy for " + state.tenantId(), exception);
        }
    }

    private TenantPolicyState deserialize(String payload, String tenantId) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, TenantPolicyState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize tenant policy for " + tenantId, exception);
        }
    }
}

