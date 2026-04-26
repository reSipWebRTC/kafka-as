package com.kafkaasr.command.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "command.state-store.provider", havingValue = "redis")
public class RedisCommandExecutionContextRepository implements CommandExecutionContextRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CommandStateStoreProperties storeProperties;

    public RedisCommandExecutionContextRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            CommandStateStoreProperties storeProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.storeProperties = storeProperties;
    }

    @Override
    public CommandExecutionContext findByExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return null;
        }
        String payload = redisTemplate.opsForValue().get(key(executionId));
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, CommandExecutionContext.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize command execution context: " + executionId, exception);
        }
    }

    @Override
    public void save(CommandExecutionContext context) {
        if (context == null || context.executionId() == null || context.executionId().isBlank()) {
            throw new IllegalArgumentException("execution context and executionId are required");
        }
        redisTemplate.opsForValue().set(
                key(context.executionId()),
                serialize(context),
                storeProperties.getTtl());
    }

    @Override
    public void delete(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        redisTemplate.delete(key(executionId));
    }

    private String serialize(CommandExecutionContext context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize command execution context: " + context.executionId(),
                    exception);
        }
    }

    private String key(String executionId) {
        return storeProperties.getKeyPrefix() + executionId;
    }
}
