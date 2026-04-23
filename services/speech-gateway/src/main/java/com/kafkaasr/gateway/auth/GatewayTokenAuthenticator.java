package com.kafkaasr.gateway.auth;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GatewayTokenAuthenticator {

    private static final String BEARER_PREFIX = "bearer ";
    private final GatewayAuthProperties authProperties;

    public GatewayTokenAuthenticator(GatewayAuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public boolean isAuthorized(HandshakeInfo handshakeInfo) {
        if (!authProperties.isEnabled()) {
            return true;
        }

        Set<String> allowedTokens = authProperties.tokenSet();
        if (allowedTokens.isEmpty()) {
            return false;
        }

        String accessToken = resolveAccessToken(handshakeInfo);
        return accessToken != null && allowedTokens.contains(accessToken);
    }

    private String resolveAccessToken(HandshakeInfo handshakeInfo) {
        String bearerToken = extractBearerToken(handshakeInfo.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (bearerToken != null) {
            return bearerToken;
        }

        String queryParam = authProperties.getQueryParam();
        if (!StringUtils.hasText(queryParam)) {
            return null;
        }

        String queryToken = UriComponentsBuilder.fromUri(handshakeInfo.getUri())
                .build()
                .getQueryParams()
                .getFirst(queryParam);
        if (!StringUtils.hasText(queryToken)) {
            return null;
        }
        return queryToken.trim();
    }

    private String extractBearerToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }

        String headerValue = authorizationHeader.trim();
        String lowerHeaderValue = headerValue.toLowerCase(Locale.ROOT);
        if (!lowerHeaderValue.startsWith(BEARER_PREFIX)) {
            return null;
        }

        String token = headerValue.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            return null;
        }
        return token;
    }
}
