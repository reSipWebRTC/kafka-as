package com.kafkaasr.orchestrator.policy;

public record ControlPlaneErrorResponse(
        String code,
        String message,
        String tenantId) {
}
