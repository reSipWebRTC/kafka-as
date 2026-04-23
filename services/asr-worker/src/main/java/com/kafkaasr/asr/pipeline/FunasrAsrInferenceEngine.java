package com.kafkaasr.asr.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.events.AudioIngressRawPayload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
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
@ConditionalOnProperty(prefix = "asr.inference", name = "mode", havingValue = "funasr")
public class FunasrAsrInferenceEngine implements AsrInferenceEngine {

    private static final double DEFAULT_CONFIDENCE_NON_FINAL = 0.6d;
    private static final double DEFAULT_CONFIDENCE_FINAL = 0.95d;
    private static final String DEFAULT_LANGUAGE = "und";

    private final AsrInferenceProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Semaphore concurrentGuard;
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);
    private final AtomicReference<HealthSnapshot> healthSnapshot =
            new AtomicReference<>(new HealthSnapshot(true, 0L));
    private final Object healthRefreshLock = new Object();

    public FunasrAsrInferenceEngine(
            AsrInferenceProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;

        String endpoint = properties.getFunasr().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("asr.inference.funasr.endpoint must be set when asr.inference.mode=funasr");
        }
        this.webClient = webClientBuilder.baseUrl(endpoint).build();
        this.concurrentGuard = new Semaphore(properties.getFunasr().getMaxConcurrentRequests(), true);
        meterRegistry.gauge("asr.funasr.inflight", inFlightRequests);
    }

    @Override
    public AsrInferenceResult infer(AudioIngressRawEvent ingressEvent) {
        String sessionId = ingressEvent == null ? "unknown" : ingressEvent.sessionId();
        if (!concurrentGuard.tryAcquire()) {
            meterRegistry.counter(
                            "asr.funasr.concurrency.rejected.total",
                            "code",
                            "ASR_PROVIDER_BUSY")
                    .increment();
            throw new AsrEngineException(
                    "ASR_PROVIDER_BUSY",
                    "FunASR concurrency limit reached for session " + sessionId,
                    true);
        }
        inFlightRequests.incrementAndGet();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (ingressEvent == null) {
                throw new AsrEngineException(
                        "ASR_INVALID_REQUEST",
                        "asr ingress event must not be null",
                        false);
            }
            AudioIngressRawPayload payload = ingressEvent.payload();
            if (payload == null) {
                throw new IllegalArgumentException("Missing audio payload for session " + sessionId);
            }

            assertProviderHealthy(sessionId);
            validateBase64(payload.audioBase64(), sessionId);

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

            AsrInferenceResult result = parseResponse(responseBody, ingressEvent);
            meterRegistry.counter(
                            "asr.funasr.requests.total",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
            return result;
        } catch (RuntimeException exception) {
            AsrEngineException mapped = toEngineException(exception, sessionId);
            meterRegistry.counter(
                            "asr.funasr.requests.total",
                            "result",
                            "error",
                            "code",
                            mapped.errorCode())
                    .increment();
            throw mapped;
        } finally {
            sample.stop(meterRegistry.timer("asr.funasr.request.duration"));
            inFlightRequests.decrementAndGet();
            concurrentGuard.release();
        }
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
            throw new AsrEngineException(
                    "ASR_PROVIDER_BAD_RESPONSE",
                    "Empty FunASR response for session " + ingressEvent.sessionId(),
                    true);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new AsrEngineException(
                    "ASR_PROVIDER_BAD_RESPONSE",
                    "Invalid FunASR response for session " + ingressEvent.sessionId(),
                    true,
                    exception);
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
            throw new AsrEngineException(
                    "ASR_PROVIDER_EMPTY_TRANSCRIPT",
                    "Empty FunASR transcript for session " + ingressEvent.sessionId(),
                    true);
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
        throw toProviderBusinessException(codeNode.asText(), message, sessionId);
    }

    private void validateBase64(String audioBase64, String sessionId) {
        if (audioBase64 == null || audioBase64.isBlank()) {
            throw new AsrEngineException(
                    "ASR_INVALID_AUDIO",
                    "Missing audioBase64 payload for session " + sessionId,
                    false);
        }
        try {
            Base64.getDecoder().decode(audioBase64);
        } catch (IllegalArgumentException exception) {
            throw new AsrEngineException(
                    "ASR_INVALID_AUDIO",
                    "Invalid audioBase64 payload for session " + sessionId,
                    false,
                    exception);
        }
    }

    private void assertProviderHealthy(String sessionId) {
        AsrInferenceProperties.Health healthProperties = properties.getFunasr().getHealth();
        if (healthProperties == null || !healthProperties.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        HealthSnapshot snapshot = healthSnapshot.get();
        if (snapshot.expiresAtMs() > now) {
            if (!snapshot.healthy()) {
                throw new AsrEngineException(
                        "ASR_PROVIDER_UNHEALTHY",
                        "FunASR health check is unhealthy for session " + sessionId,
                        true);
            }
            return;
        }

        synchronized (healthRefreshLock) {
            snapshot = healthSnapshot.get();
            if (snapshot.expiresAtMs() > now) {
                if (!snapshot.healthy()) {
                    throw new AsrEngineException(
                            "ASR_PROVIDER_UNHEALTHY",
                            "FunASR health check is unhealthy for session " + sessionId,
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
                                "asr.funasr.healthcheck.total",
                                "result",
                                healthy ? "up" : "down")
                        .increment();
                if (!healthy) {
                    throw new AsrEngineException(
                            "ASR_PROVIDER_UNHEALTHY",
                            "FunASR health check is unhealthy for session " + sessionId,
                            true);
                }
            } catch (AsrEngineException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (healthProperties.isFailOpenOnError()) {
                    cacheHealth(true, now, healthProperties.getCacheTtlMs());
                    meterRegistry.counter(
                                    "asr.funasr.healthcheck.total",
                                    "result",
                                    "error_fail_open")
                            .increment();
                    return;
                }
                cacheHealth(false, now, healthProperties.getCacheTtlMs());
                meterRegistry.counter(
                                "asr.funasr.healthcheck.total",
                                "result",
                                "error")
                        .increment();
                throw new AsrEngineException(
                        "ASR_PROVIDER_UNHEALTHY",
                        "FunASR health check failed for session " + sessionId,
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

    private AsrEngineException toProviderBusinessException(String code, String message, String sessionId) {
        Integer numericCode = parseInteger(code);
        if (numericCode != null) {
            if (numericCode == 429) {
                return new AsrEngineException(
                        "ASR_PROVIDER_RATE_LIMIT",
                        "FunASR provider rate-limited for session " + sessionId + " (message=" + message + ")",
                        true);
            }
            if (numericCode == 503 || numericCode == 502 || numericCode == 504) {
                return new AsrEngineException(
                        "ASR_PROVIDER_UNAVAILABLE",
                        "FunASR provider unavailable for session " + sessionId + " (code=" + code + ", message=" + message + ")",
                        true);
            }
            if (numericCode >= 500) {
                return new AsrEngineException(
                        "ASR_PROVIDER_FAILURE",
                        "FunASR provider failure for session " + sessionId + " (code=" + code + ", message=" + message + ")",
                        true);
            }
        }
        return new AsrEngineException(
                "ASR_PROVIDER_REJECTED",
                "FunASR provider rejected request for session " + sessionId + " (code=" + code + ", message=" + message + ")",
                false);
    }

    private AsrEngineException toEngineException(RuntimeException exception, String sessionId) {
        if (exception instanceof AsrEngineException asrEngineException) {
            return asrEngineException;
        }

        Throwable root = rootCause(exception);
        if (root instanceof TimeoutException) {
            return new AsrEngineException(
                    "ASR_TIMEOUT",
                    "FunASR inference timeout for session " + sessionId,
                    true,
                    exception);
        }
        if (root instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            if (status == 429) {
                return new AsrEngineException(
                        "ASR_PROVIDER_RATE_LIMIT",
                        "FunASR returned HTTP 429 for session " + sessionId,
                        true,
                        exception);
            }
            if (status >= 500) {
                return new AsrEngineException(
                        "ASR_PROVIDER_UNAVAILABLE",
                        "FunASR returned HTTP " + status + " for session " + sessionId,
                        true,
                        exception);
            }
            return new AsrEngineException(
                    "ASR_PROVIDER_REJECTED",
                    "FunASR returned HTTP " + status + " for session " + sessionId,
                    false,
                    exception);
        }
        if (root instanceof WebClientRequestException) {
            return new AsrEngineException(
                    "ASR_PROVIDER_UNAVAILABLE",
                    "FunASR request failed for session " + sessionId,
                    true,
                    exception);
        }
        if (exception instanceof IllegalArgumentException) {
            return new AsrEngineException(
                    "ASR_INVALID_AUDIO",
                    exception.getMessage() == null ? "Invalid audio payload for session " + sessionId : exception.getMessage(),
                    false,
                    exception);
        }
        return new AsrEngineException(
                "ASR_PROVIDER_FAILURE",
                "FunASR inference failed for session " + sessionId,
                true,
                exception);
    }

    private Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
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

    private record HealthSnapshot(boolean healthy, long expiresAtMs) {
    }
}
