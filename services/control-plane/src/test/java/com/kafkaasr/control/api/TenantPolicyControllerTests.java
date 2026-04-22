package com.kafkaasr.control.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class TenantPolicyControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void upsertThenGetPolicy() {
        webTestClient.put()
                .uri("/api/v1/tenants/tenant-api-a/policy")
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
                .jsonPath("$.tenantId").isEqualTo("tenant-api-a")
                .jsonPath("$.version").isEqualTo(1)
                .jsonPath("$.created").isEqualTo(true);

        webTestClient.get()
                .uri("/api/v1/tenants/tenant-api-a/policy")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-api-a")
                .jsonPath("$.created").isEqualTo(false)
                .jsonPath("$.asrModel").isEqualTo("funasr-v1");
    }

    @Test
    void getMissingPolicyReturnsNotFound() {
        webTestClient.get()
                .uri("/api/v1/tenants/missing/policy")
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
}
