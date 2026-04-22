package com.kafkaasr.orchestrator.service;

import org.springframework.http.HttpStatus;

public class SessionControlException extends RuntimeException {

    private final String code;
    private final String sessionId;
    private final HttpStatus status;

    private SessionControlException(String code, String message, String sessionId, HttpStatus status) {
        super(message);
        this.code = code;
        this.sessionId = sessionId;
        this.status = status;
    }

    public static SessionControlException sessionNotFound(String sessionId) {
        return new SessionControlException(
                "SESSION_NOT_FOUND",
                "Session does not exist: " + sessionId,
                sessionId,
                HttpStatus.NOT_FOUND);
    }

    public static SessionControlException conflict(String code, String message, String sessionId) {
        return new SessionControlException(code, message, sessionId, HttpStatus.CONFLICT);
    }

    public static SessionControlException invalidMessage(String message, String sessionId) {
        return new SessionControlException("INVALID_MESSAGE", message, sessionId, HttpStatus.BAD_REQUEST);
    }

    public String code() {
        return code;
    }

    public String sessionId() {
        return sessionId;
    }

    public HttpStatus status() {
        return status;
    }
}
