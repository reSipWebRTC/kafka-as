package com.kafkaasr.asr.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.events.AudioIngressRawPayload;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Primary
@Component
@ConditionalOnProperty(prefix = "asr.inference", name = "mode", havingValue = "funasr")
public class FunasrAsrInferenceEngine implements AsrInferenceEngine {

    private static final double DEFAULT_CONFIDENCE_NON_FINAL = 0.6d;
    private static final double DEFAULT_CONFIDENCE_FINAL = 0.95d;
    private static final String DEFAULT_LANGUAGE = "und";

    private final AsrInferenceProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public FunasrAsrInferenceEngine(
            AsrInferenceProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        String endpoint = properties.getFunasr().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("asr.inference.funasr.endpoint must be set when asr.inference.mode=funasr");
        }
        this.webClient = webClientBuilder.baseUrl(endpoint).build();
    }

    @Override
    public AsrInferenceResult infer(AudioIngressRawEvent ingressEvent) {
        AudioIngressRawPayload payload = ingressEvent.payload();
        if (payload == null) {
            throw new IllegalArgumentException("Missing audio payload for session " + ingressEvent.sessionId());
        }
        validateBase64(payload.audioBase64(), ingressEvent.sessionId());

        String responseBody = webClient
                .post()
                .uri(properties.getFunasr().getPath())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    String authToken = properties.getFunasr().getAuthToken();
                    if (authToken != null && !authToken.isBlank()) {
                        headers.setBearerAuth(authToken);
                    }
                })
                .bodyValue(toFunasrRequest(ingressEvent))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(properties.getFunasr().getTimeoutMs()))
                .block();

        return parseResponse(responseBody, ingressEvent);
    }

    private Map<String, Object> toFunasrRequest(AudioIngressRawEvent ingressEvent) {
        AudioIngressRawPayload payload = ingressEvent.payload();
        AsrInferenceProperties.Funasr config = properties.getFunasr();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("session_id", ingressEvent.sessionId());
        request.put("trace_id", ingressEvent.traceId());
        request.put("audio", payload.audioBase64());
        request.put("audio_format", resolveAudioFormat(payload.audioCodec(), config.getAudioFormat()));
        request.put("sample_rate", payload.sampleRate() > 0 ? payload.sampleRate() : config.getDefaultSampleRate());
        request.put("is_final", payload.endOfStream());
        request.put("model", config.getModel());
        request.put("language", config.getLanguage());
        request.put("itn", config.isEnableInverseTextNormalization());
        if (config.getHotwords() != null && !config.getHotwords().isBlank()) {
            request.put("hotwords", config.getHotwords());
        }
        return request;
    }

    private AsrInferenceResult parseResponse(String responseBody, AudioIngressRawEvent ingressEvent) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Empty FunASR response for session " + ingressEvent.sessionId());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid FunASR response for session " + ingressEvent.sessionId(), exception);
        }

        String text = firstNonBlank(
                textField(root, "text"),
                textField(root, "result"),
                textField(root, "transcript"),
                textField(root.path("data"), "text"),
                textField(root.path("data"), "result"),
                textField(root.path("data"), "transcript"),
                firstTextFromArray(root.path("result")),
                firstTextFromArray(root.path("data").path("result")));

        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Empty FunASR transcript for session " + ingressEvent.sessionId());
        }

        String language = firstNonBlank(
                textField(root, "language"),
                textField(root, "lang"),
                textField(root.path("data"), "language"),
                textField(root.path("data"), "lang"),
                DEFAULT_LANGUAGE);

        double confidence = firstNumber(
                root.path("confidence"),
                root.path("score"),
                root.path("data").path("confidence"),
                root.path("data").path("score"));
        if (confidence < 0d) {
            confidence = ingressEvent.payload().endOfStream() ? DEFAULT_CONFIDENCE_FINAL : DEFAULT_CONFIDENCE_NON_FINAL;
        }

        Boolean stable = firstBoolean(
                root.path("stable"),
                root.path("is_final"),
                root.path("final"),
                root.path("data").path("stable"),
                root.path("data").path("is_final"),
                root.path("data").path("final"));
        boolean resolvedStable = stable == null ? ingressEvent.payload().endOfStream() : stable;

        return new AsrInferenceResult(text, language, confidence, resolvedStable);
    }

    private void validateBase64(String audioBase64, String sessionId) {
        if (audioBase64 == null || audioBase64.isBlank()) {
            throw new IllegalArgumentException("Missing audioBase64 payload for session " + sessionId);
        }
        try {
            Base64.getDecoder().decode(audioBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid audioBase64 payload for session " + sessionId, exception);
        }
    }

    private String resolveAudioFormat(String codec, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (codec == null || codec.isBlank()) {
            return "pcm";
        }
        if ("pcm16le".equalsIgnoreCase(codec) || "pcm".equalsIgnoreCase(codec)) {
            return "pcm";
        }
        return codec.toLowerCase();
    }

    private String textField(JsonNode root, String fieldName) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return "";
        }
        JsonNode value = root.path(fieldName);
        if (value.isTextual()) {
            return value.asText();
        }
        return "";
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
            return first.asText();
        }
        return first.path("text").asText("");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private double firstNumber(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isNumber()) {
                return node.asDouble();
            }
        }
        return -1d;
    }

    private Boolean firstBoolean(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isBoolean()) {
                return node.asBoolean();
            }
        }
        return null;
    }
}
