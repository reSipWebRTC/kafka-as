package com.kafkaasr.asr.pipeline;

import com.kafkaasr.asr.events.AudioIngressRawEvent;

public interface AsrInferenceEngine {

    AsrInferenceResult infer(AudioIngressRawEvent ingressEvent);

    record AsrInferenceResult(
            String text,
            String language,
            double confidence,
            boolean stable) {
    }
}
