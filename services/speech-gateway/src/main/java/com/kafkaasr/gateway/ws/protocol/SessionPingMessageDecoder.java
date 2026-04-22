package com.kafkaasr.gateway.ws.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SessionPingMessageDecoder {

    private static final String SESSION_PING_TYPE = "session.ping";
    private static final String INVALID_MESSAGE_CODE = "INVALID_MESSAGE";

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public SessionPingMessageDecoder(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public SessionPingMessage decode(String rawMessage) {
        SessionPingMessage request = parse(rawMessage);
        validate(request);

        if (!SESSION_PING_TYPE.equals(request.type())) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Unsupported message type: " + request.type(),
                    request.sessionId());
        }

        return request;
    }

    private SessionPingMessage parse(String rawMessage) {
        try {
            return objectMapper.readValue(rawMessage, SessionPingMessage.class);
        } catch (JsonProcessingException exception) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Invalid JSON payload",
                    extractSessionId(rawMessage),
                    exception);
        }
    }

    private void validate(SessionPingMessage request) {
        Set<ConstraintViolation<SessionPingMessage>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Validation failed: " + message,
                    coalesceSessionId(request.sessionId()));
        }
    }

    private String extractSessionId(String rawMessage) {
        try {
            JsonNode jsonNode = objectMapper.readTree(rawMessage);
            JsonNode sessionIdNode = jsonNode.get("sessionId");
            if (sessionIdNode != null && !sessionIdNode.isNull()) {
                return sessionIdNode.asText("");
            }
        } catch (JsonProcessingException ignored) {
            // Ignore best-effort extraction failures for malformed payloads.
        }
        return "";
    }

    private String coalesceSessionId(String sessionId) {
        return sessionId == null ? "" : sessionId;
    }
}
