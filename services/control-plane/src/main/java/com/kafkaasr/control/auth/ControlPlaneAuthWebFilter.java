package com.kafkaasr.control.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.control.api.ControlPlaneErrorResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ControlPlaneAuthWebFilter implements WebFilter {

    private static final String AUTH_INVALID_TOKEN_CODE = "AUTH_INVALID_TOKEN";
    private static final String AUTH_FORBIDDEN_CODE = "AUTH_FORBIDDEN";
    private static final String INVALID_TOKEN_MESSAGE = "Invalid or missing access token";
    private static final String FORBIDDEN_MESSAGE = "Insufficient permissions for tenant policy operation";
    private static final String TENANT_API_PREFIX = "/api/v1/tenants/";

    private final AuthBackend authBackend;
    private final ObjectMapper objectMapper;

    public ControlPlaneAuthWebFilter(AuthBackend authBackend, ObjectMapper objectMapper) {
        this.authBackend = authBackend;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!requiresAuth(exchange)) {
            return chain.filter(exchange);
        }

        AuthDecision decision = authBackend.authorize(new AuthRequest(
                exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                exchange.getRequest().getMethod(),
                extractTenantId(exchange)));
        if (decision.outcome() == AuthOutcome.ALLOW) {
            return chain.filter(exchange);
        }

        if (decision.outcome() == AuthOutcome.FORBIDDEN) {
            return rejectForbidden(exchange.getResponse(), decision.tenantId());
        }
        return rejectUnauthorized(exchange.getResponse());
    }

    private boolean requiresAuth(ServerWebExchange exchange) {
        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return false;
        }
        return exchange.getRequest().getPath().value().startsWith(TENANT_API_PREFIX);
    }

    private String extractTenantId(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(TENANT_API_PREFIX)) {
            return "";
        }
        String remainder = path.substring(TENANT_API_PREFIX.length());
        int slash = remainder.indexOf('/');
        String tenantId = slash >= 0 ? remainder.substring(0, slash) : remainder;
        return tenantId == null ? "" : tenantId.trim();
    }

    private Mono<Void> rejectUnauthorized(ServerHttpResponse response) {
        return writeError(
                response,
                HttpStatus.UNAUTHORIZED,
                AUTH_INVALID_TOKEN_CODE,
                INVALID_TOKEN_MESSAGE,
                "");
    }

    private Mono<Void> rejectForbidden(ServerHttpResponse response, String tenantId) {
        return writeError(
                response,
                HttpStatus.FORBIDDEN,
                AUTH_FORBIDDEN_CODE,
                FORBIDDEN_MESSAGE,
                tenantId == null ? "" : tenantId);
    }

    private Mono<Void> writeError(
            ServerHttpResponse response,
            HttpStatus status,
            String code,
            String message,
            String tenantId) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] payload = serializeErrorPayload(code, message, tenantId);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(payload)));
    }

    private byte[] serializeErrorPayload(String code, String message, String tenantId) {
        try {
            return objectMapper.writeValueAsBytes(new ControlPlaneErrorResponse(
                    code,
                    message,
                    tenantId));
        } catch (JsonProcessingException exception) {
            String safeCode = code.replace("\"", "");
            String safeMessage = message.replace("\"", "");
            String safeTenantId = (tenantId == null ? "" : tenantId).replace("\"", "");
            return ("{\"code\":\"" + safeCode + "\",\"message\":\"" + safeMessage + "\",\"tenantId\":\""
                            + safeTenantId + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
