package com.kafkaasr.translation.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.translation.events.AsrFinalEvent;
import com.kafkaasr.translation.events.AsrFinalPayload;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class OpenaiTranslationEngineTests {

    @Test
    void translateParsesChatCompletionsAndUsesAuthorizationHeader() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"choices\":[{\"message\":{\"content\":\"hello world\"}}]}")
                    .build());
        };

        OpenaiTranslationEngine engine = new OpenaiTranslationEngine(
                openaiProperties("sk-test"),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper());

        TranslationEngine.TranslationResult result = engine.translate(sampleAsrFinalEvent("zh-CN"), "en-US");

        assertEquals("hello world", result.translatedText());
        assertEquals("zh-CN", result.sourceLang());
        assertEquals("en-US", result.targetLang());
        assertEquals("openai-translation", result.engine());
        assertEquals(HttpMethod.POST, capturedRequest.get().method());
        assertEquals("/v1/chat/completions", capturedRequest.get().url().getPath());
        assertEquals("Bearer sk-test", capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void translateParsesResponsesApiShapeAndFallsBackLanguage() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"output\":[{\"content\":[{\"text\":\"bonjour\"}]}]}")
                .build());

        OpenaiTranslationEngine engine = new OpenaiTranslationEngine(
                openaiProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper());

        TranslationEngine.TranslationResult result = engine.translate(sampleAsrFinalEvent(""), "fr-FR");

        assertEquals("bonjour", result.translatedText());
        assertEquals("und", result.sourceLang());
        assertEquals("fr-FR", result.targetLang());
        assertEquals("openai-translation", result.engine());
    }

    @Test
    void translateOmitsAuthorizationHeaderWhenApiKeyBlank() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}")
                    .build());
        };

        OpenaiTranslationEngine engine = new OpenaiTranslationEngine(
                openaiProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper());

        engine.translate(sampleAsrFinalEvent("zh-CN"), "en-US");
        assertNull(capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void translateRejectsEmptyOpenaiResponse() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{}")
                .build());

        OpenaiTranslationEngine engine = new OpenaiTranslationEngine(
                openaiProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> engine.translate(sampleAsrFinalEvent("zh-CN"), "en-US"));
        assertTrue(exception.getMessage().contains("Empty OpenAI translated text"));
    }

    private static TranslationEngineProperties openaiProperties(String apiKey) {
        TranslationEngineProperties properties = new TranslationEngineProperties();
        properties.setMode("openai");

        TranslationEngineProperties.Openai openai = properties.getOpenai();
        openai.setEndpoint("https://api.openai.com");
        openai.setPath("/v1/chat/completions");
        openai.setTimeoutMs(2500L);
        openai.setApiKey(apiKey);
        openai.setModel("gpt-4o-mini");
        openai.setTemperature(0.0d);
        openai.setMaxTokens(256);
        openai.setEngineName("openai-translation");
        openai.setSystemPrompt("Translate only.");
        return properties;
    }

    private static AsrFinalEvent sampleAsrFinalEvent(String sourceLanguage) {
        return new AsrFinalEvent(
                "evt-in-1",
                "asr.final",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                "room-1",
                "asr-worker",
                7L,
                1713744000000L,
                "sess-1:asr.final:7",
                new AsrFinalPayload("你好", sourceLanguage, 0.9d, true));
    }
}
