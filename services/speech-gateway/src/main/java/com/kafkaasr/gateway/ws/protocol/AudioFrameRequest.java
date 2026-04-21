package com.kafkaasr.gateway.ws.protocol;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AudioFrameRequest(
        @NotBlank String type,
        @NotBlank String sessionId,
        @NotNull @Min(0) Long seq,
        @NotBlank String codec,
        @NotNull @Min(8000) Integer sampleRate,
        @NotBlank String audioBase64) {
}

