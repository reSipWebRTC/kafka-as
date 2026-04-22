package com.kafkaasr.translation.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.translation.events.AsrFinalEvent;
import com.kafkaasr.translation.events.AsrFinalPayload;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Primary
@Component
@ConditionalOnProperty(prefix = "translation.engine", name = "mode", havingValue = "openai")
public class OpenaiTranslationEngine implements TranslationEngine {

    private static final String FALLBACK_LANGUAGE = "und";

    private final TranslationEngineProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenaiTranslationEngine(
            TranslationEngineProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        String endpoint = properties.getOpenai().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "translation.engine.openai.endpoint must be set when translation.engine.mode=openai");
        }
        this.webClient = webClientBuilder.baseUrl(endpoint).build();
    }

    @Override
    public TranslationResult translate(AsrFinalEvent asrFinalEvent, String targetLang) {
        if (asrFinalEvent == null) {
            throw new IllegalArgumentException("asr.final event must not be null");
        }
        AsrFinalPayload payload = asrFinalEvent.payload();
        if (payload == null) {
            throw new IllegalArgumentException("Missing asr.final payload for session " + asrFinalEvent.sessionId());
        }

        String sourceText = payload.text() == null ? "" : payload.text();
        String sourceLang = normalizeLanguage(payload.language());
        String normalizedTargetLang = normalizeLanguage(targetLang);
        TranslationEngineProperties.Openai config = properties.getOpenai();

        String responseBody = webClient
                .post()
                .uri(config.getPath())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    String apiKey = config.getApiKey();
                    if (apiKey != null && !apiKey.isBlank()) {
                        headers.setBearerAuth(apiKey);
                    }
                })
                .bodyValue(toOpenaiRequest(config, sourceText, sourceLang, normalizedTargetLang))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(config.getTimeoutMs()))
                .block();

        String translatedText = parseTranslatedText(responseBody, asrFinalEvent.sessionId());
        return new TranslationResult(translatedText, sourceLang, normalizedTargetLang, config.getEngineName());
    }

    private Map<String, Object> toOpenaiRequest(
            TranslationEngineProperties.Openai config,
            String sourceText,
            String sourceLang,
            String targetLang) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", config.getModel());
        request.put("temperature", config.getTemperature());
        request.put("max_tokens", config.getMaxTokens());

        List<Map<String, String>> messages = List.of(
                message("system", config.getSystemPrompt()),
                message("user", userPrompt(sourceText, sourceLang, targetLang)));
        request.put("messages", messages);
        return request;
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String userPrompt(String sourceText, String sourceLang, String targetLang) {
        return "Translate the following text from " + sourceLang + " to " + targetLang
                + ". Return only translated text.\n\n" + sourceText;
    }

    private String parseTranslatedText(String responseBody, String sessionId) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Empty OpenAI translation response for session " + sessionId);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid OpenAI translation response for session " + sessionId, exception);
        }

        assertProviderSuccess(root, sessionId);

        String translatedText = firstNonBlank(
                root.path("choices").path(0).path("message").path("content").asText(""),
                firstTextFromMessageContentArray(root.path("choices").path(0).path("message").path("content")),
                root.path("choices").path(0).path("text").asText(""),
                root.path("output_text").asText(""),
                firstTextFromArray(root.path("output_text")),
                root.path("output")
                        .path(0)
                        .path("content")
                        .path(0)
                        .path("text")
                        .asText(""),
                firstTextFromResponsesOutput(root.path("output")));

        if (translatedText.isBlank()) {
            throw new IllegalStateException("Empty OpenAI translated text for session " + sessionId);
        }
        return translatedText;
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank() || language.length() < 2) {
            return FALLBACK_LANGUAGE;
        }
        return language;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void assertProviderSuccess(JsonNode root, String sessionId) {
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String message = firstNonBlank(
                    errorNode.path("message").asText(""),
                    errorNode.path("code").asText(""),
                    errorNode.path("type").asText(""),
                    "unknown");
            throw new IllegalStateException(
                    "OpenAI provider error for session " + sessionId + " (message=" + message + ")");
        }

        JsonNode statusNode = root.path("status");
        if (statusNode.isMissingNode() || statusNode.isNull()) {
            return;
        }
        String status = statusNode.asText("").trim();
        if (status.isEmpty()) {
            return;
        }
        if ("completed".equalsIgnoreCase(status)
                || "succeeded".equalsIgnoreCase(status)
                || "success".equalsIgnoreCase(status)
                || "ok".equalsIgnoreCase(status)) {
            return;
        }
        throw new IllegalStateException(
                "OpenAI provider status is not successful for session " + sessionId + " (status=" + status + ")");
    }

    private String firstTextFromMessageContentArray(JsonNode contentNode) {
        if (contentNode == null || !contentNode.isArray()) {
            return "";
        }
        return firstTextFromArray(contentNode);
    }

    private String firstTextFromResponsesOutput(JsonNode outputNode) {
        if (outputNode == null || !outputNode.isArray() || outputNode.isEmpty()) {
            return "";
        }
        JsonNode firstOutput = outputNode.get(0);
        if (firstOutput == null || firstOutput.isNull()) {
            return "";
        }

        return firstNonBlank(
                firstTextFromArray(firstOutput.path("content")),
                firstOutput.path("text").asText(""),
                firstOutput.path("output_text").asText(""));
    }

    private String firstTextFromArray(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
            return "";
        }
        JsonNode first = arrayNode.get(0);
        if (first == null || first.isNull()) {
            return "";
        }
        if (first.isTextual()) {
            return first.asText("");
        }
        return firstNonBlank(
                first.path("text").asText(""),
                first.path("output_text").asText(""),
                first.path("value").asText(""));
    }
}
