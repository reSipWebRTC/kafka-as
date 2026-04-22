package com.kafkaasr.asr.pipeline;

import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.events.AudioIngressRawPayload;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class PlaceholderAsrInferenceEngine implements AsrInferenceEngine {

    @Override
    public AsrInferenceResult infer(AudioIngressRawEvent ingressEvent) {
        AudioIngressRawPayload payload = ingressEvent.payload();
        if (payload == null) {
            throw new IllegalArgumentException("Missing audio payload for session " + ingressEvent.sessionId());
        }

        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(payload.audioBase64());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid audioBase64 payload for session " + ingressEvent.sessionId(), exception);
        }

        String text = "[placeholder] codec=" + payload.audioCodec() + ", bytes=" + audioBytes.length;
        String language = "und";
        double confidence = payload.endOfStream() ? 0.95d : 0.6d;
        boolean stable = payload.endOfStream();
        return new AsrInferenceResult(text, language, confidence, stable);
    }
}
