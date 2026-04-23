package com.kafkaasr.control.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class ModeSwitchAuthBackend implements AuthBackend {

    private final StaticAuthBackend staticAuthBackend;
    private final ExternalIamAuthBackend externalIamAuthBackend;
    private final ControlPlaneAuthProperties authProperties;
    private final AuthMetricsRecorder metricsRecorder;

    @Autowired
    public ModeSwitchAuthBackend(
            StaticAuthBackend staticAuthBackend,
            ExternalIamAuthBackend externalIamAuthBackend,
            ControlPlaneAuthProperties authProperties,
            AuthMetricsRecorder metricsRecorder) {
        this.staticAuthBackend = staticAuthBackend;
        this.externalIamAuthBackend = externalIamAuthBackend;
        this.authProperties = authProperties;
        this.metricsRecorder = metricsRecorder;
    }

    ModeSwitchAuthBackend(
            StaticAuthBackend staticAuthBackend,
            ExternalIamAuthBackend externalIamAuthBackend,
            ControlPlaneAuthProperties authProperties) {
        this(staticAuthBackend, externalIamAuthBackend, authProperties, AuthMetricsRecorder.noop());
    }

    @Override
    public AuthDecision authorize(AuthRequest request) {
        long startedAtNanos = System.nanoTime();
        ControlPlaneAuthProperties.Mode mode = authProperties.getMode();
        AuthDecision decision = AuthDecision.unauthorized(request.tenantId(), "INTERNAL_ERROR");
        try {
            if (mode == ControlPlaneAuthProperties.Mode.EXTERNAL_IAM) {
                decision = externalIamAuthBackend.authorize(request);
                return decision;
            }

            if (mode == ControlPlaneAuthProperties.Mode.HYBRID) {
                AuthDecision externalDecision = externalIamAuthBackend.authorize(request);
                if (externalDecision.outcome() == AuthOutcome.ALLOW
                        || externalDecision.outcome() == AuthOutcome.FORBIDDEN) {
                    decision = externalDecision;
                    return decision;
                }
                if (shouldFallbackToStatic(externalDecision.reason())) {
                    metricsRecorder.recordHybridFallback(externalDecision.reason());
                    decision = staticAuthBackend.authorize(request);
                    return decision;
                }
                decision = externalDecision;
                return decision;
            }

            decision = staticAuthBackend.authorize(request);
            return decision;
        } finally {
            metricsRecorder.recordDecision(
                    "mode-switch",
                    mode,
                    decision,
                    System.nanoTime() - startedAtNanos);
        }
    }

    private boolean shouldFallbackToStatic(String reason) {
        return "EXTERNAL_TOKEN_INVALID".equals(reason)
                || "EXTERNAL_UNAVAILABLE".equals(reason);
    }
}
