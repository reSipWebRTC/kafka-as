package com.kafkaasr.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;

class ExternalIamClaimMatrixTests {

    @ParameterizedTest(name = "{0}")
    @MethodSource("defaultClaimMatrix")
    void validatesDefaultClaimMappingMatrix(
            String scenario,
            HttpMethod method,
            Map<String, Object> claims,
            AuthOutcome expectedOutcome,
            String expectedReason) {
        ControlPlaneAuthProperties properties = configuredProperties("scp", "tenant_ids");
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(properties, token -> claims);

        AuthDecision decision = backend.authorize(new AuthRequest("Bearer matrix-token", method, "tenant-a"));

        assertEquals(expectedOutcome, decision.outcome(), scenario);
        assertEquals(expectedReason, decision.reason(), scenario);
    }

    @Test
    void supportsCustomClaimNamesForPermissionAndTenant() {
        ControlPlaneAuthProperties properties = configuredProperties("permissions", "tenants");
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> Map.of(
                        "permissions", List.of("control.policy.read", "control.policy.write"),
                        "tenants", List.of("tenant-a")));

        AuthDecision decision = backend.authorize(new AuthRequest("Bearer matrix-token", HttpMethod.PUT, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
        assertEquals("ALLOW", decision.reason());
    }

    @Test
    void returnsUnauthorizedForMissingAuthorizationHeader() {
        ControlPlaneAuthProperties properties = configuredProperties("scp", "tenant_ids");
        ExternalIamAuthBackend backend = new ExternalIamAuthBackend(
                properties,
                token -> {
                    throw new IllegalStateException("decoder must not be called");
                });

        AuthDecision decision = backend.authorize(new AuthRequest(null, HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.UNAUTHORIZED, decision.outcome());
        assertEquals("MISSING_OR_INVALID_TOKEN", decision.reason());
    }

    private static Stream<Arguments> defaultClaimMatrix() {
        return Stream.of(
                Arguments.of(
                        "read allow with list claims",
                        HttpMethod.GET,
                        Map.of(
                                "scp", List.of("control.policy.read"),
                                "tenant_ids", List.of("tenant-a")),
                        AuthOutcome.ALLOW,
                        "ALLOW"),
                Arguments.of(
                        "write allow with combined permissions",
                        HttpMethod.PUT,
                        Map.of(
                                "scp", "control.policy.read control.policy.write",
                                "tenant_ids", List.of("tenant-a")),
                        AuthOutcome.ALLOW,
                        "ALLOW"),
                Arguments.of(
                        "write denied when write permission missing",
                        HttpMethod.PUT,
                        Map.of(
                                "scp", "control.policy.read",
                                "tenant_ids", List.of("tenant-a")),
                        AuthOutcome.FORBIDDEN,
                        "OPERATION_DENIED"),
                Arguments.of(
                        "tenant denied when scope mismatched",
                        HttpMethod.GET,
                        Map.of(
                                "scp", "control.policy.read control.policy.write",
                                "tenant_ids", List.of("tenant-b")),
                        AuthOutcome.FORBIDDEN,
                        "TENANT_SCOPE_DENIED"),
                Arguments.of(
                        "allow wildcard tenant pattern",
                        HttpMethod.GET,
                        Map.of(
                                "scp", "control.policy.read",
                                "tenant_ids", List.of("tenant-*")),
                        AuthOutcome.ALLOW,
                        "ALLOW"),
                Arguments.of(
                        "permission denied when permission claim empty",
                        HttpMethod.GET,
                        Map.of(
                                "scp", "",
                                "tenant_ids", List.of("tenant-a")),
                        AuthOutcome.FORBIDDEN,
                        "OPERATION_DENIED"),
                Arguments.of(
                        "tenant denied when tenant claim missing",
                        HttpMethod.GET,
                        Map.of("scp", "control.policy.read"),
                        AuthOutcome.FORBIDDEN,
                        "TENANT_SCOPE_DENIED"));
    }

    private static ControlPlaneAuthProperties configuredProperties(String permissionClaim, String tenantClaim) {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        ControlPlaneAuthProperties.External external = new ControlPlaneAuthProperties.External();
        external.setPermissionClaim(permissionClaim);
        external.setTenantClaim(tenantClaim);
        external.setReadPermission("control.policy.read");
        external.setWritePermission("control.policy.write");
        properties.setExternal(external);
        return properties;
    }
}
