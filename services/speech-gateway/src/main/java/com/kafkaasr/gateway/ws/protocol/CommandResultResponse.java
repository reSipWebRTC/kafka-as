package com.kafkaasr.gateway.ws.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandResultResponse(
        String type,
        String sessionId,
        long seq,
        String status,
        String code,
        String replyText,
        boolean retryable,
        String confirmToken,
        Long expiresInSec) {
}
