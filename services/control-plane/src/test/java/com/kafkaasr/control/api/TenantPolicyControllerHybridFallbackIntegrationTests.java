package com.kafkaasr.control.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.kafkaasr.control.auth.AuthMetricsRecorder;
import com.kafkaasr.control.auth.ControlPlaneAuthProperties;
import com.kafkaasr.control.auth.ControlPlaneAuthWebFilter;
import com.kafkaasr.control.auth.ExternalIamAuthBackend;
import com.kafkaasr.control.auth.ModeSwitchAuthBackend;
import com.kafkaasr.control.auth.StaticAuthBackend;
import com.kafkaasr.control.service.TenantPolicyService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(
        controllers = TenantPolicyController.class,
        properties = {
                "control.auth.enabled=true",
                "control.auth.mode=hybrid",
                "control.auth.tokens=legacy-static-token",
                "control.auth.external.issuer=https://mock-iam.local",
                "control.auth.external.audience=kafka-asr-control-plane",
                "control.auth.external.jwks-uri=http://127.0.0.1:1/.well-known/jwks.json",
                "control.auth.external.permission-claim=scp",
                "control.auth.external.tenant-claim=tenant_ids",
                "control.auth.external.read-permission=control.policy.read",
                "control.auth.external.write-permission=control.policy.write"
        })
@Import({
        ControlPlaneExceptionHandler.class,
        ControlPlaneAuthWebFilter.class,
        StaticAuthBackend.class,
        ExternalIamAuthBackend.class,
        ModeSwitchAuthBackend.class,
        AuthMetricsRecorder.class,
        TenantPolicyControllerHybridFallbackIntegrationTests.TestMetricsConfig.class
})
@EnableConfigurationProperties(ControlPlaneAuthProperties.class)
class TenantPolicyControllerHybridFallbackIntegrationTests {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private TenantPolicyService tenantPolicyService;

    @Test
    void fallsBackToStaticForReadWhenExternalIamUnavailable() {
        when(tenantPolicyService.getTenantPolicy("tenant-a"))
                .thenReturn(Mono.just(tenantPolicyResponse("tenant-a")));

        webTestClient.get()
                .uri("/api/v1/tenants/tenant-a/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer legacy-static-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-a");
    }

    @Test
    void fallsBackToStaticForWriteWhenExternalIamUnavailable() {
        when(tenantPolicyService.upsertTenantPolicy(org.mockito.ArgumentMatchers.eq("tenant-a"), any()))
                .thenReturn(Mono.just(tenantPolicyResponse("tenant-a")));

        webTestClient.put()
                .uri("/api/v1/tenants/tenant-a/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer legacy-static-token")
                .contentType(APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sourceLang": "zh-CN",
                          "targetLang": "en-US",
                          "asrModel": "funasr-v1",
                          "translationModel": "mt-v1",
                          "ttsVoice": "en-US-neural-a",
                          "maxConcurrentSessions": 200,
                          "rateLimitPerMinute": 2000,
                          "enabled": true
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-a");
    }

    @Test
    void unknownTokenRemainsUnauthorizedAfterFallback() {
        webTestClient.get()
                .uri("/api/v1/tenants/tenant-a/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer unknown-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_INVALID_TOKEN");
    }

    private static TenantPolicyResponse tenantPolicyResponse(String tenantId) {
        return new TenantPolicyResponse(
                tenantId,
                "zh-CN",
                "en-US",
                "funasr-v1",
                "mt-v1",
                "en-US-neural-a",
                200,
                2000,
                true,
                true,
                20,
                true,
                60000L,
                4,
                300L,
                ".tenant.dlq",
                1L,
                1713744000000L,
                true);
    }

    @TestConfiguration
    static class TestMetricsConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
