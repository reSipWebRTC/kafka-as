package com.kafkaasr.translation.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.translation.events.AsrFinalEvent;
import com.kafkaasr.translation.events.AsrFinalPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Primary
@Component
@ConditionalOnProperty(prefix = "translation.engine", name = "mode", havingValue = "openai")
public class OpenaiTranslationEngine implements TranslationEngine {

    private static final String FALLBACK_LANGUAGE = "und";

    private final TranslationEngineProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Semaphore concurrentGuard;
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);
    private final AtomicReference<HealthSnapshot> healthSnapshot =
            new AtomicReference<>(new HealthSnapshot(true, 0L));
    private final Object healthRefreshLock = new Object();

    public OpenaiTranslationEngine(
            TranslationEngineProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;

        String endpoint = properties.getOpenai().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "translation.engine.openai.endpoint must be set when translation.engine.mode=openai");
        }
        this.webClient = webClientBuilder.baseUrl(endpoint).build();
        this.concurrentGuard = new Semaphore(properties.getOpenai().getMaxConcurrentRequests(), true);
        meterRegistry.gauge("translation.openai.inflight", inFlightRequests);
    }

    @Override
    public TranslationResult translate(AsrFinalEvent asrFinalEvent, String targetLang) {
        String sessionId = asrFinalEvent == null ? "unknown" : asrFinalEvent.sessionId();
        if (!concurrentGuard.tryAcquire()) {
            meterRegistry.counter(
                            "translation.openai.concurrency.rejected.total",
                            "code",
                            "TRANSLATION_PROVIDER_BUSY")
                    .increment();
            throw new TranslationEngineException(
                    "TRANSLATION_PROVIDER_BUSY",
                    "OpenAI concurrency limit reached for session " + sessionId,
                    true);
        }
        inFlightRequests.incrementAndGet();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (asrFinalEvent == null) {
                throw new TranslationEngineException(
                        "TRANSLATION_INVALID_REQUEST",
                        "asr.final event must not be null",
                        false);
            }
            AsrFinalPayload payload = asrFinalEvent.payload();
            if (payload == null) {
                throw new IllegalArgumentException(
                        "Missing asr.final payload for session " + asrFinalEvent.sessionId());
            }

            String sourceText = payload.text() == null ? "" : payload.text();
            String sourceLang = normalizeLanguage(payload.language());
            String normalizedTargetLang = normalizeLanguage(targetLang);
            TranslationEngineProperties.Openai config = properties.getOpenai();

            assertProviderHealthy(sessionId);

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
            meterRegistry.counter(
                            "translation.openai.requests.total",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
            return new TranslationResult(translatedText, sourceLang, normalizedTargetLang, config.getEngineName());
        } catch (RuntimeException exception) {
            TranslationEngineException mapped = toEngineException(exception, sessionId);
            meterRegistry.counter(
                            "translation.openai.requests.total",
                            "result",
                            "error",
                            "code",
                            mapped.errorCode())
                    .increment();
            throw mapped;
        } finally {
            sample.stop(meterRegistry.timer("translation.openai.request.duration"));
            inFlightRequests.decrementAndGet();
            concurrentGuard.release();
        }
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
            throw new TranslationEngineException(
                    "TRANSLATION_PROVIDER_BAD_RESPONSE",
                    "Empty OpenAI translation response for session " + sessionId,
                    true);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new TranslationEngineException(
                    "TRANSLATION_PROVIDER_BAD_RESPONSE",
                    "Invalid OpenAI translation response for session " + sessionId,
                    true,
                    exception);
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
            throw new TranslationEngineException(
                    "TRANSLATION_PROVIDER_EMPTY_TEXT",
                    "Empty OpenAI translated text for session " + sessionId,
                    true);
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
            throw toProviderErrorException(errorNode, sessionId);
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
        String normalized = status.toLowerCase(Locale.ROOT);
        if ("failed".equals(normalized) || "error".equals(normalized)) {
            throw new TranslationEngineException(
                    "TRANSLATION_PROVIDER_FAILURE",
                    "OpenAI provider status is failed for session " + sessionId + " (status=" + status + ")",
                    true);
        }
        throw new TranslationEngineException(
                "TRANSLATION_PROVIDER_REJECTED",
                "OpenAI provider status is not successful for session " + sessionId + " (status=" + status + ")",
                false);
    }

    private TranslationEngineException toProviderErrorException(JsonNode errorNode, String sessionId) {
        String message = firstNonBlank(
                errorNode.path("message").asText(""),
                errorNode.path("code").asText(""),
                errorNode.path("type").asText(""),
                "unknown");
        String code = errorNode.path("code").asText("");
        String type = errorNode.path("type").asText("");
        String detail = (message + " " + code + " " + type).toLowerCase(Locale.ROOT);

        if (detail.contains("rate") || detail.contains("429")) {
            return new TranslationEngineException(
                    "TRANSLATION_PROVIDER_RATE_LIMIT",
                    "OpenAI provider rate-limited for session " + sessionId + " (message=" + message + ")",
                    true);
        }
        if (detail.contains("unavailable")
                || detail.contains("overload")
                || detail.contains("temporar")
                || detail.contains("server_error")) {
            return new TranslationEngineException(
                    "TRANSLATION_PROVIDER_UNAVAILABLE",
                    "OpenAI provider unavailable for session " + sessionId + " (message=" + message + ")",
                    true);
        }
        if (detail.contains("invalid")
                || detail.contains("unsupported")
                || detail.contains("auth")
                || detail.contains("permission")
                || detail.contains("context_length")) {
            return new TranslationEngineException(
                    "TRANSLATION_PROVIDER_REJECTED",
                    "OpenAI provider rejected request for session " + sessionId + " (message=" + message + ")",
                    false);
        }
        return new TranslationEngineException(
                "TRANSLATION_PROVIDER_FAILURE",
                "OpenAI provider error for session " + sessionId + " (message=" + message + ")",
                true);
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

    private void assertProviderHealthy(String sessionId) {
        TranslationEngineProperties.Health healthProperties = properties.getOpenai().getHealth();
        if (healthProperties == null || !healthProperties.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        HealthSnapshot snapshot = healthSnapshot.get();
        if (snapshot.expiresAtMs() > now) {
            if (!snapshot.healthy()) {
                throw new TranslationEngineException(
                        "TRANSLATION_PROVIDER_UNHEALTHY",
                        "OpenAI health check is unhealthy for session " + sessionId,
                        true);
            }
            return;
        }

        synchronized (healthRefreshLock) {
            snapshot = healthSnapshot.get();
            if (snapshot.expiresAtMs() > now) {
                if (!snapshot.healthy()) {
                    throw new TranslationEngineException(
                            "TRANSLATION_PROVIDER_UNHEALTHY",
                            "OpenAI health check is unhealthy for session " + sessionId,
                            true);
                }
                return;
            }

            try {
                String responseBody = webClient
                        .get()
                        .uri(healthProperties.getPath())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofMillis(healthProperties.getTimeoutMs()))
                        .block();
                boolean healthy = parseHealthResponse(responseBody);
                cacheHealth(healthy, now, healthProperties.getCacheTtlMs());
                meterRegistry.counter(
                                "translation.openai.healthcheck.total",
                                "result",
                                healthy ? "up" : "down")
                        .increment();
                if (!healthy) {
                    throw new TranslationEngineException(
                            "TRANSLATION_PROVIDER_UNHEALTHY",
                            "OpenAI health check is unhealthy for session " + sessionId,
                            true);
                }
            } catch (TranslationEngineException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (healthProperties.isFailOpenOnError()) {
                    cacheHealth(true, now, healthProperties.getCacheTtlMs());
                    meterRegistry.counter(
                                    "translation.openai.healthcheck.total",
                                    "result",
                                    "error_fail_open")
                            .increment();
                    return;
                }
                cacheHealth(false, now, healthProperties.getCacheTtlMs());
                meterRegistry.counter(
                                "translation.openai.healthcheck.total",
                                "result",
                                "error")
                        .increment();
                throw new TranslationEngineException(
                        "TRANSLATION_PROVIDER_UNHEALTHY",
                        "OpenAI health check failed for session " + sessionId,
                        true,
                        exception);
            }
        }
    }

    private boolean parseHealthResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode healthyNode = firstPresentNode(
                    root.path("healthy"),
                    root.path("ok"),
                    root.path("ready"));
            if (healthyNode != null) {
                Boolean healthy = firstBooleanLike(healthyNode);
                if (healthy != null) {
                    return healthy;
                }
            }

            JsonNode statusNode = firstPresentNode(
                    root.path("status"),
                    root.path("state"));
            if (statusNode == null || !statusNode.isTextual()) {
                return true;
            }
            String status = statusNode.asText().trim();
            if (status.isEmpty()) {
                return true;
            }
            return "up".equalsIgnoreCase(status)
                    || "ok".equalsIgnoreCase(status)
                    || "healthy".equalsIgnoreCase(status)
                    || "ready".equalsIgnoreCase(status)
                    || "success".equalsIgnoreCase(status);
        } catch (JsonProcessingException exception) {
            return true;
        }
    }

    private void cacheHealth(boolean healthy, long nowMs, long ttlMs) {
        long expiresAt = nowMs + Math.max(100L, ttlMs);
        healthSnapshot.set(new HealthSnapshot(healthy, expiresAt));
    }

    private TranslationEngineException toEngineException(RuntimeException exception, String sessionId) {
        if (exception instanceof TranslationEngineException translationEngineException) {
            return translationEngineException;
        }

        Throwable root = rootCause(exception);
        if (root instanceof TimeoutException) {
            return new TranslationEngineException(
                    "TRANSLATION_TIMEOUT",
                    "OpenAI translation timeout for session " + sessionId,
                    true,
                    exception);
        }
        if (root instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            if (status == 429) {
                return new TranslationEngineException(
                        "TRANSLATION_PROVIDER_RATE_LIMIT",
                        "OpenAI returned HTTP 429 for session " + sessionId,
                        true,
                        exception);
            }
            if (status >= 500) {
                return new TranslationEngineException(
                        "TRANSLATION_PROVIDER_UNAVAILABLE",
                        "OpenAI returned HTTP " + status + " for session " + sessionId,
                        true,
                        exception);
            }
            return new TranslationEngineException(
                    "TRANSLATION_PROVIDER_REJECTED",
                    "OpenAI returned HTTP " + status + " for session " + sessionId,
                    false,
                    exception);
        }
        if (root instanceof WebClientRequestException) {
            return new TranslationEngineException(
                    "TRANSLATION_PROVIDER_UNAVAILABLE",
                    "OpenAI request failed for session " + sessionId,
                    true,
                    exception);
        }
        if (exception instanceof IllegalArgumentException) {
            return new TranslationEngineException(
                    "TRANSLATION_INVALID_PAYLOAD",
                    exception.getMessage() == null
                            ? "Invalid asr.final payload for session " + sessionId
                            : exception.getMessage(),
                    false,
                    exception);
        }
        return new TranslationEngineException(
                "TRANSLATION_PROVIDER_FAILURE",
                "OpenAI translation failed for session " + sessionId,
                true,
                exception);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private JsonNode firstPresentNode(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isNull() && !node.isMissingNode()) {
                return node;
            }
        }
        return null;
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

    private record HealthSnapshot(boolean healthy, long expiresAtMs) {
    }
}
