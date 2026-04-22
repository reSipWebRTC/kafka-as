package com.kafkaasr.gateway.session;

import org.springframework.web.reactive.socket.CloseStatus;

public class SessionControlClientException extends RuntimeException {

    private final String code;
    private final String sessionId;
    private final CloseStatus closeStatus;

    public SessionControlClientException(String code, String message, String sessionId, CloseStatus closeStatus) {
        super(message);
        this.code = code;
        this.sessionId = sessionId;
        this.closeStatus = closeStatus;
    }

    public SessionControlClientException(
            String code,
            String message,
            String sessionId,
            CloseStatus closeStatus,
            Throwable cause) {
        super(message, cause);
        this.code = code;
        this.sessionId = sessionId;
        this.closeStatus = closeStatus;
    }

    public String code() {
        return code;
    }

    public String sessionId() {
        return sessionId;
    }

    public CloseStatus closeStatus() {
        return closeStatus;
    }

    public static SessionControlClientException unavailable(String sessionId, Throwable cause) {
        return new SessionControlClientException(
                "INTERNAL_ERROR",
                "Session control service unavailable",
                sessionId,
                CloseStatus.SERVER_ERROR,
                cause);
    }
}
