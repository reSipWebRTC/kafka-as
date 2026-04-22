package com.kafkaasr.orchestrator.policy;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

@Component
public class ControlPlaneTenantPolicyClient implements TenantPolicyClient {

    private enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final WebClient webClient;
    private final ControlPlaneClientProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<String, CachedPolicy> cachedPolicies = new ConcurrentHashMap<>();
    private final Object breakerMonitor = new Object();

    private CircuitState circuitState = CircuitState.CLOSED;
    private int consecutiveFailures = 0;
    private long breakerOpenUntilMs = 0L;
    private boolean halfOpenProbeInFlight = false;

    @Autowired
    public ControlPlaneTenantPolicyClient(
            WebClient.Builder webClientBuilder,
            ControlPlaneClientProperties properties,
            MeterRegistry meterRegistry) {
        this(
                webClientBuilder.baseUrl(properties.getBaseUrl()).build(),
                properties,
                Clock.systemUTC(),
                meterRegistry);
    }

    ControlPlaneTenantPolicyClient(
            WebClient webClient,
            ControlPlaneClientProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<TenantPolicy> getTenantPolicy(String tenantId) {
        return Mono.defer(() -> {
            if (shouldShortCircuit()) {
                TenantPolicyClientException unavailable = TenantPolicyClientException.unavailable(
                        "Control-plane circuit breaker is open for tenant " + tenantId,
                        null);
                return fallbackOrError(tenantId, "breaker_open", unavailable);
            }

            return fetchTenantPolicy(tenantId)
                    .doOnSuccess(policy -> onFetchSuccess(tenantId, policy))
                    .onErrorResume(TenantPolicyClientException.class, exception -> onFetchFailure(tenantId, exception))
                    .onErrorResume(Throwable.class, throwable -> onFetchFailure(
                            tenantId,
                            TenantPolicyClientException.unavailable(
                                    "Unexpected control-plane failure for tenant " + tenantId,
                                    throwable)));
        });
    }

    private Mono<TenantPolicy> fetchTenantPolicy(String tenantId) {
        return webClient.get()
                .uri("/api/v1/tenants/{tenantId}/policy", tenantId)
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(ControlPlaneTenantPolicyResponse.class)
                                .map(ControlPlaneTenantPolicyResponse::toTenantPolicy);
                    }

                    if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return response.bodyToMono(ControlPlaneErrorResponse.class)
                                .defaultIfEmpty(new ControlPlaneErrorResponse(
                                        "TENANT_POLICY_NOT_FOUND",
                                        "Tenant policy does not exist: " + tenantId,
                                        tenantId))
                                .flatMap(error -> Mono.error(TenantPolicyClientException.notFound(error.message())));
                    }

                    if (response.statusCode().is4xxClientError()) {
                        return response.bodyToMono(ControlPlaneErrorResponse.class)
                                .defaultIfEmpty(new ControlPlaneErrorResponse(
                                        "INVALID_MESSAGE",
                                        "Control-plane rejected tenant policy request",
                                        tenantId))
                                .flatMap(error -> Mono.error(TenantPolicyClientException.rejected(error.message())));
                    }

