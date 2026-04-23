package com.kafkaasr.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class ExternalIamAuthBackendTests {

    @Test
    void allowsWhenClaimsContainRequiredPermissionAndTenant() {
        ControlPlaneAuthProperties properties = configuredProperties();
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> Map.of(
                        "scp", "control.policy.read control.policy.write",
                        "tenant_ids", List.of("tenant-a")));

        AuthDecision decision = backend.authorize(new AuthRequest("Bearer external-jwt", HttpMethod.PUT, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
    }

    @Test
    void deniesWhenWritePermissionMissing() {
        ControlPlaneAuthProperties properties = configuredProperties();
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> Map.of(
                        "scp", "control.policy.read",
                        "tenant_ids", List.of("tenant-a")));

        AuthDecision decision = backend.authorize(new AuthRequest("Bearer external-jwt", HttpMethod.PUT, "tenant-a"));

        assertEquals(AuthOutcome.FORBIDDEN, decision.outcome());
        assertEquals("OPERATION_DENIED", decision.reason());
    }

    @Test
    void deniesWhenTenantScopeMissing() {
        ControlPlaneAuthProperties properties = configuredProperties();
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> Map.of(
                        "scp", "control.policy.read control.policy.write",
                        "tenant_ids", List.of("tenant-b")));

        AuthDecision decision = backend.authorize(new AuthRequest("Bearer external-jwt", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.FORBIDDEN, decision.outcome());
        assertEquals("TENANT_SCOPE_DENIED", decision.reason());
    }

    @Test
    void supportsWildcardTenantPatternFromClaim() {
        ControlPlaneAuthProperties properties = configuredProperties();
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> Map.of(
                        "scp", "control.policy.read",
                        "tenant_ids", List.of("tenant-*")));

        AuthDecision decision = backend.authorize(new AuthRequest("Bearer external-jwt", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
    }

    @Test
    void returnsUnauthorizedWhenDecoderReportsInvalidToken() {
        ControlPlaneAuthProperties properties = configuredProperties();
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> {
                    throw new ExternalIamTokenDecoderException(
                            ExternalIamTokenDecoderException.Category.INVALID_TOKEN,
                            "invalid",
                            null);
                });

        AuthDecision decision = backend.authorize(new AuthRequest("Bearer bad", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.UNAUTHORIZED, decision.outcome());
        assertEquals("EXTERNAL_TOKEN_INVALID", decision.reason());
    }

    @Test
    void returnsUnauthorizedWhenDecoderUnavailable() {
        ControlPlaneAuthProperties properties = configuredProperties();
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> {
                    throw new ExternalIamTokenDecoderException(
                            ExternalIamTokenDecoderException.Category.UNAVAILABLE,
                            "down",
                            null);
                });

        AuthDecision decision = backend.authorize(new AuthRequest("Bearer any", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.UNAUTHORIZED, decision.outcome());
        assertEquals("EXTERNAL_UNAVAILABLE", decision.reason());
    }

    @Test
    void unauthorizedWhenAuthorizationHeaderMissing() {
        ControlPlaneAuthProperties properties = configuredProperties();
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> {
                    throw new IllegalStateException("should not be called");
                });

        AuthDecision decision = backend.authorize(new AuthRequest(null, HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.UNAUTHORIZED, decision.outcome());
        assertEquals("MISSING_OR_INVALID_TOKEN", decision.reason());
    }

    @Test
    void allowsWhenAuthDisabled() {
        ControlPlaneAuthProperties properties = configuredProperties();
        properties.setEnabled(false);
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> Map.of());

        AuthDecision decision = backend.authorize(new AuthRequest(null, HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
        assertTrue(decision.reason().equals("ALLOW"));
    }

    private static ControlPlaneAuthProperties configuredProperties() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        ControlPlaneAuthProperties.External external = new ControlPlaneAuthProperties.External();
        external.setPermissionClaim("scp");
        external.setTenantClaim("tenant_ids");
        external.setReadPermission("control.policy.read");
        external.setWritePermission("control.policy.write");
        properties.setExternal(external);
        return properties;
    }
}
