package com.kafkaasr.command.policy;

import com.kafkaasr.command.events.CommandKafkaProperties;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class TenantRoutingPolicyResolver {

    private final WebClient webClient;
    private final CommandControlPlaneProperties controlPlaneProperties;
    private final CommandKafkaProperties kafkaProperties;
    private final Clock clock;
    private final Map<String, CachedPolicy> cachedPolicies = new ConcurrentHashMap<>();

    @Autowired
    public TenantRoutingPolicyResolver(
            WebClient.Builder webClientBuilder,
            CommandControlPlaneProperties controlPlaneProperties,
            CommandKafkaProperties kafkaProperties) {
        this(
                webClientBuilder.baseUrl(controlPlaneProperties.getBaseUrl()).build(),
                controlPlaneProperties,
                kafkaProperties,
                Clock.systemUTC());
    }

    TenantRoutingPolicyResolver(
            WebClient webClient,
            CommandControlPlaneProperties controlPlaneProperties,
            CommandKafkaProperties kafkaProperties,
            Clock clock) {
        this.webClient = webClient;
        this.controlPlaneProperties = controlPlaneProperties;
        this.kafkaProperties = kafkaProperties;
        this.clock = clock;
    }

    public TenantRoutingPolicy resolve(String tenantId) {
        TenantRoutingPolicy defaults = defaultPolicy();
        if (!controlPlaneProperties.isEnabled() || tenantId == null || tenantId.isBlank()) {
            return defaults;
        }

        long now = clock.millis();
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

            TenantRoutingPolicy resolved = normalizePolicy(response, defaults);
            long ttlMs = response.controlPlaneFallbackCacheTtlMs() > 0
                    ? response.controlPlaneFallbackCacheTtlMs()
                    : Math.max(1L, controlPlaneProperties.getPolicyCacheTtl().toMillis());
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

    public void invalidateTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        cachedPolicies.remove(tenantId);
    }

    public TenantRoutingPolicy defaultPolicy() {
        return new TenantRoutingPolicy(
                kafkaProperties.getRetryMaxAttempts(),
                kafkaProperties.getRetryBackoffMs(),
                kafkaProperties.getDlqTopicSuffix(),
                TenantRoutingPolicy.SESSION_MODE_TRANSLATION);
    }

    private TenantRoutingPolicy normalizePolicy(
            ControlPlaneTenantPolicyResponse response,
            TenantRoutingPolicy defaults) {
        int retryMaxAttempts = response.retryMaxAttempts() > 0
                ? response.retryMaxAttempts()
                : defaults.retryMaxAttempts();
        long retryBackoffMs = response.retryBackoffMs() > 0
                ? response.retryBackoffMs()
                : defaults.retryBackoffMs();
        String dlqTopicSuffix = response.dlqTopicSuffix() == null || response.dlqTopicSuffix().isBlank()
                ? defaults.dlqTopicSuffix()
                : response.dlqTopicSuffix();
        String sessionMode = response.sessionMode();
        return new TenantRoutingPolicy(
                retryMaxAttempts,
                retryBackoffMs,
                dlqTopicSuffix,
                sessionMode);
    }

    private TenantRoutingPolicy fallback(
            String tenantId,
            CachedPolicy cached,
            long now,
            TenantRoutingPolicy defaults) {
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

    private record CachedPolicy(
            TenantRoutingPolicy policy,
            long expiresAtMs,
            boolean fallbackFailOpen) {
    }
}
