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
    private final AuthMetricsRecorder metricsRecorder;

    @Autowired
    public ExternalIamAuthBackend(ControlPlaneAuthProperties authProperties, AuthMetricsRecorder metricsRecorder) {
        this(authProperties, new JwksExternalIamTokenDecoder(authProperties.getExternal()), metricsRecorder);
    }

    ExternalIamAuthBackend(ControlPlaneAuthProperties authProperties, ExternalIamTokenDecoder tokenDecoder) {
        this(authProperties, tokenDecoder, AuthMetricsRecorder.noop());
    }

    ExternalIamAuthBackend(
            ControlPlaneAuthProperties authProperties,
            ExternalIamTokenDecoder tokenDecoder,
            AuthMetricsRecorder metricsRecorder) {
        this.authProperties = authProperties;
        this.tokenDecoder = tokenDecoder;
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

            Map<String, Object> claims;
            try {
                claims = tokenDecoder.decode(token);
            } catch (ExternalIamTokenDecoderException exception) {
                if (exception.category() == ExternalIamTokenDecoderException.Category.UNAVAILABLE) {
                    decision = AuthDecision.unauthorized(request.tenantId(), "EXTERNAL_UNAVAILABLE");
                    return decision;
                }
                decision = AuthDecision.unauthorized(request.tenantId(), "EXTERNAL_TOKEN_INVALID");
                return decision;
            }

            if (!hasPermissionForMethod(claims, request.method())) {
                decision = AuthDecision.forbidden(request.tenantId(), "OPERATION_DENIED");
                return decision;
            }

            if (!allowsTenant(claims, request.tenantId())) {
                decision = AuthDecision.forbidden(request.tenantId(), "TENANT_SCOPE_DENIED");
                return decision;
            }

            decision = AuthDecision.allow(request.tenantId());
            return decision;
        } finally {
            metricsRecorder.recordDecision(
                    "external-iam",
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
