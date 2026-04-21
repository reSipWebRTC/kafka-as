package com.kafkaasr.gateway.ws.protocol;

public record SessionErrorResponse(
        String type,
        String sessionId,
        String code,
        String message) {
}

