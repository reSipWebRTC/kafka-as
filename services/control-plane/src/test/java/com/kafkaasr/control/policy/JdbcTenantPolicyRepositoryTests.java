package com.kafkaasr.control.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcTenantPolicyRepositoryTests {

    private JdbcTenantPolicyRepository repository;
    private TenantPolicyStoreProperties storeProperties;

    @BeforeEach
    void setUp() {
        storeProperties = new TenantPolicyStoreProperties();
        storeProperties.setBackend("jdbc");
        storeProperties.setJdbcCurrentTable("control_tenant_policy_current");
        storeProperties.setJdbcHistoryTable("control_tenant_policy_history");
        storeProperties.setHistoryMaxEntries(2);

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:control_policy_repo_"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        repository = new JdbcTenantPolicyRepository(jdbcTemplate, new ObjectMapper(), storeProperties);
    }

    @Test
    void createIfAbsentAndFindByTenantId() {
        TenantPolicyState first = state("tenant-a", 1L, "en-US", "TRANSLATION");

        boolean created = repository.createIfAbsent(first);
        TenantPolicyState loaded = repository.findByTenantId("tenant-a");

        assertTrue(created);
        assertNotNull(loaded);
        assertEquals(1L, loaded.version());
        assertEquals("en-US", loaded.targetLang());
    }

    @Test
    void createIfAbsentReturnsFalseWhenTenantAlreadyExists() {
        TenantPolicyState first = state("tenant-a", 1L, "en-US", "TRANSLATION");
        TenantPolicyState second = state("tenant-a", 2L, "ja-JP", "TRANSLATION");

        assertTrue(repository.createIfAbsent(first));
        assertFalse(repository.createIfAbsent(second));
    }

    @Test
    void saveUpdatesExistingTenantPolicy() {
        TenantPolicyState first = state("tenant-a", 1L, "en-US", "TRANSLATION");
        TenantPolicyState updated = state("tenant-a", 2L, "ja-JP", "SMART_HOME");

        assertTrue(repository.createIfAbsent(first));
        repository.save(updated);
        TenantPolicyState loaded = repository.findByTenantId("tenant-a");

        assertNotNull(loaded);
        assertEquals(2L, loaded.version());
        assertEquals("ja-JP", loaded.targetLang());
        assertEquals("SMART_HOME", loaded.sessionMode());
    }

    @Test
    void appendHistorySupportsLatestAndVersionLookup() {
        TenantPolicyState version1 = state("tenant-a", 1L, "en-US", "TRANSLATION");
        TenantPolicyState version2 = state("tenant-a", 2L, "ja-JP", "TRANSLATION");

        repository.appendHistory(version1);
        repository.appendHistory(version2);

        TenantPolicyState latest = repository.findLatestHistory("tenant-a");
        TenantPolicyState byVersion = repository.findHistoryByVersion("tenant-a", 1L);

        assertNotNull(latest);
        assertEquals(2L, latest.version());
        assertNotNull(byVersion);
        assertEquals(1L, byVersion.version());
        assertEquals("en-US", byVersion.targetLang());
    }

    @Test
    void appendHistoryTrimsOldEntriesByConfiguredLimit() {
        repository.appendHistory(state("tenant-a", 1L, "en-US", "TRANSLATION"));
        repository.appendHistory(state("tenant-a", 2L, "ja-JP", "TRANSLATION"));
        repository.appendHistory(state("tenant-a", 3L, "fr-FR", "TRANSLATION"));

        assertNull(repository.findHistoryByVersion("tenant-a", 1L));
        assertNotNull(repository.findHistoryByVersion("tenant-a", 2L));
        assertNotNull(repository.findHistoryByVersion("tenant-a", 3L));
    }

    @Test
    void removeLatestHistoryDeletesNewestVersionOnly() {
        repository.appendHistory(state("tenant-a", 1L, "en-US", "TRANSLATION"));
        repository.appendHistory(state("tenant-a", 2L, "ja-JP", "TRANSLATION"));

        repository.removeLatestHistory("tenant-a");

        assertNull(repository.findHistoryByVersion("tenant-a", 2L));
        assertNotNull(repository.findHistoryByVersion("tenant-a", 1L));
    }

    private TenantPolicyState state(String tenantId, long version, String targetLang, String sessionMode) {
        return new TenantPolicyState(
                tenantId,
                "zh-CN",
                targetLang,
                "funasr-v1",
                "mt-v1",
                "voice-a",
                200,
                3000,
                true,
                sessionMode,
                false,
                0,
                false,
                30000L,
                3,
                200L,
                ".dlq",
                version,
                1713744000000L + version);
    }
}

