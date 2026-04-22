package com.kafkaasr.control.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisTenantPolicyRepositoryTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisTenantPolicyRepository repository;
    private ObjectMapper objectMapper;
    private TenantPolicyStoreProperties storeProperties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        storeProperties = new TenantPolicyStoreProperties();
        storeProperties.setKeyPrefix("control:tenant-policy:");
        storeProperties.setTtl(Duration.ofHours(12));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        repository = new RedisTenantPolicyRepository(redisTemplate, objectMapper, storeProperties);
    }

    @Test
    void findByTenantIdReturnsNullWhenMissing() {
        when(valueOperations.get("control:tenant-policy:tenant-a")).thenReturn(null);

        assertNull(repository.findByTenantId("tenant-a"));
    }

    @Test
    void findByTenantIdDeserializesStoredPayload() throws Exception {
        TenantPolicyState state = new TenantPolicyState(
                "tenant-a",
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                200,
                2000,
                true,
                true,
                20,
                true,
                45000L,
                3L,
                1713744000000L);
        String payload = objectMapper.writeValueAsString(state);
        when(valueOperations.get("control:tenant-policy:tenant-a")).thenReturn(payload);

        TenantPolicyState loaded = repository.findByTenantId("tenant-a");

        assertEquals("tenant-a", loaded.tenantId());
        assertEquals(3L, loaded.version());
        assertEquals("funasr-v1", loaded.asrModel());
    }

    @Test
    void createIfAbsentWritesWithTtl() {
        TenantPolicyState state = new TenantPolicyState(
                "tenant-a",
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                200,
                2000,
                true,
                false,
                0,
                false,
                30000L,
                1L,
                1713744000000L);
        when(valueOperations.setIfAbsent(eq("control:tenant-policy:tenant-a"), any(), eq(Duration.ofHours(12))))
                .thenReturn(Boolean.TRUE);

        boolean created = repository.createIfAbsent(state);

        assertTrue(created);
    }

    @Test
    void saveWritesWithTtl() {
        TenantPolicyState state = new TenantPolicyState(
                "tenant-a",
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                200,
                2000,
                true,
                false,
                0,
                false,
                30000L,
                2L,
                1713744001000L);

        repository.save(state);

        verify(valueOperations).set(eq("control:tenant-policy:tenant-a"), any(), eq(Duration.ofHours(12)));
    }
}
