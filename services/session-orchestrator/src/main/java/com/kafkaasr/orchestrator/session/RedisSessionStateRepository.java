package com.kafkaasr.orchestrator.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisSessionStateRepository implements SessionStateRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SessionStoreProperties storeProperties;

    public RedisSessionStateRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            SessionStoreProperties storeProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.storeProperties = storeProperties;
    }

    @Override
    public SessionState findBySessionId(String sessionId) {
        String payload = redisTemplate.opsForValue().get(key(sessionId));
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, SessionState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize session state for " + sessionId, exception);
        }
    }

    @Override
    public boolean createIfAbsent(SessionState state) {
        Boolean created = redisTemplate.opsForValue().setIfAbsent(
                key(state.sessionId()),
                serialize(state),
                storeProperties.getTtl());
        return Boolean.TRUE.equals(created);
    }

    @Override
    public void save(SessionState state) {
        redisTemplate.opsForValue().set(
                key(state.sessionId()),
                serialize(state),
                storeProperties.getTtl());
    }

    @Override
    public long countActiveSessionsByTenantId(String tenantId) {
        Set<String> keys = redisTemplate.keys(storeProperties.getKeyPrefix() + "*");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }

        long count = 0;
        for (String key : keys) {
            String payload = redisTemplate.opsForValue().get(key);
            if (payload == null || payload.isBlank()) {
                continue;
            }
            SessionState state = deserialize(payload, key);
            if (state.tenantId().equals(tenantId) && state.isActive()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public List<SessionState> findActiveSessions() {
        Set<String> keys = redisTemplate.keys(storeProperties.getKeyPrefix() + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        List<SessionState> activeSessions = new ArrayList<>();
        for (String key : keys) {
            String payload = redisTemplate.opsForValue().get(key);
            if (payload == null || payload.isBlank()) {
                continue;
            }
            SessionState state = deserialize(payload, key);
            if (state.isActive()) {
                activeSessions.add(state);
            }
        }
        return activeSessions;
    }

    private String serialize(SessionState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize session state for " + state.sessionId(), exception);
        }
    }

    private SessionState deserialize(String payload, String key) {
        try {
            return objectMapper.readValue(payload, SessionState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize session state at key " + key, exception);
        }
    }

    private String key(String sessionId) {
        return storeProperties.getKeyPrefix() + sessionId;
    }
}
