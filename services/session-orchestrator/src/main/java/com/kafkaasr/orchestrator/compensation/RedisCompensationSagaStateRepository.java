package com.kafkaasr.orchestrator.compensation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisCompensationSagaStateRepository implements CompensationSagaStateRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CompensationSagaProperties properties;

    public RedisCompensationSagaStateRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            CompensationSagaProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public boolean begin(String eventId, String actionType) {
        SagaState current = find(eventId);
        if (current != null && "SUCCEEDED".equals(current.status())) {
            return false;
        }

        int nextAttempts = current == null ? 1 : current.attempts() + 1;
        save(new SagaState(
                eventId,
                actionType,
                "PROCESSING",
                "IN_PROGRESS",
                nextAttempts,
                System.currentTimeMillis()));
        return true;
    }

    @Override
    public void markSucceeded(String eventId, String actionType, String code) {
        SagaState current = find(eventId);
        int attempts = current == null ? 1 : Math.max(1, current.attempts());
        save(new SagaState(
                eventId,
                actionType,
                "SUCCEEDED",
                code,
                attempts,
                System.currentTimeMillis()));
    }

    @Override
    public void markFailed(String eventId, String actionType, String code) {
        SagaState current = find(eventId);
        int attempts = current == null ? 1 : Math.max(1, current.attempts());
        save(new SagaState(
                eventId,
                actionType,
                "FAILED",
                code,
                attempts,
                System.currentTimeMillis()));
    }

    private SagaState find(String eventId) {
        String payload = redisTemplate.opsForValue().get(key(eventId));
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, SagaState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize compensation saga state for " + eventId, exception);
        }
    }

    private void save(SagaState state) {
        try {
            redisTemplate.opsForValue().set(
                    key(state.eventId()),
                    objectMapper.writeValueAsString(state),
                    properties.getStateTtl());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize compensation saga state for " + state.eventId(),
                    exception);
        }
    }

    private String key(String eventId) {
        return properties.getStateKeyPrefix() + eventId;
    }

    record SagaState(
            String eventId,
            String actionType,
            String status,
            String code,
            int attempts,
            long updatedAtMs) {
    }
}
