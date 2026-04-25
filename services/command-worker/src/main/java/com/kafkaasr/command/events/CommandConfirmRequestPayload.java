package com.kafkaasr.command.events;

public record CommandConfirmRequestPayload(
        String confirmToken,
        boolean accept) {
}
