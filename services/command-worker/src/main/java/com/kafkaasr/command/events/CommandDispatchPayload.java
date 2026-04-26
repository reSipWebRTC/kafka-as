package com.kafkaasr.command.events;

public record CommandDispatchPayload(
        String executionId,
        String executionMode,
        String commandText,
        String intent,
        String subIntent,
        boolean confirmRequired,
        Integer confirmRound,
        Integer maxConfirmRounds,
        String confirmToken,
        Integer expiresInSec,
        String traceId) {
}
