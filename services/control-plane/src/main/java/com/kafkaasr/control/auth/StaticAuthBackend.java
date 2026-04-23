package com.kafkaasr.control.auth;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StaticAuthBackend implements AuthBackend {

    private static final String BEARER_PREFIX = "bearer ";

    private final ControlPlaneAuthProperties authProperties;

    public StaticAuthBackend(ControlPlaneAuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public AuthDecision authorize(AuthRequest request) {
        if (!authProperties.isEnabled()) {
            return AuthDecision.allow(request.tenantId());
        }

        String token = extractBearerToken(request.authorizationHeader());
        if (token == null) {
            return AuthDecision.unauthorized(request.tenantId(), "MISSING_OR_INVALID_TOKEN");
        }

        Set<String> legacyFullAccessTokens = authProperties.tokenSet();
        if (legacyFullAccessTokens.contains(token)) {
            return AuthDecision.allow(request.tenantId());
        }

        ControlPlaneAuthProperties.Credential credential = authProperties.findCredential(token).orElse(null);
        if (credential == null) {
            return AuthDecision.unauthorized(request.tenantId(), "TOKEN_NOT_FOUND");
        }

        if (!credential.allowsTenant(request.tenantId())) {
            return AuthDecision.forbidden(request.tenantId(), "TENANT_SCOPE_DENIED");
        }

        if (!hasPermissionForMethod(credential, request.method())) {
            return AuthDecision.forbidden(request.tenantId(), "OPERATION_DENIED");
        }

        return AuthDecision.allow(request.tenantId());
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
