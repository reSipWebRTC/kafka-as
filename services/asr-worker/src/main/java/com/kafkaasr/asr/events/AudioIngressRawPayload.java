package com.kafkaasr.asr.events;

public record AudioIngressRawPayload(
        String audioCodec,
        int sampleRate,
        int channels,
        String audioBase64,
        boolean endOfStream) {
}
