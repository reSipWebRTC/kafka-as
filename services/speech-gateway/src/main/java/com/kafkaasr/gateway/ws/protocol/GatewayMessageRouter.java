package com.kafkaasr.gateway.ws.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ingress.AudioIngressPublisher;
import com.kafkaasr.gateway.session.SessionControlClient;
import com.kafkaasr.gateway.session.SessionStartCommand;
import com.kafkaasr.gateway.session.SessionStopCommand;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class GatewayMessageRouter {

    private static final String AUDIO_FRAME_TYPE = "audio.frame";
    private static final String SESSION_START_TYPE = "session.start";
    private static final String SESSION_STOP_TYPE = "session.stop";
    private static final String INVALID_MESSAGE_CODE = "INVALID_MESSAGE";

    private final AudioIngressPublisher audioIngressPublisher;
    private final AudioFrameMessageDecoder audioFrameMessageDecoder;
    private final SessionStartMessageDecoder sessionStartMessageDecoder;
    private final SessionStopMessageDecoder sessionStopMessageDecoder;
    private final SessionControlClient sessionControlClient;
    private final ObjectMapper objectMapper;

    public GatewayMessageRouter(
            AudioIngressPublisher audioIngressPublisher,
            AudioFrameMessageDecoder audioFrameMessageDecoder,
            SessionStartMessageDecoder sessionStartMessageDecoder,
            SessionStopMessageDecoder sessionStopMessageDecoder,
            SessionControlClient sessionControlClient,
            ObjectMapper objectMapper) {
        this.audioIngressPublisher = audioIngressPublisher;
        this.audioFrameMessageDecoder = audioFrameMessageDecoder;
        this.sessionStartMessageDecoder = sessionStartMessageDecoder;
        this.sessionStopMessageDecoder = sessionStopMessageDecoder;
        this.sessionControlClient = sessionControlClient;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> route(String rawMessage) {
        InboundEnvelope envelope = parseEnvelope(rawMessage);

        return switch (envelope.type()) {
            case AUDIO_FRAME_TYPE -> audioIngressPublisher.publishRawFrame(audioFrameMessageDecoder.decode(rawMessage));
            case SESSION_START_TYPE -> {
                SessionStartMessage request = sessionStartMessageDecoder.decode(rawMessage);
                yield sessionControlClient.startSession(new SessionStartCommand(
                        request.sessionId(),
                        request.tenantId(),
                        request.sourceLang(),
                        request.targetLang(),
                        request.traceId()));
            }
            case SESSION_STOP_TYPE -> {
                SessionStopMessage request = sessionStopMessageDecoder.decode(rawMessage);
                yield sessionControlClient.stopSession(new SessionStopCommand(
                        request.sessionId(),
                        request.traceId(),
                        request.reason()));
            }
            default -> Mono.error(new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Unsupported message type: " + envelope.type(),
                    envelope.sessionId()));
        };
    }

    private InboundEnvelope parseEnvelope(String rawMessage) {
        try {
            JsonNode jsonNode = objectMapper.readTree(rawMessage);
            String sessionId = readText(jsonNode, "sessionId");
            String type = readText(jsonNode, "type");
            if (type.isBlank()) {
                throw new MessageValidationException(
                        INVALID_MESSAGE_CODE,
                        "Validation failed: type must not be blank",
                        sessionId);
            }
            return new InboundEnvelope(type, sessionId);
        } catch (JsonProcessingException exception) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Invalid JSON payload",
                    "",
                    exception);
        }
    }

    private String readText(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return "";
        }
        return fieldNode.asText("");
    }

    private record InboundEnvelope(String type, String sessionId) {
    }
}
