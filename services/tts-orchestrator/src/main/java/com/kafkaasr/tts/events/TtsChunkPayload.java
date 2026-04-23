package com.kafkaasr.tts.events;

public record TtsChunkPayload(
        String audioBase64,
        String codec,
        int sampleRate,
        int chunkSeq,
        boolean lastChunk) {
}
