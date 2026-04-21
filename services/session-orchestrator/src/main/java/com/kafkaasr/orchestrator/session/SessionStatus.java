package com.kafkaasr.orchestrator.session;

public enum SessionStatus {
    INIT,
    CONNECTING,
    STREAMING,
    ASR_ACTIVE,
    TRANSLATING,
    TTS_ACTIVE,
    DRAINING,
    CLOSED,
    FAILED
}
