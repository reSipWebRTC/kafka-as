package com.kafkaasr.tts.policy;

import com.kafkaasr.tts.events.TtsKafkaProperties;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class TenantReliabilityPolicyResolver {

    private final WebClient webClient;
    private final TtsControlPlaneProperties controlPlaneProperties;
    private final TtsKafkaProperties kafkaProperties;
    private final Clock clock;
    private final Map<String, CachedPolicy> cachedPolicies = new ConcurrentHashMap<>();

    @Autowired
    public TenantReliabilityPolicyResolver(
            WebClient.Builder webClientBuilder,
            TtsControlPlaneProperties controlPlaneProperties,
            TtsKafkaProperties kafkaProperties) {
        this(
                webClientBuilder.baseUrl(controlPlaneProperties.getBaseUrl()).build(),
                controlPlaneProperties,
                kafkaProperties,
                Clock.systemUTC());
    }

    TenantReliabilityPolicyResolver(
            WebClient webClient,
            TtsControlPlaneProperties controlPlaneProperties,
            TtsKafkaProperties kafkaProperties,
            Clock clock) {
        this.webClient = webClient;
        this.controlPlaneProperties = controlPlaneProperties;
        this.kafkaProperties = kafkaProperties;
        this.clock = clock;
    }

    public TenantReliabilityPolicy resolve(String tenantId) {
        TenantReliabilityPolicy defaults = defaultPolicy();
        if (!controlPlaneProperties.isEnabled() || tenantId == null || tenantId.isBlank()) {
            return defaults;
        }

        long now = nowMs();
        CachedPolicy cached = cachedPolicies.get(tenantId);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.policy();
        }

        try {
            ControlPlaneTenantPolicyResponse response = webClient.get()
                    .uri("/api/v1/tenants/{tenantId}/policy", tenantId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(ControlPlaneTenantPolicyResponse.class)
                    .timeout(controlPlaneProperties.getRequestTimeout())
                    .block();
            if (response == null) {
                return fallback(tenantId, cached, now, defaults);
            }

            TenantReliabilityPolicy resolved = normalizePolicy(response, defaults);
            long ttlMs = resolveCacheTtlMs(response);
            cachedPolicies.put(
                    tenantId,
                    new CachedPolicy(
                            resolved,
                            now + ttlMs,
                            response.controlPlaneFallbackFailOpen()));
            return resolved;
        } catch (RuntimeException ignored) {
            return fallback(tenantId, cached, now, defaults);
        }
    }

    public TenantReliabilityPolicy defaultPolicy() {
        return new TenantReliabilityPolicy(
                kafkaProperties.getRetryMaxAttempts(),
                kafkaProperties.getRetryBackoffMs(),
                kafkaProperties.getDlqTopicSuffix());
    }

    private TenantReliabilityPolicy fallback(
            String tenantId,
            CachedPolicy cached,
            long now,
            TenantReliabilityPolicy defaults) {
        if (cached == null) {
            return defaults;
        }
        if (cached.expiresAtMs() <= now) {
            cachedPolicies.remove(tenantId, cached);
            return defaults;
        }
        if (controlPlaneProperties.isFallbackFailOpen() || cached.fallbackFailOpen()) {
            return cached.policy();
        }
        return defaults;
    }

    private TenantReliabilityPolicy normalizePolicy(
            ControlPlaneTenantPolicyResponse response,
            TenantReliabilityPolicy defaults) {
        int retryMaxAttempts = response.retryMaxAttempts() > 0
                ? response.retryMaxAttempts()
                : defaults.retryMaxAttempts();
        long retryBackoffMs = response.retryBackoffMs() > 0
                ? response.retryBackoffMs()
                : defaults.retryBackoffMs();
        String dlqTopicSuffix = response.dlqTopicSuffix() == null || response.dlqTopicSuffix().isBlank()
                ? defaults.dlqTopicSuffix()
                : response.dlqTopicSuffix();
        return new TenantReliabilityPolicy(
                retryMaxAttempts,
                retryBackoffMs,
                dlqTopicSuffix);
    }

    private long resolveCacheTtlMs(ControlPlaneTenantPolicyResponse response) {
        if (response.controlPlaneFallbackCacheTtlMs() > 0) {
            return response.controlPlaneFallbackCacheTtlMs();
        }
        return Math.max(1L, controlPlaneProperties.getPolicyCacheTtl().toMillis());
    }

    private long nowMs() {
        return clock.millis();
    }

    private record CachedPolicy(
            TenantReliabilityPolicy policy,
            long expiresAtMs,
            boolean fallbackFailOpen) {
    }
}
