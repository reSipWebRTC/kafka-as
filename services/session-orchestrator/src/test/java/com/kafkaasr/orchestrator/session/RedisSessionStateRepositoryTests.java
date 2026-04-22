package com.kafkaasr.orchestrator.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
class RedisSessionStateRepositoryTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisSessionStateRepository repository;
    private Duration ttl;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        SessionStoreProperties storeProperties = new SessionStoreProperties();
        storeProperties.setKeyPrefix("orchestrator:session:");
        ttl = Duration.ofMinutes(30);
        storeProperties.setTtl(ttl);

        repository = new RedisSessionStateRepository(redisTemplate, new ObjectMapper(), storeProperties);
    }

    @Test
    void findBySessionIdReturnsNullWhenAbsent() {
        when(valueOperations.get("orchestrator:session:sess-1")).thenReturn(null);

        SessionState state = repository.findBySessionId("sess-1");

        assertNull(state);
    }

    @Test
    void findBySessionIdReturnsDeserializedState() throws Exception {
        SessionState expected = new SessionState(
                "sess-2",
                "tenant-a",
                "zh-CN",
                "en-US",
                "trc-1",
                SessionStatus.STREAMING,
                1L,
                1000L,
                1000L);
        String payload = new ObjectMapper().writeValueAsString(expected);
        when(valueOperations.get("orchestrator:session:sess-2")).thenReturn(payload);

        SessionState state = repository.findBySessionId("sess-2");

        assertNotNull(state);
        assertEquals(expected.sessionId(), state.sessionId());
        assertEquals(expected.status(), state.status());
        assertEquals(expected.lastSeq(), state.lastSeq());
    }

    @Test
    void createIfAbsentUsesSetNxWithConfiguredTtl() {
        SessionState state = new SessionState(
                "sess-3",
                "tenant-a",
                "zh-CN",
                "en-US",
                "trc-2",
                SessionStatus.STREAMING,
                1L,
                1000L,
                1000L);
        when(valueOperations.setIfAbsent(eq("orchestrator:session:sess-3"), anyString(), eq(ttl)))
                .thenReturn(true);

        boolean created = repository.createIfAbsent(state);

        assertTrue(created);
        verify(valueOperations).setIfAbsent(eq("orchestrator:session:sess-3"), anyString(), eq(ttl));
    }
}
