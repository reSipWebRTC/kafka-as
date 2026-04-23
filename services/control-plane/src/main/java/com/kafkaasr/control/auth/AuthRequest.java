package com.kafkaasr.control.auth;

import org.springframework.http.HttpMethod;

public record AuthRequest(
        String authorizationHeader,
        HttpMethod method,
        String tenantId) {

    public AuthRequest {
        tenantId = tenantId == null ? "" : tenantId.trim();
    }
}
