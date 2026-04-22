package com.kafkaasr.tts.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.TranslationResultEvent;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Primary
@Component
@ConditionalOnProperty(prefix = "tts.synthesis", name = "mode", havingValue = "http")
public class HttpTtsSynthesisEngine implements TtsSynthesisEngine {

    private final TtsSynthesisProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public HttpTtsSynthesisEngine(
            TtsSynthesisProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        String endpoint = properties.getHttp().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "tts.synthesis.http.endpoint must be set when tts.synthesis.mode=http");
        }
        this.webClient = webClientBuilder.baseUrl(endpoint).build();
    }

    @Override
    public SynthesisPlan synthesize(TranslationResultEvent sourceEvent, SynthesisInput input) {
        if (input == null) {
            throw new IllegalArgumentException("tts synthesis input must not be null");
        }

        String responseBody = webClient
                .post()
                .uri(properties.getHttp().getPath())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    String authToken = properties.getHttp().getAuthToken();
                    if (authToken != null && !authToken.isBlank()) {
                        headers.setBearerAuth(authToken);
                    }
                })
                .bodyValue(toRequestBody(sourceEvent, input))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(properties.getHttp().getTimeoutMs()))
                .block();

        return mergePlan(input, responseBody, sourceEvent == null ? "" : sourceEvent.sessionId());
    }

    private Map<String, Object> toRequestBody(TranslationResultEvent sourceEvent, SynthesisInput input) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("session_id", sourceEvent == null ? "" : sourceEvent.sessionId());
        request.put("trace_id", sourceEvent == null ? "" : sourceEvent.traceId());
        request.put("tenant_id", sourceEvent == null ? "" : sourceEvent.tenantId());
        request.put("room_id", sourceEvent == null ? "" : sourceEvent.roomId());
        request.put("seq", sourceEvent == null ? 0L : sourceEvent.seq());
        request.put("text", input.text());
        request.put("language", input.language());
        request.put("voice", input.voice());
        request.put("stream", input.stream());
        return request;
    }

    private SynthesisPlan mergePlan(SynthesisInput input, String responseBody, String sessionId) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Empty TTS synthesis response for session " + sessionId);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid TTS synthesis response for session " + sessionId, exception);
        }

        assertProviderSuccess(root, sessionId);

        String text = firstNonBlank(
                textField(root, "text"),
                textField(root, "normalized_text"),
                textField(root, "result_text"),
                textField(root.path("data"), "text"),
                textField(root.path("data"), "normalized_text"),
                textField(root.path("data"), "result_text"),
                textField(root.path("output"), "text"),
                input.text());
        String language = firstNonBlank(
                textField(root, "language"),
                textField(root, "lang"),
                textField(root, "locale"),
                textField(root.path("data"), "language"),
                textField(root.path("data"), "lang"),
                textField(root.path("data"), "locale"),
                input.language());
        String voice = firstNonBlank(
                textField(root, "voice"),
                textField(root, "speaker"),
                textField(root, "voice_id"),
                textField(root.path("data"), "voice"),
                textField(root.path("data"), "speaker"),
                textField(root.path("data"), "voice_id"),
                input.voice());
        Boolean stream = firstBooleanLike(
                root.path("stream"),
                root.path("is_stream"),
                root.path("streaming"),
                root.path("data").path("stream"),
                root.path("data").path("is_stream"),
                root.path("data").path("streaming"));

        return new SynthesisPlan(
                text,
                language,
                voice,
                stream == null ? input.stream() : stream);
    }

    private String textField(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(fieldName);
        if (value.isTextual()) {
            return value.asText();
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Boolean firstBooleanLike(JsonNode... values) {
        for (JsonNode value : values) {
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isNumber()) {
                int numeric = value.asInt();
                if (numeric == 1) {
                    return true;
                }
                if (numeric == 0) {
                    return false;
                }
            }
            if (value.isTextual()) {
                String raw = value.asText().trim();
                if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw)) {
                    return true;
                }
                if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "no".equalsIgnoreCase(raw)) {
                    return false;
                }
            }
        }
        return null;
    }

    private void assertProviderSuccess(JsonNode root, String sessionId) {
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String message = firstNonBlank(
                    textField(errorNode, "message"),
                    textField(errorNode, "msg"),
                    textField(errorNode, "code"),
                    "unknown");
            throw new IllegalStateException(
                    "TTS provider error for session " + sessionId + " (message=" + message + ")");
        }

        JsonNode codeNode = firstPresentNode(
                root.path("code"),
                root.path("status_code"),
                root.path("data").path("code"),
                root.path("data").path("status_code"));
        if (codeNode != null && !isSuccessCode(codeNode)) {
            throw new IllegalStateException(
                    "TTS provider code is not successful for session " + sessionId + " (code=" + codeNode.asText() + ")");
        }

        JsonNode statusNode = firstPresentNode(
                root.path("status"),
                root.path("state"),
                root.path("data").path("status"),
                root.path("data").path("state"));
        if (statusNode != null && !isSuccessStatus(statusNode)) {
            throw new IllegalStateException(
                    "TTS provider status is not successful for session "
                            + sessionId
                            + " (status="
                            + statusNode.asText()
                            + ")");
        }
    }

    private JsonNode firstPresentNode(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isNull() && !node.isMissingNode()) {
                return node;
            }
        }
        return null;
    }

    private boolean isSuccessCode(JsonNode codeNode) {
        if (codeNode.isBoolean()) {
            return codeNode.asBoolean();
        }
        if (codeNode.isNumber()) {
            int code = codeNode.asInt();
            return code == 0 || code == 200;
        }
        if (codeNode.isTextual()) {
            String raw = codeNode.asText().trim();
            if ("ok".equalsIgnoreCase(raw) || "success".equalsIgnoreCase(raw)) {
                return true;
            }
            try {
                int code = Integer.parseInt(raw);
                return code == 0 || code == 200;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean isSuccessStatus(JsonNode statusNode) {
        if (statusNode.isBoolean()) {
            return statusNode.asBoolean();
        }
        if (statusNode.isNumber()) {
            int code = statusNode.asInt();
            return code == 0 || code == 200;
        }
        if (!statusNode.isTextual()) {
            return false;
        }
        String raw = statusNode.asText().trim();
        return "ok".equalsIgnoreCase(raw)
                || "success".equalsIgnoreCase(raw)
                || "succeeded".equalsIgnoreCase(raw)
                || "completed".equalsIgnoreCase(raw)
                || "ready".equalsIgnoreCase(raw);
    }

}
