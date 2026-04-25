package com.kafkaasr.control.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.control.policy.TenantPolicyStoreProperties;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisTenantPolicyDistributionStatusRepositoryTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private RedisTenantPolicyDistributionStatusRepository repository;
    private ObjectMapper objectMapper;
    private TenantPolicyStoreProperties storeProperties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        storeProperties = new TenantPolicyStoreProperties();
        storeProperties.setTtl(Duration.ofHours(12));
        storeProperties.setDistributionStatusKeyPrefix("control:tenant-policy-distribution:status:");
        storeProperties.setDistributionStatusIndexPrefix("control:tenant-policy-distribution:index:");

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        repository = new RedisTenantPolicyDistributionStatusRepository(redisTemplate, objectMapper, storeProperties);
    }

    @Test
    void saveWritesItemAndIndexWithTtl() {
        TenantPolicyDistributionExecutionState state = new TenantPolicyDistributionExecutionState(
                "tenant-a",
                3L,
                "asr-worker",
                "local",
                "APPLIED",
                null,
                null,
                1713745000000L,
                "evt-src-1",
                "evt-res-1",
                "asr-worker",
                1713745000000L);

        repository.save(state);

        verify(valueOperations).set(eq("control:tenant-policy-distribution:status:tenant-a:3:asr-worker:local"), any(), eq(Duration.ofHours(12)));
        verify(setOperations).add(
                "control:tenant-policy-distribution:index:tenant-a:3",
                "control:tenant-policy-distribution:status:tenant-a:3:asr-worker:local");
        verify(redisTemplate).expire("control:tenant-policy-distribution:index:tenant-a:3", Duration.ofHours(12));
    }

    @Test
    void findByTenantAndPolicyVersionLoadsAndSortsStates() throws Exception {
        String indexKey = "control:tenant-policy-distribution:index:tenant-a:3";
        String key1 = "control:tenant-policy-distribution:status:tenant-a:3:translation-worker:local";
        String key2 = "control:tenant-policy-distribution:status:tenant-a:3:asr-worker:local";

        when(setOperations.members(indexKey)).thenReturn(new LinkedHashSet<>(List.of(key1, key2)));
        when(valueOperations.multiGet(List.of(key1, key2))).thenReturn(List.of(
                objectMapper.writeValueAsString(new TenantPolicyDistributionExecutionState(
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
                        1713745000010L)),
                objectMapper.writeValueAsString(new TenantPolicyDistributionExecutionState(
                        "tenant-a",
                        3L,
                        "asr-worker",
                        "local",
                        "APPLIED",
                        null,
                        null,
                        1713745000000L,
                        "evt-src-1",
                        "evt-res-1",
                        "asr-worker",
                        1713745000000L))));

        List<TenantPolicyDistributionExecutionState> states =
                repository.findByTenantAndPolicyVersion("tenant-a", 3L);

        assertEquals(2, states.size());
        assertEquals("asr-worker", states.get(0).service());
        assertEquals("translation-worker", states.get(1).service());
        assertTrue(states.stream().allMatch(state -> state.policyVersion() == 3L));
    }
}
