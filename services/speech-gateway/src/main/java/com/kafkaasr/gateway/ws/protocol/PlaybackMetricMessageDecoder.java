package com.kafkaasr.gateway.ws.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PlaybackMetricMessageDecoder {

    private static final String PLAYBACK_METRIC_TYPE = "playback.metric";
    private static final String INVALID_MESSAGE_CODE = "INVALID_MESSAGE";
    private static final String SESSION_SEQ_INVALID_CODE = "SESSION_SEQ_INVALID";

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public PlaybackMetricMessageDecoder(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public PlaybackMetricMessage decode(String rawMessage) {
        PlaybackMetricMessage request = parse(rawMessage);
        validate(request);

        if (!PLAYBACK_METRIC_TYPE.equals(request.type())) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Unsupported message type: " + request.type(),
                    request.sessionId());
        }

        validateSemanticFields(request);
        return request;
    }

    private PlaybackMetricMessage parse(String rawMessage) {
        try {
            return objectMapper.readValue(rawMessage, PlaybackMetricMessage.class);
        } catch (JsonProcessingException exception) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Invalid JSON payload",
                    extractSessionId(rawMessage),
                    exception);
        }
    }

    private void validate(PlaybackMetricMessage request) {
        Set<ConstraintViolation<PlaybackMetricMessage>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            String code = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .anyMatch("seq"::equals)
                    ? SESSION_SEQ_INVALID_CODE
                    : INVALID_MESSAGE_CODE;
            throw new MessageValidationException(
                    code,
                    "Validation failed: " + message,
                    coalesceSessionId(request.sessionId()));
        }
    }

    private void validateSemanticFields(PlaybackMetricMessage request) {
        String stage = normalize(request.stage());
        String source = normalize(request.source());
        if (!isValidStage(stage)) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Validation failed: stage unsupported",
                    request.sessionId());
        }
        if (!isValidSource(source)) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Validation failed: source unsupported",
                    request.sessionId());
        }
        if ((stage.equals("start") || stage.equals("stall")) && request.durationMs() == null) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Validation failed: durationMs required for stage=" + stage,
                    request.sessionId());
        }
    }

    private boolean isValidStage(String stage) {
        return stage.equals("start")
                || stage.equals("stall")
                || stage.equals("complete")
                || stage.equals("fallback");
    }

    private boolean isValidSource(String source) {
        return source.equals("remote") || source.equals("local");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
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
        if (sessionId == null) {
            return "";
        }
        return sessionId;
    }
}

