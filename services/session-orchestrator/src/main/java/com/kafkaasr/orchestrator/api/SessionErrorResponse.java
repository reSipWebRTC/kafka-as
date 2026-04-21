package com.kafkaasr.orchestrator.api;

public record SessionErrorResponse(
        String code,
        String message,
        String sessionId) {
}
