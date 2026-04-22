package com.kafkaasr.control.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisTenantPolicyRepository implements TenantPolicyRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TenantPolicyStoreProperties storeProperties;

    public RedisTenantPolicyRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            TenantPolicyStoreProperties storeProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.storeProperties = storeProperties;
    }

    @Override
    public TenantPolicyState findByTenantId(String tenantId) {
        String payload = redisTemplate.opsForValue().get(key(tenantId));
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, TenantPolicyState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize tenant policy for " + tenantId, exception);
        }
    }

    @Override
    public boolean createIfAbsent(TenantPolicyState state) {
        Boolean created = redisTemplate.opsForValue().setIfAbsent(
                key(state.tenantId()),
                serialize(state),
                storeProperties.getTtl());
        return Boolean.TRUE.equals(created);
    }

    @Override
    public void save(TenantPolicyState state) {
        redisTemplate.opsForValue().set(
                key(state.tenantId()),
                serialize(state),
                storeProperties.getTtl());
    }

    private String serialize(TenantPolicyState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize tenant policy for " + state.tenantId(), exception);
        }
    }

    private String key(String tenantId) {
        return storeProperties.getKeyPrefix() + tenantId;
    }
}
