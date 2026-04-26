package com.kafkaasr.command.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.command.pipeline.CommandExecutionState;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisCommandExecutionContextRepositoryTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisCommandExecutionContextRepository repository;
    private ObjectMapper objectMapper;
    private CommandStateStoreProperties storeProperties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        storeProperties = new CommandStateStoreProperties();
        storeProperties.setKeyPrefix("command:execution:");
        storeProperties.setTtl(Duration.ofHours(12));

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        repository = new RedisCommandExecutionContextRepository(redisTemplate, objectMapper, storeProperties);
    }

    @Test
    void findByExecutionIdReturnsNullWhenMissing() {
        when(valueOperations.get("command:execution:exec-1")).thenReturn(null);

        assertNull(repository.findByExecutionId("exec-1"));
    }

    @Test
    void findByExecutionIdDeserializesPayload() throws Exception {
        CommandExecutionContext state = new CommandExecutionContext(
                "exec-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "trc-1",
                1713744001000L,
                "打开客厅灯",
                "CONTROL",
                "SMART_HOME",
                true,
                1,
                2,
                "cfm-1",
                CommandExecutionState.WAIT_CONFIRM);
        String payload = objectMapper.writeValueAsString(state);
        when(valueOperations.get("command:execution:exec-1")).thenReturn(payload);

        CommandExecutionContext loaded = repository.findByExecutionId("exec-1");

        assertEquals("exec-1", loaded.executionId());
        assertEquals("sess-1", loaded.sessionId());
        assertEquals(CommandExecutionState.WAIT_CONFIRM, loaded.state());
    }

    @Test
    void saveWritesWithConfiguredTtl() {
        CommandExecutionContext state = new CommandExecutionContext(
                "exec-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "trc-1",
                1713744001000L,
                "打开客厅灯",
                "CONTROL",
                "SMART_HOME",
                true,
                1,
                2,
                "cfm-1",
                CommandExecutionState.WAIT_CONFIRM);

        repository.save(state);

        verify(valueOperations).set(eq("command:execution:exec-1"), any(), eq(Duration.ofHours(12)));
    }

    @Test
    void deleteByExecutionIdRemovesKey() {
        repository.delete("exec-1");

        verify(redisTemplate).delete("command:execution:exec-1");
    }
}
