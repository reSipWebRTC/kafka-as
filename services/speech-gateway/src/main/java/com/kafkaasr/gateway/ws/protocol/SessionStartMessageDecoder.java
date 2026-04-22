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
public class SessionStartMessageDecoder {

    private static final String SESSION_START_TYPE = "session.start";
    private static final String INVALID_MESSAGE_CODE = "INVALID_MESSAGE";

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public SessionStartMessageDecoder(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public SessionStartMessage decode(String rawMessage) {
        SessionStartMessage request = parse(rawMessage);
        validate(request);

        if (!SESSION_START_TYPE.equals(request.type())) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Unsupported message type: " + request.type(),
                    request.sessionId());
        }

        return request;
    }

    private SessionStartMessage parse(String rawMessage) {
        try {
            return objectMapper.readValue(rawMessage, SessionStartMessage.class);
        } catch (JsonProcessingException exception) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Invalid JSON payload",
                    extractSessionId(rawMessage),
                    exception);
        }
    }

    private void validate(SessionStartMessage request) {
        Set<ConstraintViolation<SessionStartMessage>> violations = validator.validate(request);
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
