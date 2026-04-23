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
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(
        controllers = TenantPolicyController.class,
        properties = {
                "control.auth.enabled=true",
                "control.auth.mode=external-iam",
                "control.auth.external.issuer=https://mock-iam.local",
                "control.auth.external.audience=kafka-asr-control-plane",
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
        TenantPolicyControllerExternalIamJwksIntegrationTests.TestMetricsConfig.class
})
@EnableConfigurationProperties(ControlPlaneAuthProperties.class)
class TenantPolicyControllerExternalIamJwksIntegrationTests {

    private static final MockJwksServer MOCK_JWKS_SERVER = MockJwksServer.start(
            "https://mock-iam.local",
            "kafka-asr-control-plane");

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private TenantPolicyService tenantPolicyService;

    @DynamicPropertySource
    static void registerExternalJwksUri(DynamicPropertyRegistry registry) {
        registry.add("control.auth.external.jwks-uri", MOCK_JWKS_SERVER::jwksUri);
    }

    @AfterAll
    static void shutdownJwksServer() {
        MOCK_JWKS_SERVER.close();
    }

    @Test
    void readTokenCanGetPolicy() {
        when(tenantPolicyService.getTenantPolicy("tenant-a"))
                .thenReturn(Mono.just(tenantPolicyResponse("tenant-a")));

        String readToken = MOCK_JWKS_SERVER.issueToken(
                List.of("control.policy.read"),
                List.of("tenant-a"));

        webTestClient.get()
                .uri("/api/v1/tenants/tenant-a/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenantId").isEqualTo("tenant-a");
    }

    @Test
    void writeTokenCanPutPolicy() {
        when(tenantPolicyService.upsertTenantPolicy(org.mockito.ArgumentMatchers.eq("tenant-a"), any()))
                .thenReturn(Mono.just(tenantPolicyResponse("tenant-a")));

        String writeToken = MOCK_JWKS_SERVER.issueToken(
                List.of("control.policy.write"),
                List.of("tenant-a"));

        webTestClient.put()
                .uri("/api/v1/tenants/tenant-a/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writeToken)
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
    void readTokenCannotWritePolicy() {
        String readToken = MOCK_JWKS_SERVER.issueToken(
                List.of("control.policy.read"),
                List.of("tenant-a"));

        webTestClient.put()
                .uri("/api/v1/tenants/tenant-a/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readToken)
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
    void scopedTokenCannotAccessOtherTenant() {
        String readToken = MOCK_JWKS_SERVER.issueToken(
                List.of("control.policy.read"),
                List.of("tenant-a"));

        webTestClient.get()
                .uri("/api/v1/tenants/tenant-b/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_FORBIDDEN")
                .jsonPath("$.tenantId").isEqualTo("tenant-b");
    }

    @Test
    void malformedTokenIsRejectedAsUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/tenants/tenant-a/policy")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
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

    private static final class MockJwksServer {

        private final String issuer;
        private final String audience;
        private final RSAKey rsaKey;
        private final JWSSigner signer;
        private final HttpServer server;

        private MockJwksServer(String issuer, String audience, RSAKey rsaKey, HttpServer server) {
            this.issuer = issuer;
            this.audience = audience;
            this.rsaKey = rsaKey;
            this.server = server;
            try {
                this.signer = new RSASSASigner(rsaKey.toPrivateKey());
            } catch (JOSEException exception) {
                throw new IllegalStateException("Failed to create JWT signer", exception);
            }
        }

        static MockJwksServer start(String issuer, String audience) {
            try {
                RSAKey rsaKey = new RSAKeyGenerator(2048)
                        .keyID("control-plane-test-key")
                        .generate();
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                String jwksJson = new JWKSet(rsaKey.toPublicJWK()).toString();
                server.createContext("/.well-known/jwks.json", exchange -> {
                    byte[] payload = jwksJson.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, payload.length);
                    try (OutputStream body = exchange.getResponseBody()) {
                        body.write(payload);
                    }
                });
                server.start();
                return new MockJwksServer(issuer, audience, rsaKey, server);
            } catch (IOException | JOSEException exception) {
                throw new IllegalStateException("Failed to start mock JWKS server", exception);
            }
        }

        String jwksUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
        }

        String issueToken(List<String> permissions, List<String> tenantScopes) {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject("integration-test-user")
                    .issueTime(Date.from(now))
                    .notBeforeTime(Date.from(now.minusSeconds(1)))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("scp", String.join(" ", permissions))
                    .claim("tenant_ids", tenantScopes)
                    .build();

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID(rsaKey.getKeyID())
                            .build(),
                    claims);
            try {
                jwt.sign(signer);
            } catch (JOSEException exception) {
                throw new IllegalStateException("Failed to sign mock JWT", exception);
            }
            return jwt.serialize();
        }

        void close() {
            server.stop(0);
        }
    }
}
