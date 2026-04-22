package com.kafkaasr.gateway.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.session.SessionControlClientException;
import com.kafkaasr.gateway.ws.protocol.GatewayMessageRouter;
import com.kafkaasr.gateway.ws.protocol.MessageValidationException;
import com.kafkaasr.gateway.ws.protocol.SessionErrorResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Component
public class AudioWebSocketHandler implements WebSocketHandler {

    private static final String SESSION_ERROR_TYPE = "session.error";
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    private final GatewayMessageRouter gatewayMessageRouter;
    private final GatewaySessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    public AudioWebSocketHandler(
            GatewayMessageRouter gatewayMessageRouter,
            GatewaySessionRegistry sessionRegistry,
            ObjectMapper objectMapper) {
        this.gatewayMessageRouter = gatewayMessageRouter;
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        GatewaySessionRegistry.ConnectionContext connection = sessionRegistry.openConnection(session);

        Mono<Void> outbound = session.send(connection.outbound().map(session::textMessage));

        Mono<Void> inbound = session.receive()
                .map(message -> message.getPayloadAsText())
                .flatMap(rawMessage -> gatewayMessageRouter.route(
                        rawMessage,
                        sessionId -> sessionRegistry.bindSession(connection.connectionId(), sessionId)))
                .then();

        Mono<Void> guardedInbound = inbound
                .onErrorResume(
                        MessageValidationException.class,
                        exception -> emitErrorAndClose(
                                connection.connectionId(),
                                session,
                                exception.code(),
                                exception.getMessage(),
                                exception.sessionId(),
                                CloseStatus.BAD_DATA))
                .onErrorResume(
                        SessionControlClientException.class,
                        exception -> emitErrorAndClose(
                                connection.connectionId(),
                                session,
                                exception.code(),
                                exception.getMessage(),
                                exception.sessionId(),
                                exception.closeStatus()))
                .onErrorResume(
                        exception -> emitErrorAndClose(
                                connection.connectionId(),
                                session,
                                INTERNAL_ERROR_CODE,
                                "Unexpected gateway failure",
                                "",
                                CloseStatus.SERVER_ERROR));

        return Mono.when(guardedInbound, outbound)
                .doFinally(signalType -> sessionRegistry.closeConnection(connection.connectionId()));
    }

    private Mono<Void> emitErrorAndClose(
            String connectionId,
            WebSocketSession session,
            String code,
            String message,
            String sessionId,
            CloseStatus closeStatus) {
        return sessionRegistry.emitToConnection(connectionId, toErrorPayload(code, message, sessionId))
                .then(session.close(closeStatus));
    }

    private String toErrorPayload(String code, String message, String sessionId) {
        SessionErrorResponse payload = new SessionErrorResponse(
                SESSION_ERROR_TYPE,
                sessionId,
                code,
                message);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return "{\"type\":\"session.error\",\"sessionId\":\"\",\"code\":\"INTERNAL_ERROR\",\"message\":\"serialization failure\"}";
        }
    }
}
