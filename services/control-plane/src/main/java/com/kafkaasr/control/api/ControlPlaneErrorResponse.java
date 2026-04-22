package com.kafkaasr.control.api;

public record ControlPlaneErrorResponse(
        String code,
        String message,
        String tenantId) {
}
