package com.kafkaasr.control.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.control.api.ControlPlaneErrorResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ControlPlaneAuthWebFilter implements WebFilter {

    private static final String AUTH_INVALID_TOKEN_CODE = "AUTH_INVALID_TOKEN";
    private static final String BEARER_PREFIX = "bearer ";
    private static final String TENANT_API_PREFIX = "/api/v1/tenants/";

    private final ControlPlaneAuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public ControlPlaneAuthWebFilter(ControlPlaneAuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!requiresAuth(exchange)) {
            return chain.filter(exchange);
        }

        if (isAuthorized(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))) {
            return chain.filter(exchange);
        }

        return rejectUnauthorized(exchange.getResponse());
    }

    private boolean requiresAuth(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().startsWith(TENANT_API_PREFIX);
    }

    private boolean isAuthorized(String authorizationHeader) {
        if (!authProperties.isEnabled()) {
            return true;
        }

        Set<String> allowedTokens = authProperties.tokenSet();
        if (allowedTokens.isEmpty()) {
            return false;
        }

        String token = extractBearerToken(authorizationHeader);
        return token != null && allowedTokens.contains(token);
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

    private Mono<Void> rejectUnauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] payload = serializeErrorPayload();
        return response.writeWith(Mono.just(response.bufferFactory().wrap(payload)));
    }

    private byte[] serializeErrorPayload() {
        try {
            return objectMapper.writeValueAsBytes(new ControlPlaneErrorResponse(
                    AUTH_INVALID_TOKEN_CODE,
                    "Invalid or missing access token",
                    ""));
        } catch (JsonProcessingException exception) {
            return "{\"code\":\"AUTH_INVALID_TOKEN\",\"message\":\"Invalid or missing access token\",\"tenantId\":\"\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
