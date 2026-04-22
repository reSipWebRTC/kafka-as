package com.kafkaasr.gateway.ws.downlink.events;

public record SessionControlPayload(
        String action,
        String status,
        String sourceLang,
        String targetLang,
        String reason) {
}
