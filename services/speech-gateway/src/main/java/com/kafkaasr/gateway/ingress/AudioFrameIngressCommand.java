package com.kafkaasr.gateway.ingress;

public record AudioFrameIngressCommand(
        String sessionId,
        long seq,
        String codec,
        int sampleRate,
        byte[] audioBytes) {
}

