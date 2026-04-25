package com.kafkaasr.control.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.control.policy.TenantPolicyStoreProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcTenantPolicyDistributionStatusRepositoryTests {

    private JdbcTenantPolicyDistributionStatusRepository repository;

    @BeforeEach
    void setUp() {
        TenantPolicyStoreProperties storeProperties = new TenantPolicyStoreProperties();
        storeProperties.setBackend("jdbc");
        storeProperties.setJdbcDistributionStatusTable("control_tenant_policy_distribution_status");

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:control_policy_distribution_"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        repository = new JdbcTenantPolicyDistributionStatusRepository(jdbcTemplate, new ObjectMapper(), storeProperties);
    }

    @Test
    void saveAndFindByTenantAndPolicyVersion() {
        repository.save(new TenantPolicyDistributionExecutionState(
                "tenant-a",
                3L,
                "translation-worker",
                "local",
                "APPLIED",
                null,
                null,
                1713745000010L,
                "evt-src-1",
                "evt-res-2",
                "translation-worker",
                1713745000010L));
        repository.save(new TenantPolicyDistributionExecutionState(
                "tenant-a",
                3L,
                "asr-worker",
                "local",
                "FAILED",
                "APPLY_FAILED",
                "timeout",
                1713745000000L,
                "evt-src-1",
                "evt-res-1",
                "asr-worker",
                1713745000000L));

        List<TenantPolicyDistributionExecutionState> states =
                repository.findByTenantAndPolicyVersion("tenant-a", 3L);

        assertEquals(2, states.size());
        assertEquals("asr-worker", states.get(0).service());
        assertEquals("translation-worker", states.get(1).service());
        assertTrue(states.stream().anyMatch(state -> "APPLY_FAILED".equals(state.reasonCode())));
    }

    @Test
    void saveUpdatesExistingCompositeKey() {
        repository.save(new TenantPolicyDistributionExecutionState(
                "tenant-a",
                3L,
                "asr-worker",
                "local",
                "FAILED",
                "APPLY_FAILED",
                "timeout",
                1713745000000L,
                "evt-src-1",
                "evt-res-1",
                "asr-worker",
                1713745000000L));

        repository.save(new TenantPolicyDistributionExecutionState(
                "tenant-a",
                3L,
                "asr-worker",
                "local",
                "APPLIED",
                null,
                null,
                1713745000100L,
                "evt-src-1",
                "evt-res-1b",
                "asr-worker",
                1713745000100L));

        List<TenantPolicyDistributionExecutionState> states =
                repository.findByTenantAndPolicyVersion("tenant-a", 3L);
        assertEquals(1, states.size());
        assertEquals("APPLIED", states.getFirst().status());
        assertEquals("evt-res-1b", states.getFirst().eventId());
    }
}
