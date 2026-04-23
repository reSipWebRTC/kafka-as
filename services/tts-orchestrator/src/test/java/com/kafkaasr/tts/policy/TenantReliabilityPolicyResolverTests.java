package com.kafkaasr.tts.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kafkaasr.tts.events.TtsKafkaProperties;
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

class TenantReliabilityPolicyResolverTests {

    @Test
    void resolvesTenantPolicyFromControlPlane() {
        QueueExchangeFunction exchangeFunction = new QueueExchangeFunction();
        exchangeFunction.enqueue(successResponse(4, 350L, ".tenant-a.dlq", true, 45000L));

        TenantReliabilityPolicyResolver resolver = new TenantReliabilityPolicyResolver(
                WebClient.builder().exchangeFunction(exchangeFunction).build(),
                controlPlaneProperties(),
                kafkaDefaults(),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        TenantReliabilityPolicy policy = resolver.resolve("tenant-a");

        assertEquals(4, policy.retryMaxAttempts());
        assertEquals(350L, policy.retryBackoffMs());
        assertEquals(".tenant-a.dlq", policy.dlqTopicSuffix());
    }

    @Test
    void fallsBackToKafkaDefaultsWhenControlPlaneUnavailable() {
        QueueExchangeFunction exchangeFunction = new QueueExchangeFunction();
        exchangeFunction.enqueue(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());

        TenantReliabilityPolicyResolver resolver = new TenantReliabilityPolicyResolver(
                WebClient.builder().exchangeFunction(exchangeFunction).build(),
                controlPlaneProperties(),
                kafkaDefaults(),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        TenantReliabilityPolicy policy = resolver.resolve("tenant-a");

        assertEquals(3, policy.retryMaxAttempts());
        assertEquals(200L, policy.retryBackoffMs());
        assertEquals(".dlq", policy.dlqTopicSuffix());
    }

    @Test
    void usesCachedPolicyWhenFailOpenEnabledAndControlPlaneFails() {
        QueueExchangeFunction exchangeFunction = new QueueExchangeFunction();
        exchangeFunction.enqueue(successResponse(5, 500L, ".tenant-a.dlq", true, 60000L));
        exchangeFunction.enqueue(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());

        TenantReliabilityPolicyResolver resolver = new TenantReliabilityPolicyResolver(
                WebClient.builder().exchangeFunction(exchangeFunction).build(),
                controlPlaneProperties(),
                kafkaDefaults(),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        TenantReliabilityPolicy first = resolver.resolve("tenant-a");
        TenantReliabilityPolicy second = resolver.resolve("tenant-a");

        assertEquals(5, first.retryMaxAttempts());
        assertEquals(5, second.retryMaxAttempts());
        assertEquals(".tenant-a.dlq", second.dlqTopicSuffix());
        assertEquals(1, exchangeFunction.calls());
    }

    @Test
    void invalidateTenantClearsCachedPolicyAndForcesRefresh() {
        QueueExchangeFunction exchangeFunction = new QueueExchangeFunction();
        exchangeFunction.enqueue(successResponse(4, 350L, ".tenant-a.dlq", true, 60000L));
        exchangeFunction.enqueue(successResponse(6, 650L, ".tenant-a.v2.dlq", true, 60000L));

        TenantReliabilityPolicyResolver resolver = new TenantReliabilityPolicyResolver(
                WebClient.builder().exchangeFunction(exchangeFunction).build(),
                controlPlaneProperties(),
                kafkaDefaults(),
                Clock.fixed(Instant.parse("2026-04-22T00:00:00Z"), ZoneOffset.UTC));

        TenantReliabilityPolicy first = resolver.resolve("tenant-a");
        resolver.invalidateTenant("tenant-a");
        TenantReliabilityPolicy second = resolver.resolve("tenant-a");

        assertEquals(4, first.retryMaxAttempts());
        assertEquals(6, second.retryMaxAttempts());
        assertEquals(".tenant-a.v2.dlq", second.dlqTopicSuffix());
        assertEquals(2, exchangeFunction.calls());
    }

    private static TtsControlPlaneProperties controlPlaneProperties() {
        TtsControlPlaneProperties properties = new TtsControlPlaneProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://control-plane");
        properties.setRequestTimeout(Duration.ofSeconds(1));
        properties.setPolicyCacheTtl(Duration.ofSeconds(30));
        properties.setFallbackFailOpen(true);
        return properties;
    }

    private static TtsKafkaProperties kafkaDefaults() {
        TtsKafkaProperties properties = new TtsKafkaProperties();
        properties.setRetryMaxAttempts(3);
        properties.setRetryBackoffMs(200L);
        properties.setDlqTopicSuffix(".dlq");
        return properties;
    }

    private static ClientResponse successResponse(
            int retryMaxAttempts,
            long retryBackoffMs,
            String dlqTopicSuffix,
            boolean failOpen,
            long cacheTtlMs) {
        String body = """
                {
                  "tenantId": "tenant-a",
                  "controlPlaneFallbackFailOpen": %s,
                  "controlPlaneFallbackCacheTtlMs": %d,
                  "retryMaxAttempts": %d,
                  "retryBackoffMs": %d,
                  "dlqTopicSuffix": "%s"
                }
                """.formatted(failOpen, cacheTtlMs, retryMaxAttempts, retryBackoffMs, dlqTopicSuffix);
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
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
