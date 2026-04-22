package com.kafkaasr.gateway.ws.downlink.events;

public record AsrFinalPayload(
        String text,
        String language,
        double confidence,
        boolean stable) {
}
