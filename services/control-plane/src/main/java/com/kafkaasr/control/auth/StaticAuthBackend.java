package com.kafkaasr.control.auth;

import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StaticAuthBackend implements AuthBackend {

    private static final String BEARER_PREFIX = "bearer ";

    private final ControlPlaneAuthProperties authProperties;
    private final AuthMetricsRecorder metricsRecorder;

    @Autowired
    public StaticAuthBackend(
            ControlPlaneAuthProperties authProperties,
            ObjectProvider<AuthMetricsRecorder> metricsRecorderProvider) {
        this(
                authProperties,
                metricsRecorderProvider.getIfAvailable(AuthMetricsRecorder::noop));
    }

    StaticAuthBackend(ControlPlaneAuthProperties authProperties) {
        this(authProperties, AuthMetricsRecorder.noop());
    }

    StaticAuthBackend(ControlPlaneAuthProperties authProperties, AuthMetricsRecorder metricsRecorder) {
        this.authProperties = authProperties;
        this.metricsRecorder = metricsRecorder;
    }

    @Override
    public AuthDecision authorize(AuthRequest request) {
        long startedAtNanos = System.nanoTime();
        AuthDecision decision = AuthDecision.unauthorized(request.tenantId(), "INTERNAL_ERROR");
        try {
            if (!authProperties.isEnabled()) {
                decision = AuthDecision.allow(request.tenantId());
                return decision;
            }

            String token = extractBearerToken(request.authorizationHeader());
            if (token == null) {
                decision = AuthDecision.unauthorized(request.tenantId(), "MISSING_OR_INVALID_TOKEN");
                return decision;
            }

            Set<String> legacyFullAccessTokens = authProperties.tokenSet();
            if (legacyFullAccessTokens.contains(token)) {
                decision = AuthDecision.allow(request.tenantId());
                return decision;
            }

            ControlPlaneAuthProperties.Credential credential = authProperties.findCredential(token).orElse(null);
            if (credential == null) {
                decision = AuthDecision.unauthorized(request.tenantId(), "TOKEN_NOT_FOUND");
                return decision;
            }

            if (!credential.allowsTenant(request.tenantId())) {
                decision = AuthDecision.forbidden(request.tenantId(), "TENANT_SCOPE_DENIED");
                return decision;
            }

            if (!hasPermissionForMethod(credential, request.method())) {
                decision = AuthDecision.forbidden(request.tenantId(), "OPERATION_DENIED");
                return decision;
            }

            decision = AuthDecision.allow(request.tenantId());
            return decision;
        } finally {
            metricsRecorder.recordDecision(
                    "static",
                    authProperties.getMode(),
                    decision,
                    System.nanoTime() - startedAtNanos);
        }
    }

    private String extractBearerToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }

        String headerValue = authorizationHeader.trim();
        if (!headerValue.toLowerCase(Locale.ROOT).startsWith(BEARER_PREFIX)) {
            return null;
        }

        String token = headerValue.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            return null;
        }
        return token;
    }

    private boolean hasPermissionForMethod(ControlPlaneAuthProperties.Credential credential, HttpMethod method) {
        if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
            return credential.isRead();
        }
        return credential.isWrite();
    }
}
