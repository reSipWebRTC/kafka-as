package com.kafkaasr.asr.events;

public record AsrPartialPayload(
        String text,
        String language,
        double confidence,
        boolean stable) {
}