                    return Mono.error(TenantPolicyClientException.unavailable(
                            "Control-plane returned %s while loading policy for tenant %s"
                                    .formatted(response.statusCode(), tenantId),
                            null));
                })
                .timeout(properties.getRequestTimeout())
                .onErrorMap(TimeoutException.class, exception -> TenantPolicyClientException.unavailable(
                        "Timed out loading tenant policy for " + tenantId,
                        exception))
                .onErrorMap(WebClientRequestException.class, exception -> TenantPolicyClientException.unavailable(
                        "Control-plane request failed for tenant " + tenantId,
                        exception));
    }

    private Mono<TenantPolicy> onFetchFailure(String tenantId, TenantPolicyClientException exception) {
        meterRegistry.counter(
                        "orchestrator.controlplane.policy.fetch.total",
                        "result",
                        "error",
                        "kind",
                        exception.kind().name())
                .increment();

        if (exception.kind() != TenantPolicyClientException.Kind.UNAVAILABLE) {
            markControlPlaneReachable();
            return Mono.error(exception);
        }

        registerUnavailableFailure();
        return fallbackOrError(tenantId, "request_failed", exception);
    }

    private void onFetchSuccess(String tenantId, TenantPolicy policy) {
        meterRegistry.counter(
                        "orchestrator.controlplane.policy.fetch.total",
                        "result",
                        "success",
                        "kind",
                        "OK")
                .increment();
        cachePolicy(tenantId, policy);
        markControlPlaneReachable();
    }

    private boolean shouldShortCircuit() {
        synchronized (breakerMonitor) {
            long now = nowMs();
            if (circuitState == CircuitState.OPEN) {
                if (now < breakerOpenUntilMs) {
                    return true;
                }
                circuitState = CircuitState.HALF_OPEN;
                halfOpenProbeInFlight = false;
            }

            if (circuitState == CircuitState.HALF_OPEN) {
                if (halfOpenProbeInFlight) {
                    return true;
                }
                halfOpenProbeInFlight = true;
            }
            return false;
        }
    }

    private void markControlPlaneReachable() {
        synchronized (breakerMonitor) {
            consecutiveFailures = 0;
            breakerOpenUntilMs = 0L;
            halfOpenProbeInFlight = false;
            circuitState = CircuitState.CLOSED;
        }
    }

    private void registerUnavailableFailure() {
        synchronized (breakerMonitor) {
            if (circuitState == CircuitState.HALF_OPEN) {
                openBreaker("half_open_failure");
                return;
            }

            consecutiveFailures += 1;
            if (consecutiveFailures >= properties.getCircuitBreakerFailureThreshold()) {
                openBreaker("threshold");
            }
        }
    }

    private void openBreaker(String cause) {
        circuitState = CircuitState.OPEN;
        consecutiveFailures = 0;
        halfOpenProbeInFlight = false;
        breakerOpenUntilMs = nowMs() + properties.getCircuitBreakerOpenDuration().toMillis();
        meterRegistry.counter(
                        "orchestrator.controlplane.breaker.open.total",
                        "cause",
                        cause)
                .increment();
    }

    private Mono<TenantPolicy> fallbackOrError(
            String tenantId,
            String reason,
            TenantPolicyClientException cause) {
        CachedPolicy cached = cachedPolicies.get(tenantId);
        long now = nowMs();
        if (cached != null && cached.expiresAtMs() > now && isFailOpenEnabled(cached.policy())) {
            meterRegistry.counter(
                            "orchestrator.controlplane.policy.fallback.total",
                            "result",
                            "hit",
                            "reason",
                            reason)
                    .increment();
            return Mono.just(cached.policy());
        }

        if (cached != null && cached.expiresAtMs() <= now) {
            cachedPolicies.remove(tenantId, cached);
        }

        meterRegistry.counter(
                        "orchestrator.controlplane.policy.fallback.total",
                        "result",
                        "miss",
                        "reason",
                        reason)
                .increment();
        return Mono.error(cause);
    }

    private boolean isFailOpenEnabled(TenantPolicy policy) {
        return properties.isFallbackFailOpen() || policy.controlPlaneFallbackFailOpen();
    }

    private void cachePolicy(String tenantId, TenantPolicy policy) {
        long ttlMs = resolveCacheTtlMs(policy);
        cachedPolicies.put(tenantId, new CachedPolicy(policy, nowMs() + ttlMs));
    }

    private long resolveCacheTtlMs(TenantPolicy policy) {
        if (policy.controlPlaneFallbackCacheTtlMs() > 0) {
            return policy.controlPlaneFallbackCacheTtlMs();
        }
        return Math.max(1L, properties.getPolicyCacheTtl().toMillis());
    }

    private long nowMs() {
        return clock.millis();
    }

    private record CachedPolicy(TenantPolicy policy, long expiresAtMs) {
    }
}
