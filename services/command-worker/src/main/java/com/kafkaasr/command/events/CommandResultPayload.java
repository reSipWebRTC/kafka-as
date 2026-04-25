package com.kafkaasr.command.events;

public record CommandResultPayload(
        String status,
        String code,
        String replyText,
        String ttsText,
        boolean retryable,
        String confirmToken,
        Integer expiresInSec,
        String intent,
        String subIntent) {
}
