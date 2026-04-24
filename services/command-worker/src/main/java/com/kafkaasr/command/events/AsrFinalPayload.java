package com.kafkaasr.command.events;

public record AsrFinalPayload(
        String text,
        String language,
        double confidence,
        boolean stable) {
}
