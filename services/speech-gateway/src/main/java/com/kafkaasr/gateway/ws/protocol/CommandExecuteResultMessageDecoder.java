package com.kafkaasr.gateway.ws.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ingress.CommandExecuteResultIngressCommand;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CommandExecuteResultMessageDecoder {

    private static final String COMMAND_EXECUTE_RESULT_TYPE = "command.execute.result";
    private static final String INVALID_MESSAGE_CODE = "INVALID_MESSAGE";
    private static final String SESSION_SEQ_INVALID_CODE = "SESSION_SEQ_INVALID";

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public CommandExecuteResultMessageDecoder(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public CommandExecuteResultIngressCommand decode(String rawMessage) {
        CommandExecuteResultMessage request = parse(rawMessage);
        validate(request);

        if (!COMMAND_EXECUTE_RESULT_TYPE.equals(request.type())) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Unsupported message type: " + request.type(),
                    request.sessionId());
        }

        return new CommandExecuteResultIngressCommand(
                request.sessionId(),
                request.seq(),
                request.executionId(),
                request.status(),
                request.code(),
                request.replyText(),
                request.retryable(),
                request.traceId());
    }

    private CommandExecuteResultMessage parse(String rawMessage) {
        try {
            return objectMapper.readValue(rawMessage, CommandExecuteResultMessage.class);
        } catch (JsonProcessingException exception) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Invalid JSON payload",
                    extractSessionId(rawMessage),
                    exception);
        }
    }

    private void validate(CommandExecuteResultMessage request) {
        Set<ConstraintViolation<CommandExecuteResultMessage>> violations = validator.validate(request);
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
