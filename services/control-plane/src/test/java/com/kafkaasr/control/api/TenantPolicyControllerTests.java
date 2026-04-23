package com.kafkaasr.control.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.kafkaasr.control.auth.ControlPlaneAuthProperties;
import com.kafkaasr.control.auth.ControlPlaneAuthWebFilter;
import com.kafkaasr.control.auth.StaticAuthBackend;
import com.kafkaasr.control.service.ControlPlaneException;
import com.kafkaasr.control.service.TenantPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(
        controllers = TenantPolicyController.class,
        properties = {
                "control.auth.enabled=true",
                "control.auth.tokens=test-control-token"
        })
@Import({ControlPlaneExceptionHandler.class, ControlPlaneAuthWebFilter.class, StaticAuthBackend.class})
@EnableConfigurationProperties(ControlPlaneAuthProperties.class)
class TenantPolicyControllerTests {

    private static final String AUTHORIZATION = "Bearer test-control-token";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private TenantPolicyService tenantPolicyService;

    @Test
    void upsertThenGetPolicy() {
        TenantPolicyResponse upsertResponse = new TenantPolicyResponse(
                "tenant-api-a",
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
                ".tenant-api-a.dlq",
                1L,
                1713744000000L,
                true);
        TenantPolicyResponse getResponse = new TenantPolicyResponse(
                "tenant-api-a",
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
                ".tenant-api-a.dlq",
                1L,
                1713744000000L,
                false);

        when(tenantPolicyService.upsertTenantPolicy(eq("tenant-api-a"), any()))
                .thenReturn(Mono.just(upsertResponse));
        when(tenantPolicyService.getTenantPolicy("tenant-api-a"))
                .thenReturn(Mono.just(getResponse));

        webTestClient.put()
                .uri("/api/v1/tenants/tenant-api-a/policy")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
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
                          "enabled": true,
                          "retryMaxAttempts": 4,
                          "retryBackoffMs": 300,
                          "dlqTopicSuffix": ".tenant-api-a.dlq"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-api-a")
                .jsonPath("$.version").isEqualTo(1)
                .jsonPath("$.grayEnabled").isEqualTo(true)
                .jsonPath("$.grayTrafficPercent").isEqualTo(20)
                .jsonPath("$.retryMaxAttempts").isEqualTo(4)
                .jsonPath("$.retryBackoffMs").isEqualTo(300)
                .jsonPath("$.dlqTopicSuffix").isEqualTo(".tenant-api-a.dlq")
                .jsonPath("$.created").isEqualTo(true);

        webTestClient.get()
                .uri("/api/v1/tenants/tenant-api-a/policy")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-api-a")
                .jsonPath("$.created").isEqualTo(false)
                .jsonPath("$.controlPlaneFallbackFailOpen").isEqualTo(true)
                .jsonPath("$.retryMaxAttempts").isEqualTo(4)
                .jsonPath("$.asrModel").isEqualTo("funasr-v1");
    }

    @Test
    void getMissingPolicyReturnsNotFound() {
        when(tenantPolicyService.getTenantPolicy("missing"))
                .thenReturn(Mono.error(ControlPlaneException.tenantPolicyNotFound("missing")));

        webTestClient.get()
                .uri("/api/v1/tenants/missing/policy")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("TENANT_POLICY_NOT_FOUND")
                .jsonPath("$.tenantId").isEqualTo("missing");
    }

    @Test
    void upsertInvalidBodyReturnsBadRequest() {
        webTestClient.put()
                .uri("/api/v1/tenants/tenant-api-b/policy")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(APPLICATION_JSON)
                .bodyValue("""
                        {
                          "sourceLang": "",
                          "targetLang": "en-US",
                          "asrModel": "",
                          "translationModel": "mt-v1",
                          "ttsVoice": "en-US-neural-a",
                          "maxConcurrentSessions": 0,
                          "rateLimitPerMinute": 0,
                          "enabled": true
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_MESSAGE");
    }

    @Test
    void getWithoutAuthHeaderReturnsUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/tenants/tenant-api-a/policy")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_INVALID_TOKEN");
    }
}
