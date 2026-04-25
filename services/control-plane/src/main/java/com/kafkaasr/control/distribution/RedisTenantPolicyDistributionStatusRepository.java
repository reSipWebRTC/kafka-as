package com.kafkaasr.control.distribution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.control.policy.TenantPolicyStoreProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "control.policy-store.backend", havingValue = "redis", matchIfMissing = true)
public class RedisTenantPolicyDistributionStatusRepository implements TenantPolicyDistributionStatusRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TenantPolicyStoreProperties storeProperties;

    public RedisTenantPolicyDistributionStatusRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            TenantPolicyStoreProperties storeProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.storeProperties = storeProperties;
    }

    @Override
    public void save(TenantPolicyDistributionExecutionState state) {
        String itemKey = itemKey(state.tenantId(), state.policyVersion(), state.service(), state.region());
        redisTemplate.opsForValue().set(
                itemKey,
                serialize(state),
                storeProperties.getTtl());

        String indexKey = indexKey(state.tenantId(), state.policyVersion());
        redisTemplate.opsForSet().add(indexKey, itemKey);
        redisTemplate.expire(indexKey, storeProperties.getTtl());
    }

    @Override
    public List<TenantPolicyDistributionExecutionState> findByTenantAndPolicyVersion(String tenantId, long policyVersion) {
        Set<String> rawMembers = redisTemplate.opsForSet().members(indexKey(tenantId, policyVersion));
        if (rawMembers == null || rawMembers.isEmpty()) {
            return List.of();
        }

        List<String> members = new ArrayList<>(new LinkedHashSet<>(rawMembers));
        List<String> payloads = redisTemplate.opsForValue().multiGet(members);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }

        List<TenantPolicyDistributionExecutionState> states = new ArrayList<>();
        for (String payload : payloads) {
            TenantPolicyDistributionExecutionState state = deserialize(payload, tenantId);
            if (state != null) {
                states.add(state);
            }
        }
        states.sort(Comparator
                .comparing(TenantPolicyDistributionExecutionState::service)
                .thenComparing(TenantPolicyDistributionExecutionState::region));
        return List.copyOf(states);
    }

    private String itemKey(String tenantId, long policyVersion, String service, String region) {
        return storeProperties.getDistributionStatusKeyPrefix()
                + tenantId
                + ":"
                + policyVersion
                + ":"
                + service
                + ":"
                + region;
    }

    private String indexKey(String tenantId, long policyVersion) {
        return storeProperties.getDistributionStatusIndexPrefix()
                + tenantId
                + ":"
                + policyVersion;
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
