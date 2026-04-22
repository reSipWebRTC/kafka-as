package com.kafkaasr.orchestrator.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private String serialize(SessionState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize session state for " + state.sessionId(), exception);
        }
    }

    private String key(String sessionId) {
        return storeProperties.getKeyPrefix() + sessionId;
    }
}
