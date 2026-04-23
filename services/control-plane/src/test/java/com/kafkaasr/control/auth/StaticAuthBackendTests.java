package com.kafkaasr.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class StaticAuthBackendTests {

    @Test
    void allowsWhenAuthDisabled() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(false);

        StaticAuthBackend backend = new StaticAuthBackend(properties);
        AuthDecision decision = backend.authorize(new AuthRequest(null, HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
    }

    @Test
    void returnsUnauthorizedWhenHeaderMissing() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        properties.setTokens(List.of("legacy-token"));

        StaticAuthBackend backend = new StaticAuthBackend(properties);
        AuthDecision decision = backend.authorize(new AuthRequest(null, HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.UNAUTHORIZED, decision.outcome());
        assertEquals("MISSING_OR_INVALID_TOKEN", decision.reason());
    }

    @Test
    void allowsLegacyTokenAsFullAccess() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        properties.setTokens(List.of("legacy-token"));

        StaticAuthBackend backend = new StaticAuthBackend(properties);
        AuthDecision decision = backend.authorize(new AuthRequest("Bearer legacy-token", HttpMethod.PUT, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
    }

    @Test
    void allowsScopedCredentialWhenTenantAndMethodMatch() {
        ControlPlaneAuthProperties.Credential credential = new ControlPlaneAuthProperties.Credential();
        credential.setToken("reader-tenant-a");
        credential.setRead(true);
        credential.setWrite(false);
        credential.setTenantPatterns(List.of("tenant-a"));

        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        properties.setCredentials(List.of(credential));

        StaticAuthBackend backend = new StaticAuthBackend(properties);
        AuthDecision decision = backend.authorize(new AuthRequest("Bearer reader-tenant-a", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
    }

    @Test
    void returnsForbiddenWhenTenantScopeDenied() {
        ControlPlaneAuthProperties.Credential credential = new ControlPlaneAuthProperties.Credential();
        credential.setToken("reader-tenant-a");
        credential.setRead(true);
        credential.setWrite(true);
        credential.setTenantPatterns(List.of("tenant-a"));

        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        properties.setCredentials(List.of(credential));

        StaticAuthBackend backend = new StaticAuthBackend(properties);
        AuthDecision decision = backend.authorize(new AuthRequest("Bearer reader-tenant-a", HttpMethod.GET, "tenant-b"));

        assertEquals(AuthOutcome.FORBIDDEN, decision.outcome());
        assertEquals("TENANT_SCOPE_DENIED", decision.reason());
    }

    @Test
    void returnsForbiddenWhenOperationDenied() {
        ControlPlaneAuthProperties.Credential credential = new ControlPlaneAuthProperties.Credential();
        credential.setToken("reader-tenant-a");
        credential.setRead(true);
        credential.setWrite(false);
        credential.setTenantPatterns(List.of("tenant-a"));

        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);
        properties.setCredentials(List.of(credential));

        StaticAuthBackend backend = new StaticAuthBackend(properties);
        AuthDecision decision = backend.authorize(new AuthRequest("Bearer reader-tenant-a", HttpMethod.PUT, "tenant-a"));

        assertEquals(AuthOutcome.FORBIDDEN, decision.outcome());
        assertEquals("OPERATION_DENIED", decision.reason());
    }

    @Test
    void returnsUnauthorizedWhenTokenUnknown() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setEnabled(true);

        StaticAuthBackend backend = new StaticAuthBackend(properties);
        AuthDecision decision = backend.authorize(new AuthRequest("Bearer unknown", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.UNAUTHORIZED, decision.outcome());
        assertEquals("TOKEN_NOT_FOUND", decision.reason());
    }
}
