package com.kafkaasr.orchestrator.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ControlPlaneTenantPolicyClientTests {

    @Test
    void returnsPolicyOnSuccessfulFetch() {
        QueueExchangeFunction exchangeFunction = new QueueExchangeFunction();
        exchangeFunction.enqueue(successPolicyResponse(true, 25, true, 45000L));

        ControlPlaneTenantPolicyClient client = new ControlPlaneTenantPolicyClient(
                WebClient.builder().exchangeFunction(exchangeFunction).build(),
                properties(false, 3),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());

        StepVerifier.create(client.getTenantPolicy("tenant-a"))
                .assertNext(policy -> {
                    assertEquals("tenant-a", policy.tenantId());
                    assertTrue(policy.grayEnabled());
                    assertEquals(25, policy.grayTrafficPercent());
                    assertTrue(policy.controlPlaneFallbackFailOpen());
                    assertEquals(3, policy.retryMaxAttempts());
                    assertEquals(200L, policy.retryBackoffMs());
                    assertEquals(".dlq", policy.dlqTopicSuffix());
                })
                .verifyComplete();
    }

    @Test
    void fallsBackToCachedPolicyWhenControlPlaneUnavailableAndFailOpenEnabled() {
        QueueExchangeFunction exchangeFunction = new QueueExchangeFunction();
        exchangeFunction.enqueue(successPolicyResponse(false, 0, true, 60000L));
        exchangeFunction.enqueue(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());

        ControlPlaneTenantPolicyClient client = new ControlPlaneTenantPolicyClient(
                WebClient.builder().exchangeFunction(exchangeFunction).build(),
                properties(false, 3),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());

        StepVerifier.create(client.getTenantPolicy("tenant-a"))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(client.getTenantPolicy("tenant-a"))
                .assertNext(policy -> {
                    assertEquals("tenant-a", policy.tenantId());
                    assertTrue(policy.controlPlaneFallbackFailOpen());
                })
                .verifyComplete();

        assertEquals(2, exchangeFunction.calls());
    }

    @Test
    void opensCircuitAfterThresholdAndShortCircuitsSubsequentCalls() {
        QueueExchangeFunction exchangeFunction = new QueueExchangeFunction();
        exchangeFunction.enqueue(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());

        ControlPlaneTenantPolicyClient client = new ControlPlaneTenantPolicyClient(
                WebClient.builder().exchangeFunction(exchangeFunction).build(),
                properties(false, 1),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());

        StepVerifier.create(client.getTenantPolicy("tenant-a"))
                .expectErrorSatisfies(error -> {
                    TenantPolicyClientException exception = (TenantPolicyClientException) error;
                    assertEquals(TenantPolicyClientException.Kind.UNAVAILABLE, exception.kind());
                })
                .verify();

        StepVerifier.create(client.getTenantPolicy("tenant-a"))
                .expectErrorSatisfies(error -> {
                    TenantPolicyClientException exception = (TenantPolicyClientException) error;
                    assertEquals(TenantPolicyClientException.Kind.UNAVAILABLE, exception.kind());
                })
                .verify();

        assertEquals(1, exchangeFunction.calls());
    }

    @Test
    void invalidateTenantPolicyClearsCacheAndForcesRefetch() {
        QueueExchangeFunction exchangeFunction = new QueueExchangeFunction();
        exchangeFunction.enqueue(successPolicyResponse(false, 10, true, 60000L));
        exchangeFunction.enqueue(successPolicyResponse(true, 40, true, 60000L));

        ControlPlaneTenantPolicyClient client = new ControlPlaneTenantPolicyClient(
                WebClient.builder().exchangeFunction(exchangeFunction).build(),
                properties(false, 3),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());

        StepVerifier.create(client.getTenantPolicy("tenant-a"))
                .assertNext(policy -> assertEquals(10, policy.grayTrafficPercent()))
                .verifyComplete();

        client.invalidateTenantPolicy("tenant-a");

        StepVerifier.create(client.getTenantPolicy("tenant-a"))
                .assertNext(policy -> assertEquals(40, policy.grayTrafficPercent()))
                .verifyComplete();

        assertEquals(2, exchangeFunction.calls());
    }

    private static ClientResponse successPolicyResponse(
            boolean grayEnabled,
            int grayTrafficPercent,
            boolean fallbackFailOpen,
            long fallbackCacheTtlMs) {
        String body = """
                {
                  "tenantId": "tenant-a",
                  "sourceLang": "zh-CN",
                  "targetLang": "en-US",
                  "asrModel": "asr-v1",
                  "translationModel": "mt-v1",
                  "ttsVoice": "voice-a",
                  "maxConcurrentSessions": 3,
                  "rateLimitPerMinute": 300,
                  "enabled": true,
                  "grayEnabled": %s,
                  "grayTrafficPercent": %d,
                  "controlPlaneFallbackFailOpen": %s,
                  "controlPlaneFallbackCacheTtlMs": %d,
                  "retryMaxAttempts": 3,
                  "retryBackoffMs": 200,
                  "dlqTopicSuffix": ".dlq",
                  "version": 1,
                  "updatedAtMs": 1000,
                  "created": false
                }
                """.formatted(grayEnabled, grayTrafficPercent, fallbackFailOpen, fallbackCacheTtlMs);
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static ControlPlaneClientProperties properties(boolean fallbackFailOpen, int failureThreshold) {
        ControlPlaneClientProperties properties = new ControlPlaneClientProperties();
        properties.setBaseUrl("http://control-plane");
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.setFallbackFailOpen(fallbackFailOpen);
        properties.setPolicyCacheTtl(Duration.ofSeconds(30));
        properties.setCircuitBreakerFailureThreshold(failureThreshold);
        properties.setCircuitBreakerOpenDuration(Duration.ofSeconds(5));
        return properties;
    }

    private static final class QueueExchangeFunction implements ExchangeFunction {

        private final Queue<Mono<ClientResponse>> responses = new ArrayDeque<>();
        private int calls;

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            calls += 1;
            Mono<ClientResponse> response = responses.poll();
            if (response == null) {
                return Mono.error(new IllegalStateException("No stubbed response for " + request.url()));
            }
            return response;
        }

        int calls() {
            return calls;
        }

        void enqueue(ClientResponse response) {
            responses.add(Mono.just(response));
        }
    }
}
