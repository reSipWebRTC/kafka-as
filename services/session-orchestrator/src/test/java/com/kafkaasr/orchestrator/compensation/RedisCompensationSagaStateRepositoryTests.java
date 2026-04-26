package com.kafkaasr.orchestrator.compensation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
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
class RedisCompensationSagaStateRepositoryTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisCompensationSagaStateRepository repository;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CompensationSagaProperties properties = new CompensationSagaProperties();
        properties.setStateKeyPrefix("orchestrator:compensation:saga:");
        properties.setStateTtl(Duration.ofHours(1));
        repository = new RedisCompensationSagaStateRepository(redisTemplate, new ObjectMapper(), properties);
    }

    @Test
    void beginReturnsFalseForSucceededEvent() throws Exception {
        String key = "orchestrator:compensation:saga:evt-1";
        String succeededPayload = """
                {
                  "eventId": "evt-1",
                  "actionType": "REPLAY",
                  "status": "SUCCEEDED",
                  "code": "REPLAY_OK",
                  "attempts": 1,
                  "updatedAtMs": 1710000000000
                }
                """;
        when(valueOperations.get(key)).thenReturn(new ObjectMapper().readTree(succeededPayload).toString());

        assertFalse(repository.begin("evt-1", "REPLAY"));
    }

    @Test
    void beginAndMarkSucceededPersistStateWithTtl() {
        String key = "orchestrator:compensation:saga:evt-2";
        when(valueOperations.get(key)).thenReturn(null);

        assertTrue(repository.begin("evt-2", "SESSION_CLOSE"));
        repository.markSucceeded("evt-2", "SESSION_CLOSE", "SESSION_CLOSED");

        verify(valueOperations, org.mockito.Mockito.atLeastOnce()).set(eq(key), anyString(), eq(Duration.ofHours(1)));
    }
}
