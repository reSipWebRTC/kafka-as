package com.kafkaasr.gateway.ws.protocol;

public record SessionClosedResponse(
        String type,
        String sessionId,
        String reason) {
}
