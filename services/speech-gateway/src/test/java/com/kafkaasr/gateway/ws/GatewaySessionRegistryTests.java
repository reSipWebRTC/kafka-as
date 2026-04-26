package com.kafkaasr.gateway.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GatewaySessionRegistryTests {

    @Test
    void routesSessionDownlinkToNewConnectionAfterReconnect() {
        GatewaySessionRegistry registry = new GatewaySessionRegistry();

        WebSocketSession firstSession = mockSession("conn-1");
        GatewaySessionRegistry.ConnectionContext firstContext = registry.openConnection(firstSession);
        registry.bindSession(firstContext.connectionId(), "sess-1");
        StepVerifier.create(firstContext.outbound().take(1))
                .then(() -> registry.emitToSession("sess-1", "first-payload").block())
                .assertNext(payload -> assertEquals("first-payload", payload))
                .verifyComplete();

        registry.closeConnection(firstContext.connectionId());

        WebSocketSession secondSession = mockSession("conn-2");
        GatewaySessionRegistry.ConnectionContext secondContext = registry.openConnection(secondSession);
        registry.bindSession(secondContext.connectionId(), "sess-1");

        StepVerifier.create(secondContext.outbound().take(1))
                .then(() -> registry.emitToSession("sess-1", "reconnected-payload").block())
                .assertNext(payload -> assertEquals("reconnected-payload", payload))
                .verifyComplete();
    }

    @Test
    void closeSessionInvokesSocketCloseAndUnbindsSession() {
        GatewaySessionRegistry registry = new GatewaySessionRegistry();
        WebSocketSession session = mockSession("conn-3");
        GatewaySessionRegistry.ConnectionContext context = registry.openConnection(session);
        registry.bindSession(context.connectionId(), "sess-3");

        StepVerifier.create(registry.closeSession("sess-3", CloseStatus.NORMAL))
                .verifyComplete();
        StepVerifier.create(registry.emitToSession("sess-3", "ignored"))
                .verifyComplete();
    }

    private WebSocketSession mockSession(String connectionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(connectionId);
        lenient().when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        return session;
    }
}
