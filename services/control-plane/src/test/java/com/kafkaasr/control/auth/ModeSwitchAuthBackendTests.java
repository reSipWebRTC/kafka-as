package com.kafkaasr.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class ModeSwitchAuthBackendTests {

    @Test
    void staticModeUsesStaticBackend() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setMode(ControlPlaneAuthProperties.Mode.STATIC);

        StaticAuthBackend staticBackend = staticBackendReturning(AuthDecision.allow("tenant-a"));
        ExternalIamAuthBackend externalBackend =
                externalBackendReturning(AuthDecision.unauthorized("tenant-a", "EXTERNAL_TOKEN_INVALID"));

        ModeSwitchAuthBackend backend = new ModeSwitchAuthBackend(staticBackend, externalBackend, properties);
        AuthDecision decision = backend.authorize(new AuthRequest("Bearer token", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
    }

    @Test
    void externalModeUsesExternalBackend() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setMode(ControlPlaneAuthProperties.Mode.EXTERNAL_IAM);

        StaticAuthBackend staticBackend = staticBackendReturning(AuthDecision.allow("tenant-a"));
        ExternalIamAuthBackend externalBackend =
                externalBackendReturning(AuthDecision.forbidden("tenant-a", "OPERATION_DENIED"));

        ModeSwitchAuthBackend backend = new ModeSwitchAuthBackend(staticBackend, externalBackend, properties);
        AuthDecision decision = backend.authorize(new AuthRequest("Bearer token", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.FORBIDDEN, decision.outcome());
    }

    @Test
    void hybridFallsBackToStaticWhenExternalUnavailable() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setMode(ControlPlaneAuthProperties.Mode.HYBRID);

        StaticAuthBackend staticBackend = staticBackendReturning(AuthDecision.allow("tenant-a"));
        ExternalIamAuthBackend externalBackend =
                externalBackendReturning(AuthDecision.unauthorized("tenant-a", "EXTERNAL_UNAVAILABLE"));

        ModeSwitchAuthBackend backend = new ModeSwitchAuthBackend(staticBackend, externalBackend, properties);
        AuthDecision decision = backend.authorize(new AuthRequest("Bearer legacy-static-token", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
    }

    @Test
    void hybridFallsBackToStaticWhenExternalTokenInvalid() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setMode(ControlPlaneAuthProperties.Mode.HYBRID);

        StaticAuthBackend staticBackend = staticBackendReturning(AuthDecision.allow("tenant-a"));
        ExternalIamAuthBackend externalBackend =
                externalBackendReturning(AuthDecision.unauthorized("tenant-a", "EXTERNAL_TOKEN_INVALID"));

        ModeSwitchAuthBackend backend = new ModeSwitchAuthBackend(staticBackend, externalBackend, properties);
        AuthDecision decision = backend.authorize(new AuthRequest("Bearer legacy-static-token", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.ALLOW, decision.outcome());
    }

    @Test
    void hybridDoesNotFallbackWhenExternalForbids() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setMode(ControlPlaneAuthProperties.Mode.HYBRID);

        StaticAuthBackend staticBackend = staticBackendReturning(AuthDecision.allow("tenant-a"));
        ExternalIamAuthBackend externalBackend =
                externalBackendReturning(AuthDecision.forbidden("tenant-a", "TENANT_SCOPE_DENIED"));

        ModeSwitchAuthBackend backend = new ModeSwitchAuthBackend(staticBackend, externalBackend, properties);
        AuthDecision decision = backend.authorize(new AuthRequest("Bearer external-jwt", HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.FORBIDDEN, decision.outcome());
    }

    @Test
    void hybridDoesNotFallbackForMissingTokenReason() {
        ControlPlaneAuthProperties properties = new ControlPlaneAuthProperties();
        properties.setMode(ControlPlaneAuthProperties.Mode.HYBRID);

        StaticAuthBackend staticBackend = staticBackendReturning(AuthDecision.allow("tenant-a"));
        ExternalIamAuthBackend externalBackend =
                externalBackendReturning(AuthDecision.unauthorized("tenant-a", "MISSING_OR_INVALID_TOKEN"));

        ModeSwitchAuthBackend backend = new ModeSwitchAuthBackend(staticBackend, externalBackend, properties);
        AuthDecision decision = backend.authorize(new AuthRequest(null, HttpMethod.GET, "tenant-a"));

        assertEquals(AuthOutcome.UNAUTHORIZED, decision.outcome());
    }

    private static StaticAuthBackend staticBackendReturning(AuthDecision decision) {
        return new StubStaticAuthBackend(decision);
    }

    private static ExternalIamAuthBackend externalBackendReturning(AuthDecision decision) {
        return new StubExternalIamAuthBackend(decision);
    }

    private static final class StubStaticAuthBackend extends StaticAuthBackend {
        private final AuthDecision decision;

        private StubStaticAuthBackend(AuthDecision decision) {
            super(new ControlPlaneAuthProperties());
            this.decision = decision;
        }

        @Override
        public AuthDecision authorize(AuthRequest request) {
            return decision;
        }
    }

    private static final class StubExternalIamAuthBackend extends ExternalIamAuthBackend {
        private final AuthDecision decision;

        private StubExternalIamAuthBackend(AuthDecision decision) {
            super(new ControlPlaneAuthProperties(), token -> java.util.Map.of());
            this.decision = decision;
        }

        @Override
        public AuthDecision authorize(AuthRequest request) {
            return decision;
        }
    }
}
