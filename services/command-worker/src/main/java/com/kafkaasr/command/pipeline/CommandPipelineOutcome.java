package com.kafkaasr.command.pipeline;

import com.kafkaasr.command.events.CommandDispatchEvent;
import com.kafkaasr.command.events.CommandResultEvent;

public record CommandPipelineOutcome(
        CommandDispatchEvent commandDispatchEvent,
        CommandResultEvent commandResultEvent) {

    public static CommandPipelineOutcome none() {
        return new CommandPipelineOutcome(null, null);
    }

    public static CommandPipelineOutcome dispatch(CommandDispatchEvent event) {
        return new CommandPipelineOutcome(event, null);
    }

    public static CommandPipelineOutcome result(CommandResultEvent event) {
        return new CommandPipelineOutcome(null, event);
    }
}
