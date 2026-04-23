package com.kafkaasr.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class AuthMetricsInstrumentationTests {

    @Test
    void staticBackendRecordsUnauthorizedReasonCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AuthMetricsRecorder metricsRecorder = new AuthMetricsRecorder(meterRegistry);

        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        properties.setMode(ControlPlaneAuthProperties.Mode.STATIC);
        StaticAuthBackend backend = new StaticAuthBackend(properties, metricsRecorder);

        backend.authorize(new AuthRequest(null, HttpMethod.GET, "tenant-a"));

        assertEquals(
                1.0,
                meterRegistry.get("controlplane.auth.decision.total")
                        .tag("backend", "static")
                        .tag("mode", "static")
                        .tag("outcome", "unauthorized")
                        .tag("reason", "missing_or_invalid_token")
                        .counter()
                        .count());
    }

    @Test
    void externalBackendRecordsForbiddenReasonCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AuthMetricsRecorder metricsRecorder = new AuthMetricsRecorder(meterRegistry);

        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        properties.setMode(ControlPlaneAuthProperties.Mode.EXTERNAL_IAM);
        ControlPlaneAuthProperties.External external = new ControlPlaneAuthProperties.External();
        external.setPermissionClaim("scp");
        external.setTenantClaim("tenant_ids");
        external.setReadPermission("control.policy.read");
        external.setWritePermission("control.policy.write");
        properties.setExternal(external);

        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> Map.of(
                        "scp", "control.policy.read",
                        "tenant_ids", List.of("tenant-a")),
                metricsRecorder);

        backend.authorize(new AuthRequest("Bearer external-jwt", HttpMethod.PUT, "tenant-a"));

        assertEquals(
                1.0,
                meterRegistry.get("controlplane.auth.decision.total")
                        .tag("backend", "external-iam")
                        .tag("mode", "external_iam")
                        .tag("outcome", "forbidden")
                        .tag("reason", "operation_denied")
                        .counter()
                        .count());
    }

    @Test
    void modeSwitchRecordsHybridFallbackCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AuthMetricsRecorder metricsRecorder = new AuthMetricsRecorder(meterRegistry);

        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        properties.setMode(ControlPlaneAuthProperties.Mode.HYBRID);
        properties.setTokens(List.of("legacy-static-token"));

        StaticAuthBackend staticBackend = new StaticAuthBackend(properties, metricsRecorder);
        ExternalIamAuthBackend externalBackend = new ExternalIamAuthBackend(
                properties,
                token -> {
                    throw new ExternalIamTokenDecoderException(
                            ExternalIamTokenDecoderException.Category.UNAVAILABLE,
                            "down",
                            null);
                },
                metricsRecorder);
        ModeSwitchAuthBackend modeSwitch = new ModeSwitchAuthBackend(
                staticBackend,
                externalBackend,
                properties,
                metricsRecorder);

        AuthDecision decision = modeSwitch.authorize(new AuthRequest("Bearer legacy-static-token", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
        assertEquals(
                1.0,
                meterRegistry.get("controlplane.auth.hybrid.fallback.total")
                        .tag("reason", "external_unavailable")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                meterRegistry.get("controlplane.auth.decision.total")
                        .tag("backend", "mode-switch")
                        .tag("mode", "hybrid")
                        .tag("outcome", "allow")
                        .tag("reason", "allow")
                        .counter()
                        .count());
    }
}
