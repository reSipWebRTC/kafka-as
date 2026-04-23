package com.kafkaasr.asr.pipeline;

public class AsrEngineException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public AsrEngineException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public AsrEngineException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
