package com.kafkaasr.control.auth;

public record AuthDecision(
        AuthOutcome outcome,
        String tenantId,
        String reason) {

    public AuthDecision {
        tenantId = tenantId == null ? "" : tenantId;
        reason = reason == null ? "" : reason;
    }

    public static AuthDecision allow(String tenantId) {
        return new AuthDecision(AuthOutcome.ALLOW, tenantId, "ALLOW");
    }

    public static AuthDecision unauthorized(String tenantId, String reason) {
        return new AuthDecision(AuthOutcome.UNAUTHORIZED, tenantId, reason);
    }

    public static AuthDecision forbidden(String tenantId, String reason) {
        return new AuthDecision(AuthOutcome.FORBIDDEN, tenantId, reason);
    }
}
