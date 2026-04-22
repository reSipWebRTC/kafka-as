package com.kafkaasr.gateway.ws.downlink.events;

public record AsrPartialPayload(
        String text,
        String language,
        double confidence,
        boolean stable) {
}
