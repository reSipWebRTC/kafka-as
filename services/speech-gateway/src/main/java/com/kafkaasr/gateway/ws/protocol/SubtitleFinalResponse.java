package com.kafkaasr.gateway.ws.protocol;

public record SubtitleFinalResponse(
        String type,
        String sessionId,
        long seq,
        String text) {
}
