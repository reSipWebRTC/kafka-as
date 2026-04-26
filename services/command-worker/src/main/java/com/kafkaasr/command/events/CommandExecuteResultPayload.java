package com.kafkaasr.command.events;

public record CommandExecuteResultPayload(
        String executionId,
        String executionMode,
        String status,
        String code,
        String replyText,
        boolean retryable,
        Integer confirmRound,
        String confirmToken,
        String rejectReason,
        String errorCode,
        String traceId) {
}
