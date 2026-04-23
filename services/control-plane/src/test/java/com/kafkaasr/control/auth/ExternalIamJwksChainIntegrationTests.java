package com.kafkaasr.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class ExternalIamJwksChainIntegrationTests {

    @Test
    void externalIamModeAllowsValidTokenFromMockJwks() {
        var signingKey = MockJwksTestSupport.generateRsaKey("mock-kid-allow");
        try (var jwksServer = MockJwksTestSupport.startJwksServer(signingKey, Duration.ZERO)) {
            String token = MockJwksTestSupport.issueToken(
                    signingKey,
                    "https://mock-issuer",
                    "control-plane",
                    "scp",
                    "control.policy.read control.policy.write",
                    "tenant_ids",
                    MockJwksTestSupport.tenants("tenant-a"),
                    Duration.ofMinutes(5));

            ControlPlaneAuthProperties properties = configuredProperties(
                    ControlPlaneAuthProperties.Mode.EXTERNAL_IAM,
                    jwksServer.jwksUri());
            AuthMetricsRecorder metricsRecorder = new AuthMetricsRecorder(new SimpleMeterRegistry());
            ExternalIamAuthBackend backend = new ExternalIamAuthBackend(properties, metricsRecorder);

            AuthDecision decision = backend.authorize(new AuthRequest("Bearer " + token, HttpMethod.PUT, "tenant-a"));

            assertEquals(AuthOutcome.ALLOW, decision.outcome());
            assertEquals("ALLOW", decision.reason());
        }
    }

    @Test
    void externalIamModeRejectsAudienceMismatch() {
        var signingKey = MockJwksTestSupport.generateRsaKey("mock-kid-aud");
        try (var jwksServer = MockJwksTestSupport.startJwksServer(signingKey, Duration.ZERO)) {
            String token = MockJwksTestSupport.issueToken(
                    signingKey,
                    "https://mock-issuer",
                    "wrong-audience",
                    "scp",
                    "control.policy.read control.policy.write",
                    "tenant_ids",
                    MockJwksTestSupport.tenants("tenant-a"),
                    Duration.ofMinutes(5));

            ControlPlaneAuthProperties properties = configuredProperties(
                    ControlPlaneAuthProperties.Mode.EXTERNAL_IAM,
                    jwksServer.jwksUri());
            AuthMetricsRecorder metricsRecorder = new AuthMetricsRecorder(new SimpleMeterRegistry());
            ExternalIamAuthBackend backend = new ExternalIamAuthBackend(properties, metricsRecorder);

            AuthDecision decision = backend.authorize(new AuthRequest("Bearer " + token, HttpMethod.GET, "tenant-a"));

            assertEquals(AuthOutcome.UNAUTHORIZED, decision.outcome());
            assertEquals("EXTERNAL_TOKEN_INVALID", decision.reason());
        }
    }

    @Test
    void hybridModeFallsBackToStaticWhenExternalTokenInvalid() {
        var signingKey = MockJwksTestSupport.generateRsaKey("mock-kid-hybrid");
        try (var jwksServer = MockJwksTestSupport.startJwksServer(signingKey, Duration.ZERO)) {
            String token = MockJwksTestSupport.issueToken(
                    signingKey,
                    "https://other-issuer",
                    "control-plane",
                    "scp",
                    "control.policy.read control.policy.write",
                    "tenant_ids",
                    MockJwksTestSupport.tenants("tenant-a"),
                    Duration.ofMinutes(5));

            ControlPlaneAuthProperties properties = configuredProperties(
                    ControlPlaneAuthProperties.Mode.HYBRID,
                    jwksServer.jwksUri());
            properties.setTokens(List.of(token));

            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            AuthMetricsRecorder metricsRecorder = new AuthMetricsRecorder(meterRegistry);
            StaticAuthBackend staticBackend = new StaticAuthBackend(properties, metricsRecorder);
            ExternalIamAuthBackend externalBackend = new ExternalIamAuthBackend(properties, metricsRecorder);
            ModeSwitchAuthBackend backend = new ModeSwitchAuthBackend(
                    staticBackend,
                    externalBackend,
                    properties,
                    metricsRecorder);

            AuthDecision decision = backend.authorize(new AuthRequest("Bearer " + token, HttpMethod.GET, "tenant-a"));

            assertEquals(AuthOutcome.ALLOW, decision.outcome());
            assertEquals("ALLOW", decision.reason());
            assertEquals(
                    1.0,
                    meterRegistry.get("controlplane.auth.hybrid.fallback.total")
                            .tag("reason", "external_token_invalid")
                            .counter()
                            .count());
        }
    }

    private static ControlPlaneAuthProperties configuredProperties(
            ControlPlaneAuthProperties.Mode mode,
            String jwksUri) {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        properties.setMode(mode);

        ControlPlaneAuthProperties.External external = new ControlPlaneAuthProperties.External();
        external.setIssuer("https://mock-issuer");
        external.setAudience("control-plane");
        external.setJwksUri(jwksUri);
        external.setPermissionClaim("scp");
        external.setTenantClaim("tenant_ids");
        external.setReadPermission("control.policy.read");
        external.setWritePermission("control.policy.write");
        external.setConnectTimeoutMs(500);
        external.setReadTimeoutMs(500);
        properties.setExternal(external);
        return properties;
    }
}
