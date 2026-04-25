package com.kafkaasr.command.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.kafkaasr.command.events.CommandKafkaProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class TenantRoutingPolicyResolverTests {

    @Mock
    private ExchangeFunction exchangeFunction;

    private TenantRoutingPolicyResolver resolver;
    private CommandControlPlaneProperties controlPlaneProperties;
    private CommandKafkaProperties kafkaProperties;

    @BeforeEach
    void setUp() {
        controlPlaneProperties = new CommandControlPlaneProperties();
        controlPlaneProperties.setEnabled(true);
        controlPlaneProperties.setBaseUrl("http://control-plane.test");

        kafkaProperties = new CommandKafkaProperties();
        kafkaProperties.setRetryMaxAttempts(3);
        kafkaProperties.setRetryBackoffMs(200L);
        kafkaProperties.setDlqTopicSuffix(".dlq");

        WebClient webClient = WebClient.builder()
                .baseUrl(controlPlaneProperties.getBaseUrl())
                .exchangeFunction(exchangeFunction)
                .build();

        resolver = new TenantRoutingPolicyResolver(
                webClient,
                controlPlaneProperties,
                kafkaProperties,
                Clock.fixed(Instant.parse("2026-04-25T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void resolvesSmartHomePolicyFromControlPlane() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {
                                  "tenantId": "tenant-home",
                                  "controlPlaneFallbackFailOpen": true,
                                  "controlPlaneFallbackCacheTtlMs": 60000,
                                  "retryMaxAttempts": 5,
                                  "retryBackoffMs": 400,
                                  "dlqTopicSuffix": ".tenant-home.dlq",
                                  "sessionMode": "SMART_HOME"
                                }
                                """)
                        .build()));

        TenantRoutingPolicy policy = resolver.resolve("tenant-home");

        assertEquals(5, policy.retryMaxAttempts());
        assertEquals(400L, policy.retryBackoffMs());
        assertEquals(".tenant-home.dlq", policy.dlqTopicSuffix());
        assertTrue(policy.isSmartHomeMode());
    }

    @Test
    void fallsBackToDefaultWhenControlPlaneFails() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.error(new IllegalStateException("downstream unavailable")));

        TenantRoutingPolicy policy = resolver.resolve("tenant-a");

        assertEquals(3, policy.retryMaxAttempts());
        assertEquals(200L, policy.retryBackoffMs());
        assertEquals(".dlq", policy.dlqTopicSuffix());
        assertFalse(policy.isSmartHomeMode());
    }
}
