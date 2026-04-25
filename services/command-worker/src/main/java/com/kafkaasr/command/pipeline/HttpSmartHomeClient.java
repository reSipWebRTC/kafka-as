package com.kafkaasr.command.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@Component
@ConditionalOnProperty(name = "command.smarthome.enabled", havingValue = "true", matchIfMissing = true)
public class HttpSmartHomeClient implements SmartHomeClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final SmartHomeClientProperties properties;

    @Autowired
    public HttpSmartHomeClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            SmartHomeClientProperties properties) {
        this(
                webClientBuilder.baseUrl(properties.getBaseUrl()).build(),
                objectMapper,
                properties);
    }

    HttpSmartHomeClient(
            WebClient webClient,
            ObjectMapper objectMapper,
            SmartHomeClientProperties properties) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public SmartHomeApiResponse executeCommand(
            String sessionId,
            String userId,
            String text,
            String traceId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("session_id", sessionId);
        payload.put("user_id", userId);
        payload.put("text", text);
        return post(properties.getCommandPath(), payload, traceId);
    }

    @Override
    public SmartHomeApiResponse submitConfirm(
            String confirmToken,
            boolean accept,
            String traceId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("confirm_token", confirmToken);
        payload.put("accept", accept);
        return post(properties.getConfirmPath(), payload, traceId);
    }

    private SmartHomeApiResponse post(String path, Object requestBody, String traceId) {
        try {
            return webClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (traceId != null && !traceId.isBlank()) {
                            headers.set("X-Trace-Id", traceId);
                        }
                    })
                    .bodyValue(requestBody)
                    .exchangeToMono(response -> toApiResponse(response, traceId))
                    .timeout(Duration.ofMillis(Math.max(1L, properties.getTimeoutMs())))
                    .block();
        } catch (SmartHomeClientException exception) {
            throw exception;
        } catch (WebClientRequestException exception) {
            throw new SmartHomeClientException(
                    "UPSTREAM_TIMEOUT",
                    "smartHomeNlu unavailable",
                    true,
                    exception);
        } catch (RuntimeException exception) {
            throw new SmartHomeClientException(
                    "UPSTREAM_ERROR",
                    "Failed to call smartHomeNlu",
                    true,
                    exception);
        }
    }

    private reactor.core.publisher.Mono<SmartHomeApiResponse> toApiResponse(
            ClientResponse response,
            String fallbackTraceId) {
        int statusCode = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("{}")
                .map(body -> parseBody(statusCode, body, fallbackTraceId));
    }

    private SmartHomeApiResponse parseBody(int statusCode, String body, String fallbackTraceId) {
        JsonNode root = parseJson(body);

        String code = text(root, "code");
        if (code.isBlank()) {
            code = statusCode >= 500 ? "UPSTREAM_ERROR" : "INTERNAL_ERROR";
        }
        String message = text(root, "message");
        boolean retryable = root.path("retryable").isBoolean()
                ? root.path("retryable").asBoolean(false)
                : inferredRetryable(code);
        JsonNode data = root.path("data");
        String status = text(data, "status");
        String replyText = firstNonBlank(text(data, "reply_text"), message);
        String confirmToken = blankToNull(text(data, "confirm_token"));
        Integer expiresInSec = data.path("expires_in_sec").canConvertToInt()
                ? data.path("expires_in_sec").asInt()
                : null;
        String intent = blankToNull(text(data, "intent"));
        String subIntent = blankToNull(text(data, "sub_intent"));
        String traceId = firstNonBlank(text(root, "trace_id"), fallbackTraceId);

        return new SmartHomeApiResponse(
                traceId,
                code,
                message,
                retryable,
                status,
                replyText,
                confirmToken,
                expiresInSec,
                intent,
                subIntent);
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new SmartHomeClientException(
                    "UPSTREAM_INVALID_RESPONSE",
                    "Invalid smartHomeNlu response payload",
                    true,
                    exception);
        }
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return "";
        }
        return field.asText("");
    }

    private boolean inferredRetryable(String code) {
        return "UPSTREAM_TIMEOUT".equalsIgnoreCase(code)
                || "UPSTREAM_ERROR".equalsIgnoreCase(code)
                || "INTERNAL_ERROR".equalsIgnoreCase(code);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
