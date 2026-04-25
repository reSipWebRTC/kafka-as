package com.kafkaasr.control.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.kafkaasr.control.auth.ControlPlaneAuthProperties;
import com.kafkaasr.control.auth.ControlPlaneAuthWebFilter;
import com.kafkaasr.control.auth.StaticAuthBackend;
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
                "control.auth.credentials[0].token=viewer-tenant-a",
                "control.auth.credentials[0].read=true",
                "control.auth.credentials[0].write=false",
                "control.auth.credentials[0].tenant-patterns[0]=tenant-a",
                "control.auth.credentials[1].token=writer-tenant-b",
                "control.auth.credentials[1].read=true",
                "control.auth.credentials[1].write=true",
                "control.auth.credentials[1].tenant-patterns[0]=tenant-b*"
        })
@Import({ControlPlaneExceptionHandler.class, ControlPlaneAuthWebFilter.class, StaticAuthBackend.class})
@EnableConfigurationProperties(ControlPlaneAuthProperties.class)
class TenantPolicyControllerAuthorizationTests {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private TenantPolicyService tenantPolicyService;

    @Test
    void scopedReaderCanGetAllowedTenant() {
        TenantPolicyResponse response = tenantPolicyResponse("tenant-a");
        org.mockito.Mockito.when(tenantPolicyService.getTenantPolicy("tenant-a"))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/tenants/tenant-a/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer viewer-tenant-a")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-a");
    }

    @Test
    void scopedReaderCannotWrite() {
        webTestClient.put()
                .uri("/api/v1/tenants/tenant-a/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer viewer-tenant-a")
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
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_FORBIDDEN")
                .jsonPath("$.tenantId").isEqualTo("tenant-a");
    }

    @Test
    void scopedReaderCannotRollback() {
        webTestClient.post()
                .uri("/api/v1/tenants/tenant-a/policy:rollback")
                .header(HttpHeaders.AUTHORIZATION, "Bearer viewer-tenant-a")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_FORBIDDEN")
                .jsonPath("$.tenantId").isEqualTo("tenant-a");
    }

    @Test
    void scopedReaderCannotAccessOtherTenant() {
        webTestClient.get()
                .uri("/api/v1/tenants/tenant-b/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer viewer-tenant-a")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_FORBIDDEN")
                .jsonPath("$.tenantId").isEqualTo("tenant-b");
    }

    @Test
    void unknownTokenReturnsUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/tenants/tenant-a/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer unknown-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_INVALID_TOKEN");
    }

    @Test
    void scopedWriterCanRollbackInTenantScope() {
        TenantPolicyResponse response = tenantPolicyResponse("tenant-b-main");
        org.mockito.Mockito.when(tenantPolicyService.rollbackTenantPolicy(
                        org.mockito.ArgumentMatchers.eq("tenant-b-main"),
                        org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/tenants/tenant-b-main/policy:rollback")
                .header(HttpHeaders.AUTHORIZATION, "Bearer writer-tenant-b")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-b-main");
    }

    @Test
    void scopedWriterCanRollbackWithTargetVersionInTenantScope() {
        TenantPolicyResponse response = tenantPolicyResponse("tenant-b-main");
        org.mockito.Mockito.when(tenantPolicyService.rollbackTenantPolicy(
                        org.mockito.ArgumentMatchers.eq("tenant-b-main"),
                        org.mockito.ArgumentMatchers.any(TenantPolicyRollbackRequest.class)))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/tenants/tenant-b-main/policy:rollback")
                .header(HttpHeaders.AUTHORIZATION, "Bearer writer-tenant-b")
                .contentType(APPLICATION_JSON)
                .bodyValue("""
                        {
                          "targetVersion": 2,
                          "distributionRegions": ["cn-east-1"]
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-b-main");
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
                "TRANSLATION",
                true,
                20,
                true,
                60000L,
                4,
                300L,
                ".tenant.dlq",
                1L,
                1713744000000L,
                false);
    }
}
