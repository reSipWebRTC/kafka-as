package com.kafkaasr.gateway.ingress;

public record AudioIngressRawPayload(
        String audioCodec,
        int sampleRate,
        int channels,
        String audioBase64,
        boolean endOfStream) {
}
