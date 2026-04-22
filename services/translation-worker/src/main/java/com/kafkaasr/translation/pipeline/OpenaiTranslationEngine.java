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

        String translatedText = firstNonBlank(
                root.path("choices").path(0).path("message").path("content").asText(""),
                root.path("choices").path(0).path("text").asText(""),
                root.path("output_text").asText(""),
                root.path("output")
                        .path(0)
                        .path("content")
                        .path(0)
                        .path("text")
                        .asText(""));

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
}
