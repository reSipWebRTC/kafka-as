package com.kafkaasr.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class ExternalIamFailureDrillTests {

    @Test
    void reportsExternalUnavailableWhenJwksEndpointConnectionRefused() {
        int closedPort = reserveClosedPort();
        var signingKey = MockJwksTestSupport.generateRsaKey("mock-kid-refused");
        String token = MockJwksTestSupport.issueToken(
                signingKey,
                "https://mock-issuer",
                "control-plane",
                "scp",
                "control.policy.read",
                "tenant_ids",
                MockJwksTestSupport.tenants("tenant-a"),
                Duration.ofMinutes(5));

        ControlPlaneAuthProperties properties = configuredProperties(
                ControlPlaneAuthProperties.Mode.EXTERNAL_IAM,
                "http://127.0.0.1:" + closedPort + "/jwks",
                200,
                200);
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(properties, AuthMetricsRecorder.noop());

        AuthDecision decision = backend.authorize(new AuthRequest("Bearer " + token, HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.UNAUTHORIZED, decision.outcome());
        assertEquals("EXTERNAL_UNAVAILABLE", decision.reason());
    }

    @Test
    void reportsExternalUnavailableWhenJwksReadTimeout() {
        var signingKey = MockJwksTestSupport.generateRsaKey("mock-kid-timeout");
        try (var jwksServer = MockJwksTestSupport.startJwksServer(signingKey, Duration.ofMillis(500))) {
            String token = MockJwksTestSupport.issueToken(
                    signingKey,
                    "https://mock-issuer",
                    "control-plane",
                    "scp",
                    "control.policy.read",
                    "tenant_ids",
                    MockJwksTestSupport.tenants("tenant-a"),
                    Duration.ofMinutes(5));

            ControlPlaneAuthProperties properties = configuredProperties(
                    ControlPlaneAuthProperties.Mode.EXTERNAL_IAM,
                    jwksServer.jwksUri(),
                    100,
                    100);
            ExternalIamAuthBackend backend = new ExternalIamAuthBackend(properties, AuthMetricsRecorder.noop());

            AuthDecision decision = backend.authorize(new AuthRequest("Bearer " + token, HttpMethod.GET, "tenant-a"));

            assertEquals(AuthOutcome.UNAUTHORIZED, decision.outcome());
            assertEquals("EXTERNAL_UNAVAILABLE", decision.reason());
        }
    }

    @Test
    void hybridFallsBackToStaticWhenJwksUnavailableAndEmitsMetric() {
        int closedPort = reserveClosedPort();
        var signingKey = MockJwksTestSupport.generateRsaKey("mock-kid-fallback");
        String token = MockJwksTestSupport.issueToken(
                signingKey,
                "https://mock-issuer",
                "control-plane",
                "scp",
                "control.policy.read",
                "tenant_ids",
                MockJwksTestSupport.tenants("tenant-a"),
                Duration.ofMinutes(5));

        ControlPlaneAuthProperties properties = configuredProperties(
                ControlPlaneAuthProperties.Mode.HYBRID,
                "http://127.0.0.1:" + closedPort + "/jwks",
                200,
                200);
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
                        .tag("reason", "external_unavailable")
                        .counter()
                        .count());
    }

    private static ControlPlaneAuthProperties configuredProperties(
            ControlPlaneAuthProperties.Mode mode,
            String jwksUri,
            int connectTimeoutMs,
            int readTimeoutMs) {
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
        external.setConnectTimeoutMs(connectTimeoutMs);
        external.setReadTimeoutMs(readTimeoutMs);
        properties.setExternal(external);
        return properties;
    }

    private static int reserveClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reserve local port", exception);
        }
    }
}
