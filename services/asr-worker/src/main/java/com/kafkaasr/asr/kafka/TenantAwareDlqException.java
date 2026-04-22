package com.kafkaasr.asr.kafka;

class TenantAwareDlqException extends RuntimeException {

    private final String dlqTopicSuffix;

    TenantAwareDlqException(String dlqTopicSuffix, Throwable cause) {
        super(cause);
        this.dlqTopicSuffix = dlqTopicSuffix;
    }

    String dlqTopicSuffix() {
        return dlqTopicSuffix;
    }
}
