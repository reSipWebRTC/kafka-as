package com.kafkaasr.asr.events;

public record AsrFinalPayload(
        String text,
        String language,
        double confidence,
        boolean stable) {
}
