package com.kafkaasr.gateway.ws.protocol;

public record SubtitlePartialResponse(
        String type,
        String sessionId,
        long seq,
        String text) {
}
