package com.kafkaasr.command.pipeline;

public enum CommandExecutionState {
    DISPATCHED,
    WAIT_CONFIRM,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
