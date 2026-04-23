package com.kafkaasr.control.service;

import org.springframework.http.HttpStatus;

public class ControlPlaneException extends RuntimeException {

    private final String code;
    private final String tenantId;
    private final HttpStatus status;

    private ControlPlaneException(String code, String message, String tenantId, HttpStatus status) {
        super(message);
        this.code = code;
        this.tenantId = tenantId;
        this.status = status;
    }

    public static ControlPlaneException tenantPolicyNotFound(String tenantId) {
        return new ControlPlaneException(
                "TENANT_POLICY_NOT_FOUND",
                "Tenant policy does not exist: " + tenantId,
                tenantId,
                HttpStatus.NOT_FOUND);
    }

    public static ControlPlaneException tenantPolicyRollbackNotAvailable(String tenantId) {
        return new ControlPlaneException(
                "TENANT_POLICY_ROLLBACK_NOT_AVAILABLE",
                "No previous tenant policy version available for rollback: " + tenantId,
                tenantId,
                HttpStatus.CONFLICT);
    }

    public static ControlPlaneException tenantPolicyVersionNotFound(String tenantId, long targetVersion) {
        return new ControlPlaneException(
                "TENANT_POLICY_VERSION_NOT_FOUND",
                "Tenant policy version does not exist: " + tenantId + " version=" + targetVersion,
                tenantId,
                HttpStatus.NOT_FOUND);
    }

    public static ControlPlaneException tenantPolicyRollbackVersionInvalid(
            String tenantId, long targetVersion, long currentVersion) {
        return new ControlPlaneException(
                "TENANT_POLICY_ROLLBACK_VERSION_INVALID",
                "Invalid rollback target version for tenant "
                        + tenantId
                        + ": targetVersion="
                        + targetVersion
                        + ", currentVersion="
                        + currentVersion,
                tenantId,
                HttpStatus.CONFLICT);
    }

    public static ControlPlaneException invalidMessage(String message, String tenantId) {
        return new ControlPlaneException("INVALID_MESSAGE", message, tenantId, HttpStatus.BAD_REQUEST);
    }

    public String code() {
        return code;
    }

    public String tenantId() {
        return tenantId;
    }

    public HttpStatus status() {
        return status;
    }
}
