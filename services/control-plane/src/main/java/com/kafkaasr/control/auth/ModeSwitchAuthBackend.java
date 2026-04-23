package com.kafkaasr.control.auth;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class ModeSwitchAuthBackend implements AuthBackend {

    private final StaticAuthBackend staticAuthBackend;
    private final ExternalIamAuthBackend externalIamAuthBackend;
    private final ControlPlaneAuthProperties authProperties;

    public ModeSwitchAuthBackend(
            StaticAuthBackend staticAuthBackend,
            ExternalIamAuthBackend externalIamAuthBackend,
            ControlPlaneAuthProperties authProperties) {
        this.staticAuthBackend = staticAuthBackend;
        this.externalIamAuthBackend = externalIamAuthBackend;
        this.authProperties = authProperties;
    }

    @Override
    public AuthDecision authorize(AuthRequest request) {
        ControlPlaneAuthProperties.Mode mode = authProperties.getMode();

        if (mode == ControlPlaneAuthProperties.Mode.EXTERNAL_IAM) {
            return externalIamAuthBackend.authorize(request);
        }

        if (mode == ControlPlaneAuthProperties.Mode.HYBRID) {
            AuthDecision externalDecision = externalIamAuthBackend.authorize(request);
            if (externalDecision.outcome() == AuthOutcome.ALLOW
                    || externalDecision.outcome() == AuthOutcome.FORBIDDEN) {
                return externalDecision;
            }
            if (shouldFallbackToStatic(externalDecision.reason())) {
                return staticAuthBackend.authorize(request);
            }
            return externalDecision;
        }

        return staticAuthBackend.authorize(request);
    }

    private boolean shouldFallbackToStatic(String reason) {
        return "EXTERNAL_TOKEN_INVALID".equals(reason)
                || "EXTERNAL_UNAVAILABLE".equals(reason);
    }
}
