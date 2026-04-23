package com.kafkaasr.tts.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.TranslationResultEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
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
@ConditionalOnProperty(prefix = "tts.synthesis", name = "mode", havingValue = "http")
public class HttpTtsSynthesisEngine implements TtsSynthesisEngine {

    private final TtsSynthesisProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Semaphore concurrentGuard;
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);
    private final AtomicReference<HealthSnapshot> healthSnapshot =
            new AtomicReference<>(new HealthSnapshot(true, 0L));
    private final Object healthRefreshLock = new Object();

    public HttpTtsSynthesisEngine(
            TtsSynthesisProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        String endpoint = properties.getHttp().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "tts.synthesis.http.endpoint must be set when tts.synthesis.mode=http");
        }
        this.webClient = webClientBuilder.baseUrl(endpoint).build();
        this.concurrentGuard = new Semaphore(properties.getHttp().getMaxConcurrentRequests(), true);
        meterRegistry.gauge("tts.synthesis.http.inflight", inFlightRequests);
    }

    @Override
    public SynthesisPlan synthesize(TranslationResultEvent sourceEvent, SynthesisInput input) {
        String sessionId = sourceEvent == null ? "unknown" : sourceEvent.sessionId();
        if (!concurrentGuard.tryAcquire()) {
            meterRegistry.counter(
                            "tts.synthesis.http.concurrency.rejected.total",
                            "code",
                            "TTS_PROVIDER_BUSY")
                    .increment();
            throw new TtsSynthesisException(
                    "TTS_PROVIDER_BUSY",
                    "TTS synthesis concurrency limit reached for session " + sessionId,
                    true);
        }
        inFlightRequests.incrementAndGet();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (input == null) {
                throw new TtsSynthesisException(
                        "TTS_INVALID_REQUEST",
                        "tts synthesis input must not be null",
                        false);
            }

            assertProviderHealthy(sessionId);

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

            SynthesisPlan plan = mergePlan(input, responseBody, sessionId);
            meterRegistry.counter(
                            "tts.synthesis.http.requests.total",
                            "result",
                            "success",
                            "code",
                            "OK")
                    .increment();
            return plan;
        } catch (RuntimeException exception) {
            TtsSynthesisException mapped = toSynthesisException(exception, sessionId);
            meterRegistry.counter(
                            "tts.synthesis.http.requests.total",
                            "result",
                            "error",
                            "code",
                            mapped.errorCode())
                    .increment();
            throw mapped;
        } finally {
            sample.stop(meterRegistry.timer("tts.synthesis.http.request.duration"));
            inFlightRequests.decrementAndGet();
            concurrentGuard.release();
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

    private SynthesisPlan mergePlan(SynthesisInput input, String responseBody, String sessionId) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new TtsSynthesisException(
                    "TTS_PROVIDER_BAD_RESPONSE",
                    "Empty TTS synthesis response for session " + sessionId,
                    true);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new TtsSynthesisException(
                    "TTS_PROVIDER_BAD_RESPONSE",
                    "Invalid TTS synthesis response for session " + sessionId,
                    true,
                    exception);
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
            throw toProviderErrorException(errorNode, sessionId);
        }

        JsonNode codeNode = firstPresentNode(
                root.path("code"),
                root.path("status_code"),
                root.path("data").path("code"),
                root.path("data").path("status_code"));
        if (codeNode != null && !isSuccessCode(codeNode)) {
            throw toProviderCodeException(codeNode, root, sessionId);
        }

        JsonNode statusNode = firstPresentNode(
                root.path("status"),
                root.path("state"),
                root.path("data").path("status"),
                root.path("data").path("state"));
        if (statusNode != null && !isSuccessStatus(statusNode)) {
            String status = statusNode.asText("");
            String normalized = status.toLowerCase(Locale.ROOT);
            if ("failed".equals(normalized) || "error".equals(normalized)) {
                throw new TtsSynthesisException(
                        "TTS_PROVIDER_FAILURE",
                        "TTS provider status is failed for session " + sessionId + " (status=" + status + ")",
                        true);
            }
            throw new TtsSynthesisException(
                    "TTS_PROVIDER_REJECTED",
                    "TTS provider status is not successful for session " + sessionId + " (status=" + status + ")",
                    false);
        }
    }

    private TtsSynthesisException toProviderErrorException(JsonNode errorNode, String sessionId) {
        String message = firstNonBlank(
                textField(errorNode, "message"),
                textField(errorNode, "msg"),
                textField(errorNode, "code"),
                "unknown");
        String code = textField(errorNode, "code");
        String type = textField(errorNode, "type");
        String detail = (message + " " + code + " " + type).toLowerCase(Locale.ROOT);

        if (detail.contains("rate")
                || detail.contains("quota")
                || detail.contains("429")) {
            return new TtsSynthesisException(
                    "TTS_PROVIDER_RATE_LIMIT",
                    "TTS provider rate-limited for session " + sessionId + " (message=" + message + ")",
                    true);
        }
        if (detail.contains("unavailable")
                || detail.contains("overload")
                || detail.contains("temporar")
                || detail.contains("server_error")) {
            return new TtsSynthesisException(
                    "TTS_PROVIDER_UNAVAILABLE",
                    "TTS provider unavailable for session " + sessionId + " (message=" + message + ")",
                    true);
        }
        if (detail.contains("invalid")
                || detail.contains("unsupported")
                || detail.contains("auth")
                || detail.contains("permission")) {
            return new TtsSynthesisException(
                    "TTS_PROVIDER_REJECTED",
                    "TTS provider rejected request for session " + sessionId + " (message=" + message + ")",
                    false);
        }
        return new TtsSynthesisException(
                "TTS_PROVIDER_FAILURE",
                "TTS provider error for session " + sessionId + " (message=" + message + ")",
                true);
    }

    private TtsSynthesisException toProviderCodeException(JsonNode codeNode, JsonNode root, String sessionId) {
        String code = codeNode.asText("");
        String message = firstNonBlank(
                textField(root, "message"),
                textField(root, "msg"),
                textField(root, "error"),
                textField(root.path("data"), "message"),
                textField(root.path("data"), "msg"),
                textField(root.path("data"), "error"),
                "unknown");
        Integer numericCode = parseInteger(code);
        if (numericCode != null) {
            if (numericCode == 429) {
                return new TtsSynthesisException(
                        "TTS_PROVIDER_RATE_LIMIT",
                        "TTS provider rate-limited for session " + sessionId + " (message=" + message + ")",
                        true);
            }
            if (numericCode == 503 || numericCode == 502 || numericCode == 504) {
                return new TtsSynthesisException(
                        "TTS_PROVIDER_UNAVAILABLE",
                        "TTS provider unavailable for session "
                                + sessionId
                                + " (code="
                                + code
                                + ", message="
                                + message
                                + ")",
                        true);
            }
            if (numericCode >= 500) {
                return new TtsSynthesisException(
                        "TTS_PROVIDER_FAILURE",
                        "TTS provider failure for session "
                                + sessionId
                                + " (code="
                                + code
                                + ", message="
                                + message
                                + ")",
                        true);
            }
        }
        return new TtsSynthesisException(
                "TTS_PROVIDER_REJECTED",
                "TTS provider rejected request for session "
                        + sessionId
                        + " (code="
                        + code
                        + ", message="
                        + message
                        + ")",
                false);
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

    private void assertProviderHealthy(String sessionId) {
        TtsSynthesisProperties.Health healthProperties = properties.getHttp().getHealth();
        if (healthProperties == null || !healthProperties.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        HealthSnapshot snapshot = healthSnapshot.get();
        if (snapshot.expiresAtMs() > now) {
            if (!snapshot.healthy()) {
                throw new TtsSynthesisException(
                        "TTS_PROVIDER_UNHEALTHY",
                        "TTS health check is unhealthy for session " + sessionId,
                        true);
            }
            return;
        }

        synchronized (healthRefreshLock) {
            snapshot = healthSnapshot.get();
            if (snapshot.expiresAtMs() > now) {
                if (!snapshot.healthy()) {
                    throw new TtsSynthesisException(
                            "TTS_PROVIDER_UNHEALTHY",
                            "TTS health check is unhealthy for session " + sessionId,
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
                                "tts.synthesis.http.healthcheck.total",
                                "result",
                                healthy ? "up" : "down")
                        .increment();
                if (!healthy) {
                    throw new TtsSynthesisException(
                            "TTS_PROVIDER_UNHEALTHY",
                            "TTS health check is unhealthy for session " + sessionId,
                            true);
                }
            } catch (TtsSynthesisException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (healthProperties.isFailOpenOnError()) {
                    cacheHealth(true, now, healthProperties.getCacheTtlMs());
                    meterRegistry.counter(
                                    "tts.synthesis.http.healthcheck.total",
                                    "result",
                                    "error_fail_open")
                            .increment();
                    return;
                }
                cacheHealth(false, now, healthProperties.getCacheTtlMs());
                meterRegistry.counter(
                                "tts.synthesis.http.healthcheck.total",
                                "result",
                                "error")
                        .increment();
                throw new TtsSynthesisException(
                        "TTS_PROVIDER_UNHEALTHY",
                        "TTS health check failed for session " + sessionId,
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

    private TtsSynthesisException toSynthesisException(RuntimeException exception, String sessionId) {
        if (exception instanceof TtsSynthesisException ttsSynthesisException) {
            return ttsSynthesisException;
        }

        Throwable root = rootCause(exception);
        if (root instanceof TimeoutException) {
            return new TtsSynthesisException(
                    "TTS_TIMEOUT",
                    "TTS synthesis timeout for session " + sessionId,
                    true,
                    exception);
        }
        if (root instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            if (status == 429) {
                return new TtsSynthesisException(
                        "TTS_PROVIDER_RATE_LIMIT",
                        "TTS provider returned HTTP 429 for session " + sessionId,
                        true,
                        exception);
            }
            if (status >= 500) {
                return new TtsSynthesisException(
                        "TTS_PROVIDER_UNAVAILABLE",
                        "TTS provider returned HTTP " + status + " for session " + sessionId,
                        true,
                        exception);
            }
            return new TtsSynthesisException(
                    "TTS_PROVIDER_REJECTED",
                    "TTS provider returned HTTP " + status + " for session " + sessionId,
                    false,
                    exception);
        }
        if (root instanceof WebClientRequestException) {
            return new TtsSynthesisException(
                    "TTS_PROVIDER_UNAVAILABLE",
                    "TTS provider request failed for session " + sessionId,
                    true,
                    exception);
        }
        if (exception instanceof IllegalArgumentException) {
            return new TtsSynthesisException(
                    "TTS_INVALID_REQUEST",
                    exception.getMessage() == null
                            ? "Invalid synthesis request for session " + sessionId
                            : exception.getMessage(),
                    false,
                    exception);
        }
        return new TtsSynthesisException(
                "TTS_PROVIDER_FAILURE",
                "TTS synthesis failed for session " + sessionId,
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

    private record HealthSnapshot(boolean healthy, long expiresAtMs) {
    }
}
