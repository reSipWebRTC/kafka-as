package com.kafkaasr.command.state;

import com.kafkaasr.command.pipeline.CommandExecutionState;

public record CommandExecutionContext(
        String executionId,
        String sessionId,
        String tenantId,
        String roomId,
        String userId,
        String traceId,
        long startedAtEpochMs,
        String commandText,
        String intent,
        String subIntent,
        boolean confirmRequired,
        int confirmRound,
        int maxConfirmRounds,
        String confirmToken,
        CommandExecutionState state) {
}
