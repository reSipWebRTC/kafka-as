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

        assertProviderSuccess(root, ingressEvent.sessionId());

        String text = firstNonBlank(
                textField(root, "text"),
                textField(root, "result"),
                textField(root, "transcript"),
                textField(root, "sentence"),
                textField(root.path("data"), "text"),
                textField(root.path("data"), "result"),
                textField(root.path("data"), "transcript"),
                textField(root.path("data"), "sentence"),
                firstTextFromArray(root.path("result")),
                firstTextFromArray(root.path("data").path("result")),
                firstTextFromArray(root.path("sentences")),
                firstTextFromArray(root.path("data").path("sentences")));

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
                root.path("result").path(0).path("confidence"),
                root.path("result").path(0).path("score"),
                root.path("sentences").path(0).path("confidence"),
                root.path("sentences").path(0).path("score"),
                root.path("data").path("confidence"),
                root.path("data").path("score"),
                root.path("data").path("result").path(0).path("confidence"),
                root.path("data").path("result").path(0).path("score"),
                root.path("data").path("sentences").path(0).path("confidence"),
                root.path("data").path("sentences").path(0).path("score"));
        if (confidence < 0d) {
            confidence = ingressEvent.payload().endOfStream() ? DEFAULT_CONFIDENCE_FINAL : DEFAULT_CONFIDENCE_NON_FINAL;
        }

        Boolean stable = firstBooleanLike(
                root.path("stable"),
                root.path("is_final"),
                root.path("final"),
                root.path("result").path(0).path("stable"),
                root.path("result").path(0).path("is_final"),
                root.path("result").path(0).path("final"),
                root.path("sentences").path(0).path("stable"),
                root.path("sentences").path(0).path("is_final"),
                root.path("sentences").path(0).path("final"),
                root.path("data").path("stable"),
                root.path("data").path("is_final"),
                root.path("data").path("final"),
                root.path("data").path("result").path(0).path("stable"),
                root.path("data").path("result").path(0).path("is_final"),
                root.path("data").path("result").path(0).path("final"),
                root.path("data").path("sentences").path(0).path("stable"),
                root.path("data").path("sentences").path(0).path("is_final"),
                root.path("data").path("sentences").path(0).path("final"));
        boolean resolvedStable = stable == null ? ingressEvent.payload().endOfStream() : stable;

        return new AsrInferenceResult(text, language, confidence, resolvedStable);
    }

    private void assertProviderSuccess(JsonNode root, String sessionId) {
        JsonNode codeNode = firstPresentNode(
                root.path("code"),
                root.path("status"),
                root.path("status_code"),
                root.path("data").path("code"),
                root.path("data").path("status"),
                root.path("data").path("status_code"));
        if (codeNode == null) {
            return;
        }

        if (isSuccessCode(codeNode)) {
            return;
        }

        String message = firstNonBlank(
                textField(root, "message"),
                textField(root, "msg"),
                textField(root, "error"),
                textField(root.path("data"), "message"),
                textField(root.path("data"), "msg"),
                textField(root.path("data"), "error"),
                "unknown");
        throw new IllegalStateException(
                "FunASR provider error for session "
                        + sessionId
                        + " (code="
                        + codeNode.asText()
                        + ", message="
                        + message
                        + ")");
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
        return firstNonBlank(
                first.path("text").asText(""),
                first.path("sentence").asText(""),
                first.path("transcript").asText(""),
                first.path("result").asText(""));
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
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (node.isNumber()) {
                return node.asDouble();
            }
            if (node.isTextual()) {
                try {
                    return Double.parseDouble(node.asText().trim());
                } catch (NumberFormatException ignored) {
                    // ignored
                }
            }
        }
        return -1d;
    }

    private Boolean firstBooleanLike(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isNumber()) {
                int numeric = node.asInt();
                if (numeric == 1) {
                    return true;
                }
                if (numeric == 0) {
                    return false;
                }
            }
            if (node.isTextual()) {
                String raw = node.asText().trim();
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

    private JsonNode firstPresentNode(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private boolean isSuccessCode(JsonNode codeNode) {
        if (codeNode.isNumber()) {
            int value = codeNode.asInt();
            return value == 0 || value == 200;
        }
        if (codeNode.isBoolean()) {
            return codeNode.asBoolean();
        }
        if (codeNode.isTextual()) {
            String raw = codeNode.asText().trim();
            if (raw.isEmpty()) {
                return false;
            }
            if ("ok".equalsIgnoreCase(raw) || "success".equalsIgnoreCase(raw)) {
                return true;
            }
            try {
                int value = Integer.parseInt(raw);
                return value == 0 || value == 200;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }
}
