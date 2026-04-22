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

        try {
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

            return mergePlan(input, responseBody);
        } catch (RuntimeException exception) {
            return passthrough(input);
        }
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

    private SynthesisPlan mergePlan(SynthesisInput input, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return passthrough(input);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            return passthrough(input);
        }

        String text = firstNonBlank(
                textField(root, "text"),
                textField(root, "normalized_text"),
                textField(root.path("data"), "text"),
                input.text());
        String language = firstNonBlank(
                textField(root, "language"),
                textField(root, "lang"),
                textField(root.path("data"), "language"),
                textField(root.path("data"), "lang"),
                input.language());
        String voice = firstNonBlank(
                textField(root, "voice"),
                textField(root, "speaker"),
                textField(root.path("data"), "voice"),
                textField(root.path("data"), "speaker"),
                input.voice());
        Boolean stream = firstBoolean(
                root.path("stream"),
                root.path("is_stream"),
                root.path("data").path("stream"),
                root.path("data").path("is_stream"));

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

    private Boolean firstBoolean(JsonNode... values) {
        for (JsonNode value : values) {
            if (value != null && value.isBoolean()) {
                return value.asBoolean();
            }
        }
        return null;
    }

    private SynthesisPlan passthrough(SynthesisInput input) {
        return new SynthesisPlan(
                input.text(),
                input.language(),
                input.voice(),
                input.stream());
    }
}
