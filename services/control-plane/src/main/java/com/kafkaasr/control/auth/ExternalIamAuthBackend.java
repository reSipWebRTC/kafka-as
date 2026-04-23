package com.kafkaasr.control.auth;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringUtils;

@Component
public class ExternalIamAuthBackend implements AuthBackend {

    private static final String BEARER_PREFIX = "bearer ";

    private final ControlPlaneAuthProperties authProperties;
    private final ExternalIamTokenDecoder tokenDecoder;

    @Autowired
    public ExternalIamAuthBackend(ControlPlaneAuthProperties authProperties) {
        this(authProperties, new JwksExternalIamTokenDecoder(authProperties.getExternal()));
    }

    ExternalIamAuthBackend(ControlPlaneAuthProperties authProperties, ExternalIamTokenDecoder tokenDecoder) {
        this.authProperties = authProperties;
        this.tokenDecoder = tokenDecoder;
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

        Map<String, Object> claims;
        try {
            claims = tokenDecoder.decode(token);
        } catch (ExternalIamTokenDecoderException exception) {
            if (exception.category() == ExternalIamTokenDecoderException.Category.UNAVAILABLE) {
                return AuthDecision.unauthorized(request.tenantId(), "EXTERNAL_UNAVAILABLE");
            }
            return AuthDecision.unauthorized(request.tenantId(), "EXTERNAL_TOKEN_INVALID");
        }

        if (!hasPermissionForMethod(claims, request.method())) {
            return AuthDecision.forbidden(request.tenantId(), "OPERATION_DENIED");
        }

        if (!allowsTenant(claims, request.tenantId())) {
            return AuthDecision.forbidden(request.tenantId(), "TENANT_SCOPE_DENIED");
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
        return StringUtils.hasText(token) ? token : null;
    }

    private boolean hasPermissionForMethod(Map<String, Object> claims, HttpMethod method) {
        Set<String> permissions = normalizedSet(claims.get(authProperties.getExternal().getPermissionClaim()));
        if (permissions.isEmpty()) {
            return false;
        }

        if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
            return permissions.contains(authProperties.getExternal().getReadPermission());
        }
        return permissions.contains(authProperties.getExternal().getWritePermission());
    }

    private boolean allowsTenant(Map<String, Object> claims, String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return false;
        }

        Set<String> tenants = normalizedSet(claims.get(authProperties.getExternal().getTenantClaim()));
        if (tenants.isEmpty()) {
            return false;
        }

        return tenants.stream().anyMatch(allowed -> PatternMatchUtils.simpleMatch(allowed, tenantId));
    }

    private Set<String> normalizedSet(Object value) {
        Set<String> values = new LinkedHashSet<>();

        if (value instanceof String text) {
            for (String token : text.split("[,\\s]+")) {
                String normalized = token.trim();
                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }
            return values;
        }

        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item == null) {
                    continue;
                }
                String normalized = item.toString().trim();
                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }
        }

        return values;
    }
}
