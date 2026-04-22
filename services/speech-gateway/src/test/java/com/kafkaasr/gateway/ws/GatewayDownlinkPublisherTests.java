package com.kafkaasr.gateway.ws;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.socket.CloseStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GatewayDownlinkPublisherTests {

    @Mock
    private GatewaySessionRegistry sessionRegistry;

    private GatewayDownlinkPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new GatewayDownlinkPublisher(new ObjectMapper(), sessionRegistry);
    }

    @Test
    void publishesSubtitlePartialPayload() {
        when(sessionRegistry.emitToSession(eq("sess-1"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(publisher.publishSubtitlePartial("sess-1", 7L, "hello"))
                .verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).emitToSession(eq("sess-1"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"type\":\"subtitle.partial\""));
        assertTrue(payload.contains("\"sessionId\":\"sess-1\""));
        assertTrue(payload.contains("\"seq\":7"));
        assertTrue(payload.contains("\"text\":\"hello\""));
    }

    @Test
    void publishesSessionClosedAndClosesConnection() {
        when(sessionRegistry.emitToSession(eq("sess-1"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.empty());
        when(sessionRegistry.closeSession(eq("sess-1"), eq(CloseStatus.NORMAL)))
                .thenReturn(Mono.empty());

        StepVerifier.create(publisher.publishSessionClosed("sess-1", "client.stop"))
                .verifyComplete();

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).emitToSession(eq("sess-1"), payloadCaptor.capture());
        verify(sessionRegistry).closeSession("sess-1", CloseStatus.NORMAL);

        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"type\":\"session.closed\""));
        assertTrue(payload.contains("\"reason\":\"client.stop\""));
    }

    @Test
    void ignoresBlankSessionId() {
        StepVerifier.create(publisher.publishSubtitleFinal("", 1L, "ignored"))
                .verifyComplete();

        verify(sessionRegistry, never()).emitToSession(eq(""), org.mockito.ArgumentMatchers.anyString());
    }
}
