package com.kafkaasr.gateway.ingress;

public record AudioFrameIngressCommand(
        String sessionId,
        long seq,
        String codec,
        int sampleRate,
        byte[] audioBytes,
        String tenantId,
        String userId,
        String traceId) {

    public AudioFrameIngressCommand(
            String sessionId,
            long seq,
            String codec,
            int sampleRate,
            byte[] audioBytes) {
        this(sessionId, seq, codec, sampleRate, audioBytes, null, null, null);
    }

    public AudioFrameIngressCommand withSessionContext(
            String tenantId,
            String userId,
            String traceId) {
        return new AudioFrameIngressCommand(
                sessionId,
                seq,
                codec,
                sampleRate,
                audioBytes,
                tenantId,
                userId,
                traceId);
    }
}
