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
        return deserialize(payload, tenantId);
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

    @Override
    public void appendHistory(TenantPolicyState state) {
        String historyKey = historyKey(state.tenantId());
        redisTemplate.opsForList().rightPush(historyKey, serialize(state));
        if (storeProperties.getHistoryMaxEntries() > 0) {
            redisTemplate.opsForList().trim(historyKey, -storeProperties.getHistoryMaxEntries(), -1);
        }
        redisTemplate.expire(historyKey, storeProperties.getTtl());
    }

    @Override
    public TenantPolicyState findLatestHistory(String tenantId) {
        String payload = redisTemplate.opsForList().index(historyKey(tenantId), -1);
        return deserialize(payload, tenantId);
    }

    @Override
    public void removeLatestHistory(String tenantId) {
        redisTemplate.opsForList().rightPop(historyKey(tenantId));
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

    private String key(String tenantId) {
        return storeProperties.getKeyPrefix() + tenantId;
    }

    private String historyKey(String tenantId) {
        return storeProperties.getHistoryKeyPrefix() + tenantId;
    }
}
