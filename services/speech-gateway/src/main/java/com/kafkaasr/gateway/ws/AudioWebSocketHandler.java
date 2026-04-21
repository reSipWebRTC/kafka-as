package com.kafkaasr.gateway.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ingress.AudioIngressPublisher;
import com.kafkaasr.gateway.ws.protocol.AudioFrameMessageDecoder;
import com.kafkaasr.gateway.ws.protocol.MessageValidationException;
import com.kafkaasr.gateway.ws.protocol.SessionErrorResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Component
public class AudioWebSocketHandler implements WebSocketHandler {

    private static final String SESSION_ERROR_TYPE = "session.error";
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    private final AudioIngressPublisher audioIngressPublisher;
    private final AudioFrameMessageDecoder audioFrameMessageDecoder;
    private final ObjectMapper objectMapper;

    public AudioWebSocketHandler(
            AudioIngressPublisher audioIngressPublisher,
            AudioFrameMessageDecoder audioFrameMessageDecoder,
            ObjectMapper objectMapper) {
        this.audioIngressPublisher = audioIngressPublisher;
        this.audioFrameMessageDecoder = audioFrameMessageDecoder;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Mono<Void> inbound = session.receive()
                .map(message -> message.getPayloadAsText())
                .flatMap(rawMessage -> audioIngressPublisher.publishRawFrame(
                        audioFrameMessageDecoder.decode(rawMessage)))
                .then();

        return inbound
                .onErrorResume(
                        MessageValidationException.class,
                        exception -> sendErrorAndClose(
                                session,
                                exception.code(),
                                exception.getMessage(),
                                exception.sessionId(),
                                CloseStatus.BAD_DATA))
                .onErrorResume(
                        exception -> sendErrorAndClose(
                                session,
                                INTERNAL_ERROR_CODE,
                                "Unexpected gateway failure",
                                "",
                                CloseStatus.SERVER_ERROR));
    }

    private Mono<Void> sendErrorAndClose(
            WebSocketSession session,
            String code,
            String message,
            String sessionId,
            CloseStatus closeStatus) {
        return session.send(Mono.just(session.textMessage(toErrorPayload(code, message, sessionId))))
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
