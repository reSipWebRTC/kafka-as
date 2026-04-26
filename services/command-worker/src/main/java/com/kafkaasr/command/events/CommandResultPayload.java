package com.kafkaasr.command.events;

public record CommandResultPayload(
        String executionId,
        String executionMode,
        String status,
        String code,
        String replyText,
        boolean retryable,
        Integer confirmRound,
        Integer maxConfirmRounds,
        String confirmToken,
        String rejectReason,
        String errorCode,
        Integer expiresInSec,
        String intent,
        String subIntent) {
}
