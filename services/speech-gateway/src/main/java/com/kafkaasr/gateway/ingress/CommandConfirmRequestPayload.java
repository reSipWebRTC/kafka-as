package com.kafkaasr.gateway.ingress;

public record CommandConfirmRequestPayload(
        String confirmToken,
        boolean accept) {
}
