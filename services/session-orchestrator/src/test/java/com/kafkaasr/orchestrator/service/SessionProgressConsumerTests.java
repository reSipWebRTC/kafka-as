package com.kafkaasr.orchestrator.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.orchestrator.session.SessionProgressMarker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionProgressConsumerTests {

    @Mock
    private SessionLifecycleService sessionLifecycleService;

    private SessionProgressConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SessionProgressConsumer(
                new ObjectMapper(),
                sessionLifecycleService,
                new SimpleMeterRegistry());
    }

    @Test
    void updatesSessionAggregationOnMatchingEvent() {
        when(sessionLifecycleService.recordProgress("sess-1", SessionProgressMarker.ASR_PARTIAL, 1710000000123L))
                .thenReturn(SessionLifecycleService.ProgressUpdateResult.UPDATED);

        consumer.onAsrPartial("""
                {
                  "eventType": "asr.partial",
                  "sessionId": "sess-1",
                  "ts": 1710000000123
                }
                """);

        verify(sessionLifecycleService).recordProgress("sess-1", SessionProgressMarker.ASR_PARTIAL, 1710000000123L);
    }

    @Test
    void ignoresEventWithUnexpectedEventType() {
        consumer.onAsrFinal("""
                {
                  "eventType": "translation.result",
                  "sessionId": "sess-1",
                  "ts": 1710000000123
                }
                """);

        verify(sessionLifecycleService, never()).recordProgress(eq("sess-1"), eq(SessionProgressMarker.ASR_FINAL), eq(1710000000123L));
    }
}
