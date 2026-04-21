package com.kafkaasr.gateway.ws.protocol;

public class MessageValidationException extends RuntimeException {

    private final String code;
    private final String sessionId;

    public MessageValidationException(String code, String message, String sessionId) {
        super(message);
        this.code = code;
        this.sessionId = sessionId;
    }

    public MessageValidationException(String code, String message, String sessionId, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.sessionId = sessionId;
    }

    public String code() {
        return code;
    }

    public String sessionId() {
        return sessionId;
    }
}
