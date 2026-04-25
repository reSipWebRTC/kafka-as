package com.kafkaasr.orchestrator.session;

public enum SessionProgressMarker {
    ASR_PARTIAL("asr.partial", SessionStatus.ASR_ACTIVE),
    ASR_FINAL("asr.final", SessionStatus.ASR_ACTIVE),
    TRANSLATION_RESULT("translation.result", SessionStatus.TRANSLATING),
    TTS_READY("tts.ready", SessionStatus.TTS_ACTIVE),
    COMMAND_RESULT("command.result", SessionStatus.DRAINING);

    private final String eventType;
    private final SessionStatus status;

    SessionProgressMarker(String eventType, SessionStatus status) {
        this.eventType = eventType;
        this.status = status;
    }

    public String eventType() {
        return eventType;
    }

    public SessionStatus toStatus() {
        return status;
    }
}
