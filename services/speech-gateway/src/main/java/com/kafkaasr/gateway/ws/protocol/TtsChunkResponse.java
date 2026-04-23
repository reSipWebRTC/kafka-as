package com.kafkaasr.gateway.ws.protocol;

public record TtsChunkResponse(
        String type,
        String sessionId,
        long seq,
        String audioBase64,
        String codec,
        int sampleRate,
        int chunkSeq,
        boolean lastChunk) {
}
