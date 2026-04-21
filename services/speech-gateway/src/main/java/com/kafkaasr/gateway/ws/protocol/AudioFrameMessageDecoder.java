package com.kafkaasr.gateway.ws.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ingress.AudioFrameIngressCommand;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AudioFrameMessageDecoder {

    private static final String AUDIO_FRAME_TYPE = "audio.frame";
    private static final String INVALID_MESSAGE_CODE = "INVALID_MESSAGE";
    private static final String SESSION_SEQ_INVALID_CODE = "SESSION_SEQ_INVALID";

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public AudioFrameMessageDecoder(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public AudioFrameIngressCommand decode(String rawMessage) {
        AudioFrameRequest request = parse(rawMessage);
        validate(request);

        if (!AUDIO_FRAME_TYPE.equals(request.type())) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Unsupported message type: " + request.type(),
                    request.sessionId());
        }

        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(request.audioBase64());
        } catch (IllegalArgumentException exception) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "audioBase64 must be valid base64",
                    request.sessionId(),
                    exception);
        }

        return new AudioFrameIngressCommand(
                request.sessionId(),
                request.seq(),
                request.codec(),
                request.sampleRate(),
                audioBytes);
    }

    private AudioFrameRequest parse(String rawMessage) {
        try {
            return objectMapper.readValue(rawMessage, AudioFrameRequest.class);
        } catch (JsonProcessingException exception) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Invalid JSON payload",
                    extractSessionId(rawMessage),
                    exception);
        }
    }

    private void validate(AudioFrameRequest request) {
        Set<ConstraintViolation<AudioFrameRequest>> violations = validator.validate(request);
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
            throw new MessageValidationException(code, "Validation failed: " + message, request.sessionId());
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
}
