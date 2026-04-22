package com.kafkaasr.gateway.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ws.protocol.SessionClosedResponse;
import com.kafkaasr.gateway.ws.protocol.SubtitleFinalResponse;
import com.kafkaasr.gateway.ws.protocol.SubtitlePartialResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import reactor.core.publisher.Mono;

@Component
public class GatewayDownlinkPublisher {

    private static final String SUBTITLE_PARTIAL_TYPE = "subtitle.partial";
    private static final String SUBTITLE_FINAL_TYPE = "subtitle.final";
    private static final String SESSION_CLOSED_TYPE = "session.closed";

    private final ObjectMapper objectMapper;
    private final GatewaySessionRegistry sessionRegistry;

    public GatewayDownlinkPublisher(
            ObjectMapper objectMapper,
            GatewaySessionRegistry sessionRegistry) {
        this.objectMapper = objectMapper;
        this.sessionRegistry = sessionRegistry;
    }

    public Mono<Void> publishSubtitlePartial(String sessionId, long seq, String text) {
        return publishToSession(sessionId, new SubtitlePartialResponse(
                SUBTITLE_PARTIAL_TYPE,
                sessionId,
                seq,
                coalesceText(text)));
    }

    public Mono<Void> publishSubtitleFinal(String sessionId, long seq, String text) {
        return publishToSession(sessionId, new SubtitleFinalResponse(
                SUBTITLE_FINAL_TYPE,
                sessionId,
                seq,
                coalesceText(text)));
    }

    public Mono<Void> publishSessionClosed(String sessionId, String reason) {
        return publishToSession(sessionId, new SessionClosedResponse(
                SESSION_CLOSED_TYPE,
                sessionId,
                coalesceText(reason)))
                .then(sessionRegistry.closeSession(sessionId, CloseStatus.NORMAL));
    }

    private Mono<Void> publishToSession(String sessionId, Object payload) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.empty();
        }

        try {
            String serialized = objectMapper.writeValueAsString(payload);
            return sessionRegistry.emitToSession(sessionId, serialized);
        } catch (JsonProcessingException exception) {
            return Mono.error(new IllegalStateException("Failed to serialize websocket downlink payload", exception));
        }
    }

    private String coalesceText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value;
    }
}
