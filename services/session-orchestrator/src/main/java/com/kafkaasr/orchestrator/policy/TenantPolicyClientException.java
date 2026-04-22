package com.kafkaasr.orchestrator.policy;

public class TenantPolicyClientException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        REJECTED,
        UNAVAILABLE
    }

    private final Kind kind;

    private TenantPolicyClientException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static TenantPolicyClientException notFound(String message) {
        return new TenantPolicyClientException(Kind.NOT_FOUND, message, null);
    }

    public static TenantPolicyClientException rejected(String message) {
        return new TenantPolicyClientException(Kind.REJECTED, message, null);
    }

    public static TenantPolicyClientException unavailable(String message, Throwable cause) {
        return new TenantPolicyClientException(Kind.UNAVAILABLE, message, cause);
    }

    public Kind kind() {
        return kind;
    }
}
