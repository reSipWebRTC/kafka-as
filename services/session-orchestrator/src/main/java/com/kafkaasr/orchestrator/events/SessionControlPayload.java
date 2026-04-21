package com.kafkaasr.orchestrator.events;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionControlPayload(
        String action,
        String status,
        String sourceLang,
        String targetLang,
        String reason) {
}
