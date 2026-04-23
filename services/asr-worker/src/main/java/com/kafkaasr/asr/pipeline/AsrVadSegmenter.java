package com.kafkaasr.asr.pipeline;

import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.events.AudioIngressRawPayload;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class AsrVadSegmenter {

    private final AsrInferenceProperties.Vad properties;
    private final Base64.Decoder base64Decoder = Base64.getDecoder();
    private final Map<String, SessionVadState> sessionStates = new ConcurrentHashMap<>();

    AsrVadSegmenter(AsrInferenceProperties.Vad properties) {
        this.properties = properties;
    }

    VadDecision evaluate(AudioIngressRawEvent ingressEvent, String inferenceText) {
        if (!properties.isEnabled() || ingressEvent == null || ingressEvent.payload() == null) {
            return VadDecision.noFinal();
        }

        AudioIngressRawPayload payload = ingressEvent.payload();
        if (payload.endOfStream() || !supportsCodec(payload.audioCodec())) {
            clearSession(ingressEvent.sessionId());
            return VadDecision.noFinal();
        }

        String sessionId = ingressEvent.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return VadDecision.noFinal();
        }

        SessionVadState state = sessionStates.computeIfAbsent(sessionId, ignored -> new SessionVadState());
        synchronized (state) {
            String normalizedText = normalizeText(inferenceText);
            if (!normalizedText.isEmpty()) {
                state.lastNonEmptyText = normalizedText;
            }

            boolean silentFrame = isSilentFrame(payload.audioBase64());
            if (silentFrame) {
                state.consecutiveSilenceFrames++;
            } else {
                state.consecutiveSilenceFrames = 0;
                state.activeFramesInSegment++;
            }

            boolean hasSpeechEvidence = state.activeFramesInSegment >= properties.getMinActiveFramesPerSegment()
                    || !state.lastNonEmptyText.isBlank();

            if (silentFrame
                    && hasSpeechEvidence
                    && state.consecutiveSilenceFrames >= properties.getSilenceFramesToFinalize()) {
                String finalText = normalizedText.isEmpty() ? state.lastNonEmptyText : normalizedText;
                state.reset();
                return new VadDecision(true, finalText);
            }

            return VadDecision.noFinal();
        }
    }

    void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionStates.remove(sessionId);
    }

    private boolean supportsCodec(String audioCodec) {
        return audioCodec != null && audioCodec.equalsIgnoreCase(properties.getAudioCodec());
    }

    private boolean isSilentFrame(String audioBase64) {
        if (audioBase64 == null || audioBase64.isBlank()) {
            return true;
        }

        byte[] audioBytes;
        try {
            audioBytes = base64Decoder.decode(audioBase64);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        if (audioBytes.length < 2) {
            return true;
        }

        int threshold = properties.getSilenceAmplitudeThreshold();
        for (int i = 0; i + 1 < audioBytes.length; i += 2) {
            int sample = (short) ((audioBytes[i] & 0xFF) | (audioBytes[i + 1] << 8));
            if (Math.abs(sample) > threshold) {
                return false;
            }
        }
        return true;
    }

    private String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text;
    }

    record VadDecision(boolean emitFinal, String finalText) {

        static VadDecision noFinal() {
            return new VadDecision(false, "");
        }
    }

    private static final class SessionVadState {

        private int consecutiveSilenceFrames = 0;
        private int activeFramesInSegment = 0;
        private String lastNonEmptyText = "";

        private void reset() {
            consecutiveSilenceFrames = 0;
            activeFramesInSegment = 0;
            lastNonEmptyText = "";
        }
    }
}
