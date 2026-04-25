package com.kafkaasr.translation.events;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranslationRequestPayload(
        String sourceText,
        String sourceLang,
        String targetLang) {
}
