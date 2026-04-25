package com.kafkaasr.command.pipeline;

public record SmartHomeApiResponse(
        String traceId,
        String code,
        String message,
        boolean retryable,
        String status,
        String replyText,
        String confirmToken,
        Integer expiresInSec,
        String intent,
        String subIntent) {

    public String normalizedStatus() {
        if (status == null || status.isBlank()) {
            if ("OK".equalsIgnoreCase(code)) {
                return "ok";
            }
            return "failed";
        }
        return status;
    }

    public String normalizedCode() {
        if (code == null || code.isBlank()) {
            return "INTERNAL_ERROR";
        }
        return code;
    }
}
