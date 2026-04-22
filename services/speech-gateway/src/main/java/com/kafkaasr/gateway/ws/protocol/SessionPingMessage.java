package com.kafkaasr.gateway.ws.protocol;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SessionPingMessage(
        @NotBlank(message = "type must not be blank")
        String type,

        @NotBlank(message = "sessionId must not be blank")
        String sessionId,

        @Min(value = 0, message = "ts must be greater than or equal to 0")
        long ts) {
}
