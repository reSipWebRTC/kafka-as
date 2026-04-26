package com.kafkaasr.command.events;

public record AsrFinalPayload(
        String text,
        String language,
        float confidence,
        boolean stable) {
}
