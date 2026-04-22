package com.kafkaasr.gateway.ws;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Component
public class GatewaySessionRegistry {

    private final ConcurrentMap<String, ConnectionState> connectionsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> connectionIdBySessionId = new ConcurrentHashMap<>();

    public ConnectionContext openConnection(WebSocketSession session) {
        String connectionId = session.getId();
        ConnectionState state = new ConnectionState(
                connectionId,
                session,
                Sinks.many().unicast().onBackpressureBuffer(),
                new AtomicReference<>(),
                new AtomicBoolean(false));

        ConnectionState previous = connectionsById.put(connectionId, state);
        if (previous != null) {
            terminate(previous);
        }

        return new ConnectionContext(connectionId, state.outbound.asFlux());
    }

    public void bindSession(String connectionId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        ConnectionState state = connectionsById.get(connectionId);
        if (state == null || state.closed.get()) {
            return;
        }

        String previousSessionId = state.boundSessionId.getAndSet(sessionId);
        if (previousSessionId != null && !previousSessionId.equals(sessionId)) {
            unbindSession(connectionId, previousSessionId);
        }
        connectionIdBySessionId.put(sessionId, connectionId);
    }

    public Mono<Void> emitToConnection(String connectionId, String payload) {
        ConnectionState state = connectionsById.get(connectionId);
        if (state == null || state.closed.get()) {
            return Mono.empty();
        }

        state.outbound.tryEmitNext(payload);
        return Mono.empty();
    }

    public Mono<Void> emitToSession(String sessionId, String payload) {
        ConnectionState state = resolveBySessionId(sessionId);
        if (state == null || state.closed.get()) {
            return Mono.empty();
        }

        state.outbound.tryEmitNext(payload);
        return Mono.empty();
    }

    public Mono<Void> closeSession(String sessionId, CloseStatus closeStatus) {
        ConnectionState state = resolveBySessionId(sessionId);
        if (state == null) {
            return Mono.empty();
        }

        connectionsById.remove(state.connectionId, state);
        terminate(state);

        return state.session.close(closeStatus)
                .onErrorResume(ignored -> Mono.empty());
    }

    public void closeConnection(String connectionId) {
        ConnectionState removed = connectionsById.remove(connectionId);
        if (removed == null) {
            return;
        }

        terminate(removed);
    }

    private ConnectionState resolveBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String connectionId = connectionIdBySessionId.get(sessionId);
        if (connectionId == null) {
            return null;
        }
        return connectionsById.get(connectionId);
    }

    private void unbindSession(String connectionId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        connectionIdBySessionId.remove(sessionId, connectionId);
    }

    private void terminate(ConnectionState state) {
        state.closed.set(true);
        unbindSession(state.connectionId, state.boundSessionId.get());
        state.outbound.tryEmitComplete();
    }

    public record ConnectionContext(String connectionId, Flux<String> outbound) {
    }

    private record ConnectionState(
            String connectionId,
            WebSocketSession session,
            Sinks.Many<String> outbound,
            AtomicReference<String> boundSessionId,
            AtomicBoolean closed) {
    }
}
