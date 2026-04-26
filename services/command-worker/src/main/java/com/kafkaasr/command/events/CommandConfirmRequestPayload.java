package com.kafkaasr.command.events;

public record CommandConfirmRequestPayload(
        String executionId,
        String executionMode,
        String confirmToken,
        boolean accept,
        Integer confirmRound,
        String rejectReason,
        String traceId) {
}
